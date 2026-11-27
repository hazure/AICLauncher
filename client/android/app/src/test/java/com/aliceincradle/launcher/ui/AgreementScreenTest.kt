package com.aliceincradle.launcher.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliceincradle.launcher.data.AgreementManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], shadows = [Arm64BitmapShadow::class, Arm64PaintShadow::class, Arm64CanvasShadow::class, Arm64AndroidComposeViewShadow::class])
class AgreementScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUpAntiAliasing() {
        configureAntiAliasingRenderingHints()
    }

    @Test
    fun firstLaunchShowsAgreementAndDisablesAgreeUntilChecked() {
        var agreed = false
        var exited = false

        composeTestRule.setContent {
            TestCjkTheme {
                AgreementScreen(
                    onAgree = { agreed = true },
                    onExit = { exited = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("免责声明与用户协议").assertIsDisplayed()
        composeTestRule.onNodeWithText("我已年满18岁或具备独立操作能力，并已仔细阅读上述声明").assertIsDisplayed()
        composeTestRule.onNodeWithTag("agreement_agree_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("agreement_exit_button").assertIsEnabled()

        composeTestRule.onNodeWithTag("agreement_checkbox").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("agreement_agree_button").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()

        assertTrue("onAgree should be invoked after checking box and clicking Agree", agreed)
        assertFalse("onExit should not be invoked", exited)
    }

    @Test
    fun agreementPersistsAcrossManagerInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstManager = AgreementManager(context)
        assertFalse("Initial agreement state must be false", firstManager.isAgreed())

        firstManager.setAgreed(true)
        val secondManager = AgreementManager(context)
        assertTrue("Agreement state must persist across restarts", secondManager.isAgreed())
    }
}
