package com.aliceincradle.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.data.DownloadTaskItem
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.SystemTelemetry
import com.aliceincradle.launcher.data.ThermalStats
import java.util.Locale

@Composable
fun TelemetryDashboardCard(
    telemetry: SystemTelemetry,
    onGaugeClick: (TelemetryDialogType) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AICL Beta Ver.B",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "点击仪表查看明细",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularMetricGauge(
                        label = "运存",
                        percent = telemetry.memory.usagePercent,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = { onGaugeClick(TelemetryDialogType.MEMORY) }
                    )
                    CircularMetricGauge(
                        label = "CPU",
                        percent = telemetry.cpu.usagePercent,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        onClick = { onGaugeClick(TelemetryDialogType.CPU) }
                    )
                    CircularMetricGauge(
                        label = "GPU",
                        percent = telemetry.gpu.usagePercent,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        onClick = { onGaugeClick(TelemetryDialogType.GPU) }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                ThermometerGauge(
                    thermal = telemetry.thermal,
                    onClick = { onGaugeClick(TelemetryDialogType.THERMAL) }
                )
            }
        }
    }
}

@Composable
private fun CircularMetricGauge(
    label: String,
    percent: Float,
    accentColor: Color,
    onClick: () -> Unit
) {
    val clamped = percent.coerceIn(0f, 1f)
    val percentInt = (clamped * 100).toInt()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .semantics { contentDescription = "$label 占用率 $percentInt%" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(64.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 7.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = accentColor,
                    startAngle = -90f,
                    sweepAngle = 360f * clamped,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            Text(
                text = "$percentInt%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThermometerGauge(
    thermal: ThermalStats,
    onClick: () -> Unit
) {
    val tempFraction = ((thermal.headlineTempC - 20f) / 70f).coerceIn(0.08f, 1f)
    val gaugeColor = when {
        thermal.headlineTempC >= 75 -> MaterialTheme.colorScheme.error
        thermal.headlineTempC >= 55 -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "温度 ${thermal.headlineTempC} 摄氏度" },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(10.dp)
                        .height(44.dp)
                ) {
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                        size = size
                    )
                    val fillHeight = size.height * tempFraction
                    drawRoundRect(
                        color = gaugeColor,
                        topLeft = Offset(0f, size.height - fillHeight),
                        size = Size(size.width, fillHeight),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Thermostat,
                        contentDescription = null,
                        tint = gaugeColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "${thermal.headlineTempC}°C",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = "温度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun GameStatusCard(
    processState: GameProcessState,
    onNormalExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = processState.isAlive) {
        ElevatedCard(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32))
                    )
                    Column {
                        Text(
                            text = "${processState.versionName} 正在运行",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "PID: ${processState.pid ?: "--"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onNormalExit) {
                    Text(text = "模拟返回退出")
                }
            }
        }
    }
}

@Composable
fun FloatingActionButtonGroup(
    visibility: FabGroupVisibility,
    downloadCount: Int,
    onLaunchClick: () -> Unit,
    onTerminateClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = visibility.isContainerVisible, modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (visibility.showDownloads) {
                SmallFloatingActionButton(
                    onClick = onDownloadsClick,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "下载中列表"
                        )
                        Text(
                            text = "下载中 ($downloadCount)",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            if (visibility.showTerminate) {
                FloatingActionButton(
                    onClick = onTerminateClick,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "终止进程"
                    )
                }
            }

            if (visibility.showLaunch) {
                FloatingActionButton(
                    onClick = onLaunchClick
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "启动"
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryDetailDialog(
    dialogType: TelemetryDialogType,
    telemetry: SystemTelemetry,
    onDismiss: () -> Unit
) {
    if (dialogType == TelemetryDialogType.NONE) return

    val title = when (dialogType) {
        TelemetryDialogType.MEMORY -> "运存占用率明细 (${(telemetry.memory.usagePercent * 100).toInt()}%)"
        TelemetryDialogType.CPU -> "CPU 核心实时状态 (${(telemetry.cpu.usagePercent * 100).toInt()}%)"
        TelemetryDialogType.GPU -> "GPU 实时状态 (${(telemetry.gpu.usagePercent * 100).toInt()}%)"
        TelemetryDialogType.THERMAL -> "传感器温度明细"
        TelemetryDialogType.NONE -> ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (dialogType) {
                    TelemetryDialogType.MEMORY -> {
                        val mem = telemetry.memory
                        DialogKeyValueRow("启动器占用", "${mem.launcherMb} MB")
                        DialogKeyValueRow("游戏占用", "${mem.gameMb} MB")
                        DialogKeyValueRow("PML 占用", "${mem.pmlMb} MB")
                        HorizontalDivider()
                        DialogKeyValueRow("容器总占用", "${mem.containerTotalMb} MB", emphasize = true)
                    }
                    TelemetryDialogType.CPU -> {
                        telemetry.cpu.cores.forEach { core ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Core #${core.coreIndex}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${core.freqMhz} MHz · ${core.usagePercent}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (core.usagePercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    TelemetryDialogType.GPU -> {
                        val gpu = telemetry.gpu
                        DialogKeyValueRow("渲染器", gpu.renderer)
                        DialogKeyValueRow("GPU 实时频率", "${gpu.freqMhz} MHz", emphasize = true)
                        DialogKeyValueRow("峰值参考频率", "${gpu.maxFreqMhz} MHz")
                        DialogKeyValueRow("GPU 占用率", "${(gpu.usagePercent * 100).toInt()}%")
                    }
                    TelemetryDialogType.THERMAL -> {
                        telemetry.thermal.sensors.forEach { sensor ->
                            DialogKeyValueRow(
                                label = sensor.name,
                                value = String.format(Locale.US, "%.1f °C", sensor.tempC)
                            )
                        }
                    }
                    TelemetryDialogType.NONE -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        }
    )
}

@Composable
fun DownloadQueueDialog(
    isOpen: Boolean,
    tasks: List<DownloadTaskItem>,
    onDismiss: () -> Unit
) {
    if (!isOpen) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "下载中任务列表 (${tasks.size})", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tasks.isEmpty()) {
                    Text(text = "当前无下载任务", style = MaterialTheme.typography.bodyMedium)
                } else {
                    tasks.forEach { task ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DialogKeyValueRow(task.name, "${task.progressPercent}%")
                            LinearProgressIndicator(
                                progress = { (task.progressPercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        }
    )
}

@Composable
private fun DialogKeyValueRow(
    label: String,
    value: String,
    emphasize: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasize) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium
        )
    }
}
