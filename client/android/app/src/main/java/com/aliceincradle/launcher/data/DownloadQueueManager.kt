package com.aliceincradle.launcher.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

fun interface CatalogHttpClient {
    fun fetchText(url: String): String
}

class DefaultCatalogHttpClient : CatalogHttpClient {
    override fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json, application/json")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code from $url")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val USER_AGENT = "AICL-Android/0.1.0a"
    }
}

/**
 * Fetches real remote catalog items from public GitHub raw JSON or GitHub Releases API.
 * Never falls back to hardcoded mock items on network failure.
 */
open class RemoteCatalogFetcher(
    private val httpClient: CatalogHttpClient = DefaultCatalogHttpClient()
) {
    open fun fetchCatalog(downloadDir: File?): Result<List<DownloadCatalogItem>> {
        val errors = mutableListOf<String>()

        runCatching {
            val rawJson = httpClient.fetchText(RAW_CATALOG_URL)
            parseCatalogJson(rawJson, downloadDir)
        }.onSuccess { items ->
            if (items.isNotEmpty()) {
                return Result.success(items)
            }
        }.onFailure { err ->
            errors += "catalog.json: ${err.message}"
        }

        return runCatching {
            val releasesJson = httpClient.fetchText(GITHUB_RELEASES_URL)
            parseGitHubReleases(releasesJson, downloadDir)
        }.onFailure { err ->
            errors += "releases: ${err.message}"
            SafeProbeIo.warn(TAG, "Remote catalog fetch failed: ${errors.joinToString("; ")}", err)
        }
    }

    internal fun parseCatalogJson(rawJson: String, downloadDir: File?): List<DownloadCatalogItem> {
        val trimmed = rawJson.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("items") ?: JSONArray()
        }
        val result = mutableListOf<DownloadCatalogItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").ifBlank { "remote-item-$i" }
            val title = obj.optString("title").ifBlank { id }
            val downloadUrl = obj.optString("downloadUrl", obj.optString("url", ""))
            val fileName = obj.optString("fileName").ifBlank {
                downloadUrl.substringAfterLast('/').substringBefore('?').ifBlank { "$id.bin" }
            }
            val sizeBytes = obj.optLong("sizeBytes", 0L)
            val tagsArray = obj.optJSONArray("tags")
            val tags = buildList {
                if (tagsArray != null) {
                    for (t in 0 until tagsArray.length()) {
                        tagsArray.optString(t).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            val localFile = downloadDir?.let { File(it, fileName) }
            val isDownloaded = localFile != null && localFile.exists() && localFile.length() > 0L
            val status = when {
                isDownloaded -> DownloadContentStatus.DOWNLOADED
                downloadUrl.isBlank() -> DownloadContentStatus.DISABLED
                else -> DownloadContentStatus.AVAILABLE
            }
            result += DownloadCatalogItem(
                id = id,
                title = title,
                tags = tags.ifEmpty { listOf("远端资源") },
                sizeLabel = if (sizeBytes > 0L) formatFileSize(sizeBytes) else obj.optString("sizeLabel", "未知大小"),
                sizeBytes = sizeBytes,
                status = status,
                appName = obj.optString("appName", "AIC"),
                versionChannel = obj.optString("versionChannel", "稳定版"),
                category = obj.optString("category", "游戏本体"),
                isCompatibleVersion = obj.optBoolean("isCompatibleVersion", true),
                isFeatured = obj.optBoolean("isFeatured", false),
                author = obj.optString("author", "hazure"),
                description = obj.optString("description", ""),
                downloadUrl = downloadUrl,
                fileName = fileName,
                localFilePath = if (isDownloaded) localFile?.absolutePath else null,
                progressPercent = if (isDownloaded) 100 else 0
            )
        }
        return result
    }

    internal fun parseGitHubReleases(rawJson: String, downloadDir: File?): List<DownloadCatalogItem> {
        val releases = JSONArray(rawJson.trim())
        val items = mutableListOf<DownloadCatalogItem>()
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            val tagName = rel.optString("tag_name", "release-$i")
            val relName = rel.optString("name").ifBlank { tagName }
            val isPrerelease = rel.optBoolean("prerelease", false)
            val channel = if (isPrerelease) "测试版" else "稳定版"
            val author = rel.optJSONObject("author")?.optString("login").orEmpty().ifBlank { "hazure" }
            val body = rel.optString("body", "").take(300)
            val assets = rel.optJSONArray("assets")

            if (assets != null && assets.length() > 0) {
                for (a in 0 until assets.length()) {
                    val asset = assets.optJSONObject(a) ?: continue
                    val assetName = asset.optString("name").ifBlank { "$tagName-asset-$a" }
                    val url = asset.optString("browser_download_url", "")
                    val sizeBytes = asset.optLong("size", 0L)
                    val localFile = downloadDir?.let { File(it, assetName) }
                    val isDownloaded = localFile != null && localFile.exists() && localFile.length() > 0L
                    items += DownloadCatalogItem(
                        id = "gh-${rel.optLong("id", i.toLong())}-$a",
                        title = "$relName · $assetName",
                        tags = listOf(channel, tagName, "GitHub Release"),
                        sizeLabel = if (sizeBytes > 0L) formatFileSize(sizeBytes) else "未知大小",
                        sizeBytes = sizeBytes,
                        status = when {
                            isDownloaded -> DownloadContentStatus.DOWNLOADED
                            url.isBlank() -> DownloadContentStatus.DISABLED
                            else -> DownloadContentStatus.AVAILABLE
                        },
                        appName = "AIC",
                        versionChannel = channel,
                        category = if (assetName.endsWith(".apk", true)) "游戏本体" else "武器强化",
                        isCompatibleVersion = true,
                        isFeatured = i == 0,
                        author = author,
                        description = body,
                        downloadUrl = url,
                        fileName = assetName,
                        localFilePath = if (isDownloaded) localFile?.absolutePath else null,
                        progressPercent = if (isDownloaded) 100 else 0
                    )
                }
            } else {
                val zipUrl = rel.optString("zipball_url", "")
                val fileName = "AICLauncher-${tagName.replace('/', '_')}.zip"
                val localFile = downloadDir?.let { File(it, fileName) }
                val isDownloaded = localFile != null && localFile.exists() && localFile.length() > 0L
                items += DownloadCatalogItem(
                    id = "gh-src-${rel.optLong("id", i.toLong())}",
                    title = "$relName (Source Archive)",
                    tags = listOf(channel, tagName, "源码归档"),
                    sizeLabel = "动态大小",
                    sizeBytes = 0L,
                    status = when {
                        isDownloaded -> DownloadContentStatus.DOWNLOADED
                        zipUrl.isBlank() -> DownloadContentStatus.DISABLED
                        else -> DownloadContentStatus.AVAILABLE
                    },
                    appName = "AIC",
                    versionChannel = channel,
                    category = "游戏本体",
                    isCompatibleVersion = true,
                    isFeatured = i == 0,
                    author = author,
                    description = body,
                    downloadUrl = zipUrl,
                    fileName = fileName,
                    localFilePath = if (isDownloaded) localFile?.absolutePath else null,
                    progressPercent = if (isDownloaded) 100 else 0
                )
            }
        }
        return items
    }

    companion object {
        private const val TAG = "RemoteCatalogFetcher"
        const val RAW_CATALOG_URL = "https://raw.githubusercontent.com/hazure/AICLauncher/main/catalog.json"
        const val GITHUB_RELEASES_URL = "https://api.github.com/repos/hazure/AICLauncher/releases"
    }
}

