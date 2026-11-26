package com.aliceincradle.launcher.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliceincradle.launcher.data.DeviceInfo
import com.aliceincradle.launcher.data.DeviceInfoProvider
import com.aliceincradle.launcher.data.DownloadCatalogItem
import com.aliceincradle.launcher.data.DownloadCatalogPreviewFactory
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadFilterState
import com.aliceincradle.launcher.data.DownloadPageState
import com.aliceincradle.launcher.data.DownloadTaskItem
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.GameRunStatus
import com.aliceincradle.launcher.data.SystemTelemetry
import com.aliceincradle.launcher.data.TelemetryPreviewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val downloadPage: DownloadPageState = DownloadPageState()
) {
    val fabVisibility: FabGroupVisibility
        get() = FabGroupVisibility(
            showLaunch = canLaunchGame && !gameProcess.isAlive,
            showTerminate = gameProcess.isAlive,
            showDownloads = downloadTasks.isNotEmpty()
        )
}

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var nextSimulatedPid = 10482

    fun loadDeviceInfo(appContext: Context) {
        if (hasLoaded) return
        hasLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            val info = DeviceInfoProvider.load(appContext)
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    deviceInfo = info,
                    telemetry = TelemetryPreviewFactory.createPreview(
                        gpuRenderer = info.gpu,
                        isGameRunning = state.gameProcess.isAlive
                    )
                )
            }
        }
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
            state.copy(
                gameProcess = processState,
                telemetry = TelemetryPreviewFactory.createPreview(
                    gpuRenderer = state.deviceInfo.gpu,
                    isGameRunning = processState.isAlive
                )
            )
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
                pid = pid
            )
        )
    }

    fun terminateGame() {
        updateGameProcess(
            GameProcessState(
                status = GameRunStatus.TERMINATED,
                versionName = "AIC 0.3.0",
                pid = null
            )
        )
    }

    fun exitGameNormally() {
        updateGameProcess(
            GameProcessState(
                status = GameRunStatus.STOPPED,
                versionName = "AIC 0.3.0",
                pid = null
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
