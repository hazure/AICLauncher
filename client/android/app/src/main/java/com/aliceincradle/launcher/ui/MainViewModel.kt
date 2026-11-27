package com.aliceincradle.launcher.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.aliceincradle.launcher.bridge.EnvDetector
import com.aliceincradle.launcher.data.DefaultPathProvider
import com.aliceincradle.launcher.data.DeviceInfo
import com.aliceincradle.launcher.data.DeviceInfoProvider
import com.aliceincradle.launcher.data.DownloadCatalogItem
import com.aliceincradle.launcher.data.DownloadCatalogPreviewFactory
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadFilterState
import com.aliceincradle.launcher.data.DownloadPageState
import com.aliceincradle.launcher.data.DownloadTaskItem
import com.aliceincradle.launcher.data.FileItem
import com.aliceincradle.launcher.data.FileManagerDialog
import com.aliceincradle.launcher.data.FilePaneSide
import com.aliceincradle.launcher.data.FolderPageState
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.GameRunStatus
import com.aliceincradle.launcher.data.PathProvider
import com.aliceincradle.launcher.data.PosixPermissions
import com.aliceincradle.launcher.data.SettingsPageState
import com.aliceincradle.launcher.data.SystemTelemetry
import com.aliceincradle.launcher.data.SystemTelemetryProvider
import com.aliceincradle.launcher.data.TelemetryPreviewFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MainTab(val label: String) {
    HOME("主页"),
    DOWNLOAD("下载"),
    FILES("文件夹"),
    SETTINGS("设置")
}

enum class TelemetryDialogType {
    NONE,
    MEMORY,
    CPU,
    GPU,
    THERMAL
}

data class FabGroupVisibility(
    val showLaunch: Boolean,
    val showTerminate: Boolean,
    val showDownloads: Boolean
) {
    val isContainerVisible: Boolean
        get() = showLaunch || showTerminate || showDownloads
}

data class MainUiState(
    val isLoading: Boolean = true,
    val deviceInfo: DeviceInfo = DeviceInfo(),
    val telemetry: SystemTelemetry = TelemetryPreviewFactory.createPreview(),
    val gameProcess: GameProcessState = GameProcessState(),
    val downloadTasks: List<DownloadTaskItem> = emptyList(),
    val activeDialog: TelemetryDialogType = TelemetryDialogType.NONE,
    val isDownloadSheetOpen: Boolean = false,
    val canLaunchGame: Boolean = true,
    val selectedTab: MainTab = MainTab.HOME,
    val downloadPage: DownloadPageState = DownloadPageState(),
    val folderPage: FolderPageState = FolderPageState(),
    val settingsPage: SettingsPageState = SettingsPageState()
) {
    val fabVisibility: FabGroupVisibility
        get() = FabGroupVisibility(
            showLaunch = canLaunchGame && !gameProcess.isAlive,
            showTerminate = gameProcess.isAlive,
            showDownloads = downloadTasks.isNotEmpty()
        )
}

