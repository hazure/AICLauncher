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
import android.webkit.CookieManager as SystemCookieManager
import android.webkit.WebResourceError as SystemWebResourceError
import android.webkit.WebResourceRequest as SystemWebResourceRequest
import android.webkit.WebResourceResponse as SystemWebResourceResponse
import android.webkit.WebView as SystemWebView
import android.webkit.WebViewClient as SystemWebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aliceincradle.launcher.X5EngineState
import com.aliceincradle.launcher.X5InitStatus
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CAPTURE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

private const val X5_WAIT_TIMEOUT_MS = 2_500L
private const val PAGE_LOAD_TIMEOUT_MS = 10_000L

private val AUTO_AGREE_JS = """
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

private fun streamDownloadToPartFile(
    targetUrl: String,
    cookies: String,
    ua: String,
    contentLength: Long,
    task: WebViewDownloadTask,
    cancelled: AtomicBoolean,
    mainHandler: Handler,
    onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) -> Unit,
    onProgressPercent: (Int) -> Unit,
    onPartFileReady: (File) -> Unit,
    onFailed: (String) -> Unit
) {
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
                                    onProgressPercent(pct)
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

@Composable
private fun X5FallbackBanner(onOpenBrowser: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "X5 内核加载失败，已切换至系统渲染",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD32F2F),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpenBrowser) {
                Text(text = "去系统浏览器打开")
            }
        }
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
    val x5Status by X5EngineState.status.collectAsState()
    var readyToInstantiate by remember { mutableStateOf(x5Status != X5InitStatus.PENDING) }
    var timedOutWaitingForX5 by remember { mutableStateOf(false) }
    var x5RuntimeFailed by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("正在加载官方下载页（请完成年龄确认）...") }
    var isLoading by remember { mutableStateOf(true) }
    var pageFinished by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }
    val completed = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var x5WebViewRef by remember { mutableStateOf<WebView?>(null) }
    var sysWebViewRef by remember { mutableStateOf<SystemWebView?>(null) }

    LaunchedEffect(x5Status) {
        if (x5Status != X5InitStatus.PENDING) {
            readyToInstantiate = true
        } else {
            delay(X5_WAIT_TIMEOUT_MS)
            if (x5Status == X5InitStatus.PENDING) {
                timedOutWaitingForX5 = true
                Log.w("AICL_X5", "X5 init wait timed out, falling back to system WebView")
            }
            readyToInstantiate = true
        }
    }

    val useX5 = readyToInstantiate && x5Status == X5InitStatus.READY_X5 && !x5RuntimeFailed && !timedOutWaitingForX5

    LaunchedEffect(readyToInstantiate, useX5, retryKey) {
        if (!readyToInstantiate) return@LaunchedEffect
        delay(PAGE_LOAD_TIMEOUT_MS)
        if (!pageFinished && !completed.get() && loadErrorMessage == null) {
            isLoading = false
            loadErrorMessage = "加载失败"
            statusText = "页面加载超时（10s），请重试或去系统浏览器打开"
            Log.w("AICL_X5", "ShortCodeCaptureDialog page load timed out after 10s (useX5=$useX5)")
        }
    }

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
                x5WebViewRef?.apply {
                    stopLoading()
                    setDownloadListener(null)
                    destroy()
                }
            }
            runCatching {
                sysWebViewRef?.apply {
                    stopLoading()
                    setDownloadListener(null)
                    destroy()
                }
            }
            x5WebViewRef = null
            sysWebViewRef = null
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
                if (readyToInstantiate && !useX5) {
                    X5FallbackBanner(
                        onOpenBrowser = { openInSystemBrowser(context, GameSourceResolver.OFFICIAL_WIN_URL) }
                    )
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
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

                    if (!readyToInstantiate) {
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
                                    text = "正在初始化浏览器内核...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        key(useX5, retryKey) {
                            if (useX5) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        try {
                                            WebView(ctx).apply {
                                                layoutParams = ViewGroup.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                runCatching { CookieManager.getInstance().setAcceptCookie(true) }
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.userAgentString = CAPTURE_CHROME_UA
                                                webViewClient = object : WebViewClient() {
                                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                        super.onPageStarted(view, url, favicon)
                                                        mainHandler.post {
                                                            isLoading = true
                                                            pageFinished = false
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
                                                        mainHandler.post {
                                                            pageFinished = true
                                                            isLoading = false
                                                        }
                                                        listOf(800L, 2_000L, 4_000L).forEach { delayMs ->
                                                            mainHandler.postDelayed({
                                                                if (!completed.get()) {
                                                                    runCatching { view?.evaluateJavascript(AUTO_AGREE_JS, null) }
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
                                                x5WebViewRef = this
                                                loadUrl(GameSourceResolver.OFFICIAL_WIN_URL)
                                            }
                                        } catch (t: Throwable) {
                                            Log.e("AICL_X5", "X5 WebView instantiation crashed, falling back to system WebView", t)
                                            X5EngineState.markRuntimeFallback()
                                            mainHandler.post { x5RuntimeFailed = true }
                                            android.view.View(ctx)
                                        }
                                    }
                                )
                            } else {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        try {
                                            SystemWebView(ctx).apply {
                                                layoutParams = ViewGroup.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                runCatching { SystemCookieManager.getInstance().setAcceptCookie(true) }
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.userAgentString = CAPTURE_CHROME_UA
                                                webViewClient = object : SystemWebViewClient() {
                                                    override fun onPageStarted(view: SystemWebView?, url: String?, favicon: Bitmap?) {
                                                        super.onPageStarted(view, url, favicon)
                                                        mainHandler.post {
                                                            isLoading = true
                                                            pageFinished = false
                                                            loadErrorMessage = null
                                                        }
                                                    }

                                                    override fun shouldOverrideUrlLoading(
                                                        view: SystemWebView?,
                                                        request: SystemWebResourceRequest?
                                                    ): Boolean {
                                                        val u = request?.url?.toString().orEmpty()
                                                        if (tryCapture(u)) return true
                                                        val host = request?.url?.host?.lowercase().orEmpty()
                                                        return host.isNotEmpty() && host !in GameSourceResolver.ALLOWED_OFFICIAL_HOSTS
                                                    }

                                                    override fun shouldInterceptRequest(
                                                        view: SystemWebView?,
                                                        request: SystemWebResourceRequest?
                                                    ): SystemWebResourceResponse? {
                                                        val u = request?.url?.toString().orEmpty()
                                                        if (tryCapture(u)) {
                                                            return SystemWebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                                        }
                                                        return super.shouldInterceptRequest(view, request)
                                                    }

                                                    override fun onReceivedError(
                                                        view: SystemWebView?,
                                                        request: SystemWebResourceRequest?,
                                                        error: SystemWebResourceError?
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

                                                    override fun onPageFinished(view: SystemWebView?, url: String?) {
                                                        super.onPageFinished(view, url)
                                                        mainHandler.post {
                                                            pageFinished = true
                                                            isLoading = false
                                                        }
                                                        listOf(800L, 2_000L, 4_000L).forEach { delayMs ->
                                                            mainHandler.postDelayed({
                                                                if (!completed.get()) {
                                                                    runCatching { view?.evaluateJavascript(AUTO_AGREE_JS, null) }
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
                                                sysWebViewRef = this
                                                loadUrl(GameSourceResolver.OFFICIAL_WIN_URL)
                                            }
                                        } catch (t: Throwable) {
                                            Log.e("AICL_X5", "System WebView instantiation failed", t)
                                            mainHandler.post {
                                                isLoading = false
                                                loadErrorMessage = "加载失败，请检查网络"
                                            }
                                            android.view.View(ctx)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (readyToInstantiate && isLoading && loadErrorMessage == null) {
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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                x5WebViewRef?.apply {
                                                    stopLoading()
                                                    setDownloadListener(null)
                                                    destroy()
                                                }
                                            }
                                            runCatching {
                                                sysWebViewRef?.apply {
                                                    stopLoading()
                                                    setDownloadListener(null)
                                                    destroy()
                                                }
                                            }
                                            x5WebViewRef = null
                                            sysWebViewRef = null
                                            loadErrorMessage = null
                                            pageFinished = false
                                            isLoading = true
                                            statusText = "正在加载官方下载页（请完成年龄确认）..."
                                            retryKey++
                                        }
                                    ) {
                                        Text(text = "重试", color = Color.White)
                                    }
                                    OutlinedButton(
                                        onClick = { openInSystemBrowser(context, GameSourceResolver.OFFICIAL_WIN_URL) }
                                    ) {
                                        Text(text = "去系统浏览器打开", color = Color.White)
                                    }
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
    val x5Status by X5EngineState.status.collectAsState()
    var readyToInstantiate by remember { mutableStateOf(x5Status != X5InitStatus.PENDING) }
    var timedOutWaitingForX5 by remember { mutableStateOf(false) }
    var x5RuntimeFailed by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("正在通过应用内浏览器接管下载（fp=${task.urlFp}）...") }
    var progressPercent by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var pageFinished by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }
    val startedDownload = remember { AtomicBoolean(false) }
    val cancelled = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var x5WebViewRef by remember { mutableStateOf<WebView?>(null) }
    var sysWebViewRef by remember { mutableStateOf<SystemWebView?>(null) }

    LaunchedEffect(x5Status) {
        if (x5Status != X5InitStatus.PENDING) {
            readyToInstantiate = true
        } else {
            delay(X5_WAIT_TIMEOUT_MS)
            if (x5Status == X5InitStatus.PENDING) {
                timedOutWaitingForX5 = true
                Log.w("AICL_X5", "X5 init wait timed out in download dialog, falling back to system WebView")
            }
            readyToInstantiate = true
        }
    }

    val useX5 = readyToInstantiate && x5Status == X5InitStatus.READY_X5 && !x5RuntimeFailed && !timedOutWaitingForX5

    LaunchedEffect(readyToInstantiate, useX5, retryKey) {
        if (!readyToInstantiate) return@LaunchedEffect
        delay(PAGE_LOAD_TIMEOUT_MS)
        if (!pageFinished && !startedDownload.get() && loadErrorMessage == null) {
            isLoading = false
            loadErrorMessage = "加载失败"
            statusText = "页面加载超时（10s），请重试或去系统浏览器打开"
            Log.w("AICL_X5", "WebViewDownloadDialog page load timed out after 10s (useX5=$useX5)")
        }
    }

    DisposableEffect(task.itemId, task.urlFp) {
        runCatching {
            Log.i("AICL_PIPELINE", "phase=DOWNLOAD_START itemId=${task.itemId} sourceKind=WEBVIEW_MAIN fp=${task.urlFp}")
        }
        onDispose {
            cancelled.set(true)
            runCatching {
                x5WebViewRef?.apply {
                    stopLoading()
                    setDownloadListener(null)
                    destroy()
                }
            }
            runCatching {
                sysWebViewRef?.apply {
                    stopLoading()
                    setDownloadListener(null)
                    destroy()
                }
            }
            x5WebViewRef = null
            sysWebViewRef = null
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
                if (readyToInstantiate && !useX5) {
                    X5FallbackBanner(
                        onOpenBrowser = { openInSystemBrowser(context, task.signedUrl) }
                    )
                }
                LinearProgressIndicator(
                    progress = { (progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    if (!readyToInstantiate) {
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
                                    text = "正在初始化浏览器内核...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        key(useX5, retryKey) {
                            if (useX5) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        try {
                                            WebView(ctx).apply {
                                                layoutParams = ViewGroup.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                runCatching {
                                                    CookieManager.getInstance().setAcceptCookie(true)
                                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                                }
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.userAgentString = CAPTURE_CHROME_UA
                                                webViewClient = object : WebViewClient() {
                                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                                        super.onPageStarted(view, url, favicon)
                                                        mainHandler.post {
                                                            isLoading = true
                                                            pageFinished = false
                                                            loadErrorMessage = null
                                                        }
                                                    }

                                                    override fun onPageFinished(view: WebView?, url: String?) {
                                                        super.onPageFinished(view, url)
                                                        mainHandler.post {
                                                            pageFinished = true
                                                            isLoading = false
                                                        }
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
                                                    val cookies = runCatching {
                                                        CookieManager.getInstance().getCookie(targetUrl).orEmpty()
                                                    }.getOrDefault("")
                                                    val ua = userAgent?.takeIf { it.isNotBlank() } ?: CAPTURE_CHROME_UA
                                                    mainHandler.post {
                                                        isLoading = false
                                                        statusText = "已接管下载流，正在写入缓存..."
                                                    }
                                                    streamDownloadToPartFile(
                                                        targetUrl = targetUrl,
                                                        cookies = cookies,
                                                        ua = ua,
                                                        contentLength = contentLength,
                                                        task = task,
                                                        cancelled = cancelled,
                                                        mainHandler = mainHandler,
                                                        onProgress = onProgress,
                                                        onProgressPercent = { progressPercent = it },
                                                        onPartFileReady = onPartFileReady,
                                                        onFailed = onFailed
                                                    )
                                                }
                                                x5WebViewRef = this
                                                loadUrl(task.signedUrl)
                                            }
                                        } catch (t: Throwable) {
                                            Log.e("AICL_X5", "X5 WebView download instantiation crashed, falling back to system WebView", t)
                                            X5EngineState.markRuntimeFallback()
                                            mainHandler.post { x5RuntimeFailed = true }
                                            android.view.View(ctx)
                                        }
                                    }
                                )
                            } else {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        try {
                                            SystemWebView(ctx).apply {
                                                layoutParams = ViewGroup.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                runCatching {
                                                    SystemCookieManager.getInstance().setAcceptCookie(true)
                                                    SystemCookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                                }
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.userAgentString = CAPTURE_CHROME_UA
                                                webViewClient = object : SystemWebViewClient() {
                                                    override fun onPageStarted(view: SystemWebView?, url: String?, favicon: Bitmap?) {
                                                        super.onPageStarted(view, url, favicon)
                                                        mainHandler.post {
                                                            isLoading = true
                                                            pageFinished = false
                                                            loadErrorMessage = null
                                                        }
                                                    }

                                                    override fun onPageFinished(view: SystemWebView?, url: String?) {
                                                        super.onPageFinished(view, url)
                                                        mainHandler.post {
                                                            pageFinished = true
                                                            isLoading = false
                                                        }
                                                    }

                                                    override fun onReceivedError(
                                                        view: SystemWebView?,
                                                        request: SystemWebResourceRequest?,
                                                        error: SystemWebResourceError?
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
                                                    val cookies = runCatching {
                                                        SystemCookieManager.getInstance().getCookie(targetUrl).orEmpty()
                                                    }.getOrDefault("")
                                                    val ua = userAgent?.takeIf { it.isNotBlank() } ?: CAPTURE_CHROME_UA
                                                    mainHandler.post {
                                                        isLoading = false
                                                        statusText = "已接管下载流，正在写入缓存..."
                                                    }
                                                    streamDownloadToPartFile(
                                                        targetUrl = targetUrl,
                                                        cookies = cookies,
                                                        ua = ua,
                                                        contentLength = contentLength,
                                                        task = task,
                                                        cancelled = cancelled,
                                                        mainHandler = mainHandler,
                                                        onProgress = onProgress,
                                                        onProgressPercent = { progressPercent = it },
                                                        onPartFileReady = onPartFileReady,
                                                        onFailed = onFailed
                                                    )
                                                }
                                                sysWebViewRef = this
                                                loadUrl(task.signedUrl)
                                            }
                                        } catch (t: Throwable) {
                                            Log.e("AICL_X5", "System WebView download instantiation failed", t)
                                            mainHandler.post {
                                                isLoading = false
                                                loadErrorMessage = "加载失败，请检查网络"
                                                onWebViewUnavailable(t.message ?: "WebView 不可用")
                                            }
                                            android.view.View(ctx)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (readyToInstantiate && isLoading && loadErrorMessage == null) {
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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                x5WebViewRef?.apply {
                                                    stopLoading()
                                                    setDownloadListener(null)
                                                    destroy()
                                                }
                                            }
                                            runCatching {
                                                sysWebViewRef?.apply {
                                                    stopLoading()
                                                    setDownloadListener(null)
                                                    destroy()
                                                }
                                            }
                                            x5WebViewRef = null
                                            sysWebViewRef = null
                                            loadErrorMessage = null
                                            pageFinished = false
                                            isLoading = true
                                            statusText = "正在通过应用内浏览器接管下载（fp=${task.urlFp}）..."
                                            retryKey++
                                        }
                                    ) {
                                        Text(text = "重试", color = Color.White)
                                    }
                                    OutlinedButton(
                                        onClick = { openInSystemBrowser(context, task.signedUrl) }
                                    ) {
                                        Text(text = "去系统浏览器打开", color = Color.White)
                                    }
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
