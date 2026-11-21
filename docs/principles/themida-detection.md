# 外层防护：Themida 原生加壳保护机制分析

## 1. 概述

《Alice in Cradle》(ver0.30) 官方发布的 Windows x86-64 独立版本中，位于游戏根目录下的可执行文件 `AliceInCradle.exe` 体积约为 12.35 MB。该文件并非标准的 Unity Standalone 播放器引导程序，而是被商业加壳工具 Oreans Themida 封装后的保护壳。

## 2. PE 节区特征

使用 `llvm-readobj` 或 `x86_64-w64-mingw32-objdump` 解析 `AliceInCradle.exe` 的节表（Section Table），呈现出鲜明的 Themida 特征：

```text
Section {
  Name: .aic0
  VirtualSize: 0x4B3000
  VirtualAddress: 0x1000
}
Section {
  Name: .aic1
  VirtualSize: 0x75A000
  VirtualAddress: 0x4B4000
}
Section {
  Name: .aic2
  VirtualSize: 0x2000
  VirtualAddress: 0xC0E000
}
```

其标准 `.text`、`.rdata` 等编译节区被完全抹除或重命名为自定义节名，原始入口点（OEP）被重定向至壳代码段中。

## 3. 运行环境探测机制

Themida 在进程初始化的 Ring3 空间执行以下系统特征探测：
1. **CPUID 虚拟化标志位检索**：执行特权/非特权 CPUID 指令，检索 Hypervisor 标志位（如 ECX 第 31 位）及宿主厂商标识（如 `"VMwareVMware"`、`"Microsoft Hv"`、`"KVMKVMKVM"`、`"XenVMMXenVMM"` 等）。
2. **底层硬件与特权指令计时异常**：通过 `RDTSC` 或高精度定时器测量特定指令序列的周期差，若存在 VM-Exit 或模拟器上下文切换带来的延迟开销，则判定为虚拟化宿主。
3. **Wine / Linux 宿主特征枚举**：探测 `ntdll.dll` 中是否存在 Wine 特有的导出符号（如 `wine_get_version`），检查注册表 `HKEY_CURRENT_USER\Software\Wine`，以及检查特定设备驱动文件。

## 4. 拦截表现

一旦探测逻辑命中非物理 Windows 宿主特征，Themida 壳在控制流到达 Unity 引擎之前便会中断执行，调用 Win32 GUI API 弹出系统对话框：

> `"Sorry, this application cannot run under a Virtual Machine"`

在用户点击确认后，程序立即调用 `ExitProcess(0)` 强制自杀退出。这导致任何依托 Wine（包括 Proton、Winlator、Mobox 等）或 QEMU/KVM 虚拟机的环境完全无法启动游戏。

## 5. 解耦思路

虽然 Themida 的反调试与混淆逻辑极度复杂，但 Unity Standalone 架构的核心计算与渲染流水线均封装于外部动态库 `UnityPlayer.dll` 中。

通过逆向分析其原生调用拓扑，`AliceInCradle.exe` 的本质使命仅仅是定位并调用 `UnityPlayer.dll!UnityMain2`。因此，无需耗费算力对抗 Themida 复杂的加壳层，直接剥离壳程序并用纯净的 Win32 引导桩替代，是架构上最优雅且零风险的解耦方案。
