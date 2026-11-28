package com.aliceincradle.launcher.data

import java.io.IOException
import java.net.SocketTimeoutException

enum class HttpErrorAction {
    RE_RESOLVE_LINK,
    RETRY_WITH_BACKOFF,
    FAIL_FAST
}

/**
 * Centralized HTTP error classification & exponential backoff policy (Decision D6).
 * 401/403/404 never retry and trigger a single link re-resolution;
 * 408/429/5xx/IOException retry with exponential backoff up to [MAX_TRANSIENT_RETRIES].
 */
object HttpErrorPolicy {
    const val MAX_TRANSIENT_RETRIES = 3
    val BACKOFF_DELAYS_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L)

    fun classifyHttpCode(code: Int): HttpErrorAction = when {
        code == 401 || code == 403 || code == 404 -> HttpErrorAction.RE_RESOLVE_LINK
        code == 408 || code == 429 || code in 500..599 -> HttpErrorAction.RETRY_WITH_BACKOFF
        else -> HttpErrorAction.FAIL_FAST
    }

    fun classifyException(error: Throwable): HttpErrorAction = when (error) {
        is SocketTimeoutException, is IOException -> HttpErrorAction.RETRY_WITH_BACKOFF
        else -> HttpErrorAction.FAIL_FAST
    }
}
