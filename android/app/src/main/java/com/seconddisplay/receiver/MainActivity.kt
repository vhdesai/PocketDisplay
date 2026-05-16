package com.seconddisplay.receiver

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusOverlay: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var prefs: SharedPreferences

    private var streamClient: StreamClient? = null
    private var controlClient: ControlClient? = null
    private var decoder: H264Decoder? = null

    private var serverHost = "127.0.0.1"
    private var videoPort = 5000
    private var controlPort = 5002
    private var showHud = true

    private val handler = Handler(Looper.getMainLooper())
    private var decoderStatus = "waiting"
    private var connectionStatus = "disconnected"
    private var lastDecoderDetail = ""

    private val debugHudRunnable = object : Runnable {
        override fun run() {
            if (!showHud) {
                statusOverlay.visibility = View.GONE
                handler.postDelayed(this, 500)
                return
            }
            val dec = decoder
            val sc = streamClient
            val lines = mutableListOf<String>()
            lines.add("=== DEBUG HUD ===")
            lines.add("Server: $serverHost:$videoPort")
            lines.add("Connection: $connectionStatus")
            if (dec != null) {
                lines.add("Decoder: $decoderStatus")
                lines.add("Frames decoded: ${dec.framesDecoded}")
                lines.add("Resolution: ${dec.videoWidth}x${dec.videoHeight}")
                lines.add("Bytes received: ${dec.totalBytes / 1024}KB")
                lines.add("SPS: ${if (dec.hasSps) "YES" else "NO"} | PPS: ${if (dec.hasPps) "YES" else "NO"}")
                if (lastDecoderDetail.isNotEmpty()) lines.add("Detail: $lastDecoderDetail")
            } else {
                lines.add("Decoder: not created")
            }
            if (lastError.isNotEmpty()) lines.add("Error: $lastError")
            statusOverlay.text = lines.joinToString("\n")
            statusOverlay.alpha = 0.85f
            statusOverlay.visibility = View.VISIBLE
            handler.postDelayed(this, 500)
        }
    }

    private val hideStatusRunnable = Runnable {
        // no-op now, HUD stays visible
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        prefs = getSharedPreferences("second_display", Context.MODE_PRIVATE)
        serverHost = prefs.getString("server_host", "127.0.0.1") ?: "127.0.0.1"
        videoPort = prefs.getInt("video_port", 5000)
        controlPort = prefs.getInt("control_port", 5002)
        showHud = prefs.getBoolean("show_hud", true)

        surfaceView = findViewById(R.id.surfaceView)
        statusOverlay = findViewById(R.id.statusOverlay)
        settingsButton = findViewById(R.id.settingsButton)
        surfaceView.holder.addCallback(this)

        showStatusOverlay("Tap ⚙ to set server IP, or connecting to $serverHost...")

        settingsButton.setOnClickListener { showSettingsDialog() }

        surfaceView.setOnTouchListener { _, event ->
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> "down"
                MotionEvent.ACTION_MOVE -> "move"
                MotionEvent.ACTION_UP -> "up"
                else -> null
            }

            if (action != null) {
                val viewWidth = surfaceView.width
                val viewHeight = surfaceView.height
                if (viewWidth > 0 && viewHeight > 0) {
                    val normalizedX = (event.x / viewWidth.toFloat()).coerceIn(0f, 1f)
                    val normalizedY = (event.y / viewHeight.toFloat()).coerceIn(0f, 1f)
                    controlClient?.sendTouchEvent(action, normalizedX, normalizedY)
                }
            }
            true
        }
    }

    private fun showSettingsDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
        }

        val input = EditText(this).apply {
            setText(serverHost)
            hint = "Server IP (e.g. 192.168.1.100)"
            setPadding(50, 30, 50, 30)
            selectAll()
        }
        layout.addView(input)

        val hudCheckbox = android.widget.CheckBox(this).apply {
            text = "Show Debug HUD"
            isChecked = showHud
            setPadding(50, 20, 50, 10)
        }
        layout.addView(hudCheckbox)

        AlertDialog.Builder(this)
            .setTitle("Server Connection")
            .setMessage(
                "Enter the IP shown by the server on your PC.\n" +
                "Use 127.0.0.1 for ADB USB mode.\n" +
                "Current: $serverHost:$videoPort"
            )
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val newHost = input.text.toString().trim()
                showHud = hudCheckbox.isChecked
                prefs.edit()
                    .putBoolean("show_hud", showHud)
                    .apply()
                if (newHost.isNotEmpty()) {
                    serverHost = newHost
                    prefs.edit()
                        .putString("server_host", serverHost)
                        .apply()
                    stopPipeline()
                    surfaceView.holder.let { holder ->
                        if (holder.surface.isValid) {
                            startPipeline(holder)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surfaceFrame = holder.surfaceFrame
        Log.i(TAG, "Surface created: ${surfaceFrame.width()}x${surfaceFrame.height()}")
        startPipeline(holder)
    }

    private var lastError: String = ""

    private fun startPipeline(holder: SurfaceHolder) {
        stopPipeline()
        lastError = ""
        connectionStatus = "connecting..."
        decoderStatus = "starting"
        lastDecoderDetail = ""

        // Start debug HUD updates
        handler.removeCallbacks(debugHudRunnable)
        handler.post(debugHudRunnable)

        decoder = H264Decoder(holder.surface) { status ->
            lastDecoderDetail = status
            decoderStatus = status
            Log.d(TAG, "Decoder: $status")
        }.also { it.start() }

        controlClient = ControlClient(host = serverHost, port = controlPort)

        streamClient = StreamClient(
            host = serverHost,
            port = videoPort,
            onConnected = { socket ->
                connectionStatus = "CONNECTED"
                Log.i(TAG, "Connected to $serverHost:$videoPort")
                // Attach control client to the same socket for upstream touch data
                controlClient?.attachSocket(socket)
                // Reset decoder for fresh stream
                decoder?.reset()
            },
            onDisconnected = { error ->
                lastError = error
                connectionStatus = "DISCONNECTED"
                Log.e(TAG, "Disconnected from $serverHost: $error")
            },
            onDataReceived = { data, bytesRead ->
                try {
                    decoder?.feedData(data, 0, bytesRead)
                } catch (e: Exception) {
                    Log.e(TAG, "Decoder feedData error: ${e.message}", e)
                    lastError = "Decoder: ${e.message}"
                }
            }
        ).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "Surface changed: ${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed")
        stopPipeline()
    }

    override fun onDestroy() {
        handler.removeCallbacks(debugHudRunnable)
        handler.removeCallbacks(hideStatusRunnable)
        stopPipeline()
        surfaceView.holder.removeCallback(this)
        super.onDestroy()
    }

    private fun showStatusOverlay(message: String, autoHide: Boolean = false, hideDelayMs: Long = STATUS_HIDE_DELAY_MS) {
        statusOverlay.removeCallbacks(hideStatusRunnable)
        statusOverlay.animate().cancel()
        statusOverlay.text = message
        statusOverlay.alpha = 1f
        statusOverlay.visibility = View.VISIBLE
        if (autoHide) {
            statusOverlay.postDelayed(hideStatusRunnable, hideDelayMs)
        }
    }

    private fun stopPipeline() {
        controlClient?.stop()
        controlClient = null

        streamClient?.stop()
        streamClient = null

        decoder?.stop()
        decoder = null
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STATUS_HIDE_DELAY_MS = 2000L
    }
}
