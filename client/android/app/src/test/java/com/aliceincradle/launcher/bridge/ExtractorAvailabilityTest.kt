package com.aliceincradle.launcher.bridge

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtractorAvailabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun missingSevenZipReturnsExtractorMissingWithoutFallingBackToZip() {
        val archive = tempFolder.newFile("game.7z").apply {
            writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x04))
        }
        val target = tempFolder.newFolder("target")
        val cache = tempFolder.newFolder("cache")
        val files = tempFolder.newFolder("files")

        val extractor = ArchiveExtractor(EnvironmentProbe(envProvider = { emptyMap() }))
        val res = extractor.extractAtomically(archive, target, cache, files, "fp123")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is ExtractorMissingException)
        assertTrue(target.listFiles()?.isEmpty() == true)
    }
}
