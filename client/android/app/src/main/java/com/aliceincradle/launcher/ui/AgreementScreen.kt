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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
                    AiclDisclaimerContent()
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
                    text = "我已年满 18 周岁，已阅读并同意上述免责声明及 MIT 开源协议，知晓违规使用后果自负。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
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

private val DISCLAIMER_CLAUSES = listOf(
    "0. " to ("严禁未成年人使用" to "：本软件仅面向已满 18 周岁的成年用户。鉴于《Alice In Cradle》游戏本身包含成人内容及社区争议，我们坚决禁止未成年人使用本软件。若未成年人私自使用，后果由未成年人及未成年人监护人自负；后果由未成年人及未成年人监护人自负；后果由未成年人及未成年人监护人自负！如您未满 18 周岁，请立即停止使用并删除本软件。"),
    "1. " to ("学习交流性质" to "：本项目仅供技术学习与交流使用，严禁用于任何商业用途、倒卖或非法途径。下载后请在 24 小时内自行删除。"),
    "2. " to ("无官方关联" to "：本软件不包含游戏本体。本项目绝不接受任何与游戏本体相关的 Issue 反馈。请勿就本软件的使用问题骚扰官方或原作者。"),
    "3. " to ("工具定位与运行环境限制" to "：本软件仅为在 Android 设备上运行《Alice In Cradle》提供技术辅助，不包含游戏本体或任何受版权保护的游戏资产，也不提供、不鼓励任何绕过游戏官方验证、检测或限制的方法。用户应自行确认其使用方式符合游戏官方服务条款；因使用非官方支持环境（包括但不限于各类兼容层、虚拟机或模拟器环境）产生的任何后果，均由用户自行承担。"),
    "4. " to ("使用风险自担" to "：您明确知晓并同意，使用本软件的风险由您自行承担。本软件按“现状”提供，不附带任何明示或暗示的担保。因使用本软件（包括但不限于容器配置、Mod 加载、文件操作）导致的设备损坏、数据丢失或游戏崩溃，开发者不承担任何责任。"),
    "5. " to ("用户行为约束" to "：严禁利用本软件从事任何违反法律法规或侵犯他人权益的行为。因用户违规行为引发的任何纠纷或损失，由用户自行承担。"),
    "6. " to ("条款接受" to "：您下载、安装或使用本软件，即视为您已阅读、理解并同意本免责声明的全部内容。")
)

@Composable
internal fun AiclDisclaimerContent() {
    Text(
        text = "本软件（AliceInCradle Launcher，简称 AICL）采用 MIT 许可证开源。本软件为第三方开源项目，与《Alice In Cradle》游戏官方及原作者（下称“官方”）无任何关联。《Alice In Cradle》包含限制级内容，在下载、安装或使用本软件前，请仔细阅读以下条款：",
        style = MaterialTheme.typography.bodyMedium
    )
    DISCLAIMER_CLAUSES.forEach { (prefix, titleAndBody) ->
        val (title, body) = titleAndBody
        Text(
            text = buildAnnotatedString {
                append(prefix)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(title)
                }
                append(body)
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Text(
        text = "如您不同意上述任何条款，请立即停止使用并删除本软件。",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold
    )
}
