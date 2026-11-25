#!/usr/bin/env python3
"""Gate checker (G1-G12) for Role C / Adjudicator independent review."""

import argparse
import datetime
import fnmatch
import hashlib
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_acceptance import check_expect, parse_card_yaml  # noqa: E402

SECRET_PATTERNS = [
    re.compile(r"BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY"),
    re.compile(r"(?:DRIVE_TOKEN|KEYSTORE_BASE64|API_KEY|SECRET_KEY|TOKEN)\s*[:=]\s*['\"]?[A-Za-z0-9_/+=-]{8,}"),
    re.compile(r"ghp_[A-Za-z0-9]{16,}"),
    re.compile(r"AIza[0-9A-Za-z_-]{16,}"),
]

LOCAL_ABS_PATTERNS = [
    re.compile(r"/data/data/com\.termux"),
    re.compile(r"/home/[A-Za-z0-9_.-]+/"),
    re.compile(r"[A-Za-z]:\\\\Users\\\\"),
]

SILENT_FAIL_LINE_PATTERNS = [
    re.compile(r"^\s*except\s*:\s*(?:pass)?\s*$"),
    re.compile(r"^\s*except\s+Exception(?:\s+as\s+\w+)?\s*:\s*pass\s*$"),
]

DEBUG_LEFTOVER_PATTERNS = [
    re.compile(r"^\+.*(?:pdb\.set_trace|breakpoint\(\)|FIXME_DEBUG|console\.log\([\"']DEBUG)"),
]


def _match_any(path: str, patterns: list) -> bool:
    for pat in patterns:
        p = str(pat).strip()
        if not p:
            continue
        if p.endswith("/") and path.startswith(p):
            return True
        if fnmatch.fnmatch(path, p) or path == p:
            return True
    return False


def _git_changed_files() -> list:
    r1 = subprocess.run(["git", "diff", "--name-only", "HEAD"], capture_output=True, text=True)
    r2 = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], capture_output=True, text=True)
    files = set()
    for out in (r1.stdout, r2.stdout):
        for line in out.splitlines():
            line = line.strip()
            if line:
                files.add(line)
    return sorted(files)


def _git_untracked_files() -> list:
    r = subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], capture_output=True, text=True)
    return [l.strip() for l in r.stdout.splitlines() if l.strip()]


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def check_g1(card: dict) -> tuple[bool, str]:
    write_paths = card.get("write_paths", [])
    forbidden_paths = card.get("forbidden_paths", [])
    changed = _git_changed_files()
    bad_forbidden = [p for p in changed if _match_any(p, forbidden_paths)]
    if bad_forbidden:
        return False, f"G1/R1 touched forbidden_paths: {bad_forbidden}"
    out_of_scope = [p for p in changed if not _match_any(p, write_paths)]
    if out_of_scope:
        return False, f"G1/R1 changed files outside write_paths: {out_of_scope}"
    return True, f"checked {len(changed)} changed files"


def check_g2(card: dict, report_path: str) -> tuple[bool, str]:
    if not report_path or not os.path.exists(report_path):
        return False, f"[R2] missing report: {report_path}"
    try:
        with open(report_path, "r", encoding="utf-8") as f:
            rep = json.load(f)
    except Exception as exc:
        return False, f"[R2] invalid JSON report: {exc}"

    if rep.get("card_id") != card.get("id"):
        return False, f"[R2] card_id mismatch: {rep.get('card_id')} != {card.get('id')}"

    acs = card.get("acceptance", [])
    res_map = {r.get("id"): r for r in rep.get("results", [])}
    for ac in acs:
        ac_id = ac.get("id")
        if ac_id not in res_map:
            return False, f"[R2] missing AC result: {ac_id}"
        r = res_map[ac_id]
        if r.get("cmd") != ac.get("cmd"):
            return False, f"[R2] cmd mismatch for {ac_id}"
        s_at, f_at = r.get("started_at"), r.get("finished_at")
        if not s_at or not f_at:
            return False, f"[R2] missing timestamps for {ac_id}"
        try:
            t1 = datetime.datetime.fromisoformat(s_at)
            t2 = datetime.datetime.fromisoformat(f_at)
            if t2 < t1:
                return False, f"[R2] non-monotonic timestamps for {ac_id}"
        except Exception:
            return False, f"[R2] invalid ISO timestamp for {ac_id}"

        rc = r.get("returncode")
        out = str(r.get("output", ""))
        real_pass = check_expect(ac.get("expect", "exit==0"), rc, out)
        if not r.get("passed") or not real_pass:
            return False, f"[R2] forged or failed AC {ac_id}: passed={r.get('passed')}, real_pass={real_pass}"

    return True, f"all {len(acs)} ACs verified with timestamps & output"


