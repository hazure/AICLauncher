# Alice in Cradle (ver0.30) 运行环境与虚拟机检测绕过技术方案

## 1. 概述与项目初衷

本项目源于对计算机系统工程、可执行文件加载机制以及跨平台转译兼容性的技术探索。

本项目的核心初衷并非提供通用的破坏性破解或鼓励绕过保护机制，而是立足于底层二进制逆向与操作系统原理，向技术爱好者与系统级开发者完整呈现 Windows PE 加载流程、C Runtime 最小化解耦、Unity 引擎架构及 .NET/Mono 托管字节码控制流分析的工程全貌。

我们深信，逆向工程的终极目标应当是深入理解软件如何与操作系统交互、探寻异构平台（如 Linux Wine、Android Termux/Box64/Winlator）下的互操作性边界，并在此过程中促进开发者共同正面学习与技术积累，而非用于恶意开发、破坏性对抗或灰色产业链。

---

## 2. 目标程序检测机制深度逆向分析

经对游戏二进制文件的逆向分析，该版本（ver0.30）引入了双层防御架构以阻断非物理机 Windows 环境的执行。整体拦截链分为外层原生 PE 壳与内层托管代码环境指纹分析。

### 2.1 外层防护：Themida 原生加壳保护

游戏根目录下的可执行文件 `AliceInCradle.exe`（体积约为 12.35 MB）并非标准 Unity Standalone 引导器，而是经过商业保护加壳工具 Oreans Themida 处理的二进制文件。

- **PE 节区特征**：可执行文件包含自定义节区 `.aic0`、`.aic1`、`.aic2`。
- **检测机制**：Themida 在进程入口点接管控制流，通过底层系统探测机制识别宿主是否处于虚拟机、模拟器或 Wine 环境（检测 CPUID 虚拟化标志位、特权指令响应、特定设备驱动及 Wine 注册表/导出函数特征）。
- **拦截行为**：一旦判定环境异常，壳层立即调用 Win32 对话框 API 抛出硬编码错误信息：`"Sorry, this application cannot run under a Virtual Machine"`，并在用户确认后直接调用 `ExitProcess` 强制终止，完全阻止控制流向 Unity 引擎核心 `UnityPlayer.dll` 传递。

### 2.2 内层防护：Unity 托管代码环境指纹与完整性校验

若绕过外层加壳程序直接调用 Unity 引擎，控制流进入游戏托管代码空间后，将触发位于核心程序集 `unsafeAssem.dll` 中的第二层检测。

#### 2.2.1 触发位置与调用链
该层检测固化于 `XX.IN::Awake()` 方法（RVA: `0xfb88c`，文件偏移: `0xf9a8c`）中，在主场景渲染初始化前同步执行。

#### 2.2.2 检测逻辑与特征判定
检测逻辑由三大互锁规则组成：
1. **主模块哈希与版本比对**：
   - 通过 `Process.GetCurrentProcess().MainModule.FileName` 获取启动文件路径。
   - 对启动文件进行 SHA-1 哈希计算，强制要求哈希值匹配硬编码字符串：`"41-3D-E6-87-44-73-89-8B-BC-75-DB-30-0D-6D-D4-C4-CB-9F-FE-24"`。
   - 提取文件版本信息，强制要求 `ProductVersion` 匹配字符串：`"2022.3.62f2 (7670c08855a9)"`。
