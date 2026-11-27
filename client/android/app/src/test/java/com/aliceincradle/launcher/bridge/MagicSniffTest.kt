package com.aliceincradle.launcher.bridge

import com.aliceincradle.launcher.data.DownloadQueueManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        val destRejected = tempFolder.root.resolve("rejected.7z")
        val cfRes = DownloadQueueManager.verifyMagicAndFinalize(cfHtml, destRejected, null, "fp1", extractor)
        assertTrue(cfRes.isFailure)
        assertTrue(cfRes.exceptionOrNull()?.message?.contains("CF_CHALLENGE_HTML") == true)
        assertFalse(destRejected.exists())
        assertFalse(cfHtml.exists())

        val destAccepted = tempFolder.root.resolve("accepted.7z")
        val okRes = DownloadQueueManager.verifyMagicAndFinalize(sevenZ, destAccepted, null, "fp2", extractor)
        assertTrue(okRes.isSuccess)
        assertTrue(destAccepted.exists())
    }
}
