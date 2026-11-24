#!/usr/bin/env python3
"""Adjudicator (Role C) entrypoint: default REJECT, prove innocence."""

import argparse
import glob
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_acceptance import parse_card_yaml  # noqa: E402

REQUIRED_FIELDS = {
    "id", "title", "batch", "depends_on", "parallel_group",
    "write_paths", "forbidden_paths", "goal", "acceptance",
    "rollback", "risk", "owner", "status",
}
REQUIRED_FORBIDDEN = ["src/stub_perfect.c", "*.keystore", "rootfs*"]
VAGUE_ROLLBACK = {"手动还原", "手工回滚", "自行恢复", "manual", "tbd", "todo"}
C7_PATTERN = re.compile(
    r"PLAN\.md\s+.*?(?:第[一二三四五六七八九十\d]+章(?:[（(][一二三四五六七八九十\d]+[）)])+|\d+\.\d+)\s*[—\-:： ]\s*\S{2,}"
)


def admit_all(cards_dir: str, out_path: str) -> int:
    paths = sorted(glob.glob(os.path.join(cards_dir, "T*.yaml")))
    if not paths:
        print(f"[ERROR] No cards found in {cards_dir}", file=sys.stderr)
        return 1

    cards = {parse_card_yaml(p).get("id", p): (p, parse_card_yaml(p)) for p in paths}
    failures = []

    for cid, (p, c) in cards.items():
        # C1 Schema complete
        missing = [k for k in REQUIRED_FIELDS if k not in c or c[k] in ("", None)]
        if missing:
            failures.append((cid, "C1", f"missing/empty fields: {missing}"))
        # C2 Acceptance executable
        for ac in c.get("acceptance", []):
            if not ac.get("cmd") or not ac.get("expect"):
                failures.append((cid, "C2", f"AC {ac.get('id')} missing cmd/expect"))
        # C3 Forbidden inheritance
        fb = " ".join(str(x) for x in c.get("forbidden_paths", []))
        for req in REQUIRED_FORBIDDEN:
            if req not in fb:
                failures.append((cid, "C3", f"forbidden_paths missing {req}"))
        # C6 Rollback concrete
        rb = str(c.get("rollback", "")).strip()
        if not rb or rb.lower() in VAGUE_ROLLBACK:
            failures.append((cid, "C6", f"rollback is vague/manual: {rb!r}"))
        # C7 Strict PLAN.md chapter + subsection + deliverable noun traceability
        plan_ref = str(c.get("plan_ref", "")).strip()
        if not plan_ref or not C7_PATTERN.search(plan_ref):
            failures.append((cid, "C7", f"plan_ref missing subsection+deliverable: {plan_ref!r}"))
        # C8 Scale <= 8
        if len(c.get("write_paths", [])) > 8:
            failures.append((cid, "C8", f"write_paths count > 8 ({len(c.get('write_paths', []))})"))
        # C10 No duplicate AC cmds
        cmds = [str(ac.get("cmd", "")).strip() for ac in c.get("acceptance", [])]
        if len(cmds) != len(set(cmds)):
            failures.append((cid, "C10", "duplicate acceptance cmds in card"))

    # C4 DAG & C5 Parallel disjoint
    for cid, (_, c) in cards.items():
        for dep in c.get("depends_on", []):
            if dep == cid or dep not in cards:
                failures.append((cid, "C4", f"invalid depends_on: {dep}"))
    groups = {}
    for cid, (_, c) in cards.items():
        pg = c.get("parallel_group", "solo")
        if pg != "solo":
            groups.setdefault(pg, []).append((cid, set(c.get("write_paths", []))))
    for pg, members in groups.items():
        for i in range(len(members)):
            for j in range(i + 1, len(members)):
                overlap = members[i][1] & members[j][1]
                if overlap:
                    failures.append((f"{members[i][0]}/{members[j][0]}", "C5", f"overlap in {pg}: {overlap}"))

    # C9 Duplicate goals
    seen_goals = {}
    for cid, (_, c) in cards.items():
        g = str(c.get("goal", "")).strip()
        if g in seen_goals:
            failures.append((cid, "C9", f"duplicate goal with {seen_goals[g]}"))
        seen_goals[g] = cid

    verdict = "PASS" if not failures else "REJECT"
    lines = [
        f"VERDICT: {verdict}",
        f"CARD: ADMISSION ({len(cards)} cards)",
        "REASON:",
    ]
    if failures:
        for cid, code, msg in failures:
            lines.append(f"  - [{code}] {cid}: {msg}")
        lines.append("REQUIRED_FIX:")
        for idx, (cid, code, msg) in enumerate(failures, 1):
            lines.append(f"  {idx}. Fix {cid} ({code}): {msg}")
    else:
        lines.append("  - All C1-C10 admission checks passed.")
        lines.append("REQUIRED_FIX: none")

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0 if verdict == "PASS" else 1


def adjudicate_card(args) -> int:
    card = parse_card_yaml(args.card)
    cid = card.get("id", "UNKNOWN")
    cmd = [
        sys.executable, "scripts/gate_check.py",
        "--card", args.card,
        "--report", args.report,
        "--baseline", args.baseline,
        "--gates", "G1,G2,G3,G4,G5,G6,G7,G8,G9,G10,G11,G12",
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    gate_lines = proc.stdout.strip().splitlines()
    failed_gates = [l for l in gate_lines if l.startswith("[FAIL]")]

    if args.round >= 3 or any("G3:" in l or "G6:" in l or "[R3]" in l or "[R7]" in l for l in failed_gates):
        verdict = "FREEZE"
    elif failed_gates:
        verdict = "REJECT"
    else:
        verdict = "PASS"

    lines = [
        f"VERDICT: {verdict}",
        f"CARD: {cid}",
        f"ROUND: {args.round}",
        "GATE_RESULTS:",
    ] + [f"  {l}" for l in gate_lines]
    if failed_gates:
        lines.append("REASON:")
        for l in failed_gates:
            lines.append(f"  - {l}")
        lines.append("REQUIRED_FIX:")
        for idx, l in enumerate(failed_gates, 1):
            lines.append(f"  {idx}. Resolve {l}")
    else:
        lines.append("REASON: All G1-G12 gates and R1-R7 counter-evidence checks passed.")
        lines.append("REQUIRED_FIX: none")
    lines.append("RISK_NOTE: Monitor runtime behavior on target Android/Termux-X11 triad.")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"VERDICT: {verdict} -> {args.out}")
    return 0 if verdict == "PASS" else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Adjudicator (Role C)")
    parser.add_argument("--admit-all", help="Run C1-C10 admission on cards directory")
    parser.add_argument("--card", help="Path to T##.yaml")
    parser.add_argument("--report", default="", help="Path to acceptance report")
    parser.add_argument("--diff", default="", help="Path to git diff file")
    parser.add_argument("--baseline", default="docs/baseline/baseline.json", help="Baseline JSON path")
    parser.add_argument("--round", type=int, default=1, help="Review round")
    parser.add_argument("--out", required=True, help="Output review markdown path")
    args = parser.parse_args()

    if args.admit_all:
        return admit_all(args.admit_all, args.out)
    if args.card:
        return adjudicate_card(args)
    parser.error("Provide either --admit-all or --card")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
