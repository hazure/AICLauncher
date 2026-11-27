#!/usr/bin/env bash
set -euo pipefail

SO_FILES=$(find . -path './.git' -prune -o -path './AGENTS' -prune -o -name '*.so' -print)
if [ -z "$SO_FILES" ]; then
  echo "[OK] No .so files in repo; aarch64 check skipped."
  exit 0
fi

for f in $SO_FILES; do
  if ! file "$f" | grep -qiE 'aarch64|arm aarch64'; then
    echo "[FAIL] $f is not aarch64!" >&2
    exit 1
  fi
  echo "[OK] $f is aarch64."
done
