package com.aliceincradle.launcher

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.tencent.smtt.export.external.TbsCoreSettings
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

    internal fun markRuntimeFallback() {
        _status.value = X5InitStatus.FALLBACK_SYSTEM
    }
}

class AiclApplication : Application() {
    private val x5InitThread by lazy {
        HandlerThread("aicl-x5-init").apply {
            isDaemon = true
            start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Handler(x5InitThread.looper).post {
            try {
                Log.i("AICL_X5", "X5 async init started")
                runCatching { TbsPrivacyAccess.disableSensitiveApi() }
                try {
                    val setAgreementMethod = QbSdk::class.java.getMethod(
                        "setUserAgreement",
                        Context::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                    setAgreementMethod.invoke(null, applicationContext, true)
                    Log.i("AICL_X5", "QbSdk.setUserAgreement(true) invoked")
                } catch (e: NoSuchMethodException) {
                    Log.i("AICL_X5", "QbSdk.setUserAgreement unavailable in tbssdk:44286; handled via TbsPrivacyAccess", e)
                } catch (t: Throwable) {
                    Log.w("AICL_X5", "QbSdk.setUserAgreement reflection failed; continuing with TbsPrivacyAccess", t)
                }
                runCatching {
                    QbSdk.initTbsSettings(
                        mapOf(
                            TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER to true,
                            TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE to true
                        )
                    )
                }
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
                                Log.i("AICL_X5", "X5 init SUCCESS: X5 WebView core ready")
                            } else {
                                Log.w("AICL_X5", "X5 init FALLBACK: X5 unavailable, switched to system WebView")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.e("AICL_X5", "X5 init FALLBACK: exception during async init", t)
                runCatching { QbSdk.forceSysWebView() }
                X5EngineState.update(X5InitStatus.FALLBACK_SYSTEM)
            }
        }
    }
}
