package com.timothy.joystick

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.url
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WebSocketViewModel : ViewModel() {

    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting   : ConnectionState()
        object Connected    : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    // Gesture detection
    data class GestureEvent(
        val name: String,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Websocket
    private val client = HttpClient(CIO) {
        install(WebSockets) { pingInterval = 15_000 }
    }

    private var session: DefaultClientWebSocketSession? = null
    private var listenerJob: Job? = null
    private var senderJob: Job? = null

    // Hilangin message paling lama kalau buffer penuh
    private val sendChannel = Channel<String>(
        capacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // LiveData
    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: LiveData<ConnectionState> = _connectionState

    private val _lastGesture = MutableLiveData<GestureEvent?>()
    val lastGesture: LiveData<GestureEvent?> = _lastGesture

    // Public API
    fun connect(ipAddress: String, port: Int = 9080) {
        if (_connectionState.value == ConnectionState.Connecting) return
        _connectionState.postValue(ConnectionState.Connecting)

        viewModelScope.launch {
            try {
                val wsSession = client.webSocketSession { url("ws://$ipAddress:$port") }
                if (wsSession.isActive) {
                    session = wsSession
                    _connectionState.postValue(ConnectionState.Connected)
                    startListening(wsSession)
                    startSending(wsSession)
                    sendCommand("GET_STATUS")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: $e")
                _connectionState.postValue(ConnectionState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            senderJob?.cancel()
            listenerJob?.cancel()
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnected"))
            session = null
            _connectionState.postValue(ConnectionState.Disconnected)
        }
    }

    /** Kalau channel penuh, bakal drop yang lama*/
    fun send(content: String) {
        if (_connectionState.value != ConnectionState.Connected) return
        sendChannel.trySend(content)
    }

    fun sendCommand(command: String) = send("CMD:$command")

    // Internal
    private fun startSending(wsSession: DefaultClientWebSocketSession) {
        senderJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                for (message in sendChannel) {
                    if (!wsSession.isActive) break
                    runCatching { wsSession.send(Frame.Text(message)) }
                        .onFailure { Log.e(TAG, "Send error: $it") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sender error: $e")
            }
        }
    }

    private fun startListening(wsSession: DefaultClientWebSocketSession) {
        listenerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                for (frame in wsSession.incoming) {
                    val text = when (frame) {
                        is Frame.Text   -> frame.readText()
                        is Frame.Binary -> frame.readBytes().toString(Charsets.UTF_8)
                        is Frame.Close  -> { _connectionState.postValue(ConnectionState.Disconnected); continue }
                        else            -> continue
                    }
                    handleMessage(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listener error: $e")
                _connectionState.postValue(ConnectionState.Error(e.message ?: "Connection lost"))
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            when {
                text.startsWith("GESTURE:")  -> handleGestureMessage(text)
                text.startsWith("DETECTED:") -> handleDetectionMessage(text)
                text.startsWith("STATUS:")   -> { /* informational only */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message '$text': $e")
        }
    }

    private fun handleGestureMessage(text: String) {
        // Format: GESTURE:gesture_name:confidence
        val parts = text.split(":")
        if (parts.size >= 3) {
            val name       = parts[1]
            val confidence = parts[2].toFloatOrNull() ?: 0f
            _lastGesture.postValue(GestureEvent(name, confidence))
        }
    }

    private fun handleDetectionMessage(text: String) {
        // Format: DETECTED:gesture_name:confidence
        val parts = text.split(":")
        if (parts.size >= 3) {
            val name       = parts[1]
            val confidence = parts[2].toFloatOrNull() ?: 0f
            _lastGesture.postValue(GestureEvent(name, confidence))
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            senderJob?.cancel()
            listenerJob?.cancel()
            sendChannel.close()
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Cleared"))
            client.close()
        }
    }

    companion object {
        private const val TAG = "WebSocketVM"
    }
}