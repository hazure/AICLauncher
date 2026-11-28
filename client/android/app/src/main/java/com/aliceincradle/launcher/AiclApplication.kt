package com.aliceincradle.launcher

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiclApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashSummaryHandler()
    }

    private fun installCrashSummaryHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val diagDir = File(applicationContext.filesDir, "diagnostics")
                if (!diagDir.exists()) {
                    diagDir.mkdirs()
                }
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                val topFrames = throwable.stackTrace.take(15).joinToString("\n") { "  at $it" }
                val causeLine = throwable.cause?.let { c ->
                    "Cause: ${c.javaClass.name}: ${c.message.orEmpty()}"
                } ?: "Cause: none"
                val summary = buildString {
                    appendLine("Timestamp: $timestamp")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
                    appendLine(causeLine)
                    appendLine("StackTrace (top 15):")
                    appendLine(topFrames)
                }
                File(diagDir, "last_crash_summary.txt").writeText(summary, Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.e("AICL_CRASH", "Failed to persist crash summary", t)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