data class DownloadRequest(
    val itemId: String,
    val url: String,
    val destFile: File,
    val expectedBytes: Long = 0L
)

interface DownloadProgressListener {
    fun onStarted(itemId: String)
    fun onProgress(itemId: String, downloadedBytes: Long, totalBytes: Long, percent: Int)
    fun onCompleted(itemId: String, file: File)
    fun onFailed(itemId: String, errorMessage: String)
}

/**
 * Concurrent download queue backed by Kotlin Coroutine [Channel] and [HttpURLConnection].
 */
class DownloadQueueManager(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    workerCount: Int = 2,
    private val streamDownloader: suspend (DownloadRequest, DownloadProgressListener) -> Unit = { req, listener ->
        downloadViaHttpUrlConnection(req, listener)
    }
) {
    private val queue = Channel<DownloadRequest>(Channel.UNLIMITED)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    @Volatile
    private var listener: DownloadProgressListener? = null

    init {
        repeat(workerCount.coerceAtLeast(1)) {
            scope.launch(ioDispatcher) {
                for (request in queue) {
                    val job = coroutineContext[Job]
                    if (job != null) {
                        activeJobs[request.itemId] = job
                    }
                    try {
                        listener?.onStarted(request.itemId)
                        streamDownloader(request, requireNotNull(listener))
                    } catch (e: Exception) {
                        SafeProbeIo.warn(TAG, "Download failed for ${request.itemId}", e)
                        listener?.onFailed(request.itemId, e.message ?: "下载失败")
                    } finally {
                        activeJobs.remove(request.itemId)
                    }
                }
            }
        }
    }

    fun setListener(progressListener: DownloadProgressListener) {
        this.listener = progressListener
    }

    fun enqueue(request: DownloadRequest) {
        queue.trySend(request)
    }

    fun cancel(itemId: String) {
        activeJobs[itemId]?.cancel()
    }

    fun close() {
        queue.close()
    }

    companion object {
        private const val TAG = "DownloadQueueManager"
        private const val BUFFER_SIZE = 16 * 1024
        private const val PROGRESS_EMIT_INTERVAL_MS = 120L

        fun resolveDownloadDirectory(context: Context?): File {
            if (context != null) {
                val extDl = runCatching { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
                if (extDl != null && (extDl.exists() || extDl.mkdirs())) {
                    return extDl
                }
                return File(context.filesDir, "downloads").apply { mkdirs() }
            }
            return File("downloads").apply { mkdirs() }
        }

        private suspend fun downloadViaHttpUrlConnection(
            request: DownloadRequest,
            listener: DownloadProgressListener
        ) {
            val parent = request.destFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("无法创建下载目录：${parent.absolutePath}")
            }
            val partFile = File(parent, "${request.destFile.name}.part")
            val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AICL-Android/0.1.0a")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }
                val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: request.expectedBytes
                val buffer = ByteArray(BUFFER_SIZE)
                var downloaded = 0L
                var lastEmitTime = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= PROGRESS_EMIT_INTERVAL_MS) {
                                lastEmitTime = now
                                val percent = if (totalBytes > 0L) {
                                    ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                } else {
                                    0
                                }
                                listener.onProgress(request.itemId, downloaded, totalBytes, percent)
                            }
                        }
                    }
                }

                if (request.destFile.exists()) {
                    request.destFile.delete()
                }
                if (!partFile.renameTo(request.destFile)) {
                    partFile.copyTo(request.destFile, overwrite = true)
                    partFile.delete()
                }
                listener.onProgress(request.itemId, downloaded, totalBytes.coerceAtLeast(downloaded), 100)
                listener.onCompleted(request.itemId, request.destFile)
            } catch (e: Exception) {
                if (partFile.exists()) {
                    partFile.delete()
                }
                throw e
            } finally {
                conn.disconnect()
            }
        }
    }
}
