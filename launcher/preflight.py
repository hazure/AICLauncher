"""Preflight dual-hash verification (PLAN.md Chapter 3.4)."""

import hashlib
import json
import os
from typing import Tuple


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def run_preflight(game_dir: str, baseline_json: str, known_assemblies: dict | None = None) -> Tuple[bool, list[str]]:
    """Verify baseline hashes and optional known assembly hashes. Never auto-modifies files."""
    errors: list[str] = []
    if not os.path.exists(baseline_json):
        return False, [f"missing baseline manifest: {baseline_json}"]

    with open(baseline_json, "r", encoding="utf-8") as f:
        base_files = json.load(f).get("files", {})

    for rel_path, meta in base_files.items():
        full = os.path.join(game_dir, rel_path)
        if not os.path.exists(full):
            errors.append(f"missing file: {rel_path}")
            continue
        actual = sha256_file(full)
        if actual != meta.get("sha256"):
            errors.append(f"hash mismatch: {rel_path} (expected {meta.get('sha256')}, got {actual})")

    if known_assemblies:
        for rel_path, expected_sha in known_assemblies.items():
            full = os.path.join(game_dir, rel_path)
            if not os.path.exists(full):
                errors.append(f"missing assembly: {rel_path}")
            elif sha256_file(full) != expected_sha:
                errors.append(f"assembly drift: {rel_path}")

    return len(errors) == 0, errors
