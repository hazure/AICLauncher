#!/usr/bin/env python3
"""
delete_mb.py - Deletes MonoBehaviour PathID 12 from level3 asset objects.
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
print(f"Loading {path} ...")
env = UnityPy.load(path)

count_before = len(env.objects)

if hasattr(env, 'objects') and isinstance(env.objects, dict):
    if 12 in env.objects:
        del env.objects[12]
        print("Deleted PathID 12 from dict.")
else:
    env.objects = [obj for obj in env.objects if obj.path_id != 12]
    print("Filtered out PathID 12 from list.")

count_after = len(env.objects)
print(f"Objects before: {count_before}, after: {count_after}")

with open(path, "wb") as f:
    f.write(env.file.save(packer="none"))
print("Done. File saved.")
