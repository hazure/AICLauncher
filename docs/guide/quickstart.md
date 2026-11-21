# 快速上手指引（QuickStart）

## 1. 适用目标与前置依赖

### 适用版本
- **游戏名称**：《Alice in Cradle》
- **目标版本**：官方独立版 **ver0.30**（Windows x86-64）

### 前置开发环境
- **Python**：Python 3.8 或更高版本（无需任何第三方 pip 库，纯标准库支持）；
- **C 编译器**：支持生成 Win64 PE 的工具链（满足其一即可）：
  - `llvm-mingw`（推荐，跨平台支持最广）
  - `mingw-w64`（包含 `x86_64-w64-mingw32-gcc`）
  - Microsoft Visual C++ (`cl.exe`)

---

## 2. 环境变量配置

为消除脚本内的绝对路径硬编码，工具链统一采用 `AIC_GAME_DIR` 环境变量作为标准入口。

在执行任何操作前，请在终端中导出该变量指向游戏根目录：

```bash
# Linux / Android (Termux) 环境：
export AIC_GAME_DIR="/storage/emulated/0/Documents/AliceInCradle_ver030"

# Windows (命令提示符 CMD) 环境：
set AIC_GAME_DIR=D:\Games\AliceInCradle_ver030

# Windows (PowerShell) 环境：
$env:AIC_GAME_DIR="D:\Games\AliceInCradle_ver030"
```

---

## 3. 五步完整应用流程

### 步骤 1：备份官方原始程序
进入游戏目录，将官方加壳的 `AliceInCradle.exe` 备份：
```bash
cp "$AIC_GAME_DIR/AliceInCradle.exe" "$AIC_GAME_DIR/AliceInCradle.exe.themida.bak"
```

### 步骤 2：编译并部署纯净引导器
使用 Clang 编译纯净零依赖引导器，直接覆盖至游戏根目录：
```bash
x86_64-w64-mingw32-clang -O2 -s -mwindows -nostdlib -Wl,-e,WinMainCRTStartup \
  -o "$AIC_GAME_DIR/AliceInCradle.exe" src/stub_perfect.c -lkernel32
```
*(若使用其他编译器，请参考 [多编译器构建指南](guide/compiler.md))*

### 步骤 3：修补核心托管程序集
运行自动化修补脚本。脚本会自动验证原始字节签名、备份为 `.orig` 并执行 34 字节重定向：
```bash
python3 patch_unsafeAssem.py
```

### 步骤 4：重新同步文件散列表
运行哈希表同步脚本，将修补后的 `unsafeAssem.dll` 新哈希写入校验表：
```bash
python3 update_sha.py
```

### 步骤 5：启动与验证
在 Wine、Proton 或 Android 转译容器中正常启动：
```bash
wine "$AIC_GAME_DIR/AliceInCradle.exe"
```
验证游戏是否直接跳过虚拟机拦截弹窗，正常进入主场景与标题界面。
