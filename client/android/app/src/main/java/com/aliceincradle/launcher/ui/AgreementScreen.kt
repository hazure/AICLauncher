package com.aliceincradle.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * First-launch legal disclaimer and user agreement gate.
 */
@Composable
fun AgreementScreen(
    onAgree: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isChecked by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text(
                text = "AICL 使用协议与免责声明",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "0. 此项目初始化需要联网，会收集<...>以上信息，用于检查<...>操作。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "1. 不帮助未成年人。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. 不允许未成年人接触此软件。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. 此项目仅供学习，下载后请在24小时内自行删除！",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. 与 AIC 官方无关，绝不接受任何来自 AICL 以外 Issue。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "5. 请勿骚扰 AIC 官方，一旦发现，本项目将封禁此用户使用本软件。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isChecked = !isChecked }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { isChecked = it },
                    modifier = Modifier.testTag("agreement_checkbox")
                )
                Text(
                    text = "我已阅读并同意上述声明与风险",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("agreement_exit_button")
                ) {
                    Text("退出应用")
                }
                Button(
                    onClick = onAgree,
                    enabled = isChecked,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("agreement_agree_button")
                ) {
                    Text("同意并继续")
                }
            }
        }
    }
}
