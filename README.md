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
- **内层 CIL 字节码自适应重定向**：采用纯 Python 基于上下文特征码与 CLI 元数据定位 `unsafeAssem.dll` 中 `XX.IN::Awake()` 的检测分支，实现 25 字节等长原位改写（In-place Patching），彻底解除对硬编码物理偏移的脆弱依赖；
- **哈希散列表自动联动**：修补器内置重新计算 SHA-256 并自动同步至 `StreamingAssets/_sha.txt`，亦可搭配 `update_sha.py` 全量核验，保证原生 `SHAChecker` 毫秒级通过；
- **关键逻辑物理隔离**：严格隔离反虚机逻辑与非相关功能，严禁修改游戏内容分级属性（`get_SENSITIVE`），杜绝 Mono JIT 字节码越界校验异常。

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
  - [Turnip/DXVK 驱动搭配指南](docs/guide/performance.md)
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

# 2. 执行本地托管代码自适应重定向（自动同步 _sha.txt）
python3 patch_unsafeAssem.py

# 3. 运行内置自动化测试套件验证完整性（可选）
python3 patch_unsafeAssem.py --test

# 4. 启动游戏
wine "$AIC_GAME_DIR/AliceInCradle.exe"
```
*详细步骤与异常排查请参阅 [快速上手文档](docs/guide/quickstart.md)。*

---

## 5. Turnip/DXVK 驱动最佳化搭配指南

### 5.1 Adreno 730 推荐配置
针对搭载高通 Adreno 730 GPU 的设备（如骁龙 8+ Gen 1、8 Gen 1），推荐以下黄金组合：

| 组件名称 | 推荐版本 | 说明 |
| :--- | :--- | :--- |
| **Mesa Turnip** | `v24.1.0` | 稳定性与专有指令支持最佳，无纹理闪烁 |
| **DXVK 转译层** | `2.3.1` | 完善支持 D3D11 特性与非阻塞管线编译 |
| **Box64 Dynarec** | `0.3.0+` | 启用大基本块与安全标志优化指令吞吐 |
| **渲染模式** | 独占全屏 (Native) | 保持与容器原生分辨率 1:1 输出 |

### 5.2 启动桩性能参数自动注入
在最新版本的引导启动器（`src/stub_perfect.c`）中，程序已在调用引擎前自动注入以下环境变量（无任何 CRT 依赖）：
- `BOX64_DYNAREC_BIGBLOCK=2`：启用多块合并动态重编译，显著降低 CPU 模拟开销；
- `BOX64_DYNAREC_SAFEFLAGS=1`：优化状态标志寄存器模拟，兼顾安全性与执行吞吐；
- `DXVK_ASYNC=1`：开启非阻塞管线异步编译，消除初次加载卡顿；
- `MESA_SHADER_CACHE=1`：开启 Mesa 底层磁盘持久化着色器缓存。

### 5.3 着色器缓存与实测表现
通过持久化着色器缓存，第二次进入游戏及复杂特效场景时将直接命中磁盘缓存，彻底消除微卡顿。配合本补丁，在 Adreno 730 设备上实测可达 **120 FPS 满帧稳定运行**。

用户亦可在游戏根目录放置 `dxvk.conf` 进一步调优：
```ini
dxvk.enableAsync = true
dxvk.gpl = true
```

---

## 6. 仓库结构说明

```
alice-in-cradle-030-vm-patch/
├── docs/                                 # 完整技术文档库（支持 GitHub Pages / Docsify）
│   ├── .nojekyll                         # GitHub Pages 静态站点标记
│   ├── index.html                        # Docsify 单页文档引擎入口
│   ├── README.md                         # 文档主页
│   ├── principles/                       # 双层检测原理深度剖析
│   ├── bypass/                           # 绕过实现与字节码重定向工程细节
│   └── guide/                            # 快速上手、驱动最佳化、多编译器与 FAQ
├── src/
│   └── stub_perfect.c                    # 纯净 Win64 启动器原生 C 源码（Zero-CRT，注入性能环境变量）
├── tests/                                # 历史探索实验套件与逆向复盘工具
│   ├── 01_asset_suppression_attempts/    # 方案A: 资产层报错抑制实验
│   ├── 02_dll_hijacking_attempts/        # 方案B: DLL 劫持侧载实验
│   ├── 03_native_engine_analysis/        # 方案C: 原生引擎反汇编分析
│   ├── 04_launcher_stub_evolution/       # 方案D: 启动器三代演进史
│   ├── 05_pe_il_analysis_tools/          # 方案E: PE 节区解析与偏移计算工具
│   └── README.md                         # 测试套件复盘报告
├── patch_unsafeAssem.py                  # 本地自动化 IL 字节码修补脚本（支持自测与 SHA 同步）
├── update_sha.py                         # SHA-256 自动化校验同步脚本
└── README.md                             # 项目主说明文档
```

---

## 7. 法律声明、使用守则与正版支持倡议

### 7.1 正版软件支持倡议
本项目坚定支持软件原创性与知识产权保护。
本补丁的存在绝非为盗版提供便利，我们强烈呼吁所有研究者与玩家通过官方合法渠道（如 DLsite、Steam 或官方赞助渠道）购买并持有正版游戏授权。请以实际行动支持独立开发者与创作团队，只有健康的商业反馈才能保障优质作品的长久迭代。

### 7.2 合规使用准则
本项目仅供计算机系统原理研究、操作系统兼容性分析及合法持有正版授权用户的合理硬件扩展使用。
严禁将本项目中的任何技术细节、工具或补丁用于商业牟利、未经授权的游戏二次打包分发、黑客对抗、外挂制作或其他侵害权利人合法权益的行为。技术的发展应当服务于正面建设与知识共享。

### 7.3 官方支持免责声明
**严禁向游戏开发团队或官方客服渠道提交由于使用本补丁所引发的任何游戏异常、渲染缺陷、逻辑错误或崩溃日志。**
开发团队不承担在非官方指定硬件及转译/虚拟机环境下提供技术支持的义务。任何因环境兼容性绕过导致的存档损坏、性能波动、图形伪影及系统不稳定性，均由补丁使用者自行承担全部责任与后果。

### 7.4 仓库维权与即刻下线机制
维护者充分尊重软件著作权人的知识产权与合法诉求。
**若收到游戏开发团队、发行方或相关合法权利人的官方通告、版权警示或下架请求，本仓库将会在第一时间无条件永久删除全部内容，并彻底关闭项目不再重新开放。**

### 7.5 维护者终局寄语
技术的真正价值在于打破平台壁垒、加深对底层系统的认知并驱动正面发展，而非对抗与破坏。
请珍惜社区开发与研究成果，保持清醒与克制。
**请用户们尊重开发者的劳动成果，尊重正版商业逻辑，尊重人类。**

---

## 8. 项目边界与问题反馈声明

本项目（vm_patch）的核心目标仅限于解除游戏在 VM/非物理机环境下的启动拦截（剥离 Themida 壳与修复托管层环境校验），并保证补丁应用过程中的底层 PE/IL 稳定性。

本项目不负责、也不受理任何超出此范围的兼容性问题（包括但不限于：图形渲染异常/黑屏/花屏、DXVK 或 Turnip 驱动崩溃、音频死锁、性能掉帧，以及触屏/外部按键映射等输入层适配）。

除补丁导致的启动崩溃外，其他环境与配置问题请自行调整 Winlator/Mobox 容器设置或前往对应社区求助。请勿向本仓库或原游戏官方提交此类相关 Issue。
