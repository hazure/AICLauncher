# T01–T18 代码静态审计报告（AUDIT-REPORT）

- **审计角色**：首席代码审计员（Code Auditor）
- **审计方式**：只读静态审查（未修改任何源码、未执行 `git commit`）
- **审计结论**：**存在多处高危致命缺陷与中危设计缺陷**，当前产物仅达到“原型骨架/单元测试自洽”水平，尚不可直接用于真实游戏环境生产发布。

---

## 一、【高危致命问题】（会导致游戏崩溃、坏档、漏检或红线击穿）

### 1. `plugins/UnmosaicPlugin/UnmosaicPlugin.cs` & `plugins/ModInterface/ModInterface.cs` & `UnmosaicPlugin.csproj`
- **无真实 Harmony 拦截与防空指针（`NullReferenceException`）能力**：
  - `UnmosaicPlugin.csproj` 仅声明了 `net472`，未引用 `BepInEx`、`0Harmony` 及游戏 `Assembly-CSharp.dll`，当前无法独立编译。
  - `PatchRegistry.Register("unmosaic", "nel.MosaicShower", "FnDrawMosaic")`（`UnmosaicPlugin.cs:16`）仅调用 `Console.WriteLine` 打印日志（`ModInterface.cs:18-21`），并未执行 `Harmony.Patch(...)`。
  - **0.31 版本签名变更风险**：代码中既没有使用 `AccessTools.Method(...)` 对目标方法（如 `FnDrawMosaic` / `setTarget`）进行反射判空，也没有 `try/catch` 异常隔离，且未调用 `VersionGate.IsCompatible`。一旦 0.31 修改方法名或参数签名，直接触发 `NullReferenceException` 或 `MissingMethodException` 导致游戏崩溃，无法优雅报错退出。
- **`VersionGate.IsCompatible` 字符串比较逻辑错误**（`ModInterface.cs:34-37`）：
  - 使用 `string.CompareOrdinal(currentVersion, minVersion) >= 0` 进行字典序比较，会导致 `"1.10" < "1.4"` 的经典语义误判。

### 2. `launcher/envbuilder.py` (L17–21) & `launcher/spawner.py` (L17–18)
- **违反“只补位不覆盖”（Fallback-only）原则**：
  - `envbuilder.py` 无条件构造 `env = {"BOX64_DYNAREC_STRONGMEM": strongmem, "DXVK_HUD": hud_debug, "WINEDEBUG": "-all"}`。
  - `spawner.py:17-18` 使用 `env = os.environ.copy(); env.update(extra_env)`，**强行覆盖**了玩家在容器（Winlator / Termux-X11）中手动设置的 `DXVK_HUD`、`BOX64_DYNAREC_STRONGMEM`、`WINEDEBUG` 等环境变量。

### 3. `launcher/signatures.json` (L4) & `launcher/watchdog.py` (L30–37) & `launcher/cli.py` (L24–25)
- **`SIG-01` 漏检 `Gfx command not handled`**：
  - `signatures.json:4` 的正则为 `"图形指令未处理|GL_INVALID_OPERATION|unhandled graphics"`。虽然未加 `^`/`$` 锚点可容忍前后空格与时间戳，但**完全缺失 `Gfx command not handled`**，且全库未约定 `re.IGNORECASE` 大小写不敏感匹配。
- **`LogWatchdog._run()` 未处理日志截断/重建**：
  - `watchdog.py:34-37` 仅单调递增 `self._offset`。当新一局游戏重启并覆盖重建 `Player.log`（`file_size < self._offset`）时，`f.seek(self._offset)` 会直接定位到文件末尾之后，导致整局日志永久漏读。
- **`cli.py` 未接入看门狗与诊断报告**：
  - `cli.py:24-25` 直接 `return proc.wait()`，未启动 `LogWatchdog`，也未在非零退出时调用 `signatures.json` 与 `reporter.py`，监控自愈层与主入口处于脱节状态。

### 4. `launcher/modmanager.py` (L17, L23–25, L30)
- **挂载方式确认**：采用 `shutil.copy2(src_dll, dst_dll)` 复制挂载（非移动、非软链），**程序中途崩溃不会导致 `mods_pool` 内的原始 Mod 文件丢失**。
- **路径遍历（Path Traversal）与零污染击穿风险**：
  - 未校验 `mod_id` 与 `manifest["dll"]` 是否包含 `../` 或绝对路径。若恶意/错误清单传入 `../../Assembly-CSharp.dll`，`shutil.copy2` 会逃逸出 `plugins_dir` 直接覆盖游戏原始文件，击穿“零污染”铁律。
