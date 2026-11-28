package com.aliceincradle.launcher.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Real filesystem repository backed by Java NIO/File, POSIX Os.stat/chmod, and Android SAF.
 * Never hardcodes absolute storage paths; all roots are resolved dynamically via Context/Environment.
 */
class RealFileSystemRepository {

    fun hasAllFilesAccess(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                Environment.getExternalStorageDirectory()?.canRead() == true
            }
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "SecurityException checking all-files access", e)
            false
        } catch (e: RuntimeException) {
            SafeProbeIo.debug(TAG, "All-files access check unavailable: ${e.message}")
            false
        }
    }

    fun resolveInitialLeftDir(context: Context?): File {
        if (hasAllFilesAccess()) {
            val ext = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            if (ext != null && ext.exists() && ext.canRead()) {
                return ext
            }
        }
        if (context != null) {
            val appExt = runCatching { context.getExternalFilesDir(null) }.getOrNull()
            if (appExt != null && (appExt.exists() || appExt.mkdirs())) {
                return appExt
            }
            return context.filesDir
        }
        return File(".")
    }

    fun resolveInitialRightDir(context: Context?): File {
        if (context != null) {
            val extDl = runCatching { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
            if (extDl != null && (extDl.exists() || extDl.mkdirs())) {
                return extDl
            }
            val fallback = File(context.filesDir, "downloads")
            if (!fallback.exists()) {
                fallback.mkdirs()
            }
            return fallback
        }
        return File(".")
    }

    fun listDirectory(dir: File, showHidden: Boolean): Result<List<FileItem>> {
        return try {
            if (!dir.exists()) {
                return Result.failure(IOException("目录不存在：${dir.absolutePath}"))
            }
            if (!dir.isDirectory) {
                return Result.failure(IOException("目标不是目录：${dir.absolutePath}"))
            }
            val children = dir.listFiles()
                ?: return Result.failure(SecurityException("无法读取目录（权限不足）：${dir.absolutePath}"))

            val parentEntry = FileItem(
                id = "__parent__:${dir.absolutePath}",
                name = "..",
                path = dir.parentFile?.absolutePath ?: dir.absolutePath,
                isFolder = true,
                sizeBytes = 0L,
                lastModifiedMs = 0L,
                permissions = "0755",
                type = FolderEntryType.FOLDER
            )
            val items = listOf(parentEntry) + children
                .asSequence()
                .filter { showHidden || !it.name.startsWith(".") }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
                .map { file -> toFileItem(file) }
                .toList()
            Result.success(items)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied listing ${dir.absolutePath}", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error listing ${dir.absolutePath}", e)
            Result.failure(e)
        }
    }

    fun createEntry(parentDir: File, rawName: String, isFolder: Boolean): Result<File> {
        val name = rawName.trim()
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
            return Result.failure(IllegalArgumentException("非法名称：$rawName"))
        }
        return try {
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                return Result.failure(IOException("无法创建父目录：${parentDir.absolutePath}"))
            }
            val target = File(parentDir, name)
            if (target.exists()) {
                return Result.failure(IOException("目标已存在：$name"))
            }
            val created = if (isFolder) target.mkdirs() else target.createNewFile()
            if (!created) {
                Result.failure(IOException("创建失败：$name"))
            } else {
                Result.success(target)
            }
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied creating $name", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error creating $name", e)
            Result.failure(e)
        }
    }

    fun deleteEntries(items: List<FileItem>): Result<Int> {
        var deletedCount = 0
        return try {
            for (item in items) {
                val target = File(item.path)
                if (!target.exists()) continue
                if (target.deleteRecursively()) {
                    deletedCount++
                } else {
                    return Result.failure(IOException("无法删除：${item.name}"))
                }
            }
            Result.success(deletedCount)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied deleting entries", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error deleting entries", e)
            Result.failure(e)
        }
    }

    fun renameEntry(item: FileItem, rawNewName: String): Result<File> {
        val newName = rawNewName.trim()
        if (newName.isEmpty() || newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
            return Result.failure(IllegalArgumentException("非法名称：$rawNewName"))
        }
        return try {
            val src = File(item.path)
            if (!src.exists()) {
                return Result.failure(IOException("源文件不存在：${item.name}"))
            }
            val dst = File(src.parentFile, newName)
            if (dst.exists()) {
                return Result.failure(IOException("目标名称已存在：$newName"))
            }
            if (!src.renameTo(dst)) {
                return Result.failure(IOException("重命名失败：${item.name} -> $newName"))
            }
            Result.success(dst)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied renaming ${item.name}", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error renaming ${item.name}", e)
            Result.failure(e)
        }
    }

    fun applyPermissions(item: FileItem, octal: String, recursive: Boolean): Result<Unit> {
        val mode = octal.toIntOrNull(8)
            ?: return Result.failure(IllegalArgumentException("无效八进制权限：$octal"))
        return try {
            val root = File(item.path)
            if (!root.exists()) {
                return Result.failure(IOException("目标不存在：${item.name}"))
            }
            chmodTarget(root, mode, recursive)
            Result.success(Unit)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied chmod ${item.name}", e)
            Result.failure(e)
        } catch (e: Exception) {
            SafeProbeIo.warn(TAG, "Failed chmod ${item.name}", e)
            Result.failure(e)
        }
    }

    suspend fun copyEntriesWithProgress(
        sources: List<FileItem>,
        destDir: File,
        onProgress: (currentFile: String, percent: Int) -> Unit
    ): Result<Int> {
        return try {
            if (!destDir.exists() && !destDir.mkdirs()) {
                return Result.failure(IOException("无法创建目标目录：${destDir.absolutePath}"))
            }
            val srcFiles = sources.map { File(it.path) }.filter { it.exists() }
            if (srcFiles.isEmpty()) {
                return Result.failure(IOException("未找到可复制的源文件"))
            }
            val totalBytes = srcFiles.sumOf { computeTotalBytes(it) }.coerceAtLeast(1L)
            var copiedBytes = 0L
            var copiedCount = 0

            for (src in srcFiles) {
                coroutineContext.ensureActive()
                val dst = resolveUniqueCopyTarget(destDir, src)
                copiedBytes = copyRecursively(src, dst, copiedBytes, totalBytes, onProgress)
                copiedCount++
            }
            onProgress(srcFiles.last().name, 100)
            Result.success(copiedCount)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied copying files", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error copying files", e)
            Result.failure(e)
        }
    }

    suspend fun exportToDownloads(
        context: Context,
        sources: List<FileItem>,
        onProgress: (currentFile: String, percent: Int) -> Unit
    ): Result<String> {
        val publicDir = runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AICL_Exports")
        }.getOrNull()
        val targetDir = if (publicDir != null && (publicDir.exists() || publicDir.mkdirs()) && publicDir.canWrite()) {
            publicDir
        } else {
            val appDl = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "Download")
            File(appDl, "AICL_Exports").apply { mkdirs() }
        }
        return copyEntriesWithProgress(sources, targetDir, onProgress).map { targetDir.absolutePath }
    }

    suspend fun exportToSafTree(
        context: Context,
        treeUriString: String,
        sources: List<FileItem>,
        onProgress: (currentFile: String, percent: Int) -> Unit
    ): Result<Int> {
        return try {
            val treeUri = Uri.parse(treeUriString)
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val resolver = context.contentResolver
            val srcFiles = sources.map { File(it.path) }.filter { it.exists() }
            if (srcFiles.isEmpty()) {
                return Result.failure(IOException("未找到可导出的源文件"))
            }
            val totalBytes = srcFiles.sumOf { computeTotalBytes(it) }.coerceAtLeast(1L)
            var copiedBytes = 0L
            var count = 0
            for (src in srcFiles) {
                coroutineContext.ensureActive()
                copiedBytes = copyToSafDocument(resolver, treeUri, parentDocUri, src, copiedBytes, totalBytes, onProgress)
                count++
            }
            onProgress(srcFiles.last().name, 100)
            Result.success(count)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "SAF export permission denied", e)
            Result.failure(e)
        } catch (e: Exception) {
            SafeProbeIo.warn(TAG, "SAF export failed", e)
            Result.failure(e)
        }
    }

    fun resolveSafTreeToDirectory(treeUriString: String): File? {
        return try {
            val treeUri = Uri.parse(treeUriString)
            val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
            if (docId.startsWith("primary:", ignoreCase = true)) {
                val rel = docId.substringAfter(':').trimStart('/')
                val root = Environment.getExternalStorageDirectory()
                val dir = if (rel.isEmpty()) root else File(root, rel)
                if (dir.exists() && dir.isDirectory && dir.canRead()) dir else null
            } else {
                null
            }
        } catch (e: Exception) {
            SafeProbeIo.debug(TAG, "Failed resolving SAF tree URI: ${e.message}")
            null
        }
    }

    private fun copyToSafDocument(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        src: File,
        initialCopied: Long,
        totalBytes: Long,
        onProgress: (String, Int) -> Unit
    ): Long {
        var copied = initialCopied
        if (src.isDirectory) {
            val dirUri = DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                src.name
            ) ?: throw IOException("无法在 SAF 目录创建文件夹：${src.name}")
            src.listFiles()?.forEach { child ->
                copied = copyToSafDocument(resolver, treeUri, dirUri, child, copied, totalBytes, onProgress)
            }
            return copied
        }
        val docUri = DocumentsContract.createDocument(
            resolver,
            parentDocUri,
            "application/octet-stream",
            src.name
        ) ?: throw IOException("无法在 SAF 目录创建文件：${src.name}")

        val out = resolver.openOutputStream(docUri)
            ?: throw IOException("无法打开 SAF 输出流：${src.name}")
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        FileInputStream(src).use { input ->
            out.use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val percent = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 99)
                    onProgress(src.name, percent)
                }
            }
        }
        return copied
    }

    private suspend fun copyRecursively(
        src: File,
        dst: File,
        initialCopied: Long,
        totalBytes: Long,
        onProgress: (String, Int) -> Unit
    ): Long {
        coroutineContext.ensureActive()
        var copied = initialCopied
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw IOException("无法创建目录：${dst.absolutePath}")
            }
            val children = src.listFiles() ?: emptyArray()
            for (child in children) {
                copied = copyRecursively(child, File(dst, child.name), copied, totalBytes, onProgress)
            }
            return copied
        }

        val buffer = ByteArray(COPY_BUFFER_SIZE)
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val percent = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 99)
                    onProgress(src.name, percent)
                }
            }
        }
        return copied
    }

    private fun computeTotalBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (!file.isDirectory) return file.length().coerceAtLeast(1L)
        val children = file.listFiles() ?: return 1L
        if (children.isEmpty()) return 1L
        return children.sumOf { computeTotalBytes(it) }.coerceAtLeast(1L)
    }

    private fun resolveUniqueCopyTarget(destDir: File, src: File): File {
        val candidate = File(destDir, src.name)
        if (!candidate.exists() && candidate.absolutePath != src.absolutePath) {
            return candidate
        }
        if (candidate.absolutePath != src.absolutePath) {
            return candidate
        }
        val base = if (src.isDirectory) src.name else src.nameWithoutExtension
        val ext = if (!src.isDirectory && src.extension.isNotEmpty()) ".${src.extension}" else ""
        var idx = 1
        while (true) {
            val alt = File(destDir, "${base}_copy${if (idx > 1) "_$idx" else ""}$ext")
            if (!alt.exists()) return alt
            idx++
        }
    }

    private fun chmodTarget(file: File, mode: Int, recursive: Boolean) {
        val appliedByOs = runCatching {
            Os.chmod(file.absolutePath, mode)
            true
        }.getOrElse { false }

        if (!appliedByOs) {
            val ownerR = (mode and 0b100_000_000) != 0
            val ownerW = (mode and 0b010_000_000) != 0
            val ownerX = (mode and 0b001_000_000) != 0
            val otherR = (mode and 0b000_000_100) != 0
            val otherW = (mode and 0b000_000_010) != 0
            val otherX = (mode and 0b000_000_001) != 0
            file.setReadable(ownerR || otherR, !otherR)
            file.setWritable(ownerW || otherW, !otherW)
            file.setExecutable(ownerX || otherX, !otherX)
        }

        if (recursive && file.isDirectory) {
            file.listFiles()?.forEach { child ->
                chmodTarget(child, mode, true)
            }
        }
    }

    private fun toFileItem(file: File): FileItem {
        val isFolder = file.isDirectory
        val permissions = readOctalPermissions(file)
        val type = resolveEntryType(file, isFolder)
        val sizeBytes = if (isFolder) 0L else file.length()
        return FileItem(
            id = file.absolutePath,
            name = file.name,
            path = file.absolutePath,
            isFolder = isFolder,
            sizeBytes = sizeBytes,
            lastModifiedMs = file.lastModified(),
            permissions = permissions,
            type = type
        )
    }

    private fun readOctalPermissions(file: File): String {
        val osMode = runCatching {
            val stat = Os.stat(file.absolutePath)
            stat.st_mode and 0xFFF
        }.getOrNull()
        if (osMode != null) {
            return String.format(Locale.US, "%04o", osMode)
        }
        val r = if (file.canRead()) 4 else 0
        val w = if (file.canWrite()) 2 else 0
        val x = if (file.canExecute()) 1 else 0
        val owner = r + w + x
        val other = r + x
        return "0$owner$other$other"
    }

    private fun resolveEntryType(file: File, isFolder: Boolean): FolderEntryType {
        if (isFolder) return FolderEntryType.FOLDER
        return when (file.extension.lowercase(Locale.ROOT)) {
            "py" -> FolderEntryType.PYTHON
            "sh" -> FolderEntryType.SHELL
            "exe", "bin", "so" -> FolderEntryType.EXECUTABLE
            "txt", "md", "json", "conf", "log", "xml", "yaml", "yml", "ini" -> FolderEntryType.TEXT
            else -> FolderEntryType.OTHER
        }
    }

    companion object {
        private const val TAG = "RealFileSystemRepo"
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
