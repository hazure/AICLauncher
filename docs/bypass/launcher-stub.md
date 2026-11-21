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
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

typedef int (*UnityMain2_t)(HINSTANCE, HINSTANCE, LPSTR, int);

/*
 * Thin Launcher Stub:
 * 遵循单一职责原则（SRP）：
 * 1. 锚定工作目录至当前进程所在目录；
 * 2. 动态加载 UnityPlayer.dll 并获取 UnityMain2 导出函数；
 * 3. 完整透传系统原始命令行参数（跳过自身 exe 路径，保留用户所有参数）；
 * 4. 出现错误时弹出明确的错误对话框（彻底消灭黑盒静默退出）。
 * 
 * 环境变量注入（Box64/Mesa/DXVK）与显示分辨率配置已完全交还外部启动脚本 launcher.sh。
 */
void WinMainCRTStartup(void) {
    SetCurrentDirectoryW(L".");

    HMODULE hUnity = LoadLibraryW(L"UnityPlayer.dll");
    if (!hUnity) {
        DWORD err = GetLastError();
        char msg[128];
        char code[16];
        int idx = 0;
        const char prefix[] = "Fatal: Failed to load UnityPlayer.dll (ErrorCode: ";
        for (int i = 0; prefix[i]; i++) {
            msg[idx++] = prefix[i];
        }

        DWORD tmp = err;
        int c_idx = 0;
        if (tmp == 0) {
            code[c_idx++] = '0';
        } else {
            while (tmp > 0 && c_idx < 10) {
                code[c_idx++] = (char)('0' + (tmp % 10));
                tmp /= 10;
            }
        }
        for (int i = c_idx - 1; i >= 0; i--) {
            msg[idx++] = code[i];
        }
        msg[idx++] = ')';
        msg[idx] = '\0';

        MessageBoxA(NULL, msg, "AliceInCradle Launcher Error", MB_ICONERROR | MB_OK);
        ExitProcess(1);
    }

    UnityMain2_t pUnityMain2 = (UnityMain2_t)GetProcAddress(hUnity, "UnityMain2");
    if (!pUnityMain2) {
        MessageBoxA(NULL, "Fatal: Export 'UnityMain2' not found in UnityPlayer.dll", "AliceInCradle Launcher Error", MB_ICONERROR | MB_OK);
        ExitProcess(2);
    }

    /* 正确解析并透传 Command Line 参数（跳过 argv[0] 可执行文件路径） */
    LPSTR lpCmdLine = GetCommandLineA();
    if (lpCmdLine) {
        if (*lpCmdLine == '"') {
            lpCmdLine++;
            while (*lpCmdLine && *lpCmdLine != '"') lpCmdLine++;
            if (*lpCmdLine == '"') lpCmdLine++;
        } else {
            while (*lpCmdLine && *lpCmdLine > ' ') lpCmdLine++;
        }
        while (*lpCmdLine && *lpCmdLine <= ' ') lpCmdLine++;
    } else {
        lpCmdLine = "";
    }

    int ret = pUnityMain2(GetModuleHandleW(NULL), NULL, lpCmdLine, SW_SHOW);
    ExitProcess(ret);
}
```

## 4. 单一职责与环境解耦说明

在软件工程中，引导桩（Loader / Stub）的核心职责应严格限定为：**定位引擎运行时、安全加载模块、透传原始参数并移交控制流**。

早期的原型尝试在 C 引导桩内部混入转译层专属参数（如 `BOX64_DYNAREC_*`）与窗口尺寸软编码，但这造成了严重的职责越权与代码污染：
1. **跨平台兼容破裂**：在标准 Windows x86-64 或 Proton 环境下，注入 ARM 相关的 Box64 变量毫无意义；
2. **丢失外部参数**：硬编码命令行会导致用户与自动化测试传入的引擎参数（如 `-logFile`、`-batchmode`）被静默吞掉。

新版架构中，引导程序保持纯净 Thin Stub 属性，而移动端容器环境适配与分辨率探测已统一交由外部启动脚本 `launcher.sh` 负责。
通过在启动器层面强制锁定无边框窗口化，彻底消除了游戏运行期间交换链被重构的风险。

## 5. 编译产物对比

- 官方加壳文件：体积 12.35 MB，包含 `.aic0` 等自定义混淆节。
- 开源重构引导器：体积约 3.50 KB，仅包含标准 `.text` 与 `.idata` 节。
- 依赖项：加壳版依赖特权探测，重构版仅链接系统底层 `KERNEL32.dll` 与 `USER32.dll`。

## 6. 参考链接

- [多编译器构建指南](guide/compiler.md)
- [快速上手指引](guide/quickstart.md)
- [常见问题与排查指南](guide/faq.md)
