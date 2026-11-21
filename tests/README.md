# 逆向工程试验记录与历史绕过方案技术剖析

## 1. 概述

本目录归档了在针对《Alice in Cradle》(ver0.30) 运行环境与反虚拟机检测机制进行逆向分析过程中，所编写的全部测试脚本、探索性原型代码以及中间阶段工具。

本目录的存在并非为了提供已落地的最终补丁，而是作为技术分析案例库，详尽记录逆向过程中面对双层复杂防御体系时的各种假设推演、技术验证、失败归因与方案演进路径。供安全研究员与系统开发者参考，避免在同类型工程中重复低效探索。

---

## 2. 测试分类与目录结构

```
tests/
├── 01_asset_suppression_attempts/         # 方案A: 资产层报错场景抑制与组件剥离实验
│   ├── scan_level3.py                     # 基于 UnityPy 的 level3 资产对象结构扫描器
│   ├── patch_level3.py                    # MonoBehaviour (PathID 12) 属性篡改脚本
│   ├── delete_mb.py                       # 资产对象表硬删除实验脚本
│   ├── delete_mb_v2.py                    # 兼容不同版本 UnityPy 字典结构的删除脚本
│   ├── patch_exact.py                     # 场景内报错多语言字符串原地空置 Patch
│   └── unlink_component.py                # 从 GameObject 组件指针数组中解除挂载关系
│
├── 02_dll_hijacking_attempts/             # 方案B: 基于 Win32 DLL 侧载劫持的外壳拦截阻断
│   ├── user32.c                           # MessageBoxA/W 拦截原型源码
│   ├── user32.def                         # user32.dll 导出函数系统级转发定义表
│   ├── user32_v2.c                        # 增强型日志记录与对话框拦截源码
│   ├── user32_v2.def                      # 完整版导出函数映射表
│   ├── kernel32.c                         # ExitProcess / TerminateProcess 拦截原型
│   ├── kernel32.def                       # kernel32.dll 核心导出函数映射表
│   └── dllmain.c                          # 弹窗记录分析探针动态库
│
├── 03_native_engine_analysis/             # 方案C: UnityPlayer 引擎原生交叉引用分析
│   └── find_vmware.py                     # Capstone 反汇编与特定虚拟化字符串交叉引用检索器
│
├── 04_launcher_stub_evolution/            # 方案D: 原生 Win64 引导器架构演进史
│   ├── stub_v1_crt.c                      # 第一代: 依赖 C Runtime 标准 main 入口
│   ├── stub_v2_winmain.c                  # 第二代: WinMain 入口与 GUI 子系统适配
│   └── stub_v3_nocrt.c                    # 第三代: -nostdlib 纯 KERNEL32 零依赖终极版
│
├── 05_pe_il_analysis_tools/               # 方案E: PE 节区解析与 IL 偏移精确计算工具
│   └── pe_section_rva_tool.py             # 自动化 PE 节表映射与 RVA 到物理偏移计算工具
│
└── README.md                              # 本说明文档
```

---

## 3. 各阶段探索方案技术剖析与失败归因

### 3.1 方案 A：资产层报错抑制（`01_asset_suppression_attempts/`）

#### 3.1.1 初始设想与理论假设
在最初观察到游戏弹出 "The app has been stopped because it may have been launched in an unsupported environment" 提示时，通过二进制搜索发现该提示硬编码于资产包 `AliceInCradle_Data/level3` 中。
因此推测：若能直接在资产层面移除显示该提示的 `MonoBehaviour`（PathID 12），或将该文本置空，能否直接消除报错？

#### 3.1.2 实验代码解析
- `scan_level3.py`：使用 `UnityPy` 解析 Unity 资产序列化树，枚举并定位了挂载该报错脚本的 `GameObject`（PathID 2）与 `MonoBehaviour`（PathID 12）。
- `patch_level3.py` 与 `patch_exact.py`：尝试将 `m_Enabled` 设为 0，或将字符串缓冲区填充 `\x00`。
- `delete_mb.py` 与 `unlink_component.py`：直接从序列化对象表中删除对象，或从 GameObject 的组件指针链表中擦除指针。

#### 3.1.3 失败与局限性归因
1. **治标不治本**：逆向深入后确认，`level3` 对应的实际是 `SceneCannotLaunch_InvalidEnviroment` 场景。该场景本身只是报错结果，并非检测源头。即使成功篡改资产消除文字显示，代码流依然处于阻断场景中，无法加载游戏核心数据。
2. **Unity 序列化校验敏感性**：直接对二进制资产进行对象增删极易破坏 Unity 序列化数据块的全局偏移与对齐规则，导致 Unity 引擎在加载场景时报 `Corrupted data` 崩溃。

---

### 3.2 方案 B：Win32 DLL 侧载劫持（`02_dll_hijacking_attempts/`）

#### 3.2.1 初始设想与理论假设
针对外层加壳程序弹出的 `"Sorry, this application cannot run under a Virtual Machine"`，考虑在游戏根目录下放置伪造的 `user32.dll` 或 `kernel32.dll`（利用 Windows 默认的 DLL 搜索顺序），拦截 `MessageBoxW` 与 `ExitProcess` 调用，使拦截弹窗静默并阻止进程退出。

