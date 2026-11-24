# AliceInCradle 安卓端兼容补丁

[![Target: Alice in Cradle v0.30](https://img.shields.io/badge/Target-v0.30-success.svg)]()
[![Platform: Android / Wine](https://img.shields.io/badge/Platform-Android%20%7C%20Wine-orange.svg)]()
[![Docs](https://img.shields.io/badge/Docs-在线文档-brightgreen.svg)](https://hazure.github.io/AliceInCradle-Android-Compat-Patch/)
[![Branch: nocheck_dev (Experimental)](https://img.shields.io/badge/Branch-nocheck__dev%20(Experimental)-red.svg)]()

用于让《Alice in Cradle》（ver0.30，Windows 版）在安卓端容器（Winlator、Termux-X11、Box64 + Wine 等）下正常启动和稳定运行的本地兼容补丁与辅助脚本。

> [!CAUTION]
> **当前分支：`nocheck_dev`（超前线 · 未验证实验分支）**
> 存放未经完整验证的实验性改动，最不稳定，可能出现崩溃或异常。**使用前请务必备份游戏文件和存档，不要用于日常游玩**；正常使用请切回 `main` 或 `dev`。

## 包含内容

原版 Windows 游戏在安卓转译环境（Wine / Box64）中运行容易遇到启动拦截、闪退或卡顿。本仓库不包含任何游戏文件，只提供本地修补与启动所需的脚本和源码：

- `src/stub_perfect.c`：精简启动器源码，编译后替换 `AliceInCradle.exe`，用于兼容安卓 Wine/Box64 启动并透传命令行参数。
- `patch_unsafeAssem.py`：本地修补 `unsafeAssem.dll` 的环境校验，自动备份 `.orig` 并同步 `StreamingAssets/_sha.txt`（支持 `--target` 和 `--force-skip-sha` 兼容第三方 Mod）。
- `launcher.sh`：安卓/Wine 容器启动脚本，自动补全 Box64、DXVK 和着色器缓存配置（不覆盖 Winlator 中已设好的参数）。
- `unmosaic.py`：可选去马赛克脚本，修补 `Assembly-CSharp.dll` 并同步 `_sha.txt`（支持 `--restore` 还原）。
- `update_sha.py`：单独重算并同步 `StreamingAssets/_sha.txt`。

## 分支说明

- **`main`（稳定主线）**：经过测试的稳定版本，日常使用推荐。
- **`dev`（经验证的前线分支）**：已完成基础验证的新功能和优化，合入 `main` 前会先放这里。
- **`nocheck_dev`（超前线实验分支 · 最不稳定 · 当前分支）**：未验证的实验性功能，使用前请先备份游戏和存档。

## 简单用法

先设置游戏目录路径，再替换启动器并打补丁：

```bash
export AIC_GAME_DIR="/path/to/AliceInCradle_ver030"

# 1. 编译精简启动器替换原 AliceInCradle.exe
x86_64-w64-mingw32-clang -O2 -s -mwindows -nostdlib \
  -Wl,-e,WinMainCRTStartup \
  -o "$AIC_GAME_DIR/AliceInCradle.exe" \
  src/stub_perfect.c -lkernel32 -luser32

# 2. 打兼容补丁（自动同步 _sha.txt）
python3 patch_unsafeAssem.py

# 3. 启动游戏（或直接在 Winlator 中启动）
./launcher.sh
```

其他常用命令：

```bash
python3 unmosaic.py          # 可选：去马赛克（--restore 可还原）
python3 update_sha.py        # 可选：全量同步 _sha.txt
```

## 详细文档

完整操作步骤、驱动调优、编译方法、常见问题和原理说明都在 [`docs/`](docs/) 目录（也可浏览 [在线文档](https://hazure.github.io/AliceInCradle-Android-Compat-Patch/)）：

- [快速上手指南](docs/guide/quickstart.md)
- [安卓驱动与性能设置（Turnip / DXVK / Box64）](docs/guide/performance.md)
- [编译器构建说明](docs/guide/compiler.md)
- [常见问题排查（FAQ）](docs/guide/faq.md)
- [原理与技术说明](docs/README.md)
- [使用声明与免责说明](docs/disclaimer.md)

## 注意事项

- 请支持并通过官方渠道购买正版游戏，本仓库不提供任何游戏本体或资源文件。
- 在安卓转译环境下使用本补丁遇到的任何异常或崩溃，**请勿向游戏官方反馈或提交日志**。
