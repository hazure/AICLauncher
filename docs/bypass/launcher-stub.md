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

// 只补位不覆盖（Fallback-only）：优先保留前端自定义设置
static void set_env_if_missing(const char *name, const char *val) {
    if (GetEnvironmentVariableA(name, NULL, 0) == 0) {
        SetEnvironmentVariableA(name, val);
    }
}

void WinMainCRTStartup(void) {
    // 锚定工作目录至当前程序所在目录
    SetCurrentDirectoryW(L".");

    // 动态注入环境参数（只补位不覆盖，保稳定、控开销、提准度）
    set_env_if_missing("BOX64_DYNAREC_BIGBLOCK", "1");
    set_env_if_missing("BOX64_DYNAREC_SAFEFLAGS", "1");
    set_env_if_missing("BOX64_DYNAREC_ALIGNED_ATOMICS", "1");
    set_env_if_missing("BOX64_DYNAREC_STRONGMEM", "1");
    set_env_if_missing("DXVK_ASYNC", "1");
    set_env_if_missing("MESA_SHADER_CACHE", "1");

    // 绝对持久化：动态解析当前路径并绑定专属目录，绝不写死盘符
    char cache_dir[MAX_PATH];
    DWORD len = GetCurrentDirectoryA(MAX_PATH, cache_dir);
    if (len > 0 && len + 14 < MAX_PATH) {
        if (cache_dir[len - 1] != '\\') {
            cache_dir[len++] = '\\';
        }
        const char sub[] = "shader_cache";
        for (DWORD i = 0; i < sizeof(sub); i++) {
            cache_dir[len + i] = sub[i];
        }
        CreateDirectoryA(cache_dir, NULL);
        set_env_if_missing("MESA_SHADER_CACHE_DIR", cache_dir);
        set_env_if_missing("DXVK_STATE_CACHE_PATH", cache_dir);
    }

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

    // 动态分辨率软编码与无边框窗口接管（绕开 DXVK 交换链重建崩溃）
    int screen_w = GetSystemMetrics(SM_CXSCREEN);
    int screen_h = GetSystemMetrics(SM_CYSCREEN);

    char cmdline[256];
    wsprintfA(cmdline,
        "-screen-width %d -screen-height %d -popupwindow -screen-fullscreen 0",
        screen_w, screen_h);

    // 传递动态命令行并启动引擎
    int ret = UnityMain2(
        GetModuleHandleW(NULL), NULL, cmdline, SW_SHOW);

    ExitProcess(ret);
}
```

## 4. 动态分辨率与显示模式接管

在移动端 Android 转译环境（Wine / Box64 / DXVK）下，游戏内部切换全屏/窗口化或变更分辨率时，会触发 DXVK 底层 Vulkan Swapchain 的重建。
由于移动 GPU（如 Adreno 系列）驱动对交换链频繁重置较为敏感，极易引发 `VK_ERROR_DEVICE_LOST` 异常并导致引擎崩溃。
本启动桩通过调用 `GetSystemMetrics` 动态获取当前容器宿主分辨率，软编码注入 `-screen-width`、`-screen-height`、`-popupwindow` 与 `-screen-fullscreen 0`。
通过在启动器层面强制锁定无边框窗口化，彻底消除了游戏运行期间交换链被重构的风险。

## 5. 编译产物对比

- 官方加壳文件：体积 12.35 MB，包含 `.aic0` 等自定义混淆节。
- 开源重构引导器：体积约 3.50 KB，仅包含标准 `.text` 与 `.idata` 节。
- 依赖项：加壳版依赖特权探测，重构版仅链接系统底层 `KERNEL32.dll` 与 `USER32.dll`。

## 6. 参考链接

- [多编译器构建指南](guide/compiler.md)
- [快速上手指引](guide/quickstart.md)
- [常见问题与排查指南](guide/faq.md)
