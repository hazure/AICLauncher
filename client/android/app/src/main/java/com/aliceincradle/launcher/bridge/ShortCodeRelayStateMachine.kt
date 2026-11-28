package com.aliceincradle.launcher.bridge

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RelayPhase {
    IDLE,
    AWAITING_RETURN,
    CANDIDATE_DETECTED,
    COMPLETED,
    DISMISSED
}

data class ShortCodeRelayUiState(
    val phase: RelayPhase = RelayPhase.IDLE,
    val launchStatus: String = "请通过外部浏览器完成官方下载页验证后复制链接或短码。",
    val candidate: ExtractedShortCode? = null,
    val sessionToken: Int = 0
)

/**
 * StateFlow-backed re-entrancy state machine for external browser relay & ON_RESUME clipboard capture.
 */
class ShortCodeRelayStateMachine(
    initialConsumed: Set<String> = emptySet()
) {
    private val lock = Any()
    private val consumedFingerprints = initialConsumed.toMutableSet()
    private val completed = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    private val _state = MutableStateFlow(ShortCodeRelayUiState())
    val state: StateFlow<ShortCodeRelayUiState> = _state.asStateFlow()

    fun snapshotConsumed(): Set<String> = synchronized(lock) { consumedFingerprints.toSet() }

    fun onExternalLaunch(result: ExternalLaunchResult) {
        if (disposed.get() || completed.get()) return
        val statusText = when (result) {
            is ExternalLaunchResult.OpenedCustomTabs ->
                "已拉起 Custom Tabs，完成验证并复制后切回 AICL。"
            is ExternalLaunchResult.OpenedSystemBrowser ->
                "已拉起系统浏览器，完成验证并复制后切回 AICL。"
            is ExternalLaunchResult.Unavailable ->
                result.reason
        }
        _state.update { current ->
            if (current.phase == RelayPhase.COMPLETED || current.phase == RelayPhase.DISMISSED) {
                current
            } else {
                current.copy(
                    phase = RelayPhase.AWAITING_RETURN,
                    launchStatus = statusText
                )
            }
        }
    }

    fun onResumeClipboardText(rawText: String?): ExtractedShortCode? {
        if (disposed.get() || completed.get()) return null
        val extracted = ShortCodeRelayExtractor.extract(
            rawText = rawText,
            consumedFingerprints = snapshotConsumed()
        ) ?: return null
        val current = _state.value
        if (current.candidate?.fingerprint == extracted.fingerprint &&
            current.phase == RelayPhase.CANDIDATE_DETECTED
        ) {
            return extracted
        }
        _state.update { state ->
            if (disposed.get() || completed.get()) {
                state
            } else {
                state.copy(
                    phase = RelayPhase.CANDIDATE_DETECTED,
                    candidate = extracted
                )
            }
        }
        return extracted
    }

    fun confirmCandidate(): ExtractedShortCode? {
        val candidate = _state.value.candidate ?: return null
        if (disposed.get() || !completed.compareAndSet(false, true)) return null
        synchronized(lock) {
            consumedFingerprints.add(candidate.fingerprint)
        }
        _state.update { state ->
            state.copy(
                phase = RelayPhase.COMPLETED,
                sessionToken = state.sessionToken + 1
            )
        }
        return candidate
    }

    fun confirmManualInput(
        shortCode: String,
        fileName: String = ShortCodeRelayExtractor.DEFAULT_ARCHIVE_NAME
    ): ExtractedShortCode? {
        val code = shortCode.trim()
        if (code.isEmpty()) return null
        if (disposed.get() || !completed.compareAndSet(false, true)) return null
        val cleanName = fileName.trim().ifBlank { ShortCodeRelayExtractor.DEFAULT_ARCHIVE_NAME }
        val fp = ShortCodeRelayExtractor.buildFingerprint(code, cleanName)
        synchronized(lock) {
            consumedFingerprints.add(fp)
        }
        val manual = ExtractedShortCode(shortCode = code, fileName = cleanName, fingerprint = fp)
        _state.update { state ->
            state.copy(
                phase = RelayPhase.COMPLETED,
                candidate = manual,
                sessionToken = state.sessionToken + 1
            )
        }
        return manual
    }

    fun dismissCandidate() {
        val candidate = _state.value.candidate ?: return
        synchronized(lock) {
            consumedFingerprints.add(candidate.fingerprint)
        }
        _state.update { state ->
            if (state.phase == RelayPhase.COMPLETED || state.phase == RelayPhase.DISMISSED) {
                state
            } else {
                state.copy(
                    phase = RelayPhase.IDLE,
                    candidate = null
                )
            }
        }
    }

    fun markDisposed() {
        disposed.set(true)
        _state.update { state ->
            state.copy(
                phase = RelayPhase.DISMISSED,
                sessionToken = state.sessionToken + 1
            )
        }
    }
}
