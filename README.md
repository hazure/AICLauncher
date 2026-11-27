# AliceInCradle Launcher (AICL)

又名：AIC启动器、AICL、AliceInCradle启动器、AliceInCradle安卓端

一个使用 Jetpack Compose 从零编写的第三方安卓启动器，面向《Alice In Cradle》在 Android / Termux / Wine 转译环境下的启动、资源管理与兼容运行。

AIC 的 Mod 加载及补丁支持已迁移至独立项目 AICP。

## 截图

（留空待补）

## 功能

- **运行环境遥测与动态探测**：实时监测内存、CPU、设备信息及 Python / AIC 运行环境状态。
- **资源下载与检索**：支持按名称、ID、作者检索资源，提供版本与分类筛选。
- **双栏文件管理**：提供左右双栏目录浏览、文件操作与权限属性编辑。
- **Python 与运行配置**：支持 Python 版本选择、路径探测与自定义路径配置。
- **首次启动合规拦截**：首次运行强制展示使用协议与免责声明，确认后持久化状态。

## 兼容的 AML 与 AIC

- **目标游戏版本**：Alice In Cradle v0.30（Windows x86-64 Standalone）
- **引擎与运行时**：Unity 2022.3.62f2（MonoBleedingEdge）
- **兼容层支持**：AML（Alice Mod Loader）及 Android 转译容器环境（Termux / Box64 / Wine / Winlator）

## 下载与安装

1. 从仓库 Releases 页面获取最新构建的 `app-debug.apk`（应用名称显示为 `AICL`），或使用 Android SDK / Gradle 离线构建：
   ```bash
   cd client/android && ./gradlew assembleDebug --offline
   ```
2. 在 Android 设备上安装并启动 `AICL`。
3. 首次启动时阅读并同意免责声明后即可进入主界面。

## AICL 免责声明

本软件（AliceInCradle Launcher，简称 AICL）采用 MIT 许可证开源。本软件为第三方开源项目，与《Alice In Cradle》游戏官方及原作者（下称“官方”）无任何关联。《Alice In Cradle》包含限制级内容，在下载、安装或使用本软件前，请仔细阅读以下条款：

0. **严禁未成年人使用**：本软件仅面向已满 18 周岁的成年用户。鉴于《Alice In Cradle》游戏本身包含成人内容及社区争议，我们坚决禁止未成年人使用本软件。若未成年人私自使用，后果由未成年人及未成年人监护人自负；后果由未成年人及未成年人监护人自负；后果由未成年人及未成年人监护人自负！如您未满 18 周岁，请立即停止使用并删除本软件。
1. **学习交流性质**：本项目仅供技术学习与交流使用，严禁用于任何商业用途、倒卖或非法途径。下载后请在 24 小时内自行删除。
2. **无官方关联**：本软件不包含游戏本体。本项目绝不接受任何与游戏本体相关的 Issue 反馈。请勿就本软件的使用问题骚扰官方或原作者。
3. **使用风险自担**：您明确知晓并同意，使用本软件的风险由您自行承担。本软件按“现状”提供，不附带任何明示或暗示的担保。因使用本软件（包括但不限于容器配置、Mod 加载、文件操作）导致的设备损坏、数据丢失或游戏崩溃，开发者不承担任何责任。
4. **用户行为约束**：严禁利用本软件从事任何违反法律法规或侵犯他人权益的行为。因用户违规行为引发的任何纠纷或损失，由用户自行承担。
5. **条款接受**：您下载、安装或使用本软件，即视为您已阅读、理解并同意本免责声明的全部内容。

如您不同意上述任何条款，请立即停止使用并删除本软件。


## 致谢

- 图标设计参考与基线：[Material Symbols](https://github.com/google/material-design-icons)（Apache-2.0）与 [Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme)（MIT），完整许可与来源声明见 [LICENSES/NOTICE](LICENSES/NOTICE)。

- 官方 Trial 版资源说明：AICL 运行时仅从官方源（`https://get.aliceincradle.dev/win/`）动态获取 Trial 版信息，绝不捆绑或镜像分发游戏本体；游戏本体版权及 [License Terms](https://docs.nanamehacha.dev/en/aliceincradle/license) 归 `NanameHacha@Cicini` 所有。
