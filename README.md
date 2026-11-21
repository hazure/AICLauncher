# Alice in Cradle (ver0.30) VM Patch & Technical Research

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Target: Alice in Cradle v0.30](https://img.shields.io/badge/Target-Alice%20in%20Cradle%20v0.30-success.svg)]()
[![Platform: Win64 / Linux Wine / Android](https://img.shields.io/badge/Platform-Win64%20%7C%20Wine%20%7C%20Android-orange.svg)]()
[![Documentation](https://img.shields.io/badge/Docs-GitHub%20Pages-brightgreen.svg)](https://hazure.github.io/alice-in-cradle-030-vm-patch/)

针对独立游戏《Alice in Cradle》（ver0.30，Windows x86-64）虚拟机与运行环境检测机制的深度逆向分析、开源引导器与本地自动化修补方案。

---

## 1. 项目简介

本项目源于对计算机系统底层架构、Windows PE 加载机制、C Runtime 解耦以及跨平台转译兼容性（Linux Wine、Android Termux/Box64/Winlator）的技术探索。

本项目的核心宗旨并非破坏性破解或鼓励绕过保护机制，而是以透明的逆向工程视角，完整解构 Themida 加壳保护原理与 Unity C# 托管层环境指纹检测链条，引导开发者与安全研究员共同正面学习与技术交流。

项目遵循负责任的披露与研究原则，**仓库不包含任何受版权保护的官方二进制资产或 DLL 文件**，全部修补均通过本地开源脚本计算与原地重定向完成。

---

## 2. 目标规格与核心特性

### 2.1 目标规格
- **目标软件**：《Alice in Cradle》官方独立版 **ver0.30**（Windows x86-64 Standalone）
- **引擎版本**：Unity 2022.3.62f2 (MonoBleedingEdge)
- **目标环境**：Linux (Wine / Proton) 与 Android 转译容器 (Termux-X11 / Winlator / Mobox)

### 2.2 核心方案特性
- **外层 Themida 壳剥离**：编写零 C 运行时（Zero-CRT）依赖的 2.5 KB 原生 Win64 极简引导程序（`stub_perfect.c`），直调 `UnityPlayer.dll!UnityMain2`，彻底解除加壳工具的虚拟化弹窗与环境误报；
- **内层 CIL 字节码重定向**：精准重定向 `unsafeAssem.dll` 中 `XX.IN::Awake()` 的条件分支，仅改动 34 字节原地跳过 GPU/驱动与 Wine 挂载路径探测；
- **哈希散列表动态同步**：通过 `update_sha.py` 自动化计算并同步 `StreamingAssets/_sha.txt`，保证原生 `SHAChecker` 毫秒级通过，避免状态机死锁。

---

## 3. 完整技术文档（Documentation）

本项目已构建模块化技术文档库，并支持通过 GitHub Pages 在线浏览：

- **在线文档站点**：[https://hazure.github.io/alice-in-cradle-030-vm-patch/](https://hazure.github.io/alice-in-cradle-030-vm-patch/)
- **本地文档目录**：[`docs/`](docs/)

### 文档目录索引
- **[检测机理深度分析](docs/principles/themida-detection.md)**：
  - [Themida 原生加壳防护](docs/principles/themida-detection.md)
  - [Unity 托管代码环境指纹](docs/principles/unity-detection.md)
  - [文件散列链完整性校验](docs/principles/integrity-sha.md)
- **[绕过技术方案实现](docs/bypass/launcher-stub.md)**：
  - [原生 Win64 启动桩重构](docs/bypass/launcher-stub.md)
  - [CIL 字节码控制流重定向](docs/bypass/bytecode-patch.md)
  - [动态哈希散列表同步](docs/bypass/hash-sync.md)
- **[工具链指南与排查](docs/guide/quickstart.md)**：
  - [快速上手指引](docs/guide/quickstart.md)
  - [多编译器构建指南](docs/guide/compiler.md)
  - [常见问题与避坑指南 (FAQ)](docs/guide/faq.md)
- **[法律声明与合规准则](docs/disclaimer.md)**

---

## 4. 快速上手（Quick Start）

### 4.1 配置环境变量
为消除路径硬编码，各工具优先读取 `AIC_GAME_DIR` 环境变量：
```bash
# Linux / Android (Termux)
export AIC_GAME_DIR="/storage/emulated/0/Documents/AliceInCradle_ver030"

# Windows (CMD)
set AIC_GAME_DIR=D:\Games\AliceInCradle_ver030
```

### 4.2 执行补丁流水线
```bash
# 1. 编译并部署纯净启动器（替换原 AliceInCradle.exe）
x86_64-w64-mingw32-clang -O2 -s -mwindows -nostdlib -Wl,-e,WinMainCRTStartup \
  -o "$AIC_GAME_DIR/AliceInCradle.exe" src/stub_perfect.c -lkernel32

# 2. 执行本地托管代码重定向
python3 patch_unsafeAssem.py

# 3. 重新同步校验散列表
python3 update_sha.py

# 4. 启动游戏
wine "$AIC_GAME_DIR/AliceInCradle.exe"
```
*详细步骤与异常排查请参阅 [快速上手文档](docs/guide/quickstart.md)。*

---

## 5. 仓库结构说明

```
alice-in-cradle-030-vm-patch/
├── docs/                                 # 完整技术文档库（支持 GitHub Pages / Docsify）
│   ├── .nojekyll                         # GitHub Pages 静态站点标记
│   ├── index.html                        # Docsify 单页文档引擎入口
│   ├── README.md                         # 文档主页
│   ├── principles/                       # 双层检测原理深度剖析
│   ├── bypass/                           # 绕过实现与字节码重定向工程细节
│   └── guide/                            # 快速上手、多编译器与 FAQ
├── src/
│   └── stub_perfect.c                    # 纯净 Win64 启动器原生 C 源码（Zero-CRT）
├── tests/                                # 历史探索实验套件与逆向复盘工具
│   ├── 01_asset_suppression_attempts/    # 方案A: 资产层报错抑制实验
│   ├── 02_dll_hijacking_attempts/        # 方案B: DLL 劫持侧载实验
│   ├── 03_native_engine_analysis/        # 方案C: 原生引擎反汇编分析
│   ├── 04_launcher_stub_evolution/       # 方案D: 启动器三代演进史
│   ├── 05_pe_il_analysis_tools/          # 方案E: PE 节区解析与偏移计算工具
│   └── README.md                         # 测试套件复盘报告
├── patch_unsafeAssem.py                  # 本地自动化 IL 字节码修补脚本
├── update_sha.py                         # SHA-256 自动化校验同步脚本
└── README.md                             # 项目主说明文档
```

---

## 6. 法律声明、使用守则与正版支持倡议

### 6.1 正版软件支持倡议
本项目坚定支持软件原创性与知识产权保护。
本补丁的存在绝非为盗版提供便利，我们强烈呼吁所有研究者与玩家通过官方合法渠道（如 DLsite、Steam 或官方赞助渠道）购买并持有正版游戏授权。请以实际行动支持独立开发者与创作团队，只有健康的商业反馈才能保障优质作品的长久迭代。

### 6.2 合规使用准则
本项目仅供计算机系统原理研究、操作系统兼容性分析及合法持有正版授权用户的合理硬件扩展使用。
严禁将本项目中的任何技术细节、工具或补丁用于商业牟利、未经授权的游戏二次打包分发、黑客对抗、外挂制作或其他侵害权利人合法权益的行为。技术的发展应当服务于正面建设与知识共享。

### 6.3 官方支持免责声明
**严禁向游戏开发团队或官方客服渠道提交由于使用本补丁所引发的任何游戏异常、渲染缺陷、逻辑错误或崩溃日志。**
开发团队不承担在非官方指定硬件及转译/虚拟机环境下提供技术支持的义务。任何因环境兼容性绕过导致的存档损坏、性能波动、图形伪影及系统不稳定性，均由补丁使用者自行承担全部责任与后果。

### 6.4 仓库维权与即刻下线机制
维护者充分尊重软件著作权人的知识产权与合法诉求。
**若收到游戏开发团队、发行方或相关合法权利人的官方通告、版权警示或下架请求，本仓库将会在第一时间无条件永久删除全部内容，并彻底关闭项目不再重新开放。**

### 6.5 维护者终局寄语
技术的真正价值在于打破平台壁垒、加深对底层系统的认知并驱动正面发展，而非对抗与破坏。
请珍惜社区开发与研究成果，保持清醒与克制。
**请用户们尊重开发者的劳动成果，尊重正版商业逻辑，尊重人类。**
