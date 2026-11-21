#!/usr/bin/env python3
"""
patch_exact.py - Clears TextMesh and MonoBehaviour strings in level3.
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
    if obj.path_id == 7: # TextMesh
        print("Patching TextMesh (7)...")
        data = obj.read()
        if hasattr(data, 'm_Text'):
            data.m_Text = ""
            data.save()
            print("TextMesh cleared.")
            
    if obj.path_id == 12: # MonoBehaviour
        print("Patching MonoBehaviour (12)...")
        data = obj.read()
        if hasattr(data, 'm_Enabled'):
            data.m_Enabled = False
            print("MonoBehaviour disabled.")
        for attr in dir(data):
            if not attr.startswith('_'):
                try:
                    val = getattr(data, attr)
                    if isinstance(val, str) and len(val) > 0:
                        setattr(data, attr, "")
                except Exception:
                    pass
        data.save()
        print("MonoBehaviour fields processed.")

with open(path, "wb") as f:
    f.write(env.file.save(packer="none"))
print("Done. File saved.")
