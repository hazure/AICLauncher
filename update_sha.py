#!/usr/bin/env python3
"""
update_sha.py - Recalculates SHA-256 for all entries in StreamingAssets/_sha.txt
Reads the game path from:
  1. CLI argument: python3 update_sha.py /path/to/game
  2. Environment variable: export AIC_GAME_DIR=/path/to/game
  3. Current working directory
"""
import hashlib
import os
import shutil
import sys

def resolve_paths():
    game_dir = None
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        game_dir = sys.argv[1]
    elif os.environ.get("AIC_GAME_DIR"):
        env_dir = os.environ.get("AIC_GAME_DIR")
        if os.path.exists(env_dir):
            game_dir = env_dir
        else:
            print(f"Warning: AIC_GAME_DIR is set to '{env_dir}', but path does not exist.")
    elif os.path.exists("AliceInCradle_Data"):
        game_dir = os.path.abspath(".")

    if not game_dir:
        print("Error: Target game directory could not be located.")
        print("Please configure the AIC_GAME_DIR environment variable:")
        print("  export AIC_GAME_DIR=/path/to/AliceInCradle_ver030")
        print("Or pass the game path as a command-line argument:")
        print(f"  python3 {sys.argv[0]} /path/to/AliceInCradle_ver030")
        sys.exit(1)

    # Check if game_dir points directly to AliceInCradle_Data or root
    if os.path.basename(os.path.abspath(game_dir)) == "AliceInCradle_Data":
        data_dir = game_dir
    else:
        data_dir = os.path.join(game_dir, "AliceInCradle_Data")

    sha_txt = os.path.join(data_dir, "StreamingAssets", "_sha.txt")
    if not os.path.exists(sha_txt):
        print(f"Error: Could not find _sha.txt at expected location: {sha_txt}")
        sys.exit(1)

    return data_dir, sha_txt

def main():
    data_dir, sha_txt = resolve_paths()
    print(f"Using game data directory: {data_dir}")
    print(f"Using checksum file: {sha_txt}")

    with open(sha_txt, "r", encoding="utf-8") as f:
        lines = [l.strip() for l in f.readlines() if l.strip()]

    files_to_check = []
    i = 0
    while i < len(lines) - 1:
        path = lines[i]
        old_hash = lines[i + 1]
        files_to_check.append((path, old_hash))
        i += 2

    new_lines = []
    changed = []
    for rel_path, old_hash in files_to_check:
        full_path = os.path.join(data_dir, rel_path)
        if not os.path.exists(full_path):
            print(f"[SKIP-MISSING] {rel_path}")
            new_lines.append(f"{rel_path}\n{old_hash}")
            continue
        with open(full_path, "rb") as f:
            data = f.read()
        new_hash = hashlib.sha256(data).hexdigest()
        if new_hash != old_hash:
            print(f"[UPDATED] {rel_path}")
            changed.append(rel_path)
        else:
            print(f"[OK]      {rel_path}")
        new_lines.append(f"{rel_path}\n{new_hash}")

    if changed:
        shutil.copy2(sha_txt, sha_txt + ".prev")
        with open(sha_txt, "w", encoding="utf-8") as f:
            f.write("\n".join(new_lines) + "\n")
        print(f"\nUpdated {len(changed)} entries: {changed}")
        print("_sha.txt update complete.")
    else:
        print("\nAll hashes match. No updates needed.")

if __name__ == "__main__":
    main()
