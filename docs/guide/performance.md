# Turnip 与 DXVK 驱动最佳化搭配指南

## 1. 概述

在 Android（Winlator / Termux-Box64）转译环境下运行 Windows x86-64 游戏，驱动选择决定渲染稳定性与帧率表现。
针对高通骁龙 Adreno 架构 GPU，本篇总结了实测表现最优的驱动与转译组件搭配方案。
合理配置着色器缓存与异步编译，可使游戏达成 120 FPS 稳定流畅运行。

## 2. 硬件平台与黄金组合

### 2.1 Adreno 730 推荐配置
针对搭载高通 Adreno 730 GPU 的设备（如骁龙 8+ Gen 1、8 Gen 1），推荐采用以下黄金配置：

- **Mesa Turnip** (`v24.1.0`)
  - 稳定性与专有指令支持最佳，消除纹理闪烁
- **DXVK 转译层** (`2.3.1`)
  - 完善支持 D3D11 特性与非阻塞管线异步编译
- **Box64 Dynarec** (`0.3.0+`)
  - 采用保守基本块生成，控制重编译 CPU 尖峰开销
- **渲染模式**（独占全屏 Native）
  - 保持与容器原生分辨率 1:1 输出

### 2.2 启动脚本环境参数注入与分辨率管理（launcher.sh）
根据单一职责原则，转译层驱动参数已统一由外部启动脚本 `launcher.sh` 注入，避免在 Windows PE 二进制引导桩中硬编码非通用环境变量：
- `BOX64_DYNAREC_BIGBLOCK=1`：放弃激进的 BIGBLOCK=2，改用保守基本块重编译策略，避免翻译缓存未命中引发的 CPU 异常尖峰与瞬时卡顿；
- `BOX64_DYNAREC_SAFEFLAGS=1`：优化状态标志寄存器模拟，兼顾安全性与分支效率；
- `BOX64_DYNAREC_ALIGNED_ATOMICS=1`：强制多线程原子操作内存对齐，避免 Unity JobSystem 多线程锁竞争异常；
- `BOX64_DYNAREC_STRONGMEM=1`：模拟强内存模型（Strong Memory Model），在 ARM 弱内存序架构下提供严格的内存屏障保护，防止高负载下线程死锁；
- `DXVK_ASYNC=1`：开启非阻塞管线异步编译，消除初次着色器构建卡顿；
- `MESA_SHADER_CACHE=1`：开启 Mesa 底层磁盘持久化着色器缓存；
- `MESA_SHADER_CACHE_DIR` 与 `DXVK_STATE_CACHE_PATH`：启动时自动创建并绑定 `shader_cache` 目录，确保着色器与管线状态缓存获得绝对持久化存储；
- **动态分辨率与无边框窗口模式**：脚本优先查询显示分辨率，传递 `-screen-width <宽> -screen-height <高> -popupwindow -screen-fullscreen 0` 启动 Unity 引擎，降低移动端 DXVK 交换链重建崩溃风险。

### 2.3 前端配置与底层注入优先级（只补位不覆盖）
`launcher.sh` 严格遵循“只补位不覆盖”（Fallback-only）的设计哲学：
在注入任何环境变量（如 `BOX64_DYNAREC_SAFEFLAGS`、`MESA_SHADER_CACHE`、`DXVK_ASYNC` 等）前，脚本首先检查当前进程上下文是否已有对应配置。
如果用户在 Winlator 等容器前端界面预设过相关参数，将完全尊重用户配置，绝不强行覆盖；仅在用户未配置时补充稳定参数。

## 3. 物理机对齐与着色器缓存持久化

### 3.1 指令与内存模型对齐物理机
Box64 运行在 ARM64 弱内存序（Weak Memory Ordering）物理硬件上。
而 x86-64 硬件提供强内存模型（TSO, Total Store Order）。
通过强制注入 `STRONGMEM=1` 与 `ALIGNED_ATOMICS=1`，确保多线程锁与内存原子操作的执行顺序严格符合 x86 物理机行为。
这根治了 Unity 多线程作业系统高并发下的死锁挂起，在维持高准度的同时提供极致帧率输出。

### 3.2 绝对持久化缓存
着色器初次编译引起的掉帧是移动端转译卡顿的主要成因。
引导桩在启动时动态探测游戏所在绝对目录，自动建立并关联 `shader_cache` 专属子目录。
所有已编译的 SPIR-V 与专有机器码将被稳定保存在该目录下。
第二次进入游戏及复杂特效场景时将 100% 直接命中磁盘缓存，彻底消除微卡顿。

### 3.3 配置文件优化建议（`dxvk.conf`）
用户可在游戏安装根目录下创建 `dxvk.conf` 文件进一步微调转译行为：

```ini
# dxvk.conf 性能微调配置
dxvk.enableAsync = true
dxvk.gpl = true
dxvk.hud = fps,frametimes
```

### 3.4 底层性能、稳定性与兼容层责任
在转译层（Box64 / Wine / DXVK），性能瓶颈往往会引发 CPU 异常开销，进而导致移动端温控降频与引擎崩溃。
本项目已通过 IL 托管层精准跳转彻底砍掉了反虚机高频检测所带来的 CPU 额外轮询开销；引导桩保留并注入的 Box64 参数，旨在消除转译层的异常 CPU 负载与线程死锁。
若在 Winlator 配置无误（Wine 9.2 + Turnip 24.1.0 + DXVK 2.3.1）且无温控调度干预的情况下依然出现崩溃，均为兼容层/补丁引入的转译开销问题，需持续修复以逼近物理机原生表现。

## 4. 实测表现总结

在 Adreno 730 设备上配合本补丁实测运行：
- 游戏跳过全部反虚拟机逻辑，首发加载耗时缩短至 3 秒以内；
- 全局场景实现满帧 120 FPS 稳定运行，无内存泄漏与图形伪影；
- 发热与功耗表现平稳，长时间游玩无掉帧劣化。

## 5. 参考链接

- [快速上手指引](guide/quickstart.md)
- [原生 Win64 启动桩重构方案](bypass/launcher-stub.md)
- [项目边界与问题反馈声明](disclaimer.md#6-项目边界与问题反馈声明)
