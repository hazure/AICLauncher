package com.aliceincradle.launcher.ui

import androidx.compose.foundation.layout.Arrangement
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
import com.aliceincradle.launcher.data.DeviceInfo
import com.aliceincradle.launcher.data.DownloadTaskItem
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.SystemTelemetry

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        floatingActionButton = {
            if (uiState.selectedTab != MainTab.FILES) {
                FloatingActionButtonGroup(
                    visibility = uiState.fabVisibility,
                    downloadCount = uiState.downloadTasks.size,
                    onLaunchClick = { viewModel.launchGame() },
                    onTerminateClick = { viewModel.terminateGame() },
                    onDownloadsClick = { viewModel.openDownloadSheet() }
                )
            }
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
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (uiState.selectedTab) {
            MainTab.HOME -> HomeTabContent(
                deviceInfo = uiState.deviceInfo,
                telemetry = uiState.telemetry,
                gameProcess = uiState.gameProcess,
                downloadTasks = uiState.downloadTasks,
                onGaugeClick = { viewModel.openTelemetryDialog(it) },
                onNormalExit = { viewModel.exitGameNormally() },
                onToggleSampleDownloads = { viewModel.toggleSampleDownloads() },
                modifier = contentModifier
            )

            MainTab.DOWNLOAD -> DownloadScreen(
                state = uiState.downloadPage,
                onQueryChange = { viewModel.updateDownloadQuery(it) },
                onAppSelect = { viewModel.selectDownloadApp(it) },
                onVersionSelect = { viewModel.selectDownloadVersion(it) },
                onCategorySelect = { viewModel.selectDownloadCategory(it) },
                onIncludeCompatibleChange = { viewModel.toggleIncludeCompatibleMods(it) },
                onIncludeFeaturedChange = { viewModel.toggleIncludeFeaturedMods(it) },
                onDownloadClick = { viewModel.triggerCatalogDownload(it) },
                onDetailClick = { viewModel.openCatalogItemDetail(it) },
                onDismissDetail = { viewModel.dismissCatalogItemDetail() },
                modifier = contentModifier
            )

            MainTab.FILES -> FolderScreen(
                state = uiState.folderPage,
                onSelectItem = { viewModel.selectFolderItem(it) },
                onActionClick = { viewModel.performFolderAction(it) },
                onDismissItemInfo = { viewModel.dismissFolderItemInfo() },
                modifier = contentModifier
            )

            MainTab.SETTINGS -> SettingsScreen(
                state = uiState.settingsPage,
                onPythonVersionSelect = { viewModel.selectPythonVersion(it) },
                onPythonWarningClick = { viewModel.triggerPythonDownloadHint() },
                onPythonDirSelect = { viewModel.selectPythonDir(it) },
                onOpenCustomPathDialog = { viewModel.openCustomPathDialog() },
                onCustomPathDraftChange = { viewModel.updateCustomPathDraft(it) },
                onConfirmCustomPath = { viewModel.confirmCustomPath() },
                onDismissCustomPathDialog = { viewModel.dismissCustomPathDialog() },
                onReservedSectionClick = { viewModel.triggerReservedPythonAction() },
                modifier = contentModifier
            )
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
private fun HomeTabContent(
    deviceInfo: DeviceInfo,
    telemetry: SystemTelemetry,
    gameProcess: GameProcessState,
    downloadTasks: List<DownloadTaskItem>,
    onGaugeClick: (TelemetryDialogType) -> Unit,
    onNormalExit: () -> Unit,
    onToggleSampleDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TelemetryDashboardCard(
            telemetry = telemetry,
            onGaugeClick = onGaugeClick
        )

        GameStatusCard(
            processState = gameProcess,
            onNormalExit = onNormalExit
        )

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
                InfoRow(label = "CPU", value = deviceInfo.cpu)
                InfoRow(label = "GPU", value = deviceInfo.gpu)
                InfoRow(label = "内存", value = deviceInfo.memory)
                InfoRow(label = "Android", value = deviceInfo.androidVersion)
                InfoRow(label = "Python", value = deviceInfo.pythonVersion)
                InfoRow(label = "AIC 版本", value = deviceInfo.aicVersion)
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
                        text = if (downloadTasks.isEmpty()) "当前无下载任务（按钮隐藏）" else "进行中：${downloadTasks.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onToggleSampleDownloads) {
                    Text(text = if (downloadTasks.isEmpty()) "模拟下载" else "清空下载")
                }
            }
        }
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
