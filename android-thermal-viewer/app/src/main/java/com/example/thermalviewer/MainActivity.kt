package com.example.thermalviewer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

private const val ACTION_USB_PERMISSION = "com.example.thermalviewer.USB_PERMISSION"

class MainActivity : AppCompatActivity() {

    // ---- Views ----
    private lateinit var thermalView: ThermalView
    private lateinit var statusText: TextView
    private lateinit var controlBar: View
    private lateinit var paletteSpinner: Spinner
    private lateinit var btnCenter: MaterialButton
    private lateinit var btnMinMax: MaterialButton
    private lateinit var tempCenter: TextView
    private lateinit var tempMin: TextView
    private lateinit var tempMax: TextView

    // ---- Camera ----
    private var camera: InfirayP2Camera? = null
    private lateinit var usbManager: UsbManager

    // ---- UI state ----
    private var showCenter = true
    private var showMinMax = true

    // Pending USB device to connect once camera permission is granted
    private var pendingDevice: UsbDevice? = null

    // Runtime CAMERA permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission just granted – proceed with whatever device was waiting
            pendingDevice?.let { requestUsbPermission(it) }
                ?: InfirayP2Camera.findDevice(usbManager)?.let { requestUsbPermission(it) }
        } else {
            setStatus("Camera permission is required to access the thermal camera")
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        thermalView  = findViewById(R.id.thermalView)
        statusText   = findViewById(R.id.statusText)
        controlBar   = findViewById(R.id.controlBar)
        paletteSpinner = findViewById(R.id.paletteSpinner)
        btnCenter    = findViewById(R.id.btnCenter)
        btnMinMax    = findViewById(R.id.btnMinMax)
        tempCenter   = findViewById(R.id.tempCenter)
        tempMin      = findViewById(R.id.tempMin)
        tempMax      = findViewById(R.id.tempMax)

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        setupPaletteSpinner()
        setupToggleButtons()
        registerUsbReceiver()

        thermalView.setOnClickListener { toggleControlBar() }

        // If we were launched by a USB-attached intent, handle it now
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttachIntent(intent)
        } else {
            // Otherwise look for an already-connected camera
            InfirayP2Camera.findDevice(usbManager)?.let { requestPermissionAndConnect(it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttachIntent(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectCamera()
        unregisterReceiver(usbReceiver)
    }

    // ------------------------------------------------------------------
    // USB permission & connection
    // ------------------------------------------------------------------

    private fun handleUsbAttachIntent(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: InfirayP2Camera.findDevice(usbManager) ?: return

        requestPermissionAndConnect(device)
    }

    /**
     * Entry point for connecting to a device.
     * Ensures the CAMERA runtime permission is granted before requesting USB access —
     * Android requires this permission to open a USB UVC (video) device.
     */
    private fun requestPermissionAndConnect(device: UsbDevice) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDevice = device
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            requestUsbPermission(device)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        pendingDevice = null
        if (usbManager.hasPermission(device)) {
            connectCamera(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, pi)
            setStatus("Requesting USB permission…")
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (granted && device != null) connectCamera(device)
                    else setStatus("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && InfirayP2Camera.SUPPORTED_VID_PIDS
                            .any { (v, p) -> device.vendorId == v && device.productId == p }) {
                        disconnectCamera()
                        setStatus("Camera disconnected")
                    }
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    // ------------------------------------------------------------------
    // Camera management
    // ------------------------------------------------------------------

    private fun connectCamera(device: UsbDevice) {
        disconnectCamera()
        setStatus("Connecting…")

        val cam = InfirayP2Camera(usbManager, device)
        cam.onFrame = { data ->
            runOnUiThread { onNewFrame(data) }
        }
        cam.onError = { msg ->
            runOnUiThread {
                disconnectCamera()
                setStatus("Error: $msg")
            }
        }

        // cam.open() does blocking USB control transfers — must NOT run on main thread
        thread(name = "CameraOpen", isDaemon = true) {
            if (cam.open()) {
                camera = cam
                cam.startStreaming()
                runOnUiThread { setStatus(null) }
            } else {
                runOnUiThread { setStatus("Failed to open camera") }
            }
        }
    }

    private fun disconnectCamera() {
        camera?.close()
        camera = null
    }

    private fun onNewFrame(data: ThermalData) {
        // Show the frame
        thermalView.thermalData = data

        // Update temperature readouts
        tempCenter.text = "●  %.1f°C".format(data.centerTempC)

        val mn = data.minPoint
        val mx = data.maxPoint
        tempMin.text = "▼  %.1f°C".format(mn.tempC)
        tempMax.text = "▲  %.1f°C".format(mx.tempC)
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private fun setStatus(msg: String?) {
        if (msg == null) {
            statusText.visibility = View.GONE
        } else {
            statusText.text = msg
            statusText.visibility = View.VISIBLE
        }
    }

    private fun setupPaletteSpinner() {
        val names = ThermalPalette.values().map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        paletteSpinner.adapter = adapter
        paletteSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                thermalView.palette = ThermalPalette.values()[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun toggleControlBar() {
        val visible = controlBar.visibility == View.VISIBLE
        val anim = AlphaAnimation(if (visible) 1f else 0f, if (visible) 0f else 1f).apply {
            duration = 200
        }
        controlBar.startAnimation(anim)
        controlBar.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun setupToggleButtons() {
        fun updateButtonAppearance(btn: MaterialButton, active: Boolean) {
            btn.alpha = if (active) 1.0f else 0.4f
        }

        updateButtonAppearance(btnCenter, showCenter)
        updateButtonAppearance(btnMinMax, showMinMax)

        btnCenter.setOnClickListener {
            showCenter = !showCenter
            thermalView.showCenter = showCenter
            updateButtonAppearance(btnCenter, showCenter)
        }
        btnMinMax.setOnClickListener {
            showMinMax = !showMinMax
            thermalView.showMinMax = showMinMax
            updateButtonAppearance(btnMinMax, showMinMax)
        }
    }
}
