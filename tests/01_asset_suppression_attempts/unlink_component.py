#!/usr/bin/env python3
"""
unlink_component.py - Unlinks MonoBehaviour PathID 12 from GameObject 2 in level3.
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

go = None
for obj in env.objects:
    if obj.path_id == 2:
        go = obj.read()
        break

if not go:
    print("GameObject 2 not found!")
    sys.exit(1)

print(f"Original components on GameObject 2: {go.m_Component}")

new_components = []
for comp in go.m_Component:
    if hasattr(comp, 'component') and comp.component.path_id == 12:
        print("Found MonoBehaviour 12. Unlinking from GameObject...")
        continue
    new_components.append(comp)

go.m_Component = new_components
go.save()

with open(path, "wb") as f:
    f.write(env.file.save(packer="none"))
print("Done. Saved unlinked level3 asset.")
