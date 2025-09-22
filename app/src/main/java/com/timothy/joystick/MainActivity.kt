package com.timothy.joystick

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import org.w3c.dom.Text


class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null

//    private lateinit var textView: TextView

    private lateinit var inputIpAddress: TextInputEditText
    private lateinit var buttonConnect: Button
    private lateinit var textGyroscope: TextView
    private lateinit var textAccelerometer: TextView

    private val wsViewModel: WebSocketViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        inputIpAddress = findViewById(R.id.input_ip_address)
        buttonConnect = findViewById(R.id.button_connect)
        textGyroscope = findViewById(R.id.text_gyroscope)
        textAccelerometer = findViewById(R.id.text_accelerometer)

        buttonConnect.setOnClickListener {
            wsViewModel.connect(inputIpAddress.text.toString())
        }

//        textView = TextView(this)
//        setContentView(textView)

        // Initialize the sensor manager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (gyroscope == null) {
            textGyroscope.text = "No Gyroscope Sensor Found!"
        }
        if (accelerometer == null) {
            textAccelerometer.text = "No Accelerometer Sensor Found!"
        }
    }

    override fun onResume() {
        super.onResume()
        gyroscope?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val datetime = DatetimeManager.now()
            when (it.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val x = it.values[0] // Rotation around X-axis
                    val y = it.values[1] // Rotation around Y-axis
                    val z = it.values[2] // Rotation around Z-axis

                    val message = "Gyroscope:\nX: $x\nY: $y\nZ: $z [Sent: $datetime}]"
                    textGyroscope.text = message
                    wsViewModel.send(message)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = it.values[0] // Acceleration force along X-axis
                    val y = it.values[1] // Acceleration force along Y-axis
                    val z = it.values[2] // Acceleration force along Z-axis

                    val message = "Accelerometer:\nX: $x\nY: $y\nZ: $z [Sent: $datetime]"
                    textAccelerometer.text = message
                    wsViewModel.send(message)
                }
            }

        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for basic gyroscope use
    }
}