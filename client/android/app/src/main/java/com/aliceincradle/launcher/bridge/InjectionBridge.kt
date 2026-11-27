package com.aliceincradle.launcher.bridge

import android.content.Context
import com.aliceincradle.launcher.data.SafeProbeIo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

enum class InjectionPhase {
    IDLE,
    GAME_RESOLVED,
    FRAMEWORK_READY,
    AWAITING_CONFIRMATION,
    INJECTING,
    VERIFYING,
    READY,
    FAILED,
    CANCELLED
}

data class InjectionPlan(
    val gameRoot: UnityGameRoot,
    val frameworkSource: FrameworkSource,
    val plugins: List<PluginCandidate>,
    val plannedFiles: List<String>,
    val estimatedBytes: Long
)

data class InjectionCheckpoint(
    val phase: String,
    val targetDir: String,
    val filesWritten: List<String>
)

data class PluginVerifyRecord(
    val fileName: String,
    val actualSha256: String,
    val expectedSha256: String?,
    val status: String
)

data class InjectionReport(
    val targetDir: String,
    val runtime: String,
    val frameworkSource: String,
    val frameworkSha256: String,
    val pluginRecords: List<PluginVerifyRecord>,
    val durationMs: Long,
    val status: String,
    val failureReason: String? = null
)

data class InjectionUiState(
    val envStatus: EnvironmentStatus = EnvironmentStatus.GAME_NOT_FOUND,
    val phase: InjectionPhase = InjectionPhase.IDLE,
    val gameRoot: UnityGameRoot? = null,
    val frameworkSource: FrameworkSource? = null,
    val pendingPlan: InjectionPlan? = null,
    val pendingCheckpoint: InjectionCheckpoint? = null,
    val lastReport: InjectionReport? = null,
    val statusMessage: String? = null,
    val isBusy: Boolean = false
)

/**
 * Atomic BepInEx injection engine with pre-write confirmation, checkpoint recovery,
 * strict path-traversal defense, and post-write SHA-256 verification.
 */
