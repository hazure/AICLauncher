package com.aliceincradle.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.bridge.EnvStatus

@Composable
fun GameScreen(viewModel: GameViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkEnvironment(context.filesDir)
    }

    val statusText = when {
        uiState.isChecking -> "状态：正在探测环境..."
        uiState.envStatus == EnvStatus.READY -> "状态：环境就绪"
        uiState.envStatus == EnvStatus.MISSING_PYTHON -> "未检测到运行环境，请确认 Termux 已安装"
        uiState.envStatus == EnvStatus.MISSING_GAME -> "未找到游戏资源目录"
        else -> "环境异常，请重启应用"
    }

    val buttonText = when (uiState.envStatus) {
        EnvStatus.READY -> "开始游戏"
        EnvStatus.MISSING_PYTHON -> "环境缺失"
        EnvStatus.MISSING_GAME -> "资源缺失"
        EnvStatus.UNKNOWN_ERROR -> "重试"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "AIC 启动器", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            enabled = !uiState.isChecking,
            onClick = {
                when (uiState.envStatus) {
                    EnvStatus.READY -> {
                        viewModel.startGame(context.filesDir) { err ->
                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                        }
                    }
                    EnvStatus.MISSING_PYTHON -> {
                        Toast.makeText(
                            context,
                            "未检测到运行环境，请确认 Termux 已安装",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    EnvStatus.MISSING_GAME -> {
                        Toast.makeText(
                            context,
                            "未找到游戏资源目录，请检查游戏资源路径",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    EnvStatus.UNKNOWN_ERROR -> {
                        viewModel.checkEnvironment(context.filesDir)
                    }
                }
            }
        ) {
            Text(text = buttonText)
        }
    }
}