def check_g3(card: dict, baseline_path: str) -> tuple[bool, str]:
    if not os.path.exists(baseline_path):
        return False, f"[R3] missing baseline: {baseline_path}"
    with open(baseline_path, "r", encoding="utf-8") as f:
        base = json.load(f).get("files", {})
    write_paths = card.get("write_paths", [])
    protected = ["src/stub_perfect.c", "unmosaic.py", "patch_unsafeAssem.py", "launcher.sh"]
    drifted = []
    for p in protected:
        if p in base and not _match_any(p, write_paths):
            if not os.path.exists(p) or _sha256(p) != base[p]["sha256"]:
                drifted.append(p)
    if drifted:
        return False, f"[R3] baseline hash drift (zero-pollution breach): {drifted}"
    return True, "protected baseline hashes match"


def check_g4() -> tuple[bool, str]:
    candidates = [p for p in _git_changed_files() if p.endswith(".so") and os.path.exists(p)]
    if not candidates:
        return True, "no .so artifacts in diff"
    readelf = "llvm-readelf"
    ndk_readelf = os.path.expanduser(
        "~/Android/Sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-arm64/bin/llvm-readelf"
    )
    if os.path.exists(ndk_readelf):
        readelf = ndk_readelf
    for so in candidates:
        r = subprocess.run([readelf, "-h", so], capture_output=True, text=True)
        if r.returncode != 0 or "AArch64" not in r.stdout:
            return False, f"G4/R5 {so} is not AArch64 ELF"
    return True, f"verified {len(candidates)} AArch64 .so files"


def check_g5() -> tuple[bool, str]:
    max_bytes = 50 * 1024 * 1024
    for dirpath, dirnames, filenames in os.walk("."):
        dirnames[:] = [d for d in dirnames if d not in {".git", "AGENTS", "__pycache__"}]
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            if os.path.islink(p):
                continue
            low = fn.lower()
            if "rootfs" in low and (low.endswith(".tar.xz") or low.endswith(".tar.gz") or low.endswith(".img")):
                return False, f"R6 rootfs entity in repo: {p}"
            if os.path.getsize(p) > max_bytes:
                return False, f"R6 oversized file (>50MB): {p}"
    return True, "no rootfs entity or >50MB file"


def check_g6() -> tuple[bool, str]:
    r = subprocess.run(["git", "diff", "HEAD"], capture_output=True, text=True)
    for line in r.stdout.splitlines():
        if not line.startswith("+") or line.startswith("+++"):
            continue
        for pat in SECRET_PATTERNS:
            if pat.search(line):
                return False, "[R7] potential plaintext secret in git diff"
    for p in _git_untracked_files():
        if not os.path.isfile(p) or os.path.getsize(p) > 2 * 1024 * 1024:
            continue
        try:
            txt = open(p, "r", encoding="utf-8", errors="ignore").read()
        except Exception:
            continue
        for pat in SECRET_PATTERNS:
            if pat.search(txt):
                return False, f"[R7] potential plaintext secret in untracked file: {p}"
    return True, "no plaintext secrets in diff or untracked files"


def check_g7(card: dict) -> tuple[bool, str]:
    if not str(card.get("rollback", "")).strip():
        return False, "R4 card missing rollback field"
    r = subprocess.run(["bash", "scripts/restore.sh", "--check"], capture_output=True, text=True)
    if r.returncode != 0:
        return False, f"R4 restore.sh --check failed: {r.stderr.strip()}"
    return True, "rollback field & restore.sh --check ok"