class InjectionBridge(
    private val probe: EnvironmentProbe = EnvironmentProbe()
) {

    fun evaluateEnvironment(
        context: Context?,
        filesDir: File,
        userGameDir: File? = null,
        userFrameworkFile: File? = null,
        userPluginDir: File? = null
    ): Triple<EnvironmentStatus, UnityGameRoot?, FrameworkSource?> {
        val gameOutcome = probe.resolveGameRoot(context, userGameDir)
        val gameRoot = when (gameOutcome) {
            is GameProbeOutcome.Found -> gameOutcome.root
            is GameProbeOutcome.PermissionDenied -> {
                return Triple(EnvironmentStatus.PERMISSION_DENIED, null, null)
            }
            GameProbeOutcome.NotFound -> {
                return Triple(EnvironmentStatus.GAME_NOT_FOUND, null, null)
            }
        }

        if (!gameRoot.rootDir.canWrite()) {
            return Triple(EnvironmentStatus.PERMISSION_DENIED, gameRoot, null)
        }

        val marker = injectedMarkerFile(filesDir, gameRoot.rootDir)
        if (marker.isFile && File(gameRoot.rootDir, "BepInEx/core").isDirectory) {
            val fw = probe.resolveFrameworkSource(context, userFrameworkFile)
            return Triple(EnvironmentStatus.ALREADY_INJECTED, gameRoot, fw)
        }

        val framework = probe.resolveFrameworkSource(context, userFrameworkFile)
            ?: return Triple(EnvironmentStatus.FRAMEWORK_MISSING, gameRoot, null)

        return Triple(EnvironmentStatus.READY, gameRoot, framework)
    }

    fun resolveGameRoot(context: Context?, userSelectedDir: File? = null): GameProbeOutcome {
        return probe.resolveGameRoot(context, userSelectedDir)
    }

    fun resolvePlugins(context: Context?, userPluginDir: File? = null): List<PluginCandidate> {
        return probe.resolveUserPlugins(context, userPluginDir)
    }

    fun prepareInjectionPlan(
        gameRoot: UnityGameRoot,
        frameworkSource: FrameworkSource,
        plugins: List<PluginCandidate>
    ): Result<InjectionPlan> {
        return try {
            if (gameRoot.runtime == "unknown") {
                return Result.failure(IOException("未知 Unity 运行时（未检测到 Managed/ 或 GameAssembly.dll），中止注入"))
            }
            if (!gameRoot.rootDir.canWrite()) {
                return Result.failure(SecurityException("目标目录不可写：${gameRoot.rootDir.absolutePath}"))
            }

            val planned = mutableListOf<String>()
            var totalBytes = 0L

            if (frameworkSource.isArchive) {
                ZipInputStream(FileInputStream(frameworkSource.sourceFile)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val cleanName = entry.name.trimStart('/')
                            if (cleanName.isNotEmpty()) {
                                planned += cleanName
                                if (entry.size > 0L) totalBytes += entry.size
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                frameworkSource.sourceFile.walkTopDown().forEach { f ->
                    if (f.isFile) {
                        val rel = f.relativeTo(frameworkSource.sourceFile).invariantSeparatorsPath
                        planned += rel
                        totalBytes += f.length()
                    }
                }
            }

            for (plugin in plugins) {
                planned += "BepInEx/plugins/${plugin.file.name}"
                totalBytes += plugin.file.length()
            }

            Result.success(
                InjectionPlan(
                    gameRoot = gameRoot,
                    frameworkSource = frameworkSource,
                    plugins = plugins,
                    plannedFiles = planned,
                    estimatedBytes = totalBytes
                )
            )
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied preparing injection plan", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error preparing injection plan", e)
            Result.failure(e)
        }
    }

    fun detectPendingCheckpoint(cacheDir: File): InjectionCheckpoint? {
        val file = File(cacheDir, CHECKPOINT_FILE_NAME)
        if (!file.isFile) return null
        return try {
            val lines = file.readLines(Charsets.UTF_8)
            var phase = ""
            var targetDir = ""
            val written = mutableListOf<String>()
            for (line in lines) {
                when {
                    line.startsWith("phase=") -> phase = line.removePrefix("phase=").trim()
                    line.startsWith("target_dir=") -> targetDir = line.removePrefix("target_dir=").trim()
                    line.startsWith("file=") -> {
                        val rel = line.removePrefix("file=").trim()
                        if (rel.isNotEmpty()) written += rel
                    }
                }
            }
            if (targetDir.isEmpty()) null else InjectionCheckpoint(phase, targetDir, written)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "Failed reading injection checkpoint", e)
            null
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied reading injection checkpoint", e)
            null
        }
    }

    fun recoverCheckpoint(cacheDir: File): Result<Int> {
        val checkpoint = detectPendingCheckpoint(cacheDir)
            ?: return Result.success(0)
        return try {
            val targetRoot = File(checkpoint.targetDir)
            var removed = 0
            for (rel in checkpoint.filesWritten.asReversed()) {
                val f = File(targetRoot, rel)
                if (f.exists() && f.delete()) {
                    removed++
                }
            }
            cleanupEmptyBepInExDirs(targetRoot)
            File(cacheDir, CHECKPOINT_FILE_NAME).delete()
            Result.success(removed)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Permission denied recovering checkpoint", e)
            Result.failure(e)
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IO error recovering checkpoint", e)
            Result.failure(e)
        }
    }

    fun executeInjection(
        plan: InjectionPlan,
        filesDir: File,
        cacheDir: File
    ): Result<InjectionReport> {
        val startMs = System.currentTimeMillis()
        val targetRoot = plan.gameRoot.rootDir
        val writtenRelPaths = mutableListOf<String>()
        val checkpointFile = File(cacheDir, CHECKPOINT_FILE_NAME)

        fun persistCheckpoint(phase: InjectionPhase) {
            writeCheckpoint(checkpointFile, InjectionCheckpoint(phase.name, targetRoot.absolutePath, writtenRelPaths))
        }

        fun rollbackAll(reason: String): Result<InjectionReport> {
            for (rel in writtenRelPaths.asReversed()) {
                runCatching { File(targetRoot, rel).delete() }
            }
            runCatching { cleanupEmptyBepInExDirs(targetRoot) }
            runCatching { checkpointFile.delete() }
            val failedReport = InjectionReport(
                targetDir = targetRoot.absolutePath,
                runtime = plan.gameRoot.runtime,
                frameworkSource = plan.frameworkSource.sourceFile.absolutePath,
                frameworkSha256 = "",
                pluginRecords = emptyList(),
                durationMs = System.currentTimeMillis() - startMs,
                status = "FAILED",
                failureReason = reason
            )
            writeReportFile(File(filesDir, REPORT_FILE_NAME), failedReport)
            return Result.failure(IOException(reason))
        }

        return try {
            persistCheckpoint(InjectionPhase.INJECTING)

            val rootCanonical = targetRoot.canonicalFile
            if (plan.frameworkSource.isArchive) {
                ZipInputStream(FileInputStream(plan.frameworkSource.sourceFile)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val rel = entry.name.trimStart('/')
                            if (rel.isNotEmpty()) {
                                val outFile = File(targetRoot, rel).canonicalFile
                                if (!outFile.path.startsWith(rootCanonical.path + File.separator)) {
                                    return rollbackAll("检测到非法压缩包路径：$rel")
                                }
                                if (isProtectedGameFile(rel)) {
                                    return rollbackAll("禁止覆写游戏本体文件：$rel")
                                }
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                                writtenRelPaths += rel
                                persistCheckpoint(InjectionPhase.INJECTING)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                plan.frameworkSource.sourceFile.walkTopDown().forEach { src ->
                    if (src.isFile) {
                        val rel = src.relativeTo(plan.frameworkSource.sourceFile).invariantSeparatorsPath
                        if (isProtectedGameFile(rel)) {
                            return rollbackAll("禁止覆写游戏本体文件：$rel")
                        }
                        val outFile = File(targetRoot, rel)
                        outFile.parentFile?.mkdirs()
                        FileInputStream(src).use { input ->
                            FileOutputStream(outFile).use { output -> input.copyTo(output) }
                        }
                        writtenRelPaths += rel
                        persistCheckpoint(InjectionPhase.INJECTING)
                    }
                }
            }

            // Copy user plugins into BepInEx/plugins/
            val pluginsDir = File(targetRoot, "BepInEx/plugins")
            pluginsDir.mkdirs()
            for (plugin in plan.plugins) {
                val rel = "BepInEx/plugins/${plugin.file.name}"
                val dst = File(targetRoot, rel)
                FileInputStream(plugin.file).use { input ->
                    FileOutputStream(dst).use { output -> input.copyTo(output) }
                }
                writtenRelPaths += rel
                persistCheckpoint(InjectionPhase.INJECTING)
            }

            // VERIFYING
            persistCheckpoint(InjectionPhase.VERIFYING)
            val doorstopIni = File(targetRoot, "doorstop_config.ini")
            val winhttpDll = File(targetRoot, "winhttp.dll")
            val coreDir = File(targetRoot, "BepInEx/core")
            if (!doorstopIni.isFile || !winhttpDll.isFile) {
                return rollbackAll("校验失败：doorstop_config.ini 或 winhttp.dll 缺失")
            }
            val coreFiles = coreDir.listFiles()
            if (!coreDir.isDirectory || coreFiles == null || coreFiles.isEmpty()) {
                return rollbackAll("校验失败：BepInEx/core/ 目录为空或不存在")
            }

            val frameworkSha256 = if (plan.frameworkSource.isArchive) {
                computeSha256(plan.frameworkSource.sourceFile)
            } else {
                "directory-source"
            }

            val pluginRecords = mutableListOf<PluginVerifyRecord>()
            for (plugin in plan.plugins) {
                val installed = File(pluginsDir, plugin.file.name)
                if (!installed.isFile) {
                    return rollbackAll("校验失败：插件未落地 ${plugin.file.name}")
                }
                val actualSha = computeSha256(installed)
                val expected = plugin.expectedSha256
                if (expected != null && !actualSha.equals(expected, ignoreCase = true)) {
                    return rollbackAll("校验失败：插件 ${plugin.file.name} SHA-256 不匹配")
                }
                pluginRecords += PluginVerifyRecord(
                    fileName = plugin.file.name,
                    actualSha256 = actualSha,
                    expectedSha256 = expected,
                    status = if (expected != null) "MATCHED" else "SKIPPED_NO_DECLARATION"
                )
            }

            val report = InjectionReport(
                targetDir = targetRoot.absolutePath,
                runtime = plan.gameRoot.runtime,
                frameworkSource = plan.frameworkSource.sourceFile.absolutePath,
                frameworkSha256 = frameworkSha256,
                pluginRecords = pluginRecords,
                durationMs = System.currentTimeMillis() - startMs,
                status = "READY"
            )
            writeReportFile(File(filesDir, REPORT_FILE_NAME), report)
            injectedMarkerFile(filesDir, targetRoot).writeText("READY:${System.currentTimeMillis()}", Charsets.UTF_8)
            checkpointFile.delete()
            Result.success(report)
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "SecurityException during injection", e)
            rollbackAll("权限拒绝：${e.message}")
        } catch (e: IOException) {
            SafeProbeIo.warn(TAG, "IOException during injection", e)
            rollbackAll("IO 异常：${e.message}")
        }
    }

    private fun isProtectedGameFile(relPath: String): Boolean {
        val lower = relPath.lowercase(Locale.ROOT)
        return lower.endsWith(".exe") ||
            lower == "unityplayer.dll" ||
            lower == "gameassembly.dll" ||
            lower.contains("_data/")
    }

    private fun cleanupEmptyBepInExDirs(targetRoot: File) {
        val bepDir = File(targetRoot, "BepInEx")
        if (bepDir.isDirectory) {
            bepDir.walkBottomUp().forEach { dir ->
                if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) {
                    dir.delete()
                }
            }
        }
    }

    private fun writeCheckpoint(file: File, checkpoint: InjectionCheckpoint) {
        file.parentFile?.mkdirs()
        val content = buildString {
            appendLine("phase=${checkpoint.phase}")
            appendLine("target_dir=${checkpoint.targetDir}")
            checkpoint.filesWritten.forEach { appendLine("file=$it") }
        }
        file.writeText(content, Charsets.UTF_8)
    }

    private fun writeReportFile(file: File, report: InjectionReport) {
        runCatching {
            file.parentFile?.mkdirs()
            val pluginsJson = report.pluginRecords.joinToString(",") { rec ->
                """{"file_name":"${escapeJson(rec.fileName)}","actual_sha256":"${rec.actualSha256}","expected_sha256":${rec.expectedSha256?.let { "\"$it\"" } ?: "null"},"status":"${rec.status}"}"""
            }
            val json = """
                {
                  "target_dir": "${escapeJson(report.targetDir)}",
                  "runtime": "${escapeJson(report.runtime)}",
                  "framework_source": "${escapeJson(report.frameworkSource)}",
                  "framework_sha256": "${report.frameworkSha256}",
                  "plugins": [$pluginsJson],
                  "duration_ms": ${report.durationMs},
                  "status": "${report.status}",
                  "failure_reason": ${report.failureReason?.let { "\"${escapeJson(it)}\"" } ?: "null"}
                }
            """.trimIndent()
            file.writeText(json, Charsets.UTF_8)
        }.onFailure { err ->
            SafeProbeIo.warn(TAG, "Failed writing inject_report.json", err)
        }
    }

    private fun escapeJson(raw: String): String {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun injectedMarkerFile(filesDir: File, targetRoot: File): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(targetRoot.absolutePath.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return File(filesDir, ".aicl_injected_$digest")
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "InjectionBridge"
        const val CHECKPOINT_FILE_NAME = ".aicl_inject_checkpoint"
        const val REPORT_FILE_NAME = "inject_report.json"
    }
}
