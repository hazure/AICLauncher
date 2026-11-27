package com.aliceincradle.launcher.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists first-launch legal disclaimer agreement state in private SharedPreferences
 * and exposes it as a reactive [Flow].
 */
class AgreementManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _isAgreed = MutableStateFlow(readAgreed())

    val isAgreed: Flow<Boolean> = _isAgreed.asStateFlow()

    fun isAgreedSync(): Boolean = _isAgreed.value

    fun saveAgreed() {
        try {
            prefs.edit().putBoolean(KEY_AGREED, true).apply()
            _isAgreed.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist agreement state", e)
        }
    }

    private fun readAgreed(): Boolean {
        return try {
            prefs.getBoolean(KEY_AGREED, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read agreement state", e)
            false
        }
    }

    private companion object {
        const val TAG = "AgreementManager"
        const val PREFS_NAME = "aicl_agreement_prefs"
        const val KEY_AGREED = "has_agreed_disclaimer_v1"
    }
}
