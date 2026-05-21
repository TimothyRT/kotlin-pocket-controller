package com.timothy.joystick

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LatencyLogger {

    private const val TAG = "LatencyLogger"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var writer: FileWriter? = null
    private var currentFile: File? = null
    private var sampleCount = 0

    fun startSession(context: Context, playerSlot: Byte) {
        try {
            val dir = File(context.filesDir, "latency_logs").also { it.mkdirs() }
            val fileName = "latency_P${playerSlot}_${fileNameFormat.format(Date())}.csv"
            currentFile = File(dir, fileName)

            writer = FileWriter(currentFile, false)
            // CSV Header
            writer?.appendLine("sample,timestamp,rtt_ms,player_id")
            writer?.flush()

            sampleCount = 0
            Log.d(TAG, "Session started → ${currentFile?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session: $e")
        }
    }

    fun log(rttMs: Long, playerId: Byte) {
        try {
            sampleCount++
            val timestamp = dateFormat.format(Date())
            writer?.appendLine("$sampleCount,$timestamp,$rttMs,$playerId")
            writer?.flush()  // flush each line so data isn't lost on crash
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log sample: $e")
        }
    }

    fun stopSession() {
        try {
            writer?.close()
            writer = null
            Log.d(TAG, "Session stopped. $sampleCount samples written.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop session: $e")
        }
    }

    fun shareLatestFile(context: Context) {
        val file = currentFile ?: run {
            Log.w(TAG, "No file to share")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Latency Log - ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export Latency Data"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share file: $e")
        }
    }

    fun getSessionStats(): SessionStats? {
        val file = currentFile ?: return null
        val rtts = mutableListOf<Long>()

        try {
            file.forEachLine { line ->
                if (line.startsWith("sample")) return@forEachLine  // skip header
                val rtt = line.split(",").getOrNull(2)?.toLongOrNull() ?: return@forEachLine
                rtts.add(rtt)
            }
        } catch (e: Exception) { return null }

        if (rtts.isEmpty()) return null

        return SessionStats(
            samples  = rtts.size,
            minMs    = rtts.min(),
            maxMs    = rtts.max(),
            avgMs    = rtts.average(),
            stdDevMs = rtts.stdDev()
        )
    }

    data class SessionStats(
        val samples:  Int,
        val minMs:    Long,
        val maxMs:    Long,
        val avgMs:    Double,
        val stdDevMs: Double
    )

    private fun List<Long>.stdDev(): Double {
        val mean = average()
        return Math.sqrt(sumOf { (it - mean) * (it - mean) } / size)
    }
}