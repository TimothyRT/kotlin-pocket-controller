package com.timothy.joystick

import android.annotation.SuppressLint
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
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {
    // Constants
    companion object {
        private const val TAG = "MainActivity"
        /** Panjang Windows */
        private const val WINDOW_WIDTH = 10

        /** Minimum ms antara tiap sample (60 Hz, 1/60 s tick) */
        private const val SAMPLE_INTERVAL_MS = 16L
    }

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var gyroscope:      Sensor? = null
    private var accelerometer:  Sensor? = null
    private var magnetometer:   Sensor? = null
    private var rotationVector: Sensor? = null   // AHRS

    @Volatile private var latestGyro = FloatArray(3)
    @Volatile private var latestAcc  = FloatArray(3)
    @Volatile private var latestMag  = FloatArray(3)
    @Volatile private var latestQuat = floatArrayOf(0f, 0f, 0f, 1f)

    private var hasAcc = false

    // Data buffer
    private val dataBuffer = linkedMapOf<String, MutableList<Any>>(
        "gyro_x"   to mutableListOf(),
        "gyro_y"   to mutableListOf(),
        "gyro_z"   to mutableListOf(),
        "acc_x"    to mutableListOf(),
        "acc_y"    to mutableListOf(),
        "acc_z"    to mutableListOf(),
        "mag_x"    to mutableListOf(),
        "mag_y"    to mutableListOf(),
        "mag_z"    to mutableListOf(),
        "ahrs_x"   to mutableListOf(),
        "ahrs_y"   to mutableListOf(),
        "ahrs_z"   to mutableListOf(),
        "ahrs_w"   to mutableListOf(),
        "datetime" to mutableListOf(),
        "gesture"  to mutableListOf()
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private var lastSampleMs = 0L
    private var isGesturePressed = false

    // UI reference
    private lateinit var inputIpAddress:       TextInputEditText
    private lateinit var buttonConnect:        Button
    private lateinit var textConnectionStatus: TextView
    private lateinit var textGesture:          TextView
    private lateinit var textGyroscope:        TextView
    private lateinit var textAccelerometer:    TextView
    private lateinit var textMagnetometer:     TextView
    private lateinit var textAhrs:             TextView
    private lateinit var buttonGesture:        Button

    private val wsViewModel: WebSocketViewModel by viewModels()
    private var lastGestureTime = 0L
    private val gestureDebounceMs = 500L

    // Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        initVibrator()
        initViews()
        initSensors()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        gyroscope?.let      { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let   { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {

            // Raw sensors
            Sensor.TYPE_ACCELEROMETER -> { latestAcc = event.values.clone(); hasAcc = true }
            Sensor.TYPE_MAGNETIC_FIELD -> { latestMag = event.values.clone() }

            // AHRS
            Sensor.TYPE_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                latestQuat = floatArrayOf(q[1], q[2], q[3], q[0]) // [x, y, z, w]
            }

            // Gyroscope
            Sensor.TYPE_GYROSCOPE -> {
                if (!hasAcc) return

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSampleMs < SAMPLE_INTERVAL_MS) return
                lastSampleMs = nowMs

                latestGyro = event.values.clone()

                val (qx, qy, qz, qw) = latestQuat

                // Append ke buffer
                val ts = dateFormat.format(Date())
                dataBuffer["gyro_x"]!!   += latestGyro[0]
                dataBuffer["gyro_y"]!!   += latestGyro[1]
                dataBuffer["gyro_z"]!!   += latestGyro[2]
                dataBuffer["acc_x"]!!    += latestAcc[0]
                dataBuffer["acc_y"]!!    += latestAcc[1]
                dataBuffer["acc_z"]!!    += latestAcc[2]
                dataBuffer["mag_x"]!!    += latestMag[0]
                dataBuffer["mag_y"]!!    += latestMag[1]
                dataBuffer["mag_z"]!!    += latestMag[2]
                dataBuffer["ahrs_x"]!!   += qx
                dataBuffer["ahrs_y"]!!   += qy
                dataBuffer["ahrs_z"]!!   += qz
                dataBuffer["ahrs_w"]!!   += qw
                dataBuffer["datetime"]!! += ts
                dataBuffer["gesture"]!!  += isGesturePressed

                updateSensorDisplay(latestGyro, latestAcc, latestMag, qw, qx, qy, qz)

                // Flush kalau sudah penuh datanya
                if ((dataBuffer["gesture"]?.size ?: 0) >= WINDOW_WIDTH) flushBuffer()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Buffer flush
    // (Serialisasi ke dalam bentuk JSON dan kirim data lewat WebSocket)
    private fun flushBuffer() {
        if (wsViewModel.connectionState.value != WebSocketViewModel.ConnectionState.Connected) {
            clearBuffer(); return
        }
        wsViewModel.send(buildJson())
        clearBuffer()
    }

    private fun buildJson(): String {
        val obj = JSONObject()
        dataBuffer.forEach { (key, values) ->
            val arr = JSONArray()
            values.forEach { v ->
                when (v) {
                    is Boolean -> arr.put(v)
                    is Float   -> arr.put(v.toDouble())
                    else       -> arr.put(v.toString())
                }
            }
            obj.put(key, arr)
        }
        return obj.toString()
    }

    private fun clearBuffer() = dataBuffer.values.forEach { it.clear() }

    // Display
    private fun updateSensorDisplay(
        gyro: FloatArray, acc: FloatArray, mag: FloatArray,
        qw: Float, qx: Float, qy: Float, qz: Float
    ) = runOnUiThread {
        textGyroscope.text     = "X: %+.2f\nY: %+.2f\nZ: %+.2f".format(gyro[0], gyro[1], gyro[2])
        textAccelerometer.text = "X: %+.2f\nY: %+.2f\nZ: %+.2f".format(acc[0],  acc[1],  acc[2])
        textMagnetometer.text  = "X: %+.1f\nY: %+.1f\nZ: %+.1f".format(mag[0],  mag[1],  mag[2])
        textAhrs.text          = "w:%.3f  x:%.3f\ny:%.3f  z:%.3f".format(qw, qx, qy, qz)
    }

    // Init helpers
    private lateinit var vibrator: Vibrator

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrateSuccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        inputIpAddress       = findViewById(R.id.input_ip_address)
        buttonConnect        = findViewById(R.id.button_connect)
        textConnectionStatus = findViewById(R.id.text_connection_status)
        textGesture          = findViewById(R.id.text_gesture)
        textGyroscope        = findViewById(R.id.text_gyroscope)
        textAccelerometer    = findViewById(R.id.text_accelerometer)
        textMagnetometer     = findViewById(R.id.text_magnetometer)
        textAhrs             = findViewById(R.id.text_ahrs)
        buttonGesture        = findViewById(R.id.button_gesture)

        buttonConnect.setOnClickListener {
            val ip = inputIpAddress.text.toString().trim()
            if (ip.isEmpty()) { Toast.makeText(this, "Enter IP address", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            when (wsViewModel.connectionState.value) {
                is WebSocketViewModel.ConnectionState.Connected -> wsViewModel.disconnect()
                else -> wsViewModel.connect(ip)
            }
        }

        buttonGesture.setOnTouchListener { _, me ->
            when (me.action) {
                MotionEvent.ACTION_DOWN                        -> { isGesturePressed = true;  buttonGesture.alpha = 1f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isGesturePressed = false; buttonGesture.alpha = 0.6f }
            }
            false
        }
        buttonGesture.alpha = 0.6f
        buttonGesture.isEnabled = false
    }

    private fun initSensors() {
        sensorManager  = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroscope       = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer   = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer    = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        rotationVector  = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVector == null) Log.w(TAG, "⚠ Rotation vector unavailable — AHRS will be identity")
        if (magnetometer   == null) Log.w(TAG, "⚠ Magnetometer unavailable — heading accuracy reduced")
    }

    // ViewModel observers
    private fun observeViewModel() {
        wsViewModel.connectionState.observe(this) { state ->
            when (state) {
                is WebSocketViewModel.ConnectionState.Disconnected -> {
                    textConnectionStatus.text = "Disconnected"
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    buttonConnect.text = "CONNECT"
                    buttonGesture.isEnabled = false
                }
                is WebSocketViewModel.ConnectionState.Connecting -> {
                    textConnectionStatus.text = "Connecting..."
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                    buttonConnect.isEnabled = false
                }
                is WebSocketViewModel.ConnectionState.Connected -> {
                    textConnectionStatus.text = "Connected"
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    buttonConnect.text = "DISCONNECT"
                    buttonConnect.isEnabled = true
                    buttonGesture.isEnabled = true
                    clearBuffer()
                }
                is WebSocketViewModel.ConnectionState.Error -> {
                    textConnectionStatus.text = "Error: ${state.message}"
                    textConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    buttonConnect.text = "CONNECT"
                    buttonConnect.isEnabled = true
                }
            }
        }

        wsViewModel.lastGesture.observe(this) { gesture ->
            try {
                gesture?.let {
                    val now = System.currentTimeMillis()
                    if (now - lastGestureTime < gestureDebounceMs) return@let
                    lastGestureTime = now

                    val pct = (it.confidence * 100).toInt()
                    textGesture.text = "${it.name.uppercase()} ($pct%)"
                    textGesture.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    vibrateSuccess()

                    textGesture.postDelayed({
                        try {
                            textGesture.text = "Waiting for gesture..."
                            textGesture.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                        } catch (e: Exception) { Log.e(TAG, "UI reset: $e") }
                    }, 1500)
                }
            } catch (e: Exception) { Log.e(TAG, "Gesture observer error: $e") }
        }
    }
}