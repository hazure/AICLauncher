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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class SourceKind {
    DIRECT_API,
    WEBVIEW_FALLBACK,
    LEGACY_CATALOG
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
    val fileName: String = "AliceInCradle_Win_Latest.7z"
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
class GameSourceResolver(
    private val directFetcher: suspend () -> GameSource? = { fetchDirectFromOfficial() },
    private val webViewFetcher: suspend (Context) -> GameSource? = { ctx -> fetchViaOfficialWebView(ctx) },
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

    companion object {
        private const val TAG = "AICL_PIPELINE"
        const val OFFICIAL_MIRROR_BASE = "https://get.aliceincradle.dev"
        const val OFFICIAL_WIN_URL = "$OFFICIAL_MIRROR_BASE/win/"
        const val OFFICIAL_LICENSE_URL = "https://docs.nanamehacha.dev/en/aliceincradle/license"
        val ALLOWED_OFFICIAL_HOSTS: Set<String> = setOf("get.aliceincradle.dev", "api.shiro.dev")
        const val METADATA_TTL_MS = 10L * 60L * 1000L
        const val WEBVIEW_TIMEOUT_MS = 20_000L
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private val ARCHIVE_REGEX = Regex(
            """https?://[^\s"'<>\\]+?\.(?:7z|zip)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )

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
