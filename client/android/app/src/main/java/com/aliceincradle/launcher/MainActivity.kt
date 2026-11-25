package com.aliceincradle.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aliceincradle.launcher.ui.GameScreen
import com.aliceincradle.launcher.ui.GameViewModel

class MainActivity : ComponentActivity() {
    private val gameViewModel = GameViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GameScreen(viewModel = gameViewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        gameViewModel.clear()
        super.onDestroy()
    }
}
