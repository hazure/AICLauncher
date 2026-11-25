package com.aliceincradle.launcher.ui

import com.aliceincradle.launcher.bridge.EnvStatus
import com.aliceincradle.launcher.bridge.ProcessBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class GameUiState(
    val envStatus: EnvStatus = EnvStatus.UNKNOWN_ERROR,
    val isChecking: Boolean = true
)

class GameViewModel {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    fun checkEnvironment(sandboxDir: File) {
        _uiState.value = _uiState.value.copy(isChecking = true)
        scope.launch(Dispatchers.IO) {
            val status = ProcessBridge.checkEnvironment(sandboxDir)
            _uiState.value = GameUiState(envStatus = status, isChecking = false)
        }
    }

    fun startGame(sandboxDir: File, onError: (String) -> Unit) {
        if (_uiState.value.envStatus != EnvStatus.READY) {
            return
        }
        scope.launch(Dispatchers.IO) {
            ProcessBridge.startGame(sandboxDir) { err ->
                scope.launch(Dispatchers.Main.immediate) {
                    onError(err)
                }
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}
