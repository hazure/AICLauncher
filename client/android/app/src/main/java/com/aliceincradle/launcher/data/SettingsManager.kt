package com.aliceincradle.launcher.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettingsData(
    val themeMode: String = "跟随系统",
    val showHiddenFiles: Boolean = false,
    val autoCheckUpdate: Boolean = true,
    val rootfsType: String = "Termux Proot (Ubuntu)",
    val graphicsRenderer: String = "Turnip + DXVK",
    val containerMountPath: String = "",
    val cpuCoreLimit: Int = 0,
    val memLimitMB: Int = 0,
    val customEnvVars: String = "",
    val selectedPythonVersion: String = "Python 3.10",
    val selectedPythonDir: String = "D:/python",
    val logLevel: String = "INFO",
    val isDevModeUnlocked: Boolean = false,
    val forceSoftwareRendering: Boolean = false,
    val versionName: String = "0.1.0a"
)

/**
 * Validates multi-line `KEY=VALUE` environment variable entries.
 * Returns `null` if valid, or a human-readable error message for the first invalid line.
 */
fun validateEnvVars(raw: String): String? {
    if (raw.isBlank()) return null
    val lines = raw.lines()
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEachIndexed
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx <= 0) {
            return "第 ${index + 1} 行格式错误：必须包含 '=' 且键名不能为空"
        }
    }
    return null
}

/**
 * Pure-Kotlin atomic file-backed DataStore with coroutine debouncing and [Mutex] synchronization.
 * Stores settings under `context.filesDir` without using `SharedPreferences`.
 */
class SettingsManager(
    private val storageFile: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 250L
) {
    constructor(context: Context) : this(
        storageFile = File(context.applicationContext.filesDir, DATASTORE_FILE_NAME)
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()
    private var pendingWriteJob: Job? = null

    private val _settings = MutableStateFlow(readFromDisk())
    val settingsFlow: StateFlow<SettingsData> = _settings.asStateFlow()

    fun update(immediate: Boolean = false, transform: (SettingsData) -> SettingsData) {
        val next = transform(_settings.value)
        _settings.value = next
        pendingWriteJob?.cancel()
        pendingWriteJob = scope.launch {
            if (!immediate && debounceMs > 0L) {
                delay(debounceMs)
            }
            writeToDisk(next)
        }
    }

    suspend fun flush() {
        pendingWriteJob?.cancel()
        writeToDisk(_settings.value)
    }

    private fun readFromDisk(): SettingsData {
        return try {
            if (!storageFile.exists()) return SettingsData()
            val raw = storageFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) return SettingsData()
            val props = Properties().apply { load(StringReader(raw)) }
            SettingsData(
                themeMode = props.getProperty("themeMode", "跟随系统"),
                showHiddenFiles = props.getProperty("showHiddenFiles", "false").toBooleanStrictOrNull() ?: false,
                autoCheckUpdate = props.getProperty("autoCheckUpdate", "true").toBooleanStrictOrNull() ?: true,
                rootfsType = props.getProperty("rootfsType", "Termux Proot (Ubuntu)"),
                graphicsRenderer = props.getProperty("graphicsRenderer", "Turnip + DXVK"),
                containerMountPath = props.getProperty("containerMountPath", ""),
                cpuCoreLimit = props.getProperty("cpuCoreLimit", "0").toIntOrNull() ?: 0,
                memLimitMB = props.getProperty("memLimitMB", "0").toIntOrNull() ?: 0,
                customEnvVars = props.getProperty("customEnvVars", ""),
                selectedPythonVersion = props.getProperty("selectedPythonVersion", "Python 3.10"),
                selectedPythonDir = props.getProperty("selectedPythonDir", "D:/python"),
                logLevel = props.getProperty("logLevel", "INFO"),
                isDevModeUnlocked = props.getProperty("isDevModeUnlocked", "false").toBooleanStrictOrNull() ?: false,
                forceSoftwareRendering = props.getProperty("forceSoftwareRendering", "false").toBooleanStrictOrNull() ?: false,
                versionName = props.getProperty("versionName", "0.1.0a")
            )
        } catch (e: Exception) {
            logError("Failed to read settings datastore from ${storageFile.name}", e)
            SettingsData()
        }
    }

    private suspend fun writeToDisk(data: SettingsData) {
        writeMutex.withLock {
            try {
                storageFile.parentFile?.let { parent ->
                    if (!parent.exists()) {
                        parent.mkdirs()
                    }
                }
                val props = Properties().apply {
                    setProperty("themeMode", data.themeMode)
                    setProperty("showHiddenFiles", data.showHiddenFiles.toString())
                    setProperty("autoCheckUpdate", data.autoCheckUpdate.toString())
                    setProperty("rootfsType", data.rootfsType)
                    setProperty("graphicsRenderer", data.graphicsRenderer)
                    setProperty("containerMountPath", data.containerMountPath)
                    setProperty("cpuCoreLimit", data.cpuCoreLimit.toString())
                    setProperty("memLimitMB", data.memLimitMB.toString())
                    setProperty("customEnvVars", data.customEnvVars)
                    setProperty("selectedPythonVersion", data.selectedPythonVersion)
                    setProperty("selectedPythonDir", data.selectedPythonDir)
                    setProperty("logLevel", data.logLevel)
                    setProperty("isDevModeUnlocked", data.isDevModeUnlocked.toString())
                    setProperty("forceSoftwareRendering", data.forceSoftwareRendering.toString())
                    setProperty("versionName", data.versionName)
                }
                val writer = StringWriter()
                props.store(writer, "AICL Settings DataStore")
                val serialized = writer.toString()
                val tmpFile = File(storageFile.parentFile, "${storageFile.name}.tmp")
                tmpFile.writeText(serialized, Charsets.UTF_8)
                if (!tmpFile.renameTo(storageFile)) {
                    storageFile.writeText(serialized, Charsets.UTF_8)
                    tmpFile.delete()
                }
                Unit
            } catch (e: Exception) {
                logError("Failed to persist settings datastore to ${storageFile.name}", e)
            }
        }
    }

    private fun logError(message: String, error: Throwable) {
        runCatching { Log.e(TAG, message, error) }
            .onFailure { System.err.println("[$TAG] $message: ${error.message}") }
    }

    private companion object {
        const val TAG = "SettingsManager"
        const val DATASTORE_FILE_NAME = "aicl_settings.datastore.json"
    }
}
