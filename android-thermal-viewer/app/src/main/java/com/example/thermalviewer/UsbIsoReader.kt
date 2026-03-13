package com.example.thermalviewer

/**
 * JNI wrapper around usb_iso.c.
 *
 * Android's Java UsbRequest API for isochronous endpoints is broken on many
 * OEM devices (Samsung in particular).  This native library submits URBs
 * directly via the Linux USBDEVFS ioctl interface, which is always available
 * as long as the app holds an open UsbDeviceConnection.
 */
object UsbIsoReader {
    init {
        System.loadLibrary("usb_iso")
    }

    /**
     * Allocate a URB pool and start streaming from [fd] / [epAddr].
     * Returns an opaque native handle (0 on failure).
     *
     * @param fd        File descriptor from UsbDeviceConnection.fileDescriptor
     * @param epAddr    USB endpoint address (e.g. 0x81 for EP 1 IN)
     * @param maxPacket Endpoint max packet size
     */
    external fun nativeOpen(fd: Int, epAddr: Int, maxPacket: Int): Long

    /**
     * Block until one complete YUYV frame has been accumulated into [frameBuf].
     *
     * @return Number of bytes written (== frameBuf.size on success),
     *         -1 on bad args, -2 on device disconnect.
     */
    external fun nativeReadFrame(handle: Long, frameBuf: ByteArray, frameSize: Int): Int

    /** Cancel all URBs and free native memory. */
    external fun nativeClose(handle: Long)
}
