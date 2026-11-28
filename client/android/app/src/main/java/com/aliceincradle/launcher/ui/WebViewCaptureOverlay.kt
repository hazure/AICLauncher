package com.aliceincradle.launcher.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aliceincradle.launcher.bridge.GameSourceResolver
import com.aliceincradle.launcher.data.WebViewDownloadTask
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal const val DEFAULT_ZOOM_PERCENT = 80
internal const val RESET_ZOOM_PERCENT = 100
internal const val MIN_ZOOM_PERCENT = 50
internal const val MAX_ZOOM_PERCENT = 150
internal const val ZOOM_STEP_PERCENT = 10

private const val MIN_RECOMMENDED_WEBVIEW_MAJOR = 115
private const val PAGE_LOAD_TIMEOUT_MS = 15_000L
private const val DOM_POLL_INTERVAL_MS = 500L
private const val DOM_POLL_MAX_ATTEMPTS = 12
private const val PLAY_STORE_WEBVIEW_MARKET_URI = "market://details?id=com.google.android.webview"
private const val PLAY_STORE_WEBVIEW_HTTPS_URL = "https://play.google.com/store/apps/details?id=com.google.android.webview"

internal fun clampZoomPercent(percent: Int): Int = percent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)

private val VIEWPORT_UNLOCK_JS = """
    (function() {
        try {
            var metas = document.querySelectorAll('meta[name="viewport"]');
            for (var i = 0; i < metas.length; i++) {
                metas[i].parentNode.removeChild(metas[i]);
            }
            var meta = document.createElement('meta');
            meta.name = 'viewport';
            meta.content = 'width=device-width, initial-scale=0.8, minimum-scale=0.5, maximum-scale=3.0, user-scalable=yes';
            (document.head || document.documentElement).appendChild(meta);
        } catch (_) {}
    })();
""".trimIndent()

private val URL_TOKEN_REGEX = Regex("""https?://\S+""")
private val SHORTLINK_NO_SCHEME_REGEX = Regex("""vanilla\.shiro\.dev/f/\S+""", RegexOption.IGNORE_CASE)

private val SMART_CAPTURE_AND_AGREE_JS = """
    (function() {
        var links = document.querySelectorAll('a[href], [data-href], [data-url]');
        for (var i = 0; i < links.length; i++) {
            var cand = links[i].getAttribute('href') || links[i].getAttribute('data-href') || links[i].getAttribute('data-url') || '';
            if (cand.indexOf('vanilla.shiro.dev/f/') !== -1) {
                return cand;
            }
        }
        var pre = document.getElementById('preloader');
        if (pre) {
            var cs = window.getComputedStyle ? window.getComputedStyle(pre) : pre.style;
            if (cs && cs.display !== 'none' && cs.visibility !== 'hidden' && cs.opacity !== '0') {
                return 'WAIT';
            }
        }
        try {
            var allEls = document.querySelectorAll('div, section, aside');
            var vw = window.innerWidth || 0;
            var vh = window.innerHeight || 0;
            for (var k = 0; k < allEls.length; k++) {
                var ov = allEls[k];
                if (ov.id === 'preloader') continue;
                var ocs = window.getComputedStyle ? window.getComputedStyle(ov) : null;
                if (!ocs || ocs.position !== 'fixed') continue;
                var z = parseInt(ocs.zIndex || '0', 10);
                if (isNaN(z) || z <= 10) continue;
                var rect = ov.getBoundingClientRect ? ov.getBoundingClientRect() : null;
                if (rect && vw > 0 && vh > 0 && rect.width >= vw * 0.8 && rect.height >= vh * 0.8) {
                    if (!ov.querySelector('button, a, input, [role="button"]')) {
                        ov.style.pointerEvents = 'none';
                    }
                }
            }
        } catch (_) {}
        var excludeRe = /(cancel|取消|leave|离开|exit|退出|disagree|不同意|^no$|^否$)/i;
        var positiveRe = /(18|同意|确认|agree|confirm|enter|yes|downloadwin)/i;
        var nodes = document.querySelectorAll('button, a, [role="button"], input[type="button"], input[type="submit"]');
        for (var j = 0; j < nodes.length; j++) {
            var el = nodes[j];
            if (el.dataset && el.dataset.aiclClicked === '1') continue;
            var style = window.getComputedStyle ? window.getComputedStyle(el) : el.style;
            if (style && (style.display === 'none' || style.visibility === 'hidden')) continue;
            var text = (el.innerText || el.value || el.getAttribute('aria-label') || '').trim();
            if (text && excludeRe.test(text)) continue;
            var action = el.getAttribute('data-action') || '';
            var cls = (el.className || '').toString();
            var id = el.id || '';
            var combined = text + ' ' + action + ' ' + cls + ' ' + id;
            if (positiveRe.test(combined)) {
                if (el.dataset) el.dataset.aiclClicked = '1';
                try { el.click(); } catch (_) {}
                try {
                    if (typeof PointerEvent === 'function') {
                        el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true }));
                        el.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, cancelable: true }));
                    }
                    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                } catch (_) {}
                return 'CLICKED';
            }
        }
        return 'NONE';
    })();
""".trimIndent()

