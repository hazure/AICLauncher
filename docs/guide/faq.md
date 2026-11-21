# 常见问题与避坑指南（FAQ）

## 1. 运行现象与排查分析

### Q1: 初次启动游戏，弹出年龄确认界面时画面定格、按钮没有样式仅有文字？
- **根本原因 1（命令行参数宽字符编码错位）**：
  早期引导桩错误地将单字节 ANSI `GetCommandLineA()` 传递给 `UnityMain2`。
  64 位 Unity 播放器内部通过 `SHELL32.dll!CommandLineToArgvW` 解析参数，当其接收 ANSI 字节流时，相邻 ASCII 字节被拼接为高位乱码 Unicode，造成参数解析器越界崩溃或输入消息循环死锁。
  **排查与解决**：拉取仓库最新代码，确保 `src/stub_perfect.c` 中声明为 `LPWSTR` 并传入 `GetCommandLineW()`，重新编译覆盖 `AliceInCradle.exe`。
- **根本原因 2（文件散列校验异步阻塞）**：
  若在修补 `unsafeAssem.dll` 后未执行 `update_sha.py`，原生校验器 `SHAChecker` 会检测到哈希不匹配或陷入等待，导致标题画面状态机被锁死在 `STATE.SENSITIVE_ANNOUNCE`。
  **排查与解决**：在终端运行 `python3 update_sha.py`，确保全部 31 个文件校验一致通过。

---

### Q2: 首次启动可正常进入游戏，退出后第二次启动黑屏、无响应或崩溃？
- **根本原因（全屏模式导致 Vulkan 交换链挂起）**：
  在游戏首次运行并保存配置后，若配置文件中记录了全屏模式（`CFG.fullscreen_mode == true`），在下次启动时，`CFG.loadSdFile()` 会在早期生命周期直接调用 `IN.setFullScreenMode` -> `UnityEngine.Screen::SetResolution(..., FullScreenMode.FullScreenWindow)`。
  在 Android Wine（Winlator / Mobox / Termux-X11 + Turnip 驱动）环境下，Unity 启动早期强行切换全屏往往会导致 Vulkan Swapchain 重建失败，引发无响应或黑屏。
- **排查与解决**：
  在游戏设置中始终保持**窗口化模式（Windowed Mode）**运行，切勿开启独占全屏。

---

### Q3: 运行脚本时提示找不到目标文件或目录？
- **根本原因**：未设置 `AIC_GAME_DIR` 环境变量，且当前工作目录未在游戏根目录。
- **排查与解决**：
  根据操作系统正确导出环境变量：
  ```bash
  export AIC_GAME_DIR="/path/to/AliceInCradle_ver030"
  ```
  或者将游戏根目录直接作为命令行参数传入执行：
  ```bash
  python3 patch_unsafeAssem.py /path/to/AliceInCradle_ver030
  ```

---

### Q4: 如何完全跳过初次启动时的年龄确认提示框？
- **技术原理**：在 `Assembly-CSharp.dll` 的静态构造函数 `nel.CFG::.cctor()` 中，`first_startup` 字段被默认赋值为 1（`ldc.i4.1`，文件偏移 `0x32f2` 处的字节 `0x17`）。
- **可选方案**：可将该偏移处的 `0x17` 修改为 `0x16`（`ldc.i4.0`），使 `first_startup` 默认为 0。游戏在初次启动时将直接跳过敏感确认状态机，直达主标题界面。修补后需再次运行 `python3 update_sha.py` 更新散列表。
