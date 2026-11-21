# CIL 字节码控制流重定向方案

## 1. 概述

绕过外层加壳启动 Unity 引擎后，必须在托管代码层解除 `XX.IN::Awake()` 的第二层环境拦截。

本方案采用精准的 CIL 字节码原地重定向技术，仅修改 25 个字节，不破坏程序集整体结构与元数据表。

## 2. 补丁目标与原理分析

### 2.1 核心环境检测重定向（`IN::Awake`）
- **定位方式**：自适应特征码扫描（检索 `SceneCannotLaunch_InvalidEnviroment` 字符串 Token 及其上下文指令链）。
- **原始逻辑**：检测全部完成后在 IL 偏移处校验 `loc.0` 变量：
  ```cil
  IL_02a6: 06 2d 16  ; ldloc.0; brtrue.s IL_02d4
  ```
  若 `loc.0` 为 0 则顺序执行至 `IL_02be`，加载报错场景 `SceneCannotLaunch_InvalidEnviroment`。
- **修补逻辑**：无论 `loc.0` 为何值，将条件跳转指令修改为无条件短跳（`br.s`），直接跳过整个报错场景加载块，并在后续指令槽填入 `nop`（空操作）：
  ```cil
  IL_02a6: 2b 17     ; br.s IL_02d4 (强制跳过报错加载逻辑)
  IL_02a8: 00 ... 00 ; 连续填充 23 字节 NOP
  ```
- **字节比对**：
  - 原始字节（25 字节）：
    `06 2d 16 72 fe e3 00 70 28 65 0a 00 0a 02 28 89 00 00 0a 28 af 01 00 0a 2a`
  - 修补字节（25 字节）：
    `2b 17 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00`

### 2.2 敏感特性隔离说明（`X::get_SENSITIVE`）
- **核心结论**：`get_SENSITIVE` 与 `get_SP_SENSITIVE` 属于游戏内部年龄与内容分级判定，与反虚机无关，**严禁修改**。
- **经验教训**：早期版本曾尝试将属性访问器改写为 `ldc.i4.0; ret`。由于基本块未闭合，导致 Mono JIT 扫描死代码越界，抛出 `InvalidProgramException: IL_0009: pop` 并引发主循环冻结。当前版本已彻底隔离这两处属性。

## 3. 动态特征定位与原子修补引擎（`patch_unsafeAssem.py`）

本仓库提供了本地二进制补丁工具 `patch_unsafeAssem.py`，具备以下工程安全特性：
1. **ECMA-335 元数据动态解析**：按照 CLI 规范解析 `#US`（UserString 堆），动态解析目标报错字符串的 Token 并在扫描时动态生成预期特征码（`expected_orig_bytes`）；
2. **原子化文件替换（Crash-Safe Atomic Patching）**：废弃脆弱的原地 `r+b` 模式，采用同目录临时文件流式改写并使用系统原语 `os.replace` 原子替换，杜绝断电或进程中断导致的半写入损坏；
3. **等长零偏移位移**：修改前后严格保持 25 字节完全等长，不改动 Method Header、节区表及文件尺寸；
4. **写入前熔断防御（Circuit Breaker）**：严格比对原始指令序列与目标 Token，上下文不匹配时立即拒绝写入并抛出明确异常；
5. **校验清单原子同步**：修补完成后重新计算程序集 SHA-256 并原子化同步至 `StreamingAssets/_sha.txt`；
6. **自动化自测套件**：支持 `--test` 命令，在不依赖宿主真机环境的情况下执行单元测试与等长断言。

```bash
# 运行内置自测套件
python3 patch_unsafeAssem.py --test

# 执行本地自动化修补（自动同步 _sha.txt）
python3 patch_unsafeAssem.py
```
