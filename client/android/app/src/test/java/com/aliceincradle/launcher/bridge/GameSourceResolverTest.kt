package com.aliceincradle.launcher.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GameSourceResolverTest {

    @Test
    fun directSuccessAndNullWhenBothFailWithoutThrowing() = runBlocking {
        val okResolver = GameSourceResolver(
            directFetcher = {
                GameSource(
                    url = "https://get.aliceincradle.dev/win/signed.7z?px-time=123",
                    version = "0.30i",
                    sizeBytes = 50_000_000L,
                    sourceKind = SourceKind.DIRECT_API
                )
            }
        )
        val res = okResolver.resolveLatestWinTrial(null)
        assertNotNull(res)
        assertEquals(SourceKind.DIRECT_API, res!!.sourceKind)
        assertFalse(res.toString().contains("px-time=123"))
        assertFalse(res.toMetadataJson().contains("px-time=123"))

        val failResolver = GameSourceResolver(
            directFetcher = { throw RuntimeException("network error") }
        )
        assertNull(failResolver.resolveLatestWinTrial(null))
    }
}