class MainViewModel @JvmOverloads constructor(
    private val telemetryProvider: SystemTelemetryProvider = SystemTelemetryProvider(),
    private val envDetector: EnvDetector = EnvDetector(),
    private val pathProvider: PathProvider = DefaultPathProvider(envDetector),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoStartPolling: Boolean = true,
    private val refreshIntervalMs: Long = 2_000L,
    private val fileOpStepDelayMs: Long = 35L
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    private var hasLoadedContext = false
    private var nextSimulatedPid = 10482
    private val probeScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val refreshMutex = Mutex()
    private var fileOpJob: Job? = null

    init {
        if (autoStartPolling) {
            probeScope.launch {
                refreshTelemetryAndEnv(includeEnv = true)
                while (isActive) {
                    delay(refreshIntervalMs)
                    refreshTelemetryAndEnv(includeEnv = false)
                }
            }
        }
    }

    fun loadDeviceInfo(appContext: Context) {
        this.appContext = appContext.applicationContext
        if (hasLoadedContext) return
        hasLoadedContext = true
        probeScope.launch {
            refreshTelemetryAndEnv(includeEnv = true)
        }
    }

    suspend fun refreshTelemetryAndEnv(includeEnv: Boolean = true) {
        refreshMutex.withLock {
            val ctx = appContext
            val sampledTelemetry = telemetryProvider.sample(ctx)
            val detectedProcess = envDetector.probeGameProcess()
            val updatedDeviceInfo = if (includeEnv) {
                DeviceInfoProvider.load(
                    context = ctx,
                    telemetryProvider = telemetryProvider,
                    envDetector = envDetector,
                    cachedTelemetry = sampledTelemetry
                )
            } else {
                null
            }
            val currentDir = if (includeEnv) pathProvider.currentDirectoryLabel(ctx) else null
            val targetDir = if (includeEnv) pathProvider.targetDirectoryLabel(ctx) else null

            _uiState.update { state ->
                val nextInfo = updatedDeviceInfo ?: state.deviceInfo.copy(
                    gpu = sampledTelemetry.gpu.renderer,
                    memory = DeviceInfoProvider.formatMemorySummary(sampledTelemetry.memory)
                )
                val nextProcess = when {
                    detectedProcess.isAlive -> detectedProcess
                    state.gameProcess.isSimulated -> state.gameProcess
                    else -> detectedProcess
                }
                val nextFolderPage = if (currentDir != null && targetDir != null) {
                    state.folderPage.copy(currentPath = currentDir, targetPath = targetDir)
                } else {
                    state.folderPage
                }
                state.copy(
                    isLoading = false,
                    deviceInfo = nextInfo,
                    telemetry = sampledTelemetry,
                    gameProcess = nextProcess,
                    folderPage = nextFolderPage
                )
            }
        }
    }

    override fun onCleared() {
        probeScope.cancel()
        super.onCleared()
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun openTelemetryDialog(type: TelemetryDialogType) {
        _uiState.update { it.copy(activeDialog = type) }
    }

    fun dismissTelemetryDialog() {
        _uiState.update { it.copy(activeDialog = TelemetryDialogType.NONE) }
    }

    fun openDownloadSheet() {
        _uiState.update { it.copy(isDownloadSheetOpen = true) }
    }

    fun dismissDownloadSheet() {
        _uiState.update { it.copy(isDownloadSheetOpen = false) }
    }

    /**
     * Reserved interface for feeding real telemetry samples from native/bridge probes.
     */
    fun updateTelemetry(telemetry: SystemTelemetry) {
        _uiState.update { it.copy(telemetry = telemetry) }
    }

    /**
     * Reserved interface for syncing real process monitor state.
     */
    fun updateGameProcess(processState: GameProcessState) {
        _uiState.update { state ->
            state.copy(gameProcess = processState)
        }
    }

    /**
     * Reserved interface for syncing real download queue state.
     */
    fun updateDownloads(tasks: List<DownloadTaskItem>) {
        _uiState.update { state ->
            state.copy(
                downloadTasks = tasks,
                isDownloadSheetOpen = if (tasks.isEmpty()) false else state.isDownloadSheetOpen
            )
        }
    }

    fun launchGame() {
        val pid = nextSimulatedPid++
        updateGameProcess(
            GameProcessState(
                status = GameRunStatus.RUNNING,
                versionName = "AIC 0.3.0",
                pid = pid,
                isSimulated = true
            )
        )
    }

    fun terminateGame() {
        updateGameProcess(
            GameProcessState(
                status = GameRunStatus.TERMINATED,
                versionName = "AIC 0.3.0",
                pid = null,
                isSimulated = false
            )
        )
    }

    fun exitGameNormally() {
        updateGameProcess(
            GameProcessState(
                status = GameRunStatus.STOPPED,
                versionName = "AIC 0.3.0",
                pid = null,
                isSimulated = false
            )
        )
    }

    fun setCanLaunchGame(canLaunch: Boolean) {
        _uiState.update { it.copy(canLaunchGame = canLaunch) }
    }

    fun toggleSampleDownloads() {
        val current = _uiState.value.downloadTasks
        if (current.isEmpty()) {
            updateDownloads(
                listOf(
                    DownloadTaskItem("dl-1", "Turnip v24.1.0 驱动包", 64),
                    DownloadTaskItem("dl-2", "DXVK 2.3.1 兼容层组件", 28)
                )
            )
        } else {
            updateDownloads(emptyList())
        }
    }

    fun updateDownloadQuery(query: String) {
        updateDownloadFilter { it.copy(query = query) }
    }

    fun selectDownloadApp(app: String) {
        updateDownloadFilter { it.copy(selectedApp = app) }
    }

    fun selectDownloadVersion(version: String) {
        updateDownloadFilter { it.copy(selectedVersion = version, isVersionFilterApplied = true) }
    }

    fun selectDownloadCategory(category: String) {
        updateDownloadFilter { it.copy(selectedCategory = category, isCategoryFilterApplied = true) }
    }

    fun toggleIncludeCompatibleMods(checked: Boolean) {
        updateDownloadFilter { it.copy(includeCompatibleMods = checked) }
    }

    fun toggleIncludeFeaturedMods(checked: Boolean) {
        updateDownloadFilter { it.copy(includeFeaturedMods = checked) }
    }

    fun triggerCatalogDownload(itemId: String) {
        _uiState.update { state ->
            val target = state.downloadPage.allItems.find { it.id == itemId } ?: return@update state
            if (target.status == DownloadContentStatus.DISABLED) return@update state

            val updatedAll = state.downloadPage.allItems.map { item ->
                if (item.id == itemId && item.status == DownloadContentStatus.DOWNLOADING) {
                    item.copy(status = DownloadContentStatus.DOWNLOADED)
                } else {
                    item
                }
            }
            val nextTasks = if (state.downloadTasks.any { it.id == itemId } || target.status == DownloadContentStatus.DOWNLOADED) {
                state.downloadTasks
            } else {
                state.downloadTasks + DownloadTaskItem(id = target.id, name = target.title, progressPercent = 15)
            }
            state.copy(
                downloadTasks = nextTasks,
                downloadPage = state.downloadPage.copy(
                    allItems = updatedAll,
                    items = DownloadCatalogPreviewFactory.filterItems(updatedAll, state.downloadPage.filter)
                )
            )
        }
    }

    fun openCatalogItemDetail(item: DownloadCatalogItem) {
        _uiState.update { state ->
            state.copy(downloadPage = state.downloadPage.copy(selectedDetailItem = item))
        }
    }

    fun dismissCatalogItemDetail() {
        _uiState.update { state ->
            state.copy(downloadPage = state.downloadPage.copy(selectedDetailItem = null))
        }
    }

    fun selectFilePane(side: FilePaneSide) {
        _uiState.update { state ->
            state.copy(folderPage = state.folderPage.copy(activePane = side))
        }
    }

    fun toggleFileSelection(itemId: String) {
        toggleFileSelection(FilePaneSide.LEFT, itemId)
    }

    fun toggleFileSelection(side: FilePaneSide, itemId: String) {
        _uiState.update { state ->
            val withPane = state.folderPage.copy(activePane = side)
            val updatedFolder = updateActivePaneItems(withPane) { list ->
                list.map { item ->
                    if (item.id == itemId) item.copy(isSelected = !item.isSelected) else item
                }
            }
            state.copy(folderPage = updatedFolder)
        }
    }

    fun toggleSelectAllFiles() {
        _uiState.update { state ->
            val folder = state.folderPage
            val active = folder.activeItems
            val allSelected = active.isNotEmpty() && active.all { it.isSelected }
            val updatedFolder = updateActivePaneItems(folder) { list ->
                list.map { it.copy(isSelected = !allSelected) }
            }
            state.copy(folderPage = updatedFolder)
        }
    }

    fun openRenameDialog() {
        _uiState.update { state ->
            val target = state.folderPage.selectedItems.firstOrNull() ?: return@update state
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.Rename(item = target, draftName = target.name)
                )
            )
        }
    }

    fun updateRenameDraft(draft: String) {
        _uiState.update { state ->
            val dialog = state.folderPage.activeDialog as? FileManagerDialog.Rename ?: return@update state
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = dialog.copy(draftName = draft)
                )
            )
        }
    }

    fun confirmRename() {
        val dialog = _uiState.value.folderPage.activeDialog as? FileManagerDialog.Rename ?: return
        val newName = dialog.draftName.trim()
        if (newName.isEmpty()) {
            _uiState.update { state ->
                state.copy(
                    folderPage = state.folderPage.copy(
                        activeDialog = null,
                        actionNotice = "名称不能为空，已取消重命名"
                    )
                )
            }
            return
        }
        probeScope.launch {
            _uiState.update { state ->
                if (state.folderPage.activeDialog !is FileManagerDialog.Rename) return@update state
                val updatedFolder = updateActivePaneItems(state.folderPage) { list ->
                    list.map { item ->
                        if (item.id == dialog.item.id) item.copy(name = newName) else item
                    }
                }
                state.copy(
                    folderPage = updatedFolder.copy(
                        activeDialog = null,
                        actionNotice = "已重命名：${dialog.item.name} -> $newName"
                    )
                )
            }
        }
    }

    fun openDeleteConfirmDialog() {
        _uiState.update { state ->
            val targets = state.folderPage.selectedItems
            if (targets.isEmpty()) return@update state
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.DeleteConfirm(targets = targets)
                )
            )
        }
    }

    fun confirmDeleteSelected() {
        val dialog = _uiState.value.folderPage.activeDialog as? FileManagerDialog.DeleteConfirm ?: return
        val targetIds = dialog.targets.map { it.id }.toSet()
        probeScope.launch {
            _uiState.update { state ->
                if (state.folderPage.activeDialog !is FileManagerDialog.DeleteConfirm) return@update state
                val updatedFolder = updateActivePaneItems(state.folderPage) { list ->
                    list.filterNot { it.id in targetIds }
                }
                state.copy(
                    folderPage = updatedFolder.copy(
                        activeDialog = null,
                        actionNotice = "已删除 ${targetIds.size} 项（模拟操作）"
                    )
                )
            }
        }
    }

    fun startCopySelectedFiles() {
        val folder = _uiState.value.folderPage
        val targets = folder.selectedItems
        if (targets.isEmpty()) return
        val destination = folder.activeDestinationPath
        launchSimulatedFileProgress(
            operationTitle = "正在复制文件...",
            targets = targets,
            targetDescription = destination,
            completionNotice = "已完成复制 ${targets.size} 项至 $destination"
        )
    }

    fun openPropertiesDialog() {
        _uiState.update { state ->
            val folder = state.folderPage
            val target = folder.selectedItems.firstOrNull() ?: return@update state
            val resolvedPath = if (folder.activePane == FilePaneSide.LEFT) {
                pathProvider.buildItemPath(target.name, appContext)
            } else {
                pathProvider.buildTargetItemPath(target.name, appContext)
            }
            state.copy(
                folderPage = folder.copy(
                    activeDialog = FileManagerDialog.Properties(
                        item = target,
                        resolvedPath = resolvedPath
                    )
                )
            )
        }
    }

    fun openPermissionEditDialog() {
        _uiState.update { state ->
            val target = (state.folderPage.activeDialog as? FileManagerDialog.Properties)?.item
                ?: state.folderPage.selectedItems.firstOrNull()
                ?: return@update state
            val parsed = PosixPermissions.fromOctal(target.permissions) ?: PosixPermissions()
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.PermissionEdit(
                        item = target,
                        permissions = parsed,
                        octalInput = parsed.toFourDigitOctal(),
                        applyRecursively = target.recursiveApplied
                    )
                )
            )
        }
    }

    fun updatePermissionFlags(transform: (PosixPermissions) -> PosixPermissions) {
        _uiState.update { state ->
            val dialog = state.folderPage.activeDialog as? FileManagerDialog.PermissionEdit ?: return@update state
            val nextPerm = transform(dialog.permissions)
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = dialog.copy(
                        permissions = nextPerm,
                        octalInput = nextPerm.toFourDigitOctal()
                    )
                )
            )
        }
    }

    fun updatePermissionOctalInput(rawInput: String) {
        val filtered = rawInput.filter { it in '0'..'7' }.take(4)
        _uiState.update { state ->
            val dialog = state.folderPage.activeDialog as? FileManagerDialog.PermissionEdit ?: return@update state
            val parsed = PosixPermissions.fromOctal(filtered)
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = dialog.copy(
                        octalInput = filtered,
                        permissions = parsed ?: dialog.permissions
                    )
                )
            )
        }
    }

    fun togglePermissionRecursive(applyRecursively: Boolean) {
        _uiState.update { state ->
            val dialog = state.folderPage.activeDialog as? FileManagerDialog.PermissionEdit ?: return@update state
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = dialog.copy(applyRecursively = applyRecursively)
                )
            )
        }
    }

    fun confirmPermissionEdit() {
        val dialog = _uiState.value.folderPage.activeDialog as? FileManagerDialog.PermissionEdit ?: return
        val resolvedOctal = PosixPermissions.fromOctal(dialog.octalInput)?.toFourDigitOctal()
            ?: dialog.permissions.toFourDigitOctal()
        probeScope.launch {
            _uiState.update { state ->
                if (state.folderPage.activeDialog !is FileManagerDialog.PermissionEdit) return@update state
                val updatedFolder = updateActivePaneItems(state.folderPage) { list ->
                    list.map { item ->
                        if (item.id == dialog.item.id) {
                            item.copy(
                                permissions = resolvedOctal,
                                recursiveApplied = dialog.applyRecursively
                            )
                        } else {
                            item
                        }
                    }
                }
                val recursiveNote = if (dialog.applyRecursively) "（含子目录递归）" else ""
                state.copy(
                    folderPage = updatedFolder.copy(
                        activeDialog = null,
                        actionNotice = "已更新权限：${dialog.item.name} -> $resolvedOctal$recursiveNote"
                    )
                )
            }
        }
    }

    fun openExportSheet() {
        _uiState.update { state ->
            val targets = state.folderPage.selectedItems
            if (targets.isEmpty()) return@update state
            val downloadTargets = pathProvider.downloadExportTargets(appContext)
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.ExportSheet(
                        targets = targets,
                        downloadTargets = downloadTargets
                    )
                )
            )
        }
    }

    fun startExportToDownload() {
        val sheet = _uiState.value.folderPage.activeDialog as? FileManagerDialog.ExportSheet ?: return
        val primaryTarget = sheet.downloadTargets.firstOrNull()?.displayPath
            ?: DefaultPathProvider.DEFAULT_DOWNLOAD_LABEL
        launchSimulatedFileProgress(
            operationTitle = "正在导出至 Download...",
            targets = sheet.targets,
            targetDescription = primaryTarget,
            completionNotice = "已导出 ${sheet.targets.size} 项至 $primaryTarget"
        )
    }

    fun startCustomExportToUri(treeUriString: String) {
        val targets = (_uiState.value.folderPage.activeDialog as? FileManagerDialog.ExportSheet)?.targets
            ?: _uiState.value.folderPage.selectedItems
        if (targets.isEmpty()) return
        launchSimulatedFileProgress(
            operationTitle = "正在自定义导出...",
            targets = targets,
            targetDescription = treeUriString,
            completionNotice = "已导出 ${targets.size} 项至所选目录：$treeUriString"
        )
    }

    fun cancelFileOperation() {
        fileOpJob?.cancel()
        fileOpJob = null
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = null,
                    actionNotice = "已取消文件操作"
                )
            )
        }
    }

    fun dismissFileManagerDialog() {
        if (_uiState.value.folderPage.activeDialog is FileManagerDialog.FileOpProgress) {
            cancelFileOperation()
            return
        }
        _uiState.update { state ->
            state.copy(folderPage = state.folderPage.copy(activeDialog = null))
        }
    }

    private fun updateActivePaneItems(
        folder: FolderPageState,
        transform: (List<FileItem>) -> List<FileItem>
    ): FolderPageState {
        return if (folder.activePane == FilePaneSide.LEFT) {
            folder.copy(items = transform(folder.items))
        } else {
            folder.copy(targetItems = transform(folder.targetItems))
        }
    }

    private fun launchSimulatedFileProgress(
        operationTitle: String,
        targets: List<FileItem>,
        targetDescription: String,
        completionNotice: String
    ) {
        fileOpJob?.cancel()
        val firstName = targets.firstOrNull()?.name ?: ""
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.FileOpProgress(
                        operationTitle = operationTitle,
                        currentFileName = firstName,
                        targetDescription = targetDescription,
                        progressPercent = 0
                    )
                )
            )
        }
        fileOpJob = probeScope.launch {
            for (step in 10..100 step 10) {
                if (fileOpStepDelayMs > 0L) {
                    delay(fileOpStepDelayMs)
                }
                if (!isActive) return@launch
                val currentItem = targets[((step - 1) * targets.size / 100).coerceIn(0, targets.lastIndex)]
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = FileManagerDialog.FileOpProgress(
                                operationTitle = operationTitle,
                                currentFileName = currentItem.name,
                                targetDescription = targetDescription,
                                progressPercent = step
                            )
                        )
                    )
                }
            }
            _uiState.update { state ->
                state.copy(
                    folderPage = state.folderPage.copy(
                        activeDialog = null,
                        actionNotice = completionNotice
                    )
                )
            }
        }
    }

    fun selectPythonVersion(version: String) {
        _uiState.update { state ->
            state.copy(
                settingsPage = state.settingsPage.copy(
                    selectedPythonVersion = version,
                    statusNotice = "已选择版本：$version"
                )
            )
        }
    }

    fun triggerPythonDownloadHint() {
        _uiState.update { state ->
            state.copy(
                settingsPage = state.settingsPage.copy(
                    statusNotice = "已触发 Python 下载指引（模拟状态）"
                )
            )
        }
    }

    fun selectPythonDir(dir: String) {
        _uiState.update { state ->
            state.copy(
                settingsPage = state.settingsPage.copy(
                    selectedPythonDir = dir,
                    statusNotice = "已切换目录：$dir"
                )
            )
        }
    }

    fun openCustomPathDialog() {
        _uiState.update { state ->
            state.copy(
                settingsPage = state.settingsPage.copy(
                    isCustomPathDialogOpen = true,
                    customPathDraft = state.settingsPage.selectedPythonDir
                )
            )
        }
    }

    fun updateCustomPathDraft(draft: String) {
        _uiState.update { state ->
            state.copy(settingsPage = state.settingsPage.copy(customPathDraft = draft))
        }
    }

    fun confirmCustomPath() {
        _uiState.update { state ->
            val trimmed = state.settingsPage.customPathDraft.trim()
            if (trimmed.isEmpty()) {
                return@update state.copy(
                    settingsPage = state.settingsPage.copy(
                        isCustomPathDialogOpen = false,
                        statusNotice = "路径不能为空，已保留当前配置"
                    )
                )
            }
            val nextOptions = (listOf(trimmed) + state.settingsPage.pythonDirOptions).distinct()
            state.copy(
                settingsPage = state.settingsPage.copy(
                    selectedPythonDir = trimmed,
                    pythonDirOptions = nextOptions,
                    isCustomPathDialogOpen = false,
                    statusNotice = "已更新自定义目录：$trimmed"
                )
            )
        }
    }

    fun dismissCustomPathDialog() {
        _uiState.update { state ->
            state.copy(settingsPage = state.settingsPage.copy(isCustomPathDialogOpen = false))
        }
    }

    fun triggerReservedPythonAction() {
        _uiState.update { state ->
            state.copy(
                settingsPage = state.settingsPage.copy(
                    statusNotice = "预留项已点击：${state.settingsPage.reservedButtonLabel}"
                )
            )
        }
    }

    private fun updateDownloadFilter(transform: (DownloadFilterState) -> DownloadFilterState) {
        _uiState.update { state ->
            val nextFilter = transform(state.downloadPage.filter)
            val filtered = DownloadCatalogPreviewFactory.filterItems(state.downloadPage.allItems, nextFilter)
            state.copy(
                downloadPage = state.downloadPage.copy(
                    filter = nextFilter,
                    items = filtered
                )
            )
        }
    }
}
