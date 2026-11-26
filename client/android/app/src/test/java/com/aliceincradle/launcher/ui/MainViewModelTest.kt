package com.aliceincradle.launcher.ui

import com.aliceincradle.launcher.data.DownloadTaskItem
import com.aliceincradle.launcher.data.GameProcessState
import com.aliceincradle.launcher.data.GameRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
