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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShortCodeMissingBranchTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun missingShortCodeReturnsNeedCaptureAndResumesAfterCapture() = runBlocking {
        val emptyStore = ShortCodeCacheStore(File(tempFolder.root, "missing_sc.properties"))
        val resolver = GameSourceResolver(
            shortLinkRedirectFetcher = { Result.success("https://dl.aliceincradle.dev/vanilla/Alice.7z?x-oss-signature=resumed") }
        )
        val coordinator = DownloadCoordinator(resolver, emptyStore)

        val outcome = coordinator.start()
        assertTrue(outcome is ResolveOutcome.NeedCapture)

        val item = DownloadCatalogItem(
            id = "aic-win",
            title = "AIC",
            tags = listOf("游戏本体"),
            sizeLabel = "1 MB",
            status = DownloadContentStatus.AVAILABLE,
            downloadUrl = "https://legacy.example.com/expired.7z",
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
        assertEquals("aic-win", vm.uiState.value.downloadPage.pendingCaptureItemId)

        vm.onShortCodeCaptured("Oz4hM", "Alice.7z")
        assertNull(vm.uiState.value.downloadPage.pendingCaptureItemId)
        assertEquals(
            DownloadContentStatus.DOWNLOADED,
            vm.uiState.value.downloadPage.allItems.first().status
        )
    }
}
