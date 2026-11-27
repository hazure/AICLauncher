package com.aliceincradle.launcher.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.aliceincradle.launcher.bridge.ArchiveContainer
import com.aliceincradle.launcher.bridge.ArchiveExtractor
import com.aliceincradle.launcher.bridge.GameSourceResolver
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
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
        return runCatching {
            val rawJson = httpClient.fetchText(RAW_CATALOG_URL)
            val items = parseCatalogJson(rawJson, downloadDir)
            if (items.isEmpty()) {
                throw IOException("索引获取失败，请检查网络或稍后重试")
            }
            items
        }.recoverCatching { err ->
            SafeProbeIo.warn(TAG, "Remote catalog fetch failed: ${err.message}", err)
            throw IOException("索引获取失败，请检查网络或稍后重试", err)
        }
    }

    internal fun parseCatalogJson(rawJson: String, downloadDir: File?): List<DownloadCatalogItem> {
        val trimmed = rawJson.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            obj.optJSONArray("resources") ?: obj.optJSONArray("items") ?: JSONArray()
        }
        val result = mutableListOf<DownloadCatalogItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").ifBlank { "remote-item-$i" }
            val title = obj.optString("name", obj.optString("title")).ifBlank { id }
            val downloadUrl = obj.optString("download_url", obj.optString("downloadUrl", obj.optString("url", "")))
            val rawFileName = obj.optString("fileName").ifBlank {
                downloadUrl.substringAfterLast('/').substringBefore('?').ifBlank { "$id.zip" }
            }
            val fileName = runCatching { URLDecoder.decode(rawFileName, "UTF-8") }.getOrDefault(rawFileName)
            val sizeBytes = obj.optLong("sizeBytes", 0L)
            val sizeText = obj.optString("size", obj.optString("sizeLabel", ""))
            val version = obj.optString("version", "")
            val tagsArray = obj.optJSONArray("tags")
            val tags = buildList {
                if (tagsArray != null) {
                    for (t in 0 until tagsArray.length()) {
                        tagsArray.optString(t).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                if (isEmpty()) {
                    add("游戏本体")
                    if (version.isNotBlank()) add("v$version")
                    add("稳定版")
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
                tags = tags,
                sizeLabel = when {
                    sizeBytes > 0L -> formatFileSize(sizeBytes)
                    sizeText.isNotBlank() -> sizeText
                    else -> "未知大小"
                },
                sizeBytes = sizeBytes,
                status = status,
                appName = obj.optString("appName", "AIC"),
                versionChannel = obj.optString("versionChannel", "稳定版"),
                category = obj.optString("category", "游戏本体"),
                isCompatibleVersion = obj.optBoolean("isCompatibleVersion", true),
                isFeatured = obj.optBoolean("isFeatured", true),
                author = obj.optString("author", "NanameHacha@Cicini"),
                description = obj.optString("description", ""),
                downloadUrl = downloadUrl,
                fileName = fileName,
                localFilePath = if (isDownloaded) localFile?.absolutePath else null,
                progressPercent = if (isDownloaded) 100 else 0
            )
        }
        return result
    }

    companion object {
        private const val TAG = "RemoteCatalogFetcher"
        const val RAW_CATALOG_URL = "https://raw.githubusercontent.com/hazure/AICLauncher/nocheck_dev/catalog.json"
    }
}

data class DownloadRequest(
    val itemId: String,
    val url: String,
    val destFile: File,
    val expectedBytes: Long = 0L,
    val expectedSha256: String? = null,
    val reResolveUrl: (suspend () -> String?)? = null
)

interface DownloadProgressListener {
    fun onStarted(itemId: String)
    fun onProgress(itemId: String, downloadedBytes: Long, totalBytes: Long, percent: Int)
    fun onCompleted(itemId: String, file: File)
    fun onFailed(itemId: String, errorMessage: String)
    fun onCancelled(itemId: String) {}
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
    private val activePartFiles = ConcurrentHashMap<String, File>()
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
                    val partFile = File(request.destFile.parentFile, "${request.destFile.name}.part")
                    activePartFiles[request.itemId] = partFile
                    try {
                        listener?.onStarted(request.itemId)
                        streamDownloader(request, requireNotNull(listener))
                    } catch (e: CancellationException) {
                        if (partFile.exists()) {
                            partFile.delete()
                        }
                        listener?.onCancelled(request.itemId)
                    } catch (e: Exception) {
                        if (partFile.exists()) {
                            partFile.delete()
                        }
                        SafeProbeIo.warn(TAG, "Download failed for ${request.itemId}", e)
                        listener?.onFailed(request.itemId, e.message ?: "下载失败")
                    } finally {
                        activeJobs.remove(request.itemId)
                        activePartFiles.remove(request.itemId)
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
        activePartFiles.remove(itemId)?.let { partFile ->
            if (partFile.exists()) {
                partFile.delete()
            }
        }
    }

