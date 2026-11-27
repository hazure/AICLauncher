package com.aliceincradle.launcher.ui.file

import java.io.File

data class PaneNavState(
    val rootBoundary: File,
    val currentDir: File,
    val historyStack: List<File> = emptyList()
)

sealed class NavOutcome {
    data class Navigated(val state: PaneNavState) : NavOutcome()
    data class AtRoot(val message: String) : NavOutcome()
    data class OutOfBounds(val message: String) : NavOutcome()
}

/**
 * Per-pane directory navigation with history stack and root-boundary confinement.
 * Keeps ".." at root without navigating above [PaneNavState.rootBoundary] (Decision D5).
 */
object PathNavigator {

    fun navigateInto(state: PaneNavState, targetDir: File): NavOutcome {
        val canonicalTarget = runCatching { targetDir.canonicalFile }.getOrElse { targetDir }
        if (!isWithinBoundary(state.rootBoundary, canonicalTarget)) {
            return NavOutcome.OutOfBounds("越界路径已拒绝：${canonicalTarget.name}")
        }
        if (!canonicalTarget.isDirectory || !canonicalTarget.canRead()) {
            return NavOutcome.OutOfBounds("无法访问目录：${canonicalTarget.name}")
        }
        val nextHistory = if (state.currentDir.canonicalPath != canonicalTarget.canonicalPath) {
            state.historyStack + state.currentDir
        } else {
            state.historyStack
        }
        return NavOutcome.Navigated(
            state.copy(
                currentDir = canonicalTarget,
                historyStack = nextHistory
            )
        )
    }

    fun navigateParent(state: PaneNavState): NavOutcome {
        val currentCanonical = runCatching { state.currentDir.canonicalFile }.getOrElse { state.currentDir }
        val rootCanonical = runCatching { state.rootBoundary.canonicalFile }.getOrElse { state.rootBoundary }
        if (currentCanonical.path == rootCanonical.path || currentCanonical.parentFile == null) {
            return NavOutcome.AtRoot("已在根目录")
        }
        val parent = currentCanonical.parentFile ?: return NavOutcome.AtRoot("已在根目录")
        if (!isWithinBoundary(rootCanonical, parent)) {
            return NavOutcome.AtRoot("已在根目录")
        }
        return NavOutcome.Navigated(
            state.copy(
                currentDir = parent,
                historyStack = state.historyStack + state.currentDir
            )
        )
    }

    fun navigateBack(state: PaneNavState): NavOutcome {
        if (state.historyStack.isEmpty()) {
            return NavOutcome.AtRoot("已在根目录")
        }
        val prev = state.historyStack.last()
        val remaining = state.historyStack.dropLast(1)
        return NavOutcome.Navigated(
            state.copy(
                currentDir = prev,
                historyStack = remaining
            )
        )
    }

    fun isWithinBoundary(rootBoundary: File, target: File): Boolean {
        val rootPath = runCatching { rootBoundary.canonicalPath }.getOrElse { rootBoundary.absolutePath }
        val targetPath = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator) || rootPath == File.separator
    }
}
