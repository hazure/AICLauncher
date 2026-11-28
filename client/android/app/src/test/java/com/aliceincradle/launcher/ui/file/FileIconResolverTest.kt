package com.aliceincradle.launcher.ui.file

import com.aliceincradle.launcher.data.FileItem
import com.aliceincradle.launcher.ui.icon.FileIconResolver
import com.aliceincradle.launcher.ui.icon.IconSpec
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileIconResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolvesExtensionsUpperCaseMultiDotHiddenAndUnknown() {
        assertEquals(IconSpec.CODE, FileIconResolver.resolveByName("Main.KT"))
        assertEquals(IconSpec.CONFIG, FileIconResolver.resolveByName("doorstop_config.INI"))
        assertEquals(IconSpec.MARKDOWN, FileIconResolver.resolveByName("README.v2.MD"))
        assertEquals(IconSpec.ARCHIVE, FileIconResolver.resolveByName("BepInEx.tar.gz"))
        assertEquals(IconSpec.BINARY, FileIconResolver.resolveByName("AliceInCradle.exe"))
        assertEquals(IconSpec.UNKNOWN, FileIconResolver.resolveByName("Makefile"))
        assertEquals(IconSpec.UNKNOWN, FileIconResolver.resolveByName(".gitignore"))
        assertEquals(IconSpec.UNKNOWN, FileIconResolver.resolveByName("unknown.xyz123"))
    }

    @Test
    fun resolvesFolderParentAndCacheHit() {
        FileIconResolver.clearCache()
        val parent = FileItem(id = "p", name = "..", isFolder = true, sizeBytes = 0L)
        val dir = FileItem(id = "d", name = "Mods", isFolder = true, sizeBytes = 0L)
        val kt = FileItem(id = "k", name = "Test.kt", path = "/tmp/Test.kt", isFolder = false, sizeBytes = 10L)

        assertEquals(IconSpec.PARENT, FileIconResolver.resolve(parent))
        assertEquals(IconSpec.FOLDER, FileIconResolver.resolve(dir))
        val first = FileIconResolver.resolve(kt)
        val second = FileIconResolver.resolve(kt)
        assertEquals(IconSpec.CODE, first)
        assertSame(first, second)
    }

    @Test
    fun resolvesSymlinkTargetOrUnknownWhenBroken() {
        val target = tempFolder.newFile("script.py").apply { writeText("print(1)") }
        val linkPath = File(tempFolder.root, "link_to_py").toPath()
        val brokenPath = File(tempFolder.root, "broken_link").toPath()
        val created = runCatching {
            Files.createSymbolicLink(linkPath, target.toPath())
            Files.createSymbolicLink(brokenPath, File(tempFolder.root, "missing.py").toPath())
            true
        }.getOrElse { false }

        if (created) {
            assertEquals(IconSpec.CODE, FileIconResolver.resolveFile(linkPath.toFile()))
            assertEquals(IconSpec.UNKNOWN, FileIconResolver.resolveFile(brokenPath.toFile()))
        }
    }
}
