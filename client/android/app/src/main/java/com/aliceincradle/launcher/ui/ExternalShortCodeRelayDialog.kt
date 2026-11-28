package com.aliceincradle.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aliceincradle.launcher.bridge.ExternalBrowserLauncher
import com.aliceincradle.launcher.bridge.ExternalLaunchResult
import com.aliceincradle.launcher.bridge.ExtractedShortCode
import com.aliceincradle.launcher.bridge.GameSourceResolver
import com.aliceincradle.launcher.bridge.ShortCodeRelayExtractor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private object ShortCodeRelayMemory {
    private val lock = Any()
    private val consumedFingerprints = mutableSetOf<String>()

    fun snapshot(): Set<String> = synchronized(lock) { consumedFingerprints.toSet() }

    fun markConsumed(fingerprint: String) {
        synchronized(lock) {
            consumedFingerprints.add(fingerprint)
        }
    }
}

/**
 * Module C: Guided external browser / Custom Tabs relay with ON_RESUME clipboard capture
 * and re-entrancy protection.
 */
@Composable
fun ExternalShortCodeRelayDialog(
    onCaptured: (shortCode: String, fileName: String) -> Unit,
    onFallbackManual: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionToken = remember { AtomicInteger(0) }
    val completed = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }

    var launchStatusText by remember {
        mutableStateOf("请通过外部浏览器完成官方下载页验证后复制链接或短码。")
    }
    var detectedCandidate by remember { mutableStateOf<ExtractedShortCode?>(null) }

    fun scanClipboard(token: Int = sessionToken.get()) {
        if (disposed.get() || completed.get() || token != sessionToken.get()) return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            val extracted = ShortCodeRelayExtractor.extract(
                rawText = clipText,
                consumedFingerprints = ShortCodeRelayMemory.snapshot()
            )
            if (!disposed.get() && !completed.get() && token == sessionToken.get()) {
                detectedCandidate = extracted
            }
        } catch (t: Throwable) {
            Log.w("AICL_RELAY", "Failed reading clipboard", t)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scanClipboard()
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
            sessionToken.incrementAndGet()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "外部浏览器获取短码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "1. 点击下方按钮打开外部浏览器，完成年龄/人机验证并复制下载链接或短码。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        when (val res = ExternalBrowserLauncher.openUrl(context, GameSourceResolver.OFFICIAL_WIN_URL)) {
                            is ExternalLaunchResult.OpenedCustomTabs -> {
                                launchStatusText = "已拉起 Custom Tabs，完成验证并复制后切回 AICL。"
                            }
                            is ExternalLaunchResult.OpenedSystemBrowser -> {
                                launchStatusText = "已拉起系统浏览器，完成验证并复制后切回 AICL。"
                            }
                            is ExternalLaunchResult.Unavailable -> {
                                launchStatusText = res.reason
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "打开外部浏览器（Custom Tabs）")
                }
                OutlinedButton(
                    onClick = {
                        try {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("AICL Official URL", GameSourceResolver.OFFICIAL_WIN_URL))
                            launchStatusText = "已复制官方下载页链接，可粘贴到任意浏览器打开。"
                        } catch (t: Throwable) {
                            Log.w("AICL_RELAY", "Failed copying official URL", t)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "复制官方下载页链接")
                }
                Text(
                    text = "2. 切回 AICL 后将自动识别剪贴板；也可点击下方「手动填入短码」。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = launchStatusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { scanClipboard() }) {
                    Text(text = "重新检查剪贴板")
                }

                detectedCandidate?.let { candidate ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "检测到短码：${candidate.shortCode}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    if (!disposed.get() && completed.compareAndSet(false, true)) {
                                        sessionToken.incrementAndGet()
                                        ShortCodeRelayMemory.markConsumed(candidate.fingerprint)
                                        onCaptured(candidate.shortCode, candidate.fileName)
                                    }
                                }
                            ) {
                                Text(text = "填入并下载")
                            }
                            TextButton(
                                onClick = {
                                    ShortCodeRelayMemory.markConsumed(candidate.fingerprint)
                                    detectedCandidate = null
                                }
                            ) {
                                Text(text = "忽略")
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
