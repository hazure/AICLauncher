"""5-section INI configuration loader with defaults and logging (PLAN.md Chapter 3.2)."""

import configparser
import logging
import os

logger = logging.getLogger(__name__)

DEFAULTS = {
    "paths": {
        "game_dir": ".",
        "stub": "stub.exe",
        "plugins": "BepInEx/plugins",
        "mods_pool": "mods_pool",
    },
    "graphics": {
        "preset": "low",
        "hud_debug": "0",
        "strongmem": "1",
    },
    "mods": {
        "unmosaic": "on",
        "unsafe_asm": "off",
    },
    "plugin": {
        "log_output": "BepInEx/LogOutput.log",
        "require_trio": "true",
    },
    "watchdog": {
        "log": "Player.log",
        "tail_ms": "800",
        "auto_action": "ask",
    },
}


def load_config(path: str) -> dict:
    """Load INI config from path, falling back to DEFAULTS with warnings."""
    cp = configparser.ConfigParser()
    if path and os.path.exists(path):
        cp.read(path, encoding="utf-8")
    else:
        logger.warning("Config file missing (%s); using all defaults", path)

    cfg = {}
    for section, keys in DEFAULTS.items():
        cfg[section] = {}
        for k, default_val in keys.items():
            if cp.has_option(section, k):
                cfg[section][k] = cp.get(section, k)
            else:
                logger.warning("Missing [%s] %s; fallback to default=%s", section, k, default_val)
                cfg[section][k] = default_val
    return cfg
