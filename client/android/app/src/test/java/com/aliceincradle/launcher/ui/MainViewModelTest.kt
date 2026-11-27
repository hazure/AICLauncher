package com.aliceincradle.launcher.ui

import com.aliceincradle.launcher.bridge.EnvDetector
import com.aliceincradle.launcher.data.DefaultPathProvider
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadTaskItem
import com.aliceincradle.launcher.data.FileManagerDialog
import com.aliceincradle.launcher.data.FilePaneSide
import com.aliceincradle.launcher.data.FolderEntryType
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.GameRunStatus
import com.aliceincradle.launcher.data.PosixPermissions
import com.aliceincradle.launcher.data.SystemTelemetryProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
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
    fun initialStateShowsLaunchButtonAndHidesStatusCardAndDownloadButton() {
        val vm = MainViewModel()
        val state = vm.uiState.value

        assertFalse(state.gameProcess.isAlive)
        assertTrue(state.fabVisibility.showLaunch)
        assertFalse(state.fabVisibility.showTerminate)
        assertFalse(state.fabVisibility.showDownloads)
        assertTrue(state.fabVisibility.isContainerVisible)
    }

    @Test
    fun launchAndTerminateTogglesGameStatusCardAndFabs() {
        val vm = MainViewModel()

        vm.launchGame()
        var state = vm.uiState.value
        assertTrue(state.gameProcess.isAlive)
        assertFalse(state.fabVisibility.showLaunch)
        assertTrue(state.fabVisibility.showTerminate)

        vm.terminateGame()
        state = vm.uiState.value
        assertEquals(GameRunStatus.TERMINATED, state.gameProcess.status)
        assertFalse(state.gameProcess.isAlive)
        assertTrue(state.fabVisibility.showLaunch)
        assertFalse(state.fabVisibility.showTerminate)
    }

    @Test
    fun normalExitHidesGameStatusCard() {
        val vm = MainViewModel()
        vm.updateGameProcess(GameProcessState(status = GameRunStatus.RUNNING, pid = 1234))
        assertTrue(vm.uiState.value.gameProcess.isAlive)

        vm.exitGameNormally()
        assertFalse(vm.uiState.value.gameProcess.isAlive)
        assertEquals(GameRunStatus.STOPPED, vm.uiState.value.gameProcess.status)
    }

    @Test
    fun downloadButtonAndContainerVisibilityFollowState() {
        val vm = MainViewModel()
        vm.updateDownloads(listOf(DownloadTaskItem("1", "Turnip", 50)))
        assertTrue(vm.uiState.value.fabVisibility.showDownloads)

        vm.updateDownloads(emptyList())
        assertFalse(vm.uiState.value.fabVisibility.showDownloads)

        vm.setCanLaunchGame(false)
        assertFalse(vm.uiState.value.fabVisibility.isContainerVisible)
    }

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
    fun downloadPageContainsAllRequiredStatusesAndFiltersCorrectly() {
        val vm = MainViewModel()
        val initialPage = vm.uiState.value.downloadPage

        assertEquals("共 23333 个内容，耗时 0.01s", initialPage.statsText)
        assertTrue(initialPage.items.size >= 3)
        val statuses = initialPage.items.map { it.status }.toSet()
        assertTrue(statuses.contains(DownloadContentStatus.DOWNLOADED))
        assertTrue(statuses.contains(DownloadContentStatus.DOWNLOADING))
        assertTrue(statuses.contains(DownloadContentStatus.DISABLED))

        vm.updateDownloadQuery("Legacy Relic")
        assertEquals(1, vm.uiState.value.downloadPage.items.size)
        assertEquals("mod-legacy-relic", vm.uiState.value.downloadPage.items.first().id)

        vm.updateDownloadQuery("")
        vm.selectDownloadCategory("游戏本体")
        assertTrue(vm.uiState.value.downloadPage.items.all { it.category == "游戏本体" })

        val firstItem = vm.uiState.value.downloadPage.items.first()
        vm.openCatalogItemDetail(firstItem)
        assertEquals(firstItem.id, vm.uiState.value.downloadPage.selectedDetailItem?.id)
        vm.dismissCatalogItemDetail()
        assertNull(vm.uiState.value.downloadPage.selectedDetailItem)
    }

    @Test
    fun folderPageStateMachineSelectionDialogsPermissionsAndIoOperations() {
        val vm = MainViewModel(
            ioDispatcher = Dispatchers.Unconfined,
            autoStartPolling = false,
            fileOpStepDelayMs = 0L
        )
        val initial = vm.uiState.value.folderPage

        assertEquals(DefaultPathProvider.DEFAULT_CURRENT_DIR, initial.currentPath)
        assertEquals(DefaultPathProvider.DEFAULT_TARGET_DIR, initial.targetPath)
        assertEquals(9, initial.items.size)
        assertFalse(initial.hasSelection)
        assertEquals("选中 0 项文件，约 0 MB", initial.selectionSummaryText)
        assertEquals("共 6 个文件，3 个文件夹", initial.totalCountSummaryText)

        // Clicking actions without selection leaves activeDialog null
        vm.openRenameDialog()
        vm.openDeleteConfirmDialog()
        vm.openPropertiesDialog()
        vm.openExportSheet()
        assertNull(vm.uiState.value.folderPage.activeDialog)

        // Select AliceInCradle.exe and verify stats + rename
        vm.toggleFileSelection("file-alice-exe")
        assertTrue(vm.uiState.value.folderPage.hasSelection)
        assertEquals("选中 1 项文件，约 64.0 MB", vm.uiState.value.folderPage.selectionSummaryText)

        vm.openRenameDialog()
        assertTrue(vm.uiState.value.folderPage.activeDialog is FileManagerDialog.Rename)
        vm.updateRenameDraft("Alice_Patched.exe")
        vm.confirmRename()
        assertNull(vm.uiState.value.folderPage.activeDialog)
        assertEquals("Alice_Patched.exe", vm.uiState.value.folderPage.items.last().name)

        // Properties -> PermissionEdit with SUID/SGID/Sticky + bidirectional 4-digit octal + recursive
        vm.openPropertiesDialog()
        val propDialog = vm.uiState.value.folderPage.activeDialog as FileManagerDialog.Properties
        assertTrue(propDialog.resolvedPath.endsWith("Alice_Patched.exe"))

        vm.openPermissionEditDialog()
        val permDialog = vm.uiState.value.folderPage.activeDialog as FileManagerDialog.PermissionEdit
        assertEquals("0777", permDialog.octalInput)

        vm.updatePermissionFlags {
            it.copy(suid = true, groupW = false, otherW = false)
        }
        val afterCheckbox = vm.uiState.value.folderPage.activeDialog as FileManagerDialog.PermissionEdit
        assertEquals("4755", afterCheckbox.octalInput)

        vm.updatePermissionOctalInput("600")
        val afterOctal = vm.uiState.value.folderPage.activeDialog as FileManagerDialog.PermissionEdit
        assertTrue(afterOctal.permissions.ownerR)
        assertTrue(afterOctal.permissions.ownerW)
        assertFalse(afterOctal.permissions.ownerX)
        assertFalse(afterOctal.permissions.groupR)
        assertFalse(afterOctal.permissions.suid)

        vm.updatePermissionOctalInput("4755")
        vm.togglePermissionRecursive(true)
        vm.confirmPermissionEdit()
        val updatedExe = vm.uiState.value.folderPage.items.last()
        assertEquals("4755", updatedExe.permissions)
        assertTrue(updatedExe.recursiveApplied)
        assertNull(vm.uiState.value.folderPage.activeDialog)
        assertEquals("rwsr-xr-x", PosixPermissions.fromOctal("4755")?.toSymbolicString())

        // Simulated copy & export progress completes and closes dialog
        vm.startCopySelectedFiles()
        assertNull(vm.uiState.value.folderPage.activeDialog)
        assertTrue(vm.uiState.value.folderPage.actionNotice?.contains("已完成复制") == true)

        vm.openExportSheet()
        val exportSheet = vm.uiState.value.folderPage.activeDialog as FileManagerDialog.ExportSheet
        assertTrue(exportSheet.downloadTargets.isNotEmpty())
        vm.startCustomExportToUri("content://com.android.externalstorage.documents/tree/primary%3AAIC")
        assertNull(vm.uiState.value.folderPage.activeDialog)
        assertTrue(vm.uiState.value.folderPage.actionNotice?.contains("primary%3AAIC") == true)

        // Delete confirm removes selected item
        vm.openDeleteConfirmDialog()
        assertTrue(vm.uiState.value.folderPage.activeDialog is FileManagerDialog.DeleteConfirm)
        vm.confirmDeleteSelected()
        assertEquals(8, vm.uiState.value.folderPage.items.size)
        assertNull(vm.uiState.value.folderPage.activeDialog)

        // Switching to RIGHT pane selects target directory items independently and copies back to LEFT path
        vm.toggleFileSelection(FilePaneSide.RIGHT, "dst-dll-unity")
        assertEquals(FilePaneSide.RIGHT, vm.uiState.value.folderPage.activePane)
        assertEquals("共 6 个文件，4 个文件夹", vm.uiState.value.folderPage.totalCountSummaryText)
        assertEquals(1, vm.uiState.value.folderPage.selectedCount)
        assertEquals(DefaultPathProvider.DEFAULT_CURRENT_DIR, vm.uiState.value.folderPage.activeDestinationPath)
        vm.startCopySelectedFiles()
        assertTrue(vm.uiState.value.folderPage.actionNotice?.contains(DefaultPathProvider.DEFAULT_CURRENT_DIR) == true)

        vm.selectFilePane(FilePaneSide.LEFT)
        assertEquals("共 5 个文件，3 个文件夹", vm.uiState.value.folderPage.totalCountSummaryText)
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
    fun refreshTelemetryAndEnvDrivesRealStateAndRespectsSimulatedProcess() = runBlocking {
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
        assertFalse(refreshed.gameProcess.isAlive)

        // Simulated launch must not be overridden when external procfs shows no game process.
        vm.launchGame()
        assertTrue(vm.uiState.value.gameProcess.isAlive)
        vm.refreshTelemetryAndEnv(includeEnv = false)
        assertTrue(vm.uiState.value.gameProcess.isAlive)

        // Real detected game process takes precedence.
        val pidDir = File(proc, "9876").apply { mkdirs() }
        File(pidDir, "cmdline").writeText("AliceInCradle.exe")
        vm.refreshTelemetryAndEnv(includeEnv = false)
        assertTrue(vm.uiState.value.gameProcess.isAlive)
        assertEquals(9876, vm.uiState.value.gameProcess.pid)
    }
}
