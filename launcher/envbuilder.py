"""Environment and engine argument builder (PLAN.md Chapter 3.1 & 11.3)."""

PRESET_ARGS = {
    "low": ["-screen-quality", "Fastest"],
    "medium": ["-screen-quality", "Simple"],
    "high": ["-screen-quality", "Beautiful"],
}


def build_env_and_args(cfg: dict) -> tuple[dict[str, str], list[str]]:
    """Build Box64/Wine environment variables and Unity engine CLI arguments."""
    gfx = cfg.get("graphics", {})
    preset = str(gfx.get("preset", "low")).lower()
    hud_debug = str(gfx.get("hud_debug", "0"))
    strongmem = str(gfx.get("strongmem", "1"))

    env = {
        "BOX64_DYNAREC_STRONGMEM": strongmem,
        "DXVK_HUD": hud_debug,
        "WINEDEBUG": "-all",
    }
    args = list(PRESET_ARGS.get(preset, PRESET_ARGS["low"]))
    return env, args