2. **虚拟化与特定平台路径特征探测**：
   - 探测文件系统是否存在 Wine、Android 或移动端转译容器特有路径：
     - `E:\Android\data`
     - `Z:\`（Wine 默认映射的根文件系统挂载点）
     - `C:\Windows\wfm.exe`
     - `Z:\opt\apps\winaddons.exe`
     - `Z:\opt\apps\GPUInfo.exe`
3. **渲染后端与图形驱动特征匹配**：
   - 调用 `SystemInfo.graphicsDeviceName` 获取当前 GPU 字符串并转为小写。
   - 检索是否包含转译驱动关键词：
     - `virgl`（VirGL 虚拟化 GPU）
     - `turnip`（高通 Adreno 的开源 Vulkan 驱动）
     - `vortex`
     - `gamefusion`

#### 2.2.3 拦截表现
任何一项规则命中时，方法将局部标志变量 `loc.0` 置为 0。随后在 IL 偏移 `IL_02bb` 处进行条件跳转判断：
```cil
IL_02bb: ldloc.0
IL_02bc: brtrue.s IL_02d4
IL_02be: ldstr "SceneCannotLaunch_InvalidEnviroment"
IL_02c3: call void SceneManager::LoadScene(string)
IL_02c8: ldarg.0
IL_02c9: call GameObject Component::get_gameObject()
IL_02ce: call void Object::Destroy(Object)
IL_02d3: ret
IL_02d4: call void XX.REG::initReg()
```
未通过检测的分支将直接加载索引为 3 的独立场景 `SceneCannotLaunch_InvalidEnviroment`（资产包 `level3`），屏幕输出：
> *"The app has been stopped because it may have been launched in an unsupported environment. 应用已停止运行，因为它可能在不支持的环境中启动。"*
并将当前游戏对象销毁，阻断进入 `XX.REG::initReg()` 与标题场景 `SceneTitle`。

#### 2.2.4 文件散列链条校验
除上述环境特征外，游戏在 `StreamingAssets/_sha.txt` 中维护了 31 个核心库文件的 SHA-256 校验列表，由 `XX.SHAChecker` 进行完整性轮询，任何对 DLL 的篡改若未同步更新校验表均会导致静默崩溃或拦截。

---

## 3. 绕过方案的技术原理

基于上述逆向分析，绕过方案不应采取破坏性黑盒挂钩，而应在控制流上实现精密外科手术式重定向。

### 3.1 外层 Themida 壳绕过：原生引导器解耦

在 Windows x86-64 体系下，标准 64 位 Unity Standalone 可执行文件本质上仅承担宿主进程装载职责。引擎核心及全部运行时逻辑均封装于 `UnityPlayer.dll` 中，并通过其导出的入口点函数执行：
```c
int UnityMain2(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nShowCmd);
```

- **绕过实现**：
  编写一个符合 Win32 GUI 子系统规范的原生 C 程序（不引入 C Runtime 依赖，纯静态链接 `KERNEL32.dll`）。
  其执行流程如下：
  1. 调用 `SetCurrentDirectoryW(L".")` 确保工作目录锚定于游戏根目录。
  2. 调用 `LoadLibraryW(L"UnityPlayer.dll")` 动态装载无壳的官方 Unity 核心库。
  3. 通过 `GetProcAddress` 获取 `UnityMain2` 函数指针。
  4. 传入 `GetModuleHandleW(NULL)` 与 `GetCommandLineA()` 调用该函数，直接启动 Unity 引擎。
  5. 将编译出的纯净 PE 程序重命名为 `AliceInCradle.exe`。
- **效果**：
  直接绕过外层 Themida 壳及其全部反虚拟机代码，执行体积从 12.35 MB 缩减至 2.5 KB，且消除任何加壳工具在非 Windows 物理机环境下的误报与挂起。

### 3.2 内层托管代码控制流修改：字节码重定向

针对 `unsafeAssem.dll` 中 `XX.IN::Awake()` 的检测阻断逻辑，采取直接修改 IL 指令序列的方式使其失效：

1. **分支强制重定向**：
   在文件偏移 `0xf9d53` 处（对应指令 `IL_02bb`）：
   - 原字节码：`06 2d 16`（`ldloc.0` + `brtrue.s IL_02d4`）
   - 替换为：`2b 17 00`（`br.s IL_02d4` + `nop`）
   - 原理：无论先前的 SHA-1 校验、版本比对或 Wine/Turnip 驱动探测结果如何，代码流执行至此处均无条件短跳至 `IL_02d4`（即正常的游戏业务初始化代码 `call XX.REG::initReg()`）。
2. **报错逻辑擦除**：
   将从 `IL_02be` 至 `IL_02d3` 涉及加载 `SceneCannotLaunch_InvalidEnviroment` 场景及调用 `Object::Destroy` 的 22 字节指令全部覆写为 `nop`（`0x00`）。
3. **敏感等级与内部 SHA 检查清洗**：
   - 将 `XX.X::get_SENSITIVE()` 与 `XX.X::get_SP_SENSITIVE()` 修改为固定返回 `0`（`ldc.i4.0 (0x16)` + `ret (0x2A)` + 填充 `nop`）。
   - 将 `XX.SHAChecker::progress()` 确保返回通过状态，并清空 `ErrorOccured()`。
4. **校验哈希重同步**：
   计算修补后的 `unsafeAssem.dll` 的新 SHA-256 散列值，并覆写写回 `AliceInCradle_Data/StreamingAssets/_sha.txt` 中对应的条目，维持散列链一致性。

---

## 4. 工具使用说明与适用版本

### 4.1 适用版本与环境基准

- **适用游戏**：《Alice in Cradle》
- **目标版本**：`ver0.30`（Windows x86-64 独立版本）
- **验证平台**：
  - Android 容器环境（Termux + Wine / Winlator / Mobox / Box64）
  - Linux 桌面发行版（Wine / Proton）
  - 标准虚拟化环境（KVM / QEMU / VirtualBox / VMware）

*注：若游戏后续发布微版本更新（如 0.31 或更高），由于 DLL 内部偏移与节区变动，直接覆盖可能导致散列校验失效，需重新计算偏移。*

### 4.2 环境变量配置（必须优先配置）

为了保证脚本在不同操作系统（Linux、Android Termux、Windows）及不同安装路径下均可无缝定位游戏目录，本仓库中所有 Python 脚本（包括主补丁脚本、校验更新脚本以及 `tests/` 目录下的全部测试套件）均已彻底移除了任何路径硬编码，统一采用 `AIC_GAME_DIR` 环境变量作为标准入口。

在执行任何修补或测试脚本前，请根据您的操作系统在终端中导出该变量：

```bash
# Linux / Android (Termux) 环境：
export AIC_GAME_DIR="/storage/emulated/0/Documents/AliceInCradle_ver030"

