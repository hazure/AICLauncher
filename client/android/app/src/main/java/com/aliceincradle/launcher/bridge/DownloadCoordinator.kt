package com.aliceincradle.launcher.bridge

import android.util.Log

sealed class ResolveOutcome {
    data class Ready(
        val source: GameSource,
        val fp: String,
        val isManualShortCode: Boolean
    ) : ResolveOutcome()

    data class NeedCapture(
        val reason: String = "SHORTCODE_MISSING"
    ) : ResolveOutcome()

    data class Failed(
        val code: String,
        val message: String
    ) : ResolveOutcome()
}

/**
 * Single entry point for official game acquisition (T36_hotfix H1/H2).
 * Never falls back to legacy catalog download_url.
 */
class DownloadCoordinator(
    private val resolver: GameSourceResolver,
    private val shortCodeStore: ShortCodeCacheStore,
    private val pipelineLogger: (String) -> Unit = { msg -> runCatching { Log.i("AICL_PIPELINE", msg) } }
) {
    suspend fun start(
        expectedVersion: String? = null,
        oldFp: String? = null
    ): ResolveOutcome {
        val cached = shortCodeStore.load()
        val kindLabel = when {
            cached == null -> "?"
            cached.isManual -> "manual_shortcode"
            else -> "shortcode_refresh"
        }
        pipelineLogger("AICL_PIPELINE phase=RESOLVE_ENTER sourceKind=$kindLabel cacheHit=${cached != null}")

        if (cached == null ||
            (!expectedVersion.isNullOrBlank() && !cached.version.isNullOrBlank() && cached.version != expectedVersion)
        ) {
            pipelineLogger("AICL_PIPELINE phase=RESOLVE_EXIT status=SHORTCODE_MISSING sourceKind=none")
            return ResolveOutcome.NeedCapture("SHORTCODE_MISSING")
        }

        val sourceKind = if (cached.isManual) SourceKind.MANUAL_SHORTCODE else SourceKind.SHORTCODE_REFRESH
        val result = resolver.resolveFromShortCode(
            shortCode = cached.shortCode,
            fileName = cached.fileName,
            version = cached.version,
            sourceKind = sourceKind,
            oldFp = oldFp
        )

        return result.fold(
            onSuccess = { source ->
                val fp = GameSourceResolver.urlFingerprint(source.url)
                ResolveOutcome.Ready(
                    source = source,
                    fp = fp,
                    isManualShortCode = cached.isManual
                )
            },
            onFailure = { err ->
                ResolveOutcome.Failed(
                    code = "LINK_UNRESOLVABLE",
                    message = err.message ?: "取链失败"
                )
            }
        )
    }

    fun saveShortCode(
        shortCode: String,
        fileName: String,
        version: String? = null,
        isManual: Boolean = false
    ): Result<Unit> {
        return shortCodeStore.save(
            CachedShortCode(
                shortCode = shortCode.trim(),
                fileName = fileName.trim(),
                version = version?.trim()?.takeIf { it.isNotEmpty() },
                isManual = isManual
            )
        )
    }

    fun loadCachedShortCode(): CachedShortCode? = shortCodeStore.load()
}
