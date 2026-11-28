package com.aliceincradle.launcher.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

sealed class ExternalLaunchResult {
    data object OpenedCustomTabs : ExternalLaunchResult()
    data object OpenedSystemBrowser : ExternalLaunchResult()
    data class Unavailable(val reason: String) : ExternalLaunchResult()
}

/**
 * Module A: Launches Chrome Custom Tabs with graceful fallback to system ACTION_VIEW browser.
 */
object ExternalBrowserLauncher {
    private const val TAG = "AICL_RELAY"
    private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    private const val EXTRA_CUSTOM_TABS_TITLE_VISIBILITY = "android.support.customtabs.extra.TITLE_VISIBILITY"
    private const val EXTRA_CUSTOM_TABS_URLBAR_HIDING = "android.support.customtabs.extra.ENABLE_URLBAR_HIDING"

    fun openUrl(context: Context, url: String): ExternalLaunchResult {
        val uri = try {
            Uri.parse(url)
        } catch (t: Throwable) {
            Log.e(TAG, "Invalid URL for external launch: $url", t)
            return ExternalLaunchResult.Unavailable("链接格式无效，请点击「手动填入短码」")
        }

        try {
            val customTabsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                putExtras(Bundle().apply { putBinder(EXTRA_CUSTOM_TABS_SESSION, null) })
                putExtra(EXTRA_CUSTOM_TABS_TITLE_VISIBILITY, 1)
                putExtra(EXTRA_CUSTOM_TABS_URLBAR_HIDING, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(customTabsIntent)
            return ExternalLaunchResult.OpenedCustomTabs
        } catch (t: Throwable) {
            Log.w(TAG, "Custom Tabs launch failed, falling back to system browser", t)
        }

        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            ExternalLaunchResult.OpenedSystemBrowser
        } catch (t: Throwable) {
            Log.e(TAG, "System browser launch failed for $url", t)
            ExternalLaunchResult.Unavailable("未检测到可用浏览器，请点击「手动填入短码」")
        }
    }
}