# Windows (命令提示符 CMD) 环境：
set AIC_GAME_DIR=D:\Games\AliceInCradle_ver030

# Windows (PowerShell) 环境：
$env:AIC_GAME_DIR="D:\Games\AliceInCradle_ver030"
```

*注：若未配置该环境变量，各脚本也支持直接将游戏根目录或目标文件路径作为首个命令行参数传入（例如 `python3 patch_unsafeAssem.py /path/to/game`），或者在游戏根目录下直接运行。*

### 4.3 仓库目录结构

```
alice-in-cradle-030-vm-patch/
├── .gitignore                            # Git 追踪忽略规则（排除闭源二进制）
├── README.md                             # 本技术文档
├── patch_unsafeAssem.py                  # 本地自动化 PE/IL 原地修补引擎
├── update_sha.py                         # SHA-256 自动化重新计算与同步脚本
├── src/
│   └── stub_perfect.c                    # 纯净 Win64 启动器原生 C 源码（无 CRT 依赖）
└── tests/                                # 各阶段历史探索与测试归因案例库
    ├── 01_asset_suppression_attempts/    # 资产层修补实验
    ├── 02_dll_hijacking_attempts/        # DLL 劫持侧载实验
    ├── 03_native_engine_analysis/        # 原生引擎反汇编分析
    ├── 04_launcher_stub_evolution/       # 启动器演进史
    ├── 05_pe_il_analysis_tools/          # PE 节区解析与偏移计算工具
    └── README.md                         # 测试套件深度复盘说明
```

### 4.4 补丁应用流程

1. **设置环境变量**：
   按照 4.2 节说明导出 `AIC_GAME_DIR`，确保脚本能正确读取游戏文件。
2. **编译并部署纯净启动器**：
   在具备 `llvm-mingw` 或 `mingw-w64` 的环境下编译源码，并将生成的启动器部署至游戏根目录替换原加壳文件（请提前备份原程序）：
   ```bash
   x86_64-w64-mingw32-clang -O2 -s -mwindows -nostdlib -Wl,-e,WinMainCRTStartup \
     -o "$AIC_GAME_DIR/AliceInCradle.exe" src/stub_perfect.c -lkernel32
   ```
3. **执行本地托管程序集修补**：
   运行本地修补脚本。脚本会自动验证原始字节签名、备份原文件为 `.orig` 并执行字节码重定向：
   ```bash
   python3 patch_unsafeAssem.py
   ```
4. **重新同步文件校验散列表**：
   运行校验表更新脚本，自动计算新修补 DLL 的 SHA-256 并更新写回 `_sha.txt`：
   ```bash
   python3 update_sha.py
   ```
5. **启动游戏**：
   在对应 Wine 或虚拟化容器中启动 `AliceInCradle.exe`，验证是否正常进入标题画面。

---

## 5. 法律声明、使用守则与正版支持倡议

### 5.1 正版软件支持倡议
本项目坚定支持软件原创性与知识产权保护。
本补丁的存在绝非为盗版提供便利，我们强烈呼吁所有研究者与玩家通过官方合法渠道（如 DLsite、Steam 或官方赞助渠道）购买并持有正版游戏授权。请以实际行动支持独立开发者与创作团队，只有健康的商业反馈才能保障优质作品的长久迭代。

### 5.2 合规使用准则
本项目仅供计算机系统原理研究、操作系统兼容性分析及合法持有正版授权用户的合理硬件扩展使用。
严禁将本项目中的任何技术细节、工具或补丁用于商业牟利、未经授权的游戏二次打包分发、黑客对抗、外挂制作或其他侵害权利人合法权益的行为。技术的发展应当服务于正面建设与知识共享。

### 5.3 官方支持免责声明
**严禁向游戏开发团队或官方客服渠道提交由于使用本补丁所引发的任何游戏异常、渲染缺陷、逻辑错误或崩溃日志。**
开发团队不承担在非官方指定硬件及转译/虚拟机环境下提供技术支持的义务。任何因环境兼容性绕过导致的存档损坏、性能波动、图形伪影及系统不稳定性，均由补丁使用者自行承担全部责任与后果。

### 5.4 仓库维权与即刻下线机制
维护者充分尊重软件著作权人的知识产权与合法诉求。
**若收到游戏开发团队、发行方或相关合法权利人的官方通告、版权警示或下架请求，本仓库将会在第一时间无条件永久删除全部内容，并彻底关闭项目不再重新开放。**

### 5.5 维护者终局寄语
技术的真正价值在于打破平台壁垒、加深对底层系统的认知并驱动正面发展，而非对抗与破坏。
请珍惜社区开发与研究成果，保持清醒与克制。
请用户们尊重开发者的劳动成果，尊重正版商业逻辑，尊重人类。
