package com.timothy.joystick

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
class MainActivityV3 : AppCompatActivity(), SensorEventListener {

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null

    // Vibrator for feedback
    private lateinit var vibrator: Vibrator

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
    private val wsViewModel: WebSocketViewModelV3 by viewModels()

    // Sensor rate limiting - SEPARATE for each sensor type!
    private var lastGyroSendTime: Long = 0
    private var lastAccelSendTime: Long = 0
    private val sendIntervalMs: Long = 50  // ~20 Hz (reduced to prevent overload)

    // Calibration click debounce
    private var lastCalibrationClick: Long = 0

    // Gesture detection debounce
    private var lastGestureTime: Long = 0
    private val gestureDebounceMs: Long = 500  // Ignore gestures within 500ms of each other

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main_v3)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initVibrator()
        initViews()
        initSensors()
        observeViewModel()
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrateSuccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
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
                is WebSocketViewModelV3.ConnectionState.Connected -> wsViewModel.disconnect()
                else -> wsViewModel.connect(ip)
            }
        }

        // Calibrate button with debounce
        buttonCalibrate.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastCalibrationClick < 1000) return@setOnClickListener
            lastCalibrationClick = now

            if (wsViewModel.connectionState.value == WebSocketViewModelV3.ConnectionState.Connected) {
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

        // Check sensor availability
        if (gyroscope == null) {
            Log.e("MainActivity", "⚠️ GYROSCOPE NOT AVAILABLE on this device!")
            Toast.makeText(this, "Warning: Gyroscope not available!", Toast.LENGTH_LONG).show()
        } else {
            Log.d("MainActivity", "✓ Gyroscope available: ${gyroscope?.name}")
        }

        if (accelerometer == null) {
            Log.e("MainActivity", "⚠️ ACCELEROMETER NOT AVAILABLE on this device!")
            Toast.makeText(this, "Warning: Accelerometer not available!", Toast.LENGTH_LONG).show()
        } else {
            Log.d("MainActivity", "✓ Accelerometer available: ${accelerometer?.name}")
        }
    }

    private fun observeViewModel() {
        // Connection state
        wsViewModel.connectionState.observe(this) { state ->
            when (state) {
                is WebSocketViewModelV3.ConnectionState.Disconnected -> {
                    textConnectionStatus.text = "● Disconnected"
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    buttonConnect.text = "CONNECT"
                    buttonCalibrate.isEnabled = false
                    cardCalibration.alpha = 0.5f
                }
                is WebSocketViewModelV3.ConnectionState.Connecting -> {
                    textConnectionStatus.text = "● Connecting..."
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                    buttonConnect.isEnabled = false
                }
                is WebSocketViewModelV3.ConnectionState.Connected -> {
                    textConnectionStatus.text = "● Connected"
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    buttonConnect.text = "DISCONNECT"
                    buttonConnect.isEnabled = true
                    buttonCalibrate.isEnabled = true
                    cardCalibration.alpha = 1f
                }
                is WebSocketViewModelV3.ConnectionState.Error -> {
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

        // Gesture events - wrapped in try-catch for safety
        wsViewModel.lastGesture.observe(this) { gesture ->
            try {
                gesture?.let {
                    // Debounce - ignore rapid gesture detections
                    val now = System.currentTimeMillis()
                    if (now - lastGestureTime < gestureDebounceMs) {
                        Log.d("MainActivity", "Gesture debounced: ${it.name}")
                        return@let
                    }
                    lastGestureTime = now

                    val confidencePercent = (it.confidence * 100).toInt()

                    if (it.isCalibrationFeedback) {
                        // During calibration - vibrate and flash green
                        try {
                            vibrateSuccess()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Vibration error: $e")
                        }

                        // Flash the instruction background green briefly
                        textCalibrationInstruction.setBackgroundColor(
                            ContextCompat.getColor(this, android.R.color.holo_green_light)
                        )
                        textCalibrationInstruction.text = "✓ ${it.name.uppercase()} DETECTED!"

                        // Reset after short delay
                        textCalibrationInstruction.postDelayed({
                            try {
                                textCalibrationInstruction.setBackgroundResource(R.drawable.instruction_background)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "UI reset error: $e")
                            }
                        }, 500)

                        Log.d("MainActivity", "Calibration gesture detected: ${it.name}")
                    } else {
                        // Normal gameplay - show gesture
                        textGesture.text = "🎯 ${it.name.uppercase()} (${confidencePercent}%)"
                        textGesture.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

                        // Vibrate for gameplay feedback too
                        try {
                            vibrateSuccess()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Vibration error: $e")
                        }

                        // Reset after delay
                        textGesture.postDelayed({
                            try {
                                textGesture.text = "Waiting for gesture..."
                                textGesture.setTextColor(ContextCompat.getColor(this@MainActivityV3, android.R.color.darker_gray))
                            } catch (e: Exception) {
                                Log.e("MainActivity", "UI reset error: $e")
                            }
                        }, 1500)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Gesture observer error: $e")
            }
        }
    }

    private fun updateCalibrationUI(state: WebSocketViewModelV3.CalibrationState) {
        val phaseEmoji = when (state.phase) {
            WebSocketViewModelV3.CalibrationPhase.NOT_STARTED -> "⚪"
            WebSocketViewModelV3.CalibrationPhase.IDLE -> "🧘"
            WebSocketViewModelV3.CalibrationPhase.HIT -> "👊"
            WebSocketViewModelV3.CalibrationPhase.SWING_LEFT -> "👈"
            WebSocketViewModelV3.CalibrationPhase.SWING_RIGHT -> "👉"
            WebSocketViewModelV3.CalibrationPhase.SHAKE -> "🫨"
            WebSocketViewModelV3.CalibrationPhase.TRAINING -> "⚙️"
            WebSocketViewModelV3.CalibrationPhase.COMPLETED -> "✅"
            WebSocketViewModelV3.CalibrationPhase.FAILED -> "❌"
        }

        val phaseName = when (state.phase) {
            WebSocketViewModelV3.CalibrationPhase.NOT_STARTED -> "Not Started"
            WebSocketViewModelV3.CalibrationPhase.IDLE -> "Step 1/5: IDLE"
            WebSocketViewModelV3.CalibrationPhase.HIT -> "Step 2/5: HIT"
            WebSocketViewModelV3.CalibrationPhase.SWING_LEFT -> "Step 3/5: SWING LEFT"
            WebSocketViewModelV3.CalibrationPhase.SWING_RIGHT -> "Step 4/5: SWING RIGHT"
            WebSocketViewModelV3.CalibrationPhase.SHAKE -> "Step 5/5: SHAKE"
            WebSocketViewModelV3.CalibrationPhase.TRAINING -> "Processing..."
            WebSocketViewModelV3.CalibrationPhase.COMPLETED -> "Completed!"
            WebSocketViewModelV3.CalibrationPhase.FAILED -> "Failed"
        }

        textCalibrationPhase.text = "$phaseEmoji $phaseName"
        textCalibrationInstruction.text = state.instruction

        // Progress text
        if (state.phase != WebSocketViewModelV3.CalibrationPhase.NOT_STARTED &&
            state.phase != WebSocketViewModelV3.CalibrationPhase.COMPLETED &&
            state.phase != WebSocketViewModelV3.CalibrationPhase.FAILED &&
            state.phase != WebSocketViewModelV3.CalibrationPhase.TRAINING &&
            state.phase != WebSocketViewModelV3.CalibrationPhase.IDLE) {
            textCalibrationProgress.text = "Gesture ${state.currentCount}/${state.totalCount}"
            textCalibrationProgress.visibility = View.VISIBLE
        } else {
            textCalibrationProgress.visibility = View.GONE
        }

        // Progress bar
        when (state.phase) {
            WebSocketViewModelV3.CalibrationPhase.NOT_STARTED -> {
                progressCalibration.visibility = View.GONE
                buttonCalibrate.text = "START CALIBRATION"
                buttonCalibrate.isEnabled = wsViewModel.connectionState.value == WebSocketViewModelV3.ConnectionState.Connected
            }
            WebSocketViewModelV3.CalibrationPhase.COMPLETED -> {
                progressCalibration.visibility = View.GONE
                buttonCalibrate.text = "RECALIBRATE"
                buttonCalibrate.isEnabled = true
                textCalibrationPhase.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                Toast.makeText(this, "Calibration complete!", Toast.LENGTH_SHORT).show()
            }
            WebSocketViewModelV3.CalibrationPhase.FAILED -> {
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

        var gyroRegistered = false
        var accelRegistered = false

        gyroscope?.also {
            gyroRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d("MainActivity", "Gyroscope registration: ${if (gyroRegistered) "SUCCESS" else "FAILED"}")
        } ?: Log.w("MainActivity", "Gyroscope is null, cannot register")

        accelerometer?.also {
            accelRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d("MainActivity", "Accelerometer registration: ${if (accelRegistered) "SUCCESS" else "FAILED"}")
        } ?: Log.w("MainActivity", "Accelerometer is null, cannot register")

        if (!gyroRegistered) {
            textGyroscope.text = "GYROSCOPE\nNOT AVAILABLE"
            textGyroscope.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val now = System.currentTimeMillis()

            val x = it.values[0]
            val y = it.values[1]
            val z = it.values[2]
            val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)

            when (it.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    // Rate limit gyroscope separately
                    if (now - lastGyroSendTime < sendIntervalMs) return
                    lastGyroSendTime = now

                    // Show values with magnitude for debugging
                    textGyroscope.text = String.format(
                        "X: %+.2f\nY: %+.2f\nZ: %+.2f\nMag: %.2f",
                        x, y, z, magnitude
                    )
                    wsViewModel.send("Gyroscope:\nX: $x\nY: $y\nZ: $z")
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // Rate limit accelerometer separately
                    if (now - lastAccelSendTime < sendIntervalMs) return
                    lastAccelSendTime = now

                    // Show values with magnitude for debugging
                    // Also show dynamic magnitude (minus gravity)
                    val dynamicMag = kotlin.math.sqrt(x * x + y * y + (z - 9.8f) * (z - 9.8f))
                    textAccelerometer.text = String.format(
                        "X: %+.2f\nY: %+.2f\nZ: %+.2f\nMag: %.1f (dyn: %.1f)",
                        x, y, z, magnitude, dynamicMag
                    )
                    wsViewModel.send("Accelerometer:\nX: $x\nY: $y\nZ: $z")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
