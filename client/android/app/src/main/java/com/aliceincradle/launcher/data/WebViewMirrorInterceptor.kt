package com.aliceincradle.launcher.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

fun interface MirrorWebViewInterceptor {
    suspend fun intercept(
        context: Context,
        pageUrl: String,
        timeoutMs: Long
    ): Result<SniffedDownloadLink>
}

/**
 * Headless WebView network interceptor (Plan B) that executes the SPA's obfuscated JS
 * and intercepts the real `.zip` / `.7z` download request on the Main looper.
 */
class AndroidWebViewMirrorInterceptor : MirrorWebViewInterceptor {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun intercept(
        context: Context,
        pageUrl: String,
        timeoutMs: Long
    ): Result<SniffedDownloadLink> {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<SniffedDownloadLink>> { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                val completed = AtomicBoolean(false)
                var webViewRef: WebView? = null
                val scheduledRunnables = mutableListOf<Runnable>()

                fun destroyWebViewOnMain() {
                    mainHandler.post {
                        scheduledRunnables.forEach { mainHandler.removeCallbacks(it) }
                        scheduledRunnables.clear()
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

                fun completeOnce(res: Result<SniffedDownloadLink>) {
                    if (completed.compareAndSet(false, true)) {
                        destroyWebViewOnMain()
                        if (cont.isActive) {
                            cont.resume(res)
                        }
                    }
                }

                cont.invokeOnCancellation {
                    completed.set(true)
                    destroyWebViewOnMain()
                }

                mainHandler.post {
                    try {
                        val webView = WebView(context.applicationContext)
                        webViewRef = webView
                        webView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = MirrorLinkSniffer.CHROME_USER_AGENT
                            loadsImagesAutomatically = false
                            blockNetworkImage = true
                        }

                        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
                            if (!url.isNullOrBlank()) {
                                Log.d(TAG, "WebView intercepted URL -> $url")
                                val fileName = resolveFileName(url, contentDisposition)
                                completeOnce(
                                    Result.success(
                                        SniffedDownloadLink(
                                            directUrl = url,
                                            fileName = fileName
                                        )
                                    )
                                )
                            }
                        }

                        webView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString().orEmpty()
                                if (isArchiveUrl(url)) {
                                    Log.d(TAG, "WebView intercepted URL -> $url")
                                    completeOnce(
                                        Result.success(
                                            SniffedDownloadLink(
                                                directUrl = url,
                                                fileName = resolveFileName(url, null)
                                            )
                                        )
                                    )
                                    return true
                                }
                                return false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString().orEmpty()
                                if (isArchiveUrl(url)) {
                                    Log.d(TAG, "WebView intercepted URL -> $url")
                                    completeOnce(
                                        Result.success(
                                            SniffedDownloadLink(
                                                directUrl = url,
                                                fileName = resolveFileName(url, null)
                                            )
                                        )
                                    )
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val triggerDelays = listOf(1_200L, 2_500L, 4_500L, 7_000L)
                                triggerDelays.forEachIndexed { idx, delayMs ->
                                    val forceClick = idx >= 1
                                    val r = Runnable {
                                        if (completed.get()) return@Runnable
                                        val js = """
                                            (function() {
                                                if (window.__aiclDownloadTriggered) return 'already';
                                                var preloader = document.getElementById('preloader');
                                                var ready = !preloader || preloader.style.display === 'none' || preloader.classList.contains('hidden');
                                                if (!ready && !$forceClick) return 'waiting';
                                                var btn = document.querySelector('button[data-action="downloadWin"]');
                                                if (btn) {
                                                    window.__aiclDownloadTriggered = true;
                                                    btn.click();
                                                    return 'clicked';
                                                }
                                                return 'no-btn';
                                            })();
                                        """.trimIndent()
                                        runCatching { webView.evaluateJavascript(js, null) }
                                    }
                                    scheduledRunnables += r
                                    mainHandler.postDelayed(r, delayMs)
                                }
                            }
                        }

                        webView.loadUrl(pageUrl)
                    } catch (e: Exception) {
                        SafeProbeIo.warn(TAG, "WebView mirror interception failed", e)
                        completeOnce(Result.failure(e))
                    }
                }
            }
        }
        return result ?: Result.failure(IOException("WebView 拦截镜像站下载链接超时"))
    }

    private fun isArchiveUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".zip") || clean.endsWith(".7z")
    }

    private fun resolveFileName(url: String, contentDisposition: String?): String {
        if (!contentDisposition.isNullOrBlank()) {
            val match = FILENAME_REGEX.find(contentDisposition)
            val candidate = match?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')
            if (!candidate.isNullOrBlank()) {
                return candidate
            }
        }
        val fromUrl = url.substringAfterLast('/').substringBefore('?')
        return if (fromUrl.endsWith(".zip", true) || fromUrl.endsWith(".7z", true)) {
            fromUrl
        } else {
            "AliceInCradle_Win_Latest.zip"
        }
    }

    companion object {
        private const val TAG = "MirrorLinkSniffer"
        private val FILENAME_REGEX = Regex("""filename\*?=(?:UTF-8'')?([^;\r\n]+)""", RegexOption.IGNORE_CASE)
    }
}