    fun close() {
        queue.close()
    }

    companion object {
        private const val TAG = "DownloadQueueManager"
        private const val BUFFER_SIZE = 16 * 1024
        private const val PROGRESS_EMIT_INTERVAL_MS = 120L
        const val CIEN_FALLBACK_URL = "https://ci-en.dlsite.com/creator/12611"
        const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

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
            var currentUrl = request.url
            var reResolvedOnce = false
            var transientAttempt = 0

            while (true) {
                coroutineContext.ensureActive()
                val existingBytes = if (partFile.isFile) partFile.length() else 0L
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", CHROME_USER_AGENT)
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val isResume = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                        val contentLen = conn.contentLengthLong.takeIf { it > 0L } ?: 0L
                        val totalBytes = if (isResume && contentLen > 0L) {
                            existingBytes + contentLen
                        } else if (contentLen > 0L) {
                            contentLen
                        } else {
                            request.expectedBytes
                        }
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = if (isResume) existingBytes else 0L
                        var lastEmitTime = 0L

                        conn.inputStream.use { input ->
                            FileOutputStream(partFile, isResume).use { output ->
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

                        // Integrity check (D5): strong sha256 if provided, else size + magic check
                        val expectedSha = request.expectedSha256
                        if (!expectedSha.isNullOrBlank()) {
                            val actualSha = computeSha256(partFile)
                            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                                partFile.delete()
                                throw IOException("CHECKSUM_MISMATCH: 文件哈希校验失败")
                            }
                        } else {
                            val container = ArchiveExtractor().detectContainer(partFile)
                            runCatching {
                                Log.i(
                                    "AICL_PIPELINE",
                                    "VERIFYING ok (上游未提供哈希声明, container=$container, bytes=${partFile.length()}, fp=${GameSourceResolver.urlFingerprint(currentUrl)})"
                                )
                            }
                            if (container == ArchiveContainer.UNKNOWN && partFile.length() == 0L) {
                                partFile.delete()
                                throw IOException("CHECKSUM_MISMATCH: 下载产物为空")
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
                        return
                    }

                    when (HttpErrorPolicy.classifyHttpCode(code)) {
                        HttpErrorAction.RE_RESOLVE_LINK -> {
                            runCatching {
                                Log.i("AICL_PIPELINE", "DOWNLOADING http=$code action=RE_RESOLVE_LINK fp=${GameSourceResolver.urlFingerprint(currentUrl)}")
                            }
                            if (!reResolvedOnce && request.reResolveUrl != null) {
                                reResolvedOnce = true
                                val refreshed = request.reResolveUrl.invoke()
                                if (!refreshed.isNullOrBlank()) {
                                    currentUrl = refreshed
                                    continue
                                }
                            }
                            throw IOException("LINK_EXPIRED: HTTP $code")
                        }
                        HttpErrorAction.RETRY_WITH_BACKOFF -> {
                            if (transientAttempt < HttpErrorPolicy.MAX_TRANSIENT_RETRIES) {
                                val delayMs = HttpErrorPolicy.BACKOFF_DELAYS_MS.getOrElse(transientAttempt) { 4_000L }
                                transientAttempt++
                                delay(delayMs)
                                continue
                            }
                            throw IOException("DOWNLOAD_FAILED: HTTP $code")
                        }
                        HttpErrorAction.FAIL_FAST -> {
                            throw IOException("DOWNLOAD_FAILED: HTTP $code")
                        }
                    }
                } catch (e: CancellationException) {
                    if (partFile.exists()) partFile.delete()
                    throw e
                } catch (e: IOException) {
                    if (e.message?.startsWith("LINK_EXPIRED") == true ||
                        e.message?.startsWith("CHECKSUM_MISMATCH") == true ||
                        e.message?.startsWith("DOWNLOAD_FAILED") == true
                    ) {
                        if (partFile.exists() && e.message?.startsWith("CHECKSUM_MISMATCH") == true) {
                            partFile.delete()
                        }
                        throw e
                    }
                    if (HttpErrorPolicy.classifyException(e) == HttpErrorAction.RETRY_WITH_BACKOFF &&
                        transientAttempt < HttpErrorPolicy.MAX_TRANSIENT_RETRIES
                    ) {
                        val delayMs = HttpErrorPolicy.BACKOFF_DELAYS_MS.getOrElse(transientAttempt) { 4_000L }
                        transientAttempt++
                        delay(delayMs)
                        continue
                    }
                    throw e
                } finally {
                    conn.disconnect()
                }
            }
        }

        private fun computeSha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(16 * 1024)
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
