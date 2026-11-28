package com.aliceincradle.launcher.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgeGateCaptureTest {

    @Test
    fun parsesShortCodeAndFileNameFromVanillaShortLink() {
        val parsed = GameSourceResolver.parseShortLinkUrl(
            "https://vanilla.shiro.dev/f/Oz4hM/AliceInCradle%20Win%20ver030i.7z"
        )
        assertNotNull(parsed)
        assertEquals("Oz4hM", parsed!!.first)
        assertEquals("AliceInCradle Win ver030i.7z", parsed.second)

        assertNull(GameSourceResolver.parseShortLinkUrl("https://example.com/other/path"))
    }
}
