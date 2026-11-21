# Unity 托管代码环境指纹深度逆向

## 1. 概述

游戏解耦加壳外壳后，控制流进入 Unity 引擎托管空间。
核心程序集 `unsafeAssem.dll` 中固化了第二层独立检测。
若判定环境异常，将阻止进入正常主场景。

## 2. 触发位置与调用链

该检测固化于 `unsafeAssem.dll` 的 `XX.IN::Awake()` 方法中：
- 命名空间：`XX`
- 所属类：`IN`（输入与基础系统控制单例）
- 触发时机：引擎主场景唤醒阶段同步执行
- 方法 RVA：`0xfb88c`
- PE 物理偏移：`0xf9a8c`

## 3. 三大环境探测规则

反汇编表明，`XX.IN::Awake()` 部署了三重检测规则：

### 3.1 规则 1：主模块哈希与版本比对
游戏首先提取当前进程启动主模块的绝对路径。
随后计算启动文件的 SHA-1 哈希，要求匹配硬编码散列值：
`"41-3D-E6-87-44-73-89-8B-BC-75-DB-30-0D-6D-D4-C4-CB-9F-FE-24"`。
同时比对文件的 `ProductVersion` 必须匹配 `"2022.3.62f2"`。

### 3.2 规则 2：转译环境与特征路径检索
游戏检测文件系统中是否存在如下移动端与 Wine 挂载路径：
- `E:\Android\data`：Android 系统外部存储目录
- `Z:\`：Wine 默认映射 Linux 根目录的特征盘符
- `C:\Windows\wfm.exe`：部分虚拟化环境注入组件
- `Z:\opt\apps\winaddons.exe`
- `Z:\opt\apps\GPUInfo.exe`

### 3.3 规则 3：图形驱动关键词匹配
游戏调用 `SystemInfo.graphicsDeviceName` 获取当前 GPU 字符串。
检索字符串是否包含以下虚拟化与移动驱动关键词：
- `virgl`：VirGL 虚拟化 3D 加速驱动
- `turnip`：高通 Adreno 开源 Vulkan 驱动
- `vortex` / `gamefusion`：移动端转译容器显卡标识

## 4. 拦截表现与控制流

当任一探测规则命中时，方法将局部标志 `loc.0` 置 0。
随后在 `IL_02bb` 处进行条件跳转：

```cil
IL_02bb:  ldloc.0 
IL_02bc:  brtrue.s IL_02d4

IL_02be:  ldstr "SceneCannotLaunch_InvalidEnviroment"
IL_02c3:  call void SceneManager::LoadScene(string)
IL_02c8:  ldarg.0 
IL_02c9:  call GameObject Component::get_gameObject()
IL_02ce:  call void Object::Destroy(Object)
IL_02d3:  ret 

IL_02d4:  call void XX.REG::initReg()
```

拦截触发时，引擎加载报错场景 `SceneCannotLaunch_InvalidEnviroment`。
屏幕输出停止运行警告，并销毁全局管理对象退出。

## 5. 参考链接

- [Themida 原生加壳防护机制分析](principles/themida-detection.md)
- [CIL 字节码控制流重定向方案](bypass/bytecode-patch.md)
- [快速上手指引](guide/quickstart.md)
