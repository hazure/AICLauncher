package com.aliceincradle.launcher.ui

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(shadows = [Arm64BitmapShadow::class, Arm64PaintShadow::class, Arm64CanvasShadow::class, Arm64AndroidComposeViewShadow::class])
class DownloadScreenScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
    fun downloadTabRendersFiltersScrollbarAndFollowsScrollOn360dp() {
        val viewModel = createScreenshotViewModel()
        lateinit var hostView: View

        composeTestRule.setContent {
            hostView = configureSoftwareHostView(LocalView.current)
            TestCjkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("下载").performClick()
        composeTestRule.waitForIdle()

        assertCommonDownloadElementsDisplayed()
        composeTestRule.onNodeWithTag("download_vertical_scrollbar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("download_scrollbar_thumb").assertIsDisplayed()

        val thumbTopBefore = composeTestRule
            .onNodeWithTag("download_scrollbar_thumb")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        captureSoftwareScreenshot(hostView, "download_360dp.png")

        composeTestRule.onNodeWithTag("download_lazy_column").performScrollToIndex(5)
        composeTestRule.waitForIdle()

        val thumbTopAfter = composeTestRule
            .onNodeWithTag("download_scrollbar_thumb")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertTrue(
            "Vertical scrollbar thumb must move down on scroll: before=$thumbTopBefore, after=$thumbTopAfter",
            thumbTopAfter > thumbTopBefore
        )

        captureSoftwareScreenshot(hostView, "download_360dp_scrolled.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
    fun downloadTabFiltersItemsAndCaptures412dpScreenshot() {
        val viewModel = createScreenshotViewModel()
        lateinit var hostView: View

        composeTestRule.setContent {
            hostView = configureSoftwareHostView(LocalView.current)
            TestCjkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("下载").performClick()
        composeTestRule.waitForIdle()

        assertCommonDownloadElementsDisplayed()
        captureSoftwareScreenshot(hostView, "download_412dp.png")

        composeTestRule.onNodeWithTag("download_search_field").performTextInput("Legacy")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Legacy Relic Pack · 0.24").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice In Cradle · 0.29d").assertDoesNotExist()
    }

    private fun assertCommonDownloadElementsDisplayed() {
        composeTestRule.onNodeWithText("按名称 / ID / 作者等搜索...").assertIsDisplayed()
        composeTestRule.onNodeWithText("AIC").assertIsDisplayed()
        composeTestRule.onNodeWithTag("download_app_logo", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("稳定版").assertIsDisplayed()
        composeTestRule.onNodeWithText("武器强化").assertIsDisplayed()
        composeTestRule.onNodeWithText("包含已兼容版本的 Mod").assertIsDisplayed()
        composeTestRule.onNodeWithText("包含精品 Mod").assertIsDisplayed()
        composeTestRule.onNodeWithText("共 23333 个内容，耗时 0.01s").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice In Cradle · 0.29d").assertIsDisplayed()
        composeTestRule.onNodeWithText("游戏本体 · 官方 · 稳定版").assertIsDisplayed()
        composeTestRule.onNodeWithText("约4.9GB").assertIsDisplayed()
        composeTestRule.onNodeWithTag("status_icon_aic-core-0.29d").assertIsDisplayed()
        composeTestRule.onNodeWithTag("status_icon_mod-weapon-mastery").assertIsDisplayed()
        composeTestRule.onNodeWithTag("status_icon_mod-legacy-relic").assertIsDisplayed()
    }
}
