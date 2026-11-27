package com.aliceincradle.launcher.data

import java.util.Locale

enum class FolderEntryType {
    FOLDER,
    TEXT,
    PYTHON,
    SHELL,
    EXECUTABLE,
    OTHER
}

data class PosixPermissions(
    val suid: Boolean = false,
    val sgid: Boolean = false,
    val stickyBit: Boolean = false,
    val ownerR: Boolean = true,
    val ownerW: Boolean = true,
    val ownerX: Boolean = true,
    val groupR: Boolean = true,
    val groupW: Boolean = false,
    val groupX: Boolean = true,
    val otherR: Boolean = true,
    val otherW: Boolean = false,
    val otherX: Boolean = true
) {
    fun toFourDigitOctal(): String {
        val special = (if (suid) 4 else 0) + (if (sgid) 2 else 0) + (if (stickyBit) 1 else 0)
        val owner = (if (ownerR) 4 else 0) + (if (ownerW) 2 else 0) + (if (ownerX) 1 else 0)
        val group = (if (groupR) 4 else 0) + (if (groupW) 2 else 0) + (if (groupX) 1 else 0)
        val other = (if (otherR) 4 else 0) + (if (otherW) 2 else 0) + (if (otherX) 1 else 0)
        return "$special$owner$group$other"
    }

    fun toSymbolicString(): String {
        val oR = if (ownerR) 'r' else '-'
        val oW = if (ownerW) 'w' else '-'
        val oX = when {
            suid && ownerX -> 's'
            suid -> 'S'
            ownerX -> 'x'
            else -> '-'
        }
        val gR = if (groupR) 'r' else '-'
        val gW = if (groupW) 'w' else '-'
        val gX = when {
            sgid && groupX -> 's'
            sgid -> 'S'
            groupX -> 'x'
            else -> '-'
        }
        val tR = if (otherR) 'r' else '-'
        val tW = if (otherW) 'w' else '-'
        val tX = when {
            stickyBit && otherX -> 't'
            stickyBit -> 'T'
            otherX -> 'x'
            else -> '-'
        }
        return "$oR$oW$oX$gR$gW$gX$tR$tW$tX"
    }

    companion object {
        fun fromOctal(raw: String): PosixPermissions? {
            val cleaned = raw.trim()
            if (cleaned.length !in 3..4 || cleaned.any { it !in '0'..'7' }) {
                return null
            }
            val padded = if (cleaned.length == 3) "0$cleaned" else cleaned
            val special = padded[0].digitToInt()
            val owner = padded[1].digitToInt()
            val group = padded[2].digitToInt()
            val other = padded[3].digitToInt()
            return PosixPermissions(
                suid = (special and 4) != 0,
                sgid = (special and 2) != 0,
                stickyBit = (special and 1) != 0,
                ownerR = (owner and 4) != 0,
                ownerW = (owner and 2) != 0,
                ownerX = (owner and 1) != 0,
                groupR = (group and 4) != 0,
                groupW = (group and 2) != 0,
                groupX = (group and 1) != 0,
                otherR = (other and 4) != 0,
                otherW = (other and 2) != 0,
                otherX = (other and 1) != 0
            )
        }
    }
}

data class FileItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    val permissions: String = if (isFolder) "0755" else "0644",
    val isSelected: Boolean = false,
    val type: FolderEntryType = if (isFolder) FolderEntryType.FOLDER else FolderEntryType.OTHER,
    val detailText: String = "",
    val logoText: String = "Logo",
    val recursiveApplied: Boolean = false
) {
    val sizeLabel: String
        get() = formatFileSize(sizeBytes)
}

sealed class FileManagerDialog {
    data class FileOpProgress(
        val operationTitle: String,
        val currentFileName: String,
        val targetDescription: String,
        val progressPercent: Int
    ) : FileManagerDialog()

    data class DeleteConfirm(
        val targets: List<FileItem>
    ) : FileManagerDialog()

    data class Properties(
        val item: FileItem,
        val resolvedPath: String
    ) : FileManagerDialog()

    data class PermissionEdit(
        val item: FileItem,
        val permissions: PosixPermissions,
        val octalInput: String,
        val applyRecursively: Boolean = false
    ) : FileManagerDialog()

    data class Rename(
        val item: FileItem,
        val draftName: String
    ) : FileManagerDialog()

