# 多编译器构建指南

## 1. 概述

引导程序 `src/stub_perfect.c` 采用极简 Win32 C 语言编写，实现了零 C 运行时库（Zero-CRT）依赖。不同操作系统与编译器套件均能轻松编译出仅 2.5 KB 的标准 PE 可执行文件。

## 2. 编译命令汇总

根据您当前的构建环境，选择以下命令之一执行：

### 2.1 Clang (llvm-mingw)
`llvm-mingw` 具备极佳的跨平台与标准指令支持，是 Android (Termux) 及 Linux 交叉编译的首选：
```bash
x86_64-w64-mingw32-clang -O2 -s -mwindows -nostdlib -Wl,-e,WinMainCRTStartup \
  -o "$AIC_GAME_DIR/AliceInCradle.exe" src/stub_perfect.c -lkernel32
```

### 2.2 GCC (mingw-w64)
在多数 Linux 发行版（如 Ubuntu/Debian: `apt install mingw-w64`）上可直接使用 GCC：
```bash
x86_64-w64-mingw32-gcc -O2 -s -mwindows -nostdlib -e WinMainCRTStartup \
  -o "$AIC_GAME_DIR/AliceInCradle.exe" src/stub_perfect.c -lkernel32
```

### 2.3 MSVC (Visual Studio x64 Native Tools Command Prompt)
在原生 Windows 开发者终端中，调用微软原生编译器 `cl.exe`：
```cmd
cl.exe /O2 /GS- /W3 src\stub_perfect.c /link /SUBSYSTEM:WINDOWS /ENTRY:WinMainCRTStartup /NODEFAULTLIB kernel32.lib /OUT:"%AIC_GAME_DIR%\AliceInCradle.exe"
```

## 3. 关键编译参数解析

- `-nostdlib` / `/NODEFAULTLIB`：完全切断 C 运行时库（CRT），杜绝链接 `msvcrt.dll` 或 `ucrtbase.dll`；
- `-mwindows` / `/SUBSYSTEM:WINDOWS`：声明目标子系统为 Windows GUI，启动时不分配或闪烁 Console 终端黑框；
- `-Wl,-e,WinMainCRTStartup` / `/ENTRY:WinMainCRTStartup`：指定自定义纯净入口点，绕过 CRT 初始化例程；
- `-lkernel32` / `kernel32.lib`：仅引入 Windows 底层操作系统核心库，解析 `LoadLibraryW`、`GetProcAddress` 与 `ExitProcess`。
