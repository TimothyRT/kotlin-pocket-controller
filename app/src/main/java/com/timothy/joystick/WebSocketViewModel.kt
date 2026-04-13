package com.timothy.joystick

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel

class WebSocketViewModel : ViewModel() {

    val connectionState: LiveData<WebSocketManager.ConnectionState>
        get() = WebSocketManager.connectionState

    val lastGesture: LiveData<GestureData?>
        get() = WebSocketManager.lastGesture

    fun connect(ip: String, port: Int = 9080) = WebSocketManager.connect(ip, port)
    fun disconnect()                           = WebSocketManager.disconnect()
    fun send(content: String)                  = WebSocketManager.send(content)
    fun sendCommand(command: String)           = WebSocketManager.send("CMD:$command")
}