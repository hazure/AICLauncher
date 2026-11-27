package com.aliceincradle.launcher.ui

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
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
@Config(shadows = [Arm64BitmapShadow::class, Arm64PaintShadow::class, Arm64CanvasShadow::class, Arm64AndroidComposeViewShadow::class])
class FolderAndSettingsScreenScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUpAntiAliasing() {
        configureAntiAliasingRenderingHints()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
    fun folderTabRendersDualPaneSelectionBottomIconsDialogsAndScrollsOn360dp() {
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

        composeTestRule.onNodeWithText("文件夹").performClick()
        composeTestRule.waitForIdle()

        assertFolderElementsDisplayed(hostView)
        listOf(
            "file_action_rename",
            "file_action_delete",
            "file_action_copy",
            "file_action_info",
            "file_action_export"
        ).forEach { tag ->
            composeTestRule.onNodeWithTag(tag).assertIsNotEnabled()
        }

        composeTestRule.onNodeWithTag("left_vertical_scrollbar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("left_scrollbar_thumb").assertIsDisplayed()
        composeTestRule.onNodeWithTag("right_vertical_scrollbar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("right_scrollbar_thumb").assertIsDisplayed()

        val leftThumbBefore = composeTestRule
            .onNodeWithTag("left_scrollbar_thumb")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val rightThumbBefore = composeTestRule
            .onNodeWithTag("right_scrollbar_thumb")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        captureSoftwareScreenshot(hostView, "folder_360dp.png")

        composeTestRule.onNodeWithTag("folder_left_lazy_column").performScrollToIndex(8)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("folder_right_lazy_column").performScrollToIndex(9)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("folder_item_file-alice-exe").assertIsDisplayed()
        composeTestRule.onNodeWithTag("folder_exe_logo_file-alice-exe", useUnmergedTree = true).assertIsDisplayed()

        val leftThumbAfter = composeTestRule
            .onNodeWithTag("left_scrollbar_thumb")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val rightThumbAfter = composeTestRule
            .onNodeWithTag("right_scrollbar_thumb")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertTrue(
            "Left scrollbar thumb must move down on scroll: before=$leftThumbBefore, after=$leftThumbAfter",
            leftThumbAfter > leftThumbBefore
        )
        assertTrue(
            "Right scrollbar thumb must move down on scroll: before=$rightThumbBefore, after=$rightThumbAfter",
            rightThumbAfter > rightThumbBefore
        )

        composeTestRule.onNodeWithTag("folder_item_file-alice-exe").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("folder_item_selected_file-alice-exe", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("选中 1 项文件，约 64.0 MB").assertIsDisplayed()
        composeTestRule.onNodeWithTag("file_action_info").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("dialog_properties_edit_perm").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("perm_cb_suid").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("perm_cb_recursive").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dialog_perm_cancel").performClick()
        composeTestRule.waitForIdle()

        captureSoftwareScreenshot(hostView, "folder_360dp_scrolled.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
    fun folderTabRendersAndScrollsWithoutOverflowOn412dp() {
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

        composeTestRule.onNodeWithText("文件夹").performClick()
        composeTestRule.waitForIdle()

        assertFolderElementsDisplayed(hostView)
        composeTestRule.onNodeWithTag("left_scrollbar_thumb").assertIsDisplayed()
        composeTestRule.onNodeWithTag("right_scrollbar_thumb").assertIsDisplayed()
        captureSoftwareScreenshot(hostView, "folder_412dp.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
    fun settingsTabRendersPythonCardsWarningAndCustomPathDialogOn360dp() {
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

        composeTestRule.onNodeWithText("设置").performClick()
        composeTestRule.waitForIdle()

        assertSettingsElementsDisplayed(hostView)
        captureSoftwareScreenshot(hostView, "settings_360dp.png")

        composeTestRule.onNodeWithTag("settings_custom_path_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_custom_path_input").performTextReplacement("E:/custom_py")
        composeTestRule.onNodeWithTag("settings_custom_path_confirm").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("E:/custom_py").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
    fun settingsTabRendersCleanlyOn412dp() {
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

        composeTestRule.onNodeWithText("设置").performClick()
        composeTestRule.waitForIdle()

        assertSettingsElementsDisplayed(hostView)
        captureSoftwareScreenshot(hostView, "settings_412dp.png")
    }

    private fun assertFolderElementsDisplayed(hostView: View) {
        val leftPaneBounds = composeTestRule.onNodeWithTag("folder_left_pane").fetchSemanticsNode().boundsInRoot
        val rightPaneBounds = composeTestRule.onNodeWithTag("folder_right_pane").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Dual panes must split width 50:50: left=${leftPaneBounds.width}, right=${rightPaneBounds.width}",
            abs(leftPaneBounds.width - rightPaneBounds.width) <= 2f
        )
        composeTestRule.onNodeWithTag("folder_current_path").assertIsDisplayed()
        composeTestRule.onNodeWithTag("folder_target_path").assertIsDisplayed()
        composeTestRule.onNodeWithText("选择").assertDoesNotExist()
        composeTestRule.onNodeWithTag("folder_selection_summary").assertIsDisplayed()
        composeTestRule.onNodeWithTag("folder_total_summary").assertIsDisplayed()
        composeTestRule.onNodeWithTag("file_bottom_action_bar").assertIsDisplayed()

        listOf(
            "file_action_rename",
            "file_action_delete",
            "file_action_copy",
            "file_action_info",
            "file_action_export"
        ).forEach { tag ->
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Action $tag must stay within host width (${hostView.width}): $bounds",
                bounds.right <= hostView.width.toFloat() + 1f
            )
        }
    }

    private fun assertSettingsElementsDisplayed(hostView: View) {
        composeTestRule.onNodeWithTag("settings_python_card_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_python_version_dropdown").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_python_version_icon", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Python 3.10").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_python_warning_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_python_warning_icon", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("无法检测到？点击下载 Python！").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_python_dir_dropdown").assertIsDisplayed()
        composeTestRule.onNodeWithText("D:/python").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_custom_path_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("自定义路径选择").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_reserved_button").assertIsDisplayed()
        assertTrue(
            "Expected at least two nodes with text 'Python 设置' (card title + reserved button)",
            composeTestRule.onAllNodesWithText("Python 设置").fetchSemanticsNodes().size >= 2
        )
        composeTestRule.onNodeWithText("此处不再多举例！").assertIsDisplayed()

        val menuBounds = composeTestRule
            .onNodeWithTag("settings_python_version_dropdown")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Settings dropdown must stay within host width (${hostView.width}): $menuBounds",
            menuBounds.right <= hostView.width.toFloat() + 1f
        )
    }
}
