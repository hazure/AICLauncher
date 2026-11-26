package com.aliceincradle.launcher.data

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import java.io.IOException
import java.util.Locale

data class DeviceInfo(
    val cpuInfo: String,
    val gpuInfo: String,
    val memoryInfo: String,
    val androidVersion: String,
    val pythonVersion: String,
    val aicVersion: String
)

object DeviceInfoRepository {
    private const val TAG = "DeviceInfoRepository"

    fun loadDeviceInfo(context: Context): DeviceInfo {
        return DeviceInfo(
            cpuInfo = queryCpuInfo(),
            gpuInfo = queryGpuRenderer(),
            memoryInfo = queryMemoryInfo(context),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            pythonVersion = queryPythonVersion(),
            aicVersion = "未检测（预留占位）"
        )
    }

    private fun queryCpuInfo(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
        val cores = Runtime.getRuntime().availableProcessors()
        return "$abi ($cores 核)"
    }

    private fun queryMemoryInfo(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "未知"
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
            val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            String.format(Locale.US, "%.2f GB / %.2f GB", availGb, totalGb)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied querying memory", e)
            "未知（权限受限）"
        }
    }

    private fun queryGpuRenderer(): String {
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return Build.HARDWARE
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return Build.HARDWARE

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                EGL14.eglTerminate(display)
                return Build.HARDWARE
            }

            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

            var renderer: String? = null
            if (EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)

            renderer?.takeIf { it.isNotBlank() } ?: Build.HARDWARE
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to query GPU renderer", e)
            Build.HARDWARE
        } catch (e: RuntimeException) {
            Log.w(TAG, "EGL runtime error querying GPU renderer", e)
            Build.HARDWARE
        }
    }

    private fun queryPythonVersion(): String {
        val candidates = listOf(
            listOf("python" + "3", "--version"),
            listOf("python", "--version")
        )
        for (cmd in candidates) {
            try {
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotEmpty()) {
                    return output
                }
            } catch (e: IOException) {
                Log.d(TAG, "Python binary not found for ${cmd.firstOrNull()}", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException probing ${cmd.firstOrNull()}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted probing ${cmd.firstOrNull()}", e)
                return "探测中断"
            }
        }
        return "未检测到（已降级）"
    }
}
