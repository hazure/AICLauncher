package com.aliceincradle.launcher

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsPrivacyAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class X5InitStatus {
    PENDING,
    READY_X5,
    FALLBACK_SYSTEM
}

object X5EngineState {
    private val _status = MutableStateFlow(X5InitStatus.PENDING)
    val status: StateFlow<X5InitStatus> = _status.asStateFlow()

    internal fun update(next: X5InitStatus) {
        _status.value = next
    }
}

class AiclApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Handler(Looper.getMainLooper()).post {
            try {
                runCatching { TbsPrivacyAccess.disableSensitiveApi() }
                runCatching { QbSdk.setDownloadWithoutWifi(true) }
                QbSdk.initX5Environment(
                    applicationContext,
                    object : QbSdk.PreInitCallback {
                        override fun onCoreInitFinished() {
                            Log.i("AICL_X5", "X5 core init finished")
                        }

                        override fun onViewInitFinished(isX5Core: Boolean) {
                            val next = if (isX5Core) {
                                X5InitStatus.READY_X5
                            } else {
                                X5InitStatus.FALLBACK_SYSTEM
                            }
                            X5EngineState.update(next)
                            if (isX5Core) {
                                Log.i("AICL_X5", "X5 WebView core ready")
                            } else {
                                Log.w("AICL_X5", "X5 unavailable, falling back to system WebView")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.e("AICL_X5", "X5 init crashed, forcing system WebView fallback", t)
                X5EngineState.update(X5InitStatus.FALLBACK_SYSTEM)
            }
        }
    }
}
