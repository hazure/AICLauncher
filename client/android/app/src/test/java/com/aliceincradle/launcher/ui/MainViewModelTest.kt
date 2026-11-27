package com.aliceincradle.launcher.ui

import com.aliceincradle.launcher.bridge.EnvDetector
import com.aliceincradle.launcher.data.CatalogHttpClient
import com.aliceincradle.launcher.data.DefaultPathProvider
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadQueueManager
import com.aliceincradle.launcher.data.FileManagerDialog
import com.aliceincradle.launcher.data.FilePaneSide
import com.aliceincradle.launcher.data.MirrorHttpClient
import com.aliceincradle.launcher.data.MirrorLinkSniffer
import com.aliceincradle.launcher.data.PosixPermissions
import com.aliceincradle.launcher.data.RemoteCatalogFetcher
import com.aliceincradle.launcher.data.SettingsManager
import com.aliceincradle.launcher.data.SystemTelemetryProvider
import com.aliceincradle.launcher.data.validateEnvVars
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MainViewModelTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun telemetryDialogsOpenAndDismissCorrectly() {
        val vm = MainViewModel()
        TelemetryDialogType.entries.filter { it != TelemetryDialogType.NONE }.forEach { type ->
            vm.openTelemetryDialog(type)
            assertEquals(type, vm.uiState.value.activeDialog)
            vm.dismissTelemetryDialog()
            assertEquals(TelemetryDialogType.NONE, vm.uiState.value.activeDialog)
        }
    }

    @Test
    fun downloadPageFetchesCatalogFiltersAndRunsChannelQueue() {
        val sampleItems = listOf(
            com.aliceincradle.launcher.data.DownloadCatalogItem(
                id = "aic-core-0.30",
                title = "Alice In Cradle 0.30",
                tags = listOf("游戏本体", "稳定版"),
                sizeLabel = "1.0 KB",
                sizeBytes = 1024L,
                status = DownloadContentStatus.AVAILABLE,
                appName = "AIC",
                versionChannel = "稳定版",
                category = "游戏本体",
                isCompatibleVersion = true,
                isFeatured = true,
                author = "hazure",
                downloadUrl = "https://example.com/aic.zip",
                fileName = "aic.zip"
            ),
            com.aliceincradle.launcher.data.DownloadCatalogItem(
                id = "mod-legacy-relic",
                title = "Legacy Relic Pack",
                tags = listOf("武器强化", "旧版"),
                sizeLabel = "512 B",
                sizeBytes = 512L,
                status = DownloadContentStatus.DISABLED,
                appName = "AIC",
                versionChannel = "稳定版",
                category = "武器强化",
                isCompatibleVersion = false,
                isFeatured = false,
                author = "ArchiveTeam",
                downloadUrl = ""
            )
        )
        val fetcher = object : RemoteCatalogFetcher(CatalogHttpClient { "[]" }) {
            override fun fetchCatalog(downloadDir: File?) = Result.success(sampleItems)
        }
        val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = DownloadQueueManager(
            scope = queueScope,
            ioDispatcher = Dispatchers.Unconfined,
            workerCount = 1,
            streamDownloader = { req, listener ->
                req.destFile.parentFile?.mkdirs()
                req.destFile.writeText("downloaded-payload")
                listener.onProgress(req.itemId, 18L, 18L, 100)
                listener.onCompleted(req.itemId, req.destFile)
            }
        )
        val vm = MainViewModel(
            ioDispatcher = Dispatchers.Unconfined,
            autoStartPolling = false,
            catalogFetcher = fetcher,
            downloadQueueManagerOverride = queue,
            autoFetchCatalog = true
        )

        val initialPage = vm.uiState.value.downloadPage
        assertEquals(3, initialPage.allItems.size)
        assertTrue(initialPage.allItems.first().isMirrorSniffTarget)
        assertTrue(initialPage.statsText.contains("共 3 个内容"))

        vm.updateDownloadQuery("Legacy Relic")
        assertEquals(1, vm.uiState.value.downloadPage.items.size)
        assertEquals("mod-legacy-relic", vm.uiState.value.downloadPage.items.first().id)

        vm.updateDownloadQuery("")
        vm.triggerCatalogDownload("aic-core-0.30")
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            val cur = vm.uiState.value.downloadPage.allItems.first { it.id == "aic-core-0.30" }
            if (cur.status == DownloadContentStatus.DOWNLOADED) break
            Thread.sleep(20)
        }
        val downloaded = vm.uiState.value.downloadPage.allItems.first { it.id == "aic-core-0.30" }
        assertEquals(DownloadContentStatus.DOWNLOADED, downloaded.status)
        assertEquals(100, downloaded.progressPercent)
        assertEquals(0, vm.uiState.value.downloadPage.activeDownloadCount)
    }

    @Test
    fun folderPageRealFileIoCreateRenameCopyPastePermissionsAndDelete() {
        val leftDir = tempFolder.newFolder("left_pane")
        val rightDir = tempFolder.newFolder("right_pane")
        val exeFile = File(leftDir, "AliceInCradle.exe").apply { writeText("MZ-stub-payload") }

        val vm = MainViewModel(
            ioDispatcher = Dispatchers.Unconfined,
            autoStartPolling = false,
            autoFetchCatalog = false
        )
        vm.initFileDirectoriesForTest(leftDir, rightDir)

        val initial = vm.uiState.value.folderPage
        assertEquals(leftDir.absolutePath, initial.currentPath)
        assertEquals(rightDir.absolutePath, initial.targetPath)
        assertEquals(1, initial.items.size)
        assertEquals("AliceInCradle.exe", initial.items.first().name)

        // Create new file
        vm.openCreateEntryDialog(isFolder = false)
        vm.updateCreateEntryDialog(isFolder = false, draftName = "notes.txt")
        vm.confirmCreateEntry()
        assertTrue(File(leftDir, "notes.txt").exists())
        val items = vm.uiState.value.folderPage.items
        assertTrue(items.any { it.name == "notes.txt" })

        // Select AliceInCradle.exe and rename
        vm.toggleFileSelection(FilePaneSide.LEFT, exeFile.absolutePath)
        vm.openRenameDialog()
        vm.updateRenameDraft("Alice_Patched.exe")
        vm.confirmRename()
        assertTrue(File(leftDir, "Alice_Patched.exe").exists())
        assertFalse(exeFile.exists())

        // Copy to right pane
        val renamedPath = File(leftDir, "Alice_Patched.exe").absolutePath
        vm.toggleFileSelection(FilePaneSide.LEFT, renamedPath)
        vm.startCopySelectedFiles()
        assertTrue(File(rightDir, "Alice_Patched.exe").exists())
        assertNull(vm.uiState.value.folderPage.activeDialog)

        // Delete from left pane
        vm.toggleFileSelection(FilePaneSide.LEFT, renamedPath)
        vm.openDeleteConfirmDialog()
        vm.confirmDeleteSelected()
        assertFalse(File(leftDir, "Alice_Patched.exe").exists())
    }

    @Test
    fun pathProviderResolvesDynamicLabelsWithoutHardcodedStoragePaths() {
        val gameDir = tempFolder.newFolder("aic_game_dir")
        File(gameDir, "AliceInCradle.exe").writeText("stub")
        val detector = EnvDetector(
            procRoot = tempFolder.newFolder("empty_proc"),
            allowBareCommandFallback = false,
            envProvider = { mapOf("AIC_GAME_DIR" to gameDir.absolutePath) }
        )
        val provider = DefaultPathProvider(detector)
        assertEquals("${gameDir.absolutePath}${File.separator}Mods", provider.currentDirectoryLabel(null))
        assertEquals(gameDir.absolutePath, provider.targetDirectoryLabel(null))
        assertTrue(provider.downloadExportTargets(null).isNotEmpty())
        assertTrue(provider.buildItemPath("Mods", null).endsWith("Mods"))
    }

    @Test
    fun settingsPageExposesPythonDefaultsAndUpdatesCustomPath() {
        val vm = MainViewModel()
        val settings = vm.uiState.value.settingsPage

        assertEquals("Python 3.10", settings.selectedPythonVersion)
        assertEquals("无法检测到？点击下载 Python！", settings.pythonWarningText)
        assertEquals("D:/python", settings.selectedPythonDir)
        assertEquals("自定义路径选择", settings.customPathButtonText)
        assertEquals("Python 设置", settings.reservedButtonLabel)
        assertEquals("此处不再多举例！", settings.reservedHintText)

        vm.selectPythonVersion("Python 3.11")
        assertEquals("Python 3.11", vm.uiState.value.settingsPage.selectedPythonVersion)

        vm.openCustomPathDialog()
        assertTrue(vm.uiState.value.settingsPage.isCustomPathDialogOpen)
        vm.updateCustomPathDraft("E:/tools/python311")
        vm.confirmCustomPath()
        assertFalse(vm.uiState.value.settingsPage.isCustomPathDialogOpen)
        assertEquals("E:/tools/python311", vm.uiState.value.settingsPage.selectedPythonDir)
        assertTrue(vm.uiState.value.settingsPage.pythonDirOptions.contains("E:/tools/python311"))
        vm.openCustomPathDialog()
        assertTrue(vm.uiState.value.settingsPage.isCustomPathDialogOpen)
        vm.updateCustomPathDraft("   ")
        vm.confirmCustomPath()
        assertFalse(vm.uiState.value.settingsPage.isCustomPathDialogOpen)
        assertEquals("E:/tools/python311", vm.uiState.value.settingsPage.selectedPythonDir)
    }

    @Test
    fun refreshTelemetryAndEnvDrivesRealState() = runBlocking {
        val proc = tempFolder.newFolder("proc_vm")
        val sys = tempFolder.newFolder("sys_vm")
        File(proc, "meminfo").writeText(
            """
            MemTotal:       4096000 kB
            MemAvailable:   1024000 kB
            """.trimIndent()
        )
        val zone0 = File(sys, "class/thermal/thermal_zone0").apply { mkdirs() }
        File(zone0, "type").writeText("gpu-thermal")
        File(zone0, "temp").writeText("51200")

        val vm = MainViewModel(
            telemetryProvider = SystemTelemetryProvider(procRoot = proc, sysRoot = sys),
            envDetector = EnvDetector(
                procRoot = proc,
                allowBareCommandFallback = false,
                envProvider = { emptyMap() }
            ),
            ioDispatcher = Dispatchers.Unconfined,
            autoStartPolling = false
        )

        vm.refreshTelemetryAndEnv(includeEnv = true)
        val refreshed = vm.uiState.value
        assertFalse(refreshed.isLoading)
        assertTrue(refreshed.telemetry.memory.isAvailable)
        assertEquals(0.75f, refreshed.telemetry.memory.usagePercent, 0.01f)
        assertEquals(51, refreshed.telemetry.thermal.headlineTempC)
        assertEquals(EnvDetector.MISSING_PYTHON_LABEL, refreshed.deviceInfo.pythonVersion)
    }

    @Test
    fun settingsManagerPersistsAtomicallyAndDevModeUnlocksAfter7Taps() = runBlocking {
        val storeFile = File(tempFolder.root, "aicl_settings_test.json")
        val manager = SettingsManager(
            storageFile = storeFile,
            ioDispatcher = Dispatchers.Unconfined,
            debounceMs = 0L
        )
        val vm = MainViewModel(
            ioDispatcher = Dispatchers.Unconfined,
            autoStartPolling = false,
            settingsManager = manager
        )

        assertEquals("第 1 行格式错误：必须包含 '=' 且键名不能为空", validateEnvVars("INVALID_LINE"))
        assertNull(validateEnvVars("MESA_GL_VERSION_OVERRIDE=4.6\nPROTON_LOG=1"))

        vm.setContainerMountPath("content://com.android.externalstorage.documents/tree/primary%3AAIC")
        vm.selectCpuCoreLimit(4)
        vm.openEnvVarsDialog()
        vm.updateEnvVarsDraft("BAD_ENV")
        vm.confirmEnvVars()
        assertTrue(vm.uiState.value.settingsPage.envVarsError != null)
        vm.updateEnvVarsDraft("FOO=bar")
        vm.confirmEnvVars()
        assertNull(vm.uiState.value.settingsPage.envVarsError)
        assertEquals("FOO=bar", vm.uiState.value.settingsPage.customEnvVars)

        repeat(6) { vm.onVersionRowClick() }
        assertFalse(vm.uiState.value.settingsPage.isDevModeUnlocked)
        vm.onVersionRowClick()
        assertTrue(vm.uiState.value.settingsPage.isDevModeUnlocked)

        manager.flush()
        val reloaded = SettingsManager(storageFile = storeFile, ioDispatcher = Dispatchers.Unconfined, debounceMs = 0L)
        assertEquals(4, reloaded.settingsFlow.value.cpuCoreLimit)
        assertEquals("FOO=bar", reloaded.settingsFlow.value.customEnvVars)
        assertTrue(reloaded.settingsFlow.value.isDevModeUnlocked)

        vm.openResetContainerDialog()
        assertTrue(vm.uiState.value.settingsPage.isResetContainerDialogOpen)
        vm.confirmResetContainerSettings()
        assertFalse(vm.uiState.value.settingsPage.isResetContainerDialogOpen)
        assertEquals(0, vm.uiState.value.settingsPage.cpuCoreLimit)
        assertEquals("", vm.uiState.value.settingsPage.containerMountPath)
    }

    @Test
    fun mirrorLinkSnifferExtractsArchiveUrlAndHandlesFailureGracefully() {
        val html = """
            <html><script>
            window.__DL_URL__ = "https://cdn.example.com/files/AliceInCradle_ver0.30a.7z?token=abc";
            </script></html>
        """.trimIndent()
        val sniffer = MirrorLinkSniffer(MirrorHttpClient { html })
        val ok = sniffer.sniffLatestWinLink()
        assertTrue(ok.isSuccess)
        assertEquals("AliceInCradle_ver0.30a.7z", ok.getOrThrow().fileName)
        assertEquals(
            "https://cdn.example.com/files/AliceInCradle_ver0.30a.7z?token=abc",
            ok.getOrThrow().directUrl
        )

        val failingSniffer = MirrorLinkSniffer(MirrorHttpClient { throw java.io.IOException("network down") })
        val vm = MainViewModel(
            ioDispatcher = Dispatchers.Unconfined,
            autoStartPolling = false,
            autoFetchCatalog = false,
            mirrorLinkSniffer = failingSniffer
        )
        vm.refreshDownloadCatalog()
        vm.triggerCatalogDownload(MainViewModel.OFFICIAL_MIRROR_ITEM_ID)
        assertEquals("链接解析失败，请检查网络环境", vm.uiState.value.downloadPage.mirrorSniffError)
        val mirrorItem = vm.uiState.value.downloadPage.allItems.first { it.id == MainViewModel.OFFICIAL_MIRROR_ITEM_ID }
        assertEquals(DownloadContentStatus.FAILED, mirrorItem.status)
    }
}
