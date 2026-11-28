package com.aliceincradle.launcher.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRefreshTest {

    @Test
    fun resolvesFreshSignedUrlFrom302RedirectAndExcludesUrlFromSerialization() = runBlocking {
        var seq = 0
        val resolver = GameSourceResolver(
            shortLinkRedirectFetcher = ShortLinkRedirectFetcher { shortUrl ->
                assertTrue(shortUrl.startsWith("https://vanilla.shiro.dev/f/Oz4hM/"))
                seq++
                Result.success("https://dl.aliceincradle.dev/vanilla/pkg.7z?x-oss-signature=sig$seq")
            }
        )

        val first = resolver.resolveFromShortCode("Oz4hM", "AliceInCradle Win ver030i.7z", "0.30i").getOrThrow()
        val second = resolver.resolveFromShortCode("Oz4hM", "AliceInCradle Win ver030i.7z", "0.30i").getOrThrow()
        assertNotEquals(first.url, second.url)
        assertEquals(SourceKind.SHORTCODE_REFRESH, first.sourceKind)
        assertFalse(first.toMetadataJson().contains("sig1"))
        assertFalse(first.toString().contains("sig1"))
    }
}
