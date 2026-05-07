package com.timothy.joystick

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel

class WebSocketViewModel : ViewModel() {

    val connectionState: LiveData<UDPManager.ConnectionState>
        get() = UDPManager.connectionState

    val lastGesture: LiveData<GestureData?>
        get() = UDPManager.lastGesture

    fun connect(ip: String, port: Int = 9080) = UDPManager.connect(ip, port)
    fun disconnect()                           = UDPManager.disconnect()
    fun send(content: String)                  = UDPManager.send(content)
    fun sendCommand(command: String)           = UDPManager.send("CMD:$command")
}