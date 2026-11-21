# 动态哈希散列表同步原理

## 1. 概述

在对 `unsafeAssem.dll` 执行字节码重定向后，其文件哈希发生变化。由于官方校验器 `XX.SHAChecker` 会在标题画面读取 `StreamingAssets/_sha.txt` 进行全文件哈希核对，若不更新该表，游戏将判定文件被非法篡改。

## 2. 同步方案原理

本方案选择**不篡改校验器逻辑代码，而是动态重构校验数据源**。

### 为什么不直接修改 `SHAChecker` 代码？
1. **方法头易损性**：CLR 托管代码的方法包含 Fat / Tiny Header。直接修改容易破坏 JIT 栈结构或导致运行时异常；
2. **状态机强耦合**：`SceneTitleTemp` 会轮询等待 `progress` 返回 `false`。若篡改不当恒返回 `true`，会造成死锁挂起；
3. **架构正交性**：修改数据文件（`_sha.txt`）比修改代码文件（DLL）具备更高的工程稳定性与可维护性。

## 3. 同步脚本实现（`update_sha.py`）

仓库提供了自动哈希表同步工具 `update_sha.py`，其执行流程如下：
1. 自动定位目标游戏目录中的 `AliceInCradle_Data/StreamingAssets/_sha.txt`；
2. 逐行读取相对路径，计算对应文件的当前实际 SHA-256 哈希值；
3. 比较实际哈希与记录哈希。若不匹配，在内存中更新对应的哈希行；
4. 格式化并写回 `_sha.txt`，确保所有 31 个核心文件哈希全部严丝合缝匹配。

```bash
# 执行哈希表同步
python3 update_sha.py
```

执行输出示例：
```text
Using game data directory: /path/to/AliceInCradle_Data
Using checksum file: /path/to/AliceInCradle_Data/StreamingAssets/_sha.txt
[OK]      Managed/Mono.Posix.dll
[OK]      Managed/Assembly-CSharp.dll
[UPDATED] Managed/unsafeAssem.dll
...
All hashes match. _sha.txt update complete.
```
