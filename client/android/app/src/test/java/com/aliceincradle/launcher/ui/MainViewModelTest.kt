package com.aliceincradle.launcher.ui

import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadTaskItem
import com.aliceincradle.launcher.data.FolderAction
import com.aliceincradle.launcher.data.FolderEntryType
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.GameRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {
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
    fun folderPageExposesStatsTypesAndActionTransitions() {
        val vm = MainViewModel()
        val folder = vm.uiState.value.folderPage

        assertEquals("共 3 个文件夹，7 个文件", folder.countSummary)
        assertEquals("共占 37GB", folder.totalSizeSummary)
        assertEquals(10, folder.items.size)
        val types = folder.items.map { it.type }.toSet()
        assertTrue(types.contains(FolderEntryType.FOLDER))
        assertTrue(types.contains(FolderEntryType.TEXT))
        assertTrue(types.contains(FolderEntryType.PYTHON))
        assertTrue(types.contains(FolderEntryType.SHELL))
        assertTrue(types.contains(FolderEntryType.EXECUTABLE))
        assertEquals("AliceInCradle.exe", folder.items.last().name)
        assertTrue(folder.items.last().isLauncherTarget)

        vm.selectFolderItem("file-patch-py")
        vm.performFolderAction(FolderAction.INFO)
        assertEquals("file-patch-py", vm.uiState.value.folderPage.inspectedItem?.id)
        vm.dismissFolderItemInfo()
        assertNull(vm.uiState.value.folderPage.inspectedItem)

        vm.performFolderAction(FolderAction.PLACE)
        assertEquals("剪贴板为空，请先执行 Copy", vm.uiState.value.folderPage.actionNotice)

        vm.performFolderAction(FolderAction.COPY)
        assertEquals("patch_unsafeAssem.py", vm.uiState.value.folderPage.clipboardItemName)
        vm.performFolderAction(FolderAction.PLACE)
        assertTrue(vm.uiState.value.folderPage.actionNotice?.contains("patch_unsafeAssem.py") == true)

        vm.setCanLaunchGame(false)
        vm.performFolderAction(FolderAction.RUNNING)
        assertFalse(vm.uiState.value.gameProcess.isAlive)

        vm.setCanLaunchGame(true)
        vm.performFolderAction(FolderAction.RUNNING)
        assertTrue(vm.uiState.value.gameProcess.isAlive)
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
}
