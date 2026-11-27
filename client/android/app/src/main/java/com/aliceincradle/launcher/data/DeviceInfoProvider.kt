package com.aliceincradle.launcher.data

import android.content.Context
import android.os.Build
import com.aliceincradle.launcher.bridge.EnvDetector
import java.util.Locale

data class DeviceInfo(
    val cpu: String = "检测中...",
    val gpu: String = "检测中...",
    val memory: String = "检测中...",
    val androidVersion: String = "检测中...",
    val pythonVersion: String = "检测中...",
    val aicVersion: String = "检测中..."
)

object DeviceInfoProvider {
    fun load(
        context: Context?,
        telemetryProvider: SystemTelemetryProvider = SystemTelemetryProvider(),
        envDetector: EnvDetector = EnvDetector(),
        cachedTelemetry: SystemTelemetry? = null
    ): DeviceInfo {
        val memStats = cachedTelemetry?.memory ?: telemetryProvider.sampleMemory(context)
        val memText = formatMemorySummary(memStats)
        val gpuText = cachedTelemetry?.gpu?.renderer?.takeIf { it.isNotBlank() && it != "检测中..." }
            ?: telemetryProvider.probeGpuRenderer(context)
        val envSnapshot = envDetector.probeAll(context)
        val aicText = if (envSnapshot.aicDirectory.isDetected) {
            val suffix = if (envSnapshot.aicDirectory.hasExecutable) "已就绪" else "已探测"
            "$suffix (${envSnapshot.aicDirectory.displayPath})"
        } else {
            envSnapshot.aicDirectory.displayPath
        }

        return DeviceInfo(
            cpu = getCpuInfo(),
            gpu = gpuText,
            memory = memText,
            androidVersion = getAndroidVersion(),
            pythonVersion = envSnapshot.python.versionText,
            aicVersion = aicText
        )
    }

    fun formatMemorySummary(memory: MemoryStats): String {
        if (!memory.isAvailable || memory.totalDeviceMb <= 0L) {
            return "--"
        }
        val availGb = memory.availDeviceMb / 1024.0
        val totalGb = memory.totalDeviceMb / 1024.0
        return String.format(Locale.US, "%.2f / %.2f GB", availGb, totalGb)
    }

    fun getCpuInfo(): String {
        val arch = try {
            val abis: Array<String>? = Build.SUPPORTED_ABIS
            abis?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: System.getProperty("os.arch")
                ?: "unknown"
        } catch (e: RuntimeException) {
            SafeProbeIo.debug(TAG, "Build.SUPPORTED_ABIS unavailable: ${e.message}")
            System.getProperty("os.arch") ?: "unknown"
        }
        val cores = Runtime.getRuntime().availableProcessors()
        return "$arch ($cores Cores)"
    }

    fun getAndroidVersion(): String {
        val release: String? = try {
            Build.VERSION.RELEASE
        } catch (e: RuntimeException) {
            SafeProbeIo.debug(TAG, "Build.VERSION.RELEASE unavailable: ${e.message}")
            null
        }
        val sdkInt = try {
            Build.VERSION.SDK_INT
        } catch (e: RuntimeException) {
            SafeProbeIo.debug(TAG, "Build.VERSION.SDK_INT unavailable: ${e.message}")
            0
        }
        return if (!release.isNullOrBlank()) {
            "Android $release (API $sdkInt)"
        } else {
            "Android --"
        }
    }

    private const val TAG = "DeviceInfoProvider"
}
