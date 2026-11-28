package com.aliceincradle.launcher.ui.file

import com.aliceincradle.launcher.data.SafeProbeIo
import com.aliceincradle.launcher.ui.icon.FileIconResolver
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

data class TextReadResult(
    val content: String,
    val isTruncated: Boolean,
    val hasBom: Boolean,
    val lineEnding: String,
    val totalBytes: Long
)

/**
 * Safe UTF-8 text codec with binary detection, 2MB truncation, BOM/CRLF preservation,
 * and atomic `.tmp -> fsync -> .bak -> rename` writes.
 */
object FileTextCodec {
    private const val TAG = "FileTextCodec"
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun readForViewer(
        file: File,
        maxBytes: Long = FileIconResolver.DEFAULT_MAX_TEXT_BYTES,
        maxLines: Int = 2000
    ): Result<TextReadResult> {
        return try {
            if (!file.isFile || !file.canRead()) {
                return Result.failure(SecurityException("PERMISSION_DENIED: 无法读取文件 ${file.name}"))
            }
            if (FileIconResolver.isBinaryContent(file)) {
                return Result.failure(IOException("二进制文件不支持预览"))
            }
            val length = file.length()
            if (length <= maxBytes) {
                val rawBytes = file.readBytes()
                val hasBom = hasUtf8Bom(rawBytes)
                val text = decodeUtf8(rawBytes, hasBom)
                val lineEnding = if (text.contains("\r\n")) "\r\n" else "\n"
                Result.success(TextReadResult(text, isTruncated = false, hasBom = hasBom, lineEnding = lineEnding, totalBytes = length))
            } else {
                val lines = mutableListOf<String>()
                BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                    var count = 0
                    while (count < maxLines) {
                        val line = reader.readLine() ?: break
                        lines += line
                        count++
                    }
                }
                val joined = lines.joinToString("\n").removePrefix("\uFEFF")
                Result.success(TextReadResult(joined, isTruncated = true, hasBom = false, lineEnding = "\n", totalBytes = length))
            }
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied reading ${file.name}", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error reading ${file.name}", e)
            Result.failure(e)
        }
    }

    fun readForEditor(
        file: File,
        maxBytes: Long = FileIconResolver.DEFAULT_MAX_TEXT_BYTES
    ): Result<TextReadResult> {
        return try {
            if (!file.isFile || !file.canRead()) {
                return Result.failure(SecurityException("PERMISSION_DENIED: 无法读取文件 ${file.name}"))
            }
            if (FileIconResolver.isBinaryContent(file)) {
                return Result.failure(IOException("二进制文件不支持编辑"))
            }
            val length = file.length()
            if (length > maxBytes) {
                return Result.failure(IOException("文件超过 ${maxBytes / (1024 * 1024)}MB，不支持直接编辑"))
            }
            val rawBytes = file.readBytes()
            val hasBom = hasUtf8Bom(rawBytes)
            val text = decodeUtf8(rawBytes, hasBom)
            val lineEnding = if (text.contains("\r\n")) "\r\n" else "\n"
            Result.success(TextReadResult(text, isTruncated = false, hasBom = hasBom, lineEnding = lineEnding, totalBytes = length))
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied opening editor for ${file.name}", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error opening editor for ${file.name}", e)
            Result.failure(e)
        }
    }

    fun saveAtomically(
        file: File,
        content: String,
        hasBom: Boolean,
        lineEnding: String
    ): Result<Unit> {
        val parent = file.parentFile
        if (parent == null || !parent.exists() || !parent.canWrite() || (file.exists() && !file.canWrite())) {
            return Result.failure(SecurityException("PERMISSION_DENIED: 目标目录或文件不可写"))
        }

        val normalized = if (lineEnding == "\r\n") {
            content.replace("\r\n", "\n").replace("\n", "\r\n")
        } else {
            content.replace("\r\n", "\n")
        }
        val payloadBytes = normalized.toByteArray(Charsets.UTF_8)
        val tmpFile = File(parent, "${file.name}.tmp")
        val bakFile = File(parent, "${file.name}.bak")

        return try {
            FileOutputStream(tmpFile).use { fos ->
                if (hasBom) {
                    fos.write(UTF8_BOM)
                }
                fos.write(payloadBytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }

            if (file.exists()) {
                if (bakFile.exists()) {
                    bakFile.delete()
                }
                if (!file.renameTo(bakFile)) {
                    tmpFile.delete()
                    return Result.failure(IOException("无法创建备份文件 ${bakFile.name}"))
                }
            }

            if (!tmpFile.renameTo(file)) {
                if (bakFile.exists()) {
                    bakFile.renameTo(file)
                }
                tmpFile.delete()
                return Result.failure(IOException("原子替换文件失败 ${file.name}"))
            }
            Result.success(Unit)
        } catch (e: SecurityException) {
            tmpFile.delete()
            SafeProbeIo.warn(TAG, "PERMISSION_DENIED saving ${file.name}", e)
            Result.failure(SecurityException("PERMISSION_DENIED: ${e.message}", e))
        } catch (e: IOException) {
            tmpFile.delete()
            if (!file.exists() && bakFile.exists()) {
                runCatching { bakFile.renameTo(file) }
            }
            SafeProbeIo.warn(TAG, "IO error saving ${file.name}", e)
            Result.failure(e)
        }
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean {
        return bytes.size >= 3 &&
            bytes[0] == UTF8_BOM[0] &&
            bytes[1] == UTF8_BOM[1] &&
            bytes[2] == UTF8_BOM[2]
    }

    private fun decodeUtf8(bytes: ByteArray, hasBom: Boolean): String {
        val offset = if (hasBom) 3 else 0
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }
}
