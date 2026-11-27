package com.aliceincradle.launcher.ui.file

import com.aliceincradle.launcher.data.FileItem
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileClickRouterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun enforcesFixedPriorityAndBinaryOversizedInterception() {
        val parent = FileItem(id = "p", name = "..", isFolder = true, sizeBytes = 0L)
        assertTrue(FileClickRouter.open(parent) is FileOpenAction.NavigateParent)

        val dir = FileItem(id = "d", name = "Mods", isFolder = true, sizeBytes = 0L)
        assertTrue(FileClickRouter.open(dir) is FileOpenAction.EnterDirectory)

        val binFile = tempFolder.newFile("data.txt").apply {
            writeBytes(byteArrayOf(0x41, 0x00, 0x42))
        }
        val binItem = FileItem(id = "b", name = "data.txt", path = binFile.absolutePath, isFolder = false, sizeBytes = 3L)
        assertTrue(FileClickRouter.open(binItem, binFile) is FileOpenAction.BlockedBinaryOrOversized)

        val iniFile = tempFolder.newFile("doorstop_config.ini").apply { writeText("[General]\nenabled=true\n") }
        val iniItem = FileItem(id = "i", name = "doorstop_config.ini", path = iniFile.absolutePath, isFolder = false, sizeBytes = iniFile.length())
        assertTrue(FileClickRouter.open(iniItem, iniFile) is FileOpenAction.OpenConfigTemplate)

        val jsonFile = tempFolder.newFile("custom.json").apply { writeText("{}") }
        val jsonItem = FileItem(id = "j", name = "custom.json", path = jsonFile.absolutePath, isFolder = false, sizeBytes = jsonFile.length())
        val jsonAction = FileClickRouter.open(jsonItem, jsonFile) as FileOpenAction.OpenConfigUnmatched
        assertTrue(jsonAction.fallbackToReadOnly)

        val ktFile = tempFolder.newFile("App.kt").apply { writeText("fun main() {}") }
        val ktItem = FileItem(id = "k", name = "App.kt", path = ktFile.absolutePath, isFolder = false, sizeBytes = ktFile.length())
        assertTrue(FileClickRouter.open(ktItem, ktFile) is FileOpenAction.OpenCodeEditor)

        val mdFile = tempFolder.newFile("README.md").apply { writeText("# Hello") }
        val mdItem = FileItem(id = "m", name = "README.md", path = mdFile.absolutePath, isFolder = false, sizeBytes = mdFile.length())
        val mdAction = FileClickRouter.open(mdItem, mdFile) as FileOpenAction.OpenReadOnlyViewer
        assertTrue(mdAction.isMarkdown)
    }
}
