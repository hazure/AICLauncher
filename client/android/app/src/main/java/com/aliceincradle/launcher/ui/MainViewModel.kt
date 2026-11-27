package com.aliceincradle.launcher.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.aliceincradle.launcher.bridge.EnvDetector
import com.aliceincradle.launcher.data.DefaultPathProvider
import com.aliceincradle.launcher.data.DeviceInfo
import com.aliceincradle.launcher.data.DeviceInfoProvider
import com.aliceincradle.launcher.data.DownloadCatalogFilter
import com.aliceincradle.launcher.data.DownloadCatalogItem
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadFilterState
import com.aliceincradle.launcher.data.DownloadPageState
import com.aliceincradle.launcher.data.DownloadProgressListener
import com.aliceincradle.launcher.data.DownloadQueueManager
import com.aliceincradle.launcher.data.DownloadRequest
import com.aliceincradle.launcher.data.FileItem
import com.aliceincradle.launcher.data.FileManagerDialog
import com.aliceincradle.launcher.data.FilePaneSide
import com.aliceincradle.launcher.data.FolderPageState
import com.aliceincradle.launcher.data.MaintenanceManager
import com.aliceincradle.launcher.data.MirrorLinkSniffer
import com.aliceincradle.launcher.data.PathProvider
import com.aliceincradle.launcher.data.PosixPermissions
import com.aliceincradle.launcher.data.RealFileSystemRepository
import com.aliceincradle.launcher.data.RemoteCatalogFetcher
import com.aliceincradle.launcher.data.SettingsData
import com.aliceincradle.launcher.data.SettingsManager
import com.aliceincradle.launcher.data.SettingsPageState
import com.aliceincradle.launcher.data.SystemTelemetry
import com.aliceincradle.launcher.data.SystemTelemetryProvider
import com.aliceincradle.launcher.data.TelemetryPreviewFactory
import com.aliceincradle.launcher.data.formatFileSize
import com.aliceincradle.launcher.data.validateEnvVars
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

data class MainUiState(
    val isLoading: Boolean = true,
    val deviceInfo: DeviceInfo = DeviceInfo(),
    val telemetry: SystemTelemetry = TelemetryPreviewFactory.createPreview(),
    val activeDialog: TelemetryDialogType = TelemetryDialogType.NONE,
    val selectedTab: MainTab = MainTab.HOME,
    val downloadPage: DownloadPageState = DownloadPageState(),
    val folderPage: FolderPageState = FolderPageState(),
    val settingsPage: SettingsPageState = SettingsPageState()
)

