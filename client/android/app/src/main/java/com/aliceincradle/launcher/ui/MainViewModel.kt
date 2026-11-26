package com.aliceincradle.launcher.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliceincradle.launcher.data.DeviceInfo
import com.aliceincradle.launcher.data.DeviceInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NavTab(val label: String) {
    HOME("主页"),
    DOWNLOAD("下载"),
    FOLDER("文件夹"),
    SETTINGS("设置")
}

data class MainUiState(
    val isLoading: Boolean = true,
    val deviceInfo: DeviceInfo? = null,
    val selectedTab: NavTab = NavTab.HOME
)

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun loadHardwareInfo(context: Context) {
        val appContext = context.applicationContext
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val info = DeviceInfoRepository.loadDeviceInfo(appContext)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                deviceInfo = info
            )
        }
    }

    fun selectTab(tab: NavTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }
}
