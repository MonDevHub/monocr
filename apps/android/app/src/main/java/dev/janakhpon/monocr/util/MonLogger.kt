package dev.janakhpon.monocr.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A lightweight, structured logging utility for MonOCR.
 * Standardizes log tags and writes to a local file for privacy-first observability.
 */
object MonLogger {
    private const val TAG = "MonOCR"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1 MB
    var applicationContext: Context? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun appendToFile(level: String, message: String, throwable: Throwable? = null) {
        val ctx = applicationContext ?: return
        try {
            val logFile = getLogFile(ctx)
            if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                logFile.writeText("")  // Rotate: clear the file
            }

            val timestamp = dateFormat.format(Date())
            var fullMessage = "[$timestamp] $level/$TAG: $message\n"
            if (throwable != null) {
                fullMessage += Log.getStackTraceString(throwable) + "\n"
            }

            logFile.appendText(fullMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    fun getLogFile(context: Context): File {
        return File(context.cacheDir, "monocr_logs.txt")
    }

    fun d(message: String) {
        if (dev.janakhpon.monocr.BuildConfig.DEBUG) {
            Log.d(TAG, message)
            appendToFile("D", message)
        }
    }

    fun i(message: String) {
        if (dev.janakhpon.monocr.BuildConfig.DEBUG) {
            Log.i(TAG, message)
            appendToFile("I", message)
        }
    }

    fun w(message: String) {
        Log.w(TAG, message)
        appendToFile("W", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        appendToFile("E", message, throwable)
    }
}
