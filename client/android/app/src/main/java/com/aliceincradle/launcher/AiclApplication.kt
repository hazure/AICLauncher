package com.aliceincradle.launcher

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsPrivacyAccess
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        installCrashSummaryHandler()

        if (!hasBundledX5NativeLibrary()) {
            runCatching { QbSdk.forceSysWebView() }
                .onFailure { Log.w("AICL_X5", "QbSdk.forceSysWebView failed", it) }
            X5EngineState.update(X5InitStatus.FALLBACK_SYSTEM)
            Log.i("AICL_X5", "X5 init FALLBACK: no bundled X5 native library detected, using system WebView")
            return
        }

        Handler(x5InitThread.looper).post {
            try {
                Log.i("AICL_X5", "X5 async init started")
                runCatching { TbsPrivacyAccess.disableSensitiveApi() }
                    .onFailure { Log.w("AICL_X5", "TbsPrivacyAccess.disableSensitiveApi failed", it) }
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
                            TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER to true
                        )
                    )
                }.onFailure { Log.w("AICL_X5", "QbSdk.initTbsSettings failed", it) }
                runCatching { QbSdk.setDownloadWithoutWifi(true) }
                    .onFailure { Log.w("AICL_X5", "QbSdk.setDownloadWithoutWifi failed", it) }
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
                    .onFailure { Log.w("AICL_X5", "QbSdk.forceSysWebView failed during fallback", it) }
                X5EngineState.update(X5InitStatus.FALLBACK_SYSTEM)
            }
        }
    }

    private fun hasBundledX5NativeLibrary(): Boolean {
        return try {
            val nativeDir = applicationContext.applicationInfo?.nativeLibraryDir ?: return false
            val files = File(nativeDir).listFiles() ?: return false
            files.any { file ->
                val name = file.name.lowercase(Locale.US)
                name.endsWith(".so") && (name.contains("tbs") || name.contains("x5"))
            }
        } catch (t: Throwable) {
            Log.w("AICL_X5", "Failed probing nativeLibraryDir for X5 .so", t)
            false
        }
    }

    private fun installCrashSummaryHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (thread.name.contains("tbs", ignoreCase = true) ||
                    thread.name.contains("x5", ignoreCase = true)
                ) {
                    X5EngineState.markRuntimeFallback()
                }
                val diagDir = File(applicationContext.filesDir, "diagnostics")
                if (!diagDir.exists()) {
                    diagDir.mkdirs()
                }
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                val topFrames = throwable.stackTrace.take(15).joinToString("\n") { "  at $it" }
                val causeLine = throwable.cause?.let { c ->
                    "Cause: ${c.javaClass.name}: ${c.message.orEmpty()}"
                } ?: "Cause: none"
                val summary = buildString {
                    appendLine("Timestamp: $timestamp")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
                    appendLine(causeLine)
                    appendLine("StackTrace (top 15):")
                    appendLine(topFrames)
                }
                File(diagDir, "last_crash_summary.txt").writeText(summary, Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.e("AICL_CRASH", "Failed to persist crash summary", t)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
