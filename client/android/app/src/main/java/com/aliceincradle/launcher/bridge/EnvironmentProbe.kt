package com.aliceincradle.launcher.bridge

import android.content.Context
import android.os.Environment
import com.aliceincradle.launcher.data.SafeProbeIo
import java.io.File
import java.util.Locale

enum class EnvironmentStatus {
    READY,
    GAME_NOT_FOUND,
    FRAMEWORK_MISSING,
    PERMISSION_DENIED,
    ALREADY_INJECTED
}

data class UnityGameRoot(
    val rootDir: File,
    val exeName: String,
    val dataDirName: String,
    val hasManaged: Boolean,
    val hasGameAssembly: Boolean,
    val runtime: String
)

data class FrameworkSource(
    val sourceFile: File,
    val isArchive: Boolean
)

data class PluginCandidate(
    val file: File,
    val expectedSha256: String? = null
)

sealed class GameProbeOutcome {
    data class Found(val root: UnityGameRoot) : GameProbeOutcome()
    object NotFound : GameProbeOutcome()
    data class PermissionDenied(val reason: String) : GameProbeOutcome()
}

/**
 * Dynamic environment probe for Unity game roots, local BepInEx framework packages, and user plugins.
 * Never hardcodes absolute storage paths; all roots are resolved via Context/Environment/envProvider.
 */
