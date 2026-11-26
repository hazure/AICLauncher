package com.aliceincradle.launcher.data

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale

data class DeviceInfo(
    val cpu: String = "检测中...",
    val gpu: String = "检测中...",
    val memory: String = "检测中...",
    val androidVersion: String = "检测中...",
    val pythonVersion: String = "检测中...",
    val aicVersion: String = "待检测 (Placeholder)"
)

object DeviceInfoProvider {
    private const val TAG = "DeviceInfoProvider"
    private val PYTHON_CANDIDATES = listOf("python3", "python")

    fun load(context: Context): DeviceInfo {
        return DeviceInfo(
            cpu = getCpuInfo(),
            gpu = getGpuInfo(),
            memory = getMemoryInfo(context),
            androidVersion = getAndroidVersion(),
            pythonVersion = getPythonVersion(),
            aicVersion = "待检测 (Placeholder)"
        )
    }

    private fun getCpuInfo(): String {
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val cores = Runtime.getRuntime().availableProcessors()
        return "$arch ($cores Cores)"
    }

    private fun getGpuInfo(): String {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return "${Build.HARDWARE} (GPU)"
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return "${Build.HARDWARE} (GPU)"
            }
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0 || configs[0] == null
            ) {
                return "${Build.HARDWARE} (GPU)"
            }
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return "${Build.HARDWARE} (GPU)"
            }
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)?.trim()
            if (renderer.isNullOrEmpty()) "${Build.HARDWARE} (GPU)" else renderer
        } catch (e: RuntimeException) {
            Log.w(TAG, "EGL GPU probe failed, falling back to Build.HARDWARE", e)
            "${Build.HARDWARE} (GPU)"
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun getMemoryInfo(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "未知"
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val bytesPerGb = 1024.0 * 1024.0 * 1024.0
            val availGb = memInfo.availMem / bytesPerGb
            val totalGb = memInfo.totalMem / bytesPerGb
            String.format(Locale.US, "%.2f / %.2f GB", availGb, totalGb)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading memory info", e)
            "未知"
        }
    }

    private fun getAndroidVersion(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    private fun resolvePythonCommand(): String? {
        val pathDirs = try {
            System.getenv("PATH")
                ?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read PATH environment variable", e)
            emptyList()
        }

        for (dir in pathDirs) {
            for (name in PYTHON_CANDIDATES) {
                try {
                    val file = File(dir, name)
                    if (file.isFile && file.canExecute()) {
                        return file.absolutePath
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Permission denied checking $dir/$name", e)
                }
            }
        }
        return null
    }

    private fun getPythonVersion(): String {
        val resolvedBin = resolvePythonCommand()
        val candidates = if (resolvedBin != null) listOf(resolvedBin) else PYTHON_CANDIDATES
        for (bin in candidates) {
            try {
                val process = ProcessBuilder(listOf(bin, "--version"))
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotEmpty()) {
                    return output
                }
            } catch (e: IOException) {
                Log.d(TAG, "Python binary not runnable: $bin", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException probing python: $bin", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted probing python version", e)
                return "未检测到 Python"
            }
        }
        return "未检测到 Python"
    }
}
