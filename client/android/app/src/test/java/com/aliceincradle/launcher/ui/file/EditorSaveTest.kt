package com.aliceincradle.launcher.ui.file

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorSaveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun atomicSaveCreatesBakPreservesBomAndCrlf() {
        val file = tempFolder.newFile("Script.kt")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file.writeBytes(bom + "fun a() = 1\r\n".toByteArray(Charsets.UTF_8))

        val read = FileTextCodec.readForEditor(file).getOrThrow()
        assertTrue(read.hasBom)
        assertEquals("\r\n", read.lineEnding)

        val saved = FileTextCodec.saveAtomically(file, "fun a() = 2\n", read.hasBom, read.lineEnding)
        assertTrue(saved.isSuccess)

        val bak = File(file.parentFile, "Script.kt.bak")
        assertTrue(bak.isFile)
        val newBytes = file.readBytes()
        assertEquals(0xEF.toByte(), newBytes[0])
        assertTrue(String(newBytes, 3, newBytes.size - 3, Charsets.UTF_8).contains("\r\n"))
    }

    @Test
    fun readOnlyFileReturnsPermissionDenied() {
        val file = tempFolder.newFile("ReadOnly.kt").apply {
            writeText("val x = 1")
            setWritable(false)
        }
        val res = FileTextCodec.saveAtomically(file, "val x = 2", hasBom = false, lineEnding = "\n")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("PERMISSION_DENIED") == true)
        file.setWritable(true)
    }
}
