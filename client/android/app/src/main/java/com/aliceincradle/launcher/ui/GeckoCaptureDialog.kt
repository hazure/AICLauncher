package com.aliceincradle.launcher.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aliceincradle.launcher.bridge.GameSourceResolver
import com.aliceincradle.launcher.bridge.GeckoDesktopIdentity
import com.aliceincradle.launcher.bridge.GeckoRuntimeManager
import com.aliceincradle.launcher.bridge.GeckoRuntimeStatus
import com.aliceincradle.launcher.bridge.ShortCodeRelayExtractor
import java.util.concurrent.atomic.AtomicBoolean
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

private class GeckoRefHolder<T>(var ref: T? = null)

@Composable
fun GeckoCaptureDialog(
    onCaptured: (shortCode: String, fileName: String) -> Unit,
    onDirectDownloadUrl: (url: String, fileName: String) -> Unit,
    onFallbackExternalRelay: () -> Unit,
    onFallbackManual: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtimeState by GeckoRuntimeManager.state.collectAsState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val completed = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val sessionHolder = remember { GeckoRefHolder<GeckoSession>() }
    val viewHolder = remember { GeckoRefHolder<GeckoView>() }

    var progress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val runtime = remember(context) { GeckoRuntimeManager.getOrCreate(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val session = sessionHolder.ref ?: return@LifecycleEventObserver
            try {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> session.setActive(true)
                    Lifecycle.Event.ON_PAUSE -> session.setActive(false)
                    else -> Unit
                }
            } catch (t: Throwable) {
                Log.w("AICL_GECKO", "Failed syncing GeckoSession active state", t)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            mainHandler.removeCallbacksAndMessages(null)
            try {
                viewHolder.ref?.releaseSession()
            } catch (t: Throwable) {
                Log.w("AICL_GECKO", "Failed releasing GeckoView session", t)
            }
            try {
                sessionHolder.ref?.close()
            } catch (t: Throwable) {
                Log.w("AICL_GECKO", "Failed closing GeckoSession", t)
            }
            viewHolder.ref = null
            sessionHolder.ref = null
        }
    }

    fun tryCaptureUrl(url: String?): Boolean {
        if (disposed.get() || url.isNullOrBlank()) return false
        val extracted = ShortCodeRelayExtractor.extract(url) ?: return false
        if (completed.compareAndSet(false, true)) {
            mainHandler.post {
                if (!disposed.get()) {
                    onCaptured(extracted.shortCode, extracted.fileName)
                }
            }
        }
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "应用内下载验证（Gecko）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = errorMessage ?: if (isLoading) "正在加载官方下载页..." else "请完成验证并点击下载按钮",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )

                if (runtime == null || runtimeState.status == GeckoRuntimeStatus.FAILED) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = runtimeState.errorMessage ?: "Gecko 内核初始化失败，请改用外部浏览器或手动填入短码。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            OutlinedButton(onClick = onFallbackExternalRelay) {
                                Text(text = "改用外部浏览器打开")
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val gv = GeckoView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                                try {
                                    val desktopUa = GeckoDesktopIdentity.buildDesktopFirefoxUserAgent()
                                    Log.i("AICL_GECKO", "GeckoSession Desktop Firefox UA: $desktopUa")
                                    val sessionSettings = GeckoSessionSettings.Builder()
                                        .usePrivateMode(false)
                                        .useTrackingProtection(false)
                                        .allowJavascript(true)
                                        .suspendMediaWhenInactive(false)
                                        .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                                        .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                                        .displayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER)
                                        .userAgentOverride(desktopUa)
                                        .build()
                                    val session = GeckoSession(sessionSettings).apply {
                                        contentBlockingDelegate = object : ContentBlocking.Delegate {
                                            override fun onContentBlocked(
                                                session: GeckoSession,
                                                event: ContentBlocking.BlockEvent
                                            ) {
                                                Log.w(
                                                    "AICL_GECKO",
                                                    "onContentBlocked uri=${event.uri} antiTracking=${event.antiTrackingCategory}"
                                                )
                                            }
                                        }
                                        progressDelegate = object : GeckoSession.ProgressDelegate {
                                            override fun onProgressChange(session: GeckoSession, p: Int) {
                                                if (!disposed.get()) {
                                                    mainHandler.post { progress = p.coerceIn(0, 100) }
                                                }
                                            }

                                            override fun onPageStart(session: GeckoSession, url: String) {
                                                if (!disposed.get()) {
                                                    mainHandler.post {
                                                        isLoading = true
                                                        errorMessage = null
                                                    }
                                                }
                                            }

                                            override fun onPageStop(session: GeckoSession, success: Boolean) {
                                                if (!disposed.get()) {
                                                    mainHandler.post { isLoading = false }
                                                }
                                            }
                                        }
                                        navigationDelegate = object : GeckoSession.NavigationDelegate {
                                            override fun onLocationChange(
                                                session: GeckoSession,
                                                url: String?,
                                                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                                                hasUserGesture: Boolean
                                            ) {
                                                tryCaptureUrl(url)
                                            }

                                            override fun onLoadRequest(
                                                session: GeckoSession,
                                                request: GeckoSession.NavigationDelegate.LoadRequest
                                            ): GeckoResult<AllowOrDeny> {
                                                return if (GeckoDesktopIdentity.shouldInterceptAsShortCode(request.uri) &&
                                                    tryCaptureUrl(request.uri)
                                                ) {
                                                    GeckoResult.fromValue(AllowOrDeny.DENY)
                                                } else {
                                                    GeckoResult.fromValue(AllowOrDeny.ALLOW)
                                                }
                                            }

                                            override fun onLoadError(
                                                session: GeckoSession,
                                                uri: String?,
                                                error: WebRequestError
                                            ): GeckoResult<String>? {
                                                Log.e(
                                                    "AICL_GECKO",
                                                    "onLoadError uri=$uri category=${error.category} code=${error.code}"
                                                )
                                                if (!disposed.get()) {
                                                    mainHandler.post {
                                                        isLoading = false
                                                        errorMessage = "页面加载失败（code=${error.code}），可重试或改用外部浏览器打开"
                                                    }
                                                }
                                                return null
                                            }
                                        }
                                        contentDelegate = object : GeckoSession.ContentDelegate {
                                            override fun onExternalResponse(
                                                session: GeckoSession,
                                                response: WebResponse
                                            ) {
                                                try {
                                                    val uri = response.uri
                                                    val contentDisp = response.headers.entries
                                                        .firstOrNull { it.key.equals("content-disposition", ignoreCase = true) }
                                                        ?.value
                                                    val fileName = GeckoDesktopIdentity.extractFileNameFromContentDisposition(
                                                        contentDisposition = contentDisp,
                                                        fallbackUrl = uri
                                                    )
                                                    if (!tryCaptureUrl(uri) &&
                                                        uri.isNotBlank() &&
                                                        !disposed.get() &&
                                                        completed.compareAndSet(false, true)
                                                    ) {
                                                        mainHandler.post {
                                                            if (!disposed.get()) {
                                                                onDirectDownloadUrl(uri, fileName)
                                                            }
                                                        }
                                                    }
                                                } catch (t: Throwable) {
                                                    Log.e("AICL_GECKO", "Failed handling Gecko onExternalResponse", t)
                                                } finally {
                                                    runCatching { response.body?.close() }
                                                }
                                            }
                                        }
                                    }
                                    session.open(runtime)
                                    session.setActive(true)
                                    gv.setSession(session)
                                    sessionHolder.ref = session
                                    viewHolder.ref = gv
                                    session.loadUri(GameSourceResolver.OFFICIAL_WIN_URL)
                                } catch (t: Throwable) {
                                    Log.e("AICL_GECKO", "Failed creating GeckoSession", t)
                                    mainHandler.post {
                                        isLoading = false
                                        errorMessage = "Gecko 会话启动失败，请改用外部浏览器打开"
                                    }
                                }
                                gv
                            },
                            onRelease = { released ->
                                runCatching { released.releaseSession() }
                            }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            try {
                                errorMessage = null
                                isLoading = true
                                sessionHolder.ref?.apply {
                                    setActive(true)
                                    loadUri(GameSourceResolver.OFFICIAL_WIN_URL)
                                }
                            } catch (t: Throwable) {
                                Log.e("AICL_GECKO", "Failed retrying GeckoSession loadUri", t)
                                errorMessage = "重试失败，请改用外部浏览器打开"
                                isLoading = false
                            }
                        }
                    ) {
                        Text(text = "重试")
                    }
                    OutlinedButton(onClick = onFallbackExternalRelay) {
                        Text(text = "改用外部浏览器打开")
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
