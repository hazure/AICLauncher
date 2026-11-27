package com.aliceincradle.launcher.data

import android.content.Context
import android.os.Environment
import com.aliceincradle.launcher.bridge.EnvDetector
import java.io.File

data class ExportTarget(
    val id: String,
    val name: String,
    val displayPath: String
)

interface PathProvider {
    fun currentDirectoryLabel(context: Context? = null): String
    fun targetDirectoryLabel(context: Context? = null): String
    fun downloadExportTargets(context: Context? = null): List<ExportTarget>
    fun buildItemPath(itemName: String, context: Context? = null): String
}

/**
 * Dynamically resolves display paths via EnvDetector and Android Context storage APIs.
 * Never hardcodes device-specific absolute storage paths.
 */
class DefaultPathProvider(
    private val envDetector: EnvDetector = EnvDetector()
) : PathProvider {

    override fun currentDirectoryLabel(context: Context?): String {
        val detected = envDetector.detectAicDirectory(context)
        return if (detected.isDetected) {
            "${detected.displayPath}${File.separator}Mods"
        } else {
            DEFAULT_CURRENT_DIR
        }
    }

    override fun targetDirectoryLabel(context: Context?): String {
        val detected = envDetector.detectAicDirectory(context)
        return if (detected.isDetected) {
            detected.displayPath
        } else {
            DEFAULT_TARGET_DIR
        }
    }

    override fun downloadExportTargets(context: Context?): List<ExportTarget> {
        val downloadRoot = resolveDownloadDisplayRoot(context)
        return listOf(
            ExportTarget(
                id = "dl-exports",
                name = "AliceInCradle_Exports",
                displayPath = "$downloadRoot${File.separator}AliceInCradle_Exports"
            ),
            ExportTarget(
                id = "dl-mods-backup",
                name = "Mods_Backup",
                displayPath = "$downloadRoot${File.separator}Mods_Backup"
            ),
            ExportTarget(
                id = "dl-compat-logs",
                name = "Compat_Logs",
                displayPath = "$downloadRoot${File.separator}Compat_Logs"
            )
        )
    }

    override fun buildItemPath(itemName: String, context: Context?): String {
        return "${currentDirectoryLabel(context)}${File.separator}$itemName"
    }

    private fun resolveDownloadDisplayRoot(context: Context?): String {
        if (context != null) {
            try {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?.absolutePath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                context.filesDir
                    ?.let { return File(it, "Download").absolutePath }
            } catch (e: SecurityException) {
                SafeProbeIo.warn(TAG, "Permission denied querying Download directory", e)
            } catch (e: RuntimeException) {
                SafeProbeIo.debug(TAG, "Download directory unavailable: ${e.message}")
            }
        }
        return DEFAULT_DOWNLOAD_LABEL
    }

    companion object {
        private const val TAG = "DefaultPathProvider"
        const val DEFAULT_CURRENT_DIR = "AliceInCradle 0.30/Mods"
        const val DEFAULT_TARGET_DIR = "AIC根目录"
        const val DEFAULT_DOWNLOAD_LABEL = "Download"
    }
}