    data class ExportSheet(
        val targets: List<FileItem>,
        val downloadTargets: List<ExportTarget>
    ) : FileManagerDialog()
}

enum class FilePaneSide {
    LEFT,
    RIGHT
}

data class FolderPageState(
    val currentPath: String = DefaultPathProvider.DEFAULT_CURRENT_DIR,
    val targetPath: String = DefaultPathProvider.DEFAULT_TARGET_DIR,
    val items: List<FileItem> = FolderPreviewFactory.createDefaultItems(),
    val targetItems: List<FileItem> = FolderPreviewFactory.createTargetItems(),
    val activePane: FilePaneSide = FilePaneSide.LEFT,
    val activeDialog: FileManagerDialog? = null,
    val actionNotice: String? = null
) {
    val activeItems: List<FileItem>
        get() = if (activePane == FilePaneSide.LEFT) items else targetItems

    val activeSourcePath: String
        get() = if (activePane == FilePaneSide.LEFT) currentPath else targetPath

    val activeDestinationPath: String
        get() = if (activePane == FilePaneSide.LEFT) targetPath else currentPath

    val selectedItems: List<FileItem>
        get() = activeItems.filter { it.isSelected }

    val selectedCount: Int
        get() = selectedItems.size

    val fileCount: Int
        get() = activeItems.count { !it.isFolder }

    val folderCount: Int
        get() = activeItems.count { it.isFolder }

    val selectedTotalBytes: Long
        get() = selectedItems.sumOf { it.sizeBytes }

    val hasSelection: Boolean
        get() = selectedCount > 0

    val selectionSummaryText: String
        get() = "选中 $selectedCount 项文件，约 ${formatFileSize(selectedTotalBytes)}"

    val totalCountSummaryText: String
        get() = "共 $fileCount 个文件，$folderCount 个文件夹"
}

data class SettingsPageState(
    val themeMode: String = "跟随系统",
    val themeOptions: List<String> = listOf("跟随系统", "浅色", "深色"),
    val showHiddenFiles: Boolean = false,
    val autoCheckUpdate: Boolean = true,
    val rootfsType: String = "Termux Proot (Ubuntu)",
    val rootfsOptions: List<String> = listOf("Termux Proot (Ubuntu)", "Debian Bookworm", "Arch Linux ARM"),
    val graphicsRenderer: String = "Turnip + DXVK",
    val graphicsRendererOptions: List<String> = listOf("Turnip + DXVK", "VirGL", "Zink", "LLVMpipe (纯软件)"),
    val containerMountPath: String = "",
    val cpuCoreLimit: Int = 0,
    val cpuCoreOptions: List<Int> = listOf(0, 2, 4, 6, 8),
    val memLimitMB: Int = 0,
    val memLimitOptions: List<Int> = listOf(0, 2048, 4096, 6144, 8192),
    val customEnvVars: String = "",
    val isEnvVarsDialogOpen: Boolean = false,
    val envVarsDraft: String = "",
    val envVarsError: String? = null,
    val isResetContainerDialogOpen: Boolean = false,
    val isDisclaimerDialogOpen: Boolean = false,
    val isLicensesDialogOpen: Boolean = false,
    val logLevel: String = "INFO",
    val logLevelOptions: List<String> = listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR"),
    val isDevModeUnlocked: Boolean = false,
    val forceSoftwareRendering: Boolean = false,
    val versionName: String = "0.1.0a",
    val versionTapCount: Int = 0,
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

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        else -> String.format(Locale.US, "%.1f KB", bytes / kb)
    }
}

object FolderPreviewFactory {
    private const val KB = 1024L
    private const val MB = 1024L * 1024L