- **非原子复制风险**：
  - 直接 `shutil.copy2` 写入目标 `.dll`，若复制中途崩溃会残留半截损坏的 `.dll`，导致下次启动 BepInEx 加载崩溃。

### 5. `.github/workflows/ci.yml` (L31)
- **静默吞掉测试失败**：
  - `python3 unmosaic.py --test || true` 使用 `|| true` 强行屏蔽非零退出码，违反 `PLAN.md` 第六章（二）及 `G11/X1` 静默失败红线。

---

## 二、【中危设计缺陷】

1. **`plugins/UnmosaicPlugin/UnmosaicPlugin.cs`**：`OnFrame` 与 `OnExit` 未绑定 Unity `Update()` / `OnDestroy()` 生命周期；`Diagnostics.Log` 使用 `Console.WriteLine` 而非 BepInEx `ManualLogSource`，导致日志可能无法写入 `BepInEx/LogOutput.log`。
2. **`launcher/modmanager.py`**：未校验 `manifest.json` 中的 `conflicts` 与 `min_game_version` 字段；当用户从配置中删除某个 Mod 条目时，不会清理 `plugins_dir` 中残留的孤儿 `.dll`。
3. **`launcher/preflight.py`**：`config.py` 中定义的 `[plugin] require_trio = true` 未在 `run_preflight` 中实际校验；`json.load` 未捕获损坏的 `baseline.json` 异常。
4. **`launcher/watchdog.py` (L5)**：存在未使用的导入 `import time`。
5. **`launcher/reporter.py` (L20)**：归档文件名时间戳精度仅到秒（`%Y%m%dT%H%M%SZ`），同秒内连续触发崩溃报告会互相覆盖。

---

## 三、【改进建议】

1. **Harmony 补丁防崩溃与版本比较（`UnmosaicPlugin.cs` / `ModInterface.cs`）**：
   - 使用 `AccessTools.Method(typeof(nel.MosaicShower), "FnDrawMosaic")` 获取 `MethodInfo`，**必须判空**：若为 `null` 则记录 `Logger.LogError` 并直接 `return`，绝不抛出未捕获异常；外层包裹 `try { harmony.Patch(...); } catch (Exception ex) { Logger.LogError(ex); }`。
   - `VersionGate.IsCompatible` 改用 `System.Version.TryParse` 进行语义化版本比较。
2. **环境变量“只补位不覆盖”（`envbuilder.py` / `spawner.py`）**：
   - 将 `spawner.py:18` 的 `env.update(extra_env)` 改为逐项 `env.setdefault(k, v)`（或在 `envbuilder.py` 中接收 `os.environ` 并仅当 key 不存在时写入默认值）。
3. **特征库与看门狗加固（`signatures.json` / `watchdog.py` / `cli.py`）**：
   - `SIG-01` 正则更新为：`"(?i)(图形指令未处理|GL_INVALID_OPERATION|unhandled\\s+graphics|Gfx\\s+command\\s+not\\s+handled)"`。
   - `LogWatchdog._run()` 在 `seek` 前检查 `if os.path.getsize(self.log_path) < self._offset: self._offset = 0`。
   - 移除 `watchdog.py` 未使用的 `import time`，并在 `cli.py` 中接入 `LogWatchdog` 与 `reporter.write_diagnostic_report`。
4. **Mod 挂载原子性与路径安全（`modmanager.py`）**：
   - 校验 `os.path.commonpath([os.path.abspath(plugins_dir), os.path.abspath(dst_dll)]) == os.path.abspath(plugins_dir)`，拒绝路径逃逸。
   - 先复制到 `dst_dll + ".tmp"`，再通过 `os.replace(tmp_dll, dst_dll)` 原子替换。
5. **CI 与归档细节（`ci.yml` / `reporter.py`）**：
   - 移除 `ci.yml:31` 的 `|| true`，让测试失败真实阻断流水线。
   - `reporter.py` 归档文件名增加微秒或短随机后缀（`%Y%m%dT%H%M%S_%fZ`）。
