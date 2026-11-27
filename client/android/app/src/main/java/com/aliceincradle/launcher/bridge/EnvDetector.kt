package com.aliceincradle.launcher.bridge

import android.content.Context
import android.os.Build
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.GameRunStatus
import com.aliceincradle.launcher.data.SafeProbeIo
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PythonProbeResult(
    val isInstalled: Boolean,
    val versionText: String,
    val executablePath: String? = null
)

data class AicDirectoryProbeResult(
    val isDetected: Boolean,
    val displayPath: String,
    val hasExecutable: Boolean = false
)

data class EnvProbeSnapshot(
    val python: PythonProbeResult,
    val aicDirectory: AicDirectoryProbeResult,
    val gameProcess: GameProcessState
)

/**
 * Dynamic environment detector for Python runtime, AIC game assets directory, and game process state.
 * Uses environment variables and Android Context storage APIs without hardcoded absolute paths.
 */
class EnvDetector(
    private val procRoot: File = File("/proc"),
    private val allowBareCommandFallback: Boolean = true,
    private val envProvider: () -> Map<String, String> = {
        try {
            System.getenv()
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Unable to read process environment", e)
            emptyMap()
        }
    }
) {
    fun probeAll(context: Context?): EnvProbeSnapshot {
        return EnvProbeSnapshot(
            python = detectPython(),
            aicDirectory = detectAicDirectory(context),
            gameProcess = probeGameProcess()
        )
    }

    fun detectPython(): PythonProbeResult {
        val env = envProvider()
        val resolvedBin = resolvePythonExecutable(env)
        val candidates = when {
            resolvedBin != null -> listOf(resolvedBin)
            allowBareCommandFallback -> PYTHON_CANDIDATES
            else -> emptyList()
        }

        for (bin in candidates) {
            try {
                val process = ProcessBuilder(listOf(bin, "--version"))
                    .redirectErrorStream(true)
                    .start()
                try {
                    process.outputStream.close()
                } catch (e: IOException) {
                    SafeProbeIo.debug(TAG, "Closing outputStream failed for $bin: ${e.message}")
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                try {
                    process.errorStream.close()
                } catch (e: IOException) {
                    SafeProbeIo.debug(TAG, "Closing errorStream failed for $bin: ${e.message}")
                }
                val finished = waitForProcess(process, PROCESS_TIMEOUT_SECONDS)
                if (!finished) {
                    SafeProbeIo.warn(TAG, "Python version probe timed out for $bin")
                    continue
                }
                if (process.exitValue() == 0 && output.isNotBlank()) {
                    return PythonProbeResult(
                        isInstalled = true,
                        versionText = output,
                        executablePath = bin
                    )
                }
            } catch (e: IOException) {
                SafeProbeIo.debug(TAG, "Python binary not runnable ($bin): ${e.message}")
            } catch (e: SecurityException) {
                SafeProbeIo.warn(TAG, "SecurityException running python probe ($bin)", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                SafeProbeIo.warn(TAG, "Python version probe interrupted", e)
                return PythonProbeResult(isInstalled = false, versionText = MISSING_PYTHON_LABEL)
            }
        }

        return PythonProbeResult(
            isInstalled = false,
            versionText = MISSING_PYTHON_LABEL,
            executablePath = null
        )
    }

    fun detectAicDirectory(context: Context?): AicDirectoryProbeResult {
        val env = envProvider()
        val candidateDirs = mutableListOf<File>()

        env["AIC_GAME_DIR"]
            ?.takeIf { it.isNotBlank() }
            ?.let { candidateDirs.add(File(it)) }

        if (context != null) {
            try {
                context.getExternalFilesDir(null)?.let { extRoot ->
                    candidateDirs.add(File(extRoot, "AliceInCradle"))
                    candidateDirs.add(File(extRoot, "game"))
                    candidateDirs.add(extRoot)
                }
                context.filesDir?.let { filesRoot ->
                    candidateDirs.add(File(filesRoot, "AliceInCradle"))
                    candidateDirs.add(File(filesRoot, "game"))
                }
            } catch (e: SecurityException) {
                SafeProbeIo.warn(TAG, "Permission denied querying app storage directories", e)
            } catch (e: RuntimeException) {
                SafeProbeIo.warn(TAG, "Failed querying app storage directories", e)
            }
        }

        env["PREFIX"]
            ?.takeIf { it.isNotBlank() }
            ?.let { prefix ->
                candidateDirs.add(File(prefix, "game_assets"))
                candidateDirs.add(File(prefix, "AliceInCradle"))
            }

        var existingFallbackDir: File? = null
        val externalRoot = try {
            context?.getExternalFilesDir(null)
        } catch (e: RuntimeException) {
            SafeProbeIo.debug(TAG, "getExternalFilesDir unavailable: ${e.message}")
            null
        }
        for (dir in candidateDirs) {
            try {
                if (!dir.exists() || !dir.isDirectory) continue
                val exeFile = File(dir, "AliceInCradle.exe")
                val dataDir = File(dir, "AliceInCradle_Data")
                if (exeFile.isFile || dataDir.isDirectory) {
                    return AicDirectoryProbeResult(
                        isDetected = true,
                        displayPath = dir.absolutePath,
                        hasExecutable = exeFile.isFile
                    )
                }
                if (existingFallbackDir == null && dir != externalRoot) {
                    existingFallbackDir = dir
                }
            } catch (e: SecurityException) {
                SafeProbeIo.warn(TAG, "Permission denied probing directory ${dir.path}", e)
            }
        }

        if (existingFallbackDir != null) {
            return AicDirectoryProbeResult(
                isDetected = true,
                displayPath = existingFallbackDir.absolutePath,
                hasExecutable = false
            )
        }

        return AicDirectoryProbeResult(
            isDetected = false,
            displayPath = MISSING_AIC_DIR_LABEL,
            hasExecutable = false
        )
    }

    fun isGameRunning(): Boolean = probeGameProcess().isAlive

    fun probeGameProcess(): GameProcessState {
        val entries = try {
            procRoot.listFiles() ?: return GameProcessState()
        } catch (e: SecurityException) {
            SafeProbeIo.debug(TAG, "Permission denied listing procfs for process check: ${e.message}")
            return GameProcessState()
        }

        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (!entry.isDirectory) continue

            val cmdline = SafeProbeIo.readAllText(TAG, File(entry, "cmdline"))
                ?.replace('\u0000', ' ')
                ?.trim()
            val comm = SafeProbeIo.readAllText(TAG, File(entry, "comm"))?.trim()
            val combined = listOfNotNull(cmdline, comm).joinToString(" ")
            if (combined.contains("AliceInCradle", ignoreCase = true)) {
                return GameProcessState(
                    status = GameRunStatus.RUNNING,
                    versionName = "AIC 0.3.0",
                    pid = pid,
                    isSimulated = false
                )
            }
        }

        return GameProcessState(
            status = GameRunStatus.STOPPED,
            versionName = "AIC 0.3.0",
            pid = null,
            isSimulated = false
        )
    }

    private fun resolvePythonExecutable(env: Map<String, String>): String? {
        val candidateDirs = linkedSetOf<String>()
        env["PREFIX"]
            ?.takeIf { it.isNotBlank() }
            ?.let { candidateDirs.add("$it${File.separator}bin") }

        env["PATH"]
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.let { candidateDirs.addAll(it) }

        for (dir in candidateDirs) {
            for (name in PYTHON_CANDIDATES) {
                try {
                    val file = File(dir, name)
                    if (file.isFile && file.canExecute()) {
                        return file.absolutePath
                    }
                } catch (e: SecurityException) {
                    SafeProbeIo.warn(TAG, "Permission denied inspecting $dir/$name", e)
                }
            }
        }
        return null
    }

    private fun waitForProcess(process: Process, timeoutSeconds: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            return finished
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadlineNanos) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(25L)
            }
        }
        process.destroy()
        return false
    }

    companion object {
        private const val TAG = "EnvDetector"
        private const val PROCESS_TIMEOUT_SECONDS = 2L
        const val MISSING_PYTHON_LABEL = "未检测到 Python"
        const val MISSING_AIC_DIR_LABEL = "未检测到"
        private val PYTHON_CANDIDATES = listOf("python3", "python")
    }
}
