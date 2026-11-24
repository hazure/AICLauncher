#!/usr/bin/env python3
"""Dispatch Adjudicator VERDICT: PASS / REJECT / FREEZE with 3-FREEZE circuit breaker."""

import argparse
import datetime
import os
import re
import subprocess
import sys


def _check_circuit_breaker() -> bool:
    if not os.path.exists("docs/PROGRESS.md"):
        return False
    rows = [
        l for l in open("docs/PROGRESS.md", "r", encoding="utf-8").read().splitlines()
        if l.startswith("|") and not l.startswith("| 时间") and not l.startswith("| :---")
    ]
    if len(rows) < 3:
        return False
    last3 = rows[-3:]
    return all("| frozen |" in r for r in last3)


def main() -> int:
    parser = argparse.ArgumentParser(description="Dispatch Adjudicator verdict")
    parser.add_argument("--verdict", required=True, help="Path to T##-rN.md")
    parser.add_argument("--dry-run", action="store_true", help="Do not mutate files")
    args = parser.parse_args()

    text = open(args.verdict, "r", encoding="utf-8").read()
    m_v = re.search(r"^VERDICT:\s*(PASS|REJECT|FREEZE)", text, re.MULTILINE)
    m_c = re.search(r"^CARD:\s*(T\d+)", text, re.MULTILINE)
    if not m_v or not m_c:
        print(f"[ERROR] Invalid verdict file: {args.verdict}", file=sys.stderr)
        return 1

    verdict = m_v.group(1)
    card_id = m_c.group(1)
    card_path = f"docs/tasks/cards/{card_id}.yaml"
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")

    print(f"[DISPATCH] {card_id} -> {verdict} (dry_run={args.dry_run})")
    if args.dry_run:
        return 0

    if verdict == "PASS":
        if os.path.exists(card_path):
            raw = open(card_path, "r", encoding="utf-8").read()
            raw = re.sub(r"^status:\s*\w+", "status: done", raw, flags=re.MULTILINE)
            open(card_path, "w", encoding="utf-8").write(raw)
        with open("docs/PROGRESS.md", "a", encoding="utf-8") as f:
            f.write(f"| {now} | {card_id} | done | Adjudicator PASS ({args.verdict}) | 无 |\n")
    elif verdict == "REJECT":
        if os.path.exists(card_path):
            subprocess.run(["bash", "scripts/auto_rollback.sh", "--card", card_path], check=False)
    elif verdict == "FREEZE":
        if os.path.exists(card_path):
            raw = open(card_path, "r", encoding="utf-8").read()
            raw = re.sub(r"^status:\s*\w+", "status: frozen", raw, flags=re.MULTILINE)
            open(card_path, "w", encoding="utf-8").write(raw)
        with open("docs/tasks/FROZEN.md", "a", encoding="utf-8") as f:
            f.write(f"| {now} | {card_id} | - | Adjudicator FREEZE | {args.verdict} | 改卡 / 改需求 / 拆卡 / 人工接手 |\n")
        with open("docs/PROGRESS.md", "a", encoding="utf-8") as f:
            f.write(f"| {now} | {card_id} | frozen | Adjudicator FREEZE ({args.verdict}) | 见 FROZEN.md |\n")
        if _check_circuit_breaker():
            cb_path = "docs/tasks/reviews/CIRCUIT_BREAKER.md"
            os.makedirs(os.path.dirname(cb_path), exist_ok=True)
            with open(cb_path, "w", encoding="utf-8") as f:
                f.write(
                    "# 尺度熔断自检报告（CIRCUIT BREAKER）\n\n"
                    f"- 触发时间：{now}\n"
                    "- 触发条件：连续 3 张任务卡进入 `FREEZE`。\n"
                    "- 铁律：绝对禁止通过放宽规则来恢复流水线，必须人工介入核查。\n"
                )
            print(f"[CIRCUIT_BREAKER] 3 consecutive FREEZEs! Wrote {cb_path}", file=sys.stderr)
            return 2

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
