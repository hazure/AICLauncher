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
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

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
