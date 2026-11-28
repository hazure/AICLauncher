package com.aliceincradle.launcher.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoDesktopIdentityTest {
    @Test
    fun buildDesktopFirefoxUserAgent_isSelfConsistentDesktopFirefox() {
        val ua = GeckoDesktopIdentity.buildDesktopFirefoxUserAgent()
        assertTrue(GeckoDesktopIdentity.isValidDesktopFirefoxUa(ua))
        assertTrue(ua.contains("Firefox/133.0"))
        assertTrue(ua.contains("Windows NT 10.0; Win64; x64"))
        assertFalse(ua.contains("Chrome", ignoreCase = true))
        assertFalse(ua.contains("Android", ignoreCase = true))
        assertFalse(ua.contains("Mobile", ignoreCase = true))
    }

    @Test
    fun isValidDesktopFirefoxUa_rejectsChromeOrMobileUas() {
        assertFalse(GeckoDesktopIdentity.isValidDesktopFirefoxUa("Mozilla/5.0 (Linux; Android 14) Gecko/20100101 Firefox/133.0"))
        assertFalse(GeckoDesktopIdentity.isValidDesktopFirefoxUa("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36"))
    }

    @Test
    fun extractFileNameFromContentDisposition_parsesQuotedAndFallback() {
        assertEquals(
            "AliceInCradle_v030i.7z",
            GeckoDesktopIdentity.extractFileNameFromContentDisposition(
                """attachment; filename="AliceInCradle_v030i.7z"""",
                "https://dl.aliceincradle.dev/file"
            )
        )
        assertEquals(
            "fallback.7z",
            GeckoDesktopIdentity.extractFileNameFromContentDisposition(
                null,
                "https://dl.aliceincradle.dev/path/fallback.7z"
            )
        )
    }
}
