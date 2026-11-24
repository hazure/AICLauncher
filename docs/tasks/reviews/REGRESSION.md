# N1–N10 拦截能力回归测试报告（REGRESSION）

- 执行时间：2026-11-25 01:55:03
- 拦截率：**10/10 (100%)**

| 编号 | 测试场景 | 期望结果 | 判定 |
| :--- | :--- | :--- | :--- |
| N1 | 删掉一张卡的 plan_ref | REJECT ([C7]) | [PASS] |
| N2 | write_paths 外改一个文件 | REJECT (G1/R1) | [PASS] |
| N3 | AC 输出手写伪造 | REJECT ([R2]) | [PASS] |
| N4 | 关闭插件后哈希不一致 | FREEZE ([R3]) | [PASS] |
| N5 | 引入 x86_64 .so | REJECT (G4/R5) | [PASS] |
| N6 | 塞入明文 token | FREEZE ([R7]) | [PASS] |
| N7 | rollback 写成"手动还原" | REJECT ([C6]) | [PASS] |
| N8 | 同组两卡 write_paths 有交集 | REJECT ([C5]) | [PASS] |
| N9 | 验收依赖本机绝对路径 | REJECT (G9/X3) | [PASS] |
| N10 | 加一个空 except | REJECT (G11/X1) | [PASS] |

## 逐项命令行回显与实证

### N1：删掉一张卡的 plan_ref — `[PASS]`
- **期望**：`REJECT ([C7])`
- **命令**：`python3 scripts/adjudicate.py --admit-all docs/tasks/cards --out docs/tasks/reviews/ADMISSION-n1.md`
```text
VERDICT: REJECT
CARD: ADMISSION (1 cards)
REASON:
  - [C7] T01: plan_ref missing subsection+deliverable: ''
REQUIRED_FIX:
  1. Fix T01 (C7): plan_ref missing subsection+deliverable: ''
```

### N2：write_paths 外改一个文件 — `[PASS]`
- **期望**：`REJECT (G1/R1)`
- **命令**：`python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md`
```text
VERDICT: REJECT
CARD: T01
ROUND: 1
GATE_RESULTS:
  [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log', 'outside.txt']
  [PASS] G2: all 1 ACs verified with timestamps & output
  [PASS] G3: protected baseline hashes match
  [PASS] G4: no .so artifacts in diff
  [PASS] G5: no rootfs entity or >50MB file
  [PASS] G6: no plaintext secrets in diff or untracked files
  [PASS] G7: rollback field & restore.sh --check ok
  [PASS] G8: all ACs verified idempotent (--twice)
  [PASS] G9: no hardcoded local paths in acceptance cmds
  [PASS] G10: no undeclared dependencies in diff
  [PASS] G11: no silent failure patterns in diff or untracked .py
  [PASS] G12: no debug leftovers in diff
REASON:
  - [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log', 'outside.txt']
REQUIRED_FIX:
  1. Resolve [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log', 'outside.txt']
RISK_NOTE: Monitor runtime behavior on target Android/Termux-X11 triad.
```

### N3：AC 输出手写伪造 — `[PASS]`
- **期望**：`REJECT ([R2])`
- **命令**：`python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md`
```text
VERDICT: REJECT
CARD: T01
ROUND: 1
GATE_RESULTS:
  [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  [FAIL] G2: [R2] missing timestamps for AC-T01-1
  [PASS] G3: protected baseline hashes match
  [PASS] G4: no .so artifacts in diff
  [PASS] G5: no rootfs entity or >50MB file
  [PASS] G6: no plaintext secrets in diff or untracked files
  [PASS] G7: rollback field & restore.sh --check ok
  [PASS] G8: all ACs verified idempotent (--twice)
  [PASS] G9: no hardcoded local paths in acceptance cmds
  [PASS] G10: no undeclared dependencies in diff
  [PASS] G11: no silent failure patterns in diff or untracked .py
  [PASS] G12: no debug leftovers in diff
REASON:
  - [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  - [FAIL] G2: [R2] missing timestamps for AC-T01-1
REQUIRED_FIX:
  1. Resolve [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  2. Resolve [FAIL] G2: [R2] missing timestamps for AC-T01-1
RISK_NOTE: Monitor runtime behavior on target Android/Termux-X11 triad.
```

