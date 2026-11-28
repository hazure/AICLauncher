package com.aliceincradle.launcher.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

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

    @Volatile
    private var svgCompatExtension: WebExtension? = null

    private val _state = MutableStateFlow(GeckoRuntimeState())
    val state: StateFlow<GeckoRuntimeState> = _state.asStateFlow()

    fun getOrCreate(context: Context): GeckoRuntime? {
        runtime?.let { return it }
        return synchronized(lock) {
            runtime?.let { return it }
            _state.value = GeckoRuntimeState(status = GeckoRuntimeStatus.INITIALIZING)
            try {
                val contentBlocking = ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.NONE)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.NONE)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                    .strictSocialTrackingProtection(false)
                    .queryParameterStrippingEnabled(false)
                    .build()
                val settings = GeckoRuntimeSettings.Builder()
                    .contentBlocking(contentBlocking)
                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
                    .consoleOutput(true)
                    .automaticFontSizeAdjustment(false)
                    .enterpriseRootsEnabled(true)
                    .webFontsEnabled(true)
                    .locales(arrayOf("en-US", "zh-CN"))
                    .build()
                val created = GeckoRuntime.create(context.applicationContext, settings)
                runtime = created
                _state.value = GeckoRuntimeState(status = GeckoRuntimeStatus.READY)
                Log.i(TAG, "GeckoRuntime initialized (Desktop Firefox ${GeckoDesktopIdentity.GECKO_MAJOR_VERSION})")
                ensureSvgCompatExtension(created) {}
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

    fun ensureSvgCompatExtension(
        runtime: GeckoRuntime,
        onReady: () -> Unit
    ) {
        if (svgCompatExtension != null) {
            onReady()
            return
        }
        try {
            runtime.webExtensionController
                .ensureBuiltIn(
                    "resource://android/assets/extensions/aicl_svg_fix/",
                    "aicl-svg-fix@aliceincradle.launcher"
                )
                .accept(
                    { ext ->
                        if (ext != null) {
                            svgCompatExtension = ext
                        }
                        onReady()
                    },
                    { err ->
                        Log.w(TAG, "Failed installing SVG compat WebExtension", err)
                        onReady()
                    }
                )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed ensuring SVG compat WebExtension", t)
            onReady()
        }
    }
}
