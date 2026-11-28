package com.aliceincradle.launcher.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
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
    val sessionStarted = remember { AtomicBoolean(false) }
    val sessionHolder = remember { GeckoRefHolder<GeckoSession>() }
    val viewHolder = remember { GeckoRefHolder<GeckoView>() }

    var progress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val runtime = remember(context) { GeckoRuntimeManager.getOrCreate(context) }

    fun activateSession(session: GeckoSession, gv: GeckoView?) {
        if (disposed.get()) return
        try {
            session.setActive(true)
            session.setFocused(true)
            runtime?.webExtensionController?.setTabActive(session, true)
            gv?.let { view ->
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                if (!view.isFocused) {
                    view.requestFocus()
                }
            }
        } catch (t: Throwable) {
            Log.w("AICL_GECKO", "Failed activating GeckoSession", t)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val session = sessionHolder.ref ?: return@LifecycleEventObserver
            try {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> activateSession(session, viewHolder.ref)
                    Lifecycle.Event.ON_PAUSE -> {
                        session.setActive(false)
                        runtime?.webExtensionController?.setTabActive(session, false)
                    }
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
                sessionHolder.ref?.let { s ->
                    runtime?.webExtensionController?.setTabActive(s, false)
                }
            } catch (t: Throwable) {
                Log.w("AICL_GECKO", "Failed deactivating GeckoSession tab", t)
            }
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

    BackHandler(enabled = true, onBack = onDismiss)

    fun startSessionIfReady(gv: GeckoView) {
        val activeRuntime = runtime ?: return
        if (disposed.get() || sessionStarted.get()) return
        if (!gv.isAttachedToWindow || gv.width <= 0 || gv.height <= 0) return
        if (!sessionStarted.compareAndSet(false, true)) return

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
                                activateSession(session, viewHolder.ref)
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
                        mainHandler.post { activateSession(session, viewHolder.ref) }
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
            session.open(activeRuntime)
            gv.setSession(session)
            activateSession(session, gv)
            sessionHolder.ref = session
            viewHolder.ref = gv
            val initialLoadTriggered = AtomicBoolean(false)
            fun triggerInitialLoad() {
                if (!initialLoadTriggered.compareAndSet(false, true) || disposed.get()) return
                gv.post {
                    if (!disposed.get()) {
                        try {
                            activateSession(session, gv)
                            session.loadUri(GameSourceResolver.OFFICIAL_WIN_URL)
                        } catch (t: Throwable) {
                            Log.e("AICL_GECKO", "Failed loading initial GeckoSession URI", t)
                            isLoading = false
                            errorMessage = "页面加载启动失败，请重试或改用外部浏览器打开"
                        }
                    }
                }
            }
            GeckoRuntimeManager.ensureSvgCompatExtension(activeRuntime) {
                triggerInitialLoad()
            }
            mainHandler.postDelayed({ triggerInitialLoad() }, 500L)
        } catch (t: Throwable) {
            Log.e("AICL_GECKO", "Failed creating GeckoSession", t)
            mainHandler.post {
                isLoading = false
                errorMessage = "Gecko 会话启动失败，请改用外部浏览器打开"
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "应用内下载验证（Gecko）",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onDismiss) {
                    Text(text = "取消", maxLines = 1, softWrap = false)
                }
            }
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
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
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val gv = GeckoView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                isFocusable = true
                                isFocusableInTouchMode = true
                            }
                            viewHolder.ref = gv
                            val layoutListener = object : View.OnLayoutChangeListener {
                                override fun onLayoutChange(
                                    v: View,
                                    left: Int,
                                    top: Int,
                                    right: Int,
                                    bottom: Int,
                                    oldLeft: Int,
                                    oldTop: Int,
                                    oldRight: Int,
                                    oldBottom: Int
                                ) {
                                    startSessionIfReady(gv)
                                    if (sessionStarted.get()) {
                                        gv.removeOnLayoutChangeListener(this)
                                    }
                                }
                            }
                            gv.addOnLayoutChangeListener(layoutListener)
                            gv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: View) {
                                    gv.post { startSessionIfReady(gv) }
                                }

                                override fun onViewDetachedFromWindow(v: View) {
                                    gv.removeOnLayoutChangeListener(layoutListener)
                                }
                            })
                            gv
                        },
                        onRelease = { released ->
                            runCatching { released.releaseSession() }
                        }
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            try {
                                errorMessage = null
                                isLoading = true
                                val existingSession = sessionHolder.ref
                                if (existingSession != null) {
                                    activateSession(existingSession, viewHolder.ref)
                                    existingSession.loadUri(GameSourceResolver.OFFICIAL_WIN_URL)
                                } else {
                                    viewHolder.ref?.let { startSessionIfReady(it) }
                                }
                            } catch (t: Throwable) {
                                Log.e("AICL_GECKO", "Failed retrying GeckoSession loadUri", t)
                                errorMessage = "重试失败，请改用外部浏览器打开"
                                isLoading = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "重试",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onFallbackManual,
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Text(
                            text = "手动填入短码",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                OutlinedButton(
                    onClick = onFallbackExternalRelay,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "改用外部浏览器打开",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
