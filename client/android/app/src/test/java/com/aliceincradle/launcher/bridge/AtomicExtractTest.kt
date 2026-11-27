package com.aliceincradle.launcher.bridge

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicExtractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extractsZipAtomicallyAndRecoversPendingCheckpoint() {
        val zipFile = tempFolder.newFile("game.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("AliceInCradle.exe"))
            zos.write("MZ".toByteArray())
            zos.closeEntry()
        }
        val target = tempFolder.newFolder("target")
        val cache = tempFolder.newFolder("cache")
        val files = tempFolder.newFolder("files")

        // Simulate interrupted checkpoint
        val staleTemp = File(cache, "extract_tmp_stale").apply { mkdirs() }
        File(staleTemp, "partial.bin").writeText("half")
        File(cache, ArchiveExtractor.EXTRACT_CHECKPOINT_FILE).writeText(
            "phase=EXTRACTING\nurl_fp=abc\ntemp_dir=${staleTemp.absolutePath}\ntarget_dir=${target.absolutePath}\n"
        )

        val extractor = ArchiveExtractor()
        assertNotNull(extractor.detectPendingExtractCheckpoint(cache))
        assertTrue(extractor.recoverExtractCheckpoint(cache))
        assertFalse(staleTemp.exists())
        assertNull(extractor.detectPendingExtractCheckpoint(cache))

        val res = extractor.extractAtomically(zipFile, target, cache, files, "fp_zip")
        assertTrue(res.isSuccess)
        assertTrue(File(target, "AliceInCradle.exe").isFile)
    }
}
