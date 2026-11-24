#!/usr/bin/env python3
"""Run acceptance criteria for a single task card (stdlib-only YAML parser)."""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys


def _strip_comment(line: str) -> str:
    in_sq = False
    in_dq = False
    for i, ch in enumerate(line):
        if ch == "'" and not in_dq:
            in_sq = not in_sq
        elif ch == '"' and not in_sq:
            in_dq = not in_dq
        elif ch == "#" and not in_sq and not in_dq:
            if i == 0 or line[i - 1].isspace():
                return line[:i].rstrip()
    return line.rstrip()


def _parse_scalar(val: str):
    val = val.strip()
    if val == "[]":
        return []
    if val.startswith("[") and val.endswith("]"):
        inner = val[1:-1].strip()
        if not inner:
            return []
        return [_parse_scalar(x) for x in inner.split(",")]
    if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
        return val[1:-1]
    if val.lower() == "true":
        return True
    if val.lower() == "false":
        return False
    return val


def parse_card_yaml(path: str) -> dict:
    """Parse a task card YAML file using Python stdlib only."""
    with open(path, "r", encoding="utf-8") as f:
        raw_lines = f.readlines()

    card = {}
    current_key = None
    current_list = None
    current_item = None

    for raw in raw_lines:
        line = _strip_comment(raw.rstrip("\r\n"))
        if not line.strip() or line.strip() == "---":
            continue
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()

        if indent == 0:
            current_item = None
            if ":" in stripped:
                k, v = stripped.split(":", 1)
                k = k.strip()
                v = v.strip()
                current_key = k
                if v == "":
                    card[k] = []
                    current_list = card[k]
                else:
                    card[k] = _parse_scalar(v)
                    current_list = None
        elif indent >= 2 and stripped.startswith("- "):
            item_str = stripped[2:].strip()
            if current_list is None and current_key is not None:
                card[current_key] = []
                current_list = card[current_key]
            if ":" in item_str and not (item_str.startswith('"') or item_str.startswith("'")):
                k, v = item_str.split(":", 1)
                current_item = {k.strip(): _parse_scalar(v)}
                current_list.append(current_item)
            else:
                current_item = None
                current_list.append(_parse_scalar(item_str))
        elif indent >= 4 and current_item is not None and ":" in stripped:
            k, v = stripped.split(":", 1)
            current_item[k.strip()] = _parse_scalar(v)

    return card


def check_expect(expect: str, rc: int, combined_out: str) -> bool:
    exp = str(expect).strip()
    if exp in {"exit==0", "exit_code: 0", "exit 0", "0"}:
        return rc == 0
    m = re.match(r"^exit(?:_code)?\s*(?:==|:)\s*(\d+)$", exp)
    if m:
        return rc == int(m.group(1))
    if rc != 0 and "exit" not in exp.lower() and "fail" not in exp.lower() and "error" not in exp.lower():
        return False
    return exp in combined_out or bool(re.search(exp, combined_out, re.MULTILINE))


def _utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def main() -> int:
    parser = argparse.ArgumentParser(description="Run task card acceptance criteria")
    parser.add_argument("--card", required=True, help="Path to T##.yaml")
    parser.add_argument("--report", required=True, help="Path to output report")
    parser.add_argument("--twice", action="store_true", help="Run each AC twice to verify idempotency (G8)")
    args = parser.parse_args()

    card = parse_card_yaml(args.card)
    acs = card.get("acceptance", [])
    if not isinstance(acs, list) or not acs:
        print(f"[ERROR] No acceptance items found in {args.card}", file=sys.stderr)
        return 1

    results = []
    all_pass = True
    all_idempotent = True

    for ac in acs:
        ac_id = ac.get("id", "UNKNOWN")
        cmd = ac.get("cmd", "")
        expect = ac.get("expect", "exit==0")
        started_at = _utc_now()
        proc = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        finished_at = _utc_now()
        combined = (proc.stdout or "") + (proc.stderr or "")
        passed = check_expect(expect, proc.returncode, combined)

        idempotent = None
        if args.twice:
            proc2 = subprocess.run(cmd, shell=True, capture_output=True, text=True)
            combined2 = (proc2.stdout or "") + (proc2.stderr or "")
            idempotent = (proc.returncode == proc2.returncode) and (combined.strip() == combined2.strip())
            if not idempotent:
                all_idempotent = False

        if not passed:
            all_pass = False
        results.append({
            "id": ac_id,
            "cmd": cmd,
            "expect": expect,
            "returncode": proc.returncode,
            "passed": passed,
            "idempotent": idempotent,
            "started_at": started_at,
            "finished_at": finished_at,
            "output": combined.strip(),
        })
        status = "PASS" if passed else "FAIL"
        idem_tag = f" idempotent={idempotent}" if args.twice else ""
        print(f"[{status}] {ac_id}: rc={proc.returncode}{idem_tag}")

    os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
    report_obj = {
        "card_id": card.get("id", "UNKNOWN"),
        "card_path": args.card,
        "all_passed": all_pass,
        "idempotent": all_idempotent if args.twice else None,
        "results": results,
    }
    with open(args.report, "w", encoding="utf-8") as f:
        json.dump(report_obj, f, indent=2, ensure_ascii=False)
        f.write("\n")

    return 0 if all_pass and (not args.twice or all_idempotent) else 1


if __name__ == "__main__":
    raise SystemExit(main())
