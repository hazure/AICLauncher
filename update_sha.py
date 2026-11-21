#!/usr/bin/env python3
"""
update_sha.py - 重新计算 _sha.txt 里所有文件的 SHA256 并更新
每次修改游戏 DLL 后运行此脚本
"""
import hashlib, os, shutil

GAME = '/storage/emulated/0/Documents/AliceInCradle_ver030/AliceInCradle_Data'
SHA_TXT = GAME + '/StreamingAssets/_sha.txt'

with open(SHA_TXT, 'r') as f:
    lines = [l.strip() for l in f.readlines() if l.strip()]

files_to_check = []
i = 0
while i < len(lines) - 1:
    path = lines[i]
    old_hash = lines[i+1]
    files_to_check.append((path, old_hash))
    i += 2

new_lines = []
changed = []
for rel_path, old_hash in files_to_check:
    full_path = os.path.join(GAME, rel_path)
    if not os.path.exists(full_path):
        print(f"[SKIP-MISSING] {rel_path}")
        new_lines.append(f"{rel_path}\n{old_hash}")
        continue
    with open(full_path, 'rb') as f:
        data = f.read()
    new_hash = hashlib.sha256(data).hexdigest()
    if new_hash != old_hash:
        print(f"[UPDATED] {rel_path}")
        changed.append(rel_path)
    else:
        print(f"[OK]      {rel_path}")
    new_lines.append(f"{rel_path}\n{new_hash}")

if changed:
    # 备份原文件
    shutil.copy2(SHA_TXT, SHA_TXT + '.prev')
    with open(SHA_TXT, 'w') as f:
        f.write('\n'.join(new_lines) + '\n')
    print(f"\n更新了 {len(changed)} 个条目: {changed}")
    print("_sha.txt 已更新！")
else:
    print("\n所有文件哈希正确，无需更新。")
