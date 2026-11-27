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

0. 此项目初始化需要联网，会收集<...>以上信息，用于检查<...>操作。
1. 不帮助未成年人。
2. 不允许未成年人接触此软件。
3. 此项目仅供学习，下载后请在24小时内自行删除！
4. 与 AIC 官方无关，绝不接受任何来自 AICL 以外 Issue。
5. 请勿骚扰 AIC 官方，一旦发现，本项目将封禁此用户使用本软件。
