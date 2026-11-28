package com.aliceincradle.launcher

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsListener
import com.tencent.smtt.sdk.TbsPrivacyAccess
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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

    companion object {
        private const val OFFLINE_X5_ASSET_NAME = "tbs_core_046285_arm64_v8a.tbs.apk"
        private const val OFFLINE_X5_CORE_VERSION = 46285
    }

    override fun onCreate() {
        super.onCreate()
        installCrashSummaryHandler()

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

                val localInstallPending = AtomicBoolean(false)
                lateinit var preInitCallback: QbSdk.PreInitCallback
                preInitCallback = object : QbSdk.PreInitCallback {
                    override fun onCoreInitFinished() {
                        Log.i("AICL_X5", "X5 core init finished")
                    }

                    override fun onViewInitFinished(isX5Core: Boolean) {
                        val canLoad = runCatching { QbSdk.canLoadX5(applicationContext) }.getOrDefault(false)
                        val ver = runCatching { QbSdk.getTbsVersion(applicationContext) }.getOrDefault(0)
                        if (isX5Core || canLoad) {
                            X5EngineState.update(X5InitStatus.READY_X5)
                            Log.i("AICL_X5", "X5 init SUCCESS: X5 WebView core ready (isX5Core=$isX5Core, tbsVersion=$ver)")
                        } else if (!localInstallPending.get()) {
                            X5EngineState.update(X5InitStatus.FALLBACK_SYSTEM)
                            Log.w("AICL_X5", "X5 init FALLBACK: X5 unavailable (tbsVersion=$ver), switched to system WebView")
                        } else {
                            Log.i("AICL_X5", "X5 init waiting for offline core install completion (tbsVersion=$ver)")
                        }
                    }
                }

                QbSdk.setTbsListener(object : TbsListener {
                    override fun onDownloadFinish(code: Int) {
                        Log.i("AICL_X5", "X5 onDownloadFinish: code=$code")
                    }

                    override fun onDownloadProgress(progress: Int) {
                        Log.i("AICL_X5", "X5 onDownloadProgress: $progress%")
                    }

                    override fun onInstallFinish(code: Int) {
                        localInstallPending.set(false)
                        val canLoad = runCatching { QbSdk.canLoadX5(applicationContext) }.getOrDefault(false)
                        val ver = runCatching { QbSdk.getTbsVersion(applicationContext) }.getOrDefault(0)
                        Log.i("AICL_X5", "X5 onInstallFinish: code=$code, canLoadX5=$canLoad, tbsVersion=$ver")
                        if (canLoad || code == 200 || code == 232) {
                            if (canLoad) {
                                X5EngineState.update(X5InitStatus.READY_X5)
                            }
                            runCatching { QbSdk.preInit(applicationContext, preInitCallback) }
                                .onFailure { Log.w("AICL_X5", "QbSdk.preInit after local install failed", it) }
                        } else {
                            X5EngineState.update(X5InitStatus.FALLBACK_SYSTEM)
                        }
                    }
                })

                val installedVer = runCatching { QbSdk.getTbsVersion(applicationContext) }.getOrDefault(0)
                val alreadyCanLoad = runCatching { QbSdk.canLoadX5(applicationContext) }.getOrDefault(false)
                if (!alreadyCanLoad && installedVer < OFFLINE_X5_CORE_VERSION) {
                    val localCoreFile = prepareOfflineCoreFile()
                    if (localCoreFile != null && localCoreFile.length() > 0L) {
                        localInstallPending.set(true)
                        QbSdk.installLocalTbsCore(
                            applicationContext,
                            OFFLINE_X5_CORE_VERSION,
                            localCoreFile.absolutePath
                        )
                        Log.i("AICL_X5", "X5 local core install triggered: version=$OFFLINE_X5_CORE_VERSION, size=${localCoreFile.length()}")
                    }
                }

                QbSdk.initX5Environment(applicationContext, preInitCallback)
            } catch (t: Throwable) {
                Log.e("AICL_X5", "X5 init FALLBACK: exception during async init", t)
                runCatching { QbSdk.forceSysWebView() }
                    .onFailure { Log.w("AICL_X5", "QbSdk.forceSysWebView failed during fallback", it) }
                X5EngineState.update(X5InitStatus.FALLBACK_SYSTEM)
            }
        }
    }

    private fun prepareOfflineCoreFile(): File? {
        return try {
            val coreDir = File(applicationContext.filesDir, "x5_core")
            if (!coreDir.exists()) {
                coreDir.mkdirs()
            }
            val target = File(coreDir, OFFLINE_X5_ASSET_NAME)
            if (target.isFile && target.length() > 0L) {
                return target
            }
            val tmp = File(coreDir, "$OFFLINE_X5_ASSET_NAME.tmp")
            applicationContext.assets.open(OFFLINE_X5_ASSET_NAME).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (t: Throwable) {
            Log.e("AICL_X5", "Failed preparing offline X5 core from assets", t)
            null
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
