#!/usr/bin/env bash
set -euo pipefail

BASELINE_JSON="docs/baseline/baseline.json"

usage() {
  cat << 'EOF'
Usage:
  scripts/restore.sh --check
  scripts/restore.sh --baseline
  scripts/restore.sh --file <path>
  scripts/restore.sh --tag <tag> [--force]
EOF
}

if [ $# -lt 1 ]; then
  usage
  exit 1
fi

case "$1" in
  --check)
    git rev-parse --is-inside-work-tree >/dev/null
    test -f "$BASELINE_JSON"
    python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$BASELINE_JSON"
    echo "[OK] restore.sh check passed (git repo & $BASELINE_JSON valid)."
    ;;
  --baseline)
    python3 - "$BASELINE_JSON" << 'PY'
import hashlib, json, os, sys
base = json.load(open(sys.argv[1]))["files"]
drifted = []
for p, meta in base.items():
    if not os.path.exists(p):
        drifted.append((p, "missing"))
        continue
    h = hashlib.sha256(open(p, "rb").read()).hexdigest()
    if h != meta["sha256"]:
        drifted.append((p, "modified"))
if drifted:
    for p, st in drifted:
        print(f"[DRIFT] {st}: {p}")
    sys.exit(1)
print(f"[OK] All {len(base)} baseline files match.")
PY
    ;;
  --file)
    [ $# -ge 2 ] || { usage; exit 1; }
    git checkout -- "$2"
    echo "[OK] Restored $2 from HEAD."
    ;;
  --tag)
    [ $# -ge 2 ] || { usage; exit 1; }
    TAG="$2"
    FORCE="${3:-}"
    if [ "$FORCE" != "--force" ] && [ -n "$(git status --porcelain)" ]; then
      echo "[ERROR] Working tree is dirty. Commit/stash or pass --force." >&2
      exit 1
    fi
    git checkout "$TAG"
    echo "[OK] Checked out tag $TAG."
    ;;
  *)
    usage
    exit 1
    ;;
esac
