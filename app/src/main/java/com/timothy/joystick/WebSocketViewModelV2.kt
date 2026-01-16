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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WebSocket ViewModel with Dynamic Calibration Support
 * 
 * Supports guided calibration where user performs specific gestures:
 * 1. Hold still (IDLE)
 * 2. Hit motion
 * 3. Swing left
 * 4. Swing right  
 * 5. Shake
 */
class WebSocketViewModelV2 : ViewModel() {
    
    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    // Calibration phase
    enum class CalibrationPhase {
        NOT_STARTED,
        IDLE,           // Hold still
        HIT,            // Downward strike
        SWING_LEFT,     // Swing left
        SWING_RIGHT,    // Swing right
        SHAKE,          // Shake phone
        TRAINING,       // Processing data
        COMPLETED,
        FAILED
    }
    
    // Calibration state with detailed info
    data class CalibrationState(
        val phase: CalibrationPhase = CalibrationPhase.NOT_STARTED,
        val instruction: String = "",
        val currentCount: Int = 0,
        val totalCount: Int = 5,
        val progress: Float = 0f,
        val error: String? = null
    )
    
    // Gesture detection
    data class GestureEvent(
        val name: String,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 15_000
        }
    }

    private var connectedIpAddress: String? = null
    private var session: DefaultClientWebSocketSession? = null
    private var listenerJob: Job? = null
    
    // LiveData
    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: LiveData<ConnectionState> = _connectionState
    
    private val _calibrationState = MutableLiveData(CalibrationState())
    val calibrationState: LiveData<CalibrationState> = _calibrationState
    
    private val _lastGesture = MutableLiveData<GestureEvent?>()
    val lastGesture: LiveData<GestureEvent?> = _lastGesture
    
    private val _isCalibrated = MutableLiveData(false)
    val isCalibrated: LiveData<Boolean> = _isCalibrated
    
    // Instructions for each phase
    private val phaseInstructions = mapOf(
        CalibrationPhase.IDLE to "Hold the phone STILL on a flat surface",
        CalibrationPhase.HIT to "Do a DOWNWARD STRIKE motion\n(like hitting a drum)",
        CalibrationPhase.SWING_LEFT to "SWING the phone to the LEFT",
        CalibrationPhase.SWING_RIGHT to "SWING the phone to the RIGHT",
        CalibrationPhase.SHAKE to "SHAKE the phone rapidly",
        CalibrationPhase.TRAINING to "Processing calibration data...",
        CalibrationPhase.COMPLETED to "Calibration complete! ✓"
    )
    
    /**
     * Connect to server
     */
    fun connect(ipAddress: String, port: Int = 9080) {
        if (_connectionState.value == ConnectionState.Connecting) return
        
        _connectionState.postValue(ConnectionState.Connecting)
        
        viewModelScope.launch {
            try {
                val wsSession = client.webSocketSession {
                    url("ws://${ipAddress}:${port}")
                }

                if (wsSession.isActive) {
                    connectedIpAddress = ipAddress
                    session = wsSession
                    _connectionState.postValue(ConnectionState.Connected)
                    
                    startListening(wsSession)
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
            listenerJob?.cancel()
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnected"))
            session = null
            _connectionState.postValue(ConnectionState.Disconnected)
        }
    }
    
    fun send(content: String) {
        if (_connectionState.value != ConnectionState.Connected) return
        
        viewModelScope.launch {
            runCatching {
                session?.send(Frame.Text(content))
            }.onFailure { Log.e(TAG, "Send error: $it") }
        }
    }
    
    fun sendCommand(command: String) = send("CMD:$command")
    
    /**
     * Start dynamic calibration
     */
    fun startCalibration() {
        val currentState = _calibrationState.value
        if (currentState?.phase != CalibrationPhase.NOT_STARTED && 
            currentState?.phase != CalibrationPhase.COMPLETED &&
            currentState?.phase != CalibrationPhase.FAILED) {
            Log.d(TAG, "Calibration already in progress")
            return
        }
        
        _calibrationState.postValue(CalibrationState(
            phase = CalibrationPhase.IDLE,
            instruction = phaseInstructions[CalibrationPhase.IDLE] ?: ""
        ))
        
        sendCommand("START_DYNAMIC_CALIBRATION")
    }
    
    fun resetCalibration() {
        _calibrationState.postValue(CalibrationState())
        _isCalibrated.postValue(false)
        sendCommand("RESET_CALIBRATION")
    }
    
    private fun startListening(wsSession: DefaultClientWebSocketSession) {
        listenerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                for (frame in wsSession.incoming) {
                    val text = when (frame) {
                        is Frame.Text -> frame.readText()
                        is Frame.Binary -> frame.readBytes().toString(Charsets.UTF_8)
                        is Frame.Close -> {
                            _connectionState.postValue(ConnectionState.Disconnected)
                            continue
                        }
                        else -> continue
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
        Log.d(TAG, "Received: $text")
        
        when {
            text.startsWith("CALIBRATION:") -> handleCalibrationMessage(text)
            text.startsWith("PHASE:") -> handlePhaseMessage(text)
            text.startsWith("GESTURE:") -> handleGestureMessage(text)
            text.startsWith("STATUS:") -> handleStatusMessage(text)
        }
    }
    
    private fun handleCalibrationMessage(text: String) {
        val parts = text.split(":")
        when (parts.getOrNull(1)) {
            "STARTED" -> {
                _calibrationState.postValue(CalibrationState(
                    phase = CalibrationPhase.IDLE,
                    instruction = phaseInstructions[CalibrationPhase.IDLE] ?: ""
                ))
            }
            "COMPLETED" -> {
                _calibrationState.postValue(CalibrationState(
                    phase = CalibrationPhase.COMPLETED,
                    instruction = phaseInstructions[CalibrationPhase.COMPLETED] ?: "",
                    progress = 1f
                ))
                _isCalibrated.postValue(true)
            }
            "FAILED" -> {
                val error = parts.getOrNull(2) ?: "Unknown error"
                _calibrationState.postValue(CalibrationState(
                    phase = CalibrationPhase.FAILED,
                    instruction = "Calibration failed",
                    error = error
                ))
            }
            "READY" -> {
                _isCalibrated.postValue(true)
                _calibrationState.postValue(CalibrationState(
                    phase = CalibrationPhase.COMPLETED,
                    instruction = "Already calibrated ✓"
                ))
            }
            "NEEDED" -> {
                _isCalibrated.postValue(false)
            }
        }
    }
    
    private fun handlePhaseMessage(text: String) {
        // Format: PHASE:phase_name:current:total:instruction
        val parts = text.split(":", limit = 5)
        val phaseName = parts.getOrNull(1) ?: return
        val current = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val total = parts.getOrNull(3)?.toIntOrNull() ?: 5
        val instruction = parts.getOrNull(4) ?: ""
        
        val phase = when (phaseName.lowercase()) {
            "idle" -> CalibrationPhase.IDLE
            "hit" -> CalibrationPhase.HIT
            "swing_left" -> CalibrationPhase.SWING_LEFT
            "swing_right" -> CalibrationPhase.SWING_RIGHT
            "shake" -> CalibrationPhase.SHAKE
            "training" -> CalibrationPhase.TRAINING
            "completed" -> CalibrationPhase.COMPLETED
            else -> CalibrationPhase.NOT_STARTED
        }
        
        val overallProgress = calculateOverallProgress(phase, current, total)
        
        _calibrationState.postValue(CalibrationState(
            phase = phase,
            instruction = instruction.ifEmpty { phaseInstructions[phase] ?: "" },
            currentCount = current,
            totalCount = total,
            progress = overallProgress
        ))
        
        Log.d(TAG, "Phase: $phaseName, Progress: $current/$total, Overall: ${(overallProgress * 100).toInt()}%")
    }
    
    private fun calculateOverallProgress(phase: CalibrationPhase, current: Int, total: Int): Float {
        val phaseWeights = mapOf(
            CalibrationPhase.IDLE to 0f,
            CalibrationPhase.HIT to 0.2f,
            CalibrationPhase.SWING_LEFT to 0.4f,
            CalibrationPhase.SWING_RIGHT to 0.6f,
            CalibrationPhase.SHAKE to 0.8f,
            CalibrationPhase.TRAINING to 0.95f,
            CalibrationPhase.COMPLETED to 1f
        )
        
        val baseProgress = phaseWeights[phase] ?: 0f
        val phaseProgress = if (total > 0) current.toFloat() / total else 0f
        val phaseWeight = 0.2f  // Each gesture phase is 20% of total
        
        return (baseProgress + phaseProgress * phaseWeight).coerceIn(0f, 1f)
    }
    
    private fun handleGestureMessage(text: String) {
        val parts = text.split(":")
        if (parts.size >= 3) {
            val name = parts[1]
            val confidence = parts[2].toFloatOrNull() ?: 0f
            _lastGesture.postValue(GestureEvent(name, confidence))
        }
    }
    
    private fun handleStatusMessage(text: String) {
        val json = text.substringAfter("STATUS:")
        // Parse JSON status if needed
        Log.d(TAG, "Status: $json")
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            listenerJob?.cancel()
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Cleared"))
            client.close()
        }
    }
    
    companion object {
        private const val TAG = "WebSocketVM"
    }
}
