package com.aliceincradle.launcher.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HandoffToInjectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun probeLocatesExtractedUnityRootAndHandsOffToInjectionBridge() {
        val extractedRoot = tempFolder.newFolder("extracted")
        val gameDir = File(extractedRoot, "Win ver030i/AliceInCradle_ver030i").apply { mkdirs() }
        File(gameDir, "AliceInCradle.exe").writeText("MZ")
        File(gameDir, "AliceInCradle_Data/Managed").mkdirs()

        val probe = EnvironmentProbe(envProvider = { emptyMap() })
        val outcome = probe.resolveGameRoot(null, extractedRoot)
        assertTrue(outcome is GameProbeOutcome.Found)
        val found = outcome as GameProbeOutcome.Found
        assertEquals("mono", found.root.runtime)
        assertEquals(gameDir.canonicalPath, found.root.rootDir.canonicalPath)
    }
}
