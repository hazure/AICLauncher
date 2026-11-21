#!/usr/bin/env python3
"""
scan_level3.py - Scans level3 Unity asset objects for detection strings.
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

for obj in env.objects:
    obj_type = obj.type.name
    try:
        data = obj.read()
        name = getattr(data, 'm_Name', 'Unnamed')
    except Exception:
        name = 'Unreadable'
    
    print(f"Type: {obj_type:<15} PathID: {obj.path_id:<15} Name: {name}")
    
    if obj_type == "TextAsset":
        try:
            script = data.m_Script
            if "unsupported environment" in script:
                print(">>> FOUND TARGET STRING IN TEXTASSET <<<")
        except Exception:
            pass