class MainViewModel @JvmOverloads constructor(
    private val telemetryProvider: SystemTelemetryProvider = SystemTelemetryProvider(),
    private val envDetector: EnvDetector = EnvDetector(),
    private val pathProvider: PathProvider = DefaultPathProvider(envDetector),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoStartPolling: Boolean = true,
    private val refreshIntervalMs: Long = 2_000L,
    private var settingsManager: SettingsManager? = null,
    private val fileRepository: RealFileSystemRepository = RealFileSystemRepository(),
    private val catalogFetcher: RemoteCatalogFetcher = RemoteCatalogFetcher(),
    private val maintenanceManager: MaintenanceManager = MaintenanceManager(),
    downloadQueueManagerOverride: DownloadQueueManager? = null,
    autoFetchCatalog: Boolean = true,
    private val mirrorLinkSniffer: MirrorLinkSniffer = MirrorLinkSniffer()
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    private var hasLoadedContext = false
    private val probeScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val refreshMutex = Mutex()
    private var fileOpJob: Job? = null
    private val mirrorSniffJobs = ConcurrentHashMap<String, Job>()

    private val downloadQueueManager: DownloadQueueManager = downloadQueueManagerOverride
        ?: DownloadQueueManager(scope = probeScope, ioDispatcher = ioDispatcher, workerCount = 2)

    init {
        downloadQueueManager.setListener(object : DownloadProgressListener {
            override fun onStarted(itemId: String) {
                updateCatalogItem(itemId) {
                    it.copy(status = DownloadContentStatus.DOWNLOADING, errorMessage = null)
                }
            }

            override fun onProgress(itemId: String, downloadedBytes: Long, totalBytes: Long, percent: Int) {
                updateCatalogItem(itemId) {
                    it.copy(
                        status = DownloadContentStatus.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        sizeBytes = if (totalBytes > 0L) totalBytes else it.sizeBytes,
                        progressPercent = percent
                    )
                }
            }

            override fun onCompleted(itemId: String, file: File) {
                updateCatalogItem(itemId) {
                    it.copy(
                        status = DownloadContentStatus.DOWNLOADED,
                        progressPercent = 100,
                        downloadedBytes = file.length(),
                        sizeBytes = file.length(),
                        sizeLabel = formatFileSize(file.length()),
                        localFilePath = file.absolutePath,
                        errorMessage = null
                    )
                }
                refreshFilePanes()
            }

            override fun onFailed(itemId: String, errorMessage: String) {
                updateCatalogItem(itemId) {
                    it.copy(
                        status = DownloadContentStatus.FAILED,
                        errorMessage = errorMessage
                    )
                }
            }

            override fun onCancelled(itemId: String) {
                updateCatalogItem(itemId) {
                    it.copy(
                        status = DownloadContentStatus.AVAILABLE,
                        progressPercent = 0,
                        downloadedBytes = 0L,
                        errorMessage = null
                    )
                }
            }
        })

        if (autoStartPolling) {
            probeScope.launch {
                refreshTelemetryAndEnv(includeEnv = true)
                while (isActive) {
                    delay(refreshIntervalMs)
                    refreshTelemetryAndEnv(includeEnv = false)
                }
            }
        }
        if (autoFetchCatalog) {
            refreshDownloadCatalog()
        }
    }

    fun loadDeviceInfo(appContext: Context) {
        this.appContext = appContext.applicationContext
        if (settingsManager == null) {
            settingsManager = SettingsManager(this.appContext!!)
        }
        settingsManager?.settingsFlow?.value?.let { persisted ->
            hydrateSettingsFromStore(persisted)
        }
        if (hasLoadedContext) return
        hasLoadedContext = true

        val leftDir = fileRepository.resolveInitialLeftDir(this.appContext)
        val rightDir = fileRepository.resolveInitialRightDir(this.appContext)
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    currentPath = leftDir.absolutePath,
                    targetPath = rightDir.absolutePath,
                    hasAllFilesAccess = fileRepository.hasAllFilesAccess()
                )
            )
        }

        probeScope.launch {
            refreshTelemetryAndEnv(includeEnv = true)
            refreshFilePanesSync()
            refreshCacheSizeSync()
        }
    }

    suspend fun refreshTelemetryAndEnv(includeEnv: Boolean = true) {
        refreshMutex.withLock {
            val ctx = appContext
            val sampledTelemetry = telemetryProvider.sample(ctx)
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
                state.copy(
                    isLoading = false,
                    deviceInfo = nextInfo,
                    telemetry = sampledTelemetry
                )
            }
        }
    }

    override fun onCleared() {
        downloadQueueManager.close()
        probeScope.cancel()
        super.onCleared()
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        when (tab) {
            MainTab.FILES -> {
                refreshAllFilesPermission()
                refreshFilePanes()
            }
            MainTab.SETTINGS -> refreshCacheSize()
            else -> Unit
        }
    }

    fun openTelemetryDialog(type: TelemetryDialogType) {
        _uiState.update { it.copy(activeDialog = type) }
    }

    fun dismissTelemetryDialog() {
        _uiState.update { it.copy(activeDialog = TelemetryDialogType.NONE) }
    }

    fun updateTelemetry(telemetry: SystemTelemetry) {
        _uiState.update { it.copy(telemetry = telemetry) }
    }

    // ==================== Download Catalog & Queue ====================

    fun refreshDownloadCatalog() {
        val downloadDir = DownloadQueueManager.resolveDownloadDirectory(appContext)
        val mirrorItem = buildOfficialMirrorItem(downloadDir)
        _uiState.update { state ->
            val currentAll = if (state.downloadPage.allItems.any { it.isMirrorSniffTarget }) {
                state.downloadPage.allItems
            } else {
                listOf(mirrorItem) + state.downloadPage.allItems
            }
            state.copy(
                downloadPage = state.downloadPage.copy(
                    allItems = currentAll,
                    items = DownloadCatalogFilter.filterItems(currentAll, state.downloadPage.filter),
                    isLoadingCatalog = true,
                    catalogError = null,
                    statsText = "正在拉取远端资源列表..."
                )
            )
        }
        probeScope.launch {
            val startMs = System.currentTimeMillis()
            val result = catalogFetcher.fetchCatalog(downloadDir)
            val elapsedSec = (System.currentTimeMillis() - startMs).coerceAtLeast(1L) / 1000.0
            result.onSuccess { fetched ->
                val combined = listOf(buildOfficialMirrorItem(downloadDir)) + fetched
                _uiState.update { state ->
                    val filtered = DownloadCatalogFilter.filterItems(combined, state.downloadPage.filter)
                    state.copy(
                        downloadPage = state.downloadPage.copy(
                            allItems = combined,
                            items = filtered,
                            isLoadingCatalog = false,
                            catalogError = null,
                            statsText = String.format(Locale.US, "共 %d 个内容，耗时 %.2fs", combined.size, elapsedSec)
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    val fallbackList = listOf(buildOfficialMirrorItem(downloadDir))
                    val filtered = DownloadCatalogFilter.filterItems(fallbackList, state.downloadPage.filter)
                    state.copy(
                        downloadPage = state.downloadPage.copy(
                            allItems = fallbackList,
                            items = filtered,
                            isLoadingCatalog = false,
                            catalogError = err.message ?: "网络请求失败",
                            statsText = "官方镜像就绪；GitHub 列表拉取失败：${err.message ?: "未知错误"}"
                        )
                    )
                }
            }
        }
    }

    private fun buildOfficialMirrorItem(downloadDir: File?): DownloadCatalogItem {
        val existingFile = downloadDir?.listFiles()?.firstOrNull { file ->
            file.isFile && file.length() > 0L && (
                file.name.equals("AliceInCradle_Win_Latest.zip", ignoreCase = true) ||
                    file.name.startsWith("AliceInCradle", ignoreCase = true) &&
                    (file.name.endsWith(".zip", true) || file.name.endsWith(".7z", true))
                )
        }
        return DownloadCatalogItem(
            id = OFFICIAL_MIRROR_ITEM_ID,
            title = "Alice In Cradle · Windows 最新版（官方镜像站）",
            tags = listOf("游戏本体", "官方镜像", "动态解析"),
            sizeLabel = existingFile?.let { formatFileSize(it.length()) } ?: "动态大小",
            sizeBytes = existingFile?.length() ?: 0L,
            status = if (existingFile != null) DownloadContentStatus.DOWNLOADED else DownloadContentStatus.AVAILABLE,
            appName = "AIC",
            versionChannel = "稳定版",
            category = "游戏本体",
            isCompatibleVersion = true,
            isFeatured = true,
            author = "NanameHacha",
            description = "点击下载时实时请求 get.aliceincradle.dev/win/ 动态解析最新压缩包直链并流式下载。",
            downloadUrl = "",
            fileName = existingFile?.name ?: "AliceInCradle_Win_Latest.zip",
            localFilePath = existingFile?.absolutePath,
            progressPercent = if (existingFile != null) 100 else 0,
            isMirrorSniffTarget = true
        )
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
        val target = _uiState.value.downloadPage.allItems.find { it.id == itemId } ?: return
        if (target.status == DownloadContentStatus.DISABLED ||
            target.status == DownloadContentStatus.PREPARING ||
            target.status == DownloadContentStatus.QUEUED ||
            target.status == DownloadContentStatus.DOWNLOADING
        ) {
            return
        }

        val downloadDir = DownloadQueueManager.resolveDownloadDirectory(appContext)
        if (target.isMirrorSniffTarget) {
            _uiState.update { state ->
                state.copy(downloadPage = state.downloadPage.copy(mirrorSniffError = null))
            }
            updateCatalogItem(itemId) {
                it.copy(
                    status = DownloadContentStatus.PREPARING,
                    progressPercent = 0,
                    downloadedBytes = 0L,
                    errorMessage = null
                )
            }
            val sniffJob = probeScope.launch {
                val sniffResult = mirrorLinkSniffer.sniffLatestWinLink(appContext)
                sniffResult.onSuccess { sniffed ->
                    val destFile = File(downloadDir, sniffed.fileName)
                    updateCatalogItem(itemId) {
                        it.copy(
                            status = DownloadContentStatus.QUEUED,
                            downloadUrl = sniffed.directUrl,
                            fileName = sniffed.fileName,
                            errorMessage = null
                        )
                    }
                    downloadQueueManager.enqueue(
                        DownloadRequest(
                            itemId = itemId,
                            url = sniffed.directUrl,
                            destFile = destFile,
                            expectedBytes = target.sizeBytes
                        )
                    )
                }.onFailure { err ->
                    _uiState.update { state ->
                        state.copy(
                            downloadPage = state.downloadPage.copy(
                                mirrorSniffError = "链接解析失败，请检查网络环境"
                            )
                        )
                    }
                    updateCatalogItem(itemId) {
                        it.copy(
                            status = DownloadContentStatus.FAILED,
                            errorMessage = err.message ?: "链接解析失败，请检查网络环境"
                        )
                    }
                }
                mirrorSniffJobs.remove(itemId)
            }
            mirrorSniffJobs[itemId] = sniffJob
            return
        }

        if (target.downloadUrl.isBlank()) return
        val fileName = target.fileName.ifBlank { "${target.id}.bin" }
        val destFile = File(downloadDir, fileName)

        updateCatalogItem(itemId) {
            it.copy(
                status = DownloadContentStatus.QUEUED,
                progressPercent = 0,
                downloadedBytes = 0L,
                errorMessage = null
            )
        }
        downloadQueueManager.enqueue(
            DownloadRequest(
                itemId = itemId,
                url = target.downloadUrl,
                destFile = destFile,
                expectedBytes = target.sizeBytes
            )
        )
    }

    fun cancelCatalogDownload(itemId: String) {
        mirrorSniffJobs.remove(itemId)?.cancel()
        downloadQueueManager.cancel(itemId)
        updateCatalogItem(itemId) {
            it.copy(
                status = DownloadContentStatus.AVAILABLE,
                progressPercent = 0,
                downloadedBytes = 0L,
                errorMessage = null
            )
        }
    }

    companion object {
        const val OFFICIAL_MIRROR_ITEM_ID = "aic-win-official-mirror"
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

    private fun updateCatalogItem(
        itemId: String,
        transform: (DownloadCatalogItem) -> DownloadCatalogItem
    ) {
        _uiState.update { state ->
            val updatedAll = state.downloadPage.allItems.map { item ->
                if (item.id == itemId) transform(item) else item
            }
            state.copy(
                downloadPage = state.downloadPage.copy(
                    allItems = updatedAll,
                    items = DownloadCatalogFilter.filterItems(updatedAll, state.downloadPage.filter)
                )
            )
        }
    }

    // ==================== Real Dual-Pane File Manager ====================

    fun refreshAllFilesPermission() {
        val granted = fileRepository.hasAllFilesAccess()
        _uiState.update { state ->
            state.copy(folderPage = state.folderPage.copy(hasAllFilesAccess = granted))
        }
    }

    fun initFileDirectoriesForTest(leftDir: File, rightDir: File) {
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    currentPath = leftDir.absolutePath,
                    targetPath = rightDir.absolutePath
                )
            )
        }
        refreshFilePanes()
    }

    fun refreshFilePanes() {
        probeScope.launch {
            refreshFilePanesSync()
        }
    }

    private fun refreshFilePanesSync() {
        val state = _uiState.value
        val showHidden = state.settingsPage.showHiddenFiles
        val leftPath = state.folderPage.currentPath
        val rightPath = state.folderPage.targetPath

        val leftResult = if (leftPath.isNotBlank()) {
            fileRepository.listDirectory(File(leftPath), showHidden)
        } else {
            Result.success(emptyList())
        }
        val rightResult = if (rightPath.isNotBlank()) {
            fileRepository.listDirectory(File(rightPath), showHidden)
        } else {
            Result.success(emptyList())
        }

        _uiState.update { current ->
            val notice = leftResult.exceptionOrNull()?.message
                ?: rightResult.exceptionOrNull()?.message
                ?: current.folderPage.actionNotice
            current.copy(
                folderPage = current.folderPage.copy(
                    items = leftResult.getOrDefault(current.folderPage.items),
                    targetItems = rightResult.getOrDefault(current.folderPage.targetItems),
                    hasAllFilesAccess = fileRepository.hasAllFilesAccess(),
                    isLoadingFiles = false,
                    actionNotice = notice
                )
            )
        }
    }

    fun selectFilePane(side: FilePaneSide) {
        _uiState.update { state ->
            state.copy(folderPage = state.folderPage.copy(activePane = side))
        }
    }

    fun navigateIntoFolder(side: FilePaneSide, item: FileItem) {
        if (!item.isFolder || item.path.isBlank()) return
        switchPaneDirectory(side, File(item.path))
    }

    fun navigateUp(side: FilePaneSide) {
        val currentPath = if (side == FilePaneSide.LEFT) {
            _uiState.value.folderPage.currentPath
        } else {
            _uiState.value.folderPage.targetPath
        }
        val parent = File(currentPath).parentFile ?: return
        if (!parent.canRead()) {
            _uiState.update { state ->
                state.copy(folderPage = state.folderPage.copy(actionNotice = "无法访问上级目录：${parent.absolutePath}"))
            }
            return
        }
        switchPaneDirectory(side, parent)
    }

    fun switchPaneDirectory(side: FilePaneSide, dir: File) {
        _uiState.update { state ->
            val updatedFolder = if (side == FilePaneSide.LEFT) {
                state.folderPage.copy(activePane = side, currentPath = dir.absolutePath, actionNotice = null)
            } else {
                state.folderPage.copy(activePane = side, targetPath = dir.absolutePath, actionNotice = null)
            }
            state.copy(folderPage = updatedFolder)
        }
        refreshFilePanes()
    }

    fun switchPaneDirectoryFromSaf(side: FilePaneSide, treeUriString: String) {
        val resolved = fileRepository.resolveSafTreeToDirectory(treeUriString)
        if (resolved != null) {
            switchPaneDirectory(side, resolved)
        } else {
            _uiState.update { state ->
                state.copy(folderPage = state.folderPage.copy(actionNotice = "所选 SAF 目录暂不支持直接浏览，可用于导出目标"))
            }
        }
    }

    fun toggleFileSelection(itemId: String) {
        toggleFileSelection(_uiState.value.folderPage.activePane, itemId)
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

    fun openCreateEntryDialog(isFolder: Boolean = false) {
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.CreateEntry(isFolder = isFolder, draftName = "")
                )
            )
        }
    }

    fun updateCreateEntryDialog(isFolder: Boolean, draftName: String) {
        _uiState.update { state ->
            if (state.folderPage.activeDialog !is FileManagerDialog.CreateEntry) return@update state
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.CreateEntry(isFolder = isFolder, draftName = draftName)
                )
            )
        }
    }

    fun confirmCreateEntry() {
        val dialog = _uiState.value.folderPage.activeDialog as? FileManagerDialog.CreateEntry ?: return
        val parentPath = _uiState.value.folderPage.activeSourcePath
        if (parentPath.isBlank()) return
        probeScope.launch {
            val res = fileRepository.createEntry(File(parentPath), dialog.draftName, dialog.isFolder)
            res.onSuccess { created ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "已创建：${created.name}"
                        )
                    )
                }
                refreshFilePanesSync()
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "创建失败：${err.message}"
                        )
                    )
                }
            }
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
            val res = fileRepository.renameEntry(dialog.item, newName)
            res.onSuccess { renamed ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "已重命名：${dialog.item.name} -> ${renamed.name}"
                        )
                    )
                }
                refreshFilePanesSync()
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "重命名失败：${err.message}"
                        )
                    )
                }
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
        probeScope.launch {
            val res = fileRepository.deleteEntries(dialog.targets)
            res.onSuccess { count ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "已删除 $count 项"
                        )
                    )
                }
                refreshFilePanesSync()
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "删除失败：${err.message}"
                        )
                    )
                }
            }
        }
    }

    fun copySelectedToClipboard() {
        val targets = _uiState.value.folderPage.selectedItems
        if (targets.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    clipboardItems = targets,
                    actionNotice = "已复制 ${targets.size} 项到剪贴板，可切换目录后点击粘贴"
                )
            )
        }
    }

    fun pasteClipboardToActivePane() {
        val folder = _uiState.value.folderPage
        val sources = folder.clipboardItems
        if (sources.isEmpty() || folder.activeSourcePath.isBlank()) return
        val destDir = File(folder.activeSourcePath)
        launchRealCopy(
            operationTitle = "正在粘贴文件...",
            sources = sources,
            destDir = destDir,
            completionNoticePrefix = "已粘贴"
        )
    }

    fun startCopySelectedFiles() {
        val folder = _uiState.value.folderPage
        val targets = folder.selectedItems
        if (targets.isEmpty()) return
        val destination = folder.activeDestinationPath
        if (destination.isBlank()) return
        launchRealCopy(
            operationTitle = "正在复制文件...",
            sources = targets,
            destDir = File(destination),
            completionNoticePrefix = "已完成复制"
        )
    }

    private fun launchRealCopy(
        operationTitle: String,
        sources: List<FileItem>,
        destDir: File,
        completionNoticePrefix: String
    ) {
        fileOpJob?.cancel()
        val firstName = sources.firstOrNull()?.name ?: ""
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.FileOpProgress(
                        operationTitle = operationTitle,
                        currentFileName = firstName,
                        targetDescription = destDir.absolutePath,
                        progressPercent = 0
                    )
                )
            )
        }
        fileOpJob = probeScope.launch {
            val res = fileRepository.copyEntriesWithProgress(sources, destDir) { currentFile, percent ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = FileManagerDialog.FileOpProgress(
                                operationTitle = operationTitle,
                                currentFileName = currentFile,
                                targetDescription = destDir.absolutePath,
                                progressPercent = percent
                            )
                        )
                    )
                }
            }
            res.onSuccess { count ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "$completionNoticePrefix $count 项至 ${destDir.absolutePath}"
                        )
                    )
                }
                refreshFilePanesSync()
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "文件操作失败：${err.message}"
                        )
                    )
                }
            }
        }
    }

    fun openPropertiesDialog() {
        _uiState.update { state ->
            val folder = state.folderPage
            val target = folder.selectedItems.firstOrNull() ?: return@update state
            val resolvedPath = target.path.ifBlank {
                if (folder.activePane == FilePaneSide.LEFT) {
                    pathProvider.buildItemPath(target.name, appContext)
                } else {
                    pathProvider.buildTargetItemPath(target.name, appContext)
                }
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
            val res = fileRepository.applyPermissions(dialog.item, resolvedOctal, dialog.applyRecursively)
            res.onSuccess {
                val recursiveNote = if (dialog.applyRecursively) "（含子目录递归）" else ""
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "已更新权限：${dialog.item.name} -> $resolvedOctal$recursiveNote"
                        )
                    )
                }
                refreshFilePanesSync()
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "权限修改失败：${err.message}"
                        )
                    )
                }
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
        val ctx = appContext ?: return
        val targets = (_uiState.value.folderPage.activeDialog as? FileManagerDialog.ExportSheet)?.targets
            ?: _uiState.value.folderPage.selectedItems
        if (targets.isEmpty()) return

        fileOpJob?.cancel()
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.FileOpProgress(
                        operationTitle = "正在导出至 Download...",
                        currentFileName = targets.first().name,
                        targetDescription = "Download/AICL_Exports",
                        progressPercent = 0
                    )
                )
            )
        }
        fileOpJob = probeScope.launch {
            val res = fileRepository.exportToDownloads(ctx, targets) { currentFile, percent ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = FileManagerDialog.FileOpProgress(
                                operationTitle = "正在导出至 Download...",
                                currentFileName = currentFile,
                                targetDescription = "Download/AICL_Exports",
                                progressPercent = percent
                            )
                        )
                    )
                }
            }
            res.onSuccess { exportedPath ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "已导出 ${targets.size} 项至 $exportedPath"
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "导出失败：${err.message}"
                        )
                    )
                }
            }
        }
    }

    fun startCustomExportToUri(treeUriString: String) {
        val ctx = appContext ?: return
        val targets = (_uiState.value.folderPage.activeDialog as? FileManagerDialog.ExportSheet)?.targets
            ?: _uiState.value.folderPage.selectedItems
        if (targets.isEmpty()) return

        fileOpJob?.cancel()
        _uiState.update { state ->
            state.copy(
                folderPage = state.folderPage.copy(
                    activeDialog = FileManagerDialog.FileOpProgress(
                        operationTitle = "正在导出至所选目录...",
                        currentFileName = targets.first().name,
                        targetDescription = treeUriString,
                        progressPercent = 0
                    )
                )
            )
        }
        fileOpJob = probeScope.launch {
            val res = fileRepository.exportToSafTree(ctx, treeUriString, targets) { currentFile, percent ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = FileManagerDialog.FileOpProgress(
                                operationTitle = "正在导出至所选目录...",
                                currentFileName = currentFile,
                                targetDescription = treeUriString,
                                progressPercent = percent
                            )
                        )
                    )
                }
            }
            res.onSuccess { count ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "已导出 $count 项至所选目录"
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        folderPage = state.folderPage.copy(
                            activeDialog = null,
                            actionNotice = "SAF 导出失败：${err.message}"
                        )
                    )
                }
            }
        }
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

    // ==================== Settings & Maintenance ====================

    fun selectPythonVersion(version: String) {
        updateSettingsState(immediate = false) {
            it.copy(selectedPythonVersion = version, statusNotice = "已选择版本：$version")
        }
    }

    fun triggerPythonDownloadHint() {
        selectTab(MainTab.DOWNLOAD)
    }

    fun selectPythonDir(dir: String) {
        updateSettingsState(immediate = false) {
            it.copy(selectedPythonDir = dir, statusNotice = "已切换目录：$dir")
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

    fun selectThemeMode(theme: String) {
        updateSettingsState(immediate = true) { it.copy(themeMode = theme, statusNotice = "已切换主题：$theme") }
    }

    fun toggleShowHiddenFiles(enabled: Boolean) {
        updateSettingsState(immediate = false) { it.copy(showHiddenFiles = enabled) }
        refreshFilePanes()
    }

    fun toggleAutoCheckUpdate(enabled: Boolean) {
        updateSettingsState(immediate = false) { it.copy(autoCheckUpdate = enabled) }
    }

    fun selectRootfsType(rootfs: String) {
        updateSettingsState(immediate = false) { it.copy(rootfsType = rootfs) }
    }

    fun selectGraphicsRenderer(renderer: String) {
        updateSettingsState(immediate = false) { it.copy(graphicsRenderer = renderer) }
    }

    fun setContainerMountPath(path: String) {
        val cleaned = path.trim()
        if (cleaned.isEmpty()) return
        updateSettingsState(immediate = true) {
            it.copy(containerMountPath = cleaned, statusNotice = "已挂载目录：$cleaned")
        }
    }

    fun clearContainerMountPath() {
        updateSettingsState(immediate = true) {
            it.copy(containerMountPath = "", statusNotice = "已清除挂载目录")
        }
    }

    fun selectCpuCoreLimit(cores: Int) {
        updateSettingsState(immediate = false) { it.copy(cpuCoreLimit = cores) }
    }

    fun selectMemLimitMB(memMb: Int) {
        updateSettingsState(immediate = false) { it.copy(memLimitMB = memMb) }
    }

    fun openResetContainerDialog() {
        _uiState.update { it.copy(settingsPage = it.settingsPage.copy(isResetContainerDialogOpen = true)) }
    }

    fun dismissResetContainerDialog() {
        _uiState.update { it.copy(settingsPage = it.settingsPage.copy(isResetContainerDialogOpen = false)) }
    }

    fun confirmResetContainerSettings() {
        updateSettingsState(immediate = true) {
            it.copy(
                rootfsType = "Termux Proot (Ubuntu)",
                graphicsRenderer = "Turnip + DXVK",
                containerMountPath = "",
                cpuCoreLimit = 0,
                memLimitMB = 0,
                isResetContainerDialogOpen = false,
                statusNotice = "已重置容器运行配置"
            )
        }
    }

    fun openEnvVarsDialog() {
        _uiState.update {
            it.copy(
                settingsPage = it.settingsPage.copy(
                    isEnvVarsDialogOpen = true,
                    envVarsDraft = it.settingsPage.customEnvVars,
                    envVarsError = null
                )
            )
        }
    }

    fun updateEnvVarsDraft(draft: String) {
        _uiState.update {
            it.copy(
                settingsPage = it.settingsPage.copy(
                    envVarsDraft = draft,
                    envVarsError = null
                )
            )
        }
    }

    fun confirmEnvVars() {
        val draft = _uiState.value.settingsPage.envVarsDraft
        val error = validateEnvVars(draft)
        if (error != null) {
            _uiState.update {
                it.copy(settingsPage = it.settingsPage.copy(envVarsError = error))
            }
            return
        }
        updateSettingsState(immediate = true) {
            it.copy(
                customEnvVars = draft.trim(),
                isEnvVarsDialogOpen = false,
                envVarsError = null,
                statusNotice = "已保存自定义环境变量"
            )
        }
    }

    fun dismissEnvVarsDialog() {
        _uiState.update {
            it.copy(
                settingsPage = it.settingsPage.copy(
                    isEnvVarsDialogOpen = false,
                    envVarsError = null
                )
            )
        }
    }

    fun updatePythonDirFromSaf(uriString: String) {
        val cleaned = uriString.trim()
        if (cleaned.isEmpty()) return
        updateSettingsState(immediate = true) { state ->
            val nextOptions = (listOf(cleaned) + state.pythonDirOptions).distinct()
            state.copy(
                selectedPythonDir = cleaned,
                pythonDirOptions = nextOptions,
                statusNotice = "已选择 Python 目录：$cleaned"
            )
        }
    }

    fun refreshCacheSize() {
        probeScope.launch {
            refreshCacheSizeSync()
        }
    }

    private fun refreshCacheSizeSync() {
        val ctx = appContext ?: return
        val bytes = maintenanceManager.calculateCacheSizeBytes(ctx)
        _uiState.update { state ->
            state.copy(
                settingsPage = state.settingsPage.copy(
                    cacheSizeText = if (bytes == 0L) "0 KB" else formatFileSize(bytes)
                )
            )
        }
    }

    fun triggerCleanTempFiles() {
        val ctx = appContext ?: return
        probeScope.launch {
            val res = maintenanceManager.clearCache(ctx)
            res.onSuccess { (freed, remaining) ->
                val freedLabel = if (freed == 0L) "0 KB" else formatFileSize(freed)
                val remLabel = if (remaining == 0L) "0 KB" else formatFileSize(remaining)
                _uiState.update { state ->
                    state.copy(
                        settingsPage = state.settingsPage.copy(
                            cacheSizeText = remLabel,
                            statusNotice = "已清理缓存：释放 $freedLabel"
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        settingsPage = state.settingsPage.copy(
                            statusNotice = "清理缓存失败：${err.message}"
                        )
                    )
                }
            }
        }
    }

    fun triggerExportLogs() {
        val ctx = appContext ?: return
        probeScope.launch {
            val res = maintenanceManager.exportLogcatToDownloads(ctx)
            res.onSuccess { savedPath ->
                _uiState.update { state ->
                    state.copy(
                        settingsPage = state.settingsPage.copy(
                            statusNotice = "已导出 Logcat 至：$savedPath"
                        )
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        settingsPage = state.settingsPage.copy(
                            statusNotice = "导出日志失败：${err.message}"
                        )
                    )
                }
            }
        }
    }

    fun openDisclaimerDialog() {
        _uiState.update { it.copy(settingsPage = it.settingsPage.copy(isDisclaimerDialogOpen = true)) }
    }

    fun dismissDisclaimerDialog() {
        _uiState.update { it.copy(settingsPage = it.settingsPage.copy(isDisclaimerDialogOpen = false)) }
    }

    fun openLicensesDialog() {
        _uiState.update { it.copy(settingsPage = it.settingsPage.copy(isLicensesDialogOpen = true)) }
    }

    fun dismissLicensesDialog() {
        _uiState.update { it.copy(settingsPage = it.settingsPage.copy(isLicensesDialogOpen = false)) }
    }

    fun onVersionRowClick() {
        val current = _uiState.value.settingsPage
        if (current.isDevModeUnlocked) {
            _uiState.update {
                it.copy(settingsPage = it.settingsPage.copy(statusNotice = "开发者选项已处于解锁状态"))
            }
            return
        }
        val nextCount = current.versionTapCount + 1
        if (nextCount >= 7) {
            updateSettingsState(immediate = true) {
                it.copy(
                    versionTapCount = 7,
                    isDevModeUnlocked = true,
                    statusNotice = "已解锁开发者选项"
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    settingsPage = it.settingsPage.copy(
                        versionTapCount = nextCount,
                        statusNotice = if (nextCount >= 4) "再点击 ${7 - nextCount} 次解锁开发者选项" else null
                    )
                )
            }
        }
    }

    fun selectLogLevel(level: String) {
        updateSettingsState(immediate = false) { it.copy(logLevel = level) }
    }

    fun toggleForceSoftwareRendering(enabled: Boolean) {
        updateSettingsState(immediate = false) { it.copy(forceSoftwareRendering = enabled) }
    }

    private fun hydrateSettingsFromStore(data: SettingsData) {
        _uiState.update { state ->
            val nextDirOptions = if (data.selectedPythonDir.isNotBlank()) {
                (listOf(data.selectedPythonDir) + state.settingsPage.pythonDirOptions).distinct()
            } else {
                state.settingsPage.pythonDirOptions
            }
            state.copy(
                settingsPage = state.settingsPage.copy(
                    themeMode = data.themeMode,
                    showHiddenFiles = data.showHiddenFiles,
                    autoCheckUpdate = data.autoCheckUpdate,
                    rootfsType = data.rootfsType,
                    graphicsRenderer = data.graphicsRenderer,
                    containerMountPath = data.containerMountPath,
                    cpuCoreLimit = data.cpuCoreLimit,
                    memLimitMB = data.memLimitMB,
                    customEnvVars = data.customEnvVars,
                    selectedPythonVersion = data.selectedPythonVersion,
                    selectedPythonDir = data.selectedPythonDir,
                    pythonDirOptions = nextDirOptions,
                    logLevel = data.logLevel,
                    isDevModeUnlocked = data.isDevModeUnlocked,
                    forceSoftwareRendering = data.forceSoftwareRendering,
                    versionName = data.versionName
                )
            )
        }
    }

    private fun updateSettingsState(
        immediate: Boolean,
        transform: (SettingsPageState) -> SettingsPageState
    ) {
        _uiState.update { state ->
            val nextPage = transform(state.settingsPage)
            settingsManager?.update(immediate = immediate) { current ->
                current.copy(
                    themeMode = nextPage.themeMode,
                    showHiddenFiles = nextPage.showHiddenFiles,
                    autoCheckUpdate = nextPage.autoCheckUpdate,
                    rootfsType = nextPage.rootfsType,
                    graphicsRenderer = nextPage.graphicsRenderer,
                    containerMountPath = nextPage.containerMountPath,
                    cpuCoreLimit = nextPage.cpuCoreLimit,
                    memLimitMB = nextPage.memLimitMB,
                    customEnvVars = nextPage.customEnvVars,
                    selectedPythonVersion = nextPage.selectedPythonVersion,
                    selectedPythonDir = nextPage.selectedPythonDir,
                    logLevel = nextPage.logLevel,
                    isDevModeUnlocked = nextPage.isDevModeUnlocked,
                    forceSoftwareRendering = nextPage.forceSoftwareRendering,
                    versionName = nextPage.versionName
                )
            }
            state.copy(settingsPage = nextPage)
        }
    }

    private fun updateDownloadFilter(transform: (DownloadFilterState) -> DownloadFilterState) {
        _uiState.update { state ->
            val nextFilter = transform(state.downloadPage.filter)
            val filtered = DownloadCatalogFilter.filterItems(state.downloadPage.allItems, nextFilter)
            state.copy(
                downloadPage = state.downloadPage.copy(
                    filter = nextFilter,
                    items = filtered
                )
            )
        }
    }
}
