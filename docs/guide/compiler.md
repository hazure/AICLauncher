# 多编译器构建指南

## 1. 概述

本项目启动桩 `src/stub_perfect.c` 采用纯 Win32 C 语言编写。
代码实现零 C 运行时（Zero-CRT）依赖，编译体积仅约 2.5 KB。
开发者可在不同操作系统中自由选用主流编译套件完成构建。

## 2. 前置条件

1. 源码路径：确认仓库根目录下存在 `src/stub_perfect.c` 文件。
2. 目标路径：已导出 `AIC_GAME_DIR` 环境变量，指向有效目录。
3. 工具链支持：系统中已安装并配置以下任意一种编译器。

## 3. 操作步骤

### 3.1 使用 Clang (llvm-mingw) 编译
在 Linux、macOS 或 Android (Termux) 终端中执行交叉编译命令。

```bash
x86_64-w64-mingw32-clang -O2 -s -mwindows -nostdlib \
  -Wl,-e,WinMainCRTStartup \
  -o "$AIC_GAME_DIR/AliceInCradle.exe" \
  src/stub_perfect.c -lkernel32 -luser32
```

预期结果：在目标目录生成约 3.5 KB 的 `AliceInCradle.exe` 文件。

### 3.2 使用 GCC (mingw-w64) 编译
在安装有 GCC 交叉编译环境的 Linux 或 MSYS2 系统中执行构建。

```bash
x86_64-w64-mingw32-gcc -O2 -s -mwindows -nostdlib \
  -e WinMainCRTStartup \
  -o "$AIC_GAME_DIR/AliceInCradle.exe" \
  src/stub_perfect.c -lkernel32 -luser32
```

预期结果：生成功能完全相同的原生 Win64 PE 文件。

### 3.3 使用 MSVC (Visual Studio) 编译
在 Windows 的 `x64 Native Tools Command Prompt` 终端中执行。

```cmd
cl.exe /O2 /GS- /W3 src\stub_perfect.c ^
  /link /SUBSYSTEM:WINDOWS /ENTRY:WinMainCRTStartup ^
  /NODEFAULTLIB kernel32.lib user32.lib ^
  /OUT:"%AIC_GAME_DIR%\AliceInCradle.exe"
```

预期结果：MSVC 链接器成功输出无默认库依赖的 GUI 可执行文件。

## 4. 关键编译参数说明

- `-nostdlib` / `/NODEFAULTLIB`：
  完全切断 C 运行时库，杜绝链接 `msvcrt.dll` 或 `ucrtbase.dll`。
- `-mwindows` / `/SUBSYSTEM:WINDOWS`：
  声明目标子系统为 Win32 GUI，启动时不分配或闪烁控制台黑框。
- `-e WinMainCRTStartup` / `/ENTRY:WinMainCRTStartup`：
  指定自定义纯净入口点，绕过 CRT 初始化开销。
- `-lkernel32 -luser32` / `kernel32.lib user32.lib`：
  仅静态导入 Windows 系统底层核心动态库（Kernel32 与 User32）。

## 5. 常见问题

1. 提示缺少 `WinMainCRTStartup` 符号：
   请检查是否遗漏了入口点指定参数 `-Wl,-e` 或 `/ENTRY`。
2. 编译时报错 `undefined reference to LoadLibraryW` 或 `GetSystemMetrics`：
   请确保在链接命令末尾保留了 `-lkernel32 -luser32` 选项。

## 6. 参考链接

- [原生 Win64 启动桩重构设计](bypass/launcher-stub.md)
- [快速上手指引](guide/quickstart.md)
- [常见问题与排查指南](guide/faq.md)
