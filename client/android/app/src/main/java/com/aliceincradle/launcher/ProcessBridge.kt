package com.aliceincradle.launcher

import android.util.Log
import java.io.File
import java.io.IOException

enum class EnvironmentStatus {
    READY,
    MISSING_PYTHON,
    MISSING_GAME
}

data class EnvironmentReport(
    val status: EnvironmentStatus,
    val pythonBin: String? = null,
    val gameDir: File? = null,
    val detail: String = ""
)

sealed class CommandResult {
    data class Success(val output: String) : CommandResult()
    data class PythonNotFound(val reason: String) : CommandResult()
    data class GameNotFound(val reason: String) : CommandResult()
    data class Failure(val exitCode: Int, val error: String) : CommandResult()
}

object ProcessBridge {
    private const val TAG = "ProcessBridge"
    private val CANDIDATE_BIN_DIRS = listOf(
        "/data/data/com.termux/files/usr/bin"
    )

    private fun findPythonBinary(): String? {
        val pathDirs = try {
            System.getenv("PATH")
                ?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read PATH env", e)
            emptyList()
        }

        for (dir in pathDirs + CANDIDATE_BIN_DIRS) {
            try {
                val candidate = File(dir, "python3")
                if (candidate.isFile && candidate.canExecute()) {
                    return candidate.absolutePath
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission denied probing $dir", e)
            }
        }
        return null
    }

    private fun findGameDir(sandboxDir: File): File? {
        val candidates = listOf(
            File(sandboxDir, "game"),
            File(sandboxDir, "AliceInCradle"),
            sandboxDir
        )
        for (dir in candidates) {
            if (dir.isDirectory && (File(dir, "scripts/cli.py").isFile || dir != sandboxDir)) {
                return dir
            }
        }
        return null
    }

    fun checkEnvironment(
        sandboxDir: File,
        onResult: ((EnvironmentReport) -> Unit)? = null
    ): EnvironmentReport {
        val pythonBin = findPythonBinary()
        val report = if (pythonBin == null) {
            EnvironmentReport(
                status = EnvironmentStatus.MISSING_PYTHON,
                detail = "未检测到 python3 运行环境"
            )
        } else {
            val gameDir = findGameDir(sandboxDir)
            if (gameDir == null) {
                EnvironmentReport(
                    status = EnvironmentStatus.MISSING_GAME,
                    pythonBin = pythonBin,
                    detail = "未检测到游戏目录"
                )
            } else {
                EnvironmentReport(
                    status = EnvironmentStatus.READY,
                    pythonBin = pythonBin,
                    gameDir = gameDir,
                    detail = "环境就绪"
                )
            }
        }
        onResult?.invoke(report)
        return report
    }

    fun execute(command: List<String>, workingDir: File): CommandResult {
        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            when {
                exitCode == 0 -> CommandResult.Success(output.ifEmpty { "OK" })
                exitCode == 127 -> CommandResult.PythonNotFound("python3 not found (exit 127)")
                else -> CommandResult.Failure(exitCode, output.ifEmpty { "Exit code $exitCode" })
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start process: ${command.joinToString(" ")}", e)
            CommandResult.PythonNotFound(e.message ?: "IOException")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting process", e)
            CommandResult.Failure(-1, e.message ?: "SecurityException")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Process interrupted", e)
            CommandResult.Failure(-1, e.message ?: "Interrupted")
        }
    }

    fun runPreflight(sandboxDir: File): CommandResult {
        val report = checkEnvironment(sandboxDir)
        return when (report.status) {
            EnvironmentStatus.MISSING_PYTHON -> CommandResult.PythonNotFound(report.detail)
            EnvironmentStatus.MISSING_GAME -> CommandResult.GameNotFound(report.detail)
            EnvironmentStatus.READY -> {
                val py = report.pythonBin ?: return CommandResult.PythonNotFound("python3 missing")
                val workDir = report.gameDir ?: sandboxDir
                val cliScript = File(workDir, "scripts/cli.py")
                val cmd = if (cliScript.isFile) {
                    listOf(py, "scripts/cli.py", "--preflight")
                } else {
                    listOf(py, "-c", "print('Preflight OK')")
                }
                execute(cmd, workDir)
            }
        }
    }
}
