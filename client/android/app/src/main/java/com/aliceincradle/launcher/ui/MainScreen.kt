package com.aliceincradle.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.aliceincradle.launcher.data.DownloadQueueManager
import com.aliceincradle.launcher.ui.file.ConfigTemplateRegistry
import com.aliceincradle.launcher.ui.file.FilePickerDialog
import java.io.File
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val snackbarHostState = remember { SnackbarHostState() }
    var showGameDirPicker by remember { mutableStateOf(false) }

    BackHandler(
        enabled = uiState.selectedTab == MainTab.FILES &&
            uiState.folderPage.activeDialog == null &&
            uiState.downloadPage.pendingCaptureItemId == null
    ) {
        viewModel.onFolderBackPressed()
    }

    LaunchedEffect(uiState.injectionState.statusMessage) {
        val msg = uiState.injectionState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissInjectionSnackbar()
        }
    }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            if (uiState.selectedTab != MainTab.FILES) {
                val activeDownloads = uiState.downloadPage.activeDownloadCount
                FloatingActionButton(
                    onClick = {
                        if (activeDownloads > 0) {
                            viewModel.selectTab(MainTab.DOWNLOAD)
                        } else {
                            Toast.makeText(context, "容器启动逻辑尚未接入", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("home_play_fab")
                ) {
                    if (activeDownloads > 0) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    modifier = Modifier.testTag("home_fab_download_badge")
                                ) {
                                    Text(text = activeDownloads.toString())
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "下载中 $activeDownloads"
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "启动"
                        )
                    }
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
                injectionState = uiState.injectionState,
                onGaugeClick = { viewModel.openTelemetryDialog(it) },
                onPickGameDir = { showGameDirPicker = true },
                onRefreshProbe = { viewModel.refreshInjectionState() },
                onRequestInject = { viewModel.requestInjection() },
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
                onCancelDownload = { viewModel.cancelCatalogDownload(it) },
                onOpenDownloadedFile = { item ->
                    val path = item.localFilePath
                    if (!path.isNullOrBlank()) {
                        runCatching {
                            val file = File(path)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "打开或安装"))
                        }.onFailure {
                            Toast.makeText(context, "无法打开文件：$path", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onOpenCienFallback = {
                    runCatching {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(DownloadQueueManager.CIEN_FALLBACK_URL)
                        )
                        context.startActivity(intent)
                    }.onFailure {
                        Toast.makeText(context, "未找到可用浏览器", Toast.LENGTH_SHORT).show()
                    }
                },
                onRefreshCatalog = { viewModel.refreshDownloadCatalog() },
                onDetailClick = { viewModel.openCatalogItemDetail(it) },
                onDismissDetail = { viewModel.dismissCatalogItemDetail() },
                onOpenManualShortCode = { viewModel.openManualShortCodeDialog() },
                modifier = contentModifier
            )

            MainTab.FILES -> FolderScreen(
                state = uiState.folderPage,
                onSelectPane = { viewModel.selectFilePane(it) },
                onToggleSelection = { side, id -> viewModel.toggleFileSelection(side, id) },
                onOpenEntry = { side, item ->
                    viewModel.onPaneFileClicked(side, item) { unknownItem ->
                        val path = unknownItem.path
                        if (path.isNotBlank()) {
                            try {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    File(path)
                                )
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "打开文件"))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "未找到可打开 ${unknownItem.name} 的应用", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(context, "无法打开 ${unknownItem.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onOpenFolder = { side, item -> viewModel.navigateIntoFolder(side, item) },
                onNavigateUp = { side -> viewModel.navigateUp(side) },
                onSwitchPaneFromSaf = { side, uri -> viewModel.switchPaneDirectoryFromSaf(side, uri) },
                onRefreshPermission = { viewModel.refreshAllFilesPermission() },
                onCreateClick = { viewModel.openCreateEntryDialog() },
                onCreateDialogChange = { isFolder, draft -> viewModel.updateCreateEntryDialog(isFolder, draft) },
                onConfirmCreate = { viewModel.confirmCreateEntry() },
                onRenameClick = { viewModel.openRenameDialog() },
                onDeleteClick = { viewModel.openDeleteConfirmDialog() },
                onCopyClick = { viewModel.startCopySelectedFiles() },
                onPasteClick = { viewModel.pasteClipboardToActivePane() },
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
                onEditorContentChange = { viewModel.updateCodeEditorContent(it) },
                onEditorUndo = { viewModel.undoCodeEditor() },
                onEditorRedo = { viewModel.redoCodeEditor() },
                onEditorInsertTab = { viewModel.insertTabInCodeEditor() },
                onEditorSave = { viewModel.saveCodeEditor() },
                onEditorRequestClose = { viewModel.requestCloseCodeEditor() },
                onEditorConfirmDiscard = { viewModel.dismissFileManagerDialog() },
                onEditorDismissDiscard = { viewModel.dismissCodeEditorDiscardConfirm() },
                onConfigFieldChange = { idx, v -> viewModel.updateConfigTemplateField(idx, v) },
                onConfigSave = { viewModel.saveConfigTemplateForm() },
                onConfigUnmatchedDirectEdit = { viewModel.openUnmatchedConfigDirectly() },
                onConfigUnmatchedSearchOnline = { fileName ->
                    try {
                        val url = ConfigTemplateRegistry.buildAllowlistedSearchUrl(fileName)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "未找到可用浏览器", Toast.LENGTH_SHORT).show()
                    }
                },
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
                onOpenManualShortCodeDialog = { viewModel.openManualShortCodeDialog() },
                onManualShortCodeDraftChange = { viewModel.updateManualShortCodeDraft(it) },
                onManualFileNameDraftChange = { viewModel.updateManualFileNameDraft(it) },
                onManualVersionDraftChange = { viewModel.updateManualVersionDraft(it) },
                onConfirmManualShortCode = { viewModel.confirmManualShortCode() },
                onDismissManualShortCodeDialog = { viewModel.dismissManualShortCodeDialog() },
                modifier = contentModifier
            )
        }
    }

    TelemetryDetailDialog(
        dialogType = uiState.activeDialog,
        telemetry = uiState.telemetry,
        onDismiss = { viewModel.dismissTelemetryDialog() }
    )

    uiState.injectionState.pendingPlan?.let { plan ->
        InjectConfirmDialog(
            plan = plan,
            onConfirm = { viewModel.confirmInjection() },
            onCancel = { viewModel.cancelInjectionPlan() }
        )
    }

    uiState.injectionState.pendingCheckpoint?.let { checkpoint ->
        CheckpointRecoveryDialog(
            checkpoint = checkpoint,
            onRecover = { viewModel.recoverInjectionCheckpoint() }
        )
    }

    if (showGameDirPicker) {
        FilePickerDialog(
            initialDir = viewModel.getInitialPickerDir(),
            pickDirectoryOnly = true,
            onConfirm = { picked ->
                showGameDirPicker = false
                viewModel.setInjectionGameDir(picked)
            },
            onDismiss = { showGameDirPicker = false }
        )
    }

    if (uiState.downloadPage.pendingCaptureItemId != null) {
        var useExternalRelayFallback by remember(uiState.downloadPage.pendingCaptureItemId) {
            mutableStateOf(false)
        }
        if (!useExternalRelayFallback) {
            GeckoCaptureDialog(
                onCaptured = { code, fileName -> viewModel.onShortCodeCaptured(code, fileName) },
                onDirectDownloadUrl = { url, fileName -> viewModel.onDirectDownloadUrlCaptured(url, fileName) },
                onFallbackExternalRelay = { useExternalRelayFallback = true },
                onFallbackManual = { viewModel.onShortCodeCaptureCancelled(openManualDialog = true) },
                onDismiss = { viewModel.onShortCodeCaptureCancelled(openManualDialog = false) }
            )
        } else {
            ExternalShortCodeRelayDialog(
                onCaptured = { code, fileName -> viewModel.onShortCodeCaptured(code, fileName) },
                onFallbackManual = { viewModel.onShortCodeCaptureCancelled(openManualDialog = true) },
                onDismiss = { viewModel.onShortCodeCaptureCancelled(openManualDialog = false) }
            )
        }
    }
    }
}

@Composable
private fun HomeTabContent(
    deviceInfo: DeviceInfo,
    telemetry: SystemTelemetry,
    injectionState: com.aliceincradle.launcher.bridge.InjectionUiState,
    onGaugeClick: (TelemetryDialogType) -> Unit,
    onPickGameDir: () -> Unit,
    onRefreshProbe: () -> Unit,
    onRequestInject: () -> Unit,
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

        InjectionStatusCard(
            state = injectionState,
            onPickGameDir = onPickGameDir,
            onRefreshProbe = onRefreshProbe,
            onRequestInject = onRequestInject
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
