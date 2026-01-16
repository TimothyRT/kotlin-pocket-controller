package com.timothy.joystick

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * Main Activity with Dynamic Calibration
 * 
 * Guides user through gesture calibration:
 * 1. IDLE - Hold still
 * 2. HIT - Downward strike
 * 3. SWING_LEFT - Swing left
 * 4. SWING_RIGHT - Swing right
 * 5. SHAKE - Shake phone
 */
class MainActivityV2 : AppCompatActivity(), SensorEventListener {
    
    // Sensors
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    
    // UI Elements - Connection
    private lateinit var inputIpAddress: TextInputEditText
    private lateinit var buttonConnect: Button
    private lateinit var textConnectionStatus: TextView
    
    // UI Elements - Calibration
    private lateinit var cardCalibration: CardView
    private lateinit var buttonCalibrate: Button
    private lateinit var textCalibrationPhase: TextView
    private lateinit var textCalibrationInstruction: TextView
    private lateinit var textCalibrationProgress: TextView
    private lateinit var progressCalibration: ProgressBar
    
    // UI Elements - Gesture & Sensors
    private lateinit var textGesture: TextView
    private lateinit var textGyroscope: TextView
    private lateinit var textAccelerometer: TextView
    
    // ViewModel
    private val wsViewModel: WebSocketViewModelV2 by viewModels()
    
    // Sensor rate limiting
    private var lastSendTime: Long = 0
    private val sendIntervalMs: Long = 16  // ~60 Hz
    
