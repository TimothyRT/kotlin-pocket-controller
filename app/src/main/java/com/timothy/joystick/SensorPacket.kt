package com.timothy.joystick

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Packet layout (little-endian):
 * ┌────────┬──────────┬──────────────┬──────────────────────────┐
 * │ type   │ seq (4B) │ timestamp(4B)│ float32 payload (N×4B)   │
 * │ 1 byte │ int32    │ int32 ms     │ sensor values...         │
 * └────────┴──────────┴──────────────┴──────────────────────────┘
 * Total for 6-axis IMU: 1 + 4 + 4 + 24 = 33 bytes  (vs ~120 bytes JSON)
 */
object SensorPacket {

    const val TYPE_SENSOR : Byte = 0x01
    const val TYPE_COMMAND: Byte = 0x02

    private val seq = AtomicInteger(0)

    // Pre-allocate a reusable buffer — call encodeSensor() from ONE thread only
    private val sensorBuf = ByteBuffer
        .allocate(256)
        .order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Encode sensor floats into a binary packet.
     * Returns the backing array slice — valid until the next call.
     * Call only from your sensor thread.
     */
    fun encodeSensor(floats: FloatArray): ByteArray {
        sensorBuf.clear()
        sensorBuf.put(TYPE_SENSOR)
        sensorBuf.putInt(seq.getAndIncrement())
        sensorBuf.putInt((System.currentTimeMillis() and 0xFFFFFFFFL).toInt())
        for (f in floats) sensorBuf.putFloat(f)
        // Return a copy only for the send buffer — one allocation here, none in the loop
        return sensorBuf.array().copyOf(sensorBuf.position())
    }

    fun encodeCommand(cmd: String): ByteArray {
        val cmdBytes = cmd.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + cmdBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_COMMAND)
            .put(cmdBytes)
            .array()
    }
}