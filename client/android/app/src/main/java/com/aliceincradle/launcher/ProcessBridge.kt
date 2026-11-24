package com.aliceincradle.launcher

import android.util.Log
import java.io.File
import java.io.IOException

sealed class CommandResult {
    data class Success(val output: String) : CommandResult()
    data class PythonNotFound(val reason: String) : CommandResult()
    data class Failure(val exitCode: Int, val error: String) : CommandResult()
}

object ProcessBridge {
    private const val TAG = "ProcessBridge"

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
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Process interrupted", e)
            CommandResult.Failure(-1, e.message ?: "Interrupted")
        }
    }

    fun runPreflight(workingDir: File): CommandResult {
        val cliScript = File(workingDir, "scripts/cli.py")
        val cmd = if (cliScript.isFile) {
            listOf("python3", "scripts/cli.py", "--preflight")
        } else {
            listOf("python3", "-c", "print('Preflight OK')")
        }
        return execute(cmd, workingDir)
    }
}
