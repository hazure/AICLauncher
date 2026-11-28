package com.aliceincradle.launcher.ui.file

import com.aliceincradle.launcher.data.FilePaneSide

/**
 * Pure 50:50 dual-pane hit-testing resolver.
 * Divider and outer margins preserve [currentActive] unchanged; single-pane mode always returns LEFT.
 */
object ActivePaneResolver {
    fun resolvePaneHit(
        tapX: Float,
        totalWidth: Float,
        dividerHalfWidthPx: Float,
        currentActive: FilePaneSide,
        isSinglePane: Boolean = false
    ): FilePaneSide {
        if (isSinglePane) return FilePaneSide.LEFT
        if (totalWidth <= 0f || tapX < 0f || tapX > totalWidth) return currentActive

        val mid = totalWidth * 0.5f
        if (tapX >= mid - dividerHalfWidthPx && tapX <= mid + dividerHalfWidthPx) {
            return currentActive
        }
        return if (tapX < mid) FilePaneSide.LEFT else FilePaneSide.RIGHT
    }
}
