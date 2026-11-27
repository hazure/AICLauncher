package com.aliceincradle.launcher.ui

import android.view.View
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewGroup

/**
 * # ComposeDialogShadowHelper
 *
 * 正式测试基建辅助类：解决 Robolectric LEGACY 模式下 Compose AlertDialog 触发
 * `AppNotIdleException` (60 秒超时死锁) 的问题。
 *
 * ---
 *
 * ## 问题根因
 *
 * Compose 的 `AlertDialog` / `Dialog` 组合件会在独立的 `android.view.Window`（通过
 * `DialogWrapper extends ComponentDialog`）中创建一个新的 `AndroidComposeView`。
 * 这个 Dialog 窗口拥有独立的 `ViewRootImpl`，其初始 layout 请求通过
 * `View.requestLayout()` → `ViewRootImpl.scheduleTraversals()` → 向主 Looper 投递
 * `TraversalRunnable` 完成。
 *
 * 然而在 Robolectric 4.13 `@GraphicsMode(LEGACY)` 模式下：
 *
 * 1. `RobolectricIdlingStrategy.runUntilIdle` 进入循环，依次调用：
 *    - `runEspressoOnIdle()` → 排空主线程 `MainLooper` 即时消息
 *    - `requestLayoutIfNeeded()` → 对所有 `getShouldWaitForMeasureAndLayout() == true` 的
 *      `ViewRootForTest` 调用 `view.requestLayout()`
 *    - `composeIdlingResource.isIdleNow()` → 仅当 `shouldPumpTime()`（即存在主时钟 awaiter、
 *      Snapshot 变更或 Recomposer 待处理工作）为 `true` 时，调用
 *      `TestMonotonicFrameClock.advanceTimeByFrame()` 以对已注册根视图执行
 *      `measureAndLayoutForTest()`
 *
 * 2. Dialog 的 `AndroidComposeView` 在独立二级窗口中脱离了上述 frame clock 同步路径；
 *    当主 UI 已无 Snapshot/Recomposer 挂起变更导致 `shouldPumpTime() == false` 时，
 *    二级窗口的 `requestLayout()` 在 LEGACY 模式下不会自动推进并清除
 *    `MeasureAndLayoutDelegate.hasPendingMeasureOrLayout`，使 `getShouldWaitForMeasureAndLayout()`
 *    始终为 `true`、`isIdleNow()` 始终返回 `false`，最终在 60 秒后抛出
 *    `AppNotIdleException`。
 *
 * 注意：仅在 window resize 阶段触发的 `ShadowViewRootImpl.callDispatchResized()` 无法作为
 * 通用每轮 idling 推进点（且 `ViewRootImpl` 本身不暴露 `measureAndLayoutForTest()`）；
 * 而 `AndroidComposeView` 实现 `ViewRootForTest` 并被 `RobolectricIdlingStrategy.requestLayoutIfNeeded()`
 * 在每轮等待中直接调用，因此在此处切入最直接可靠。
 *
 * ---
 *
 * ## 修复方案
 *
 * Shadow `AndroidComposeView.requestLayout()`，在原始 `View.requestLayout()` 语义
 * 之后，**立即同步调用 `measureAndLayoutForTest()`**，从而在 idling 循环的当次迭代
 * 内就清除 `hasPendingMeasureOrLayout`，使 `ComposeIdlingResource.isIdleNow()`
 * 能够立即返回 `true`，消除 Dialog 窗口 60 秒超时死锁。
 *
 * ---
 *
 * ## 使用方式
 *
 * 在需要与 Compose Dialog 交互的测试类上，将 [Arm64AndroidComposeViewShadow] 加入
 * `@Config(shadows = [...])` 列表：
 *
 * ```kotlin
 * @Config(shadows = [
 *     Arm64BitmapShadow::class,
 *     Arm64PaintShadow::class,
 *     Arm64CanvasShadow::class,
 *     Arm64AndroidComposeViewShadow::class,  // ← Dialog 死锁修复
 * ])
 * class MyScreenshotTest { ... }
 * ```
 *
 * ---
 *
 * ## 技术细节
 *
 * ### 为什么必须继承 `ShadowViewGroup`？
 *
 * Robolectric 对 Android 类进行字节码插桩后，`View.requestLayout()` 的插桩实现会调用
 * `Shadow.extract(this)` 并将结果强制转型为 `ShadowView`。若自定义 shadow 不继承
 * `ShadowView`（或其子类），则在 `AndroidComposeView` 构造时（调用链：
 * `ViewGroup.__constructor__` → `initViewGroup` → `View.setFlags` → `View.requestLayout`）
 * 就会立即抛出 `ClassCastException`。由于 `AndroidComposeView extends ViewGroup`，
 * 我们继承 `ShadowViewGroup`（继承链 `ShadowViewGroup → ShadowView`），既满足类型约束，
 * 又能复用父类的 View 状态管理。
 *
 * `realView` 字段继承自 `ShadowView`（`protected View realView`），无需重复声明。
 *
 * ### 为什么用字符串 `className` 引用 `AndroidComposeView`？
 *
 * `androidx.compose.ui.platform.AndroidComposeView` 在 Compose 源码中被标注为
 * `internal`，无法在测试代码中直接 `import`。使用 `@Implements(className = "...")`
 * 形式可绕过编译期可见性检查，仅在运行时由 Robolectric 动态绑定 shadow。
 *
 * ### 为什么用字符串版 `Shadow.directlyOn`？
 *
 * `Shadow.directlyOn<Unit>(instance, Class, name)` 在 Kotlin 中存在泛型重载歧义，
 * 编译器无法推断 `Unit` 类型参数。改用 `Shadow.directlyOn<Any?>(instance, "className",
 * "methodName")` 匹配签名 `directlyOn(Any!, String!, String!, vararg ClassParameter):R`
 * 即可消除歧义并正确绕过 shadow 分发、调用真实的 `View.requestLayout()` 实现体。
 */
@Suppress("unused")
@Implements(className = "androidx.compose.ui.platform.AndroidComposeView", isInAndroidSdk = false)
class Arm64AndroidComposeViewShadow : ShadowViewGroup() {
    // `realView: View` 继承自 ShadowView (via ShadowViewGroup)，无需重复声明 @RealObject。

    /**
     * Shadow 实现：在原始 `View.requestLayout()` 执行后立即同步触发 Compose 测量/布局，
     * 使 `ComposeIdlingResource.getShouldWaitForMeasureAndLayout()` 在本次 idling
     * 循环迭代结束前变为 `false`，彻底消除 Dialog 触发的 60 秒超时死锁。
     */
    @Implementation
    override fun requestLayout() {
        // 1. 调用真实的 View.requestLayout() 语义，确保 View 层级 layout 标志位正常置位
        //    并传播到父节点（包括 ViewRootImpl.scheduleTraversals()）。
        //    字符串版 directlyOn 绕过 shadow 分发、无递归风险，同时避免 Kotlin 泛型歧义。
        Shadow.directlyOn<Any?>(realView, "android.view.View", "requestLayout")

        // 2. 立即同步执行 Compose 测量/布局 pass，清除 MeasureAndLayoutDelegate 的
        //    hasPendingMeasureOrLayout 标志，使 ComposeIdlingResource 在下一次
        //    isIdleNow() 检查时返回 true。
        //    runCatching 防御：若 measureAndLayoutForTest() 因状态不一致抛出异常，
        //    不影响 View 层级的正常构建；正常情况下此调用不会抛出。
        runCatching {
            realView.javaClass.getMethod("measureAndLayoutForTest").invoke(realView)
        }
    }
}
