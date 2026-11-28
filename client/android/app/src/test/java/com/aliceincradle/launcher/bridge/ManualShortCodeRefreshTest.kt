package com.aliceincradle.launcher.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManualShortCodeRefreshTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun manualShortCodeRefreshesSignedUrlWithoutLeakingPlaintext() = runBlocking {
        val logs = mutableListOf<String>()
        val store = ShortCodeCacheStore(tempFolder.newFile("manual_sc.properties"))
        var callCount = 0
        val resolver = GameSourceResolver(
            shortLinkRedirectFetcher = {
                callCount++
                Result.success("https://dl.aliceincradle.dev/vanilla/Alice.7z?x-oss-signature=secretSig$callCount")
            },
            pipelineLogger = { logs += it }
        )
        val coordinator = DownloadCoordinator(resolver, store, pipelineLogger = { logs += it })
        coordinator.saveShortCode("Oz4hM", "Alice.7z", version = "0.30i", isManual = true)

        val first = coordinator.start() as ResolveOutcome.Ready
        val second = coordinator.start(oldFp = first.fp) as ResolveOutcome.Ready

        assertTrue(first.isManualShortCode)
        assertNotEquals(first.fp, second.fp)
        assertFalse(first.source.toString().contains("secretSig"))
        assertFalse(first.source.toMetadataJson().contains("secretSig"))
        assertTrue(logs.none { it.contains("secretSig") })
        assertTrue(logs.any { it.contains("sourceKind=manual_shortcode") })
    }
}