    fun createDefaultItems(): List<FileItem> = listOf(
        FileItem(
            id = "dir-data",
            name = "AliceInCradle_Data",
            isFolder = true,
            sizeBytes = 640L * MB,
            permissions = "0755",
            type = FolderEntryType.FOLDER,
            detailText = "游戏主资产与场景包目录。"
        ),
        FileItem(
            id = "dir-mods",
            name = "Mods",
            isFolder = true,
            sizeBytes = 128L * MB,
            permissions = "0777",
            type = FolderEntryType.FOLDER,
            detailText = "本地模组与动态挂载扩展池。"
        ),
        FileItem(
            id = "dir-streaming",
            name = "StreamingAssets",
            isFolder = true,
            sizeBytes = 96L * MB,
            permissions = "0755",
            type = FolderEntryType.FOLDER,
            detailText = "包含原生完整性校验表 _sha.txt 与流式资源。"
        ),
        FileItem(
            id = "file-patch-py",
            name = "patch_unsafeAssem.py",
            isFolder = false,
            sizeBytes = 30L * KB,
            permissions = "0644",
            type = FolderEntryType.PYTHON,
            detailText = "CIL 字节码动态重定向与哈希同步脚本。"
        ),
        FileItem(
            id = "file-unmosaic-py",
            name = "unmosaic.py",
            isFolder = false,
            sizeBytes = 62L * KB,
            permissions = "0644",
            type = FolderEntryType.PYTHON,
            detailText = "托管层资源处理与校验辅助脚本。"
        ),
        FileItem(
            id = "file-launcher-sh",
            name = "launcher.sh",
            isFolder = false,
            sizeBytes = 2L * KB,
            permissions = "0755",
            type = FolderEntryType.SHELL,
            detailText = "Box64 / DXVK / Turnip 转译层外部启动脚本。"
        ),
        FileItem(
            id = "file-readme-txt",
            name = "README.txt",
            isFolder = false,
            sizeBytes = 15L * KB,
            permissions = "0644",
            type = FolderEntryType.TEXT,
            detailText = "兼容补丁环境说明与构建注意事项。"
        ),
        FileItem(
            id = "file-notes-txt",
            name = "compat_notes.txt",
            isFolder = false,
            sizeBytes = 8L * KB,
            permissions = "0644",
            type = FolderEntryType.TEXT,
            detailText = "转译层运行参数调优记录。"
        ),
        FileItem(
            id = "file-alice-exe",
            name = "AliceInCradle.exe",
            isFolder = false,
            sizeBytes = 64L * MB,
            permissions = "0777",
            type = FolderEntryType.EXECUTABLE,
            detailText = "Zero-CRT 原生 Win64 启动引导程序。"
        )
    )

    fun createTargetItems(): List<FileItem> = listOf(
        FileItem(
            id = "dst-dir-data",
            name = "AliceInCradle_Data",
            isFolder = true,
            sizeBytes = 640L * MB,
            permissions = "0755",
            type = FolderEntryType.FOLDER
        ),
        FileItem(
            id = "dst-dir-mono",
            name = "MonoBleedingEdge",
            isFolder = true,
            sizeBytes = 42L * MB,
            permissions = "0755",
            type = FolderEntryType.FOLDER
        ),
        FileItem(
            id = "dst-dir-mods",
            name = "Mods",
            isFolder = true,
            sizeBytes = 128L * MB,
            permissions = "0777",
            type = FolderEntryType.FOLDER
        ),
        FileItem(
            id = "dst-dir-bepinex",
            name = "BepInEx",
            isFolder = true,
            sizeBytes = 24L * MB,
            permissions = "0755",
            type = FolderEntryType.FOLDER
        ),
        FileItem(
            id = "dst-exe-aic",
            name = "AliceInCradle.exe",
            isFolder = false,
            sizeBytes = 64L * MB,
            permissions = "0777",
            type = FolderEntryType.EXECUTABLE
        ),
        FileItem(
            id = "dst-dll-unity",
            name = "UnityPlayer.dll",
            isFolder = false,
            sizeBytes = 31L * MB,
            permissions = "0755",
            type = FolderEntryType.OTHER
        ),
        FileItem(
            id = "dst-sh-launcher",
            name = "launcher.sh",
            isFolder = false,
            sizeBytes = 2L * KB,
            permissions = "0755",
            type = FolderEntryType.SHELL
        ),
        FileItem(
            id = "dst-conf-dxvk",
            name = "dxvk.conf",
            isFolder = false,
            sizeBytes = 1L * KB,
            permissions = "0644",
            type = FolderEntryType.TEXT
        ),
        FileItem(
            id = "dst-py-patch",
            name = "patch_unsafeAssem.py",
            isFolder = false,
            sizeBytes = 30L * KB,
            permissions = "0644",
            type = FolderEntryType.PYTHON
        ),
        FileItem(
            id = "dst-txt-readme",
            name = "README.txt",
            isFolder = false,
            sizeBytes = 15L * KB,
            permissions = "0644",
            type = FolderEntryType.TEXT
        )
    )
}
