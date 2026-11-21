#!/usr/bin/env python3
"""
PE Section Table Parser & RVA to File Offset Converter
Author: Maintainer
Description:
    Analyzes PE32/PE32+ files, prints section headers, and accurately converts
    Relative Virtual Addresses (RVA) to physical file offsets.
    Used for verifying IL bytecode patch targets in Managed .NET assemblies.
"""

import sys
import struct
import os

def parse_pe_sections(pe_data):
    if pe_data[:2] != b'MZ':
        raise ValueError("Invalid DOS header signature")
    
    pe_offset = struct.unpack_from('<I', pe_data, 0x3C)[0]
    if pe_data[pe_offset:pe_offset+4] != b'PE\x00\x00':
        raise ValueError("Invalid PE signature")
    
    file_header_offset = pe_offset + 4
    num_sections = struct.unpack_from('<H', pe_data, file_header_offset + 2)[0]
    opt_header_size = struct.unpack_from('<H', pe_data, file_header_offset + 16)[0]
    opt_header_offset = file_header_offset + 20
    
    section_table_offset = opt_header_offset + opt_header_size
    sections = []
    
    for i in range(num_sections):
        sec_offset = section_table_offset + i * 40
        sec_raw = pe_data[sec_offset:sec_offset+40]
        name = sec_raw[:8].rstrip(b'\x00').decode('latin1', errors='replace')
        vsize, vaddr, raw_size, raw_ptr = struct.unpack_from('<IIII', sec_raw, 8)
        sections.append({
            'name': name,
            'virtual_address': vaddr,
            'virtual_size': vsize,
            'raw_offset': raw_ptr,
            'raw_size': raw_size
        })
    
    return sections

def rva_to_file_offset(sections, rva):
    for sec in sections:
        vaddr = sec['virtual_address']
        vsize = sec['virtual_size']
        if vaddr <= rva < vaddr + vsize:
            return sec['raw_offset'] + (rva - vaddr)
    return None

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <path_to_pe_file> [hex_rva...]")
        sys.exit(1)
        
    file_path = sys.argv[1]
    if not os.path.exists(file_path):
        print(f"File not found: {file_path}")
        sys.exit(1)
        
    with open(file_path, 'rb') as f:
        data = f.read()
        
    sections = parse_pe_sections(data)
    print(f"File: {file_path} (Size: {len(data)} bytes)")
    print(f"{'Section':<10} {'VirtualAddress':<16} {'VirtualSize':<14} {'RawOffset':<14} {'RawSize':<14}")
    print("-" * 68)
    for s in sections:
        print(f"{s['name']:<10} {hex(s['virtual_address']):<16} {hex(s['virtual_size']):<14} {hex(s['raw_offset']):<14} {hex(s['raw_size']):<14}")
        
    if len(sys.argv) > 2:
        print("\nRVA Translations:")
        for arg in sys.argv[2:]:
            rva = int(arg, 16) if arg.startswith(('0x', '0X')) else int(arg)
            off = rva_to_file_offset(sections, rva)
            if off is not None:
                hex_preview = data[off:off+16].hex()
                print(f"  RVA {hex(rva)} -> File Offset {hex(off)} (Bytes: {hex_preview})")
            else:
                print(f"  RVA {hex(rva)} -> Not found in any section")

if __name__ == '__main__':
    main()
