package com.aliceincradle.launcher.data

import android.util.Log
import java.io.File
import java.io.IOException

internal object SafeProbeIo {
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        } catch (e: RuntimeException) {
            System.err.println("[$tag] WARN: $message (${throwable?.message ?: e.message})")
        }
    }

    fun debug(tag: String, message: String) {
        try {
            Log.d(tag, message)
        } catch (e: RuntimeException) {
            System.err.println("[$tag] DEBUG: $message (${e.message})")
        }
    }

    fun readFirstLine(tag: String, file: File): String? {
        return try {
            if (!file.exists() || !file.canRead()) return null
            file.bufferedReader().use { it.readLine()?.trim() }?.takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            debug(tag, "Unable to read ${file.path}: ${e.message}")
            null
        } catch (e: SecurityException) {
            debug(tag, "Permission denied reading ${file.path}: ${e.message}")
            null
        }
    }

    fun readLines(tag: String, file: File): List<String>? {
        return try {
            if (!file.exists() || !file.canRead()) return null
            file.bufferedReader().use { it.readLines() }
        } catch (e: IOException) {
            debug(tag, "Unable to read lines ${file.path}: ${e.message}")
            null
        } catch (e: SecurityException) {
            debug(tag, "Permission denied reading lines ${file.path}: ${e.message}")
            null
        }
    }

    fun readAllText(tag: String, file: File): String? {
        return try {
            if (!file.exists() || !file.canRead()) return null
            file.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            debug(tag, "Unable to read ${file.path}: ${e.message}")
            null
        } catch (e: SecurityException) {
            debug(tag, "Permission denied reading ${file.path}: ${e.message}")
            null
        }
    }
}
