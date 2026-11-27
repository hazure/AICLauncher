package com.aliceincradle.launcher.ui

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertEquals
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
class TelemetryLayoutScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
    fun narrowScreen360dpRendersEqualCircularGauges() {
        verifyCircularGaugesAndCapture(
            fileName = "telemetry_360dp.png",
            simulateRunning = true
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
    fun standardScreen412dpRendersEqualCircularGauges() {
        verifyCircularGaugesAndCapture(
            fileName = "telemetry_412dp.png",
            simulateRunning = false
        )
    }

    private fun verifyCircularGaugesAndCapture(
        fileName: String,
        simulateRunning: Boolean
    ) {
        val viewModel = createScreenshotViewModel()
        if (simulateRunning) {
            viewModel.launchGame()
            viewModel.toggleSampleDownloads()
        }

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

        val memBounds = composeTestRule.onNodeWithTag("gauge_ring_运存", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val cpuBounds = composeTestRule.onNodeWithTag("gauge_ring_CPU", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val gpuBounds = composeTestRule.onNodeWithTag("gauge_ring_GPU", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue("Memory ring width must be positive: $memBounds", memBounds.width > 0f)
        assertTrue("Memory ring must be 1:1 square: $memBounds", abs(memBounds.width - memBounds.height) <= 1f)
        assertTrue("CPU ring must be 1:1 square: $cpuBounds", abs(cpuBounds.width - cpuBounds.height) <= 1f)
        assertTrue("GPU ring must be 1:1 square: $gpuBounds", abs(gpuBounds.width - gpuBounds.height) <= 1f)
        assertEquals("GPU ring width must equal CPU ring width", cpuBounds.width, gpuBounds.width, 1f)
        assertEquals("GPU ring width must equal Memory ring width", memBounds.width, gpuBounds.width, 1f)
        assertEquals("GPU ring height must equal CPU ring height", cpuBounds.height, gpuBounds.height, 1f)

        captureSoftwareScreenshot(hostView, fileName)
    }
}
