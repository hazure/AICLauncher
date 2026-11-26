package com.aliceincradle.launcher.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliceincradle.launcher.data.DeviceInfo
import com.aliceincradle.launcher.data.DeviceInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MainTab(val label: String) {
    HOME("主页"),
    DOWNLOAD("下载"),
    FILES("文件夹"),
    SETTINGS("设置")
}

data class MainUiState(
    val isLoading: Boolean = true,
    val deviceInfo: DeviceInfo = DeviceInfo(),
    val selectedTab: MainTab = MainTab.HOME
)

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun loadDeviceInfo(appContext: Context) {
        if (hasLoaded) return
        hasLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            val info = DeviceInfoProvider.load(appContext)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                deviceInfo = info
            )
        }
    }

    fun selectTab(tab: MainTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }
}
