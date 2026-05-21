package com.timothy.joystick

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel

class WebSocketViewModel : ViewModel() {

    val connectionState: LiveData<UDPManager.ConnectionState>
        get() = UDPManager.connectionState

    val lastGesture: LiveData<GestureData?>
        get() = UDPManager.lastGesture

    val latencyMs: LiveData<Long>
        get() = UDPManager.latencyMs

    val vibrateEvent: LiveData<Long>
        get() = UDPManager.vibrateEvent

    fun connect(ip: String, port: Int = 9080) = UDPManager.connect(ip, port)
    fun disconnect()                           = UDPManager.disconnect()
    fun send(content: String)                  = UDPManager.send(content)
    fun sendCommand(command: String)           = UDPManager.send("CMD:$command")
    fun sendBytes(data: ByteArray)            = UDPManager.sendBytes(data)
    fun setPlayerId(id: Byte){
        UDPManager.playerId = id
    }

    // Measuring Test
    fun startPingLoop(intervalMs: Long = 1000L) = UDPManager.startPingLoop(intervalMs)
    fun stopPingLoop()                          = UDPManager.stopPingLoop()
    fun sendPing()                              = UDPManager.sendPing()
}