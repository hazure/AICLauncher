package com.aliceincradle.launcher.bridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloaderFallbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cfChallengeHtmlNeverEntersExtraction() {
        val cfFile = tempFolder.newFile("cf.7z").apply {
            writeText("<!doctype html><html><body>Challenge</body></html>")
        }
        val target = tempFolder.newFolder("target")
        val cache = tempFolder.newFolder("cache")
        val files = tempFolder.newFolder("files")

        val res = ArchiveExtractor().extractAtomically(cfFile, target, cache, files, "fp_cf")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("CF_CHALLENGE_HTML") == true)
        assertTrue(target.listFiles()?.isEmpty() == true)
    }
}
