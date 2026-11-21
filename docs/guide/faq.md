# 常见问题与排查指南（FAQ）

## 1. 概述

本篇汇集了在虚拟化与转译环境下运行游戏时的典型问题。
内容涵盖启动参数异常、状态机阻塞与图形后端兼容性等排查思路。

## 2. 界面交互与状态机问题

### Q1: 初次启动游戏卡在年龄确认界面，按钮无样式且无法交互？
- **故障现象**：
  画面呈现无样式纯文本，鼠标点击无效，拖动窗口画面不重绘。
- **原因分析 1（启动参数宽字符编码错位）**：
  启动桩错误传入单字节 ANSI 字符串 `GetCommandLineA()`。
  Unity 内部调用 `CommandLineToArgvW` 解析参数。
  ANSI 字节被拼成乱码 Unicode，导致消息循环异常死锁。
- **原因分析 2（散列校验异步阻塞）**：
  未执行 `update_sha.py` 同步哈希清单。
  校验器未能通过比对，导致标题状态机无限等待。
- **解决方案**：
  在终端中重新执行散列表同步：
  ```bash
  python3 update_sha.py
  ```

### Q2: 如何跳过初次启动时的年龄确认界面？
- **技术原理**：
  在 `nel.CFG::.cctor()` 中，字段 `first_startup` 默认赋值为 1。
  对应文件偏移 `0x32f2` 处的字节码 `0x17`（`ldc.i4.1`）。
- **修补方案**：
  将该字节修改为 `0x16`（`ldc.i4.0`）。
  修改后 `first_startup` 默认为 0，游戏将直接进入主标题界面。
  修补后必须重新执行 `update_sha.py`。

## 3. 图形后端与 Wine 环境问题

### Q3: 首次启动正常，退出后第二次启动黑屏或崩溃？
- **故障现象**：
  首发运行正常，保存配置后再次运行出现黑屏或无响应。
- **原因分析**：
  配置文件中启用了全屏模式（`CFG.fullscreen_mode == true`）。
  下次启动时游戏在生命周期早期强行切换全屏。
  在 Wine 或 Turnip 驱动下，极易引发 Vulkan 交换链挂起。
- **解决方案**：
  在游戏设置中始终保持窗口化模式（Windowed Mode）运行。
  切勿开启独占全屏。

### Q4: 终端运行修补脚本提示找不到目标文件？
- **故障现象**：
  执行 `python3 patch_unsafeAssem.py` 报错提示文件缺失。
- **解决方案**：
  在执行前导出 `AIC_GAME_DIR` 环境变量：
  ```bash
  export AIC_GAME_DIR="/path/to/AliceInCradle_ver030"
  ```
  或者直接将游戏路径作为首个参数传入：
  ```bash
  python3 patch_unsafeAssem.py /path/to/AliceInCradle_ver030
  ```

## 4. 参考链接

- [快速上手指引](guide/quickstart.md)
- [原生 Win64 启动桩重构设计](bypass/launcher-stub.md)
- [法律声明与合规准则](disclaimer.md)
