package com.aliceincradle.launcher.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aliceincradle.launcher.bridge.GameSourceResolver
import com.aliceincradle.launcher.data.WebViewDownloadTask
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val CAPTURE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShortCodeCaptureDialog(
    onCaptured: (shortCode: String, fileName: String) -> Unit,
    onFallbackManual: () -> Unit,
    onDismiss: () -> Unit
) {
    var statusText by remember { mutableStateOf("正在加载官方下载页（请完成年龄确认）...") }
    val completed = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                statusText = "捕获超时（20s），请改用手动填入短码"
            }
        }
        mainHandler.postDelayed(timeoutRunnable, GameSourceResolver.WEBVIEW_TIMEOUT_MS)
        onDispose {
            mainHandler.removeCallbacks(timeoutRunnable)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "官方下载页短码捕获") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            CookieManager.getInstance().setAcceptCookie(true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = CAPTURE_CHROME_UA
                            webViewClient = object : WebViewClient() {
                                private fun tryCapture(url: String): Boolean {
                                    val parsed = GameSourceResolver.parseShortLinkUrl(url) ?: return false
                                    if (completed.compareAndSet(false, true)) {
                                        runCatching {
                                            Log.i("AICL_PIPELINE", "phase=SHORTCODE_CAPTURED shortCode=${parsed.first}")
                                        }
                                        mainHandler.post { onCaptured(parsed.first, parsed.second) }
                                    }
                                    return true
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val u = request?.url?.toString().orEmpty()
                                    if (tryCapture(u)) return true
                                    val host = request?.url?.host?.lowercase().orEmpty()
                                    return host.isNotEmpty() && host !in GameSourceResolver.ALLOWED_OFFICIAL_HOSTS
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val u = request?.url?.toString().orEmpty()
                                    if (tryCapture(u)) {
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val js = """
                                        (function() {
                                            var pre = document.getElementById('preloader');
                                            if (pre && pre.style.display !== 'none') return;
                                            var btns = document.querySelectorAll('button, a');
                                            for (var i = 0; i < btns.length; i++) {
                                                var t = (btns[i].innerText || '').trim();
                                                if (t === 'Agree' || btns[i].getAttribute('data-action') === 'downloadWin') {
                                                    btns[i].click();
                                                }
                                            }
                                        })();
                                    """.trimIndent()
                                    listOf(800L, 2_000L, 4_000L).forEach { delayMs ->
                                        mainHandler.postDelayed({
                                            if (!completed.get()) {
                                                runCatching { view?.evaluateJavascript(js, null) }
                                            }
                                        }, delayMs)
                                    }
                                }
                            }
                            webViewRef = this
                            loadUrl(GameSourceResolver.OFFICIAL_WIN_URL)
                        }
                    }
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onFallbackManual) {
                Text(text = "手动填入短码")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewDownloadDialog(
    task: WebViewDownloadTask,
    onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) -> Unit,
    onPartFileReady: (File) -> Unit,
    onWebViewUnavailable: (String) -> Unit,
    onFailed: (String) -> Unit,
    onCancel: () -> Unit
) {
    var statusText by remember { mutableStateOf("正在通过应用内浏览器接管下载（fp=${task.urlFp}）...") }
    var progressPercent by remember { mutableStateOf(0) }
    val startedDownload = remember { AtomicBoolean(false) }
    val cancelled = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(task.itemId, task.urlFp) {
        runCatching {
            Log.i("AICL_PIPELINE", "phase=DOWNLOAD_START itemId=${task.itemId} sourceKind=WEBVIEW_MAIN fp=${task.urlFp}")
        }
        onDispose {
            cancelled.set(true)
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

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "正在下载游戏本体") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { (progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    factory = { ctx ->
                        try {
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = CAPTURE_CHROME_UA
                                webViewClient = WebViewClient()
                                setDownloadListener { dlUrl, userAgent, _, _, contentLength ->
                                    if (!startedDownload.compareAndSet(false, true)) return@setDownloadListener
                                    val targetUrl = dlUrl?.takeIf { it.isNotBlank() } ?: task.signedUrl
                                    val cookies = CookieManager.getInstance().getCookie(targetUrl).orEmpty()
                                    val ua = userAgent?.takeIf { it.isNotBlank() } ?: CAPTURE_CHROME_UA
                                    mainHandler.post { statusText = "已接管下载流，正在写入缓存..." }
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val destFile = File(task.destFilePath)
                                        val partFile = File(destFile.parentFile, "${destFile.name}.part")
                                        partFile.parentFile?.mkdirs()
                                        try {
                                            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                                                requestMethod = "GET"
                                                connectTimeout = 15_000
                                                readTimeout = 25_000
                                                instanceFollowRedirects = true
                                                setRequestProperty("User-Agent", ua)
                                                if (cookies.isNotBlank()) {
                                                    setRequestProperty("Cookie", cookies)
                                                }
                                            }
                                            try {
                                                val code = conn.responseCode
                                                if (code !in 200..299) {
                                                    throw java.io.IOException("HTTP $code")
                                                }
                                                val total = conn.contentLengthLong.takeIf { it > 0L }
                                                    ?: contentLength.takeIf { it > 0L }
                                                    ?: task.expectedBytes
                                                val buf = ByteArray(16 * 1024)
                                                var downloaded = 0L
                                                var lastEmit = 0L
                                                conn.inputStream.use { input ->
                                                    FileOutputStream(partFile).use { output ->
                                                        while (!cancelled.get()) {
                                                            val read = input.read(buf)
                                                            if (read <= 0) break
                                                            output.write(buf, 0, read)
                                                            downloaded += read
                                                            val now = System.currentTimeMillis()
                                                            if (now - lastEmit >= 150L) {
                                                                lastEmit = now
                                                                val pct = if (total > 0L) {
                                                                    ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
                                                                } else 0
                                                                mainHandler.post {
                                                                    progressPercent = pct
                                                                    onProgress(downloaded, total, pct)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                if (cancelled.get()) {
                                                    partFile.delete()
                                                    return@launch
                                                }
                                                mainHandler.post { onPartFileReady(partFile) }
                                            } finally {
                                                conn.disconnect()
                                            }
                                        } catch (e: Exception) {
                                            partFile.delete()
                                            mainHandler.post { onFailed(e.message ?: "WebView 下载失败") }
                                        }
                                    }
                                }
                                webViewRef = this
                                loadUrl(task.signedUrl)
                            }
                        } catch (e: Exception) {
                            mainHandler.post { onWebViewUnavailable(e.message ?: "WebView 不可用") }
                            android.view.View(ctx)
                        }
                    }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "取消")
            }
        }
    )
}
