package com.aliceincradle.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadHardwareInfo(context)
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    Toast.makeText(context, "启动器就绪（Beta Ver.A 占位）", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "开始游戏")
            }
        },
        bottomBar = {
            NavigationBar {
                NavTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        NavTab.HOME -> Icons.Filled.Home
                        NavTab.DOWNLOAD -> Icons.Filled.Download
                        NavTab.FOLDER -> Icons.Filled.Folder
                        NavTab.SETTINGS -> Icons.Filled.Settings
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
        ) {
            InfoPanelCard(
                isLoading = uiState.isLoading,
                info = uiState.deviceInfo
            )
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (uiState.selectedTab) {
                    NavTab.HOME -> {
                        Text(
                            text = "日志 / Mod 列表预留区域",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Text(
                            text = "${uiState.selectedTab.label}（开发中）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPanelCard(
    isLoading: Boolean,
    info: com.aliceincradle.launcher.data.DeviceInfo?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "AICL Beta Ver.A",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (isLoading || info == null) {
                Text(text = "正在探测设备硬件与环境信息...", style = MaterialTheme.typography.bodyMedium)
            } else {
                InfoRow("CPU", info.cpuInfo)
                InfoRow("GPU", info.gpuInfo)
                InfoRow("内存", info.memoryInfo)
                InfoRow("Android", info.androidVersion)
                InfoRow("Python", info.pythonVersion)
                InfoRow("AIC 版本", info.aicVersion)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
