package com.aliceincradle.launcher.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.aliceincradle.launcher.bridge.EnvDetector
import com.aliceincradle.launcher.data.DeviceInfo
import com.aliceincradle.launcher.data.DeviceInfoProvider
import com.aliceincradle.launcher.data.DownloadCatalogItem
import com.aliceincradle.launcher.data.DownloadCatalogPreviewFactory
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadFilterState
import com.aliceincradle.launcher.data.DownloadPageState
import com.aliceincradle.launcher.data.DownloadTaskItem
import com.aliceincradle.launcher.data.FolderAction
import com.aliceincradle.launcher.data.FolderPageState
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.GameRunStatus
import com.aliceincradle.launcher.data.SettingsPageState
import com.aliceincradle.launcher.data.SystemTelemetry
import com.aliceincradle.launcher.data.SystemTelemetryProvider
import com.aliceincradle.launcher.data.TelemetryPreviewFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoStartPolling: Boolean = true,
    private val refreshIntervalMs: Long = 2_000L
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    private var hasLoadedContext = false
    private var nextSimulatedPid = 10482
    private val probeScope = CoroutineScope(SupervisorJob() + ioDispatcher)

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
            state.copy(
                isLoading = false,
                deviceInfo = nextInfo,
                telemetry = sampledTelemetry,
                gameProcess = nextProcess
            )
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

    fun selectFolderItem(itemId: String) {
        _uiState.update { state ->
            state.copy(folderPage = state.folderPage.copy(selectedItemId = itemId))
        }
    }

    fun performFolderAction(action: FolderAction) {
        _uiState.update { state ->
            val folder = state.folderPage
            val target = folder.items.find { it.id == folder.selectedItemId } ?: folder.items.firstOrNull()
            when (action) {
                FolderAction.INFO -> state.copy(
                    folderPage = folder.copy(
                        inspectedItem = target,
                        actionNotice = target?.let { "查看属性：${it.name}" }
                    )
                )
                FolderAction.DELETE -> state.copy(
                    folderPage = folder.copy(
                        actionNotice = target?.let { "模拟删除预览（未修改原文件）：${it.name}" }
                            ?: "当前没有可删除的项目"
                    )
                )
                FolderAction.COPY -> state.copy(
                    folderPage = folder.copy(
                        clipboardItemName = target?.name,
                        actionNotice = target?.let { "已复制：${it.name}" } ?: "未选择要复制的项目"
                    )
                )
                FolderAction.PLACE -> {
                    val clip = folder.clipboardItemName
                    state.copy(
                        folderPage = folder.copy(
                            actionNotice = if (clip != null) "已放置副本：$clip" else "剪贴板为空，请先执行 Copy"
                        )
                    )
                }
                FolderAction.RUNNING -> {
                    if (!state.canLaunchGame) {
                        return@update state.copy(
                            folderPage = folder.copy(actionNotice = "当前运行环境未就绪，暂不可启动")
                        )
                    }
                    val pid = nextSimulatedPid++
                    val runningState = GameProcessState(
                        status = GameRunStatus.RUNNING,
                        versionName = "AIC 0.3.0",
                        pid = pid,
                        isSimulated = true
                    )
                    state.copy(
                        gameProcess = runningState,
                        folderPage = folder.copy(
                            actionNotice = "已模拟启动：${target?.name ?: "AliceInCradle.exe"} (PID: $pid)"
                        )
                    )
                }
            }
        }
    }

    fun dismissFolderItemInfo() {
        _uiState.update { state ->
            state.copy(folderPage = state.folderPage.copy(inspectedItem = null))
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
