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
}