### N4：关闭插件后哈希不一致 — `[PASS]`
- **期望**：`FREEZE ([R3])`
- **命令**：`python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md`
```text
VERDICT: FREEZE
CARD: T01
ROUND: 1
GATE_RESULTS:
  [FAIL] G1: G1/R1 touched forbidden_paths: ['src/stub_perfect.c']
  [PASS] G2: all 1 ACs verified with timestamps & output
  [FAIL] G3: [R3] baseline hash drift (zero-pollution breach): ['src/stub_perfect.c']
  [PASS] G4: no .so artifacts in diff
  [PASS] G5: no rootfs entity or >50MB file
  [PASS] G6: no plaintext secrets in diff or untracked files
  [PASS] G7: rollback field & restore.sh --check ok
  [PASS] G8: all ACs verified idempotent (--twice)
  [PASS] G9: no hardcoded local paths in acceptance cmds
  [PASS] G10: no undeclared dependencies in diff
  [PASS] G11: no silent failure patterns in diff or untracked .py
  [PASS] G12: no debug leftovers in diff
REASON:
  - [FAIL] G1: G1/R1 touched forbidden_paths: ['src/stub_perfect.c']
  - [FAIL] G3: [R3] baseline hash drift (zero-pollution breach): ['src/stub_perfect.c']
REQUIRED_FIX:
  1. Resolve [FAIL] G1: G1/R1 touched forbidden_paths: ['src/stub_perfect.c']
  2. Resolve [FAIL] G3: [R3] baseline hash drift (zero-pollution breach): ['src/stub_perfect.c']
RISK_NOTE: Monitor runtime behavior on target Android/Termux-X11 triad.
```

### N5：引入 x86_64 .so — `[PASS]`
- **期望**：`REJECT (G4/R5)`
- **命令**：`python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md`
```text
VERDICT: REJECT
CARD: T01
ROUND: 1
GATE_RESULTS:
  [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  [PASS] G2: all 1 ACs verified with timestamps & output
  [PASS] G3: protected baseline hashes match
  [FAIL] G4: G4/R5 lib/x86_64/libbad.so is not AArch64 ELF
  [PASS] G5: no rootfs entity or >50MB file
  [PASS] G6: no plaintext secrets in diff or untracked files
  [PASS] G7: rollback field & restore.sh --check ok
  [PASS] G8: all ACs verified idempotent (--twice)
  [PASS] G9: no hardcoded local paths in acceptance cmds
  [PASS] G10: no undeclared dependencies in diff
  [PASS] G11: no silent failure patterns in diff or untracked .py
  [PASS] G12: no debug leftovers in diff
REASON:
  - [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  - [FAIL] G4: G4/R5 lib/x86_64/libbad.so is not AArch64 ELF
REQUIRED_FIX:
  1. Resolve [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  2. Resolve [FAIL] G4: G4/R5 lib/x86_64/libbad.so is not AArch64 ELF
RISK_NOTE: Monitor runtime behavior on target Android/Termux-X11 triad.
```

### N6：塞入明文 token — `[PASS]`
- **期望**：`FREEZE ([R7])`
- **命令**：`python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md`
```text
VERDICT: FREEZE
CARD: T01
ROUND: 1
GATE_RESULTS:
  [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  [PASS] G2: all 1 ACs verified with timestamps & output
  [PASS] G3: protected baseline hashes match
  [PASS] G4: no .so artifacts in diff
  [PASS] G5: no rootfs entity or >50MB file
  [FAIL] G6: [R7] potential plaintext secret in git diff
  [PASS] G7: rollback field & restore.sh --check ok
  [PASS] G8: all ACs verified idempotent (--twice)
  [PASS] G9: no hardcoded local paths in acceptance cmds
  [PASS] G10: no undeclared dependencies in diff
  [PASS] G11: no silent failure patterns in diff or untracked .py
  [PASS] G12: no debug leftovers in diff
REASON:
  - [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  - [FAIL] G6: [R7] potential plaintext secret in git diff
REQUIRED_FIX:
  1. Resolve [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  2. Resolve [FAIL] G6: [R7] potential plaintext secret in git diff
RISK_NOTE: Monitor runtime behavior on target Android/Termux-X11 triad.
```

