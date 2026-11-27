package com.aliceincradle.launcher.data

import android.content.Context
import android.util.Log

/**
 * Persists first-launch legal disclaimer agreement state in private SharedPreferences.
 */
class AgreementManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAgreed(): Boolean {
        return try {
            prefs.getBoolean(KEY_AGREED, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read agreement state", e)
            false
        }
    }

    fun setAgreed(agreed: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_AGREED, agreed).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist agreement state", e)
        }
    }

    private companion object {
        const val TAG = "AgreementManager"
        const val PREFS_NAME = "aicl_agreement_prefs"
        const val KEY_AGREED = "has_agreed_disclaimer_v1"
    }
}
