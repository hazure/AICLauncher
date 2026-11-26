package com.aliceincradle.launcher.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.HardwarePropertiesManager
import java.io.File
import kotlinx.coroutines.delay

/**
 * Probes real device CPU, memory, GPU, and thermal telemetry off the main thread.
 * All procfs/sysfs/Android API failures are isolated and degraded safely.
 */
class SystemTelemetryProvider(
    private val procRoot: File = File("/proc"),
    private val sysRoot: File = File("/sys")
) {
    private data class CpuTimeSample(
        val totalTicks: Long,
        val idleTicks: Long
    )

    private val lock = Any()
    private var previousCpuSamples: Map<String, CpuTimeSample> = emptyMap()

    @Volatile
    private var cachedGpuRenderer: String? = null

    suspend fun sample(context: Context?): SystemTelemetry {
        val memory = sampleMemory(context)
        val cpu = sampleCpu()
        val gpu = sampleGpu(context)
        val thermal = sampleThermal(context)
        return SystemTelemetry(
            memory = memory,
            cpu = cpu,
            gpu = gpu,
            thermal = thermal
        )
    }

    fun probeGpuRenderer(context: Context?): String {
        cachedGpuRenderer?.let { return it }

        var display = EGL14.EGL_NO_DISPLAY
        var eglContext = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        val eglRenderer = try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display != EGL14.EGL_NO_DISPLAY) {
                val version = IntArray(2)
                if (EGL14.eglInitialize(display, version, 0, version, 1)) {
                    val configAttribs = intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_NONE
                    )
                    val configs = arrayOfNulls<EGLConfig>(1)
                    val numConfigs = IntArray(1)
                    if (EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                        numConfigs[0] > 0 && configs[0] != null
                    ) {
                        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
                        surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
                        if (EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                            GLES20.glGetString(GLES20.GL_RENDERER)?.trim()?.takeIf { it.isNotEmpty() }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: RuntimeException) {
            safeLogWarn("EGL GPU probe failed", e)
            null
        } catch (e: UnsatisfiedLinkError) {
            safeLogWarn("EGL native library unavailable in test host", e)
            null
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                    EGL14.eglTerminate(display)
                }
            } catch (e: RuntimeException) {
                safeLogWarn("Failed releasing EGL resources", e)
            }
        }

        val hardwareName = try {
            Build.HARDWARE?.takeIf { it.isNotBlank() } ?: "Device"
        } catch (e: RuntimeException) {
            safeLogDebug("Build.HARDWARE unavailable: ${e.message}")
            "Device"
        }
        val resolved = eglRenderer ?: probeGpuFromActivityManager(context, hardwareName) ?: "$hardwareName (GPU)"
        cachedGpuRenderer = resolved
        return resolved
    }

    private fun probeGpuFromActivityManager(context: Context?, hardwareName: String): String? {
        if (context == null) return null
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val glVersion = am.deviceConfigurationInfo?.glEsVersion
            if (!glVersion.isNullOrBlank()) {
                "$hardwareName (OpenGL ES $glVersion)"
            } else {
                null
            }
        } catch (e: SecurityException) {
            safeLogWarn("SecurityException probing ActivityManager GPU info", e)
            null
        } catch (e: RuntimeException) {
            safeLogWarn("Failed reading ActivityManager GPU info", e)
            null
        }
    }

    internal fun sampleMemory(context: Context?): MemoryStats {
        val launcherMb = sampleLauncherMemoryMb()
        if (context != null) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (am != null) {
                    val memInfo = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(memInfo)
                    if (memInfo.totalMem > 0L) {
                        val totalMb = memInfo.totalMem / BYTES_PER_MB
                        val availMb = (memInfo.availMem / BYTES_PER_MB).coerceIn(0L, totalMb)
                        val usedMb = (totalMb - availMb).coerceAtLeast(0L)
                        val usage = (usedMb.toDouble() / totalMb.toDouble()).toFloat().coerceIn(0f, 1f)
                        return MemoryStats(
                            usagePercent = usage,
                            launcherMb = launcherMb,
                            gameMb = 0,
                            pmlMb = 0,
                            containerTotalMb = launcherMb,
                            totalDeviceMb = totalMb,
                            usedDeviceMb = usedMb,
                            availDeviceMb = availMb,
                            isAvailable = true
                        )
                    }
                }
            } catch (e: SecurityException) {
                safeLogWarn("Permission denied reading ActivityManager memory", e)
            } catch (e: RuntimeException) {
                safeLogWarn("Failed reading ActivityManager memory", e)
            }
        }

        return sampleProcMeminfo(launcherMb)
    }

    private fun sampleProcMeminfo(launcherMb: Int): MemoryStats {
        val meminfoFile = File(procRoot, "meminfo")
        val lines = readTextLinesSafely(meminfoFile) ?: return MemoryStats(
            usagePercent = 0f,
            launcherMb = launcherMb,
            gameMb = 0,
            pmlMb = 0,
            containerTotalMb = launcherMb,
            isAvailable = false
        )

        var totalKb = 0L
        var availKb = -1L
        var freeKb = 0L
        var buffersKb = 0L
        var cachedKb = 0L
        for (line in lines) {
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size < 2) continue
            val value = parts[1].toLongOrNull() ?: continue
            when (parts[0]) {
                "MemTotal:" -> totalKb = value
                "MemAvailable:" -> availKb = value
                "MemFree:" -> freeKb = value
                "Buffers:" -> buffersKb = value
                "Cached:" -> cachedKb = value
            }
        }

        if (totalKb <= 0L) {
            return MemoryStats(
                usagePercent = 0f,
                launcherMb = launcherMb,
                gameMb = 0,
                pmlMb = 0,
                containerTotalMb = launcherMb,
                isAvailable = false
            )
        }

        val effectiveAvailKb = if (availKb >= 0L) availKb else (freeKb + buffersKb + cachedKb)
        val totalMb = totalKb / 1024L
        val availMb = (effectiveAvailKb / 1024L).coerceIn(0L, totalMb)
        val usedMb = (totalMb - availMb).coerceAtLeast(0L)
        val usage = if (totalMb > 0L) (usedMb.toDouble() / totalMb.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
        return MemoryStats(
            usagePercent = usage,
            launcherMb = launcherMb,
            gameMb = 0,
            pmlMb = 0,
            containerTotalMb = launcherMb,
            totalDeviceMb = totalMb,
            usedDeviceMb = usedMb,
            availDeviceMb = availMb,
            isAvailable = true
        )
    }

    private fun sampleLauncherMemoryMb(): Int {
        return try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val totalPssKb = info.totalPss
            if (totalPssKb > 0) {
                (totalPssKb / 1024).coerceAtLeast(1)
            } else {
                val rt = Runtime.getRuntime()
                ((rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB).toInt().coerceAtLeast(1)
            }
        } catch (e: RuntimeException) {
            safeLogDebug("Debug.MemoryInfo unavailable, falling back to JVM heap: ${e.message}")
            val rt = Runtime.getRuntime()
            ((rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB).toInt().coerceAtLeast(1)
        }
    }

    internal suspend fun sampleCpu(): CpuStats {
        val coreCount = detectCpuCoreCount()
        val freqPairs = (0 until coreCount).map { idx ->
            readCoreFreqMhz(idx)
        }

        val initialSnapshot = readProcStatSnapshot()
        val latestSnapshot = if (initialSnapshot != null) {
            val hadPrevious = synchronized(lock) { previousCpuSamples.isNotEmpty() }
            if (!hadPrevious) {
                synchronized(lock) { previousCpuSamples = initialSnapshot }
                delay(CPU_DELTA_SAMPLE_DELAY_MS)
                readProcStatSnapshot()
            } else {
                initialSnapshot
            }
        } else {
            null
        }

        if (latestSnapshot != null) {
            val prevSnapshot = synchronized(lock) {
                val prev = previousCpuSamples
                previousCpuSamples = latestSnapshot
                prev
            }
            if (prevSnapshot.isNotEmpty()) {
                val totalUsage = computeCpuDelta(prevSnapshot["cpu"], latestSnapshot["cpu"])
                val coreStats = (0 until coreCount).map { idx ->
                    val (curMhz, maxMhz) = freqPairs[idx]
                    val coreLoad = computeCpuDelta(prevSnapshot["cpu$idx"], latestSnapshot["cpu$idx"])
                        ?: totalUsage
                        ?: 0f
                    CpuCoreStat(
                        coreIndex = idx,
                        freqMhz = curMhz,
                        maxFreqMhz = maxMhz,
                        usagePercent = (coreLoad * 100f).toInt().coerceIn(0, 100)
                    )
                }
                val resolvedUsage = totalUsage ?: if (coreStats.isNotEmpty()) {
                    (coreStats.sumOf { it.usagePercent } / coreStats.size.toFloat()) / 100f
                } else {
                    0f
                }
                return CpuStats(
                    usagePercent = resolvedUsage.coerceIn(0f, 1f),
                    cores = coreStats,
                    isAvailable = true,
                    isEstimatedFromFreq = false
                )
            }
        }

        val hasAnyFreq = freqPairs.any { (cur, _) -> cur > 0 }
        val fallbackCores = (0 until coreCount).map { idx ->
            val (curMhz, maxMhz) = freqPairs[idx]
            val estimatedPercent = when {
                curMhz > 0 && maxMhz > 0 -> ((curMhz.toFloat() / maxMhz.toFloat()) * 100f).toInt().coerceIn(1, 100)
                else -> 0
            }
            CpuCoreStat(
                coreIndex = idx,
                freqMhz = curMhz,
                maxFreqMhz = maxMhz,
                usagePercent = estimatedPercent
            )
        }

        val avgLoad = if (hasAnyFreq && fallbackCores.isNotEmpty()) {
            (fallbackCores.sumOf { it.usagePercent } / fallbackCores.size.toFloat()) / 100f
        } else {
            0f
        }
        return CpuStats(
            usagePercent = avgLoad.coerceIn(0f, 1f),
            cores = fallbackCores,
            isAvailable = hasAnyFreq,
            isEstimatedFromFreq = hasAnyFreq
        )
    }

    private fun detectCpuCoreCount(): Int {
        val onlineText = readFirstLineSafely(File(sysRoot, "devices/system/cpu/online"))
        if (!onlineText.isNullOrBlank()) {
            val parsed = parseCpuRangeCount(onlineText)
            if (parsed > 0) return parsed
        }
        val cpuDir = File(sysRoot, "devices/system/cpu")
        val dirCount = try {
            cpuDir.listFiles()?.count { it.isDirectory && CPU_DIR_REGEX.matches(it.name) } ?: 0
        } catch (e: SecurityException) {
            safeLogWarn("Permission denied listing CPU directories", e)
            0
        }
        if (dirCount > 0) return dirCount
        return Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    private fun readCoreFreqMhz(coreIndex: Int): Pair<Int, Int> {
        val cpufreqDir = File(sysRoot, "devices/system/cpu/cpu$coreIndex/cpufreq")
        val curKhz = readLongSafely(File(cpufreqDir, "scaling_cur_freq"))
            ?: readLongSafely(File(cpufreqDir, "cpuinfo_cur_freq"))
            ?: 0L
        val maxKhz = readLongSafely(File(cpufreqDir, "cpuinfo_max_freq"))
            ?: readLongSafely(File(cpufreqDir, "scaling_max_freq"))
            ?: 0L
        val curMhz = (curKhz / 1000L).toInt().coerceAtLeast(0)
        val maxMhz = (maxKhz / 1000L).toInt().coerceAtLeast(0)
        return curMhz to maxMhz
    }

    private fun readProcStatSnapshot(): Map<String, CpuTimeSample>? {
        val lines = readTextLinesSafely(File(procRoot, "stat")) ?: return null
        val samples = mutableMapOf<String, CpuTimeSample>()
        for (line in lines) {
            if (!line.startsWith("cpu")) break
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size < 5) continue
            val key = parts[0]
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) continue
            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            val total = values.sum()
            samples[key] = CpuTimeSample(totalTicks = total, idleTicks = idle)
        }
        return samples.takeIf { it.isNotEmpty() }
    }

    private fun computeCpuDelta(prev: CpuTimeSample?, curr: CpuTimeSample?): Float? {
        if (prev == null || curr == null) return null
        val deltaTotal = curr.totalTicks - prev.totalTicks
        val deltaIdle = curr.idleTicks - prev.idleTicks
        if (deltaTotal <= 0L) return null
        return ((deltaTotal - deltaIdle).toFloat() / deltaTotal.toFloat()).coerceIn(0f, 1f)
    }

    internal fun sampleGpu(context: Context?): GpuStats {
        val renderer = probeGpuRenderer(context)
        val kgslDir = File(sysRoot, "class/kgsl/kgsl-3d0")
        var busyPercent: Float? = null
        var curFreqMhz: Int? = null
        var maxFreqMhz: Int? = null

        val kgslBusyText = readFirstLineSafely(File(kgslDir, "gpu_busy_percentage"))
        if (kgslBusyText != null) {
            val pct = kgslBusyText.replace("%", "").trim().toFloatOrNull()
            if (pct != null) busyPercent = (pct / 100f).coerceIn(0f, 1f)
        }

        val kgslClockMhz = readLongSafely(File(kgslDir, "clock_mhz"))
        if (kgslClockMhz != null && kgslClockMhz > 0L) {
            curFreqMhz = kgslClockMhz.toInt()
        } else {
            val gpuClkHz = readLongSafely(File(kgslDir, "gpuclk"))
            if (gpuClkHz != null && gpuClkHz > 0L) {
                curFreqMhz = (gpuClkHz / 1_000_000L).toInt()
            }
        }
        val kgslMaxHz = readLongSafely(File(kgslDir, "max_gpuclk"))
        if (kgslMaxHz != null && kgslMaxHz > 0L) {
            maxFreqMhz = (kgslMaxHz / 1_000_000L).toInt()
        }

        if (busyPercent == null || curFreqMhz == null) {
            val devfreqDir = File(sysRoot, "class/devfreq")
            val candidates = try {
                devfreqDir.listFiles()?.filter { dir ->
                    val low = dir.name.lowercase()
                    dir.isDirectory && (low.contains("gpu") || low.contains("kgsl") || low.contains("mali") || low.contains("adreno"))
                }.orEmpty()
            } catch (e: SecurityException) {
                safeLogDebug("Permission denied listing devfreq: ${e.message}")
                emptyList()
            }
            for (dir in candidates) {
                if (curFreqMhz == null) {
                    val curHz = readLongSafely(File(dir, "cur_freq"))
                    if (curHz != null && curHz > 0L) curFreqMhz = (curHz / 1_000_000L).toInt()
                }
                if (maxFreqMhz == null) {
                    val maxHz = readLongSafely(File(dir, "max_freq"))
                    if (maxHz != null && maxHz > 0L) maxFreqMhz = (maxHz / 1_000_000L).toInt()
                }
                if (busyPercent == null) {
                    val loadText = readFirstLineSafely(File(dir, "load"))
                    val loadPct = loadText?.substringBefore("@")?.trim()?.toFloatOrNull()
                    if (loadPct != null) busyPercent = (loadPct / 100f).coerceIn(0f, 1f)
                }
            }
        }

        return GpuStats(
            usagePercent = busyPercent ?: 0f,
            freqMhz = curFreqMhz ?: 0,
            maxFreqMhz = maxFreqMhz ?: 0,
            renderer = renderer,
            isUsageAvailable = busyPercent != null,
            isFreqAvailable = curFreqMhz != null
        )
    }

    internal fun sampleThermal(context: Context?): ThermalStats {
        val sensors = mutableListOf<SensorTemp>()

        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val hpm = context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as? HardwarePropertiesManager
                if (hpm != null) {
                    readHardwareTemp(hpm, HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU, "CPU")?.let { sensors.add(it) }
                    readHardwareTemp(hpm, HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU, "GPU")?.let { sensors.add(it) }
                    readHardwareTemp(hpm, HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN, "机身")?.let { sensors.add(it) }
                    readHardwareTemp(hpm, HardwarePropertiesManager.DEVICE_TEMPERATURE_BATTERY, "电池")?.let { sensors.add(it) }
                }
            } catch (e: SecurityException) {
                safeLogDebug("HardwarePropertiesManager requires privileged permission: ${e.message}")
            } catch (e: RuntimeException) {
                safeLogWarn("HardwarePropertiesManager probe failed", e)
            }
        }

        if (sensors.isEmpty()) {
            val thermalRoot = File(sysRoot, "class/thermal")
            for (idx in 0 until MAX_THERMAL_ZONES) {
                val zoneDir = File(thermalRoot, "thermal_zone$idx")
                val rawTemp = readFirstLineSafely(File(zoneDir, "temp"))?.toFloatOrNull() ?: continue
                val tempC = normalizeTemperatureC(rawTemp) ?: continue
                val rawType = readFirstLineSafely(File(zoneDir, "type")) ?: "Zone $idx"
                sensors.add(SensorTemp(name = formatThermalZoneName(rawType), tempC = tempC))
                if (sensors.size >= MAX_DISPLAY_SENSORS) break
            }
        }

        if (context != null && sensors.none { it.name == "电池" }) {
            readBatteryTempC(context)?.let { battTemp ->
                sensors.add(SensorTemp(name = "电池", tempC = battTemp))
            }
        }

        val headlineTemp = sensors.mapNotNull { it.tempC }.maxOrNull()?.toInt()
        return ThermalStats(
            headlineTempC = headlineTemp,
            sensors = sensors
        )
    }

    private fun readHardwareTemp(hpm: HardwarePropertiesManager, type: Int, label: String): SensorTemp? {
        val temps = hpm.getDeviceTemperatures(type, HardwarePropertiesManager.TEMPERATURE_CURRENT)
        val valid = temps.firstOrNull { it != HardwarePropertiesManager.UNDEFINED_TEMPERATURE && it > -20f && it < 150f }
            ?: return null
        return SensorTemp(name = label, tempC = valid)
    }

    private fun readBatteryTempC(context: Context): Float? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenths > 0 && tenths < 1500) {
                tenths / 10f
            } else {
                null
            }
        } catch (e: SecurityException) {
            safeLogWarn("Permission denied reading battery temperature", e)
            null
        } catch (e: RuntimeException) {
            safeLogWarn("Failed reading battery temperature", e)
            null
        }
    }

    private fun normalizeTemperatureC(raw: Float): Float? {
        val celsius = when {
            raw > 1000f -> raw / 1000f
            raw > 150f -> raw / 10f
            else -> raw
        }
        return celsius.takeIf { it > -20f && it < 150f }
    }

    private fun formatThermalZoneName(rawType: String): String {
        val low = rawType.lowercase()
        return when {
            low.contains("cpu") || low.contains("tsens") || low.contains("aoss") -> "CPU ($rawType)"
            low.contains("gpu") -> "GPU ($rawType)"
            low.contains("batt") -> "电池"
            low.contains("skin") || low.contains("xo-therm") -> "机身 ($rawType)"
            low.contains("ddr") || low.contains("mem") -> "Mem ($rawType)"
            else -> rawType
        }
    }

    private fun parseCpuRangeCount(rangeSpec: String): Int {
        var total = 0
        for (part in rangeSpec.trim().split(",")) {
            val bounds = part.trim().split("-")
            if (bounds.size == 2) {
                val start = bounds[0].toIntOrNull()
                val end = bounds[1].toIntOrNull()
                if (start != null && end != null && end >= start) {
                    total += (end - start + 1)
                }
            } else if (bounds.size == 1 && bounds[0].toIntOrNull() != null) {
                total += 1
            }
        }
        return total
    }

    private fun readFirstLineSafely(file: File): String? = SafeProbeIo.readFirstLine(TAG, file)

    private fun readTextLinesSafely(file: File): List<String>? = SafeProbeIo.readLines(TAG, file)

    private fun readLongSafely(file: File): Long? {
        return readFirstLineSafely(file)?.toLongOrNull()
    }

    private fun safeLogWarn(message: String, throwable: Throwable? = null) =
        SafeProbeIo.warn(TAG, message, throwable)

    private fun safeLogDebug(message: String) = SafeProbeIo.debug(TAG, message)

    companion object {
        private const val TAG = "SystemTelemetryProvider"
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val CPU_DELTA_SAMPLE_DELAY_MS = 120L
        private const val MAX_THERMAL_ZONES = 32
        private const val MAX_DISPLAY_SENSORS = 8
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val CPU_DIR_REGEX = Regex("^cpu\\d+$")
    }
}