### N7：rollback 写成"手动还原" — `[PASS]`
- **期望**：`REJECT ([C6])`
- **命令**：`python3 scripts/adjudicate.py --admit-all docs/tasks/cards --out docs/tasks/reviews/ADMISSION-n7.md`
```text
VERDICT: REJECT
CARD: ADMISSION (1 cards)
REASON:
  - [C6] T01: rollback is vague/manual: '手动还原'
REQUIRED_FIX:
  1. Fix T01 (C6): rollback is vague/manual: '手动还原'
```

### N8：同组两卡 write_paths 有交集 — `[PASS]`
- **期望**：`REJECT ([C5])`
- **命令**：`python3 scripts/adjudicate.py --admit-all docs/tasks/cards --out docs/tasks/reviews/ADMISSION-n8.md`
```text
VERDICT: REJECT
CARD: ADMISSION (2 cards)
REASON:
  - [C5] T01/T02: overlap in batch4_modules: {'shared.py'}
REQUIRED_FIX:
  1. Fix T01/T02 (C5): overlap in batch4_modules: {'shared.py'}
```

### N9：验收依赖本机绝对路径 — `[PASS]`
- **期望**：`REJECT (G9/X3)`
- **命令**：`python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md`
```text
VERDICT: REJECT
CARD: T01
ROUND: 1
GATE_RESULTS:
  [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  [PASS] G2: all 1 ACs verified with timestamps & output
  [PASS] G3: protected baseline hashes match
  [PASS] G4: no .so artifacts in diff
  [PASS] G5: no rootfs entity or >50MB file
  [PASS] G6: no plaintext secrets in diff or untracked files
  [PASS] G7: rollback field & restore.sh --check ok
  [PASS] G8: all ACs verified idempotent (--twice)
  [FAIL] G9: G9/X3 hardcoded local path in AC-T01-1: ls /data/data/com.termux/files/home >/dev/null 2>&1 || true
  [PASS] G10: no undeclared dependencies in diff
  [PASS] G11: no silent failure patterns in diff or untracked .py
  [PASS] G12: no debug leftovers in diff
REASON:
  - [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  - [FAIL] G9: G9/X3 hardcoded local path in AC-T01-1: ls /data/data/com.termux/files/home >/dev/null 2>&1 || true
REQUIRED_FIX:
  1. Resolve [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  2. Resolve [FAIL] G9: G9/X3 hardcoded local path in AC-T01-1: ls /data/data/com.termux/files/home >/dev/null 2>&1 || true
RISK_NOTE: Monitor runtime behavior on target Android/Termux-X11 triad.
```

### N10：加一个空 except — `[PASS]`
- **期望**：`REJECT (G11/X1)`
- **命令**：`python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md`
```text
VERDICT: REJECT
CARD: T01
ROUND: 1
GATE_RESULTS:
  [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  [PASS] G2: all 1 ACs verified with timestamps & output
  [PASS] G3: protected baseline hashes match
  [PASS] G4: no .so artifacts in diff
  [PASS] G5: no rootfs entity or >50MB file
  [PASS] G6: no plaintext secrets in diff or untracked files
  [PASS] G7: rollback field & restore.sh --check ok
  [PASS] G8: all ACs verified idempotent (--twice)
  [PASS] G9: no hardcoded local paths in acceptance cmds
  [PASS] G10: no undeclared dependencies in diff
  [FAIL] G11: G11/X1 silent failure pattern in bad_mod.py: except:
  [PASS] G12: no debug leftovers in diff
REASON:
  - [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  - [FAIL] G11: G11/X1 silent failure pattern in bad_mod.py: except:
REQUIRED_FIX:
  1. Resolve [FAIL] G1: G1/R1 changed files outside write_paths: ['docs/baseline/baseline.json', 'docs/tasks/cards/T01.yaml', 'out.log']
  2. Resolve [FAIL] G11: G11/X1 silent failure pattern in bad_mod.py: except:
RISK_NOTE: Monitor runtime behavior on target Android/Termux-X11 triad.
```
