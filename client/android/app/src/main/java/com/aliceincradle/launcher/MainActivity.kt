package com.aliceincradle.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            var hasAgreed by remember { mutableStateOf(agreementManager.isAgreed()) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!hasAgreed) {
                        AgreementScreen(
                            onAgree = {
                                agreementManager.setAgreed(true)
                                hasAgreed = true
                            },
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
