#!/usr/bin/env python3
"""
find_vmware.py - Disassembles executable sections searching for references to VirtualBox.
Environment variable: AIC_GAME_DIR
"""
import os
import sys
import pefile
from capstone import *

def get_target_path():
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        return sys.argv[1]
    env_dir = os.environ.get("AIC_GAME_DIR")
    if env_dir:
        cand = os.path.join(env_dir, "UnityPlayer.dll")
        if os.path.exists(cand):
            return cand
    for c in ["UnityPlayer.dll", "UnityPlayer_clean.dll"]:
        if os.path.exists(c):
            return os.path.abspath(c)
    print("Error: Target UnityPlayer.dll not found. Please set AIC_GAME_DIR environment variable:")
    print("  export AIC_GAME_DIR=/path/to/AliceInCradle_ver030")
    print("Or specify the file directly:")
    print(f"  python3 {sys.argv[0]} /path/to/UnityPlayer.dll")
    sys.exit(1)

path = get_target_path()
print(f"Analyzing {path} ...")

pe = pefile.PE(path)
image_base = pe.OPTIONAL_HEADER.ImageBase

with open(path, "rb") as f:
    data = f.read()

str_offset = data.find(b"VirtualBox")
if str_offset == -1:
    print("VirtualBox string not found in file. Aborting.")
    sys.exit(0)

str_va = 0
for section in pe.sections:
    if section.PointerToRawData <= str_offset < section.PointerToRawData + section.SizeOfRawData:
        str_va = image_base + section.VirtualAddress + (str_offset - section.PointerToRawData)
        break

print(f"VirtualBox string found at file offset: {str_offset}, Virtual Address: 0x{str_va:X}")

md = Cs(CS_ARCH_X86, CS_MODE_64)
md.detail = True

for section in pe.sections:
    if section.Characteristics & 0x20000000: # IMAGE_SCN_MEM_EXECUTE
        sec_data = section.get_data()
        sec_va = image_base + section.VirtualAddress
        
        for i in md.disasm(sec_data, sec_va):
            if i.mnemonic == "lea" and len(i.operands) == 2:
                op = i.operands[1]
                if op.type == CS_OP_MEM and op.mem.base == X86_REG_RIP:
                    target = i.address + i.size + op.mem.disp
                    if target == str_va:
                        print(f"\n--- FOUND INSTRUCTION AT 0x{i.address:X} ---")
                        print(f"0x{i.address:X}: {i.mnemonic} {i.op_str}")
