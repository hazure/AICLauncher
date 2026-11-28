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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aliceincradle.launcher.bridge.ExternalBrowserLauncher
import com.aliceincradle.launcher.bridge.GameSourceResolver
import com.aliceincradle.launcher.bridge.ShortCodeRelayStateMachine

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
 * Module C: Guided external browser / Custom Tabs relay backed by [ShortCodeRelayStateMachine]
 * with ON_RESUME clipboard capture, inline manual fallback, and re-entrancy protection.
 */
@Composable
fun ExternalShortCodeRelayDialog(
    onCaptured: (shortCode: String, fileName: String) -> Unit,
    onFallbackManual: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val stateMachine = remember { ShortCodeRelayStateMachine(ShortCodeRelayMemory.snapshot()) }
    val uiState by stateMachine.state.collectAsState()
    var manualDraft by remember { mutableStateOf("") }

    fun scanClipboard() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            stateMachine.onResumeClipboardText(clipText)
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
            stateMachine.markDisposed()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "外部浏览器获取短码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "1. 点击下方按钮打开外部浏览器，完成年龄/人机验证并复制下载链接或短码。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        val res = ExternalBrowserLauncher.openUrl(context, GameSourceResolver.OFFICIAL_WIN_URL)
                        stateMachine.onExternalLaunch(res)
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
                        } catch (t: Throwable) {
                            Log.w("AICL_RELAY", "Failed copying official URL", t)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "复制官方下载页链接")
                }
                Text(
                    text = "2. 切回 AICL 后将自动识别剪贴板；也可直接在下方手动填入短码。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = uiState.launchStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { scanClipboard() }) {
                    Text(text = "重新检查剪贴板")
                }

                uiState.candidate?.let { candidate ->
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
                                    stateMachine.confirmCandidate()?.let { confirmed ->
                                        ShortCodeRelayMemory.markConsumed(confirmed.fingerprint)
                                        onCaptured(confirmed.shortCode, confirmed.fileName)
                                    }
                                }
                            ) {
                                Text(text = "填入并下载")
                            }
                            TextButton(
                                onClick = {
                                    ShortCodeRelayMemory.markConsumed(candidate.fingerprint)
                                    stateMachine.dismissCandidate()
                                }
                            ) {
                                Text(text = "忽略")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualDraft,
                        onValueChange = { manualDraft = it },
                        label = { Text(text = "手动填入短码") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        enabled = manualDraft.isNotBlank(),
                        onClick = {
                            stateMachine.confirmManualInput(manualDraft)?.let { confirmed ->
                                ShortCodeRelayMemory.markConsumed(confirmed.fingerprint)
                                onCaptured(confirmed.shortCode, confirmed.fileName)
                            }
                        }
                    ) {
                        Text(text = "确认")
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onFallbackManual) {
                Text(text = "高级短码设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}
