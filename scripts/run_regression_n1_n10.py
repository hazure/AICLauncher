#!/usr/bin/env python3
"""N1-N10 Interception Regression Test Suite (isolated temp git repos)."""

import argparse
import datetime
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def _init_sandbox(tmp_dir: str) -> None:
    shutil.copytree(os.path.join(REPO_ROOT, "scripts"), os.path.join(tmp_dir, "scripts"))
    os.makedirs(os.path.join(tmp_dir, "src"), exist_ok=True)
    os.makedirs(os.path.join(tmp_dir, "docs/baseline"), exist_ok=True)
    os.makedirs(os.path.join(tmp_dir, "docs/tasks/cards"), exist_ok=True)
    os.makedirs(os.path.join(tmp_dir, "docs/tasks/reviews"), exist_ok=True)

    with open(os.path.join(tmp_dir, "src/stub_perfect.c"), "w", encoding="utf-8") as f:
        f.write("int main(void) { return 0; }\n")
    with open(os.path.join(tmp_dir, "README.md"), "w", encoding="utf-8") as f:
        f.write("# Sandbox\n")

    subprocess.run(["git", "init", "-q"], cwd=tmp_dir, check=True)
    subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=tmp_dir, check=True)
    subprocess.run(["git", "config", "user.name", "Test"], cwd=tmp_dir, check=True)
    subprocess.run(["git", "add", "."], cwd=tmp_dir, check=True)
    subprocess.run(["git", "commit", "-q", "-m", "init"], cwd=tmp_dir, check=True)
    subprocess.run(
        [sys.executable, "scripts/gen_baseline.py", "--out", "docs/baseline/baseline.json"],
        cwd=tmp_dir,
        check=True,
        capture_output=True,
    )


