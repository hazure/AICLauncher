package com.aliceincradle.launcher.bridge

import com.aliceincradle.launcher.data.HttpErrorAction
import com.aliceincradle.launcher.data.HttpErrorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResolveTighteningTest {

    @Test
    fun identicalFingerprintStopsAnd403TriggersSingleReResolve() {
        val oldUrl = "https://dl.aliceincradle.dev/vanilla/a.7z?x-oss-signature=same"
        val newUrl = "https://dl.aliceincradle.dev/vanilla/a.7z?x-oss-signature=fresh"
        assertEquals(
            GameSourceResolver.urlFingerprint(oldUrl),
            GameSourceResolver.urlFingerprint(oldUrl)
        )
        assertNotEquals(
            GameSourceResolver.urlFingerprint(oldUrl),
            GameSourceResolver.urlFingerprint(newUrl)
        )
        assertEquals(HttpErrorAction.RE_RESOLVE_LINK, HttpErrorPolicy.classifyHttpCode(403))
        assertEquals(HttpErrorAction.RE_RESOLVE_LINK, HttpErrorPolicy.classifyHttpCode(404))
    }
}
