package com.aliceincradle.launcher.ui.file

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PathNavigationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun navigatesUpDownHistoryAndRejectsOutOfBounds() {
        val root = tempFolder.newFolder("sandbox_root")
        val sub = File(root, "sub").apply { mkdirs() }
        val outside = tempFolder.newFolder("outside")

        var state = PaneNavState(rootBoundary = root, currentDir = root)
        assertTrue(PathNavigator.navigateParent(state) is NavOutcome.AtRoot)

        val into = PathNavigator.navigateInto(state, sub) as NavOutcome.Navigated
        state = into.state
        assertEquals(sub.canonicalPath, state.currentDir.canonicalPath)
        assertEquals(1, state.historyStack.size)

        val back = PathNavigator.navigateBack(state) as NavOutcome.Navigated
        state = back.state
        assertEquals(root.canonicalPath, state.currentDir.canonicalPath)

        assertTrue(PathNavigator.navigateInto(state, outside) is NavOutcome.OutOfBounds)
    }
}
