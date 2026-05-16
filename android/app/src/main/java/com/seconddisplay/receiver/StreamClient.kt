package com.seconddisplay.receiver

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress

/**
 * TCP client that connects to the streaming server via ADB reverse port forwarding.
 * Connects to localhost:5000 for video stream.
 * Also provides the socket for upstream control data.
 */
class StreamClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5000,
    private val onConnected: (Socket) -> Unit = {},
    private val onDisconnected: (String) -> Unit = {},
    private val onDataReceived: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "StreamClient"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_BUFFER_SIZE = 65536
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var running = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "Starting StreamClient to $host:$port")
        job = scope.launch {
            while (running && isActive) {
                try {
                    connect()
                    readLoop()
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "Connection failed to $host:$port: ${e.javaClass.simpleName}: ${e.message}", e)
                        onDisconnected("${e.javaClass.simpleName}: ${e.message}")
                        delay(RECONNECT_DELAY_MS)
                    }
                } finally {
                    disconnect()
                }
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        disconnect()
        scope.cancel()
    }

    private fun connect() {
        Log.i(TAG, "Connecting to $host:$port...")
        val address = InetSocketAddress(host, port)
        Log.i(TAG, "Resolved address: ${address.address}:${address.port} (unresolved=${address.isUnresolved})")
        socket = Socket().apply {
            connect(address, CONNECT_TIMEOUT_MS)
            tcpNoDelay = true
            receiveBufferSize = READ_BUFFER_SIZE * 4
        }
        inputStream = socket!!.getInputStream()
        Log.i(TAG, "Connected to $host:$port")
        onConnected(socket!!)
    }

    private fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (running) {
            val bytesRead = inputStream?.read(buffer) ?: -1
            if (bytesRead == -1) {
                throw Exception("End of stream")
            }
            if (bytesRead > 0) {
                onDataReceived(buffer, bytesRead)
            }
        }
    }

    private fun disconnect() {
        try {
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
        socket = null
        inputStream = null
    }
}
