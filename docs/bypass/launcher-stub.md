# 原生 Win64 启动桩重构方案

## 1. 概述

本项目采用原生 Win64 极简引导程序全面替代加壳的 `AliceInCradle.exe`。
设计目标是彻底规避商业保护壳在虚拟化及 Wine 环境下的拦截。
编译生成的可执行文件体积仅约 2.5 KB，具备极高跨平台兼容性。

## 2. 核心设计原则

1. 零运行时依赖：切断 C 运行时库，纯静态链接系统核心 API。
2. GUI 子系统规范：声明 `-mwindows`，启动时不闪烁控制台黑框。
3. ABI 栈对齐：保证 x86-64 架构 16 字节栈对齐并预留影子空间。
4. 命令行接口：向引擎传递 `GetCommandLineA()` 命令行参数。

## 3. 源码实现

完整 C 源码位于 `src/stub_perfect.c`：

```c
#include <windows.h>

// 声明标准字符串指针类型
typedef int (*UnityMain2_t)(HINSTANCE, HINSTANCE, LPSTR, int);

void WinMainCRTStartup(void) {
    // 锚定工作目录至当前程序所在目录
    SetCurrentDirectoryW(L".");

    // 动态装载官方无壳 Unity 引擎核心
    HMODULE hUnity = LoadLibraryW(L"UnityPlayer.dll");
    if (!hUnity) {
        ExitProcess(1);
    }

    // 解析引擎主入口点函数地址
    UnityMain2_t UnityMain2 = 
        (UnityMain2_t)GetProcAddress(hUnity, "UnityMain2");
    if (!UnityMain2) {
        ExitProcess(2);
    }

    // 传递命令行并启动引擎
    int ret = UnityMain2(
        GetModuleHandleW(NULL), NULL, GetCommandLineA(), SW_SHOW);

    ExitProcess(ret);
}
```

## 4. 关键参数规范

反汇编表明，`UnityPlayer.dll`（RVA `0x180817440`）内部直接调用：
`SHELL32.dll!CommandLineToArgvW(LPCWSTR, int*)`。
若传递 ANSI 单字节字符串，相邻字节将被误解为乱码 Unicode。
这会引发参数解析越界或消息循环死锁，因此必须严格传入宽字符。

## 5. 编译产物对比

- 官方加壳文件：体积 12.35 MB，包含 `.aic0` 等自定义混淆节。
- 开源重构引导器：体积 2.50 KB，仅包含标准 `.text` 与 `.idata` 节。
- 依赖项：加壳版依赖特权探测，重构版仅链接系统底层 `KERNEL32.dll`。

## 6. 参考链接

- [多编译器构建指南](guide/compiler.md)
- [快速上手指引](guide/quickstart.md)
- [常见问题与排查指南](guide/faq.md)
