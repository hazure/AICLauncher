package com.aliceincradle.launcher.bridge

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveMagicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun detectsSevenZAndZipByMagicBytesRegardlessOfExtension() {
        val fakeZipActually7z = tempFolder.newFile("disguised.zip").apply {
            writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x04))
        }
        val fake7zActuallyZip = tempFolder.newFile("disguised.7z").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        }
        val unknown = tempFolder.newFile("plain.bin").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        }

        val extractor = ArchiveExtractor()
        assertEquals(ArchiveContainer.SEVEN_Z, extractor.detectContainer(fakeZipActually7z))
        assertEquals(ArchiveContainer.ZIP, extractor.detectContainer(fake7zActuallyZip))
        assertEquals(ArchiveContainer.UNKNOWN, extractor.detectContainer(unknown))
    }
}
