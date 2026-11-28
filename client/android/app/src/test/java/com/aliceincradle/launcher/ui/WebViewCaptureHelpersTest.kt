package com.aliceincradle.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewCaptureHelpersTest {
    @Test
    fun parseWebViewMajorVersion_parsesModernAndLegacyVersions() {
        assertEquals(160, parseWebViewMajorVersion("160.0.8171.0"))
        assertEquals(103, parseWebViewMajorVersion("103.0.5060.129"))
        assertNull(parseWebViewMajorVersion(null))
        assertNull(parseWebViewMajorVersion(""))
        assertNull(parseWebViewMajorVersion("unknown"))
    }

    @Test
    fun extractClipboardShortCode_extractsOfficialShortLinkOnly() {
        val direct = extractClipboardShortCode("https://vanilla.shiro.dev/f/abc123/AliceInCradle_Win_Latest.7z")
        assertEquals("abc123" to "AliceInCradle_Win_Latest.7z", direct)

        val embedded = extractClipboardShortCode("下载链接：https://vanilla.shiro.dev/f/xyz789/game.7z 请打开")
        assertEquals("xyz789" to "game.7z", embedded)

        assertNull(extractClipboardShortCode("https://example.com/other/file.7z"))
        assertNull(extractClipboardShortCode("普通剪贴板文本"))
    }

    @Test
    fun clampZoomPercent_andDesktopUa_matchExpectedContract() {
        assertEquals(MIN_ZOOM_PERCENT, clampZoomPercent(10))
        assertEquals(80, clampZoomPercent(80))
        assertEquals(MAX_ZOOM_PERCENT, clampZoomPercent(220))
        assertEquals(true, DESKTOP_CHROME_UA.contains("Windows NT"))
        assertEquals(true, DESKTOP_CHROME_UA.contains("Chrome/"))
        assertEquals(false, DESKTOP_CHROME_UA.contains("Mobile"))
    }

    @Test
    fun cloudflareChallengeDetection_andAllowedHosts_workAsExpected() {
        assertEquals(true, isAllowedNavigationHost("get.aliceincradle.dev"))
        assertEquals(true, isAllowedNavigationHost("challenges.cloudflare.com"))
        assertEquals(false, isAllowedNavigationHost("evil.example.com"))
        assertEquals(true, isCloudflareChallengeUrl("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b"))
        assertEquals(true, isCloudflareChallengeUrl("https://dl.aliceincradle.dev/?__cf_chl_tk=abc"))
        assertEquals(false, isCloudflareChallengeUrl("https://get.aliceincradle.dev/win/"))
    }
}
