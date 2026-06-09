package com.timothy.joystick

import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JoystickActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val EXTRA_IP = "extra_ip"
        private const val TAG = "JoystickActivity"
        private const val AXIS_SEND_INTERVAL_MS = 50L
        private const val WINDOW_WIDTH = 15
        private const val SAMPLE_INTERVAL_MS = 33L
    }

    private val wsViewModel: WebSocketViewModel by viewModels()

    // Thumbstick view references
    private var leftStickView:  VirtualThumbstick? = null

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var gyroscope:      Sensor? = null
    private var accelerometer:  Sensor? = null
    private var magnetometer:   Sensor? = null
    private var rotationVector: Sensor? = null

    @Volatile private var latestGyro = FloatArray(3)
    @Volatile private var latestAcc  = FloatArray(3)
    @Volatile private var latestMag  = FloatArray(3)
    @Volatile private var latestQuat = floatArrayOf(0f, 0f, 0f, 1f)
    private var hasAcc       = false
    private var lastSampleMs = 0L
    @Volatile var isGesturePressed = false

    // Sensor data buffer
    private val dataBuffer = linkedMapOf<String, MutableList<Any>>(
        "gyro_x"   to mutableListOf(),
        "gyro_y"   to mutableListOf(),
        "gyro_z"   to mutableListOf(),
        "acc_x"    to mutableListOf(),
        "acc_y"    to mutableListOf(),
        "acc_z"    to mutableListOf(),
        "gesture"  to mutableListOf()
    )
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    // Coroutines
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var axisSenderJob: Job? = null


    // Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        initSensors()
        observeViewModelForBusinessLogic()

        setContent {
            val connectionState by wsViewModel.connectionState.observeAsState()

            JoystickScreen(
                connectionState   = connectionState,
                onDisconnect      = { finish() },
                onButtonPress   = { name ->
                    wsViewModel.send("BTN:$name:1")
                    if (name == "GESTURE_BUTTON_NAME") setGestureState(true)
                },
                onButtonRelease = { name ->
                    wsViewModel.send("BTN:$name:0")
                    if (name == "GESTURE_BUTTON_NAME") setGestureState(false)
                },
                onAxisChange = { axis, value ->
                    wsViewModel.send("AXIS:$axis:$value")
                }
            )
        }

        val ip = intent.getStringExtra(EXTRA_IP) ?: ""
        if (ip.isNotEmpty()) wsViewModel.connect(ip)
    }

    override fun onResume() {
        super.onResume()
        gyroscope?.let      { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let   { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        axisSenderJob?.cancel()
        super.onDestroy()
    }


    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER  -> { latestAcc = event.values.clone(); hasAcc = true }
            Sensor.TYPE_MAGNETIC_FIELD -> { latestMag = event.values.clone() }

            Sensor.TYPE_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                latestQuat = floatArrayOf(q[1], q[2], q[3], q[0]) // [x, y, z, w]
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (!hasAcc) return
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSampleMs < SAMPLE_INTERVAL_MS) return
                lastSampleMs = nowMs

                latestGyro = event.values.clone()

                // Send (Instant "Should be")
                sendSensorBinary(latestAcc, latestGyro, isGesturePressed)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendSensorBinary(acc: FloatArray, gyro: FloatArray, gesturePressed: Boolean) {
        // Only send if connected
        if (wsViewModel.connectionState.value !is UDPManager.ConnectionState.Connected) return

        // Allocated 27 bytes: 1 (type) + 1 (player_id) + 24 (6 floats) + 1 (boolean)
        val buffer = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(1.toByte()) // Packet Type 1: Sensor Data

        // Player ID (1 byte)
        buffer.put(UDPManager.playerId)

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

    fun setGestureState(isPressed: Boolean){
        isGesturePressed = isPressed
    }

    // Axis sender
    private fun startAxisSender() {
        axisSenderJob?.cancel()
        axisSenderJob = scope.launch {
            var prevLX = 0f; var prevLY = 0f
            while (true) {
                delay(AXIS_SEND_INTERVAL_MS)
                if (wsViewModel.connectionState.value !is UDPManager.ConnectionState.Connected) continue

                val lx = leftStickView?.normX  ?: 0f
                val ly = leftStickView?.normY  ?: 0f

                if (lx != prevLX) { wsViewModel.send("AXIS:LEFT_X:$lx");  prevLX = lx }
                if (ly != prevLY) { wsViewModel.send("AXIS:LEFT_Y:$ly");  prevLY = ly }
            }
        }
    }


    // Sensor init
    private fun initSensors() {
        sensorManager  = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroscope      = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer  = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer   = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }


    // ViewModel observers
    private fun observeViewModelForBusinessLogic() {
        wsViewModel.connectionState.observe(this) { state ->
            when (state) {
                is UDPManager.ConnectionState.Connected -> {
                    startAxisSender()
                    Log.d(TAG, "Connected — axis sender + sensor pipeline active")
                }
                is UDPManager.ConnectionState.Disconnected,
                is UDPManager.ConnectionState.Error -> axisSenderJob?.cancel()
                else -> Unit
            }
        }

        if (wsViewModel.connectionState.value is UDPManager.ConnectionState.Connected) {
            startAxisSender()
            Log.d(TAG, "Already connected on start — axis sender + sensor pipeline active")
        }

        wsViewModel.lastGesture.observe(this) { gesture ->
            gesture?.let { Log.d(TAG, "Gesture from server: ${it.name} (${it.confidence})") }
        }

        wsViewModel.vibrateEvent.observe(this) {
            triggerVibration()
        }
    }

    private fun triggerVibration() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator

        // Half a second vibration
        val durationMs = 500L

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val effect = android.os.VibrationEffect.createOneShot(
                durationMs,
                100
            )
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
}