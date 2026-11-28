package com.aliceincradle.launcher.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

enum class GeckoRuntimeStatus {
    IDLE,
    INITIALIZING,
    READY,
    FAILED
}

data class GeckoRuntimeState(
    val status: GeckoRuntimeStatus = GeckoRuntimeStatus.IDLE,
    val errorMessage: String? = null
)

/**
 * Process-wide GeckoRuntime singleton with atomic initialization guard and StateFlow status.
 */
object GeckoRuntimeManager {
    private const val TAG = "AICL_GECKO"
    private val lock = Any()

    @Volatile
    private var runtime: GeckoRuntime? = null

    private val _state = MutableStateFlow(GeckoRuntimeState())
    val state: StateFlow<GeckoRuntimeState> = _state.asStateFlow()

    fun getOrCreate(context: Context): GeckoRuntime? {
        runtime?.let { return it }
        return synchronized(lock) {
            runtime?.let { return it }
            _state.value = GeckoRuntimeState(status = GeckoRuntimeStatus.INITIALIZING)
            try {
                val settings = GeckoRuntimeSettings.Builder()
                    .consoleOutput(true)
                    .automaticFontSizeAdjustment(false)
                    .locales(arrayOf("en-US", "zh-CN"))
                    .build()
                val created = GeckoRuntime.create(context.applicationContext, settings)
                runtime = created
                _state.value = GeckoRuntimeState(status = GeckoRuntimeStatus.READY)
                Log.i(TAG, "GeckoRuntime initialized (Desktop Firefox ${GeckoDesktopIdentity.GECKO_MAJOR_VERSION})")
                created
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize GeckoRuntime", t)
                _state.value = GeckoRuntimeState(
                    status = GeckoRuntimeStatus.FAILED,
                    errorMessage = t.message ?: "GeckoRuntime 初始化失败"
                )
                null
            }
        }
    }
}
