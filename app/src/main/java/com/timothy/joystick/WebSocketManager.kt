package com.timothy.joystick

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.url
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

object WebSocketManager {

    private const val TAG = "WebSocketManager"

    private val isSessionActive = AtomicBoolean(false)

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        object Connected    : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: LiveData<ConnectionState> get() = _connectionState

    private val _lastGesture = MutableLiveData<GestureData?>()
    val lastGesture: LiveData<GestureData?> get() = _lastGesture

    private val client = HttpClient(CIO) {
        install(WebSockets) { pingInterval = 15_000 }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var session: DefaultClientWebSocketSession? = null
    private var listenerJob: Job? = null
    private var senderJob: Job? = null

    // Drop oldest message if buffer full
    private val sendChannel = Channel<String>(
        capacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // API

    fun connect(ip: String, port: Int = 9080) {
        if (!isSessionActive.compareAndSet(false, true)) {
            return
        }
        // Guard: don't open a second socket if already connected/connecting
        val state = _connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) return

        _connectionState.postValue(ConnectionState.Connecting)

        scope.launch {
            try {
                val ws = client.webSocketSession { url("ws://$ip:$port") }
                if (ws.isActive) {
                    session?.cancel()
                    session = ws
                    _connectionState.postValue(ConnectionState.Connected)
                    startSending(ws)
                    startListening(ws)
                    send("CMD:GET_STATUS")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: $e")
                isSessionActive.set(false)
                _connectionState.postValue(ConnectionState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun send(data: String) {
        if (_connectionState.value is ConnectionState.Connected)
            sendChannel.trySend(data)
    }

    fun disconnect() {
        scope.launch {
            isSessionActive.set(false)
            senderJob?.cancel()
            listenerJob?.cancel()
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnected"))
            session = null
            _connectionState.postValue(ConnectionState.Disconnected)
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun startSending(ws: DefaultClientWebSocketSession) {
        senderJob = scope.launch {
            try {
                for (message in sendChannel) {
                    if (!ws.isActive) break
                    runCatching { ws.send(Frame.Text(message)) }
                        .onFailure { Log.e(TAG, "Send error: $it") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sender coroutine error: $e")
            }
        }
    }

    private fun startListening(ws: DefaultClientWebSocketSession) {
        listenerJob = scope.launch {
            try {
                for (frame in ws.incoming) {
                    val text = when (frame) {
                        is Frame.Text   -> frame.readText()
                        is Frame.Binary -> frame.readBytes().toString(Charsets.UTF_8)
                        is Frame.Close  -> break
                        else -> continue
                    }
                    handleMessage(text)
                }
                Log.d(TAG, "Incoming channel closed cleanly")
                isSessionActive.set(false)
                _connectionState.postValue(ConnectionState.Disconnected)
            } catch (e: Exception) {
                Log.e(TAG, "Listener error: $e")
                isSessionActive.set(false)
                _connectionState.postValue(ConnectionState.Error(e.message ?: "Connection lost"))
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            when {
                text.startsWith("GESTURE:")  -> parseGesture(text)
                text.startsWith("DETECTED:") -> parseGesture(text)
                text.startsWith("STATUS:")   -> { /* informational only */ }
                else -> Log.d(TAG, "Unhandled message: $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message '$text': $e")
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