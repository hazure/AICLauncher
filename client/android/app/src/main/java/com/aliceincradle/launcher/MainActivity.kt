package com.aliceincradle.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.aliceincradle.launcher.data.AgreementManager
import com.aliceincradle.launcher.ui.AgreementScreen
import com.aliceincradle.launcher.ui.MainScreen
import com.aliceincradle.launcher.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.loadDeviceInfo(applicationContext)
        val agreementManager = AgreementManager(applicationContext)

        setContent {
            val hasAgreed by agreementManager.isAgreed.collectAsState(initial = agreementManager.isAgreedSync())
            val uiState by viewModel.uiState.collectAsState()
            val isDarkTheme = when (uiState.settingsPage.themeMode) {
                "深色" -> true
                "浅色" -> false
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(
                colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!hasAgreed) {
                        AgreementScreen(
                            onAgree = { agreementManager.saveAgreed() },
                            onExit = { finish() }
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
