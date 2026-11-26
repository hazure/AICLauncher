package com.aliceincradle.launcher.data

enum class FolderEntryType {
    FOLDER,
    TEXT,
    PYTHON,
    SHELL,
    EXECUTABLE,
    OTHER
}

enum class FolderAction(val label: String) {
    INFO("info"),
    DELETE("Del"),
    COPY("Copy"),
    PLACE("Place"),
    RUNNING("Running")
}

data class FolderItem(
    val id: String,
    val name: String,
    val type: FolderEntryType,
    val sizeLabel: String,
    val detailText: String = "",
    val logoText: String = "Logo",
    val isLauncherTarget: Boolean = false
)

data class FolderPageState(
    val countSummary: String = "共 3 个文件夹，7 个文件",
    val totalSizeSummary: String = "共占 37GB",
    val items: List<FolderItem> = FolderPreviewFactory.createDefaultItems(),
    val selectedItemId: String? = "file-alice-exe",
    val clipboardItemName: String? = null,
    val actionNotice: String? = null,
    val inspectedItem: FolderItem? = null
)

data class SettingsPageState(
    val selectedPythonVersion: String = "Python 3.10",
    val pythonVersionOptions: List<String> = listOf("Python 3.10", "Python 3.11", "Python 3.12"),
    val pythonWarningText: String = "无法检测到？点击下载 Python！",
    val showPythonWarning: Boolean = true,
    // Display-only mock preset strings; never used for filesystem I/O.
    val selectedPythonDir: String = "D:/python",
    val pythonDirOptions: List<String> = listOf("D:/python", "C:/Python310", "容器默认环境"),
    val customPathButtonText: String = "自定义路径选择",
    val isCustomPathDialogOpen: Boolean = false,
    val customPathDraft: String = "",
    val reservedButtonLabel: String = "Python 设置",
    val reservedHintText: String = "此处不再多举例！",
    val statusNotice: String? = null
)

object FolderPreviewFactory {
    fun createDefaultItems(): List<FolderItem> = listOf(
        FolderItem(
            id = "dir-data",
            name = "AliceInCradle_Data",
            type = FolderEntryType.FOLDER,
            sizeLabel = "文件夹 · 31.4 GB",
            detailText = "游戏主资产与场景包目录。"
        ),
        FolderItem(
            id = "dir-mods",
            name = "Mods",
            type = FolderEntryType.FOLDER,
            sizeLabel = "文件夹 · 4.2 GB",
            detailText = "本地模组与动态挂载扩展池。"
        ),
        FolderItem(
            id = "dir-streaming",
            name = "StreamingAssets",
            type = FolderEntryType.FOLDER,
            sizeLabel = "文件夹 · 1.3 GB",
            detailText = "包含原生完整性校验表 _sha.txt 与流式资源。"
        ),
        FolderItem(
            id = "file-patch-py",
            name = "patch_unsafeAssem.py",
            type = FolderEntryType.PYTHON,
            sizeLabel = "Python 脚本 · 29.8 KB",
            detailText = "CIL 字节码动态重定向与哈希同步脚本。"
        ),
        FolderItem(
            id = "file-unmosaic-py",
            name = "unmosaic.py",
            type = FolderEntryType.PYTHON,
            sizeLabel = "Python 脚本 · 62.1 KB",
            detailText = "托管层资源处理与校验辅助脚本。"
        ),
        FolderItem(
            id = "file-sha-py",
            name = "update_sha.py",
            type = FolderEntryType.PYTHON,
            sizeLabel = "Python 脚本 · 3.2 KB",
            detailText = "全量 SHA-256 散列表校验更新工具。"
        ),
        FolderItem(
            id = "file-launcher-sh",
            name = "launcher.sh",
            type = FolderEntryType.SHELL,
            sizeLabel = "Shell 脚本 · 2.0 KB",
            detailText = "Box64 / DXVK / Turnip 转译层外部启动脚本。"
        ),
        FolderItem(
            id = "file-readme-txt",
            name = "README.txt",
            type = FolderEntryType.TEXT,
            sizeLabel = "文本文件 · 14.8 KB",
            detailText = "兼容补丁环境说明与构建注意事项。"
        ),
        FolderItem(
            id = "file-notes-txt",
            name = "compat_notes.txt",
            type = FolderEntryType.TEXT,
            sizeLabel = "文本文件 · 6.4 KB",
            detailText = "转译层运行参数调优记录。"
        ),
        FolderItem(
            id = "file-alice-exe",
            name = "AliceInCradle.exe",
            type = FolderEntryType.EXECUTABLE,
            sizeLabel = "可执行程序 · 64.0 MB",
            detailText = "Zero-CRT 原生 Win64 启动引导程序（点击 Running 可模拟启动）。",
            logoText = "Logo",
            isLauncherTarget = true
        )
    )
}
