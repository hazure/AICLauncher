package com.aliceincradle.launcher.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortCodeRelayExtractorTest {
    @Test
    fun extract_parsesOfficialShortLinkAndEmbeddedShareText() {
        val direct = ShortCodeRelayExtractor.extract("https://vanilla.shiro.dev/f/abc123/AliceInCradle_Win_Latest.7z")
        assertEquals("abc123", direct?.shortCode)
        assertEquals("AliceInCradle_Win_Latest.7z", direct?.fileName)

        val embedded = ShortCodeRelayExtractor.extract("链接：https://vanilla.shiro.dev/f/xyz789/game.7z，请下载")
        assertEquals("xyz789", embedded?.shortCode)
        assertEquals("game.7z", embedded?.fileName)
    }

    @Test
    fun extract_parsesQueryParamsSchemeLessAndStandaloneShortCodes() {
        val query = ShortCodeRelayExtractor.extract("https://get.aliceincradle.dev/win/?code=qwe123&file=aic.7z")
        assertEquals("qwe123", query?.shortCode)
        assertEquals("aic.7z", query?.fileName)

        val noScheme = ShortCodeRelayExtractor.extract("vanilla.shiro.dev/f/ns456/trial.7z")
        assertEquals("ns456", noScheme?.shortCode)
        assertEquals("trial.7z", noScheme?.fileName)

        val standalone = ShortCodeRelayExtractor.extract("ab12cd34")
        assertEquals("ab12cd34", standalone?.shortCode)
        assertEquals(ShortCodeRelayExtractor.DEFAULT_ARCHIVE_NAME, standalone?.fileName)
    }

    @Test
    fun extract_rejectsInvalidOrConsumedInputs() {
        assertNull(ShortCodeRelayExtractor.extract(null))
        assertNull(ShortCodeRelayExtractor.extract("   "))
        assertNull(ShortCodeRelayExtractor.extract("12345678"))
        assertNull(ShortCodeRelayExtractor.extract("download"))
        assertNull(ShortCodeRelayExtractor.extract("https://example.com/other/file.7z"))

        val fp = ShortCodeRelayExtractor.buildFingerprint("abc123", "AliceInCradle_Win_Latest.7z")
        assertNull(
            ShortCodeRelayExtractor.extract(
                "https://vanilla.shiro.dev/f/abc123/AliceInCradle_Win_Latest.7z",
                consumedFingerprints = setOf(fp)
            )
        )
    }
}
