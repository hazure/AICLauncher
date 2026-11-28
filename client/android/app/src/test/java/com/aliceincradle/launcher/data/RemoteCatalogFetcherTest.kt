package com.aliceincradle.launcher.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCatalogFetcherTest {
    private val sampleJson = """[{"id":"aic_win","name":"Alice In Cradle"}]"""

    private fun newTestFetcher(
        httpClient: CatalogHttpClient,
        cacheFile: File
    ): RemoteCatalogFetcher {
        return object : RemoteCatalogFetcher(
            httpClient = httpClient,
            cacheFileProvider = { cacheFile }
        ) {
            override fun parseCatalogJson(rawJson: String, downloadDir: File?): List<DownloadCatalogItem> {
                if (!rawJson.contains("aic_win")) return emptyList()
                return listOf(
                    DownloadCatalogItem(
                        id = "aic_win",
                        title = "Alice In Cradle",
                        tags = listOf("游戏本体"),
                        sizeLabel = "50 MB",
                        status = DownloadContentStatus.AVAILABLE
                    )
                )
            }
        }
    }

    @Test
    fun fetchCatalog_fallsBackToMirrorAndPersistsLocalCache() {
        val tempDir = Files.createTempDirectory("aicl_catalog_test").toFile()
        try {
            val cacheFile = File(tempDir, "catalog_cache.json")
            val fetcher = newTestFetcher(
                httpClient = CatalogHttpClient { url ->
                    if (url == RemoteCatalogFetcher.RAW_CATALOG_URL) {
                        throw IOException("primary timeout")
                    }
                    sampleJson
                },
                cacheFile = cacheFile
            )
            val res = fetcher.fetchCatalog(null)
            assertTrue(res.isSuccess)
            assertEquals(1, res.getOrThrow().size)
            assertTrue(cacheFile.isFile && cacheFile.length() > 0L)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fetchCatalog_usesLocalCacheWhenAllRemotesFail_andErrorsClearlyWithoutCache() {
        val tempDir = Files.createTempDirectory("aicl_catalog_cache_test").toFile()
        try {
            val cacheFile = File(tempDir, "catalog_cache.json")
            cacheFile.writeText(sampleJson, Charsets.UTF_8)

            val cachedFetcher = newTestFetcher(
                httpClient = CatalogHttpClient { throw IOException("offline") },
                cacheFile = cacheFile
            )
            val cachedRes = cachedFetcher.fetchCatalog(null)
            assertTrue(cachedRes.isSuccess)
            assertEquals("aic_win", cachedRes.getOrThrow().first().id)

            val missingCacheFetcher = newTestFetcher(
                httpClient = CatalogHttpClient { throw IOException("offline") },
                cacheFile = File(tempDir, "missing.json")
            )
            val failRes = missingCacheFetcher.fetchCatalog(null)
            assertTrue(failRes.isFailure)
            assertTrue(failRes.exceptionOrNull()?.message.orEmpty().contains("索引获取失败"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
