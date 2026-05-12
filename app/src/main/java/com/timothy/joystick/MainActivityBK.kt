package com.timothy.joystick

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivityBK : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG            = "MainActivity"
        private const val WINDOW_WIDTH   = 15
        private const val SAMPLE_INTERVAL_MS = 33L
    }

    // Sensor
    private lateinit var sensorManager: SensorManager
    private var gyroscope:      Sensor? = null
    private var accelerometer:  Sensor? = null
    private var magnetometer:   Sensor? = null
    private var rotationVector: Sensor? = null

    @Volatile private var latestGyro = FloatArray(3)
    @Volatile private var latestAcc  = FloatArray(3)
    @Volatile private var latestMag  = FloatArray(3)
    @Volatile private var latestQuat = floatArrayOf(0f, 0f, 0f, 1f)
    private var hasAcc = false

    private val dataBuffer = linkedMapOf<String, MutableList<Any>>(
        "gyro_x"   to mutableListOf(),
        "gyro_y"   to mutableListOf(),
        "gyro_z"   to mutableListOf(),
        "acc_x"    to mutableListOf(),
        "acc_y"    to mutableListOf(),
        "acc_z"    to mutableListOf(),
        "gesture"  to mutableListOf()
    )

    private val dateFormat     = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private var lastSampleMs   = 0L
    private var isGesturePressed = false

    // UI
    private lateinit var inputIpAddress:       TextInputEditText
    private lateinit var buttonConnect:        Button
    private lateinit var textConnectionStatus: TextView
    private lateinit var textGesture:          TextView
    private lateinit var textGyroscope:        TextView
    private lateinit var textAccelerometer:    TextView
    private lateinit var textMagnetometer:     TextView
    private lateinit var textAhrs:             TextView
    private lateinit var buttonGesture:        Button
    private lateinit var buttonJoystickMode:   Button
    private lateinit var viewStatusDot:        View

    private val wsViewModel: WebSocketViewModel by viewModels()
    private var lastGestureTime    = 0L
    private val gestureDebounceMs  = 500L
    private lateinit var vibrator: Vibrator

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
        gyroscope?.let      { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)   }
        accelerometer?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)   }
        magnetometer?.let   { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)   }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // Sensor Event Listener
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER  -> { latestAcc = event.values.clone(); hasAcc = true }
            Sensor.TYPE_MAGNETIC_FIELD -> { latestMag = event.values.clone() }

            Sensor.TYPE_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                latestQuat = floatArrayOf(q[1], q[2], q[3], q[0])
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (!hasAcc) return
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSampleMs < SAMPLE_INTERVAL_MS) return
                lastSampleMs = nowMs

                latestGyro = event.values.clone()

                // Update the UI
                updateSensorDisplay(latestGyro, latestAcc, latestMag, latestQuat[3], latestQuat[0], latestQuat[1], latestQuat[2])

                // Send (Instant "Should be")
                sendSensorBinary(latestAcc, latestGyro, isGesturePressed)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendSensorBinary(acc: FloatArray, gyro: FloatArray, gesturePressed: Boolean) {
        // Only send if connected
        if (wsViewModel.connectionState.value !is UDPManager.ConnectionState.Connected) return

        // Allocated 26 bytes: 1 (type) + 24 (6 floats) + 1 (boolean)
        val buffer = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(1.toByte()) // Packet Type 1: Sensor Data

        // Accelerometer (12 bytes)
        buffer.putFloat(acc[0])
        buffer.putFloat(acc[1])
        buffer.putFloat(acc[2])

        // Gyroscope (12 bytes)
        buffer.putFloat(gyro[0])
        buffer.putFloat(gyro[1])
        buffer.putFloat(gyro[2])

        // Gesture State (1 byte)
        buffer.put((if (gesturePressed) 1 else 0).toByte())

        // Send
        wsViewModel.sendBytes(buffer.array())
    }


    private fun updateSensorDisplay(
        gyro: FloatArray, acc: FloatArray, mag: FloatArray,
        qw: Float, qx: Float, qy: Float, qz: Float
    ) = runOnUiThread {
        textGyroscope.text     = "X: %+.2f\nY: %+.2f\nZ: %+.2f".format(gyro[0], gyro[1], gyro[2])
        textAccelerometer.text = "X: %+.2f\nY: %+.2f\nZ: %+.2f".format(acc[0],  acc[1],  acc[2])
        textMagnetometer.text  = "X: %+.1f\nY: %+.1f\nZ: %+.1f".format(mag[0],  mag[1],  mag[2])
        textAhrs.text          = "w:%.3f\nx:%.3f\ny:%.3f\nz:%.3f".format(qw, qx, qy, qz)
    }

    // Init

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
        buttonJoystickMode   = findViewById(R.id.button_joystick_mode)
        viewStatusDot        = findViewById(R.id.view_status_dot)

        // Launch Controller
        buttonJoystickMode.setOnClickListener {
            val ip = inputIpAddress.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter IP address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, JoystickActivity::class.java).apply {
                putExtra(JoystickActivity.EXTRA_IP, ip)
            }

            val options = ActivityOptionsCompat.makeCustomAnimation(
                this,
                R.anim.p5_enter,
                R.anim.p5_exit
            )

            startActivity(intent, options.toBundle())
        }

        // Connect / Disconnect
        buttonConnect.setOnClickListener {
            val ip = inputIpAddress.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Enter IP address first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            when (wsViewModel.connectionState.value) {
                is UDPManager.ConnectionState.Connected -> wsViewModel.disconnect()
                else -> wsViewModel.connect(ip)
            }
        }

        // Gesture Hold Button
        buttonGesture.setOnTouchListener { _, me ->
            when (me.action) {
                MotionEvent.ACTION_DOWN                            -> isGesturePressed = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL  -> isGesturePressed = false
            }
            false
        }
        // Start visually inactive; enabled only once connected
        buttonGesture.alpha     = 0.4f
        buttonGesture.isEnabled = false
    }

    private fun initSensors() {
        sensorManager  = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroscope       = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer   = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer    = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        rotationVector  = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVector == null) Log.w(TAG, "Rotation vector unavailable — AHRS will be identity")
        if (magnetometer   == null) Log.w(TAG, "Magnetometer unavailable — heading accuracy reduced")
    }

    // Viewmodel Observer

    private fun observeViewModel() {

        wsViewModel.connectionState.observe(this) { state ->
            when (state) {

                is UDPManager.ConnectionState.Disconnected -> {
                    setStatusUi(
                        statusText  = "DISCONNECTED",
                        statusColor = android.R.color.holo_red_dark,
                        dotColor    = android.R.color.holo_red_dark,
                        btnLabel    = "CONNECT",
                        btnEnabled  = true,
                        gestureOn   = false
                    )
                }

                is UDPManager.ConnectionState.Connecting -> {
                    setStatusUi(
                        statusText  = "CONNECTING...",
                        statusColor = android.R.color.holo_orange_dark,
                        dotColor    = android.R.color.holo_orange_dark,
                        btnLabel    = "CONNECT",
                        btnEnabled  = false,
                        gestureOn   = false
                    )
                }

                is UDPManager.ConnectionState.Connected -> {
                    setStatusUi(
                        statusText  = "CONNECTED",
                        statusColor = android.R.color.holo_green_dark,
                        dotColor    = android.R.color.holo_green_dark,
                        btnLabel    = "DISCONNECT",
                        btnEnabled  = true,
                        gestureOn   = true
                    )
                }

                is UDPManager.ConnectionState.Error -> {
                    setStatusUi(
                        statusText  = "ERR: ${state.message}",
                        statusColor = android.R.color.holo_red_dark,
                        dotColor    = android.R.color.holo_red_dark,
                        btnLabel    = "CONNECT",
                        btnEnabled  = true,
                        gestureOn   = false
                    )
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

                    // Reset gesture label after 1.5 s
                    textGesture.postDelayed({
                        try { textGesture.text = "" }
                        catch (e: Exception) { Log.e(TAG, "UI reset: $e") }
                    }, 1500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gesture observer error: $e")
            }
        }
    }

    private fun setStatusUi(
        statusText:  String,
        statusColor: Int,
        dotColor:    Int,
        btnLabel:    String,
        btnEnabled:  Boolean,
        gestureOn:   Boolean
    ) {
        val color = ContextCompat.getColor(this, statusColor)
        val dot   = ContextCompat.getColor(this, dotColor)

        textConnectionStatus.text  = statusText
        textConnectionStatus.setTextColor(color)

        viewStatusDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(dot)

        buttonConnect.text      = btnLabel
        buttonConnect.isEnabled = btnEnabled

        buttonGesture.isEnabled = gestureOn
        buttonGesture.alpha     = if (gestureOn) 1f else 0.4f
    }
}