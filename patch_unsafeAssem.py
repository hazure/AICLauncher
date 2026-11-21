#!/usr/bin/env python3
"""
patch_unsafeAssem.py - In-Place Binary Patcher for Alice in Cradle v0.30
Author: Maintainer
Description:
    Reads a legitimate copy of unsafeAssem.dll, verifies original byte sequences,
    creates a backup, and patches the VM/environment checks in-place.
    Path resolution:
      1. CLI argument: python3 patch_unsafeAssem.py [path_to_dll_or_game_dir]
      2. Environment variable: export AIC_GAME_DIR=/path/to/game
      3. Relative to current working directory
"""

import os
import sys
import shutil

TARGET_AWK_OFF  = 0xf9d53

ORIG_AWK_BYTES  = bytes.fromhex("062d1672fee3007028650a000a02288900000a28af01000a2a")
PATCH_AWK_BYTES  = bytes.fromhex("2b17" + "00" * 23)

def resolve_target_dll():
    if len(sys.argv) > 1:
        arg_path = sys.argv[1]
        if os.path.isfile(arg_path):
            return arg_path
        if os.path.isdir(arg_path):
            candidate = os.path.join(arg_path, "AliceInCradle_Data", "Managed", "unsafeAssem.dll")
            if os.path.isfile(candidate):
                return candidate
            candidate2 = os.path.join(arg_path, "Managed", "unsafeAssem.dll")
            if os.path.isfile(candidate2):
                return candidate2

    env_dir = os.environ.get("AIC_GAME_DIR")
    if env_dir and os.path.exists(env_dir):
        if os.path.isfile(env_dir):
            return env_dir
        candidate = os.path.join(env_dir, "AliceInCradle_Data", "Managed", "unsafeAssem.dll")
        if os.path.isfile(candidate):
            return candidate
        candidate2 = os.path.join(env_dir, "Managed", "unsafeAssem.dll")
        if os.path.isfile(candidate2):
            return candidate2

    cwd_candidates = [
        "unsafeAssem.dll",
        "Managed/unsafeAssem.dll",
        "AliceInCradle_Data/Managed/unsafeAssem.dll"
    ]
    for c in cwd_candidates:
        if os.path.isfile(c):
            return os.path.abspath(c)

    print("Error: Target unsafeAssem.dll could not be located.")
    print("Please configure the AIC_GAME_DIR environment variable:")
    print("  export AIC_GAME_DIR=/path/to/AliceInCradle_ver030")
    print("Or specify the DLL file directly:")
    print(f"  python3 {sys.argv[0]} /path/to/AliceInCradle_Data/Managed/unsafeAssem.dll")
    sys.exit(1)

def patch_file(dll_path):
    print(f"Target assembly: {dll_path}")
    with open(dll_path, "rb") as f:
        data = bytearray(f.read())

    # 1. Validation
    awk_actual = data[TARGET_AWK_OFF:TARGET_AWK_OFF + len(ORIG_AWK_BYTES)]

    if awk_actual == PATCH_AWK_BYTES:
        print("Notice: This DLL has already been patched.")
        return True

    if awk_actual != ORIG_AWK_BYTES:
        print(f"Error: IN::Awake signature mismatch at 0x{TARGET_AWK_OFF:X}")
        return False

    # 2. Backup
    bak_path = dll_path + ".orig"
    if not os.path.exists(bak_path):
        shutil.copy2(dll_path, bak_path)
        print(f"Backup created: {bak_path}")

    # 3. Apply patch
    data[TARGET_AWK_OFF:TARGET_AWK_OFF + len(PATCH_AWK_BYTES)] = PATCH_AWK_BYTES

    with open(dll_path, "wb") as f:
        f.write(data)

    print(f"Successfully patched {dll_path}")
    return True

def main():
    target_dll = resolve_target_dll()
    success = patch_file(target_dll)
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
