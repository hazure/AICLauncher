package com.aliceincradle.launcher.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Real maintenance utilities for cache inspection/cleanup and Logcat export.
 */
class MaintenanceManager {

    fun calculateCacheSizeBytes(context: Context): Long {
        var total = 0L
        total += computeDirSize(context.cacheDir)
        context.externalCacheDir?.let { total += computeDirSize(it) }
        return total
    }

    fun clearCache(context: Context): Result<Pair<Long, Long>> {
        return try {
            val before = calculateCacheSizeBytes(context)
            deleteDirContents(context.cacheDir)
            context.externalCacheDir?.let { deleteDirContents(it) }
            val after = calculateCacheSizeBytes(context)
            val freed = (before - after).coerceAtLeast(0L)
            Result.success(freed to after)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied clearing cache", e)
            Result.failure(e)
        } catch (e: Exception) {
            SafeProbeIo.warn(TAG, "Failed clearing cache", e)
            Result.failure(e)
        }
    }

    fun exportLogcatToDownloads(context: Context): Result<String> {
        return try {
            val logText = captureLogcat()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "AICL_logcat_$timestamp.log"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AICL_Logs")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val written = runCatching {
                        resolver.openOutputStream(uri)?.use { out ->
                            out.write(logText.toByteArray(Charsets.UTF_8))
                            true
                        } ?: false
                    }.getOrElse { false }
                    if (written) {
                        return Result.success("Download/AICL_Logs/$fileName")
                    }
                }
            }

            val publicDir = runCatching {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AICL_Logs")
            }.getOrNull()
            val targetDir = if (publicDir != null && (publicDir.exists() || publicDir.mkdirs()) && publicDir.canWrite()) {
                publicDir
            } else {
                val appDl = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "Download")
                File(appDl, "AICL_Logs").apply { mkdirs() }
            }
            val outFile = File(targetDir, fileName)
            outFile.writeText(logText, Charsets.UTF_8)
            Result.success(outFile.absolutePath)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied exporting logcat", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error exporting logcat", e)
            Result.failure(e)
        }
    }

    private fun captureLogcat(): String {
        val process = ProcessBuilder("logcat", "-d", "-v", "time")
            .redirectErrorStream(true)
            .start()
        return try {
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val finished = process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            output.ifBlank { "[AICL] logcat output was empty." }
        } finally {
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun computeDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (!dir.isDirectory) return dir.length()
        return dir.listFiles()?.sumOf { computeDirSize(it) } ?: 0L
    }

    private fun deleteDirContents(dir: File?) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            child.deleteRecursively()
        }
    }

    companion object {
        private const val TAG = "MaintenanceManager"
        private const val LOGCAT_TIMEOUT_SECONDS = 5L
    }
}
