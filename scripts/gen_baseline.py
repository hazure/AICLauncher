#!/usr/bin/env python3
"""Generate baseline SHA-256 manifest for AliceInCradle-Android-Compat-Patch."""

import argparse
import datetime
import hashlib
import json
import os
import subprocess

EXCLUDE_DIRS = {".git", "AGENTS", "docs/baseline", "out", "__pycache__"}


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def git_head(root: str) -> str:
    try:
        r = subprocess.run(
            ["git", "-C", root, "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        )
        return r.stdout.strip()
    except Exception:
        return "unknown"


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate baseline.json")
    parser.add_argument("--out", default="docs/baseline/baseline.json", help="Output JSON path")
    parser.add_argument("--root", default=".", help="Repository root")
    args = parser.parse_args()

    root = os.path.abspath(args.root)
    files = {}

    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root).replace("\\", "/")
        if rel_dir == ".":
            rel_dir = ""

        kept = []
        for d in sorted(dirnames):
            sub_rel = f"{rel_dir}/{d}" if rel_dir else d
            if d in {".git", "AGENTS", "out", "__pycache__"} or sub_rel in EXCLUDE_DIRS:
                continue
            kept.append(d)
        dirnames[:] = kept

        for fn in sorted(filenames):
            rel_path = f"{rel_dir}/{fn}" if rel_dir else fn
            if rel_path == args.out.replace("\\", "/"):
                continue
            full_path = os.path.join(dirpath, fn)
            if os.path.islink(full_path):
                continue
            files[rel_path] = {
                "sha256": sha256_file(full_path),
                "size": os.path.getsize(full_path),
            }

    payload = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "git_head": git_head(root),
        "file_count": len(files),
        "files": files,
    }

    out_path = os.path.join(root, args.out) if not os.path.isabs(args.out) else args.out
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(f"Generated {out_path} with {len(files)} files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
