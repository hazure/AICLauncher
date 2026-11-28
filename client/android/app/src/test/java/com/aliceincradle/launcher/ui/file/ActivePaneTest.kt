package com.aliceincradle.launcher.ui.file

import com.aliceincradle.launcher.data.FilePaneSide
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivePaneTest {

    @Test
    fun resolvesLeftRightDividerAndSinglePaneMode() {
        val width = 1000f
        val dividerHalf = 10f

        assertEquals(
            FilePaneSide.LEFT,
            ActivePaneResolver.resolvePaneHit(200f, width, dividerHalf, FilePaneSide.RIGHT)
        )
        assertEquals(
            FilePaneSide.RIGHT,
            ActivePaneResolver.resolvePaneHit(800f, width, dividerHalf, FilePaneSide.LEFT)
        )
        assertEquals(
            FilePaneSide.RIGHT,
            ActivePaneResolver.resolvePaneHit(500f, width, dividerHalf, FilePaneSide.RIGHT)
        )
        assertEquals(
            FilePaneSide.LEFT,
            ActivePaneResolver.resolvePaneHit(800f, width, dividerHalf, FilePaneSide.RIGHT, isSinglePane = true)
        )
    }
}
