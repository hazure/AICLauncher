# 常见问题与排查指南（FAQ）

## 1. 概述

本篇汇总了在虚拟化与转译环境下运行游戏时的常见问题与诊断结论。
近期深度分析排查澄清了此前对状态机阻塞的多项误解。
文内提供了经实测验证的标准排查路径与修复方案。

## 2. 界面交互与状态机问题

### Q1: 初次启动游戏卡在年龄确认界面，按钮无样式且无法交互？
- **故障现象**：
  游戏窗口渲染出年龄选择文字，但按钮样式未加载，仅呈现无样式纯文本。
  鼠标点击无反应，拖动窗口画面不重绘。
  查看 `Player.log` 可见每帧持续抛出 `NullReferenceException` 异常。
- **前期误解澄清**：
  此前曾推测该现象由启动器 ANSI 编码错位或哈希表异步校验阻塞引起。
  经抓取最新运行日志与 CIL 逆向分析，确认该推测并不准确。
- **真实根本原因**：
  早期补丁脚本误将 `XX.X::get_SENSITIVE` 识别为环境检测并将其指令强行清空。
  Mono JIT 校验器扫描死代码时发生越界，抛出致命异常：
  `InvalidProgramException: Invalid IL code in XX.X:get_SENSITIVE (): IL_0009: pop`。
  该异常中断了 `initSensitiveAnnounce()` 流程，导致按钮贴图未加载。
  随后主循环因顶级 UI 容器 `BxTop` 为空，每帧陷入空引用死锁。
- **解决方案**：
  1. 使用最新版自适应补丁器彻底还原 `get_SENSITIVE` 为官方原始字节；
  2. 补丁器仅对 `XX.IN::Awake()` 的 25 字节检测执行等长原位重定向：
  ```bash
  python3 patch_unsafeAssem.py
  ```

### Q2: 首次启动可交互，退出后第二次启动异常冻结？
- **故障现象**：
  首次启动无配置时保持正常，退出后再次运行出现异常冻结。
- **原因分析**：
  若先前启动曾发生中断，可能在存储目录残留了损坏或半初始化的配置文件。
  引擎读取现存 `config.cfg` 后判定 `first_startup = false`。
  状态机在跳转过程中跳过特定组件的重新初始化，从而诱发空引用崩溃。
- **解决方案**：
  清理 Wine 容器中的旧配置与脏数据：
  1. 删除配置目录中的 `config.cfg` 与 `whole.data`：
     `.../AppData/LocalLow/NanameHacha/AliceInCradle/`
  2. 检查 Wine 注册表 `user.reg`，移除 `[Software\NanameHacha\AliceInCradle]` 键值；
  3. 重新运行游戏完成正常初次保存，后续启动即可稳定读盘。

## 3. 运行环境与补丁工具问题

### Q3: 运行修补脚本报错提示版本不兼容或特征不匹配？
- **故障现象**：
  执行 `python3 patch_unsafeAssem.py` 触发 `IncompatibleVersionError` 熔断。
- **原因分析**：
  补丁器内置了严格的安全熔断机制与版本探针。
  若检测到目标 DLL 文件已被第三方工具篡改，脚本将拒绝盲目覆写。
- **解决方案**：
  1. 从原始干净安装包中恢复官方原版 `unsafeAssem.dll`；
  2. 重新运行最新版 `patch_unsafeAssem.py` 执行自适应修补。

### Q4: 终端运行脚本提示找不到目标动态库？
- **故障现象**：
  执行修补脚本提示找不到 `unsafeAssem.dll` 文件。
- **解决方案**：
  在执行前导出 `AIC_GAME_DIR` 环境变量：
  ```bash
  export AIC_GAME_DIR="/path/to/AliceInCradle_ver030"
  python3 patch_unsafeAssem.py
  ```
  或者直接将游戏主目录作为参数显式传入：
  ```bash
  python3 patch_unsafeAssem.py /path/to/AliceInCradle_ver030
  ```

## 4. 参考链接

- [快速上手指引](guide/quickstart.md)
- [CIL 字节码控制流重定向方案](bypass/bytecode-patch.md)
- [法律声明与合规准则](disclaimer.md)
