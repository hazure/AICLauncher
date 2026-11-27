package com.aliceincradle.launcher.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aliceincradle.launcher.data.SafeProbeIo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class SourceKind {
    DIRECT_API,
    WEBVIEW_FALLBACK,
    LEGACY_CATALOG,
    SHORTCODE_REFRESH,
    WEBVIEW_CAPTURE,
    MANUAL_SHORTCODE
}

/**
 * Transient resolved game source. [url] is marked @Transient and excluded from [toString]
 * and [toMetadataJson] so signed URLs are never persisted or logged in plaintext.
 */
data class GameSource(
    @Transient val url: String,
    val version: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val expireAt: Long? = null,
    val sourceKind: SourceKind,
    val fileName: String = "AliceInCradle_Win_Latest.7z",
    val shortCode: String? = null
) {
    override fun toString(): String {
        return "GameSource(version=$version, sizeBytes=$sizeBytes, sha256=${sha256 != null}, sourceKind=$sourceKind, fileName=$fileName)"
    }

    fun toMetadataJson(): String {
        val ver = version?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
        return """{"version":$ver,"sizeBytes":${sizeBytes ?: "null"},"sourceKind":"${sourceKind.name}","fileName":"${fileName.replace("\"", "\\\"")}"}"""
    }
}

private data class CachedMetadata(
    val version: String?,
    val sizeBytes: Long?,
    val cachedAtMs: Long
)

/**
 * Runtime official game source resolver (Decision D1 & D2).
 * Attempts direct official endpoint resolution first, then falls back to an official-domain-only
 * headless WebView (20s timeout, destroyed immediately after use).
 */
