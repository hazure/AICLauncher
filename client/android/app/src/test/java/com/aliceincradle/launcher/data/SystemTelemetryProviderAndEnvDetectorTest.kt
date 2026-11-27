package com.aliceincradle.launcher.data

import com.aliceincradle.launcher.bridge.EnvDetector
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemTelemetryProviderAndEnvDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun samplesProcStatDeltaMeminfoGpuAndThermalFromSysfs() = runBlocking {
        val proc = tempFolder.newFolder("proc")
        val sys = tempFolder.newFolder("sys")

        File(proc, "meminfo").writeText(
            """
            MemTotal:       8192000 kB
            MemFree:        1024000 kB
            MemAvailable:   4096000 kB
            """.trimIndent()
        )

        val statFile = File(proc, "stat")
        statFile.writeText(
            """
            cpu  1000 0 500 8500 0 0 0 0 0 0
            cpu0 500 0 250 4250 0 0 0 0 0 0
            cpu1 500 0 250 4250 0 0 0 0 0 0
            """.trimIndent()
        )

        val cpuRoot = File(sys, "devices/system/cpu").apply { mkdirs() }
        File(cpuRoot, "online").writeText("0-1")
        listOf(0, 1).forEach { idx ->
            val cpufreq = File(cpuRoot, "cpu$idx/cpufreq").apply { mkdirs() }
            File(cpufreq, "scaling_cur_freq").writeText("1800000")
            File(cpufreq, "cpuinfo_max_freq").writeText("2400000")
        }

        val kgslDir = File(sys, "class/kgsl/kgsl-3d0").apply { mkdirs() }
        File(kgslDir, "gpu_busy_percentage").writeText(" 38 %")
        File(kgslDir, "clock_mhz").writeText("540")
        File(kgslDir, "max_gpuclk").writeText("850000000")

        val zone0 = File(sys, "class/thermal/thermal_zone0").apply { mkdirs() }
        File(zone0, "type").writeText("cpu-tsens")
        File(zone0, "temp").writeText("46500")

        val provider = SystemTelemetryProvider(procRoot = proc, sysRoot = sys)

        // Prime first snapshot, then mutate /proc/stat to simulate real CPU delta on second sample.
        provider.sample(null)
        statFile.writeText(
            """
            cpu  1400 0 700 8900 0 0 0 0 0 0
            cpu0 700 0 350 4450 0 0 0 0 0 0
            cpu1 700 0 350 4450 0 0 0 0 0 0
            """.trimIndent()
        )

        val sampled = provider.sample(null)

        assertTrue(sampled.memory.isAvailable)
        assertEquals(8000L, sampled.memory.totalDeviceMb)
        assertEquals(4000L, sampled.memory.availDeviceMb)
        assertEquals(0.5f, sampled.memory.usagePercent, 0.01f)

        assertTrue(sampled.cpu.isAvailable)
        assertFalse(sampled.cpu.isEstimatedFromFreq)
        assertEquals(0.6f, sampled.cpu.usagePercent, 0.02f)
        assertEquals(2, sampled.cpu.cores.size)
        assertEquals(1800, sampled.cpu.cores[0].freqMhz)
        assertEquals(60, sampled.cpu.cores[0].usagePercent)

        assertTrue(sampled.gpu.isUsageAvailable)
        assertTrue(sampled.gpu.isFreqAvailable)
        assertEquals(0.38f, sampled.gpu.usagePercent, 0.01f)
        assertEquals(540, sampled.gpu.freqMhz)
        assertEquals(850, sampled.gpu.maxFreqMhz)

        assertEquals(46, sampled.thermal.headlineTempC)
        assertEquals(1, sampled.thermal.sensors.size)
        assertEquals(46.5f, sampled.thermal.sensors[0].tempC ?: 0f, 0.01f)
    }

    @Test
    fun degradesGracefullyWhenProcStatThermalGpuAndPythonAreUnavailable() = runBlocking {
        val emptyProc = tempFolder.newFolder("empty_proc")
        val emptySys = tempFolder.newFolder("empty_sys")
        val zeroZone = File(emptySys, "class/thermal/thermal_zone0").apply { mkdirs() }
        File(zeroZone, "type").writeText("unused-zone")
        File(zeroZone, "temp").writeText("0")
        val cpuRoot = File(emptySys, "devices/system/cpu").apply { mkdirs() }
        File(cpuRoot, "online").writeText("0-1")
        val cpufreq0 = File(cpuRoot, "cpu0/cpufreq").apply { mkdirs() }
        File(cpufreq0, "scaling_cur_freq").writeText("1000000")
        File(cpufreq0, "cpuinfo_max_freq").writeText("2000000")

        val provider = SystemTelemetryProvider(procRoot = emptyProc, sysRoot = emptySys)
        val sampled = provider.sample(null)

        assertFalse(sampled.memory.isAvailable)
        assertTrue(sampled.cpu.isAvailable)
        assertTrue(sampled.cpu.isEstimatedFromFreq)
        assertFalse(sampled.gpu.isUsageAvailable)
        assertFalse(sampled.gpu.isFreqAvailable)
        assertNull(sampled.thermal.headlineTempC)
        assertTrue(sampled.thermal.sensors.isEmpty())

        val detector = EnvDetector(
            procRoot = emptyProc,
            allowBareCommandFallback = false,
            envProvider = { emptyMap() }
        )
        val python = detector.detectPython()
        val aicDir = detector.detectAicDirectory(null)
        assertFalse(python.isInstalled)
        assertEquals(EnvDetector.MISSING_PYTHON_LABEL, python.versionText)
        assertFalse(aicDir.isDetected)
        assertEquals(EnvDetector.MISSING_AIC_DIR_LABEL, aicDir.displayPath)
        assertFalse(detector.isGameRunning())
    }

    @Test
    fun detectsAicDirectoryAndGameProcessFromProcRoot() {
        val proc = tempFolder.newFolder("proc_game")
        val pidDir = File(proc, "4242").apply { mkdirs() }
        File(pidDir, "cmdline").writeText("wine\u0000AliceInCradle.exe\u0000-popupwindow")

        val gameDir = tempFolder.newFolder("game_root")
        File(gameDir, "AliceInCradle.exe").writeText("stub")

        val detector = EnvDetector(
            procRoot = proc,
            allowBareCommandFallback = false,
            envProvider = { mapOf("AIC_GAME_DIR" to gameDir.absolutePath) }
        )

        val aic = detector.detectAicDirectory(null)
        assertTrue(aic.isDetected)
        assertTrue(aic.hasExecutable)
        assertEquals(gameDir.absolutePath, aic.displayPath)

        val process = detector.probeGameProcess()
        assertTrue(detector.isGameRunning())
        assertTrue(process.isAlive)
        assertEquals(4242, process.pid)
        assertNotNull(process.versionName)
    }
}