class EnvironmentProbe(
    private val envProvider: () -> Map<String, String> = {
        try {
            System.getenv()
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "Unable to read process environment", e)
            emptyMap()
        }
    }
) {

    fun resolveGameRoot(context: Context?, userSelectedDir: File? = null): GameProbeOutcome {
        if (userSelectedDir != null) {
            when (val res = inspectCandidateTree(userSelectedDir, maxDepth = 2)) {
                is GameProbeOutcome.Found -> return res
                is GameProbeOutcome.PermissionDenied -> return res
                GameProbeOutcome.NotFound -> Unit
            }
        }

        val env = envProvider()
        val candidates = linkedSetOf<File>()
        env["AIC_GAME_DIR"]?.takeIf { it.isNotBlank() }?.let { candidates += File(it) }
        env["WINEPREFIX"]?.takeIf { it.isNotBlank() }?.let { prefix ->
            candidates += File(prefix, "drive_c")
        }
        env["PREFIX"]?.takeIf { it.isNotBlank() }?.let { prefix ->
            candidates += File(prefix, "game_assets")
        }

        if (context != null) {
            try {
                context.getExternalFilesDir(null)?.let { candidates += it }
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { candidates += it }
                context.filesDir?.let { candidates += it }
            } catch (e: SecurityException) {
                SafeProbeIo.warn(TAG, "Permission denied querying app storage directories", e)
                return GameProbeOutcome.PermissionDenied(e.message ?: "无法访问应用存储目录")
            } catch (e: RuntimeException) {
                SafeProbeIo.debug(TAG, "App storage directory unavailable: ${e.message}")
            }
        }

        var deniedReason: String? = null
        for (candidate in candidates) {
            when (val res = inspectCandidateTree(candidate, maxDepth = 2)) {
                is GameProbeOutcome.Found -> return res
                is GameProbeOutcome.PermissionDenied -> if (deniedReason == null) deniedReason = res.reason
                GameProbeOutcome.NotFound -> Unit
            }
        }

        return if (deniedReason != null) {
            GameProbeOutcome.PermissionDenied(deniedReason)
        } else {
            GameProbeOutcome.NotFound
        }
    }

    fun resolveFrameworkSource(
        context: Context?,
        userSelectedFramework: File? = null
    ): FrameworkSource? {
        if (userSelectedFramework != null) {
            classifyFrameworkFile(userSelectedFramework)?.let { return it }
        }

        val env = envProvider()
        val candidateFilesAndDirs = linkedSetOf<File>()
        env["AIC_BEPINEX_PATH"]?.takeIf { it.isNotBlank() }?.let { candidateFilesAndDirs += File(it) }

        if (context != null) {
            runCatching {
                context.filesDir?.let { filesRoot ->
                    candidateFilesAndDirs += File(filesRoot, "frameworks")
                    candidateFilesAndDirs += filesRoot
                }
                context.cacheDir?.let { cacheRoot ->
                    candidateFilesAndDirs += File(cacheRoot, "frameworks")
                }
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dl ->
                    candidateFilesAndDirs += dl
                }
                context.getExternalFilesDir(null)?.let { ext ->
                    candidateFilesAndDirs += File(ext, "frameworks")
                    candidateFilesAndDirs += ext
                }
            }.onFailure { err ->
                SafeProbeIo.warn(TAG, "Failed querying framework candidate directories", err)
            }
        }

        for (candidate in candidateFilesAndDirs) {
            classifyFrameworkFile(candidate)?.let { return it }
            if (candidate.isDirectory && candidate.canRead()) {
                val children = runCatching { candidate.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    classifyFrameworkFile(child)?.let { return it }
                }
            }
        }
        return null
    }

    fun resolveUserPlugins(context: Context?, userPluginDir: File? = null): List<PluginCandidate> {
        val dirs = linkedSetOf<File>()
        if (userPluginDir != null) {
            dirs += userPluginDir
        }
        if (context != null) {
            runCatching {
                context.filesDir?.let { dirs += File(it, "user_plugins") }
                context.getExternalFilesDir(null)?.let { dirs += File(it, "user_plugins") }
            }.onFailure { err ->
                SafeProbeIo.warn(TAG, "Failed querying user plugin directories", err)
            }
        }

        val results = mutableListOf<PluginCandidate>()
        val seenNames = mutableSetOf<String>()
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) continue
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (file in files) {
                if (!file.isFile || !file.name.endsWith(".dll", ignoreCase = true)) continue
                if (!seenNames.add(file.name.lowercase(Locale.ROOT))) continue
                val shaFile = File(dir, "${file.name}.sha256")
                val expectedSha = if (shaFile.isFile && shaFile.canRead()) {
                    SafeProbeIo.readAllText(TAG, shaFile)
                        ?.trim()
                        ?.split(Regex("\\s+"))
                        ?.firstOrNull()
                        ?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }
                        ?.lowercase(Locale.ROOT)
                } else {
                    null
                }
                results += PluginCandidate(file = file, expectedSha256 = expectedSha)
            }
        }
        return results
    }

    private fun inspectCandidateTree(dir: File, maxDepth: Int): GameProbeOutcome {
        return try {
            if (!dir.exists() || !dir.isDirectory) return GameProbeOutcome.NotFound
            if (!dir.canRead()) {
                return GameProbeOutcome.PermissionDenied("无权读取目录：${dir.absolutePath}")
            }
            detectUnityRootInDir(dir)?.let { return GameProbeOutcome.Found(it) }
            if (maxDepth <= 0) return GameProbeOutcome.NotFound
            val children = dir.listFiles() ?: return GameProbeOutcome.NotFound
            for (child in children) {
                if (child.isDirectory && !child.name.startsWith(".")) {
                    when (val nested = inspectCandidateTree(child, maxDepth - 1)) {
                        is GameProbeOutcome.Found -> return nested
                        is GameProbeOutcome.PermissionDenied -> return nested
                        GameProbeOutcome.NotFound -> Unit
                    }
                }
            }
            GameProbeOutcome.NotFound
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "SecurityException probing ${dir.absolutePath}", e)
            GameProbeOutcome.PermissionDenied(e.message ?: "权限被拒绝")
        }
    }

    private fun detectUnityRootInDir(dir: File): UnityGameRoot? {
        val entries = dir.listFiles() ?: return null
        val exeFiles = entries.filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
        for (exe in exeFiles) {
            val stem = exe.nameWithoutExtension
            val dataDir = entries.firstOrNull {
                it.isDirectory && it.name.equals("${stem}_Data", ignoreCase = true)
            } ?: continue
            val hasManaged = File(dataDir, "Managed").isDirectory
            val hasGameAssembly = entries.any {
                it.isFile && it.name.equals("GameAssembly.dll", ignoreCase = true)
            }
            val runtime = when {
                hasManaged -> "mono"
                hasGameAssembly -> "il2cpp"
                else -> "unknown"
            }
            return UnityGameRoot(
                rootDir = dir,
                exeName = exe.name,
                dataDirName = dataDir.name,
                hasManaged = hasManaged,
                hasGameAssembly = hasGameAssembly,
                runtime = runtime
            )
        }
        return null
    }

    private fun classifyFrameworkFile(file: File): FrameworkSource? {
        return try {
            if (!file.exists() || !file.canRead()) return null
            if (file.isFile &&
                file.name.contains("bepinex", ignoreCase = true) &&
                file.name.endsWith(".zip", ignoreCase = true)
            ) {
                FrameworkSource(sourceFile = file, isArchive = true)
            } else if (file.isDirectory && File(file, "BepInEx/core").isDirectory) {
                FrameworkSource(sourceFile = file, isArchive = false)
            } else {
                null
            }
        } catch (e: SecurityException) {
            SafeProbeIo.warn(TAG, "SecurityException inspecting framework ${file.path}", e)
            null
        }
    }

    companion object {
        private const val TAG = "EnvironmentProbe"
    }
}
