"""Dual-directory idempotent mod manager (PLAN.md Chapter 3.5)."""

import json
import os
import shutil


def sync_mods(mods_pool: str, plugins_dir: str, mod_switches: dict) -> dict[str, str]:
    """Idempotently sync enabled mods from mods_pool to plugins_dir without touching game originals."""
    os.makedirs(plugins_dir, exist_ok=True)
    actions: dict[str, str] = {}

    if not os.path.isdir(mods_pool):
        return actions

    for mod_id, enabled in mod_switches.items():
        manifest_path = os.path.join(mods_pool, mod_id, "manifest.json")
        if not os.path.isfile(manifest_path):
            actions[mod_id] = "skipped_missing_manifest"
            continue
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        dll_name = manifest.get("dll", f"{mod_id}.dll")
        src_dll = os.path.join(mods_pool, mod_id, dll_name)
        dst_dll = os.path.join(plugins_dir, dll_name)

        is_on = str(enabled).strip().lower() in {"on", "true", "1", "yes"}
        if is_on:
            if os.path.isfile(src_dll):
                shutil.copy2(src_dll, dst_dll)
                actions[mod_id] = "enabled"
            else:
                actions[mod_id] = "skipped_missing_dll"
        else:
            if os.path.isfile(dst_dll):
                os.remove(dst_dll)
                actions[mod_id] = "disabled_removed"
            else:
                actions[mod_id] = "disabled_absent"
    return actions
