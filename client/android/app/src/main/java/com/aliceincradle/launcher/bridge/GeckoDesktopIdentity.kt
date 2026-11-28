package com.aliceincradle.launcher.bridge

import java.net.URI
import java.net.URLDecoder

object GeckoDesktopIdentity {
    const val GECKO_MAJOR_VERSION = 133

    private val FORBIDDEN_UA_TOKENS = listOf(
        "chrome",
        "crios",
        "edg/",
        "android",
        "mobile",
        "tablet",
        "sec-ch-ua"
    )

    private val FILENAME_STAR_REGEX = Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8'')?([^;\r\n]+)""")
    private val FILENAME_QUOTED_REGEX = Regex("""filename\s*=\s*"([^"]+)"""")
    private val FILENAME_PLAIN_REGEX = Regex("""filename\s*=\s*([^;\r\n]+)""")

    fun buildDesktopFirefoxUserAgent(majorVersion: Int = GECKO_MAJOR_VERSION): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:${majorVersion}.0) Gecko/20100101 Firefox/${majorVersion}.0"
    }

    fun isValidDesktopFirefoxUa(ua: String): Boolean {
        val lower = ua.lowercase()
        if (!ua.contains("Firefox/") || !ua.contains("Gecko/20100101") || !ua.contains("Windows NT")) {
            return false
        }
        return FORBIDDEN_UA_TOKENS.none { token -> lower.contains(token) }
    }

    fun extractFileNameFromContentDisposition(contentDisposition: String?, fallbackUrl: String): String {
        val cd = contentDisposition.orEmpty()
        if (cd.isNotBlank()) {
            FILENAME_STAR_REGEX.find(cd)?.groupValues?.getOrNull(1)?.trim()?.trim('"')?.let { raw ->
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                if (decoded.isNotBlank()) return decoded
            }
            FILENAME_QUOTED_REGEX.find(cd)?.groupValues?.getOrNull(1)?.trim()?.let { name ->
                if (name.isNotBlank()) return name
            }
            FILENAME_PLAIN_REGEX.find(cd)?.groupValues?.getOrNull(1)?.trim()?.trim('"')?.let { name ->
                if (name.isNotBlank()) return name
            }
        }
        val fromUrl = runCatching {
            URI(fallbackUrl).path?.substringAfterLast('/')?.trim()
        }.getOrNull().orEmpty()
        return fromUrl.ifBlank { ShortCodeRelayExtractor.DEFAULT_ARCHIVE_NAME }
    }
}
