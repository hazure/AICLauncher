#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat << 'EOF'
Usage:
  scripts/auto_rollback.sh --check
  scripts/auto_rollback.sh --card <path-to-T##.yaml> [--dry-run]
EOF
}

if [ $# -lt 1 ]; then
  usage
  exit 1
fi

if [ "$1" = "--check" ]; then
  git rev-parse --is-inside-work-tree >/dev/null
  test -x scripts/restore.sh
  echo "[OK] auto_rollback.sh check passed."
  exit 0
fi

CARD=""
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --card) CARD="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) usage; exit 1 ;;
  esac
done

[ -n "$CARD" ] && [ -f "$CARD" ] || { echo "[ERROR] Missing --card" >&2; exit 1; }

python3 - "$CARD" "$DRY_RUN" << 'PY'
import os, subprocess, sys
sys.path.insert(0, "scripts")
from run_acceptance import parse_card_yaml

card = parse_card_yaml(sys.argv[1])
dry = sys.argv[2] == "1"
write_paths = card.get("write_paths", [])
for p in write_paths:
    p = str(p).strip()
    if not p or p.startswith("AGENTS"):
        continue
    if dry:
        print(f"[DRY-RUN] would rollback: {p}")
        continue
    tracked = subprocess.run(["git", "ls-files", "--error-unmatch", p], capture_output=True).returncode == 0
    if tracked:
        subprocess.run(["git", "checkout", "--", p], check=False)
        print(f"[ROLLBACK] restored tracked: {p}")
    elif os.path.isfile(p):
        os.remove(p)
        print(f"[ROLLBACK] removed untracked: {p}")
PY
