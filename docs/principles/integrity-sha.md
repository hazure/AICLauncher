# 文件散列链完整性校验机制分析

## 1. 概述

为了防止玩家或逆向分析者直接修改 Managed 目录下的托管动态链接库（DLL），《Alice in Cradle》(ver0.30) 引入了基于散列表校验的运行期防篡改机制。

## 2. 校验文件与存储格式

校验列表固化在游戏资产目录中：
- **物理路径**：`AliceInCradle_Data/StreamingAssets/_sha.txt`
- **文件编码**：标准 ASCII / UTF-8 纯文本
- **数据结构**：每两行为一个校验单元：
  - 第 1 行：相对于 `AliceInCradle_Data` 的相对文件路径（如 `Managed/unsafeAssem.dll`）
  - 第 2 行：该文件的预期 SHA-256 哈希值（小写 64 字节十六进制字符串）

当前版本共纳入了 31 个核心库文件（包括 `Assembly-CSharp.dll`、`unsafeAssem.dll`、`mscorlib.dll`、`better.dll` 以及原生库 `cri_ware_unity.dll` 等）。

## 3. 校验器架构（`XX.SHAChecker`）

校验逻辑由 `unsafeAssem.dll` 中的 `XX.SHAChecker` 类实现：

```csharp
namespace XX {
    public class SHAChecker {
        public byte error_occured;
        public int current_i;
        private string[] Alines;
        public string path_header;
        public const string sha_checker_file = "_sha.txt";

        public void checkInit() {
            // 读取 StreamingAssets/_sha.txt 并按换行符切割为 Alines
        }

        public bool progress(int time) {
            // 逐个计算文件 SHA-256 并与预期哈希比对
            // 若全部校验通过，返回 false 表示校验结束
            // 若仍在校验，返回 true 表示保持异步轮询
        }
    }
}
```

## 4. 标题状态机联动时序

在标题场景管理器 `SceneTitleTemp` 中，文件校验与首次启动的状态机深度互锁：

```csharp
// SceneTitleTemp::runIRD()
bool sha_in_progress = this.progressShaChecker(loc3 ? 1 : 0);
if (loc1) {
    if (sha_in_progress) {
        // 校验未完成，状态机保持在当前状态空转
    } else {
        // 校验完成，允许流转至 STATE.TOP（主标题状态）
        this.changeState(STATE.TOP);
    }
}
```

## 5. 防护绕过考量

历史上曾有尝试直接修改 `SHAChecker::progress` 的指令使其强行返回 `false`。然而，C# 托管代码的方法头包含精确的 Fat/Tiny Header 结构，直接写入字节码极易破坏方法元数据或引发 Mono JIT 异常；更重要的是，强行修改方法恒返回 `true` 会导致标题状态机无限死锁。

**最佳技术实践**：
尊重原生 `SHAChecker` 的完整验证流程，通过计算并更新 `StreamingAssets/_sha.txt` 中被修补文件的实际哈希，使官方校验器在首帧毫秒级即可自然通过比对并返回 `false`。
