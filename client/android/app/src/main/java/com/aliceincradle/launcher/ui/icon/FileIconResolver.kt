package com.aliceincradle.launcher.ui.icon

import com.aliceincradle.launcher.data.FileItem
import com.aliceincradle.launcher.data.SafeProbeIo
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.Locale

enum class IconSpec {
    FOLDER,
    PARENT,
    TEXT,
    MARKDOWN,
    CODE,
    CONFIG,
    ARCHIVE,
    IMAGE,
    AUDIO,
    VIDEO,
    PDF,
    SHEET,
    BINARY,
    UNKNOWN
}

enum class FileCategory {
    PARENT,
    DIRECTORY,
    BINARY_OR_OVERSIZED,
    CONFIG,
    CODE,
    READABLE,
    UNKNOWN
}

/**
 * Single source of truth for file type icon resolution and click-routing classification.
 * Bounded LRU cache (512 entries) avoids repeated extension parsing during LazyColumn scroll.
 */
object FileIconResolver {
    private const val TAG = "FileIconResolver"
    private const val CACHE_CAPACITY = 512
    const val DEFAULT_MAX_TEXT_BYTES = 2L * 1024L * 1024L
    private const val BINARY_PROBE_BYTES = 8 * 1024

    val CODE_EXTENSIONS: Set<String> = setOf(
        "kt", "kts", "java", "py", "c", "h", "cpp", "hpp",
        "cs", "ts", "js", "sh", "bat", "html", "css", "sql"
    )

    val CONFIG_EXTENSIONS: Set<String> = setOf(
        "conf", "cfg", "ini", "toml", "yaml", "yml", "properties", "json", "xml"
    )

    val READABLE_EXTENSIONS: Set<String> = setOf(
        "txt", "log", "md", "rst", "csv", "tsv"
    )

    val EXTENSION_TO_ICON: Map<String, IconSpec> = buildMap {
        CODE_EXTENSIONS.forEach { put(it, IconSpec.CODE) }
        CONFIG_EXTENSIONS.forEach { put(it, IconSpec.CONFIG) }
        put("txt", IconSpec.TEXT)
        put("log", IconSpec.TEXT)
        put("rst", IconSpec.TEXT)
        put("md", IconSpec.MARKDOWN)
        put("csv", IconSpec.SHEET)
        put("tsv", IconSpec.SHEET)
        put("xls", IconSpec.SHEET)
        put("xlsx", IconSpec.SHEET)
        put("zip", IconSpec.ARCHIVE)
        put("7z", IconSpec.ARCHIVE)
        put("tar", IconSpec.ARCHIVE)
        put("gz", IconSpec.ARCHIVE)
        put("rar", IconSpec.ARCHIVE)
        put("apk", IconSpec.ARCHIVE)
        put("png", IconSpec.IMAGE)
        put("jpg", IconSpec.IMAGE)
        put("jpeg", IconSpec.IMAGE)
        put("webp", IconSpec.IMAGE)
        put("gif", IconSpec.IMAGE)
        put("svg", IconSpec.IMAGE)
        put("mp3", IconSpec.AUDIO)
        put("wav", IconSpec.AUDIO)
        put("ogg", IconSpec.AUDIO)
        put("flac", IconSpec.AUDIO)
        put("mp4", IconSpec.VIDEO)
        put("mkv", IconSpec.VIDEO)
        put("webm", IconSpec.VIDEO)
        put("pdf", IconSpec.PDF)
        put("exe", IconSpec.BINARY)
        put("dll", IconSpec.BINARY)
        put("so", IconSpec.BINARY)
        put("bin", IconSpec.BINARY)
        put("dat", IconSpec.BINARY)
        put("o", IconSpec.BINARY)
        put("class", IconSpec.BINARY)
    }