def _write_card(path: str, *, cid="T99", plan_ref='"PLAN.md 第八章（一）— 沙盒产物"',
                parallel_group="solo", write_paths=None, cmd="echo ok", expect="ok",
                rollback="git checkout -- README.md"):
    if write_paths is None:
        write_paths = ["README.md"]
    wp_lines = "\n".join(f"  - {p}" for p in write_paths)
    content = (
        f"id: {cid}\n"
        f"title: 沙盒测试卡 {cid}\n"
        f"batch: 批次一\n"
    )
    if plan_ref is not None:
        content += f"plan_ref: {plan_ref}\n"
    content += (
        "depends_on: []\n"
        f"parallel_group: {parallel_group}\n"
        "write_paths:\n"
        f"{wp_lines}\n"
        "forbidden_paths:\n"
        "  - src/stub_perfect.c\n"
        "  - \"*.keystore\"\n"
        "  - \"rootfs*\"\n"
        f"goal: 验证 {cid}\n"
        "acceptance:\n"
        f"  - id: AC-{cid}-1\n"
        f"    cmd: {cmd}\n"
        f"    expect: {expect}\n"
        f"rollback: {rollback}\n"
        "risk: 测试卡\n"
        "owner: agent\n"
        "status: draft\n"
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def _run_case(case_id: str, desc: str, expected_verdict: str, expected_code: str, fn) -> dict:
    tmp = tempfile.mkdtemp(prefix=f"reg_{case_id}_")
    try:
        _init_sandbox(tmp)
        cmd_str, out = fn(tmp)
        ok = (f"VERDICT: {expected_verdict}" in out) and (expected_code in out)
        return {
            "id": case_id,
            "desc": desc,
            "expected": f"{expected_verdict} ({expected_code})",
            "cmd": cmd_str,
            "output": out.strip(),
            "passed": ok,
        }
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def case_n1(tmp: str):
    _write_card(os.path.join(tmp, "docs/tasks/cards/T01.yaml"), cid="T01", plan_ref=None)
    cmd = "python3 scripts/adjudicate.py --admit-all docs/tasks/cards --out docs/tasks/reviews/ADMISSION-n1.md"
    p = subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    return cmd, p.stdout + p.stderr


def case_n2(tmp: str):
    card = os.path.join(tmp, "docs/tasks/cards/T01.yaml")
    _write_card(card, cid="T01", write_paths=["README.md"])
    with open(os.path.join(tmp, "outside.txt"), "w", encoding="utf-8") as f:
        f.write("outside\n")
    subprocess.run([sys.executable, "scripts/run_acceptance.py", "--card", card, "--report", "out.log", "--twice"],
                   cwd=tmp, check=True, capture_output=True)
    cmd = "python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md"
    p = subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    rev = open(os.path.join(tmp, "docs/tasks/reviews/T01-r1.md"), encoding="utf-8").read()
    return cmd, rev


def case_n3(tmp: str):
    card = os.path.join(tmp, "docs/tasks/cards/T01.yaml")
    _write_card(card, cid="T01", cmd="echo real_out", expect="real_out")
    forged = {
        "card_id": "T01",
        "card_path": card,
        "all_passed": True,
        "idempotent": True,
        "results": [{
            "id": "AC-T01-1",
            "cmd": "echo real_out",
            "expect": "real_out",
            "returncode": 0,
            "passed": True,
            "idempotent": True,
            "output": "forged_wrong_output",
        }],
    }
    with open(os.path.join(tmp, "out.log"), "w", encoding="utf-8") as f:
        json.dump(forged, f)
    cmd = "python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md"
    subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    rev = open(os.path.join(tmp, "docs/tasks/reviews/T01-r1.md"), encoding="utf-8").read()
    return cmd, rev


def case_n4(tmp: str):
    card = os.path.join(tmp, "docs/tasks/cards/T01.yaml")
    _write_card(card, cid="T01", write_paths=["README.md"])
    with open(os.path.join(tmp, "src/stub_perfect.c"), "a", encoding="utf-8") as f:
        f.write("// polluted\n")
    subprocess.run([sys.executable, "scripts/run_acceptance.py", "--card", card, "--report", "out.log", "--twice"],
                   cwd=tmp, check=True, capture_output=True)
    cmd = "python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md"
    subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    rev = open(os.path.join(tmp, "docs/tasks/reviews/T01-r1.md"), encoding="utf-8").read()
    return cmd, rev


def case_n5(tmp: str):
    card = os.path.join(tmp, "docs/tasks/cards/T01.yaml")
    _write_card(card, cid="T01", write_paths=["lib/x86_64/libbad.so"])
    os.makedirs(os.path.join(tmp, "lib/x86_64"), exist_ok=True)
    # Minimal 64-byte ELF64 header with e_machine = 62 (EM_X86_64)
    ehdr = bytearray(64)
    ehdr[0:4] = b"\x7fELF"
    ehdr[4] = 2  # ELFCLASS64
    ehdr[5] = 1  # ELFDATA2LSB
    ehdr[6] = 1  # EV_CURRENT
    struct.pack_into("<HHI", ehdr, 16, 3, 62, 1)  # ET_DYN=3, EM_X86_64=62, EV_CURRENT=1
    with open(os.path.join(tmp, "lib/x86_64/libbad.so"), "wb") as f:
        f.write(ehdr)
    subprocess.run([sys.executable, "scripts/run_acceptance.py", "--card", card, "--report", "out.log", "--twice"],
                   cwd=tmp, check=True, capture_output=True)
    cmd = "python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md"
    subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    rev = open(os.path.join(tmp, "docs/tasks/reviews/T01-r1.md"), encoding="utf-8").read()
    return cmd, rev


def case_n6(tmp: str):
    card = os.path.join(tmp, "docs/tasks/cards/T01.yaml")
    _write_card(card, cid="T01", write_paths=["README.md"])
    with open(os.path.join(tmp, "README.md"), "a", encoding="utf-8") as f:
        f.write('DRIVE_TOKEN="ghp_FakeSecretToken123456789012345"\n')
    subprocess.run([sys.executable, "scripts/run_acceptance.py", "--card", card, "--report", "out.log", "--twice"],
                   cwd=tmp, check=True, capture_output=True)
    cmd = "python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md"
    subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    rev = open(os.path.join(tmp, "docs/tasks/reviews/T01-r1.md"), encoding="utf-8").read()
    return cmd, rev


def case_n7(tmp: str):
    _write_card(os.path.join(tmp, "docs/tasks/cards/T01.yaml"), cid="T01", rollback="手动还原")
    cmd = "python3 scripts/adjudicate.py --admit-all docs/tasks/cards --out docs/tasks/reviews/ADMISSION-n7.md"
    p = subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    return cmd, p.stdout + p.stderr


def case_n8(tmp: str):
    _write_card(os.path.join(tmp, "docs/tasks/cards/T01.yaml"), cid="T01",
                parallel_group="batch4_modules", write_paths=["shared.py"])
    _write_card(os.path.join(tmp, "docs/tasks/cards/T02.yaml"), cid="T02",
                parallel_group="batch4_modules", write_paths=["shared.py"])
    cmd = "python3 scripts/adjudicate.py --admit-all docs/tasks/cards --out docs/tasks/reviews/ADMISSION-n8.md"
    p = subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    return cmd, p.stdout + p.stderr


def case_n9(tmp: str):
    card = os.path.join(tmp, "docs/tasks/cards/T01.yaml")
    _write_card(card, cid="T01", cmd="ls /data/data/com.termux/files/home >/dev/null 2>&1 || true", expect="exit==0")
    subprocess.run([sys.executable, "scripts/run_acceptance.py", "--card", card, "--report", "out.log", "--twice"],
                   cwd=tmp, check=True, capture_output=True)
    cmd = "python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md"
    subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    rev = open(os.path.join(tmp, "docs/tasks/reviews/T01-r1.md"), encoding="utf-8").read()
    return cmd, rev


def case_n10(tmp: str):
    card = os.path.join(tmp, "docs/tasks/cards/T01.yaml")
    _write_card(card, cid="T01", write_paths=["bad_mod.py"])
    with open(os.path.join(tmp, "bad_mod.py"), "w", encoding="utf-8") as f:
        f.write("def f():\n    try:\n        return 1\n    except:\n        pass\n")
    subprocess.run([sys.executable, "scripts/run_acceptance.py", "--card", card, "--report", "out.log", "--twice"],
                   cwd=tmp, check=True, capture_output=True)
    cmd = "python3 scripts/adjudicate.py --card docs/tasks/cards/T01.yaml --report out.log --round 1 --out docs/tasks/reviews/T01-r1.md"
    subprocess.run(cmd, shell=True, cwd=tmp, capture_output=True, text=True)
    rev = open(os.path.join(tmp, "docs/tasks/reviews/T01-r1.md"), encoding="utf-8").read()
    return cmd, rev


def main() -> int:
    parser = argparse.ArgumentParser(description="Run N1-N10 interception regression tests")
    parser.add_argument("--out", default="docs/tasks/reviews/REGRESSION.md", help="Output markdown report")
    args = parser.parse_args()

    specs = [
        ("N1", "删掉一张卡的 plan_ref", "REJECT", "[C7]", case_n1),
        ("N2", "write_paths 外改一个文件", "REJECT", "G1/R1", case_n2),
        ("N3", "AC 输出手写伪造", "REJECT", "[R2]", case_n3),
        ("N4", "关闭插件后哈希不一致", "FREEZE", "[R3]", case_n4),
        ("N5", "引入 x86_64 .so", "REJECT", "G4/R5", case_n5),
        ("N6", "塞入明文 token", "FREEZE", "[R7]", case_n6),
        ("N7", "rollback 写成\"手动还原\"", "REJECT", "[C6]", case_n7),
        ("N8", "同组两卡 write_paths 有交集", "REJECT", "[C5]", case_n8),
        ("N9", "验收依赖本机绝对路径", "REJECT", "G9/X3", case_n9),
        ("N10", "加一个空 except", "REJECT", "G11/X1", case_n10),
    ]

    results = [_run_case(*s) for s in specs]
    passed_cnt = sum(1 for r in results if r["passed"])
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    md = [
        "# N1–N10 拦截能力回归测试报告（REGRESSION）",
        "",
        f"- 执行时间：{now}",
        f"- 拦截率：**{passed_cnt}/{len(results)} ({passed_cnt * 100 // len(results)}%)**",
        "",
        "| 编号 | 测试场景 | 期望结果 | 判定 |",
        "| :--- | :--- | :--- | :--- |",
    ]
    for r in results:
        st = "[PASS]" if r["passed"] else "[FAILED]"
        md.append(f"| {r['id']} | {r['desc']} | {r['expected']} | {st} |")

    md.append("")
    md.append("## 逐项命令行回显与实证")
    md.append("")
    for r in results:
        st = "PASS" if r["passed"] else "FAILED"
        md.extend([
            f"### {r['id']}：{r['desc']} — `[{st}]`",
            f"- **期望**：`{r['expected']}`",
            f"- **命令**：`{r['cmd']}`",
            "```text",
            r["output"],
            "```",
            "",
        ])

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(md))

    for r in results:
        print(f"[{'PASS' if r['passed'] else 'FAILED'}] {r['id']} ({r['desc']}) -> {r['expected']}")
    print(f"TOTAL: {passed_cnt}/{len(results)}")
    return 0 if passed_cnt == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