def check_g8(report_path: str) -> tuple[bool, str]:
    if not report_path or not os.path.exists(report_path):
        return False, "G8/X6 missing report for idempotency check (run_acceptance.py --twice)"
    with open(report_path, "r", encoding="utf-8") as f:
        rep = json.load(f)
    if rep.get("idempotent") is not True:
        return False, f"G8/X6 idempotent={rep.get('idempotent')} (expected True)"
    return True, "all ACs verified idempotent (--twice)"


PROBE_LAYER_EXEMPT = {
    "client/android/app/src/main/java/com/aliceincradle/launcher/bridge/ProcessBridge.kt",
    "client/android/app/src/main/java/com/aliceincradle/launcher/ProcessBridge.kt",
    "scripts/run_regression_n1_n10.py",
    "scripts/gate_check.py",
}

PROBE_WHITELIST_PATHS = {
    "/data/data/com.termux/files/usr/bin",
    "/data/data/com.termux/files/usr/game_assets",
}

DATA_DATA_LITERAL_RE = re.compile(r"['\"](/data/data/[^'\"]+)['\"]")


def _is_probe_layer_file(path: str) -> bool:
    norm = path.replace("\\", "/")
    return norm.endswith("bridge/ProcessBridge.kt") or norm.endswith("bridge/EnvDetector.kt") or norm in PROBE_LAYER_EXEMPT


def _strip_kotlin_comments(txt: str) -> str:
    txt = re.sub(r"/\*[\s\S]*?\*/", "", txt)
    txt = re.sub(r"//.*", "", txt)
    return txt


def check_kotlin_process_builder(txt: str) -> bool:
    """Return True if all ProcessBuilder( calls are wrapped in try { ... } catch (...)."""
    cleaned = _strip_kotlin_comments(txt)
    for m in re.finditer(r"ProcessBuilder\s*\(", cleaned):
        pos = m.start()
        prefix = cleaned[:pos]
        last_try = prefix.rfind("try")
        if last_try == -1:
            return False
        between = cleaned[last_try:pos]
        if not re.search(r"\btry\s*\{", between):
            return False
        suffix = cleaned[pos:]
        if not re.search(r"\}\s*catch\s*\(", suffix):
            return False
    return True


def check_file_g9_g11(path: str, txt: str) -> list[str]:
    """Check a single .kt or .py file for G9/X3 (/data/data/ hardcode) and G11 (ProcessBuilder try-catch)."""
    errors = []
    norm = path.replace("\\", "/")
    if norm.endswith(".kt") or norm.endswith(".py"):
        for m in DATA_DATA_LITERAL_RE.finditer(txt):
            lit = m.group(1)
            if _is_probe_layer_file(norm) and lit in PROBE_WHITELIST_PATHS:
                continue
            if not _is_probe_layer_file(norm):
                errors.append(f"G9/X3 hardcoded /data/data/ path in {path}: {lit}")
                break
        if norm not in PROBE_LAYER_EXEMPT and not _is_probe_layer_file(norm):
            for pat in LOCAL_ABS_PATTERNS:
                if pat.search(txt):
                    errors.append(f"G9/X3 hardcoded local path in {path}")
                    break
    if norm.endswith(".kt") and not check_kotlin_process_builder(txt):
        errors.append(f"G11/X1 ProcessBuilder without try-catch in {path}")
    return errors


def check_g9(card: dict) -> tuple[bool, str]:
    for ac in card.get("acceptance", []):
        cmd = str(ac.get("cmd", ""))
        for pat in LOCAL_ABS_PATTERNS:
            if pat.search(cmd):
                return False, f"G9/X3 hardcoded local path in {ac.get('id')}: {cmd}"
    for p in _git_changed_files():
        if not (p.endswith(".kt") or p.endswith(".py")) or not os.path.isfile(p):
            continue
        txt = open(p, "r", encoding="utf-8", errors="ignore").read()
        errs = [e for e in check_file_g9_g11(p, txt) if e.startswith("G9/")]
        if errs:
            return False, errs[0]
    return True, "no hardcoded local paths in acceptance cmds or non-probe code"


