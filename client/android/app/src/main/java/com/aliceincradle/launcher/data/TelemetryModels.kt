package com.aliceincradle.launcher.data

data class MemoryStats(
    val usagePercent: Float,
    val launcherMb: Int,
    val gameMb: Int,
    val pmlMb: Int,
    val containerTotalMb: Int,
    val totalDeviceMb: Long = 0L,
    val usedDeviceMb: Long = 0L,
    val availDeviceMb: Long = 0L,
    val isAvailable: Boolean = true
)

data class CpuCoreStat(
    val coreIndex: Int,
    val freqMhz: Int,
    val maxFreqMhz: Int = 0,
    val usagePercent: Int
)

data class CpuStats(
    val usagePercent: Float,
    val cores: List<CpuCoreStat>,
    val isAvailable: Boolean = true,
    val isEstimatedFromFreq: Boolean = false
)

data class GpuStats(
    val usagePercent: Float,
    val freqMhz: Int,
    val maxFreqMhz: Int,
    val renderer: String,
    val isUsageAvailable: Boolean = true,
    val isFreqAvailable: Boolean = true
)

data class SensorTemp(
    val name: String,
    val tempC: Float?
)

data class ThermalStats(
    val headlineTempC: Int?,
    val sensors: List<SensorTemp>
)

data class SystemTelemetry(
    val memory: MemoryStats,
    val cpu: CpuStats,
    val gpu: GpuStats,
    val thermal: ThermalStats
)

enum class GameRunStatus {
    STOPPED,
    RUNNING,
    TERMINATED
}

data class GameProcessState(
    val status: GameRunStatus = GameRunStatus.STOPPED,
    val versionName: String = "AIC 0.3.0",
    val pid: Int? = null,
    val isSimulated: Boolean = false
) {
    val isAlive: Boolean
        get() = status == GameRunStatus.RUNNING && pid != null
}

data class DownloadTaskItem(
    val id: String,
    val name: String,
    val progressPercent: Int
)

/**
 * Generates initial/fallback telemetry data before live device probes complete.
 */
object TelemetryPreviewFactory {
    fun createPreview(
        coreCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, 12),
        gpuRenderer: String = "检测中..."
    ): SystemTelemetry {
        val coreList = (0 until coreCount).map { idx ->
            CpuCoreStat(
                coreIndex = idx,
                freqMhz = 0,
                maxFreqMhz = 0,
                usagePercent = 0
            )
        }

        return SystemTelemetry(
            memory = MemoryStats(
                usagePercent = 0f,
                launcherMb = 0,
                gameMb = 0,
                pmlMb = 0,
                containerTotalMb = 0,
                isAvailable = false
            ),
            cpu = CpuStats(
                usagePercent = 0f,
                cores = coreList,
                isAvailable = false
            ),
            gpu = GpuStats(
                usagePercent = 0f,
                freqMhz = 0,
                maxFreqMhz = 0,
                renderer = gpuRenderer,
                isUsageAvailable = false,
                isFreqAvailable = false
            ),
            thermal = ThermalStats(
                headlineTempC = null,
                sensors = emptyList()
            )
        )
    }
}
