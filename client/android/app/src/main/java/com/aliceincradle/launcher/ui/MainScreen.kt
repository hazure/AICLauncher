package com.aliceincradle.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.data.DeviceInfo
import com.aliceincradle.launcher.data.SystemTelemetry

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        floatingActionButton = {
            if (uiState.selectedTab != MainTab.FILES) {
                FloatingActionButton(
                    onClick = {
                        Toast.makeText(context, "游戏启动逻辑待接入", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("home_play_fab")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "启动"
                    )
                }
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
                onGaugeClick = { viewModel.openTelemetryDialog(it) },
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
                onSelectPane = { viewModel.selectFilePane(it) },
                onToggleSelection = { side, id -> viewModel.toggleFileSelection(side, id) },
                onRenameClick = { viewModel.openRenameDialog() },
                onDeleteClick = { viewModel.openDeleteConfirmDialog() },
                onCopyClick = { viewModel.startCopySelectedFiles() },
                onInfoClick = { viewModel.openPropertiesDialog() },
                onExportClick = { viewModel.openExportSheet() },
                onRenameDraftChange = { viewModel.updateRenameDraft(it) },
                onConfirmRename = { viewModel.confirmRename() },
                onConfirmDelete = { viewModel.confirmDeleteSelected() },
                onOpenPermissionEdit = { viewModel.openPermissionEditDialog() },
                onUpdatePermissions = { viewModel.updatePermissionFlags(it) },
                onOctalInputChange = { viewModel.updatePermissionOctalInput(it) },
                onRecursiveToggle = { viewModel.togglePermissionRecursive(it) },
                onConfirmPermissionEdit = { viewModel.confirmPermissionEdit() },
                onExportToDownload = { viewModel.startExportToDownload() },
                onCustomExportToUri = { viewModel.startCustomExportToUri(it) },
                onCancelFileOp = { viewModel.cancelFileOperation() },
                onDismissDialog = { viewModel.dismissFileManagerDialog() },
                modifier = contentModifier
            )

            MainTab.SETTINGS -> SettingsScreen(
                state = uiState.settingsPage,
                onThemeSelect = { viewModel.selectThemeMode(it) },
                onShowHiddenFilesChange = { viewModel.toggleShowHiddenFiles(it) },
                onAutoCheckUpdateChange = { viewModel.toggleAutoCheckUpdate(it) },
                onRootfsSelect = { viewModel.selectRootfsType(it) },
                onGraphicsRendererSelect = { viewModel.selectGraphicsRenderer(it) },
                onMountPathPicked = { viewModel.setContainerMountPath(it) },
                onClearMountPath = { viewModel.clearContainerMountPath() },
                onCpuLimitSelect = { viewModel.selectCpuCoreLimit(it) },
                onMemLimitSelect = { viewModel.selectMemLimitMB(it) },
                onOpenResetContainerDialog = { viewModel.openResetContainerDialog() },
                onConfirmResetContainer = { viewModel.confirmResetContainerSettings() },
                onDismissResetContainerDialog = { viewModel.dismissResetContainerDialog() },
                onOpenEnvVarsDialog = { viewModel.openEnvVarsDialog() },
                onEnvVarsDraftChange = { viewModel.updateEnvVarsDraft(it) },
                onConfirmEnvVars = { viewModel.confirmEnvVars() },
                onDismissEnvVarsDialog = { viewModel.dismissEnvVarsDialog() },
                onPythonVersionSelect = { viewModel.selectPythonVersion(it) },
                onPythonWarningClick = { viewModel.triggerPythonDownloadHint() },
                onPythonDirSelect = { viewModel.selectPythonDir(it) },
                onPythonDirPicked = { viewModel.updatePythonDirFromSaf(it) },
                onOpenCustomPathDialog = { viewModel.openCustomPathDialog() },
                onCustomPathDraftChange = { viewModel.updateCustomPathDraft(it) },
                onConfirmCustomPath = { viewModel.confirmCustomPath() },
                onDismissCustomPathDialog = { viewModel.dismissCustomPathDialog() },
                onCleanTempFiles = { viewModel.triggerCleanTempFiles() },
                onExportLogs = { viewModel.triggerExportLogs() },
                onOpenDisclaimerDialog = { viewModel.openDisclaimerDialog() },
                onDismissDisclaimerDialog = { viewModel.dismissDisclaimerDialog() },
                onOpenLicensesDialog = { viewModel.openLicensesDialog() },
                onDismissLicensesDialog = { viewModel.dismissLicensesDialog() },
                onVersionRowClick = { viewModel.onVersionRowClick() },
                onLogLevelSelect = { viewModel.selectLogLevel(it) },
                onForceSoftwareRenderingChange = { viewModel.toggleForceSoftwareRendering(it) },
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
}

@Composable
private fun HomeTabContent(
    deviceInfo: DeviceInfo,
    telemetry: SystemTelemetry,
    onGaugeClick: (TelemetryDialogType) -> Unit,
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
                InfoRow(label = "AIC 目录", value = deviceInfo.aicVersion)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
