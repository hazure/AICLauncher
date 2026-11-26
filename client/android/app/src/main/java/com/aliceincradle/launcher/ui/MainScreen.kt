package com.aliceincradle.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val info = uiState.deviceInfo

    Scaffold(
        floatingActionButton = {
            FloatingActionButtonGroup(
                visibility = uiState.fabVisibility,
                downloadCount = uiState.downloadTasks.size,
                onLaunchClick = { viewModel.launchGame() },
                onTerminateClick = { viewModel.terminateGame() },
                onDownloadsClick = { viewModel.openDownloadSheet() }
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        MainTab.HOME -> Icons.Filled.Home
                        MainTab.DOWNLOAD -> Icons.Filled.Download
                        MainTab.FILES -> Icons.Filled.Folder
                        MainTab.SETTINGS -> Icons.Filled.Settings
                    }
                    NavigationBarItem(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(imageVector = icon, contentDescription = tab.label) },
                        label = { Text(text = tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TelemetryDashboardCard(
                telemetry = uiState.telemetry,
                onGaugeClick = { dialogType -> viewModel.openTelemetryDialog(dialogType) }
            )

            GameStatusCard(
                processState = uiState.gameProcess,
                onNormalExit = { viewModel.exitGameNormally() }
            )

            when (uiState.selectedTab) {
                MainTab.HOME -> {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "硬件与运行环境（动态探测）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            InfoRow(label = "CPU", value = info.cpu)
                            InfoRow(label = "GPU", value = info.gpu)
                            InfoRow(label = "内存", value = info.memory)
                            InfoRow(label = "Android", value = info.androidVersion)
                            InfoRow(label = "Python", value = info.pythonVersion)
                            InfoRow(label = "AIC 版本", value = info.aicVersion)
                        }
                    }

                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "下载队列调试",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (uiState.downloadTasks.isEmpty()) "当前无下载任务（按钮隐藏）" else "进行中：${uiState.downloadTasks.size} 项",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = { viewModel.toggleSampleDownloads() }) {
                                Text(text = if (uiState.downloadTasks.isEmpty()) "模拟下载" else "清空下载")
                            }
                        }
                    }
                }

                MainTab.DOWNLOAD -> {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "下载中心",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                OutlinedButton(onClick = { viewModel.toggleSampleDownloads() }) {
                                    Text(text = if (uiState.downloadTasks.isEmpty()) "添加模拟任务" else "清空队列")
                                }
                            }
                            if (uiState.downloadTasks.isEmpty()) {
                                Text(
                                    text = "暂无下载任务",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                uiState.downloadTasks.forEach { task ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        InfoRow(label = task.name, value = "${task.progressPercent}%")
                                        LinearProgressIndicator(
                                            progress = { (task.progressPercent / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                MainTab.FILES -> {
                    PlaceholderTabCard(text = "文件夹管理（占位）")
                }

                MainTab.SETTINGS -> {
                    PlaceholderTabCard(text = "设置（占位）")
                }
            }
        }
    }

    TelemetryDetailDialog(
        dialogType = uiState.activeDialog,
        telemetry = uiState.telemetry,
        onDismiss = { viewModel.dismissTelemetryDialog() }
    )

    DownloadQueueDialog(
        isOpen = uiState.isDownloadSheetOpen,
        tasks = uiState.downloadTasks,
        onDismiss = { viewModel.dismissDownloadSheet() }
    )
}

@Composable
private fun PlaceholderTabCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
