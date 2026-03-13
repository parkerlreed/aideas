package com.example.thermalviewer

import android.hardware.usb.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread



private const val TAG = "InfirayP2Camera"

/**
 * Driver for the Infiray P2 Pro (and the Habotest HT820 clone) thermal camera.
 *
 * ## How the camera works
 *
 * The camera enumerates as a standard UVC (USB Video Class) device:
 *   VID 0x0bda (Realtek), PID 0x5830 (P2 Pro) or 0x5840 (HT820)
 *
 * It exposes two formats in its VideoStreaming interface:
 *   1. 256×192 YUYV  – a grey-scale preview of the thermal image
 *   2. 256×384 YUYV  – the preview on top PLUS raw temperature data underneath
 *
 * We request format 2 (256×384).  The bottom 256×192 pixels are not a real
 * image but 256*192 = 49 152 little-endian uint16 values, each representing
 * temperature in units of 1/64 Kelvin.
 *
 *   temperature_K   = raw_u16 / 64.0
 *   temperature_C   = raw_u16 / 64.0 − 273.15
 *
 * ## UVC streaming setup (abbreviated)
 *
 *   1. Find the VideoStreaming interface  (class=0x0E, subclass=0x02)
 *   2. VS_PROBE_CONTROL – negotiate format/frame/interval with the camera
 *   3. VS_COMMIT_CONTROL – lock in the negotiated parameters
 *   4. Select the alternate interface setting that has the isochronous IN endpoint
 *   5. Read isochronous USB packets; reassemble into full YUYV frames
 *   6. Extract uint16 thermal data from the second half of each frame
 */
class InfirayP2Camera(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) {
    companion object {
        val SUPPORTED_VID_PIDS = listOf(
            0x0bda to 0x5830,  // Infiray P2 Pro
            0x0bda to 0x5840,  // Habotest HT820 (clone)
        )

        fun findDevice(usbManager: UsbManager): UsbDevice? =
            usbManager.deviceList.values.firstOrNull { dev ->
                SUPPORTED_VID_PIDS.any { (vid, pid) -> dev.vendorId == vid && dev.productId == pid }
            }

        // Full YUYV frame dimensions:  256 wide × 384 tall (2 bytes/pixel YUYV)
        private const val FRAME_WIDTH  = THERMAL_WIDTH
        private const val FRAME_HEIGHT = THERMAL_HEIGHT * 2
        private const val FRAME_BYTES  = FRAME_WIDTH * FRAME_HEIGHT * 2  // 196 608

        // The thermal uint16 data begins at byte offset = top-half YUYV size
        private const val THERMAL_OFFSET = FRAME_WIDTH * THERMAL_HEIGHT * 2  // 98 304
    }

    // ---- UVC class-specific request constants ----
    private val RT_CLASS_IFACE_SET = 0x21   // bmRequestType: class | interface | host→dev
    private val RT_CLASS_IFACE_GET = 0xA1   // bmRequestType: class | interface | dev→host
    private val UVC_SET_CUR        = 0x01
    private val UVC_GET_MAX        = 0x83
    private val VS_PROBE_CONTROL   = 0x0100 // wValue for probe  (selector << 8)
    private val VS_COMMIT_CONTROL  = 0x0200 // wValue for commit (selector << 8)

    // ---- Callbacks ----
    /** Called on a background thread with each decoded [ThermalData] frame. */
    var onFrame: ((ThermalData) -> Unit)? = null
    /** Called on a background thread when a non-recoverable error occurs. */
    var onError: ((String) -> Unit)? = null

    // ---- Internal state ----
    private var connection: UsbDeviceConnection? = null
    private var streamIface: UsbInterface? = null
    @Volatile private var running = false
    private var workerThread: Thread? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Open the USB device, negotiate UVC streaming, and return true on success.
     * Call [startStreaming] afterwards to begin receiving frames.
     */
    fun open(): Boolean {
        val conn = usbManager.openDevice(device) ?: run {
            onError?.invoke("Cannot open USB device – was permission granted?")
            return false
        }
        connection = conn

        val iface = findBestStreamingInterface() ?: run {
            onError?.invoke("UVC VideoStreaming interface not found")
            conn.close(); return false
        }
        streamIface = iface

        if (!conn.claimInterface(iface, /* force= */ true)) {
            onError?.invoke("Cannot claim VideoStreaming interface")
            conn.close(); return false
        }

        // UVC sequence: probe/commit FIRST (device stays at alt-setting 0, zero-bandwidth),
        // then activate the streaming alt-setting.  Calling setInterface() before negotiation
        // causes some devices to reset to alt-0 mid-negotiation, leaving the isochronous
        // endpoint un-activated when we later try to initialize UsbRequests.
        if (!negotiateStreaming(conn, iface)) {
            onError?.invoke("UVC probe/commit failed")
            conn.releaseInterface(iface); conn.close(); return false
        }

        Log.i(TAG, "UVC streaming ready on ${device.deviceName}")
        return true
    }

