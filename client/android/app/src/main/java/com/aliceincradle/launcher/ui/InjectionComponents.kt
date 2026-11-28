package com.aliceincradle.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.bridge.EnvironmentStatus
import com.aliceincradle.launcher.bridge.InjectionCheckpoint
import com.aliceincradle.launcher.bridge.InjectionPlan
import com.aliceincradle.launcher.bridge.InjectionUiState
import com.aliceincradle.launcher.data.formatFileSize

@Composable
fun InjectionStatusCard(
    state: InjectionUiState,
    onPickGameDir: () -> Unit,
    onRefreshProbe: () -> Unit,
    onRequestInject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusLabel = when (state.envStatus) {
        EnvironmentStatus.READY -> "就绪（可注入 BepInEx）"
        EnvironmentStatus.ALREADY_INJECTED -> "已注入（BepInEx 就绪）"
        EnvironmentStatus.GAME_NOT_FOUND -> "未检测到游戏本体"
        EnvironmentStatus.FRAMEWORK_MISSING -> "缺少本地 BepInEx 框架包"
        EnvironmentStatus.PERMISSION_DENIED -> "目标目录无写入权限"
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "BepInEx 注入状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "状态：$statusLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.envStatus == EnvironmentStatus.READY ||
                    state.envStatus == EnvironmentStatus.ALREADY_INJECTED
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.testTag("injection_env_status")
            )
            state.gameRoot?.let { root ->
                Text(
                    text = "本体：${root.exeName} (${root.runtime}) · ${root.rootDir.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.frameworkSource?.let { fw ->
                Text(
                    text = "框架：${fw.sourceFile.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPickGameDir,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "选择游戏目录", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onRefreshProbe,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "重新探测", maxLines = 1)
                }
            }
            Button(
                onClick = onRequestInject,
                enabled = state.envStatus == EnvironmentStatus.READY && !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("injection_action_button")
            ) {
                Text(
                    text = when (state.envStatus) {
                        EnvironmentStatus.ALREADY_INJECTED -> "已完成注入"
                        EnvironmentStatus.READY -> "注入 BepInEx 框架"
                        else -> "注入不可用（请先满足环境条件）"
                    }
                )
            }
            state.lastReport?.let { report ->
                Text(
                    text = "最近校验：${report.status} · 耗时 ${report.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InjectConfirmDialog(
    plan: InjectionPlan,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "确认注入 BepInEx") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "目标路径：${plan.gameRoot.rootDir.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "预计占用：${formatFileSize(plan.estimatedBytes)} · 共 ${plan.plannedFiles.size} 个文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    plan.plannedFiles.take(40).forEach { rel ->
                        Text(
                            text = "• $rel",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (plan.plannedFiles.size > 40) {
                        Text(
                            text = "... 其余 ${plan.plannedFiles.size - 40} 个文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = "确认注入")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "取消")
            }
        }
    )
}

@Composable
fun CheckpointRecoveryDialog(
    checkpoint: InjectionCheckpoint,
    onRecover: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = "检测到未完成的注入") },
        text = {
            Text(
                text = "上次注入在 ${checkpoint.phase} 阶段中断（已写入 ${checkpoint.filesWritten.size} 个半成品文件）。点击“清理并恢复”以删除半成品。",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onRecover) {
                Text(text = "清理并恢复")
            }
        }
    )
}
