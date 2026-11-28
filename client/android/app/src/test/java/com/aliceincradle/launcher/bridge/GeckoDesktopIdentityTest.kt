package com.aliceincradle.launcher.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeckoDesktopIdentityTest {
    @Test
    fun nativeGeckoDesktopMode_usesNoOverride_andRejectsAndroidChromeOrWindowsSpoof() {
        assertNull(GeckoDesktopIdentity.USE_NATIVE_USER_AGENT_OVERRIDE)
        assertTrue(
            GeckoDesktopIdentity.isValidNativeGeckoDesktopUa(
                "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0"
            )
        )
        assertFalse(
            GeckoDesktopIdentity.isValidNativeGeckoDesktopUa(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
            )
        )
        assertFalse(
            GeckoDesktopIdentity.isValidNativeGeckoDesktopUa(
                "Mozilla/5.0 (Android 13; Mobile; rv:133.0) Gecko/133.0 Firefox/133.0"
            )
        )
        assertFalse(
            GeckoDesktopIdentity.isValidNativeGeckoDesktopUa(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
            )
        )
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

    @Test
    fun requestPolicy_allowsImagesAndCdn_andOnlyInterceptsShortLinks() {
        assertFalse(GeckoDesktopIdentity.shouldInterceptAsShortCode("https://get.aliceincradle.dev/win/"))
        assertFalse(GeckoDesktopIdentity.shouldInterceptAsShortCode("https://get.aliceincradle.dev/_img/noel.webp"))
        assertTrue(GeckoDesktopIdentity.isAllowedNavigationRequest("https://get.aliceincradle.dev/_img/noel.webp"))
        assertTrue(GeckoDesktopIdentity.isAllowedNavigationRequest("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b"))
        assertTrue(GeckoDesktopIdentity.shouldInterceptAsShortCode("https://vanilla.shiro.dev/f/abc123/AliceInCradle_Win_Latest.7z"))
        assertFalse(GeckoDesktopIdentity.isAllowedNavigationRequest("https://vanilla.shiro.dev/f/abc123/AliceInCradle_Win_Latest.7z"))
    }
}
