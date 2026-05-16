package com.seconddisplay.receiver

import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * TCP client for sending control/touch input back to the Windows server.
 * Sends touch events over the existing video stream connection (upstream).
 */
class ControlClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5002
) {
    companion object {
        private const val TAG = "ControlClient"
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false
    private var connectJob: Job? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    fun start() {
        if (running) return
        running = true
        connectJob = scope.launch {
            while (running && isActive) {
                try {
                    connect()
                    // Stay connected until stopped or error
                    while (running && isActive && isConnected) {
                        delay(1000)
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.w(TAG, "Control connection lost: ${e.message}")
                        delay(3000)
                    }
                } finally {
                    disconnect()
                }
            }
        }
    }

    /** Use an existing socket (from StreamClient) instead of creating a new connection */
    fun attachSocket(existingSocket: Socket) {
        disconnect()
        socket = existingSocket
        outputStream = existingSocket.getOutputStream()
        running = true
        // Cancel the connect loop since we're attached
        connectJob?.cancel()
        Log.i(TAG, "Attached to existing socket for control")
    }

    fun stop() {
        running = false
        connectJob?.cancel()
        disconnect()
        scope.cancel()
    }

    /**
     * Send a touch event to the server.
     * @param action "down", "move", or "up"
     * @param x normalized x coordinate (0.0 to 1.0)
     * @param y normalized y coordinate (0.0 to 1.0)
     */
    fun sendTouchEvent(action: String, x: Float, y: Float) {
        if (!isConnected) return
        scope.launch {
            try {
                val message = """{"action":"$action","x":$x,"y":$y}""" + "\n"
                outputStream?.write(message.toByteArray(StandardCharsets.UTF_8))
                outputStream?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send touch event: ${e.message}")
            }
        }
    }

    private fun connect() {
        Log.i(TAG, "Connecting control channel to $host:$port...")
        socket = Socket().apply {
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tcpNoDelay = true
        }
        outputStream = socket!!.getOutputStream()
        Log.i(TAG, "Control channel connected")
    }

    private fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing control socket: ${e.message}")
        }
        socket = null
        outputStream = null
    }
}
