package com.aliceincradle.launcher.bridge

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MagicSniffTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun detectsCfChallengeHtmlAndRejectsAsNonArchive() {
        val cfHtml = tempFolder.newFile("fake.7z").apply {
            writeText("<!doctype html><html><head><title>Just a moment...</title></head></html>")
        }
        val sevenZ = tempFolder.newFile("real.7z").apply {
            writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))
        }
        val extractor = ArchiveExtractor()
        assertEquals(ArchiveContainer.CF_CHALLENGE_HTML, extractor.detectContainer(cfHtml))
        assertEquals(ArchiveContainer.SEVEN_Z, extractor.detectContainer(sevenZ))
    }
}
