package com.aliceincradle.launcher.bridge

import com.aliceincradle.launcher.data.HttpErrorAction
import com.aliceincradle.launcher.data.HttpErrorPolicy
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpErrorPolicyTest {

    @Test
    fun classifies4xxAsReResolveAnd5xxAsBackoff() {
        assertEquals(HttpErrorAction.RE_RESOLVE_LINK, HttpErrorPolicy.classifyHttpCode(401))
        assertEquals(HttpErrorAction.RE_RESOLVE_LINK, HttpErrorPolicy.classifyHttpCode(403))
        assertEquals(HttpErrorAction.RE_RESOLVE_LINK, HttpErrorPolicy.classifyHttpCode(404))

        assertEquals(HttpErrorAction.RETRY_WITH_BACKOFF, HttpErrorPolicy.classifyHttpCode(408))
        assertEquals(HttpErrorAction.RETRY_WITH_BACKOFF, HttpErrorPolicy.classifyHttpCode(429))
        assertEquals(HttpErrorAction.RETRY_WITH_BACKOFF, HttpErrorPolicy.classifyHttpCode(503))

        assertEquals(HttpErrorAction.RETRY_WITH_BACKOFF, HttpErrorPolicy.classifyException(IOException("reset")))
        assertEquals(HttpErrorAction.RETRY_WITH_BACKOFF, HttpErrorPolicy.classifyException(SocketTimeoutException("timeout")))
        assertEquals(3, HttpErrorPolicy.MAX_TRANSIENT_RETRIES)
    }
}
