#!/usr/bin/env python3
"""
patch_level3.py - Attempts to disable MonoBehaviour PathID 12 in level3.
Environment variable: AIC_GAME_DIR
"""
import os
import sys
import UnityPy

def get_level3_path():
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        return sys.argv[1]
    env_dir = os.environ.get("AIC_GAME_DIR")
    if env_dir:
        c1 = os.path.join(env_dir, "AliceInCradle_Data", "level3")
        if os.path.exists(c1):
            return c1
        c2 = os.path.join(env_dir, "level3")
        if os.path.exists(c2):
            return c2
    for c in ["level3", "AliceInCradle_Data/level3"]:
        if os.path.exists(c):
            return os.path.abspath(c)
    print("Error: level3 asset not found. Please set AIC_GAME_DIR environment variable:")
    print("  export AIC_GAME_DIR=/path/to/AliceInCradle_ver030")
    sys.exit(1)

path = get_level3_path()
backup_path = path + ".bak"

print(f"Loading {path} ...")
env = UnityPy.load(path)

target_mb = None
target_go = None

for obj in env.objects:
    if obj.path_id == 12:
        target_mb = obj
    elif obj.path_id == 2:
        target_go = obj

if target_mb is None:
    print("Error: MonoBehaviour 12 not found!")
    sys.exit(1)

print("Removing MonoBehaviour (PathID 12) from scene...")
if 12 in env.objects:
    try:
        mb_data = target_mb.read()
        if hasattr(mb_data, 'm_Enabled'):
            mb_data.m_Enabled = 0
            mb_data.save()
            print("Set m_Enabled = 0 on MonoBehaviour 12.")
        else:
            print("m_Enabled not found.")
    except Exception as e:
        print(f"Error modifying MonoBehaviour: {e}")
