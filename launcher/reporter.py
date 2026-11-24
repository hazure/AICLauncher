"""Crash log archiver and rollback-aware diagnostic reporter (PLAN.md Chapter 4.3)."""

import datetime
import json
import os
import shutil


def write_diagnostic_report(
    report_path: str,
    log_path: str,
    archive_dir: str,
    matched_signatures: list[dict],
    rollback_info: dict,
) -> dict:
    """Archive crash log first, then write diagnostic report with rollback info."""
    os.makedirs(archive_dir, exist_ok=True)
    archived_log = ""
    if log_path and os.path.isfile(log_path):
        ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        archived_log = os.path.join(archive_dir, f"crash_{ts}.log")
        shutil.copy2(log_path, archived_log)

    payload = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "archived_log": archived_log,
        "matched_signatures": matched_signatures,
        "rollback_info": rollback_info,
    }
    os.makedirs(os.path.dirname(os.path.abspath(report_path)), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return payload
