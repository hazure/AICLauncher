# 快速上手指引

## 1. 概述

本指引介绍如何使用本项目工具链完成游戏运行环境检测绕过。
项目基于本地二进制修补技术，全程无需替换第三方商业文件。
通过五个标准化操作步骤，可使游戏在 Wine 或转译容器中正常运行。

## 2. 前置条件

在开始操作前，请确保具备以下环境与文件：
1. 游戏版本：已安装《Alice in Cradle》官方独立版 ver0.30。
2. 解释器环境：已安装 Python 3.8 或更高版本。
3. 编译工具链：已安装 MinGW-w64 或 Clang (llvm-mingw) 交叉编译器。
4. 环境变量配置：在终端中导出 `AIC_GAME_DIR` 环境变量指向游戏根目录。

根据您的操作系统，在终端执行以下命令导出环境变量：

```bash
# Linux / Android (Termux)
export AIC_GAME_DIR="/storage/emulated/0/Documents/AliceInCradle_ver030"
```

```cmd
:: Windows (命令提示符 CMD)
set AIC_GAME_DIR=D:\Games\AliceInCradle_ver030
```

```powershell
# Windows (PowerShell)
$env:AIC_GAME_DIR="D:\Games\AliceInCradle_ver030"
```

## 3. 操作步骤

### 3.1 备份官方原始程序
执行备份命令，保存加壳的原始可执行文件。

```bash
cp "$AIC_GAME_DIR/AliceInCradle.exe" \
   "$AIC_GAME_DIR/AliceInCradle.exe.themida.bak"
```

预期结果：在游戏根目录下生成名为 `AliceInCradle.exe.themida.bak` 的备份。

### 3.2 编译并部署纯净引导器
使用 Clang 交叉编译器编译 `src/stub_perfect.c`。
生成体积仅约 2.5 KB 的无加壳 PE 引导文件，直接覆盖原程序。

```bash
x86_64-w64-mingw32-clang -O2 -s -mwindows -nostdlib \
  -Wl,-e,WinMainCRTStartup \
  -o "$AIC_GAME_DIR/AliceInCradle.exe" \
  src/stub_perfect.c -lkernel32
```

预期结果：游戏根目录下的 `AliceInCradle.exe` 被替换为 2.5 KB 的干净引导器。

### 3.3 执行托管程序集本地修补
运行本地修补脚本，重定向 `unsafeAssem.dll` 中的环境探测分支。

```bash
python3 patch_unsafeAssem.py
```

预期结果：终端输出如下提示，并在同目录下生成 `.orig` 备份：
```text
Target assembly: .../Managed/unsafeAssem.dll
Backup created: .../Managed/unsafeAssem.dll.orig
Successfully patched .../Managed/unsafeAssem.dll
```

### 3.4 重新同步文件散列表
运行散列表同步脚本，将修补后的 DLL 哈希写回校验清单。

```bash
python3 update_sha.py
```

预期结果：终端输出各文件哈希对比状态，并显示更新成功：
```text
[OK]      Managed/Assembly-CSharp.dll
[UPDATED] Managed/unsafeAssem.dll
All hashes match. _sha.txt update complete.
```

### 3.5 启动游戏验证
在 Wine 或目标转译容器中启动新生成的可执行程序。

```bash
wine "$AIC_GAME_DIR/AliceInCradle.exe"
```

预期结果：游戏跳过虚拟机报错弹窗，平滑进入初始年龄确认与主标题场景。

## 4. 示例

以下为完整自动化执行的示例会话记录：

```bash
$ export AIC_GAME_DIR="/data/games/AliceInCradle_ver030"
$ python3 patch_unsafeAssem.py
Target assembly: .../Managed/unsafeAssem.dll
Successfully patched .../Managed/unsafeAssem.dll
$ python3 update_sha.py
Updated 1 entries: ['Managed/unsafeAssem.dll']
_sha.txt update complete.
```

## 5. 常见问题

1. 提示找不到目标动态库：
   请检查是否已在当前终端正确设置 `AIC_GAME_DIR` 环境变量。
2. 启动后画面定格且按钮无样式：
   此现象表明启动器版本过旧或哈希表未更新。请重新执行第 3.2 步与第 3.4 步。
3. 二次启动黑屏：
   在 Wine 下请勿开启全屏模式，建议在设置中维持窗口化运行。

## 6. 参考链接

- [Themida 原生加壳防护机制分析](principles/themida-detection.md)
- [Unity 托管代码环境指纹深度逆向](principles/unity-detection.md)
- [多编译器构建指南](guide/compiler.md)
- [常见问题与排查指南](guide/faq.md)
- [法律声明与正版支持倡议](disclaimer.md)