internal data class WebViewVersionInfo(
    val packageName: String?,
    val versionName: String?,
    val majorVersion: Int?
)

internal fun parseWebViewMajorVersion(versionName: String?): Int? {
    val trimmed = versionName?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val firstSegment = trimmed.substringBefore('.').takeWhile { it.isDigit() }
    return firstSegment.toIntOrNull()
}

internal fun extractClipboardShortCode(rawText: String?): Pair<String, String>? {
    return try {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return null
        for (match in URL_TOKEN_REGEX.findAll(text)) {
            val cleaned = match.value.trimEnd(',', '.', ';', ')', ']', '"', '\'')
            val parsed = GameSourceResolver.parseShortLinkUrl(cleaned)
            if (parsed != null) return parsed
        }
        val noScheme = SHORTLINK_NO_SCHEME_REGEX.find(text)?.value
            ?.trimEnd(',', '.', ';', ')', ']', '"', '\'')
        if (!noScheme.isNullOrBlank()) {
            return GameSourceResolver.parseShortLinkUrl("https://$noScheme")
        }
        null
    } catch (t: Throwable) {
        Log.w("AICL_WEBVIEW", "Failed parsing clipboard short code", t)
        null
    }
}

private fun detectSystemWebViewVersion(context: Context): WebViewVersionInfo {
    return try {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()
        } else {
            runCatching {
                val cls = Class.forName("android.webkit.WebViewFactory")
                val m = cls.getMethod("getLoadedPackageInfo")
                m.invoke(null) as? android.content.pm.PackageInfo
            }.getOrNull() ?: listOf(
                "com.google.android.webview",
                "com.android.chrome",
                "com.android.webview"
            ).firstNotNullOfOrNull { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            }
        }
        val verName = pkgInfo?.versionName
        WebViewVersionInfo(
            packageName = pkgInfo?.packageName,
            versionName = verName,
            majorVersion = parseWebViewMajorVersion(verName)
        )
    } catch (t: Throwable) {
        Log.w("AICL_WEBVIEW", "Failed detecting system WebView version", t)
        WebViewVersionInfo(packageName = null, versionName = null, majorVersion = null)
    }
}

private class WebViewHolder<T>(var ref: T? = null)

private fun safeDestroyWebView(view: View?) {
    if (view is WebView) {
        try {
            view.stopLoading()
            view.setDownloadListener(null)
            view.webViewClient = WebViewClient()
            view.webChromeClient = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        } catch (t: Throwable) {
            Log.w("AICL_WEBVIEW", "safeDestroyWebView failed", t)
        }
    }
}

private fun applyWebViewZoom(view: WebView?, targetPercent: Int) {
    val clamped = clampZoomPercent(targetPercent)
    try {
        view?.setInitialScale(clamped)
        view?.evaluateJavascript(
            "try{if(document&&document.documentElement){document.documentElement.style.zoom='${clamped}%';}}catch(_){}",
            null
        )
    } catch (t: Throwable) {
        Log.w("AICL_WEBVIEW", "Failed applying WebView zoom=$clamped", t)
    }
}

