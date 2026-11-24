"""Headless launcher CLI entrypoint (PLAN.md Chapter 3.1 & 3.3)."""

import argparse
import sys

from launcher.config import load_config
from launcher.envbuilder import build_env_and_args
from launcher.modmanager import sync_mods
from launcher.preflight import run_preflight
from launcher.spawner import spawn_game


def launch(cfg: dict, baseline_json: str, executable: str | None = None) -> int:
    """Execute preflight -> modmanager -> envbuilder -> spawner."""
    paths = cfg.get("paths", {})
    ok, errs = run_preflight(paths.get("game_dir", "."), baseline_json)
    if not ok:
        for e in errs:
            print(f"[PREFLIGHT ERROR] {e}", file=sys.stderr)
        return 2

    sync_mods(paths.get("mods_pool", "mods_pool"), paths.get("plugins", "BepInEx/plugins"), cfg.get("mods", {}))
    env, args = build_env_and_args(cfg)
    proc = spawn_game(cfg, env, args, executable=executable)
    return proc.wait()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="AliceInCradle Android Compat Launcher")
    parser.add_argument("--config", default="launcher.ini", help="Path to launcher.ini")
    parser.add_argument("--baseline", default="docs/baseline/baseline.json", help="Path to baseline.json")
    args = parser.parse_args(argv)
    cfg = load_config(args.config)
    return launch(cfg, args.baseline)


if __name__ == "__main__":
    raise SystemExit(main())
