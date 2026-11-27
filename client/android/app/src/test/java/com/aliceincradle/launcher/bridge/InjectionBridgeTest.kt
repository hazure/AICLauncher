package com.aliceincradle.launcher.bridge

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InjectionBridgeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createUnityMonoGameDir(name: String = "AliceInCradle_ver030i"): File {
        val dir = tempFolder.newFolder(name)
        File(dir, "AliceInCradle.exe").writeText("MZ-game-exe")
        File(dir, "AliceInCradle_Data/Managed").mkdirs()
        return dir
    }

    private fun createBepInExZip(name: String = "BepInEx_x64_5.4.22.0.zip"): File {
        val zipFile = tempFolder.newFile(name)
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("doorstop_config.ini"))
            zos.write("[UnityDoorstop]\nenabled=true\n".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("winhttp.dll"))
            zos.write("MZ-winhttp-stub".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("BepInEx/core/BepInEx.dll"))
            zos.write("MZ-bepinex-core".toByteArray())
            zos.closeEntry()
        }
        return zipFile
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(file.readBytes())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun fullInjectionSucceedsAndWritesReportAndMarker() {
        val gameDir = createUnityMonoGameDir()
        val fwZip = createBepInExZip()
        val filesDir = tempFolder.newFolder("app_files")
        val cacheDir = tempFolder.newFolder("app_cache")
        val pluginDir = tempFolder.newFolder("user_plugins")
        val pluginDll = File(pluginDir, "TestMod.dll").apply { writeText("MZ-plugin-payload") }
        File(pluginDir, "TestMod.dll.sha256").writeText(sha256Hex(pluginDll))

        val bridge = InjectionBridge(EnvironmentProbe(envProvider = { emptyMap() }))
        val (status, gameRoot, framework) = bridge.evaluateEnvironment(
            context = null,
            filesDir = filesDir,
            userGameDir = gameDir,
            userFrameworkFile = fwZip,
            userPluginDir = pluginDir
        )
        assertEquals(EnvironmentStatus.READY, status)
        assertNotNull(gameRoot)
        assertEquals("mono", gameRoot!!.runtime)
        assertNotNull(framework)

        val plugins = bridge.resolvePlugins(null, pluginDir)
        val plan = bridge.prepareInjectionPlan(gameRoot, framework!!, plugins).getOrThrow()
        val report = bridge.executeInjection(plan, filesDir, cacheDir).getOrThrow()

        assertEquals("READY", report.status)
        assertTrue(File(gameDir, "doorstop_config.ini").isFile)
        assertTrue(File(gameDir, "winhttp.dll").isFile)
        assertTrue(File(gameDir, "BepInEx/core/BepInEx.dll").isFile)
        assertTrue(File(gameDir, "BepInEx/plugins/TestMod.dll").isFile)
        assertTrue(File(filesDir, InjectionBridge.REPORT_FILE_NAME).isFile)
        assertNull(bridge.detectPendingCheckpoint(cacheDir))

        val (afterStatus, _, _) = bridge.evaluateEnvironment(
            context = null,
            filesDir = filesDir,
            userGameDir = gameDir,
            userFrameworkFile = fwZip,
            userPluginDir = pluginDir
        )
        assertEquals(EnvironmentStatus.ALREADY_INJECTED, afterStatus)
    }

    @Test
    fun missingGameOrFrameworkReturnsGracefulDegradedStatus() {
        val filesDir = tempFolder.newFolder("files_empty")
        val emptyDir = tempFolder.newFolder("empty_game")
        val bridge = InjectionBridge(EnvironmentProbe(envProvider = { emptyMap() }))

        val (noGameStatus, _, _) = bridge.evaluateEnvironment(null, filesDir, userGameDir = emptyDir)
        assertEquals(EnvironmentStatus.GAME_NOT_FOUND, noGameStatus)

        val gameDir = createUnityMonoGameDir("mono_no_fw")
        val (noFwStatus, _, _) = bridge.evaluateEnvironment(null, filesDir, userGameDir = gameDir)
        assertEquals(EnvironmentStatus.FRAMEWORK_MISSING, noFwStatus)
    }

    @Test
    fun preparePlanWithoutExecuteLeavesZeroFiles() {
        val gameDir = createUnityMonoGameDir("mono_cancel")
        val fwZip = createBepInExZip("fw_cancel.zip")
        val bridge = InjectionBridge(EnvironmentProbe(envProvider = { emptyMap() }))
        val outcome = bridge.resolveGameRoot(null, gameDir) as GameProbeOutcome.Found

        val plan = bridge.prepareInjectionPlan(outcome.root, FrameworkSource(fwZip, true), emptyList()).getOrThrow()
        assertTrue(plan.plannedFiles.isNotEmpty())
        assertFalse(File(gameDir, "winhttp.dll").exists())
        assertFalse(File(gameDir, "doorstop_config.ini").exists())
    }

    @Test
    fun checkpointRecoveryCleansPartialFilesAndAllowsCleanReinjection() {
        val gameDir = createUnityMonoGameDir("mono_ckpt")
        val fwZip = createBepInExZip("fw_ckpt.zip")
        val filesDir = tempFolder.newFolder("files_ckpt")
        val cacheDir = tempFolder.newFolder("cache_ckpt")

        // Simulate mid-injection kill: winhttp.dll written, BepInEx/core missing
        File(gameDir, "winhttp.dll").writeText("partial")
        File(cacheDir, InjectionBridge.CHECKPOINT_FILE_NAME).writeText(
            "phase=INJECTING\ntarget_dir=${gameDir.absolutePath}\nfile=winhttp.dll\n"
        )

        val bridge = InjectionBridge(EnvironmentProbe(envProvider = { emptyMap() }))
        val pending = bridge.detectPendingCheckpoint(cacheDir)
        assertNotNull(pending)
        val removed = bridge.recoverCheckpoint(cacheDir).getOrThrow()
        assertEquals(1, removed)
        assertFalse(File(gameDir, "winhttp.dll").exists())
        assertNull(bridge.detectPendingCheckpoint(cacheDir))

        val outcome = bridge.resolveGameRoot(null, gameDir) as GameProbeOutcome.Found
        val plan = bridge.prepareInjectionPlan(outcome.root, FrameworkSource(fwZip, true), emptyList()).getOrThrow()
        val report = bridge.executeInjection(plan, filesDir, cacheDir).getOrThrow()
        assertEquals("READY", report.status)
        assertTrue(File(gameDir, "BepInEx/core/BepInEx.dll").isFile)
    }

    @Test
    fun mismatchedPluginSha256TriggersRollback() {
        val gameDir = createUnityMonoGameDir("mono_bad_sha")
        val fwZip = createBepInExZip("fw_bad_sha.zip")
        val filesDir = tempFolder.newFolder("files_bad_sha")
        val cacheDir = tempFolder.newFolder("cache_bad_sha")
        val pluginDir = tempFolder.newFolder("plugins_bad_sha")
        val pluginDll = File(pluginDir, "BadMod.dll").apply { writeText("MZ-bad") }

        val bridge = InjectionBridge(EnvironmentProbe(envProvider = { emptyMap() }))
        val outcome = bridge.resolveGameRoot(null, gameDir) as GameProbeOutcome.Found
        val badPlugin = PluginCandidate(
            file = pluginDll,
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        val plan = bridge.prepareInjectionPlan(outcome.root, FrameworkSource(fwZip, true), listOf(badPlugin)).getOrThrow()
        val result = bridge.executeInjection(plan, filesDir, cacheDir)

        assertTrue(result.isFailure)
        assertFalse(File(gameDir, "winhttp.dll").exists())
        assertFalse(File(gameDir, "doorstop_config.ini").exists())
        assertFalse(File(gameDir, "BepInEx/plugins/BadMod.dll").exists())
    }
}