@Composable
private fun WebViewZoomToolbar(
    zoomPercent: Int,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onResetZoom: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "缩放：$zoomPercent%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onZoomOut,
                enabled = zoomPercent > MIN_ZOOM_PERCENT
            ) {
                Text(text = "-")
            }
            OutlinedButton(
                onClick = onZoomIn,
                enabled = zoomPercent < MAX_ZOOM_PERCENT
            ) {
                Text(text = "+")
            }
            TextButton(onClick = onResetZoom) {
                Text(text = "100%")
            }
        }
    }
}

private fun openInSystemBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (t: Throwable) {
        Log.e("AICL_WEBVIEW", "Failed to open system browser for $url", t)
    }
}

private fun openWebViewInPlayStore(context: Context) {
    try {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEBVIEW_MARKET_URI)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(marketIntent)
    } catch (t: Throwable) {
        Log.w("AICL_WEBVIEW", "Play Store market:// intent failed, falling back to HTTPS", t)
        openInSystemBrowser(context, PLAY_STORE_WEBVIEW_HTTPS_URL)
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
    val destFile = File(task.destFilePath)
    val partFile = File(destFile.parentFile, "${destFile.name}.part")
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("AICL_WEBVIEW", "Uncaught coroutine error in WebView download stream", throwable)
        runCatching { partFile.delete() }
        mainHandler.post { onFailed(throwable.message ?: "WebView 下载失败") }
    }
    CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
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
        } catch (t: Throwable) {
            Log.e("AICL_WEBVIEW", "WebView download stream failed", t)
            runCatching { partFile.delete() }
            mainHandler.post { onFailed(t.message ?: "WebView 下载失败") }
        }
    }
}

