package com.aliceincradle.launcher.ui.file

import java.net.URLEncoder
import java.util.Locale

data class ConfigField(
    val section: String,
    val key: String,
    val value: String,
    val comment: String = ""
)

data class ConfigTemplate(
    val id: String,
    val displayName: String,
    val filePatterns: Set<String>,
    val knownDescriptions: Map<String, String>
)

data class ParsedConfig(
    val fields: List<ConfigField>,
    val lineEnding: String = "\n"
)

/**
 * Built-in configuration template registry & INI/CFG parser.
 * Allowlisted external search URLs are only constructed on explicit user click; zero auto-fetch.
 */
object ConfigTemplateRegistry {

    private val ALLOWLISTED_SEARCH_BASE = "https://docs.bepinex.dev/search.html?q="

    private val templates: List<ConfigTemplate> = listOf(
        ConfigTemplate(
            id = "doorstop_config",
            displayName = "UnityDoorstop 配置 (doorstop_config.ini)",
            filePatterns = setOf("doorstop_config.ini"),
            knownDescriptions = mapOf(
                "enabled" to "是否启用 Doorstop 注入 (true/false)",
                "target_assembly" to "BepInEx 预加载器入口 DLL 相对路径",
                "redirect_output_log" to "是否重定向 Unity 输出日志",
                "ignore_disable_switch" to "忽略环境变量禁用开关"
            )
        ),
        ConfigTemplate(
            id = "bepinex_cfg",
            displayName = "BepInEx 核心配置 (BepInEx.cfg)",
            filePatterns = setOf("bepinex.cfg"),
            knownDescriptions = mapOf(
                "Enabled" to "是否启用控制台或日志输出",
                "LogLevels" to "日志记录级别 (Fatal, Error, Warning, Message, Info, Debug)",
                "HideManagerGameObject" to "隐藏 BepInEx 管理器 GameObject"
            )
        ),
        ConfigTemplate(
            id = "dxvk_conf",
            displayName = "DXVK 渲染配置 (dxvk.conf)",
            filePatterns = setOf("dxvk.conf"),
            knownDescriptions = mapOf(
                "dxgi.syncInterval" to "垂直同步间隔 (0 关闭，1 开启)",
                "d3d11.maxFeatureLevel" to "Direct3D 11 最大特性级别"
            )
        )
    )

    fun matchTemplate(fileName: String): ConfigTemplate? {
        val lower = fileName.trim().lowercase(Locale.ROOT)
        return templates.firstOrNull { tpl -> lower in tpl.filePatterns }
    }

    fun parseIniConfig(rawText: String, template: ConfigTemplate? = null): ParsedConfig {
        val lineEnding = if (rawText.contains("\r\n")) "\r\n" else "\n"
        val fields = mutableListOf<ConfigField>()
        var currentSection = "General"
        val pendingComments = mutableListOf<String>()

        rawText.split("\r\n", "\n").forEach { rawLine ->
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() -> Unit
                trimmed.startsWith("#") || trimmed.startsWith(";") -> {
                    pendingComments += trimmed.trimStart('#', ';').trim()
                }
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    currentSection = trimmed.substring(1, trimmed.length - 1).trim().ifEmpty { "General" }
                    pendingComments.clear()
                }
                trimmed.contains("=") -> {
                    val eqIdx = trimmed.indexOf('=')
                    val key = trimmed.substring(0, eqIdx).trim()
                    val value = trimmed.substring(eqIdx + 1).trim()
                    if (key.isNotEmpty()) {
                        val comment = pendingComments.joinToString(" ").ifBlank {
                            template?.knownDescriptions?.get(key).orEmpty()
                        }
                        fields += ConfigField(
                            section = currentSection,
                            key = key,
                            value = value,
                            comment = comment
                        )
                    }
                    pendingComments.clear()
                }
            }
        }
        return ParsedConfig(fields = fields, lineEnding = lineEnding)
    }

    fun serializeIniConfig(parsed: ParsedConfig): String {
        val grouped = parsed.fields.groupBy { it.section }
        val sb = StringBuilder()
        grouped.entries.forEachIndexed { secIdx, (section, sectionFields) ->
            if (secIdx > 0) sb.append(parsed.lineEnding)
            sb.append('[').append(section).append(']').append(parsed.lineEnding)
            sectionFields.forEach { field ->
                if (field.comment.isNotBlank()) {
                    sb.append("# ").append(field.comment).append(parsed.lineEnding)
                }
                sb.append(field.key).append(" = ").append(field.value).append(parsed.lineEnding)
            }
        }
        return sb.toString()
    }

    fun buildAllowlistedSearchUrl(fileName: String): String {
        val safeQuery = URLEncoder.encode(fileName.trim(), "UTF-8")
        return "$ALLOWLISTED_SEARCH_BASE$safeQuery"
    }
}