def check_g10() -> tuple[bool, str]:
    r = subprocess.run(["git", "diff", "HEAD"], capture_output=True, text=True)
    stdlib = sys.stdlib_module_names if hasattr(sys, "stdlib_module_names") else set()
    declared = set()
    for req in ("requirements.txt", "pyproject.toml"):
        if os.path.exists(req):
            declared.update(open(req, encoding="utf-8").read().lower().split())
    for line in r.stdout.splitlines():
        if line.startswith("+import ") or line.startswith("+from "):
            mod = line[1:].split()[1].split(".")[0]
            if stdlib and mod not in stdlib and mod.lower() not in declared and not os.path.exists(f"{mod}.py"):
                return False, f"G10/X4 undeclared non-stdlib import in diff: {mod}"
    return True, "no undeclared dependencies in diff"


def _has_empty_except(lines: list[str]) -> str | None:
    for idx, line in enumerate(lines):
        for pat in SILENT_FAIL_LINE_PATTERNS:
            if pat.search(line):
                return line.strip()
        if re.match(r"^\s*except(?:\s+[^:]+)?\s*:\s*$", line):
            if idx + 1 < len(lines) and re.match(r"^\s*pass\s*$", lines[idx + 1]):
                return f"{line.strip()} pass"
    return None


def check_g11() -> tuple[bool, str]:
    r = subprocess.run(["git", "diff", "HEAD"], capture_output=True, text=True)
    added = [l[1:] for l in r.stdout.splitlines() if l.startswith("+") and not l.startswith("+++")]
    hit = _has_empty_except(added)
    if hit:
        return False, f"G11/X1 silent failure pattern in diff: {hit}"
    for p in _git_untracked_files():
        if p.endswith(".py") and os.path.isfile(p):
            lines = open(p, "r", encoding="utf-8", errors="ignore").read().splitlines()
            hit = _has_empty_except(lines)
            if hit:
                return False, f"G11/X1 silent failure pattern in {p}: {hit}"
    for p in _git_changed_files():
        if p.endswith(".kt") and os.path.isfile(p):
            txt = open(p, "r", encoding="utf-8", errors="ignore").read()
            if not check_kotlin_process_builder(txt):
                return False, f"G11/X1 ProcessBuilder without try-catch in {p}"
    return True, "no silent failure patterns in diff, untracked .py, or .kt ProcessBuilder"


def check_g12() -> tuple[bool, str]:
    r = subprocess.run(["git", "diff", "HEAD"], capture_output=True, text=True)
    for line in r.stdout.splitlines():
        for pat in DEBUG_LEFTOVER_PATTERNS:
            if pat.search(line):
                return False, f"G12/X10 debug leftover in diff: {line.strip()}"
    return True, "no debug leftovers in diff"


def main() -> int:
    parser = argparse.ArgumentParser(description="Run G1-G12 gate checks")
    parser.add_argument("--card", required=True, help="Path to T##.yaml")
    parser.add_argument("--report", default="", help="Path to acceptance report")
    parser.add_argument("--gates", default="G1,G2,G3,G4,G5,G6,G7,G8,G9,G10,G11,G12", help="Comma-separated gates")
    parser.add_argument("--baseline", default="docs/baseline/baseline.json", help="Baseline JSON path")
    args = parser.parse_args()

    card = parse_card_yaml(args.card)
    gates = [g.strip().upper() for g in args.gates.split(",") if g.strip()]
    all_ok = True

    dispatch = {
        "G1": lambda: check_g1(card),
        "G2": lambda: check_g2(card, args.report),
        "G3": lambda: check_g3(card, args.baseline),
        "G4": check_g4,
        "G5": check_g5,
        "G6": check_g6,
        "G7": lambda: check_g7(card),
        "G8": lambda: check_g8(args.report),
        "G9": lambda: check_g9(card),
        "G10": check_g10,
        "G11": check_g11,
        "G12": check_g12,
    }

    for g in gates:
        if g in dispatch:
            ok, msg = dispatch[g]()
        else:
            ok, msg = False, f"unknown gate {g}"
        if not ok:
            all_ok = False
        print(f"[{'PASS' if ok else 'FAIL'}] {g}: {msg}")

    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
