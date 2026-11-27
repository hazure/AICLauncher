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
    val recursiveApplied: Boolean = false,
    val path: String = "",
    val lastModifiedMs: Long = 0L
) {
    val sizeLabel: String
        get() = if (isFolder) "目录" else formatFileSize(sizeBytes)
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

    data class CreateEntry(
        val isFolder: Boolean = false,
        val draftName: String = ""
    ) : FileManagerDialog()

    data class ReadOnlyViewer(
        val item: FileItem,
        val content: String,
        val isMarkdown: Boolean,
        val isTruncated: Boolean
    ) : FileManagerDialog()

    data class CodeEditor(
        val item: FileItem,
        val initialContent: String,
        val content: String,
        val hasBom: Boolean,
        val lineEnding: String,
        val undoStack: List<String> = emptyList(),
        val redoStack: List<String> = emptyList(),
        val showDiscardConfirm: Boolean = false
    ) : FileManagerDialog() {
        val isDirty: Boolean get() = content != initialContent
    }

    data class ConfigTemplateForm(
        val item: FileItem,
        val templateName: String,
        val fields: List<com.aliceincradle.launcher.ui.file.ConfigField>,
        val hasBom: Boolean,
        val lineEnding: String
    ) : FileManagerDialog()

    data class ConfigUnmatchedPrompt(
        val item: FileItem,
        val fallbackToReadOnly: Boolean
    ) : FileManagerDialog()
}

enum class FilePaneSide {
    LEFT,
    RIGHT
}

data class FolderPageState(
    val currentPath: String = "",
    val targetPath: String = "",
    val items: List<FileItem> = emptyList(),
    val targetItems: List<FileItem> = emptyList(),
    val activePane: FilePaneSide = FilePaneSide.LEFT,
    val activeDialog: FileManagerDialog? = null,
    val actionNotice: String? = null,
    val clipboardItems: List<FileItem> = emptyList(),
    val hasAllFilesAccess: Boolean = false,
    val isLoadingFiles: Boolean = false
) {
    val hasClipboard: Boolean
        get() = clipboardItems.isNotEmpty()
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
    val versionName: String = "0.1.0b",
    val versionTapCount: Int = 0,
    val selectedPythonVersion: String = "Python 3.10",
    val pythonVersionOptions: List<String> = listOf("Python 3.10", "Python 3.11", "Python 3.12"),
    val pythonWarningText: String = "无法检测到？点击下载 Python！",
    val showPythonWarning: Boolean = true,
    val selectedPythonDir: String = "D:/python",
    val pythonDirOptions: List<String> = listOf("D:/python", "C:/Python310", "容器默认环境"),
    val customPathButtonText: String = "自定义路径选择",
    val isCustomPathDialogOpen: Boolean = false,
    val customPathDraft: String = "",
    val reservedButtonLabel: String = "Python 设置",
    val reservedHintText: String = "此处不再多举例！",
    val cacheSizeText: String = "计算中...",
    val statusNotice: String? = null,
    val cachedShortCodeSummary: String = "未配置短码",
    val isManualShortCodeDialogOpen: Boolean = false,
    val manualShortCodeDraft: String = "",
    val manualFileNameDraft: String = "AliceInCradle Win ver030i.7z",
    val manualVersionDraft: String = "0.30i",
    val manualShortCodeError: String? = null
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

