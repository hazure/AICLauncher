# T19 环境阻塞报告（Termux aarch64 本地编译）

- **任务卡**：`T19`（Kotlin + Jetpack Compose Android 客户端初步骨架搭建）
- **分支**：`nocheck_dev`
- **阻塞结论**：**Termux 端本地 `./gradlew assembleDebug` 超过 120s/150s 仍未完成（触发资源/内存瓶颈）**，按指令立即停止本地编译尝试，转交 GitHub Actions CI 流水线完成最终编译验证。

## 1. 已核对并应用的 `TERMUX_ANDROID_BUILD_ENV.md` 配置
- `JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk`（OpenJDK `17.0.21.1`）
- `ANDROID_HOME=/data/data/com.termux/files/home/Android/Sdk`（`platforms;android-34`、`build-tools;34.0.0`、`ndk/26.3.11579264`）
- `client/android/local.properties`（gitignored）：配置 `sdk.dir` 与 `cmake.dir=/data/data/com.termux/files/usr`
- 全局 `~/.gradle/gradle.properties`：`android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`
- `client/android/gradle.properties`：
  - `android.useAndroidX=true`、`android.nonTransitiveRClass=true`
  - `org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m`
  - `org.gradle.workers.max=2`、`kotlin.compiler.execution.strategy=in-process`
- `client/android/app/build.gradle.kts`：关闭调试包混淆与资源压缩（`isMinifyEnabled = false`、`isShrinkResources = false`）。

## 2. 阻塞原因
- 在 Termux (`aarch64 Android`) 单机环境下，Jetpack Compose (`1.6.8`) + Material3 (`1.2.1`) + Kotlin (`1.9.24`) 的依赖解析、D8 dexing 与 Compose 编译器插件在受限内存/CPU 下耗时过长（>150s 未收敛），不适合在端侧阻塞等待。

## 3. 后续交接
- 已将 `client/android/` 完整骨架代码与本报告提交并推送至 `nocheck_dev` 分支，并向 `dev` 分支发起 PR，由 GitHub Actions（`ubuntu-latest`）完成 `./gradlew assembleDebug` 最终编译验证。
