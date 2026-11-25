package com.aliceincradle.launcher.bridge

import android.util.Log
import java.io.File
import java.io.IOException

object ProcessBridge {
    private const val TAG = "ProcessBridge"
    private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"
    private const val TERMUX_GAME_ASSETS_DIR = "/data/data/com.termux/files/usr/game_assets"

    private val PYTHON_CANDIDATES = listOf("python", "python" + "3")

    private fun findExecutableInDir(dirPath: String): String? {
        val dir = File(dirPath)
        if (!dir.isDirectory) return null
        for (name in PYTHON_CANDIDATES) {
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }
        return null
    }

    private fun findPythonBinary(customPythonPath: String? = null): String? {
        if (!customPythonPath.isNullOrBlank()) {
            val customFile = File(customPythonPath)
            if (customFile.isFile && customFile.canExecute()) {
                return customFile.absolutePath
            }
        }

        // 1. 扫描 Termux 私有 bin 目录
        findExecutableInDir(TERMUX_BIN_DIR)?.let { return it }

        // 2. 遍历系统 PATH 环境变量中的每个目录
        val pathDirs = System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        for (dir in pathDirs) {
            findExecutableInDir(dir)?.let { return it }
        }
        return null
    }

    private fun findGameDir(sandboxDir: File?): File? {
        val candidates = mutableListOf<File>()
        if (sandboxDir != null) {
            candidates.add(File(sandboxDir, "game"))
            candidates.add(File(sandboxDir, "AliceInCradle"))
            if (File(sandboxDir, "scripts/cli.py").isFile) {
                candidates.add(sandboxDir)
            }
        }
        candidates.add(File(TERMUX_GAME_ASSETS_DIR))

        return candidates.firstOrNull { it.isDirectory && it.canRead() }
    }

    fun checkEnvironment(
        sandboxDir: File? = null,
        customPythonPath: String? = null
    ): EnvStatus {
        return try {
            val pythonBin = findPythonBinary(customPythonPath)
            if (pythonBin == null) {
                EnvStatus.MISSING_PYTHON
            } else if (findGameDir(sandboxDir) == null) {
                EnvStatus.MISSING_GAME
            } else {
                EnvStatus.READY
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while probing environment", e)
            EnvStatus.UNKNOWN_ERROR
        } catch (e: IOException) {
            Log.e(TAG, "IO error while probing environment", e)
            EnvStatus.UNKNOWN_ERROR
        }
    }

    fun startGame(sandboxDir: File? = null, onError: (String) -> Unit) {
        val status = checkEnvironment(sandboxDir)
        if (status != EnvStatus.READY) {
            onError("环境未就绪：$status")
            return
        }

        val pythonBin = try {
            findPythonBinary()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied resolving python binary", e)
            onError("权限不足：${e.message ?: "SecurityException"}")
            return
        } catch (e: IOException) {
            Log.e(TAG, "IO error resolving python binary", e)
            onError("IO 异常：${e.message ?: "IOException"}")
            return
        }

        if (pythonBin == null) {
            onError("未检测到 Python 运行环境")
            return
        }

        val workDir = try {
            findGameDir(sandboxDir)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied resolving game directory", e)
            onError("权限不足：${e.message ?: "SecurityException"}")
            return
        } catch (e: IOException) {
            Log.e(TAG, "IO error resolving game directory", e)
            onError("IO 异常：${e.message ?: "IOException"}")
            return
        }

        if (workDir == null) {
            onError("未找到游戏资源目录")
            return
        }

        val cliScript = File(workDir, "scripts/cli.py")
        val cmd = if (cliScript.isFile) {
            listOf(pythonBin, "scripts/cli.py", "--preflight")
        } else {
            listOf(pythonBin, "-c", "print('Preflight OK')")
        }

        try {
            val process = ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                onError(output.ifEmpty { "进程退出码异常: $exitCode" })
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start game process", e)
            onError("启动失败（IO 异常）：${e.message ?: "IOException"}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting game process", e)
            onError("启动失败（权限不足）：${e.message ?: "SecurityException"}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Game process interrupted", e)
            onError("启动被中断：${e.message ?: "InterruptedException"}")
        }
    }
}
