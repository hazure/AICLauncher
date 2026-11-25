package com.aliceincradle.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LauncherScreen()
                }
            }
        }
    }
}

@Composable
fun LauncherScreen() {
    val context = LocalContext.current
    var envStatus by remember { mutableStateOf(EnvironmentStatus.MISSING_PYTHON) }
    var statusText by remember { mutableStateOf("状态：正在探测环境...") }
    var buttonText by remember { mutableStateOf("开始游戏") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun applyEnvironmentReport(report: EnvironmentReport) {
        envStatus = report.status
        when (report.status) {
            EnvironmentStatus.READY -> {
                statusText = "状态：环境就绪"
                buttonText = "开始游戏"
            }
            EnvironmentStatus.MISSING_PYTHON -> {
                statusText = "环境缺失：未检测到运行环境，请确认 Termux 已安装"
                buttonText = "开始游戏（需安装环境）"
            }
            EnvironmentStatus.MISSING_GAME -> {
                statusText = "环境缺失：未检测到游戏目录"
                buttonText = "开始游戏（缺少游戏目录）"
            }
        }
    }

    LaunchedEffect(Unit) {
        val sandboxDir = context.filesDir
        Thread {
            ProcessBridge.checkEnvironment(sandboxDir) { report ->
                mainHandler.post { applyEnvironmentReport(report) }
            }
        }.start()
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
        Button(onClick = {
            statusText = "状态：正在预检..."
            val sandboxDir = context.filesDir
            Thread {
                val result = ProcessBridge.runPreflight(sandboxDir)
                mainHandler.post {
                    when (result) {
                        is CommandResult.Success -> {
                            envStatus = EnvironmentStatus.READY
                            statusText = "状态：${result.output}"
                            buttonText = "开始游戏"
                        }
                        is CommandResult.PythonNotFound -> {
                            envStatus = EnvironmentStatus.MISSING_PYTHON
                            statusText = "环境缺失：未检测到运行环境，请确认 Termux 已安装"
                            buttonText = "开始游戏（需安装环境）"
                            Toast.makeText(
                                context,
                                "未检测到运行环境，请确认 Termux 已安装（${result.reason}）",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is CommandResult.GameNotFound -> {
                            envStatus = EnvironmentStatus.MISSING_GAME
                            statusText = "环境缺失：未检测到游戏目录"
                            buttonText = "开始游戏（缺少游戏目录）"
                            Toast.makeText(
                                context,
                                "未检测到游戏目录：${result.reason}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is CommandResult.Failure -> {
                            statusText = "状态：失败 (${result.exitCode}) ${result.error}"
                        }
                    }
                }
            }.start()
        }) {
            Text(text = buttonText)
        }
    }
}
