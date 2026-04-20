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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JoystickActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val EXTRA_IP = "extra_ip"
        private const val TAG = "JoystickActivity"
        private const val AXIS_SEND_INTERVAL_MS = 50L
        private const val WINDOW_WIDTH = 10
        private const val SAMPLE_INTERVAL_MS = 16L
    }

    private val wsViewModel: WebSocketViewModel by viewModels()

    // Thumbstick view references
    private var leftStickView:  VirtualThumbstick? = null
    private var rightStickView: VirtualThumbstick? = null

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

    // ── Sensor data buffer (gesture removed) ─────────────────────────────────
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
        "datetime" to mutableListOf()
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
                onButtonPress     = { name -> wsViewModel.send("BTN:$name:1") },
                onButtonRelease   = { name -> wsViewModel.send("BTN:$name:0") },
                onLeftStickReady  = { view -> leftStickView  = view },
                onRightStickReady = { view -> rightStickView = view }
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
                val (qx, qy, qz, qw) = latestQuat
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

                if ((dataBuffer["datetime"]?.size ?: 0) >= WINDOW_WIDTH) flushBuffer()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}


    // Buffer helpers
    private fun flushBuffer() {
        if (wsViewModel.connectionState.value != WebSocketManager.ConnectionState.Connected) {
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
                    is Float -> arr.put(v.toDouble())
                    else     -> arr.put(v.toString())
                }
            }
            obj.put(key, arr)
        }
        return obj.toString()
    }

    private fun clearBuffer() = dataBuffer.values.forEach { it.clear() }


    // Axis sender
    private fun startAxisSender() {
        axisSenderJob?.cancel()
        axisSenderJob = scope.launch {
            var prevLX = 0f; var prevLY = 0f
            var prevRX = 0f; var prevRY = 0f
            while (true) {
                delay(AXIS_SEND_INTERVAL_MS)
                if (wsViewModel.connectionState.value != WebSocketManager.ConnectionState.Connected) continue

                val lx = leftStickView?.normX  ?: 0f
                val ly = leftStickView?.normY  ?: 0f
                val rx = rightStickView?.normX ?: 0f
                val ry = rightStickView?.normY ?: 0f

                if (lx != prevLX) { wsViewModel.send("AXIS:LEFT_X:$lx");  prevLX = lx }
                if (ly != prevLY) { wsViewModel.send("AXIS:LEFT_Y:$ly");  prevLY = ly }
                if (rx != prevRX) { wsViewModel.send("AXIS:RIGHT_X:$rx"); prevRX = rx }
                if (ry != prevRY) { wsViewModel.send("AXIS:RIGHT_Y:$ry"); prevRY = ry }
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
                is WebSocketManager.ConnectionState.Connected -> {
                    clearBuffer()
                    startAxisSender()
                    Log.d(TAG, "Connected — axis sender + sensor pipeline active")
                }
                is WebSocketManager.ConnectionState.Disconnected,
                is WebSocketManager.ConnectionState.Error -> axisSenderJob?.cancel()
                else -> Unit
            }
        }

        wsViewModel.lastGesture.observe(this) { gesture ->
            gesture?.let { Log.d(TAG, "Gesture from server: ${it.name} (${it.confidence})") }
        }
    }
}