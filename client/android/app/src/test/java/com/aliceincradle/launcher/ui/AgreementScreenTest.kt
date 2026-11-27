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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

        composeTestRule.onNodeWithText("AICL 使用协议与免责声明").assertIsDisplayed()
        composeTestRule.onNodeWithText("0. 此项目初始化需要联网，会收集<...>以上信息，用于检查<...>操作。").assertIsDisplayed()
        composeTestRule.onNodeWithText("1. 不帮助未成年人。").assertIsDisplayed()
        composeTestRule.onNodeWithText("2. 不允许未成年人接触此软件。").assertIsDisplayed()
        composeTestRule.onNodeWithText("3. 此项目仅供学习，下载后请在24小时内自行删除！").assertIsDisplayed()
        composeTestRule.onNodeWithText("4. 与 AIC 官方无关，绝不接受任何来自 AICL 以外 Issue。").assertIsDisplayed()
        composeTestRule.onNodeWithText("5. 请勿骚扰 AIC 官方，一旦发现，本项目将封禁此用户使用本软件。").assertIsDisplayed()
        composeTestRule.onNodeWithText("我已阅读并同意上述声明与风险").assertIsDisplayed()
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
