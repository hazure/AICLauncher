package com.aliceincradle.launcher

import android.app.Application
import android.util.Log
import com.tencent.smtt.sdk.QbSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            try {
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
            } catch (e: Exception) {
                Log.e("AICL_X5", "Failed to initialize X5 environment", e)
                X5EngineState.update(X5InitStatus.FALLBACK_SYSTEM)
            }
        }
    }
}
