# 内层防护：Unity 托管代码环境指纹分析

## 1. 概述

在成功剥离外层 Themida 壳并直接唤醒 Unity 引擎后，游戏执行流将顺利进入 C# 托管代码空间。然而，开发团队在游戏核心程序集 `unsafeAssem.dll` 中固化了第二层独立的环境检测。

## 2. 触发位置与调用链

该层检测位于程序集 `unsafeAssem.dll` 的 `XX.IN::Awake()` 方法中：
- **命名空间**：`XX`
- **所属类**：`IN`（游戏全局输入与系统管理单例）
- **触发时机**：引擎生命周期的 `Awake()` 阶段，在主场景渲染初始化前同步执行。
- **方法 RVA**：`0xfb88c`
- **PE 文件物理偏移**：`0xf9a8c`

## 3. 三大检测规则详解

反编译 CIL 字节码可得，`XX.IN::Awake()` 内部构建了严密的三重特征过滤流水线：

### 3.1 规则 1：主模块 SHA-1 与版本元数据比对
游戏首先获取当前进程启动主模块的绝对路径：
```csharp
Process currentProcess = Process.GetCurrentProcess();
string fileName = currentProcess.MainModule.FileName;
```
随后执行两项强校验：
1. **启动程序 SHA-1 校验**：对当前可执行文件进行 SHA-1 哈希计算，强制要求匹配开发团队发布的原始可执行文件哈希：
   `"41-3D-E6-87-44-73-89-8B-BC-75-DB-30-0D-6D-D4-C4-CB-9F-FE-24"`
2. **ProductVersion 比对**：提取文件的版本资源，强制要求 `ProductVersion` 字符串匹配：
   `"2022.3.62f2 (7670c08855a9)"`

### 3.2 规则 2：跨平台与移动端转译路径探测
游戏通过标准文件系统 API 检索宿主机中是否存在以下特定路径或工具：
- `E:\Android\data`：Android 系统的标准外部存储路径；
- `Z:\`：Wine 环境下默认将 Linux 根文件系统 `/` 映射为 `Z:` 盘的特征路径；
- `C:\Windows\wfm.exe`：部分虚拟化环境或转译容器内注入的辅助组件；
- `Z:\opt\apps\winaddons.exe`
- `Z:\opt\apps\GPUInfo.exe`

### 3.3 规则 3：渲染后端与转译 GPU 驱动关键词匹配
游戏调用 Unity 原生图形接口获取当前活动的 GPU 适配器名称：
```csharp
string graphicsDeviceName = SystemInfo.graphicsDeviceName.ToLower();
```
并在字符串中搜索以下关键词：
- `virgl`：QEMU/KVM 环境下常用的 VirGL 虚拟 3D 加速驱动；
- `turnip`：开源 Mesa 驱动中专门针对高通 Adreno GPU 的 Vulkan 驱动（广泛用于 Android 上的 Wine/Termux/Winlator 转译环境）；
- `vortex` / `gamefusion`：移动端定制转译环境的虚拟显卡标识。

## 4. 拦截机制与控制流

当上述任何一项检测规则命中时，方法将内部标志变量 `loc.0` 置为 0（表示非法环境）。随后在 IL 偏移 `IL_02bb` 处进行条件跳转：

```cil
IL_02bb:  ldloc.0 
IL_02bc:  brtrue.s IL_02d4

IL_02be:  ldstr "SceneCannotLaunch_InvalidEnviroment"
IL_02c3:  call void class [UnityEngine.CoreModule]UnityEngine.SceneManagement.SceneManager::LoadScene(string)
IL_02c8:  ldarg.0 
IL_02c9:  call instance class [UnityEngine.CoreModule]UnityEngine.GameObject class [UnityEngine.CoreModule]UnityEngine.Component::get_gameObject()
IL_02ce:  call void class [UnityEngine.CoreModule]UnityEngine.Object::Destroy(class [UnityEngine.CoreModule]UnityEngine.Object)
IL_02d3:  ret 

IL_02d4:  call void class XX.REG::initReg()
```

### 拦截行为
一旦跳转失败（即未通过检测）：
1. 引擎立即强制加载独立场景 `SceneCannotLaunch_InvalidEnviroment`（资产包 `level3`）；
2. 屏幕输出官方停止运行提示：
   > *"The app has been stopped because it may have been launched in an unsupported environment. 应用已停止运行，因为它可能在不支持的环境中启动。"*
3. 立即调用 `Object.Destroy` 销毁游戏基础管理对象并提前退出（`ret`），彻底阻断进入后续的 `REG::initReg()` 与正常标题场景 `SceneTitle`。
