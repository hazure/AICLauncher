package com.aliceincradle.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aliceincradle.launcher.bridge.GameSourceResolver
import com.aliceincradle.launcher.data.WebViewDownloadTask
import com.tencent.smtt.export.external.interfaces.WebResourceError
import com.tencent.smtt.export.external.interfaces.WebResourceRequest
import com.tencent.smtt.export.external.interfaces.WebResourceResponse
import com.tencent.smtt.sdk.CookieManager
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient
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

private fun openInSystemBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("AICL_X5", "Failed to open system browser for $url", e)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShortCodeCaptureDialog(
    onCaptured: (shortCode: String, fileName: String) -> Unit,
    onFallbackManual: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("正在加载官方下载页（请完成年龄确认）...") }
    var isLoading by remember { mutableStateOf(true) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }
    val completed = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                isLoading = false
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            try {
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.userAgentString = CAPTURE_CHROME_UA
                                    val tryCapture: (String) -> Boolean = { url ->
                                        val parsed = GameSourceResolver.parseShortLinkUrl(url)
                                        if (parsed != null) {
                                            if (completed.compareAndSet(false, true)) {
                                                runCatching {
                                                    Log.i("AICL_PIPELINE", "phase=SHORTCODE_CAPTURED shortCode=${parsed.first}")
                                                }
                                                mainHandler.post { onCaptured(parsed.first, parsed.second) }
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            mainHandler.post {
                                                isLoading = true
                                                loadErrorMessage = null
                                            }
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

                                        @Deprecated("Deprecated in Java")
                                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                            val u = url.orEmpty()
                                            if (tryCapture(u)) return true
                                            val host = runCatching { Uri.parse(u).host?.lowercase().orEmpty() }.getOrDefault("")
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

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame == true) {
                                                mainHandler.post {
                                                    isLoading = false
                                                    loadErrorMessage = "加载失败，请检查网络"
                                                    statusText = "加载失败，请检查网络"
                                                }
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            mainHandler.post { isLoading = false }
                                            val js = """
                                                (function() {
                                                    var pre = document.getElementById('preloader');
                                                    if (pre && pre.style.display !== 'none') return;
                                                    var btns = document.querySelectorAll('button, a');
                                                    for (var i = 0; i < btns.length; i++) {
                                                        var t = (btns[i].innerText || '').trim();
                                                        if (t === 'Agree' || t === '同意' || btns[i].getAttribute('data-action') === 'downloadWin') {
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
                                    setDownloadListener { dlUrl, _, _, _, _ ->
                                        if (!dlUrl.isNullOrBlank()) {
                                            tryCapture(dlUrl)
                                        }
                                    }
                                    webViewRef = this
                                    loadUrl(GameSourceResolver.OFFICIAL_WIN_URL)
                                }
                            } catch (e: Exception) {
                                Log.e("AICL_X5", "Failed to create X5 WebView for capture", e)
                                mainHandler.post {
                                    isLoading = false
                                    loadErrorMessage = "加载失败，请检查网络"
                                }
                                android.view.View(ctx)
                            }
                        }
                    )

                    if (isLoading && loadErrorMessage == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(color = Color.White)
                                Text(
                                    text = "正在加载官方页面...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (loadErrorMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = loadErrorMessage ?: "加载失败，请检查网络",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                                OutlinedButton(
                                    onClick = { openInSystemBrowser(context, GameSourceResolver.OFFICIAL_WIN_URL) }
                                ) {
                                    Text(text = "使用系统浏览器打开", color = Color.White)
                                }
                            }
                        }
                    }
                }
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
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("正在通过应用内浏览器接管下载（fp=${task.urlFp}）...") }
    var progressPercent by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
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
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            mainHandler.post {
                                                isLoading = true
                                                loadErrorMessage = null
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            mainHandler.post { isLoading = false }
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame == true) {
                                                mainHandler.post {
                                                    isLoading = false
                                                    loadErrorMessage = "加载失败，请检查网络"
                                                }
                                            }
                                        }
                                    }
                                    setDownloadListener { dlUrl, userAgent, _, _, contentLength ->
                                        if (!startedDownload.compareAndSet(false, true)) return@setDownloadListener
                                        val targetUrl = dlUrl?.takeIf { it.isNotBlank() } ?: task.signedUrl
                                        val cookies = CookieManager.getInstance().getCookie(targetUrl).orEmpty()
                                        val ua = userAgent?.takeIf { it.isNotBlank() } ?: CAPTURE_CHROME_UA
                                        mainHandler.post {
                                            isLoading = false
                                            statusText = "已接管下载流，正在写入缓存..."
                                        }
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
                                Log.e("AICL_X5", "Failed to create X5 WebView for download", e)
                                mainHandler.post {
                                    isLoading = false
                                    loadErrorMessage = "加载失败，请检查网络"
                                    onWebViewUnavailable(e.message ?: "WebView 不可用")
                                }
                                android.view.View(ctx)
                            }
                        }
                    )

                    if (isLoading && loadErrorMessage == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(color = Color.White)
                                Text(
                                    text = "正在加载官方页面...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (loadErrorMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = loadErrorMessage ?: "加载失败，请检查网络",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                                OutlinedButton(
                                    onClick = { openInSystemBrowser(context, task.signedUrl) }
                                ) {
                                    Text(text = "使用系统浏览器打开", color = Color.White)
                                }
                            }
                        }
                    }
                }
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
