package com.aliceincradle.launcher.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShortCodeCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun persistsShortCodeAndFileNameWithoutAnySignedUrl() {
        val cacheFile = File(tempFolder.root, "shortlink_cache.properties")
        val store = ShortCodeCacheStore(cacheFile)

        store.save(
            CachedShortCode(
                shortCode = "Oz4hM",
                fileName = "AliceInCradle Win ver030i.7z",
                version = "0.30i",
                updatedAtMs = 123456L
            )
        ).getOrThrow()

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("Oz4hM", loaded!!.shortCode)
        assertEquals("AliceInCradle Win ver030i.7z", loaded.fileName)
        assertEquals("0.30i", loaded.version)
        val rawText = cacheFile.readText()
        assertFalse(rawText.contains("x-oss-signature"))
        assertFalse(rawText.contains("dl.aliceincradle.dev"))
    }
}