    private val cache: MutableMap<String, IconSpec> = Collections.synchronizedMap(
        object : LinkedHashMap<String, IconSpec>(CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IconSpec>?): Boolean {
                return size > CACHE_CAPACITY
            }
        }
    )

    fun clearCache() {
        cache.clear()
    }

    fun resolve(entry: FileItem): IconSpec {
        if (entry.name == "..") return IconSpec.PARENT
        if (entry.isFolder) return IconSpec.FOLDER
        val key = entry.path.ifBlank { entry.name }
        cache[key]?.let { return it }
        val resolved = if (entry.path.isNotBlank()) {
            resolveFile(File(entry.path), isParentEntry = false)
        } else {
            resolveByName(entry.name)
        }
        cache[key] = resolved
        return resolved
    }

    fun resolveFile(file: File, isParentEntry: Boolean = false): IconSpec {
        if (isParentEntry || file.name == "..") return IconSpec.PARENT
        val target = resolveSymlinkTarget(file) ?: return IconSpec.UNKNOWN
        if (target.isDirectory) return IconSpec.FOLDER
        return resolveByName(target.name)
    }

    fun resolveByName(fileName: String): IconSpec {
        val ext = extractExtension(fileName) ?: return IconSpec.UNKNOWN
        return EXTENSION_TO_ICON[ext] ?: IconSpec.UNKNOWN
    }

    fun extractExtension(fileName: String): String? {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        val lastDot = trimmed.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == trimmed.length - 1) return null
        return trimmed.substring(lastDot + 1).trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }

    fun resolveSymlinkTarget(file: File): File? {
        return try {
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) {
                val canonical = file.canonicalFile
                if (canonical.exists()) canonical else null
            } else {
                file
            }
        } catch (e: IOException) {
            SafeProbeIo.debug(TAG, "Unreachable symlink ${file.name}: ${e.message}")
            null
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied resolving symlink ${file.name}", e)
            null
        }
    }

    fun isBinaryContent(file: File): Boolean {
        if (!file.isFile || !file.canRead()) return false
        return try {
            val buffer = ByteArray(BINARY_PROBE_BYTES)
            FileInputStream(file).use { input ->
                val read = input.read(buffer)
                for (i in 0 until read) {
                    if (buffer[i] == 0.toByte()) return true
                }
                false
            }
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error probing binary content for ${file.name}", e)
            false
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied probing binary content for ${file.name}", e)
            false
        }
    }

    /**
     * Unified classification shared with [com.aliceincradle.launcher.ui.file.FileClickRouter].
     * Priority: PARENT -> DIRECTORY -> BINARY_OR_OVERSIZED -> CONFIG -> CODE -> READABLE -> UNKNOWN.
     */
    fun classifyForRouting(
        entry: FileItem,
        file: File?,
        maxTextBytes: Long = DEFAULT_MAX_TEXT_BYTES
    ): FileCategory {
        if (entry.name == "..") return FileCategory.PARENT
        if (entry.isFolder) return FileCategory.DIRECTORY

        val ext = extractExtension(entry.name)
        val icon = if (ext != null) EXTENSION_TO_ICON[ext] ?: IconSpec.UNKNOWN else IconSpec.UNKNOWN
        if (icon == IconSpec.BINARY || icon == IconSpec.ARCHIVE || icon == IconSpec.IMAGE ||
            icon == IconSpec.AUDIO || icon == IconSpec.VIDEO || icon == IconSpec.PDF
        ) {
            return FileCategory.BINARY_OR_OVERSIZED
        }

        if (file != null && file.isFile) {
            if (file.length() > maxTextBytes && ext !in READABLE_EXTENSIONS) {
                return FileCategory.BINARY_OR_OVERSIZED
            }
            if (isBinaryContent(file)) {
                return FileCategory.BINARY_OR_OVERSIZED
            }
        }

        return when (ext) {
            in CONFIG_EXTENSIONS -> FileCategory.CONFIG
            in CODE_EXTENSIONS -> FileCategory.CODE
            in READABLE_EXTENSIONS -> FileCategory.READABLE
            else -> FileCategory.UNKNOWN
        }
    }
}