    /** Begin reading frames in a background thread. */
    fun startStreaming() {
        running = true
        workerThread = thread(name = "ThermalStream", isDaemon = true) { streamLoop() }
    }

    /** Stop streaming and release all USB resources. */
    fun close() {
        running = false
        workerThread?.join(2_000)
        streamIface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null
    }

    // ------------------------------------------------------------------
    // UVC negotiation
    // ------------------------------------------------------------------

    /**
     * Find the VideoStreaming (class=0x0E, subclass=0x02) alternate setting
     * with the largest isochronous IN endpoint – that gives us the most bandwidth.
     */
    private fun findBestStreamingInterface(): UsbInterface? {
        var best: UsbInterface? = null
        var bestMaxPacket = 0
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != 0x0E || iface.interfaceSubclass != 0x02) continue
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    ep.type    == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    ep.maxPacketSize > bestMaxPacket) {
                    bestMaxPacket = ep.maxPacketSize
                    best = iface
                }
            }
        }
        // Fall back to any VS interface (covers bulk-streaming cameras)
        if (best == null) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 0x0E && iface.interfaceSubclass == 0x02)
                    return iface
            }
        }
        return best
    }

    /**
     * Build a 26-byte UVC VideoProbe/Commit control structure.
     *
     * @param formatIndex  1-based index of the format descriptor (1 = YUYV)
     * @param frameIndex   1-based index of the frame descriptor  (2 = 256×384)
     * @param frameInterval frame duration in 100 ns units         (400000 = 25 fps)
     */
    private fun buildControl(formatIndex: Int, frameIndex: Int, frameInterval: Int): ByteArray {
        return ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0)                    // bmHint
            put(formatIndex.toByte())      // bFormatIndex
            put(frameIndex.toByte())       // bFrameIndex
            putInt(frameInterval)          // dwFrameInterval
            putShort(0); putShort(0)       // wKeyFrameRate, wPFrameRate
            putShort(0); putShort(0)       // wCompQuality, wCompWindowSize
            putShort(0)                    // wDelay
            putInt(FRAME_BYTES)            // dwMaxVideoFrameSize (hint to camera)
            putInt(0)                      // dwMaxPayloadTransferSize
        }.array()
    }

    private fun negotiateStreaming(conn: UsbDeviceConnection, iface: UsbInterface): Boolean {
        val ifaceIdx = iface.id

        // 1. Query the camera's maximum capabilities
        val maxCtrl = ByteArray(26)
        conn.controlTransfer(RT_CLASS_IFACE_GET, UVC_GET_MAX,
            VS_PROBE_CONTROL, ifaceIdx, maxCtrl, maxCtrl.size, 1_000)

        // 2. Propose our desired format: YUYV (format 1), 256×384 (frame 2), 25 fps
        //    Frame index 2 because the 256×192 stream is typically frame 1.
        val proposed = buildControl(formatIndex = 1, frameIndex = 2, frameInterval = 400_000)
        conn.controlTransfer(RT_CLASS_IFACE_SET, UVC_SET_CUR,
            VS_PROBE_CONTROL, ifaceIdx, proposed, proposed.size, 1_000)

        // 3. Read back what the camera actually agreed to
        val negotiated = ByteArray(26)
        val ret = conn.controlTransfer(RT_CLASS_IFACE_GET, 0x81 /* GET_CUR */,
            VS_PROBE_CONTROL, ifaceIdx, negotiated, negotiated.size, 1_000)
        if (ret < 0) {
            Log.w(TAG, "GET_CUR probe failed ($ret), using proposed values")
            System.arraycopy(proposed, 0, negotiated, 0, proposed.size)
        } else {
            val bb = ByteBuffer.wrap(negotiated).order(ByteOrder.LITTLE_ENDIAN)
            val fmt   = bb.get(2).toInt() and 0xFF
            val frame = bb.get(3).toInt() and 0xFF
            bb.position(18)
            val maxFrameSize = bb.int
            Log.i(TAG, "Camera agreed: format=$fmt frame=$frame maxFrameSize=$maxFrameSize")
        }

        // 4. Commit the negotiated control
        val commitRet = conn.controlTransfer(RT_CLASS_IFACE_SET, UVC_SET_CUR,
            VS_COMMIT_CONTROL, ifaceIdx, negotiated, negotiated.size, 1_000)
        if (commitRet < 0) {
            Log.e(TAG, "VS_COMMIT_CONTROL failed: $commitRet")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // Streaming loop
    // ------------------------------------------------------------------

    private fun streamLoop() {
        val conn   = connection ?: return
        val iface  = streamIface ?: return

        // Find isochronous IN endpoint; fall back to bulk
        var isoEp:  UsbEndpoint? = null
        var bulkEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction != UsbConstants.USB_DIR_IN) continue
            when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> isoEp  = ep
                UsbConstants.USB_ENDPOINT_XFER_BULK -> bulkEp = ep
            }
        }

        when {
            isoEp  != null -> readIsochronous(conn, isoEp)
            bulkEp != null -> readBulk(conn, bulkEp)
            else           -> onError?.invoke("No suitable IN endpoint on streaming interface")
        }
    }

    // ------------------------------------------------------------------
    // Isochronous reading via native USBDEVFS ioctls
    // ------------------------------------------------------------------
    //
    // Android's Java UsbRequest API for isochronous endpoints is broken on
    // many OEM devices.  usb_iso.c uses USBDEVFS_SUBMITURB / REAPURB ioctls
    // directly, which always works as long as we hold the file descriptor.
    //
    private fun readIsochronous(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        // Activate the streaming alt-setting so the endpoint has USB bandwidth.
        val iface = streamIface!!
        if (!conn.setInterface(iface)) {
            onError?.invoke("setInterface() failed before streaming")
            return
        }
        Log.d(TAG, "setInterface OK, maxPacket=${endpoint.maxPacketSize}")

        val fd     = conn.fileDescriptor
        val epAddr = endpoint.address

        // Android returns the RAW wMaxPacketSize descriptor value.
        // For USB 2.0 high-speed isochronous, bits [12:11] encode additional
        // transactions per microframe; the kernel decodes this and rejects
        // iso_frame_desc[i].length > decoded size with EMSGSIZE.
        //   bits[10:0]  = base packet size (e.g. 1024)
        //   bits[12:11] = additional transactions (0=1×, 1=2×, 2=3×)
        // Decoded = (1 + bits[12:11]) × bits[10:0]
        val rawPkt    = endpoint.maxPacketSize          // e.g. 5120 = 0x1400
        val mult      = 1 + ((rawPkt shr 11) and 0x3)  // e.g. 3
        val actualPkt = mult * (rawPkt and 0x7FF)       // e.g. 3 × 1024 = 3072
        Log.d(TAG, "rawMaxPacket=$rawPkt → actualMaxPacket=$actualPkt (mult=$mult)")

        val frameBuf = ByteArray(FRAME_BYTES)
        val handle = UsbIsoReader.nativeOpen(fd, epAddr, actualPkt)
        if (handle == 0L) {
            onError?.invoke("UsbIsoReader.nativeOpen() failed")
            return
        }
        Log.i(TAG, "Native ISO reader opened (handle=$handle)")

        try {
            while (running) {
                val n = UsbIsoReader.nativeReadFrame(handle, frameBuf, FRAME_BYTES)
                when {
                    n == FRAME_BYTES -> dispatchFrame(frameBuf)
                    n == -2          -> { onError?.invoke("USB device disconnected"); break }
                    n < 0            -> { if (running) onError?.invoke("nativeReadFrame error $n"); break }
                    // partial frame — keep looping
                }
            }
        } finally {
            UsbIsoReader.nativeClose(handle)
        }
    }

    // ------------------------------------------------------------------
    // Bulk reading (fallback – some cameras or custom firmware use this)
    // ------------------------------------------------------------------

    private fun readBulk(conn: UsbDeviceConnection, endpoint: UsbEndpoint) {
        conn.setInterface(streamIface!!)
        val buf = ByteArray(FRAME_BYTES)
        var pos = 0
        while (running) {
            val n = conn.bulkTransfer(endpoint, buf, pos, buf.size - pos, 1_000)
            if (n < 0) {
                if (running) onError?.invoke("USB bulk read error: $n")
                break
            }
            pos += n
            if (pos >= FRAME_BYTES) {
                dispatchFrame(buf)
                pos = 0
            }
        }
    }

    // ------------------------------------------------------------------
    // Frame parsing
    // ------------------------------------------------------------------

    /**
     * Extract the 256×192 uint16 thermal values from the bottom half of a
     * 256×384 YUYV frame and deliver them to [onFrame].
     *
     * Memory layout of the full 256×384 YUYV frame (2 bytes per pixel):
     *
     *   bytes   0 … 98303  = 256×192 YUYV greyscale preview  (ignored)
     *   bytes 98304 … 196607 = 256×192 little-endian uint16 temperatures
     *
     * Each uint16 value encodes temperature as: T_kelvin = value / 64.0
     */
    private fun dispatchFrame(yuyvFrame: ByteArray) {
        if (yuyvFrame.size < THERMAL_OFFSET + THERMAL_WIDTH * THERMAL_HEIGHT * 2) return

        val temps = ShortArray(THERMAL_WIDTH * THERMAL_HEIGHT)
        val bb = ByteBuffer.wrap(yuyvFrame, THERMAL_OFFSET, THERMAL_WIDTH * THERMAL_HEIGHT * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in temps.indices) temps[i] = bb.short

        onFrame?.invoke(ThermalData(temps))
    }
}
