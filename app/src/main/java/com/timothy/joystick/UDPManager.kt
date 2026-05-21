package com.timothy.joystick

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object UDPManager {
    private const val TAG = "UDPManager"
    private const val TIMEOUT_MS = 3000L

    // Player Identifier
    var playerId: Byte = 0

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

    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 9080

    // Latency Log
    private var listenerJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastMessageTime: Long = 0

    // Vibrate
    private val _vibrateEvent = MutableLiveData<Long>()
    val vibrateEvent: LiveData<Long> get() = _vibrateEvent


    val serverSlots = MutableLiveData<Pair<Boolean, Boolean>>(Pair(false, false))

    fun connect(ip: String, port: Int = 9080) {
        if (_connectionState.value is ConnectionState.Connected) return

        // 1. Set state to Connecting, NOT Connected
        _connectionState.postValue(ConnectionState.Connecting)

        scope.launch {
            try {
                targetAddress = InetAddress.getByName(ip)
                targetPort = port

                if (socket == null || socket?.isClosed == true) {
                    socket = DatagramSocket()
                    socket?.soTimeout = 1000
                }

                startListening()

                send("CMD:JOIN:$playerId")

                var timeWaited = 0
                while (_connectionState.value == ConnectionState.Connecting && timeWaited < 3000) {
                    delay(100)
                    timeWaited += 100
                }

                if (_connectionState.value == ConnectionState.Connecting) {
                    Log.e(TAG, "Connection timed out. Receiver offline.")
                    _connectionState.postValue(ConnectionState.Error("Could not connect to receiver."))
                    disconnect()
                } else if (_connectionState.value == ConnectionState.Connected) {
                    lastMessageTime = System.currentTimeMillis()
                    startWatchdog()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start UDP: $e")
                _connectionState.postValue(ConnectionState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun send(data: String) {
        val address = targetAddress ?: return
        if (socket?.isClosed == true) return

        scope.launch {
            try {
                val bytes = data.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, address, targetPort)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Send error: $e")
            }
        }
    }

    fun disconnect() {
        send("CMD:DISCONNECT")
        scope.launch {
            delay(50)
            listenerJob?.cancel()
            watchdogJob?.cancel()
            socket?.close()
            socket = null
            _connectionState.postValue(ConnectionState.Disconnected)

            // Wipe taken slot memory
            serverSlots.postValue(Pair(false, false))
        }
    }

    fun sendBytes(data: ByteArray) {
        if (socket == null || targetAddress == null || targetPort == -1) return

        scope.launch {
            try {
                val packet = DatagramPacket(data, data.size, targetAddress, targetPort)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e("UDPManager", "Failed to send bytes: ${e.message}")
            }
        }
    }

    private fun startListening() {
        listenerJob = scope.launch {
            val buffer = ByteArray(2048)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)

                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    lastMessageTime = System.currentTimeMillis()

                    if (_connectionState.value == ConnectionState.Connecting || _connectionState.value == ConnectionState.Disconnected) {
                        _connectionState.postValue(ConnectionState.Connected)
                    }

                    handleMessage(text)

                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Listen error: $e")
                    break
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (_connectionState.value == ConnectionState.Connected) {
                    val timeSinceLastMsg = System.currentTimeMillis() - lastMessageTime
                    if (timeSinceLastMsg > TIMEOUT_MS) {
                        Log.w(TAG, "Connection timeout! No packets received for $TIMEOUT_MS ms.")
                        _connectionState.postValue(ConnectionState.Disconnected)
                    }
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            when {
                text.startsWith("STATUS:ASSIGNED:") -> {
                    val assignedId = text.split(":")[2].toByte()
                    playerId = assignedId // Accept forced slot
                    Log.d(TAG, "Server assigned us Player ID: $assignedId")

                    if (_connectionState.value != ConnectionState.Connected) {
                        _connectionState.postValue(ConnectionState.Connected)
                    }
                }

                text.startsWith("STATUS:SLOTS:") -> {
                    // Example Data: STATUS:SLOTS:true,false
                    val parts = text.split(":")[2].split(",")
                    val p1Taken = parts[0] == "true"
                    val p2Taken = parts[1] == "true"
                    serverSlots.postValue(Pair(p1Taken, p2Taken))
                }

                text == "STATUS:PONG" -> {
                    if(pingTimestamp != 0L) {
                        val rttNs = System.nanoTime() - pingTimestamp
                        val rttMs = rttNs / 1_000_000
                        _latencyMs.postValue(rttMs)
                        Log.d(TAG, "Latency: ${rttMs}ms")
                        pingTimestamp = 0L
                    }
                }

                text == "STATUS:ALIVE" -> {
                    // Status online ACK
                }

                text == "CMD:VIBRATE" -> {
                    _vibrateEvent.postValue(System.currentTimeMillis())
                }

                text == "STATUS:DISCONNECTED" -> {
                    _connectionState.postValue(ConnectionState.Disconnected)
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message '$text': $e")
        }
    }

    // ===== Measuring Latency =====
    private val _latencyMs = MutableLiveData<Long>()
    val latencyMs: LiveData<Long> get() = _latencyMs

    private var pingTimestamp: Long = 0L
    private var pingJob: Job? = null

    fun sendPing(){
        pingTimestamp = System.nanoTime()
        send("CMD:PING")
    }

    fun startPingLoop(intervalMs: Long = 1000L) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.Connected) {
                sendPing()
                delay(intervalMs)
            }
        }
    }

    fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }
}