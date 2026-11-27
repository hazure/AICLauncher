package com.aliceincradle.launcher.bridge

import android.util.Log
import com.aliceincradle.launcher.data.SafeProbeIo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class ArchiveContainer {
    SEVEN_Z,
    ZIP,
    CF_CHALLENGE_HTML,
    UNKNOWN
}

class ExtractorMissingException(message: String) : IOException(message)

data class ExtractCheckpoint(
    val phase: String,
    val urlFingerprint: String,
    val tempDir: String,
    val targetDir: String
)

/**
 * Magic-number-based archive extractor (Decision D3, D4, D7).
 * Reuses [InjectionBridge.isPathInsideRoot] for Zip-Slip defense.
 */
class ArchiveExtractor(
    private val probe: EnvironmentProbe = EnvironmentProbe()
) {

    fun detectContainer(file: File): ArchiveContainer {
        if (!file.isFile || !file.canRead()) return ArchiveContainer.UNKNOWN
        return try {
            val header = ByteArray(64)
            val read = FileInputStream(file).use { it.read(header) }
            if (read >= 6 &&
                header[0] == 0x37.toByte() &&
                header[1] == 0x7A.toByte() &&
                header[2] == 0xBC.toByte() &&
                header[3] == 0xAF.toByte() &&
                header[4] == 0x27.toByte() &&
                header[5] == 0x1C.toByte()
            ) {
                ArchiveContainer.SEVEN_Z
            } else if (read >= 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
            ) {
                ArchiveContainer.ZIP
            } else if (read > 0) {
                val headText = String(header, 0, read, Charsets.US_ASCII).lowercase()
                if (headText.contains("<!doctype") || headText.contains("<html")) {
                    ArchiveContainer.CF_CHALLENGE_HTML
                } else {
                    ArchiveContainer.UNKNOWN
                }
            } else {
                ArchiveContainer.UNKNOWN
            }
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "Failed reading magic bytes for ${file.name}", e)
            ArchiveContainer.UNKNOWN
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied reading magic bytes for ${file.name}", e)
            ArchiveContainer.UNKNOWN
        }
    }

    fun detectPendingExtractCheckpoint(cacheDir: File): ExtractCheckpoint? {
        val file = File(cacheDir, EXTRACT_CHECKPOINT_FILE)
        if (!file.isFile) return null
        return try {
            var phase = ""
            var fp = ""
            var tempDir = ""
            var targetDir = ""
            file.readLines(Charsets.UTF_8).forEach { line ->
                when {
                    line.startsWith("phase=") -> phase = line.removePrefix("phase=").trim()
                    line.startsWith("url_fp=") -> fp = line.removePrefix("url_fp=").trim()
                    line.startsWith("temp_dir=") -> tempDir = line.removePrefix("temp_dir=").trim()
                    line.startsWith("target_dir=") -> targetDir = line.removePrefix("target_dir=").trim()
                }
            }
            if (tempDir.isEmpty()) null else ExtractCheckpoint(phase, fp, tempDir, targetDir)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "Failed reading extract checkpoint", e)
            null
        }
    }

    fun recoverExtractCheckpoint(cacheDir: File): Boolean {
        val ckpt = detectPendingExtractCheckpoint(cacheDir) ?: return false
        runCatching { File(ckpt.tempDir).deleteRecursively() }
        return File(cacheDir, EXTRACT_CHECKPOINT_FILE).delete()
    }

    fun extractAtomically(
        archiveFile: File,
        targetDir: File,
        cacheDir: File,
        filesDir: File,
        urlFingerprint: String = ""
    ): Result<File> {
        val startMs = System.currentTimeMillis()
        val container = detectContainer(archiveFile)
        runCatching { Log.i(TAG, "EXTRACTING start container=$container fp=$urlFingerprint") }

        val tempDir = File(cacheDir, "extract_tmp_${System.currentTimeMillis()}")
        val checkpointFile = File(cacheDir, EXTRACT_CHECKPOINT_FILE)

        fun cleanupAndFail(error: Exception): Result<File> {
            runCatching { tempDir.deleteRecursively() }
            runCatching { checkpointFile.delete() }
            return Result.failure(error)
        }

        return try {
            tempDir.mkdirs()
            checkpointFile.writeText(
                "phase=EXTRACTING\nurl_fp=$urlFingerprint\ntemp_dir=${tempDir.absolutePath}\ntarget_dir=${targetDir.absolutePath}\n",
                Charsets.UTF_8
            )

            when (container) {
                ArchiveContainer.SEVEN_Z -> {
                    val sevenZip = probe.resolveSevenZipExecutable()
                        ?: return cleanupAndFail(ExtractorMissingException("该包为 7z，需 7z 支持"))
                    val process = ProcessBuilder(
                        listOf(sevenZip.absolutePath, "x", "-y", "-o${tempDir.absolutePath}", archiveFile.absolutePath)
                    ).redirectErrorStream(true).start()
                    process.inputStream.bufferedReader().use { reader ->
                        var lines = 0
                        while (reader.readLine() != null && lines < 500) {
                            lines++
                        }
                    }
                    val finished = process.waitFor(120, TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        return cleanupAndFail(IOException("7z 解压超时"))
                    }
                    if (process.exitValue() != 0) {
                        return cleanupAndFail(IOException("7z 解压失败，退出码 ${process.exitValue()}"))
                    }
                }
                ArchiveContainer.ZIP -> {
                    val tempCanonical = tempDir.canonicalFile
                    ZipInputStream(FileInputStream(archiveFile)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val rel = entry.name.trimStart('/')
                            if (rel.isNotEmpty()) {
                                val outFile = File(tempDir, rel).canonicalFile
                                if (!InjectionBridge.isPathInsideRoot(tempCanonical, outFile)) {
                                    return cleanupAndFail(IOException("检测到非法压缩包路径：$rel"))
                                }
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                ArchiveContainer.CF_CHALLENGE_HTML -> {
                    return cleanupAndFail(IOException("CF_CHALLENGE_HTML: 命中 Cloudflare 挑战页，非压缩包文件"))
                }
                ArchiveContainer.UNKNOWN -> {
                    return cleanupAndFail(IOException("未知压缩包格式（非 7z 或 zip 魔数）"))
                }
            }

            if (tempDir.listFiles().isNullOrEmpty()) {
                return cleanupAndFail(IOException("解压结果为空"))
            }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            tempDir.listFiles()?.forEach { child ->
                val dest = File(targetDir, child.name)
                if (dest.exists()) dest.deleteRecursively()
                if (!child.renameTo(dest)) {
                    child.copyRecursively(dest, overwrite = true)
                    child.deleteRecursively()
                }
            }
            tempDir.deleteRecursively()
            File(filesDir, ".aicl_extracted_${urlFingerprint.ifBlank { "local" }}").writeText(
                "READY:${System.currentTimeMillis()}",
                Charsets.UTF_8
            )
            checkpointFile.delete()
            runCatching { Log.i(TAG, "EXTRACTING ok elapsedMs=${System.currentTimeMillis() - startMs}") }
            Result.success(targetDir)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied extracting archive", e)
            cleanupAndFail(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error extracting archive", e)
            cleanupAndFail(e)
        }
    }

    companion object {
        private const val TAG = "AICL_PIPELINE"
        const val EXTRACT_CHECKPOINT_FILE = ".aicl_extract_checkpoint"
    }
}
