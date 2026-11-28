package com.aliceincradle.launcher.ui.file

import com.aliceincradle.launcher.data.FileItem
import com.aliceincradle.launcher.ui.icon.FileCategory
import com.aliceincradle.launcher.ui.icon.FileIconResolver
import java.io.File
import java.util.Locale

sealed class FileOpenAction {
    data class NavigateParent(val entry: FileItem) : FileOpenAction()
    data class EnterDirectory(val entry: FileItem) : FileOpenAction()
    data class BlockedBinaryOrOversized(val reason: String) : FileOpenAction()
    data class OpenConfigTemplate(val entry: FileItem, val template: ConfigTemplate) : FileOpenAction()
    data class OpenConfigUnmatched(val entry: FileItem, val fallbackToReadOnly: Boolean) : FileOpenAction()
    data class OpenCodeEditor(val entry: FileItem) : FileOpenAction()
    data class OpenReadOnlyViewer(val entry: FileItem, val isMarkdown: Boolean) : FileOpenAction()
    data class OpenUnknownExternal(val entry: FileItem) : FileOpenAction()
}

/**
 * Single click-routing dispatcher sharing [FileIconResolver.classifyForRouting].
 * Fixed priority: PARENT -> DIRECTORY -> BINARY_OR_OVERSIZED -> CONFIG -> CODE -> READABLE -> UNKNOWN.
 */
object FileClickRouter {
    fun open(
        entry: FileItem,
        file: File? = entry.path.takeIf { it.isNotBlank() }?.let(::File),
        maxTextBytes: Long = FileIconResolver.DEFAULT_MAX_TEXT_BYTES
    ): FileOpenAction {
        return when (FileIconResolver.classifyForRouting(entry, file, maxTextBytes)) {
            FileCategory.PARENT -> FileOpenAction.NavigateParent(entry)
            FileCategory.DIRECTORY -> FileOpenAction.EnterDirectory(entry)
            FileCategory.BINARY_OR_OVERSIZED -> {
                val reason = if (file != null && file.isFile && file.length() > maxTextBytes) {
                    "文件过大（超过 ${maxTextBytes / (1024 * 1024)}MB），不支持直接打开"
                } else {
                    "二进制文件不支持预览"
                }
                FileOpenAction.BlockedBinaryOrOversized(reason)
            }
            FileCategory.CONFIG -> {
                val template = ConfigTemplateRegistry.matchTemplate(entry.name)
                if (template != null) {
                    FileOpenAction.OpenConfigTemplate(entry, template)
                } else {
                    val ext = FileIconResolver.extractExtension(entry.name)?.lowercase(Locale.ROOT)
                    val fallbackToReadOnly = ext == "json" || ext == "xml"
                    FileOpenAction.OpenConfigUnmatched(entry, fallbackToReadOnly = fallbackToReadOnly)
                }
            }
            FileCategory.CODE -> FileOpenAction.OpenCodeEditor(entry)
            FileCategory.READABLE -> {
                val ext = FileIconResolver.extractExtension(entry.name)
                FileOpenAction.OpenReadOnlyViewer(entry, isMarkdown = ext == "md")
            }
            FileCategory.UNKNOWN -> FileOpenAction.OpenUnknownExternal(entry)
        }
    }
}