data class CachedShortCode(
    val shortCode: String,
    val fileName: String,
    val version: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Persists only non-signed shortCode + fileName + version in app-private storage.
 * Signed URLs are strictly excluded.
 */
class ShortCodeCacheStore(private val storageFile: File) {
    fun save(entry: CachedShortCode): Result<Unit> {
        return try {
            storageFile.parentFile?.mkdirs()
            val props = Properties().apply {
                setProperty("shortCode", entry.shortCode)
                setProperty("fileName", entry.fileName)
                entry.version?.let { setProperty("version", it) }
                setProperty("updatedAtMs", entry.updatedAtMs.toString())
            }
            val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            FileOutputStream(tmp).use { out ->
                props.store(out, "AICL ShortCode Cache (Non-Signed)")
            }
            if (storageFile.exists()) storageFile.delete()
            if (!tmp.renameTo(storageFile)) {
                tmp.copyTo(storageFile, overwrite = true)
                tmp.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            SafeProbeIo.warn("AICL_PIPELINE", "Failed saving shortCode cache: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun load(): CachedShortCode? {
        if (!storageFile.isFile) return null
        return try {
            val props = Properties()
            FileInputStream(storageFile).use { props.load(it) }
            val code = props.getProperty("shortCode")?.trim().orEmpty()
            val name = props.getProperty("fileName")?.trim().orEmpty()
            if (code.isEmpty() || name.isEmpty()) return null
            CachedShortCode(
                shortCode = code,
                fileName = name,
                version = props.getProperty("version")?.takeIf { it.isNotBlank() },
                updatedAtMs = props.getProperty("updatedAtMs")?.toLongOrNull() ?: 0L
            )
        } catch (e: Exception) {
            SafeProbeIo.warn("AICL_PIPELINE", "Failed loading shortCode cache: ${e.message}", e)
            null
        }
    }

    fun clear() {
        runCatching { storageFile.delete() }
    }
}

fun interface ShortLinkRedirectFetcher {
    suspend fun fetchRedirectLocation(shortLinkUrl: String): Result<String>
}

class GameSourceResolver(
    private val directFetcher: suspend () -> GameSource? = { fetchDirectFromOfficial() },
    private val webViewFetcher: suspend (Context) -> GameSource? = { ctx -> fetchViaOfficialWebView(ctx) },
    private val shortLinkRedirectFetcher: ShortLinkRedirectFetcher = ShortLinkRedirectFetcher { shortUrl ->
        fetchShortLinkRedirect(shortUrl)
    },
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var metadataCache: CachedMetadata? = null

    fun getCachedMetadata(): Pair<String?, Long?>? {
        val cached = metadataCache ?: return null
        if (clockMs() - cached.cachedAtMs > METADATA_TTL_MS) {
            metadataCache = null
            return null
        }
        return cached.version to cached.sizeBytes
    }

    suspend fun resolveLatestWinTrial(context: Context? = null): GameSource? {
        val startMs = clockMs()
        logPipeline("RESOLVING_SOURCE start")

        val direct = try {
            directFetcher()
        } catch (e: Exception) {
            SafeProbeIo.warn(TAG, "Direct source resolution failed: ${e.message}", e)
            null
        }
        if (direct != null && direct.url.isNotBlank()) {
            cacheMetadata(direct)
            logPipeline("RESOLVING_SOURCE ok kind=${direct.sourceKind} fp=${urlFingerprint(direct.url)} elapsedMs=${clockMs() - startMs}")
            return direct
        }

        if (context != null) {
            val webViewResult = try {
                webViewFetcher(context)
            } catch (e: Exception) {
                SafeProbeIo.warn(TAG, "WebView fallback resolution failed: ${e.message}", e)
                null
            }
            if (webViewResult != null && webViewResult.url.isNotBlank()) {
                cacheMetadata(webViewResult)
                logPipeline("RESOLVING_SOURCE ok kind=${webViewResult.sourceKind} fp=${urlFingerprint(webViewResult.url)} elapsedMs=${clockMs() - startMs}")
                return webViewResult
            }
        }

        logPipeline("RESOLVING_SOURCE failed reason=SOURCE_UNAVAILABLE elapsedMs=${clockMs() - startMs}")
        return null
    }

    private fun cacheMetadata(source: GameSource) {
        metadataCache = CachedMetadata(
            version = source.version,
            sizeBytes = source.sizeBytes,
            cachedAtMs = clockMs()
        )
    }

    suspend fun resolveFromShortCode(
        shortCode: String,
        fileName: String,
        version: String? = null,
        sourceKind: SourceKind = SourceKind.SHORTCODE_REFRESH
    ): Result<GameSource> {
        val startMs = clockMs()
        val shortUrl = buildShortLinkUrl(shortCode, fileName)
        val locRes = shortLinkRedirectFetcher.fetchRedirectLocation(shortUrl)
        return locRes.map { signedUrl ->
            val fp = urlFingerprint(signedUrl)
            logPipeline("RESOLVING_LINK ok sourceKind=$sourceKind fp=$fp elapsedMs=${clockMs() - startMs}")
            GameSource(
                url = signedUrl,
                version = version,
                sourceKind = sourceKind,
                fileName = fileName,
                shortCode = shortCode
            )
        }.onFailure { err ->
            logPipeline("RESOLVING_LINK failed sourceKind=$sourceKind reason=${err.message} elapsedMs=${clockMs() - startMs}")
        }
    }

    companion object {
        private const val TAG = "AICL_PIPELINE"
        const val OFFICIAL_MIRROR_BASE = "https://get.aliceincradle.dev"
        const val OFFICIAL_WIN_URL = "$OFFICIAL_MIRROR_BASE/win/"
        const val SHORTLINK_BASE = "https://vanilla.shiro.dev/f/"
        const val OFFICIAL_LICENSE_URL = "https://docs.nanamehacha.dev/en/aliceincradle/license"
        val ALLOWED_OFFICIAL_HOSTS: Set<String> = setOf(
            "get.aliceincradle.dev",
            "api.shiro.dev",
            "vanilla.shiro.dev",
            "dl.aliceincradle.dev"
        )
        const val METADATA_TTL_MS = 10L * 60L * 1000L
        const val WEBVIEW_TIMEOUT_MS = 20_000L
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private val ARCHIVE_REGEX = Regex(
            """https?://[^\s"'<>\\]+?\.(?:7z|zip)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )

        fun buildShortLinkUrl(shortCode: String, fileName: String): String {
            val encCode = URLEncoder.encode(shortCode.trim(), "UTF-8").replace("+", "%20")
            val encFile = URLEncoder.encode(fileName.trim(), "UTF-8").replace("+", "%20")
            return "$SHORTLINK_BASE$encCode/$encFile"
        }

        fun parseShortLinkUrl(url: String): Pair<String, String>? {
            return runCatching {
                val parsed = URL(url)
                if (!parsed.host.equals("vanilla.shiro.dev", ignoreCase = true)) return null
                val segs = parsed.path.split('/').filter { it.isNotBlank() }
                val idx = segs.indexOf("f")
                if (idx < 0 || idx + 2 >= segs.size) return null
                val code = URLDecoder.decode(segs[idx + 1], "UTF-8")
                val file = URLDecoder.decode(segs.last(), "UTF-8")
                if (code.isBlank() || file.isBlank()) null else (code to file)
            }.getOrNull()
        }

        private suspend fun fetchShortLinkRedirect(shortLinkUrl: String): Result<String> = withContext(Dispatchers.IO) {
            val conn = (URL(shortLinkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", CHROME_UA)
            }
            try {
                val code = conn.responseCode
                if (code == 404) {
                    return@withContext Result.failure(IOException("LINK_INVALID: HTTP 404"))
                }
                if (code !in listOf(301, 302, 303, 307, 308)) {
                    return@withContext Result.failure(IOException("LINK_UNRESOLVABLE: HTTP $code"))
                }
                val loc = conn.getHeaderField("Location").orEmpty()
                if (loc.isBlank() || !loc.contains("x-oss-signature", ignoreCase = true)) {
                    return@withContext Result.failure(IOException("LINK_UNRESOLVABLE: 302 Location 无签名参数"))
                }
                Result.success(loc)
            } catch (e: IOException) {
                Result.failure(e)
            } finally {
                conn.disconnect()
            }
        }

        fun urlFingerprint(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray(Charsets.UTF_8))
                .take(6)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun logPipeline(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private suspend fun fetchDirectFromOfficial(): GameSource? = withContext(Dispatchers.IO) {
            val conn = (URL(OFFICIAL_WIN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CHROME_UA)
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val match = ARCHIVE_REGEX.find(html)?.value ?: return@withContext null
                val fileName = match.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "AliceInCradle_Win_Latest.7z" }
                GameSource(
                    url = match,
                    version = null,
                    sizeBytes = null,
                    sha256 = null,
                    sourceKind = SourceKind.DIRECT_API,
                    fileName = fileName
                )
            } finally {
                conn.disconnect()
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private suspend fun fetchViaOfficialWebView(context: Context): GameSource? {
            return withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
                suspendCancellableCoroutine<GameSource?> { cont ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    val completed = AtomicBoolean(false)
                    var webViewRef: WebView? = null
                    val runnables = mutableListOf<Runnable>()

                    fun destroyOnMain() {
                        mainHandler.post {
                            runnables.forEach { mainHandler.removeCallbacks(it) }
                            runnables.clear()
                            runCatching {
                                webViewRef?.apply {
                                    stopLoading()
                                    setDownloadListener(null)
                                    destroy()
                                }
                            }
                            webViewRef = null
                        }
                    }

                    fun finish(result: GameSource?) {
                        if (completed.compareAndSet(false, true)) {
                            destroyOnMain()
                            if (cont.isActive) cont.resume(result)
                        }
                    }

                    cont.invokeOnCancellation {
                        completed.set(true)
                        destroyOnMain()
                    }

                    mainHandler.post {
                        try {
                            val wv = WebView(context.applicationContext)
                            webViewRef = wv
                            wv.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                userAgentString = CHROME_UA
                                loadsImagesAutomatically = false
                                blockNetworkImage = true
                            }
                            wv.setDownloadListener { url, _, _, _, _ ->
                                if (!url.isNullOrBlank()) {
                                    val name = url.substringAfterLast('/').substringBefore('?')
                                        .ifBlank { "AliceInCradle_Win_Latest.7z" }
                                    finish(GameSource(url = url, sourceKind = SourceKind.WEBVIEW_FALLBACK, fileName = name))
                                }
                            }
                            wv.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val u = request?.url?.toString().orEmpty()
                                    val clean = u.substringBefore('?').lowercase()
                                    if (clean.endsWith(".7z") || clean.endsWith(".zip")) {
                                        val name = u.substringAfterLast('/').substringBefore('?')
                                        finish(GameSource(url = u, sourceKind = SourceKind.WEBVIEW_FALLBACK, fileName = name))
                                        return true
                                    }
                                    val host = request?.url?.host?.lowercase().orEmpty()
                                    return host.isNotEmpty() && host !in ALLOWED_OFFICIAL_HOSTS
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val u = request?.url?.toString().orEmpty()
                                    val clean = u.substringBefore('?').lowercase()
                                    if (clean.endsWith(".7z") || clean.endsWith(".zip")) {
                                        val name = u.substringAfterLast('/').substringBefore('?')
                                        finish(GameSource(url = u, sourceKind = SourceKind.WEBVIEW_FALLBACK, fileName = name))
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    listOf(1_200L, 3_000L, 6_000L).forEach { delayMs ->
                                        val r = Runnable {
                                            if (completed.get()) return@Runnable
                                            val js = """
                                                (function() {
                                                    if (window.__aiclClicked) return;
                                                    var btn = document.querySelector('button[data-action="downloadWin"]');
                                                    if (btn) { window.__aiclClicked = true; btn.click(); }
                                                })();
                                            """.trimIndent()
                                            runCatching { wv.evaluateJavascript(js, null) }
                                        }
                                        runnables += r
                                        mainHandler.postDelayed(r, delayMs)
                                    }
                                }
                            }
                            wv.loadUrl(OFFICIAL_WIN_URL)
                        } catch (e: Exception) {
                            SafeProbeIo.warn(TAG, "WebView initialization failed: ${e.message}", e)
                            finish(null)
                        }
                    }
                }
            }
        }
    }
}
