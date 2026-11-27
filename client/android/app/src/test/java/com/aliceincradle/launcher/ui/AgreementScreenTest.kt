package com.aliceincradle.launcher.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliceincradle.launcher.data.AgreementManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34])
class AgreementScreenTest {

    @Test
    fun agreementPersistsAcrossManagerInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstManager = AgreementManager(context)
        assertFalse("Initial agreement state must be false", firstManager.isAgreed.first())

        firstManager.saveAgreed()
        assertTrue("Flow must emit true after saveAgreed()", firstManager.isAgreed.first())

        val secondManager = AgreementManager(context)
        assertTrue("Agreement state must persist across restarts", secondManager.isAgreed.first())
    }
}