@Composable
private fun LowWebViewVersionBanner(
    versionInfo: WebViewVersionInfo,
    onUpdatePlayStore: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    val versionLabel = versionInfo.versionName?.let { "（当前版本：$it）" } ?: "（版本未知）"
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "当前系统 WebView 版本过低$versionLabel，可能导致官方下载页灰屏，建议前往 Google Play 商店更新至新版。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUpdatePlayStore) {
                    Text(text = "去 Play 商店更新")
                }
                TextButton(onClick = onOpenBrowser) {
                    Text(text = "外部浏览器打开下载页")
                }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val versionInfo = remember { detectSystemWebViewVersion(context) }
    val isLowWebView = versionInfo.majorVersion == null || versionInfo.majorVersion < MIN_RECOMMENDED_WEBVIEW_MAJOR

    var statusText by remember { mutableStateOf("正在加载官方下载页（请完成年龄确认）...") }
    var isLoading by remember { mutableStateOf(true) }
    var pageFinished by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }
    var clipboardCandidate by remember { mutableStateOf<Pair<String, String>?>(null) }
    var dismissedClipboardCode by remember { mutableStateOf<String?>(null) }
    var zoomPercent by remember { mutableStateOf(DEFAULT_ZOOM_PERCENT) }
    val completed = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val webViewHolder = remember { WebViewHolder<WebView>() }

    val tryCapture: (String) -> Boolean = { url ->
        if (disposed.get()) {
            false
        } else {
            val parsed = GameSourceResolver.parseShortLinkUrl(url)
            if (parsed != null) {
                if (completed.compareAndSet(false, true)) {
                    runCatching {
                        Log.i("AICL_PIPELINE", "phase=SHORTCODE_CAPTURED shortCode=${parsed.first}")
                    }
                    mainHandler.post {
                        if (!disposed.get()) onCaptured(parsed.first, parsed.second)
                    }
                }
                true
            } else {
                false
            }
        }
    }

    LaunchedEffect(retryKey) {
        delay(PAGE_LOAD_TIMEOUT_MS)
        if (!pageFinished && !completed.get() && loadErrorMessage == null) {
            isLoading = false
            loadErrorMessage = "加载失败"
            statusText = "页面加载超时（15s），请重试或去系统浏览器打开"
            Log.w("AICL_WEBVIEW", "ShortCodeCaptureDialog page load timed out after 15s (ver=${versionInfo.versionName})")
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !completed.get()) {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString()
                    val parsed = extractClipboardShortCode(clipText)
                    if (parsed != null && parsed.first != dismissedClipboardCode) {
                        clipboardCandidate = parsed
                    }
                } catch (t: Throwable) {
                    Log.w("AICL_WEBVIEW", "Failed reading clipboard on resume", t)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                isLoading = false
                statusText = "捕获超时，请改用手动填入短码"
            }
        }
        mainHandler.postDelayed(timeoutRunnable, GameSourceResolver.WEBVIEW_TIMEOUT_MS)
        onDispose {
            disposed.set(true)
            mainHandler.removeCallbacksAndMessages(null)
            safeDestroyWebView(webViewHolder.ref)
            webViewHolder.ref = null
        }
    }

    fun scheduleDomPoll(view: WebView?, attempt: Int) {
        if (attempt >= DOM_POLL_MAX_ATTEMPTS || disposed.get() || completed.get() || view == null) return
        mainHandler.postDelayed({
            if (disposed.get() || completed.get() || webViewHolder.ref !== view) return@postDelayed
            try {
                view.evaluateJavascript(SMART_CAPTURE_AND_AGREE_JS) { raw ->
                    val cleaned = raw?.trim()?.trim('"')?.replace("\\/", "/") ?: ""
                    if (cleaned.contains("vanilla.shiro.dev/f/")) {
                        tryCapture(cleaned)
                    } else if (!completed.get()) {
                        scheduleDomPoll(view, attempt + 1)
                    }
                }
            } catch (t: Throwable) {
                Log.w("AICL_WEBVIEW", "DOM poll evaluateJavascript failed", t)
            }
        }, DOM_POLL_INTERVAL_MS)
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
                if (isLowWebView) {
                    LowWebViewVersionBanner(
                        versionInfo = versionInfo,
                        onUpdatePlayStore = { openWebViewInPlayStore(context) },
                        onOpenBrowser = { openInSystemBrowser(context, GameSourceResolver.OFFICIAL_WIN_URL) }
                    )
                }
                clipboardCandidate?.let { candidate ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
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
                                text = "检测到剪贴板短码：${candidate.first}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    if (completed.compareAndSet(false, true)) {
                                        onCaptured(candidate.first, candidate.second)
                                    }
                                }
                            ) {
                                Text(text = "填入")
                            }
                            TextButton(
                                onClick = {
                                    dismissedClipboardCode = candidate.first
                                    clipboardCandidate = null
                                }
                            ) {
                                Text(text = "忽略")
                            }
                        }
                    }
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                WebViewZoomToolbar(
                    zoomPercent = zoomPercent,
                    onZoomOut = {
                        val next = clampZoomPercent(zoomPercent - ZOOM_STEP_PERCENT)
                        zoomPercent = next
                        applyWebViewZoom(webViewHolder.ref, next)
                    },
                    onZoomIn = {
                        val next = clampZoomPercent(zoomPercent + ZOOM_STEP_PERCENT)
                        zoomPercent = next
                        applyWebViewZoom(webViewHolder.ref, next)
                    },
                    onResetZoom = {
                        zoomPercent = RESET_ZOOM_PERCENT
                        applyWebViewZoom(webViewHolder.ref, RESET_ZOOM_PERCENT)
                    }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    key(retryKey) {
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
                                        settings.userAgentString = DESKTOP_CHROME_UA
                                        settings.loadWithOverviewMode = true
                                        settings.useWideViewPort = true
                                        settings.setSupportZoom(true)
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        setInitialScale(zoomPercent)
                                        webViewClient = object : WebViewClient() {
                                            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                                                super.onScaleChanged(view, oldScale, newScale)
                                                if (newScale > 0f && !disposed.get()) {
                                                    mainHandler.post {
                                                        if (!disposed.get()) {
                                                            zoomPercent = clampZoomPercent((newScale * 100f).toInt())
                                                        }
                                                    }
                                                }
                                            }
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
                                                if (disposed.get()) return
                                                try {
                                                    view?.evaluateJavascript(VIEWPORT_UNLOCK_JS, null)
                                                    if (zoomPercent != DEFAULT_ZOOM_PERCENT) {
                                                        applyWebViewZoom(view, zoomPercent)
                                                    }
                                                } catch (t: Throwable) {
                                                    Log.w("AICL_WEBVIEW", "Failed injecting viewport unlock JS", t)
                                                }
                                                if (!url.isNullOrBlank()) {
                                                    tryCapture(url)
                                                }
                                                mainHandler.post {
                                                    if (!disposed.get()) {
                                                        pageFinished = true
                                                        isLoading = false
                                                    }
                                                }
                                                scheduleDomPoll(view, 0)
                                            }
                                        }
                                        setDownloadListener { dlUrl, _, _, _, _ ->
                                            if (!dlUrl.isNullOrBlank()) {
                                                tryCapture(dlUrl)
                                            }
                                        }
                                        webViewHolder.ref = this
                                        loadUrl(GameSourceResolver.OFFICIAL_WIN_URL)
                                    }
                                } catch (t: Throwable) {
                                    Log.e("AICL_WEBVIEW", "System WebView instantiation failed", t)
                                    mainHandler.post {
                                        isLoading = false
                                        loadErrorMessage = "加载失败，请检查网络"
                                    }
                                    View(ctx)
                                }
                            },
                            onRelease = { released ->
                                if (webViewHolder.ref === released) webViewHolder.ref = null
                                safeDestroyWebView(released)
                            }
                        )
                    }

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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            mainHandler.removeCallbacksAndMessages(null)
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
    val versionInfo = remember { detectSystemWebViewVersion(context) }
    val isLowWebView = versionInfo.majorVersion == null || versionInfo.majorVersion < MIN_RECOMMENDED_WEBVIEW_MAJOR

    var statusText by remember { mutableStateOf("正在通过应用内浏览器接管下载（fp=${task.urlFp}）...") }
    var progressPercent by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var pageFinished by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }
    val startedDownload = remember { AtomicBoolean(false) }
    val cancelled = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val webViewHolder = remember { WebViewHolder<WebView>() }

    LaunchedEffect(retryKey) {
        delay(PAGE_LOAD_TIMEOUT_MS)
        if (!pageFinished && !startedDownload.get() && loadErrorMessage == null) {
            isLoading = false
            loadErrorMessage = "加载失败"
            statusText = "页面加载超时（15s），请重试或去系统浏览器打开"
            Log.w("AICL_WEBVIEW", "WebViewDownloadDialog page load timed out after 15s (ver=${versionInfo.versionName})")
        }
    }

    DisposableEffect(task.itemId, task.urlFp) {
        runCatching {
            Log.i("AICL_PIPELINE", "phase=DOWNLOAD_START itemId=${task.itemId} sourceKind=WEBVIEW_MAIN fp=${task.urlFp}")
        }
        onDispose {
            disposed.set(true)
            cancelled.set(true)
            mainHandler.removeCallbacksAndMessages(null)
            safeDestroyWebView(webViewHolder.ref)
            webViewHolder.ref = null
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
                if (isLowWebView) {
                    LowWebViewVersionBanner(
                        versionInfo = versionInfo,
                        onUpdatePlayStore = { openWebViewInPlayStore(context) },
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
                    key(retryKey) {
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
                                        settings.userAgentString = DESKTOP_CHROME_UA
                                        settings.loadWithOverviewMode = true
                                        settings.useWideViewPort = true
                                        settings.setSupportZoom(true)
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        setInitialScale(DEFAULT_ZOOM_PERCENT)
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
                                                if (disposed.get()) return
                                                try {
                                                    view?.evaluateJavascript(VIEWPORT_UNLOCK_JS, null)
                                                } catch (t: Throwable) {
                                                    Log.w("AICL_WEBVIEW", "Failed injecting viewport unlock JS in download dialog", t)
                                                }
                                                mainHandler.post {
                                                    if (!disposed.get()) {
                                                        pageFinished = true
                                                        isLoading = false
                                                    }
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
                                            val ua = userAgent?.takeIf { it.isNotBlank() } ?: DESKTOP_CHROME_UA
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
                                        webViewHolder.ref = this
                                        loadUrl(task.signedUrl)
                                    }
                                } catch (t: Throwable) {
                                    Log.e("AICL_WEBVIEW", "System WebView download instantiation failed", t)
                                    mainHandler.post {
                                        isLoading = false
                                        loadErrorMessage = "加载失败，请检查网络"
                                        onWebViewUnavailable(t.message ?: "WebView 不可用")
                                    }
                                    View(ctx)
                                }
                            },
                            onRelease = { released ->
                                if (webViewHolder.ref === released) webViewHolder.ref = null
                                safeDestroyWebView(released)
                            }
                        )
                    }

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
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            mainHandler.removeCallbacksAndMessages(null)
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
