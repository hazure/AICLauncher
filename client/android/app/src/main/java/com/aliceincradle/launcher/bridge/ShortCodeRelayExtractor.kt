package com.aliceincradle.launcher.bridge

import android.util.Log
import java.net.URI
import java.net.URLDecoder

data class ExtractedShortCode(
    val shortCode: String,
    val fileName: String,
    val fingerprint: String
)

/**
 * Module B: Pure extractor for official short-links, query params, and standalone short codes.
 */
object ShortCodeRelayExtractor {
    const val DEFAULT_ARCHIVE_NAME = "AliceInCradle_Win_Latest.7z"

    private val URL_TOKEN_REGEX = Regex("""https?://[^\s，。、；！？）】」"'<>]+""")
    private val NO_SCHEME_SHORTLINK_REGEX = Regex("""vanilla\.shiro\.dev/f/[^\s，。、；！？）】」"'<>]+""", RegexOption.IGNORE_CASE)
    private val STANDALONE_SHORTCODE_REGEX = Regex("""^[A-Za-z0-9_-]{6,32}$""")

    fun buildFingerprint(shortCode: String, fileName: String): String {
        return "${shortCode.trim().lowercase()}|${fileName.trim().lowercase()}"
    }

    fun extract(rawText: String?, consumedFingerprints: Set<String> = emptySet()): ExtractedShortCode? {
        return try {
            val text = rawText?.trim().orEmpty()
            if (text.isEmpty()) return null

            for (match in URL_TOKEN_REGEX.findAll(text)) {
                val token = trimTrailingPunctuation(match.value)
                val parsed = GameSourceResolver.parseShortLinkUrl(token)
                    ?: parseFromOfficialQueryOrFragment(token)
                if (parsed != null) {
                    return acceptIfFresh(parsed.first, parsed.second, consumedFingerprints)
                }
            }

            val noScheme = NO_SCHEME_SHORTLINK_REGEX.find(text)?.value?.let(::trimTrailingPunctuation)
            if (!noScheme.isNullOrBlank()) {
                val parsed = GameSourceResolver.parseShortLinkUrl("https://$noScheme")
                if (parsed != null) {
                    return acceptIfFresh(parsed.first, parsed.second, consumedFingerprints)
                }
            }

            if (STANDALONE_SHORTCODE_REGEX.matches(text) &&
                text.any { it.isLetter() } &&
                text.any { it.isDigit() }
            ) {
                return acceptIfFresh(text, DEFAULT_ARCHIVE_NAME, consumedFingerprints)
            }

            null
        } catch (t: Throwable) {
            runCatching { Log.w("AICL_RELAY", "Failed extracting short code", t) }
            null
        }
    }

    private fun acceptIfFresh(
        shortCode: String,
        fileName: String,
        consumedFingerprints: Set<String>
    ): ExtractedShortCode? {
        val code = shortCode.trim()
        val name = fileName.trim().ifBlank { DEFAULT_ARCHIVE_NAME }
        if (code.isEmpty()) return null
        val fp = buildFingerprint(code, name)
        if (fp in consumedFingerprints) return null
        return ExtractedShortCode(shortCode = code, fileName = name, fingerprint = fp)
    }

    private fun trimTrailingPunctuation(token: String): String {
        return token.trimEnd(',', '.', ';', ')', ']', '"', '\'', '>', '}')
    }

    private fun parseFromOfficialQueryOrFragment(url: String): Pair<String, String>? {
        return runCatching {
            val uri = URI(url)
            val host = uri.host?.lowercase().orEmpty()
            if (!host.endsWith("aliceincradle.dev") && !host.endsWith("shiro.dev")) return null
            val params = buildList {
                uri.rawQuery?.let { addAll(it.split('&')) }
                uri.rawFragment?.substringAfter('?', "")?.takeIf { it.isNotBlank() }?.let { addAll(it.split('&')) }
            }
            var code: String? = null
            var file: String? = null
            for (pair in params) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8").lowercase()
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim()
                if (v.isBlank()) continue
                when (k) {
                    "code", "shortcode", "short_code" -> code = v
                    "file", "filename", "file_name" -> file = v
                }
            }
            if (code.isNullOrBlank()) null else (code to (file ?: DEFAULT_ARCHIVE_NAME))
        }.getOrNull()
    }
}
