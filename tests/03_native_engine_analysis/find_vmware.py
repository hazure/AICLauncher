import pefile
from capstone import *

path = "/data/data/com.termux/files/home/aic/work/UnityPlayer_clean.dll"

# 1. 加载 PE
pe = pefile.PE(path)
image_base = pe.OPTIONAL_HEADER.ImageBase

# 2. 找到 VirtualBox 字符串所在的文件偏移和虚拟地址
with open(path, "rb") as f:
    data = f.read()

str_offset = data.find(b"VirtualBox")
if str_offset == -1:
    print("VirtualBox string not found in file. Aborting.")
    exit()

# 计算虚拟地址
str_va = 0
for section in pe.sections:
    if section.PointerToRawData <= str_offset < section.PointerToRawData + section.SizeOfRawData:
        str_va = image_base + section.VirtualAddress + (str_offset - section.PointerToRawData)
        break

print(f"VirtualBox string found at file offset: {str_offset}, Virtual Address: 0x{str_va:X}")

# 3. 遍历所有可执行节区，找引用这个地址的指令
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
