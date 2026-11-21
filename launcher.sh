#!/usr/bin/env bash
#
# launcher.sh - External Environment & Driver Configuration Launcher
# Decoupled from AliceInCradle.exe to preserve the Single Responsibility Principle.
#

set -e

# 1. Resolve Game Directory
if [ -n "$AIC_GAME_DIR" ] && [ -d "$AIC_GAME_DIR" ]; then
    GAME_DIR="$AIC_GAME_DIR"
elif [ -d "AliceInCradle_Data" ]; then
    GAME_DIR="$(pwd)"
elif [ -n "$1" ] && [ -d "$1" ]; then
    GAME_DIR="$1"
    shift
else
    GAME_DIR="$(pwd)"
fi

cd "$GAME_DIR"

# 2. Box64 Environment Defaults (Fallback-only: respect user pre-configured values)
export BOX64_DYNAREC_BIGBLOCK="${BOX64_DYNAREC_BIGBLOCK:-1}"
export BOX64_DYNAREC_SAFEFLAGS="${BOX64_DYNAREC_SAFEFLAGS:-1}"
export BOX64_DYNAREC_ALIGNED_ATOMICS="${BOX64_DYNAREC_ALIGNED_ATOMICS:-1}"
export BOX64_DYNAREC_STRONGMEM="${BOX64_DYNAREC_STRONGMEM:-1}"

# 3. DXVK & Mesa Shader Cache Configuration
export DXVK_ASYNC="${DXVK_ASYNC:-1}"
export MESA_SHADER_CACHE="${MESA_SHADER_CACHE:-1}"

CACHE_DIR="$GAME_DIR/shader_cache"
mkdir -p "$CACHE_DIR"
export MESA_SHADER_CACHE_DIR="${MESA_SHADER_CACHE_DIR:-$CACHE_DIR}"
export DXVK_STATE_CACHE_PATH="${DXVK_STATE_CACHE_PATH:-$CACHE_DIR}"

# 4. Optional Resolution Detection
EXTRA_ARGS=()
HAS_RES=0
for arg in "$@"; do
    case "$arg" in
        -screen-width*|-screen-height*|-screen-fullscreen*)
            HAS_RES=1
            ;;
    esac
done

if [ "$HAS_RES" -eq 0 ]; then
    if command -v xrandr >/dev/null 2>&1; then
        RES=$(xrandr 2>/dev/null | grep '\*' | awk '{print $1}' | head -n1)
        if [ -n "$RES" ]; then
            SW=$(echo "$RES" | cut -d'x' -f1)
            SH=$(echo "$RES" | cut -d'x' -f2)
            EXTRA_ARGS+=("-screen-width" "$SW" "-screen-height" "$SH" "-popupwindow" "-screen-fullscreen" "0")
        fi
    fi
fi

# 5. Launch via Wine
TARGET_EXE="$GAME_DIR/AliceInCradle.exe"
if [ ! -f "$TARGET_EXE" ]; then
    echo "[Error] AliceInCradle.exe not found in $GAME_DIR" >&2
    exit 1
fi

echo "Launching: wine $TARGET_EXE ${EXTRA_ARGS[*]} $*"
exec wine "$TARGET_EXE" "${EXTRA_ARGS[@]}" "$@"
