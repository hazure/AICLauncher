#!/usr/bin/env python3
"""
patch_unsafeAssem.py - In-Place Binary Patcher for Alice in Cradle v0.30
Author: Maintainer
Description:
    Reads a legitimate copy of unsafeAssem.dll, verifies original byte sequences,
    creates a backup, and patches the VM/environment checks in-place.
"""

import os
import sys
import shutil

TARGET_SENS_OFF = 0x13e1d0
TARGET_SP_OFF   = 0x13e1da
TARGET_AWK_OFF  = 0xf9d53

ORIG_SENS_BYTES = bytes.fromhex("267e2a1e000416fe022a")
ORIG_SP_BYTES   = bytes.fromhex("267e2a1e000417fe022a")
ORIG_AWK_BYTES  = bytes.fromhex("062d1672fee3007028650a000a02288900000a28af01000a2a")

PATCH_SENS_BYTES = bytes.fromhex("26162a00000000000000")
PATCH_SP_BYTES   = bytes.fromhex("26162a00000000000000")
PATCH_AWK_BYTES  = bytes.fromhex("2b17" + "00" * 23)

def patch_file(dll_path):
    if not os.path.exists(dll_path):
        print(f"Error: File not found at {dll_path}")
        return False

    with open(dll_path, "rb") as f:
        data = bytearray(f.read())

    # 1. Validation
    sens_actual = data[TARGET_SENS_OFF:TARGET_SENS_OFF + len(ORIG_SENS_BYTES)]
    sp_actual   = data[TARGET_SP_OFF:TARGET_SP_OFF + len(ORIG_SP_BYTES)]
    awk_actual  = data[TARGET_AWK_OFF:TARGET_AWK_OFF + len(ORIG_AWK_BYTES)]

    if sens_actual == PATCH_SENS_BYTES and awk_actual == PATCH_AWK_BYTES:
        print("Notice: This DLL is already patched.")
        return True

    if sens_actual != ORIG_SENS_BYTES:
        print(f"Error: get_SENSITIVE signature mismatch at 0x{TARGET_SENS_OFF:X}")
        return False
    if sp_actual != ORIG_SP_BYTES:
        print(f"Error: get_SP_SENSITIVE signature mismatch at 0x{TARGET_SP_OFF:X}")
        return False
    if awk_actual != ORIG_AWK_BYTES:
        print(f"Error: IN::Awake signature mismatch at 0x{TARGET_AWK_OFF:X}")
        return False

    # 2. Backup
    bak_path = dll_path + ".orig"
    if not os.path.exists(bak_path):
        shutil.copy2(dll_path, bak_path)
        print(f"Backup created: {bak_path}")

    # 3. Apply patches
    data[TARGET_SENS_OFF:TARGET_SENS_OFF + len(PATCH_SENS_BYTES)] = PATCH_SENS_BYTES
    data[TARGET_SP_OFF:TARGET_SP_OFF + len(PATCH_SP_BYTES)]       = PATCH_SP_BYTES
    data[TARGET_AWK_OFF:TARGET_AWK_OFF + len(PATCH_AWK_BYTES)]    = PATCH_AWK_BYTES

    with open(dll_path, "wb") as f:
        f.write(data)

    print(f"Successfully patched {dll_path}")
    return True

def main():
    target = None
    if len(sys.argv) > 1:
        target = sys.argv[1]
    else:
        # Default search locations
        candidates = [
            "unsafeAssem.dll",
            "AliceInCradle_Data/Managed/unsafeAssem.dll",
            "/storage/emulated/0/Documents/AliceInCradle_ver030/AliceInCradle_Data/Managed/unsafeAssem.dll"
        ]
        for c in candidates:
            if os.path.exists(c):
                target = c
                break

    if not target:
        print(f"Usage: {sys.argv[0]} [path_to_unsafeAssem.dll]")
        sys.exit(1)

    success = patch_file(target)
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
