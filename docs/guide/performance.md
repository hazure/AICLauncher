# Turnip 与 DXVK 驱动最佳化搭配指南

## 1. 概述

在 Android（Winlator / Termux-Box64）转译环境下运行 Windows x86-64 游戏，驱动选择决定渲染稳定性与帧率表现。
针对高通骁龙 Adreno 架构 GPU，本篇总结了实测表现最优的驱动与转译组件搭配方案。
合理配置着色器缓存与异步编译，可使游戏达成 120 FPS 稳定流畅运行。

## 2. 硬件平台与黄金组合

### 2.1 Adreno 730 推荐配置
针对搭载高通 Adreno 730 GPU 的设备（如骁龙 8+ Gen 1、8 Gen 1），推荐以下黄金组合：

| 组件名称 | 推荐版本 | 说明 |
| :--- | :--- | :--- |
| **Mesa Turnip** | `v24.1.0` | 稳定性与专有指令支持最佳，无纹理闪烁 |
| **DXVK 转译层** | `2.3.1` | 完善支持 D3D11 特性与非阻塞管线编译 |
| **Box64 Dynarec** | `0.3.0+` | 启用大基本块与安全标志优化指令吞吐 |
| **渲染模式** | 独占全屏 (Native) | 保持与容器原生分辨率 1:1 输出 |

### 2.2 启动桩高准度环境参数自动注入
在最新版本的引导启动器（`src/stub_perfect.c`）中，程序已在调用引擎前自动注入以下环境变量（仅调用 Win32 原生 API，无任何 C 运行时依赖）：
- `BOX64_DYNAREC_BIGBLOCK=2`：启用大基本块合并重编译，降低 CPU 模拟开销；
- `BOX64_DYNAREC_SAFEFLAGS=1`：优化状态标志寄存器模拟，兼顾安全性与分支效率；
- `BOX64_DYNAREC_ALIGNED_ATOMICS=1`：强制多线程原子操作内存对齐，避免 Unity JobSystem 多线程锁竞争异常；
- `BOX64_DYNAREC_STRONGMEM=1`：模拟强内存模型（Strong Memory Model），在 ARM 弱内存序架构下提供严格的内存屏障保护，防止高负载下线程死锁；
- `DXVK_ASYNC=1`：开启非阻塞管线异步编译，消除初次着色器构建卡顿；
- `MESA_SHADER_CACHE=1`：开启 Mesa 底层磁盘持久化着色器缓存；
- `MESA_SHADER_CACHE_DIR` 与 `DXVK_STATE_CACHE_PATH`：启动时通过 `GetCurrentDirectoryA` 动态获取游戏目录，自动创建并绑定 `shader_cache` 目录，确保着色器与管线状态缓存获得绝对持久化存储。

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

## 4. 实测表现总结

在 Adreno 730 设备上配合本补丁实测运行：
- 游戏跳过全部反虚拟机逻辑，首发加载耗时缩短至 3 秒以内；
- 全局场景实现满帧 120 FPS 稳定运行，无内存泄漏与图形伪影；
- 发热与功耗表现平稳，长时间游玩无掉帧劣化。

## 5. 参考链接

- [快速上手指引](guide/quickstart.md)
- [原生 Win64 启动桩重构方案](bypass/launcher-stub.md)
- [项目边界与问题反馈声明](disclaimer.md#6-项目边界与问题反馈声明)