    // Calibration click debounce
    private var lastCalibrationClick: Long = 0
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main_v2)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        initViews()
        initSensors()
        observeViewModel()
    }
    
    private fun initViews() {
        // Connection
        inputIpAddress = findViewById(R.id.input_ip_address)
        buttonConnect = findViewById(R.id.button_connect)
        textConnectionStatus = findViewById(R.id.text_connection_status)
        
        // Calibration
        cardCalibration = findViewById(R.id.card_calibration)
        buttonCalibrate = findViewById(R.id.button_calibrate)
        textCalibrationPhase = findViewById(R.id.text_calibration_phase)
        textCalibrationInstruction = findViewById(R.id.text_calibration_instruction)
        textCalibrationProgress = findViewById(R.id.text_calibration_progress)
        progressCalibration = findViewById(R.id.progress_calibration)
        
        // Gesture & Sensors
        textGesture = findViewById(R.id.text_gesture)
        textGyroscope = findViewById(R.id.text_gyroscope)
        textAccelerometer = findViewById(R.id.text_accelerometer)
        
        // Connect button
        buttonConnect.setOnClickListener {
            val ip = inputIpAddress.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            when (wsViewModel.connectionState.value) {
                is WebSocketViewModelV2.ConnectionState.Connected -> wsViewModel.disconnect()
                else -> wsViewModel.connect(ip)
            }
        }
        
        // Calibrate button with debounce
        buttonCalibrate.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastCalibrationClick < 1000) return@setOnClickListener
            lastCalibrationClick = now
            
            if (wsViewModel.connectionState.value == WebSocketViewModelV2.ConnectionState.Connected) {
                buttonCalibrate.isEnabled = false
                wsViewModel.startCalibration()
            } else {
                Toast.makeText(this, "Connect first!", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    
    private fun observeViewModel() {
        // Connection state
        wsViewModel.connectionState.observe(this) { state ->
            when (state) {
                is WebSocketViewModelV2.ConnectionState.Disconnected -> {
                    textConnectionStatus.text = "● Disconnected"
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    buttonConnect.text = "CONNECT"
                    buttonCalibrate.isEnabled = false
                    cardCalibration.alpha = 0.5f
                }
                is WebSocketViewModelV2.ConnectionState.Connecting -> {
                    textConnectionStatus.text = "● Connecting..."
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                    buttonConnect.isEnabled = false
                }
                is WebSocketViewModelV2.ConnectionState.Connected -> {
                    textConnectionStatus.text = "● Connected"
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    buttonConnect.text = "DISCONNECT"
                    buttonConnect.isEnabled = true
                    buttonCalibrate.isEnabled = true
                    cardCalibration.alpha = 1f
                }
                is WebSocketViewModelV2.ConnectionState.Error -> {
                    textConnectionStatus.text = "● Error: ${state.message}"
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    buttonConnect.text = "CONNECT"
                    buttonConnect.isEnabled = true
                }
            }
        }
        
        // Calibration state
        wsViewModel.calibrationState.observe(this) { state ->
            Log.d("MainActivity", "Calibration state: ${state.phase}")
            
            updateCalibrationUI(state)
        }
        
        // Gesture events
        wsViewModel.lastGesture.observe(this) { gesture ->
            gesture?.let {
                val confidencePercent = (it.confidence * 100).toInt()
                textGesture.text = "🎯 ${it.name.uppercase()} (${confidencePercent}%)"
                textGesture.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                
                // Reset after delay
                textGesture.postDelayed({
                    textGesture.text = "Waiting for gesture..."
                    textGesture.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                }, 1500)
            }
        }
    }
    
    private fun updateCalibrationUI(state: WebSocketViewModelV2.CalibrationState) {
        val phaseEmoji = when (state.phase) {
            WebSocketViewModelV2.CalibrationPhase.NOT_STARTED -> "⚪"
            WebSocketViewModelV2.CalibrationPhase.IDLE -> "🧘"
            WebSocketViewModelV2.CalibrationPhase.HIT -> "👊"
            WebSocketViewModelV2.CalibrationPhase.SWING_LEFT -> "👈"
            WebSocketViewModelV2.CalibrationPhase.SWING_RIGHT -> "👉"
            WebSocketViewModelV2.CalibrationPhase.SHAKE -> "🫨"
            WebSocketViewModelV2.CalibrationPhase.TRAINING -> "⚙️"
            WebSocketViewModelV2.CalibrationPhase.COMPLETED -> "✅"
            WebSocketViewModelV2.CalibrationPhase.FAILED -> "❌"
        }
        
        val phaseName = when (state.phase) {
            WebSocketViewModelV2.CalibrationPhase.NOT_STARTED -> "Not Started"
            WebSocketViewModelV2.CalibrationPhase.IDLE -> "Step 1/5: IDLE"
            WebSocketViewModelV2.CalibrationPhase.HIT -> "Step 2/5: HIT"
            WebSocketViewModelV2.CalibrationPhase.SWING_LEFT -> "Step 3/5: SWING LEFT"
            WebSocketViewModelV2.CalibrationPhase.SWING_RIGHT -> "Step 4/5: SWING RIGHT"
            WebSocketViewModelV2.CalibrationPhase.SHAKE -> "Step 5/5: SHAKE"
            WebSocketViewModelV2.CalibrationPhase.TRAINING -> "Processing..."
            WebSocketViewModelV2.CalibrationPhase.COMPLETED -> "Completed!"
            WebSocketViewModelV2.CalibrationPhase.FAILED -> "Failed"
        }
        
        textCalibrationPhase.text = "$phaseEmoji $phaseName"
        textCalibrationInstruction.text = state.instruction
        
        // Progress text
        if (state.phase != WebSocketViewModelV2.CalibrationPhase.NOT_STARTED &&
            state.phase != WebSocketViewModelV2.CalibrationPhase.COMPLETED &&
            state.phase != WebSocketViewModelV2.CalibrationPhase.FAILED &&
            state.phase != WebSocketViewModelV2.CalibrationPhase.TRAINING &&
            state.phase != WebSocketViewModelV2.CalibrationPhase.IDLE) {
            textCalibrationProgress.text = "Gesture ${state.currentCount}/${state.totalCount}"
            textCalibrationProgress.visibility = View.VISIBLE
        } else {
            textCalibrationProgress.visibility = View.GONE
        }
        
        // Progress bar
        when (state.phase) {
            WebSocketViewModelV2.CalibrationPhase.NOT_STARTED -> {
                progressCalibration.visibility = View.GONE
                buttonCalibrate.text = "START CALIBRATION"
                buttonCalibrate.isEnabled = wsViewModel.connectionState.value == WebSocketViewModelV2.ConnectionState.Connected
            }
            WebSocketViewModelV2.CalibrationPhase.COMPLETED -> {
                progressCalibration.visibility = View.GONE
                buttonCalibrate.text = "RECALIBRATE"
                buttonCalibrate.isEnabled = true
                textCalibrationPhase.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                Toast.makeText(this, "Calibration complete!", Toast.LENGTH_SHORT).show()
            }
            WebSocketViewModelV2.CalibrationPhase.FAILED -> {
                progressCalibration.visibility = View.GONE
                buttonCalibrate.text = "RETRY"
                buttonCalibrate.isEnabled = true
                textCalibrationPhase.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                Toast.makeText(this, "Calibration failed: ${state.error}", Toast.LENGTH_LONG).show()
            }
            else -> {
                progressCalibration.visibility = View.VISIBLE
                progressCalibration.progress = (state.progress * 100).toInt()
                buttonCalibrate.isEnabled = false
                textCalibrationPhase.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        gyroscope?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }
    
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val now = System.currentTimeMillis()
            if (now - lastSendTime < sendIntervalMs) return
            lastSendTime = now
            
            val x = it.values[0]
            val y = it.values[1]
            val z = it.values[2]
            
            when (it.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    textGyroscope.text = String.format("X: %+.2f\nY: %+.2f\nZ: %+.2f", x, y, z)
                    wsViewModel.send("Gyroscope:\nX: $x\nY: $y\nZ: $z")
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    textAccelerometer.text = String.format("X: %+.2f\nY: %+.2f\nZ: %+.2f", x, y, z)
                    wsViewModel.send("Accelerometer:\nX: $x\nY: $y\nZ: $z")
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
