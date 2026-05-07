package com.timothy.joystick

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

object UDPManager {

    private const val TAG = "UDPManager"
    private const val BUFFER_SIZE = 4096

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connected    : ConnectionState()
        object Connecting   : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: LiveData<ConnectionState> get() = _connectionState

    private val _lastGesture = MutableLiveData<GestureData?>()
    val lastGesture: LiveData<GestureData?> get() = _lastGesture

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 9080
    private var listenerJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    // API

    fun connect(ip: String, port: Int = 9080) {
        if (!isRunning.compareAndSet(false, true)) return

        serverPort = port
        scope.launch {
            try {
                serverAddress = InetAddress.getByName(ip)
                socket = DatagramSocket().apply {
                    soTimeout = 0          // non-blocking receive via coroutine
                    reuseAddress = true
                }
                _connectionState.postValue(ConnectionState.Connected)
                startListening()
                Log.d(TAG, "UDP ready → $ip:$port")
            } catch (e: Exception) {
                isRunning.set(false)
                Log.e(TAG, "Init error: $e")
                _connectionState.postValue(ConnectionState.Error(e.message ?: "Init failed"))
            }
        }
    }

    /**
     * Fire-and-forget send. Safe to call from any thread at high frequency.
     * Drops silently if socket isn't ready — no queue, no blocking.
     */
    fun send(data: String) {
        val addr = serverAddress ?: return
        val sock = socket ?: return
        scope.launch {
            runCatching {
                val bytes = data.toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(bytes, bytes.size, addr, serverPort))
            }.onFailure { Log.e(TAG, "Send error: $it") }
        }
    }

    fun disconnect() {
        isRunning.set(false)
        listenerJob?.cancel()
        socket?.close()
        socket = null
        serverAddress = null
        _connectionState.postValue(ConnectionState.Disconnected)
        Log.d(TAG, "UDP disconnected")
    }

    // Internal Func

    private fun startListening() {
        listenerJob = scope.launch {
            val buf = ByteArray(BUFFER_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            try {
                while (isRunning.get()) {
                    val sock = socket ?: break
                    runCatching { sock.receive(packet) }
                        .onSuccess {
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            handleMessage(text)
                        }
                        .onFailure {
                            if (isRunning.get()) Log.e(TAG, "Receive error: $it")
                        }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Listener crashed: $e")
                    _connectionState.postValue(ConnectionState.Error(e.message ?: "Lost"))
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            when {
                text.startsWith("GESTURE:")  -> parseGesture(text)
                text.startsWith("DETECTED:") -> parseGesture(text)
                text.startsWith("STATUS:")   -> { /* informational */ }
                else -> Log.d(TAG, "Unhandled: $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handle error '$text': $e")
        }
    }

    private fun parseGesture(text: String) {
        val parts = text.split(":")
        if (parts.size >= 3) {
            val name       = parts[1]
            val confidence = parts[2].toFloatOrNull() ?: 0f
            _lastGesture.postValue(GestureData(name, confidence))
        }
    }
}