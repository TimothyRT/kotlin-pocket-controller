package com.timothy.joystick

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pre-allocated, zero-GC sensor window.
 * Replaces Map<String, MutableList<Any>> — no boxing, no resizing.
 *
 * Binary wire format (little-endian):
 * ┌──────┬──────────┬────────────────┬───────────┬──────────────────────────────┬───────────────┐
 * │ 0x03 │ seq (4B) │ start_ms (8B)  │ count (2B)│ 13 channels × count × 4B    │ gesture count │
 * └──────┴──────────┴────────────────┴───────────┴──────────────────────────────┴───────────────┘
 * Header total: 15 bytes
 * For count=50: 15 + (13×50×4) + 50 = 2,665 bytes  (vs ~4,000+ bytes JSON)
 */
class SensorWindow(val size: Int) {

    // One FloatArray per channel — no boxing, CPU cache-friendly
    val gyroX  = FloatArray(size)
    val gyroY  = FloatArray(size)
    val gyroZ  = FloatArray(size)
    val accX   = FloatArray(size)
    val accY   = FloatArray(size)
    val accZ   = FloatArray(size)
    val magX   = FloatArray(size)
    val magY   = FloatArray(size)
    val magZ   = FloatArray(size)
    val ahrsX  = FloatArray(size)
    val ahrsY  = FloatArray(size)
    val ahrsZ  = FloatArray(size)
    val ahrsW  = FloatArray(size)
    val gesture = ByteArray(size)  // 0 or 1 — no boxing

    var count    = 0
    var startMs  = 0L

    val isFull get() = count >= size

    fun reset() {
        count   = 0
        startMs = 0L
    }

    fun push(
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
        mx: Float, my: Float, mz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        gest: Boolean
    ) {
        if (count == 0) startMs = System.currentTimeMillis()
        val i = count++
        gyroX[i] = gx;  gyroY[i] = gy;  gyroZ[i] = gz
        accX[i]  = ax;  accY[i]  = ay;  accZ[i]  = az
        magX[i]  = mx;  magY[i]  = my;  magZ[i]  = mz
        ahrsX[i] = qx;  ahrsY[i] = qy;  ahrsZ[i] = qz;  ahrsW[i] = qw
        gesture[i] = if (gest) 1 else 0
    }

    /**
     * Encode the filled window to binary.
     * Called once per window — allocation here is acceptable (~2–3 KB, once every N samples).
     */
    fun toBinary(seq: Int): ByteArray {
        val n     = count
        val total = 15 + 13 * n * 4 + n   // header + float channels + gesture bytes
        val buf   = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(0x03.toByte())    // TYPE_WINDOW
        buf.putInt(seq)
        buf.putLong(startMs)
        buf.putShort(n.toShort())

        // Channels in a fixed order Godot will mirror
        for (arr in arrayOf(gyroX, gyroY, gyroZ, accX, accY, accZ,
            magX, magY, magZ, ahrsX, ahrsY, ahrsZ, ahrsW))
            for (i in 0 until n) buf.putFloat(arr[i])

        buf.put(gesture, 0, n)
        return buf.array()
    }
}