#### 3.2.2 实验代码解析
- `user32.c` / `user32_v2.c`：导出 `MyMessageBoxA/W`，并在 `.def` 文件中将数以百计的其他 Windows API 通过 `#pragma comment` 或 `.def` 转发给系统原生 `C:\Windows\System32\user32.dll`。
- `kernel32.c`：提供空的 `MyExitProcess` 与假装成功的 `MyTerminateProcess`。
- `dllmain.c`：探针模块，用于在 Wine 环境下探测调用时序并记录至日志文件。

#### 3.2.3 失败与局限性归因
1. **Themida 壳层保护抵御**：商业保护壳 Themida 自身包含严格的 DLL 侧载防护与系统库完整性校验，在初始化阶段即会检测关键导出表是否被篡改或劫持。
2. **KnownDLLs 机制限制**：现代 Windows 及 Wine 环境对 `kernel32.dll` 和 `user32.dll` 实施 `KnownDLLs` 保护策略，操作系统直接强制从 System32/SysWOW64 加载，不会优先搜索应用程序工作目录，导致劫持完全不生效。
3. **控制流并未恢复**：即使通过 API 钩子阻止了 `ExitProcess`，加壳程序进入异常分支后由于关键解密逻辑未执行，后续依然会因访问非法内存地址引发 `0xC0000005 (Access Violation)`。

---

### 3.3 方案 C：UnityPlayer 引擎原生代码逆向（`03_native_engine_analysis/`）

#### 3.3.1 探索过程
在分析 `UnityPlayer.dll`（约 31 MB）时，初步发现包含 `VirtualBox` 字符串。使用 Python 脚本 `find_vmware.py`（基于 `pefile` 与 `capstone` 反汇编引擎）搜索引用该字符串的指令：
```python
# 遍历所有可执行节区，检索引用 0x1819B8780 虚拟地址的 RIP-relative 指令
```

#### 3.3.2 分析结论（排除假阳性）
经反汇编分析确认，该段代码位于 Unity 渲染后端的硬件信息统计模块中，其上下文为：
`Trident`, `VirtualBox`, `VMWare`, `Microsoft`, `Parallels`, `Qualcomm`...
这仅仅是 Unity 引擎内置用于将显卡 PCI Vendor ID 映射为厂商可读名称的查表代码（属于引擎内部日志统计逻辑），根本不参与反虚拟机环境拦截。此探索帮助团队成功排除了对 `UnityPlayer.dll` 进行复杂机器码补丁的不必要工作。

---

### 3.4 方案 D：原生 Win64 启动器演进史（`04_launcher_stub_evolution/`）

在确认 `AliceInCradle.exe` 的本质仅是调用 `UnityPlayer.dll!UnityMain2` 后，团队决定独立编写干净启动器以彻底解耦 Themida。启动器经历了三代演进：

#### 3.4.1 第一代：`stub_v1_crt.c`
- **实现特征**：使用标准 ANSI C `main(int argc, char** argv)` 入口，依赖标准 C Runtime（CRT）。
- **问题**：在通过 `llvm-mingw` 编译为 Windows GUI 子系统（`-mwindows`）时，链接器报错缺少 `wWinMain` 入口符号；若编译为 Console 子系统，在启动游戏时会闪烁黑色的命令行窗口，体验与官方版本不符。

#### 3.4.2 第二代：`stub_v2_winmain.c`
- **实现特征**：改用 `WinMain` 入口并设定 `-mwindows`，使用 `windows.h` 标准数据类型。
- **问题**：虽然消除了黑框，但可执行文件依然动态链接了 `api-ms-win-crt-*`（UCRT 运行时库），在精简版 Wine 或特定 Android 转译容器中可能因缺少 UCRT 组件而启动失败。

#### 3.4.3 第三代（最终采纳）：`stub_v3_nocrt.c`
- **实现特征**：
  - 编译选项加入 `-nostdlib`，完全切断任何 C 运行时库；
  - 入口函数指定为自定义的 `WinMainCRTStartup`；
  - 仅链接系统底层 `KERNEL32.dll`；
  - 显式保证 x86-64 ABI 的 16 字节栈对齐要求，并为 `UnityMain2` 预留 32 字节 Shadow Space。
- **结果**：编译产物体积仅为 2.5 KB，没有任何第三方或高版本 CRT 依赖，具备最高的跨容器与跨 Wine 版本兼容性。

---

### 3.5 方案 E：PE 节区解析与 IL 偏移精确计算（`05_pe_il_analysis_tools/`）

在托管代码补丁过程中，绝对不能凭借经验硬编码文件偏移。本目录归档的 `pe_section_rva_tool.py` 实现了以下自动化分析流水线：
1. 读取 DOS 头 `e_lfanew (0x3C)` 解析 PE 签名；
2. 遍历 Optional Header 之后的 Section Table，获取每个节区的 `VirtualAddress`、`VirtualSize`、`PointerToRawData` 及 `SizeOfRawData`；
3. 将 `monodis` 输出中获取到的 Method RVA（如 `0xfb88c` 与 `0x13ffd0`）通过节区映射公式精确换算为物理文件偏移：
   $$\text{FileOffset} = \text{RVA} - \text{Section.VirtualAddress} + \text{Section.PointerToRawData}$$
4. 打印该偏移处的十六进制指令，与反汇编字节严格比对，确保每一处 Patch 均精准命中目标方法边界，不污染邻近元数据。

---

## 4. 总结与致谢

上述历史代码完整记录了从“表面症状压制”走向“根本原因溯源”的逆向工程全过程。
我们鼓励开发者阅读并理解这些失败案例，从中汲取关于 PE 加载机制、Unity 资产序列化、Win32 ABI 与 .NET 字节码控制流重定向的工程经验。
