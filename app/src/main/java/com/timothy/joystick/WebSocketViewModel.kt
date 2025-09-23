package com.timothy.joystick

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.url
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WebSocketViewModel : ViewModel() {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private var connectedIpAddress: String? = null
    private var connectedPort: Int? = null
    private var session: DefaultClientWebSocketSession? = null

    fun connect(ipAddress: String, port: Int = 9080) {
        viewModelScope.launch {
            try {
                Log.d("ws", "Attempting to set up WebSocket.")
                val wsSession = client.webSocketSession {
                    url("ws://${ipAddress}:${port}")
                }

                if (wsSession.isActive) {
                    connectedIpAddress = ipAddress
                    connectedPort = port
                    session = wsSession
                    Log.d("ws", "WebSocket connected. [${DatetimeManager.now()}]")

                    // Start listening in a background coroutine
                    launch(Dispatchers.IO) {
                        try {
                            for (frame in wsSession.incoming) {
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        Log.d("ws", "Received text: $text [${DatetimeManager.now()}]")
                                    }
                                    is Frame.Close -> {
                                        Log.d("ws", "Connection closed by server. [${DatetimeManager.now()}]")
                                    }
                                    is Frame.Binary -> {
                                        val bytes = frame.readBytes()
                                        val decoded = bytes.toString(Charsets.UTF_8)
                                        Log.d("ws", "Received binary (decoded): $decoded [${DatetimeManager.now()}]")
                                    }
                                    else -> Log.d("ws", "Received other frame: $frame  [${DatetimeManager.now()}]")
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("ws", "Error while listening: $e")
                        }
                    }

                    send("Hello from Android with Ktor!")
                }
            } catch (e: Exception) {
                Log.d("ws", "Error setting up WebSocket [$e]. [${DatetimeManager.now()}]")
                e.printStackTrace()
            }
        }
    }

    fun send(content: String) {
        viewModelScope.launch {
            runCatching {
                session?.send(Frame.Text(content)) ?: throw IllegalStateException("Session is null")
            }.onSuccess {
                Log.d("ws", "Sent message '$content' to $connectedIpAddress. [${DatetimeManager.now()}]")
            }.onFailure { e ->
                Log.d("ws", "Error sending message [$e].")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "ViewModel cleared"))
        }
        client.close()
    }
}
