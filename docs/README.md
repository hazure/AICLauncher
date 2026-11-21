# Alice in Cradle (ver0.30) 逆向工程与系统兼容性技术文档

欢迎查阅《Alice in Cradle》(ver0.30) 运行环境与虚拟机检测机制深度逆向分析、开源引导器与本地修补方案技术文档。

## 1. 项目初衷与背景

本项目立足于计算机系统工程、可执行文件加载机制以及跨平台转译兼容性的技术探索。

本项目的核心初衷并非提供通用的破坏性破解或鼓励绕过保护机制，而是立足于底层二进制逆向与操作系统原理，向技术爱好者与系统级开发者完整呈现 Windows PE 加载流程、C Runtime 最小化解耦、Unity 引擎架构及 .NET/Mono 托管字节码控制流分析的工程全貌。

我们深信，逆向工程的终极目标应当是深入理解软件如何与操作系统交互、探寻异构平台（如 Linux Wine、Android Termux/Box64/Winlator）下的互操作性边界，并在此过程中促进开发者共同正面学习与技术积累，而非用于恶意开发、破坏性对抗或灰色产业链。

## 2. 目标程序与环境规范

| 属性 | 规格参数 |
| :--- | :--- |
| **目标游戏名称** | Alice in Cradle |
| **目标游戏版本** | 官方独立版 **ver0.30** |
| **底层引擎架构** | Unity 2022.3.62f2 (Windows x86-64 Standalone, MonoBleedingEdge) |
| **目标运行环境** | Linux (Wine 8.0+) / Android 转译容器 (Termux-X11 / Winlator / Mobox / Box64) |
| **外层防护方案** | Oreans Themida 原生加壳 |
| **内层防护方案** | Unity 托管代码环境指纹分析 + SHA-256 完整性链条校验 |

## 3. 文档体系导览

本技术文档库分为四大核心板块：

- **检测机理深度分析**：
  - [Themida 原生加壳防护](principles/themida-detection.md)：剖析商业加壳工具在 Ring3 入口点的虚拟化判定逻辑与硬编码弹窗。
  - [Unity 托管代码环境指纹](principles/unity-detection.md)：详述 `XX.IN::Awake()` 中对驱动关键词、特定挂载路径及主模块 SHA-1 的探测链。
  - [文件散列链完整性校验](principles/integrity-sha.md)：分析游戏在 `StreamingAssets/_sha.txt` 中维护的 31 项哈希校验机制。
- **技术绕过方案实现**：
  - [原生 Win64 启动桩重构](bypass/launcher-stub.md)：实现仅 2.5 KB 的零 CRT 纯系统调用启动器，直通 `UnityPlayer.dll!UnityMain2`。
  - [CIL 字节码控制流重定向](bypass/bytecode-patch.md)：通过单指令短跳重定向绕过 `SceneCannotLaunch_InvalidEnviroment` 报错场景。
  - [动态哈希散列表同步](bypass/hash-sync.md)：基于 Python 动态同步 `_sha.txt`，恢复原生校验器合规状态。
- **实战指南与常见问题**：
  - [快速上手指引](guide/quickstart.md)：基于 `AIC_GAME_DIR` 环境变量的自动化五步落地教程。
  - [多编译器构建指南](guide/compiler.md)：提供 Clang、GCC 与 MSVC 三套原生编译流水线。
  - [常见问题与避坑指南 (FAQ)](guide/faq.md)：深度复盘 Wine 环境下的 Vulkan Swapchain 重建死锁与宽字符参数规范。
- **法律声明与维护者原则**：
  - [法律声明与正版倡议](disclaimer.md)：正版持有声明、官方免责、即刻下线机制与终局寄语。
