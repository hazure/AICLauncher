"""Process spawner enforcing game_dir as working directory (PLAN.md Chapter 2.5 & 3.1)."""

import os
import subprocess


def resolve_log_path(cfg: dict) -> str:
    game_dir = cfg.get("paths", {}).get("game_dir", ".")
    log_rel = cfg.get("watchdog", {}).get("log", "Player.log")
    return os.path.join(game_dir, log_rel)


def spawn_game(cfg: dict, extra_env: dict[str, str], args: list[str], executable: str | None = None) -> subprocess.Popen:
    """Spawn stub process with cwd forced to game_dir."""
    game_dir = os.path.abspath(cfg.get("paths", {}).get("game_dir", "."))
    stub = executable or cfg.get("paths", {}).get("stub", "stub.exe")
    env = os.environ.copy()
    env.update(extra_env)
    return subprocess.Popen([stub, *args], cwd=game_dir, env=env)
