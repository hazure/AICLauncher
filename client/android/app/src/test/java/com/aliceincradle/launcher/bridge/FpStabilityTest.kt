package com.aliceincradle.launcher.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FpStabilityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fpChangesWhenSignedUrlRefreshesEvenWithSameShortCode() = runBlocking {
        val store = ShortCodeCacheStore(tempFolder.newFile("fp_sc.properties")).apply {
            save(CachedShortCode("Oz4hM", "Alice.7z", "0.30i", isManual = false))
        }
        var seq = 0
        val resolver = GameSourceResolver(
            shortLinkRedirectFetcher = {
                seq++
                Result.success("https://dl.aliceincradle.dev/vanilla/Alice.7z?x-oss-expires=3600&x-oss-signature=sig_$seq")
            }
        )
        val coordinator = DownloadCoordinator(resolver, store)
        val r1 = coordinator.start() as ResolveOutcome.Ready
        val r2 = coordinator.start(oldFp = r1.fp) as ResolveOutcome.Ready

        assertNotEquals(r1.fp, r2.fp)
    }
}
