package com.aliceincradle.launcher.bridge

import com.aliceincradle.launcher.data.CatalogHttpClient
import com.aliceincradle.launcher.data.DownloadCatalogItem
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadQueueManager
import com.aliceincradle.launcher.data.RemoteCatalogFetcher
import com.aliceincradle.launcher.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResolveEntryPointWiringTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun uiDownloadClickAlwaysRoutesThroughDownloadCoordinator() {
        val logs = mutableListOf<String>()
        val store = ShortCodeCacheStore(tempFolder.newFile("sc.properties")).apply {
            save(CachedShortCode("Oz4hM", "Alice.7z", "0.30i", isManual = false))
        }
        val resolver = GameSourceResolver(
            shortLinkRedirectFetcher = { Result.success("https://dl.aliceincradle.dev/vanilla/Alice.7z?x-oss-signature=sigA") },
            pipelineLogger = { logs += it }
        )
        val coordinator = DownloadCoordinator(resolver, store, pipelineLogger = { logs += it })
        val item = DownloadCatalogItem(
            id = "aic-win",
            title = "AIC",
            tags = listOf("游戏本体"),
            sizeLabel = "1 MB",
            status = DownloadContentStatus.AVAILABLE,
            downloadUrl = "https://expired.example.com/old.7z",
            fileName = "Alice.7z"
        )
        val fetcher = object : RemoteCatalogFetcher(CatalogHttpClient { "[]" }) {
            override fun fetchCatalog(downloadDir: File?) = Result.success(listOf(item))
        }
        val queue = DownloadQueueManager(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            workerCount = 1,
            streamDownloader = { req, listener ->
                req.destFile.parentFile?.mkdirs()
                req.destFile.writeText("ok")
                listener.onCompleted(req.itemId, req.destFile)
            }
        )
        val vm = MainViewModel(
            ioDispatcher = Dispatchers.Unconfined,
            autoStartPolling = false,
            catalogFetcher = fetcher,
            downloadQueueManagerOverride = queue,
            autoFetchCatalog = true,
            downloadCoordinatorOverride = coordinator,
            useWebViewPrimaryOverride = false
        )

        vm.triggerCatalogDownload("aic-win")
        assertTrue(logs.any { it.contains("RESOLVE_ENTER") })
        assertTrue(logs.any { it.contains("RESOLVE_EXIT") && it.contains("status=OK") })
    }
}
