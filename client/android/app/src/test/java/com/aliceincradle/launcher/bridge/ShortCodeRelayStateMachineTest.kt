package com.aliceincradle.launcher.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShortCodeRelayStateMachineTest {
    @Test
    fun onResumeClipboardText_preventsReentrancyAfterConfirmOrDismiss() {
        val sm = ShortCodeRelayStateMachine()
        val url = "https://vanilla.shiro.dev/f/abc123/AliceInCradle_Win_Latest.7z"

        val first = sm.onResumeClipboardText(url)
        assertNotNull(first)
        assertEquals(RelayPhase.CANDIDATE_DETECTED, sm.state.value.phase)

        val confirmed = sm.confirmCandidate()
        assertNotNull(confirmed)
        assertEquals(RelayPhase.COMPLETED, sm.state.value.phase)

        assertNull(sm.confirmCandidate())
        assertNull(sm.onResumeClipboardText(url))
    }

    @Test
    fun dismissCandidate_and_markDisposed_blockSubsequentTriggers() {
        val sm = ShortCodeRelayStateMachine()
        val url = "https://vanilla.shiro.dev/f/xyz789/game.7z"

        assertNotNull(sm.onResumeClipboardText(url))
        sm.dismissCandidate()
        assertEquals(RelayPhase.IDLE, sm.state.value.phase)
        assertNull(sm.onResumeClipboardText(url))

        sm.markDisposed()
        assertEquals(RelayPhase.DISMISSED, sm.state.value.phase)
        assertNull(sm.onResumeClipboardText("https://vanilla.shiro.dev/f/new999/game.7z"))
        assertNull(sm.confirmManualInput("new999"))
    }

    @Test
    fun confirmManualInput_worksAndLocksState() {
        val sm = ShortCodeRelayStateMachine()
        val manual = sm.confirmManualInput("man12345")
        assertNotNull(manual)
        assertEquals("man12345", manual?.shortCode)
        assertEquals(RelayPhase.COMPLETED, sm.state.value.phase)
        assertNull(sm.confirmManualInput("man67890"))
    }
}
