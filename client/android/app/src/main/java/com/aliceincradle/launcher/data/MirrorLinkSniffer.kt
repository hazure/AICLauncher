package com.aliceincradle.launcher.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun interface MirrorHttpClient {
    fun fetchText(url: String): String
}

class DefaultMirrorHttpClient : MirrorHttpClient {
    override fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", MirrorLinkSniffer.CHROME_USER_AGENT)
            setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code from $url")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 12_000
    }
}

data class SniffedDownloadLink(
    val directUrl: String,
    val fileName: String,
    val versionHint: String? = null
)

/**
 * Dynamically sniffs the latest Windows archive direct download link from the official mirror.
 * Never hardcodes archive URLs; falls back cleanly to Result.failure when parsing fails.
 */
open class MirrorLinkSniffer(
    private val httpClient: MirrorHttpClient = DefaultMirrorHttpClient()
) {
    open fun sniffLatestWinLink(): Result<SniffedDownloadLink> {
        return try {
            val html = httpClient.fetchText(MIRROR_WIN_URL)
            extractArchiveUrl(html)?.let { directUrl ->
                val resolved = buildSniffedLink(directUrl, html)
                logResolvedUrl(resolved.directUrl)
                return Result.success(resolved)
            }

            val jsonUrls = JSON_URL_REGEX.findAll(html)
                .map { it.value }
                .distinct()
                .take(3)
                .toList()
            for (jsonUrl in jsonUrls) {
                val jsonText = runCatching { httpClient.fetchText(jsonUrl) }.getOrNull() ?: continue
                extractArchiveUrl(jsonText)?.let { directUrl ->
                    val resolved = buildSniffedLink(directUrl, jsonText)
                    logResolvedUrl(resolved.directUrl)
                    return Result.success(resolved)
                }
            }

            Result.failure(IOException("未能从官方镜像站解析出有效下载直链"))
        } catch (e: Exception) {
            SafeProbeIo.warn(TAG, "Mirror link sniff failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun extractArchiveUrl(text: String): String? {
        return ARCHIVE_URL_REGEX.find(text)?.value
    }

    private fun buildSniffedLink(directUrl: String, sourceText: String): SniffedDownloadLink {
        val rawName = directUrl.substringAfterLast('/').substringBefore('?')
        val fileName = if (rawName.endsWith(".zip", ignoreCase = true) ||
            rawName.endsWith(".7z", ignoreCase = true)
        ) {
            rawName
        } else {
            "AliceInCradle_Win_Latest.zip"
        }
        val versionHint = VERSION_REGEX.find(sourceText)?.groupValues?.getOrNull(1)
        return SniffedDownloadLink(
            directUrl = directUrl,
            fileName = fileName,
            versionHint = versionHint
        )
    }

    private fun logResolvedUrl(url: String) {
        runCatching { Log.i(TAG, "Resolved direct download URL: $url") }
            .onFailure { println("[$TAG] Resolved direct download URL: $url") }
    }

    companion object {
        private const val TAG = "MirrorLinkSniffer"
        const val MIRROR_WIN_URL = "https://get.aliceincradle.dev/win/"
        const val CIEN_FALLBACK_URL = "https://ci-en.dlsite.com/creator/12611"
        const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private val ARCHIVE_URL_REGEX = Regex(
            """https?://[^\s"'<>\\]+?\.(?:zip|7z)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val JSON_URL_REGEX = Regex(
            """https?://[^\s"'<>\\]+?\.json(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val VERSION_REGEX = Regex("""(0\.\d+\.\d+[a-z0-9.\-]*)""", RegexOption.IGNORE_CASE)
    }
}
