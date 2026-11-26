package com.aliceincradle.launcher.data

data class MemoryStats(
    val usagePercent: Float,
    val launcherMb: Int,
    val gameMb: Int,
    val pmlMb: Int,
    val containerTotalMb: Int
)

data class CpuCoreStat(
    val coreIndex: Int,
    val freqMhz: Int,
    val usagePercent: Int
)

data class CpuStats(
    val usagePercent: Float,
    val cores: List<CpuCoreStat>
)

data class GpuStats(
    val usagePercent: Float,
    val freqMhz: Int,
    val maxFreqMhz: Int,
    val renderer: String
)

data class SensorTemp(
    val name: String,
    val tempC: Float
)

data class ThermalStats(
    val headlineTempC: Int,
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
    val pid: Int? = null
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
 * Generates preview telemetry data for Ver.B UI until native procfs/bridge sensors are wired.
 */
object TelemetryPreviewFactory {
    fun createPreview(
        coreCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, 12),
        gpuRenderer: String = "Adreno (TM) GPU",
        isGameRunning: Boolean = false
    ): SystemTelemetry {
        val launcherMb = 148
        val pmlMb = 236
        val gameMb = if (isGameRunning) 1860 else 0
        val containerTotalMb = launcherMb + pmlMb + gameMb
        val memPercent = if (isGameRunning) 0.72f else 0.34f

        val coreList = (0 until coreCount).map { idx ->
            val baseFreq = when {
                idx >= coreCount - 2 -> if (isGameRunning) 2688 else 1650
                idx >= coreCount / 2 -> if (isGameRunning) 2208 else 1344
                else -> if (isGameRunning) 1766 else 960
            }
            val load = when {
                idx >= coreCount - 2 -> if (isGameRunning) 68 else 26
                idx >= coreCount / 2 -> if (isGameRunning) 52 else 21
                else -> if (isGameRunning) 39 else 16
            }
            CpuCoreStat(coreIndex = idx, freqMhz = baseFreq, usagePercent = load)
        }
        val avgCpuPercent = (coreList.sumOf { it.usagePercent } / coreList.size.coerceAtLeast(1)) / 100f

        val gpuPercent = if (isGameRunning) 0.64f else 0.18f
        val gpuFreq = if (isGameRunning) 680 else 305

        val sensors = listOf(
            SensorTemp("CPU", if (isGameRunning) 61.4f else 41.2f),
            SensorTemp("GPU", if (isGameRunning) 58.9f else 39.5f),
            SensorTemp("Mem", if (isGameRunning) 49.6f else 36.8f),
            SensorTemp("电池", if (isGameRunning) 38.2f else 32.4f),
            SensorTemp("机身", if (isGameRunning) 39.7f else 33.1f),
            SensorTemp("基带", if (isGameRunning) 43.0f else 34.5f)
        )
        val headlineTemp = sensors.maxOf { it.tempC }.toInt()

        return SystemTelemetry(
            memory = MemoryStats(
                usagePercent = memPercent,
                launcherMb = launcherMb,
                gameMb = gameMb,
                pmlMb = pmlMb,
                containerTotalMb = containerTotalMb
            ),
            cpu = CpuStats(
                usagePercent = avgCpuPercent,
                cores = coreList
            ),
            gpu = GpuStats(
                usagePercent = gpuPercent,
                freqMhz = gpuFreq,
                maxFreqMhz = 900,
                renderer = gpuRenderer
            ),
            thermal = ThermalStats(
                headlineTempC = headlineTemp,
                sensors = sensors
            )
        )
    }
}
