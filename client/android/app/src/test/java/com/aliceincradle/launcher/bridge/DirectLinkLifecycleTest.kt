package com.aliceincradle.launcher.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DirectLinkLifecycleTest {

    @Test
    fun signedUrlIsNeverPersistedAndSecondResolveFetchesFreshUrl() = runBlocking {
        var counter = 0
        var now = 1_000L
        val resolver = GameSourceResolver(
            directFetcher = {
                counter++
                GameSource(
                    url = "https://get.aliceincradle.dev/win/pkg.7z?sig=$counter",
                    version = "0.30i",
                    sizeBytes = 1024L,
                    sourceKind = SourceKind.DIRECT_API
                )
            },
            clockMs = { now }
        )

        val first = resolver.resolveLatestWinTrial(null)!!
        now += 5_000L
        val cachedMeta = resolver.getCachedMetadata()
        assertNotNull(cachedMeta)
        assertEquals("0.30i", cachedMeta!!.first)

        val second = resolver.resolveLatestWinTrial(null)!!
        assertNotEquals(first.url, second.url)
        assertFalse(first.toMetadataJson().contains("sig="))
    }
}
