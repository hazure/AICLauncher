#!/usr/bin/env python3
"""
unmosaic.py - ECMA-335 IL-Level Unmosaic Patcher for Alice in Cradle (Assembly-CSharp.dll)
Description:
    Standalone, zero-offset-hardcoding CIL binary patcher targeting FnDrawMosaic in
    Assembly-CSharp.dll via full PE32/PE32+ and ECMA-335 CLI metadata parsing:
      1. Parses PE sections, CLR Runtime Header, CLI Metadata Root ("BSJB"),
         #~ / #- metadata tables (Module, TypeRef, TypeDef, Field, MethodDef),
         #Strings heap, and #Blob signature heap with strict bounds validation.
      2. Dynamically locates concrete CIL MethodDef entries matching FnDrawMosaic
         (ignoring abstract, P/Invoke, and MulticastDelegate runtime methods).
      3. Inspects each method's #Blob MethodDefSig RetType to emit stack-safe CIL:
           - bool / int32 -> ldc.i4.0 (0x16) + ret (0x2A)
           - void         -> ret (0x2A)
           - reference    -> ldnull (0x14) + ret (0x2A)
      4. Rewrites Tiny (1-byte) or Fat (12-byte) CIL method headers and bodies in-place
         (shrinking CodeSize cleanly, clearing CorILMethod_MoreSects / InitLocals on Fat
         headers to detach EH clauses safely, and zero-padding dead tail bytes),
         strictly preserving file size and zero RVA shift.
      5. Performs crash-safe atomic disk replacement (same-dir temp file + fsync + os.replace)
         with pre-write circuit-breaker verification and automatic .orig backup.
      6. Reuses patch_unsafeAssem.py SHA-256 sync logic (with standalone fallback) to
         recalculate Assembly-CSharp.dll SHA-256 and atomically update StreamingAssets/_sha.txt.
      7. Includes a self-contained --test suite that validates synthetic PE32/PE32+ Tiny & Fat
         assemblies, negative/malformed PE boundaries, and (when mcs/mono are available) real
         Mono JIT verification (mono --verify-all).

Usage:
    python3 unmosaic.py [path_to_dll_or_game_dir]
    python3 unmosaic.py --target /path/to/Assembly-CSharp.dll
    python3 unmosaic.py --restore
    python3 unmosaic.py --test
"""

import argparse
import hashlib
import inspect
import os
import shutil
import struct
import subprocess
import sys
import tempfile

# ---------------------------------------------------------------------------
# Custom Exceptions
# ---------------------------------------------------------------------------
class IncompatibleVersionError(Exception):
    """Raised when PE/CLI metadata is invalid or target method cannot be safely located."""
    pass


class SecurityVerificationError(Exception):
    """Raised when pre-write byte verification fails, triggering the circuit breaker."""
    pass


# ---------------------------------------------------------------------------
# SHA-256 & Manifest Synchronization (Reuses patch_unsafeAssem.py when present)
# ---------------------------------------------------------------------------
def _fallback_compute_sha256(file_path):
    """Calculates SHA-256 hash of a file in streaming 64 KiB chunks."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def _matches_manifest_entry(entry_path, target_name):
    """Exact case-insensitive basename match (prevents Assembly-CSharp-firstpass.dll collisions)."""
    norm_entry = entry_path.replace("\\", "/").strip()
    return os.path.basename(norm_entry).lower() == target_name.lower()


def _fallback_sync_sha_file(dll_path, new_hash, target_name="Assembly-CSharp.dll"):
    """Atomically updates target_name's SHA-256 entry in StreamingAssets/_sha.txt."""
    base_dir = os.path.dirname(os.path.abspath(dll_path))
    candidates = [
        os.path.join(base_dir, "_sha.txt"),
        os.path.join(base_dir, "StreamingAssets", "_sha.txt"),
        os.path.join(base_dir, "..", "StreamingAssets", "_sha.txt"),
        os.path.join(base_dir, "..", "..", "StreamingAssets", "_sha.txt"),
        os.path.join(base_dir, "..", "_sha.txt"),
    ]
    env_dir = os.environ.get("AIC_GAME_DIR")
    if env_dir:
        candidates.append(os.path.join(env_dir, "AliceInCradle_Data", "StreamingAssets", "_sha.txt"))
        candidates.append(os.path.join(env_dir, "StreamingAssets", "_sha.txt"))
        candidates.append(os.path.join(env_dir, "_sha.txt"))

    sha_file = None
    for c in candidates:
        norm = os.path.normpath(c)
        if os.path.isfile(norm):
            sha_file = norm
            break

    if not sha_file:
        print("[Notice] StreamingAssets/_sha.txt not found nearby. Skipping SHA manifest sync.")
        return False

    with open(sha_file, "r", encoding="utf-8", errors="ignore", newline="") as f:
        raw_lines = f.readlines()

    updated = False
    new_lines = []
    i = 0
    while i < len(raw_lines):
        line = raw_lines[i]
        stripped = line.strip()
        eol = "\r\n" if line.endswith("\r\n") else "\n"

        # Two-line format: <relative_path>\n<sha256>
        if (
            _matches_manifest_entry(stripped, target_name)
            and not any(c in stripped for c in " \t")
            and (i + 1 < len(raw_lines))
        ):
            next_eol = "\r\n" if raw_lines[i + 1].endswith("\r\n") else "\n"
            new_lines.append(line)
            new_lines.append(f"{new_hash}{next_eol}")
            updated = True
            i += 2
            continue

        # Single-line format: <sha256>  <relative_path>
        parts = stripped.split(None, 1)
        if len(parts) == 2 and _matches_manifest_entry(parts[1], target_name):
            new_lines.append(f"{new_hash}  {parts[1]}{eol}")
            updated = True
            i += 1
            continue

        new_lines.append(line)
        i += 1

    if updated:
        dirname, basename = os.path.split(sha_file)
        tmp_name = None
        try:
            with tempfile.NamedTemporaryFile(
                "w", dir=dirname, prefix=f".{basename}.", delete=False, encoding="utf-8", newline=""
            ) as tmp_f:
                tmp_name = tmp_f.name
                tmp_f.writelines(new_lines)
                tmp_f.flush()
                os.fsync(tmp_f.fileno())
            os.replace(tmp_name, sha_file)
        except Exception:
            if tmp_name and os.path.exists(tmp_name):
                os.remove(tmp_name)
            raise
        print(f"[OK] Synchronized {target_name} SHA-256 to {sha_file}")
        return True

    print(f"[Notice] {target_name} entry not found in {sha_file}.")
    return False


def _load_hash_helpers():
    """Attempts to reuse compute_sha256 & sync_sha_file from co-located patch_unsafeAssem.py."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)
    try:
        import patch_unsafeAssem as pua
        sha_fn = getattr(pua, "compute_sha256", _fallback_compute_sha256)
        sync_fn = getattr(pua, "sync_sha_file", None)
        if sync_fn is not None and "target_name" in inspect.signature(sync_fn).parameters:
            return sha_fn, sync_fn
        return sha_fn, _fallback_sync_sha_file
    except Exception:
        return _fallback_compute_sha256, _fallback_sync_sha_file


compute_sha256, _shared_sync_sha_file = _load_hash_helpers()


def sync_assembly_sha(dll_path, new_hash, target_name="Assembly-CSharp.dll"):
    """Synchronizes Assembly-CSharp.dll SHA-256 into StreamingAssets/_sha.txt."""
    return _shared_sync_sha_file(dll_path, new_hash, target_name=target_name)


# ---------------------------------------------------------------------------
# PE32/PE32+ & ECMA-335 CLI Metadata Parser
# ---------------------------------------------------------------------------
def parse_cli_metadata(dll_bytes):
    """
    Parses PE headers, section table, CLR Runtime Header, and CLI Metadata Root ("BSJB")
    with strict bounds checking against malformed/truncated inputs.
    Returns: (rva_to_offset_and_limit, streams_dict)
    """
    n = len(dll_bytes)
    if n < 0x40 or dll_bytes[:2] != b"MZ":
        raise IncompatibleVersionError("Invalid PE format: Missing MZ signature.")

    pe_off = struct.unpack_from("<I", dll_bytes, 0x3C)[0]
    if pe_off + 0x18 > n or dll_bytes[pe_off:pe_off + 4] != b"PE\x00\x00":
        raise IncompatibleVersionError("Invalid PE format: Missing or out-of-bounds PE signature.")

    num_sections = struct.unpack_from("<H", dll_bytes, pe_off + 0x06)[0]
    opt_hdr_size = struct.unpack_from("<H", dll_bytes, pe_off + 0x14)[0]
    opt_off = pe_off + 0x18
    if opt_hdr_size < 0x60 or opt_off + opt_hdr_size > n:
        raise IncompatibleVersionError("Invalid PE format: Truncated Optional Header.")

    magic = struct.unpack_from("<H", dll_bytes, opt_off)[0]
    if magic == 0x10B:  # PE32
        num_rva_off = opt_off + 92
        clr_dir_off = opt_off + 208
    elif magic == 0x20B:  # PE32+
        num_rva_off = opt_off + 108
        clr_dir_off = opt_off + 224
    else:
        raise IncompatibleVersionError(f"Unsupported PE Optional Header magic: 0x{magic:04X}.")

    if num_rva_off + 4 > opt_off + opt_hdr_size:
        raise IncompatibleVersionError("Invalid PE format: Truncated NumberOfRvaAndSizes.")
    num_rva_and_sizes = struct.unpack_from("<I", dll_bytes, num_rva_off)[0]
    if num_rva_and_sizes < 15 or clr_dir_off + 8 > opt_off + opt_hdr_size:
        raise IncompatibleVersionError("Not a valid .NET assembly: Missing CLR Data Directory (index 14).")

    clr_rva, clr_size = struct.unpack_from("<II", dll_bytes, clr_dir_off)
    if clr_rva == 0 or clr_size < 72:
        raise IncompatibleVersionError("Not a valid .NET assembly: Empty or invalid CLR Runtime Header.")

    sec_hdr_off = opt_off + opt_hdr_size
    if num_sections == 0 or sec_hdr_off + num_sections * 40 > n:
        raise IncompatibleVersionError("Invalid PE format: Truncated or empty Section Table.")

    sections = []
    for i in range(num_sections):
        s_off = sec_hdr_off + i * 40
        vsize, va, raw_size, raw_ptr = struct.unpack_from("<IIII", dll_bytes, s_off + 8)
        sections.append((va, vsize, raw_size, raw_ptr))

    def rva_to_offset_and_limit(rva):
        """Maps an RVA to (file_offset, section_raw_end) with strict bounds checks."""
        for va, vsize, raw_size, raw_ptr in sections:
            span = max(vsize, raw_size)
            if va <= rva < va + span:
                delta = rva - va
                if delta < raw_size:
                    off = raw_ptr + delta
                    sec_end = min(raw_ptr + raw_size, n)
                    if off < sec_end:
                        return off, sec_end
        return None, None

    clr_off, clr_sec_end = rva_to_offset_and_limit(clr_rva)
    if clr_off is None or clr_off + 72 > clr_sec_end:
        raise IncompatibleVersionError("Unable to resolve CLR Runtime Header within PE sections.")

    meta_rva, meta_size = struct.unpack_from("<II", dll_bytes, clr_off + 8)
    meta_off, meta_sec_end = rva_to_offset_and_limit(meta_rva)
    if meta_off is None or meta_size < 16 or meta_off + 16 > meta_sec_end:
        raise IncompatibleVersionError("Unable to resolve CLI Metadata Root within PE sections.")

    sig = struct.unpack_from("<I", dll_bytes, meta_off)[0]
    if sig != 0x424A5342:  # "BSJB"
        raise IncompatibleVersionError("Invalid CLI Metadata Root signature (expected 'BSJB').")

    vlen = struct.unpack_from("<I", dll_bytes, meta_off + 12)[0]
    if vlen > 256:
        raise IncompatibleVersionError(f"Malformed CLI Metadata version string length: {vlen}.")
    streams_off = meta_off + 16 + vlen
    if streams_off + 4 > min(meta_off + meta_size, meta_sec_end):
        raise IncompatibleVersionError("Truncated CLI Metadata Streams Header.")

    _, num_streams = struct.unpack_from("<HH", dll_bytes, streams_off)
    p = streams_off + 4
    meta_end = min(meta_off + meta_size, n)
    streams = {}

    for _ in range(num_streams):
        if p + 8 > meta_end:
            raise IncompatibleVersionError("Truncated CLI Metadata Stream descriptor.")
        s_r_off, s_size = struct.unpack_from("<II", dll_bytes, p)
        p += 8

        # ECMA-335 II.24.2.2: null-terminated ASCII stream name (<= 32 chars), 4-byte padded
        name_buf = bytearray()
        found_nul = False
        for _ in range(32):
            if p >= meta_end:
                break
            ch = dll_bytes[p]
            p += 1
            if ch == 0:
                found_nul = True
                break
            name_buf.append(ch)
        if not found_nul:
            raise IncompatibleVersionError("Malformed CLI Metadata stream name (missing NUL terminator).")

        rem = (p - meta_off) % 4
        if rem != 0:
            p += 4 - rem
        if p > meta_end:
            raise IncompatibleVersionError("Truncated CLI Metadata stream header padding.")

        abs_s_off = meta_off + s_r_off
        if s_r_off + s_size > meta_size or abs_s_off + s_size > n:
            raise IncompatibleVersionError(f"CLI stream '{name_buf.decode('ascii', errors='replace')}' out of bounds.")
        streams[name_buf.decode("ascii", errors="ignore")] = (abs_s_off, s_size)

    return rva_to_offset_and_limit, streams


def read_compressed_uint(buf, pos, end):
    """Decodes an ECMA-335 II.23.2 compressed unsigned integer."""
    if pos >= end:
        raise IncompatibleVersionError("Truncated compressed integer in CLI #Blob stream.")
    b0 = buf[pos]
    if (b0 & 0x80) == 0:
        return b0, pos + 1
    if (b0 & 0xC0) == 0x80:
        if pos + 2 > end:
            raise IncompatibleVersionError("Truncated 2-byte compressed integer in #Blob stream.")
        return ((b0 & 0x3F) << 8) | buf[pos + 1], pos + 2
    if (b0 & 0xE0) == 0xC0:
        if pos + 4 > end:
            raise IncompatibleVersionError("Truncated 4-byte compressed integer in #Blob stream.")
        val = (
            ((b0 & 0x1F) << 24)
            | (buf[pos + 1] << 16)
            | (buf[pos + 2] << 8)
            | buf[pos + 3]
        )
        return val, pos + 4
    raise IncompatibleVersionError(f"Invalid compressed integer leading byte: 0x{b0:02X}.")


def decode_return_type_stub(dll_bytes, blob_off, blob_size, sig_idx, force_mode="auto"):
    """
    Parses a MethodDefSig from #Blob (ECMA-335 II.23.2.1 & II.23.2.11) to determine
    the stack-safe CIL return stub:
      Returns: (stub_bytes, required_max_stack, return_type_label)
    """
    if force_mode == "false":
        return b"\x16\x2a", 1, "forced:bool(ldc.i4.0; ret)"
    if force_mode == "void":
        return b"\x2a", 0, "forced:void(ret)"

    blob_end = blob_off + blob_size
    if sig_idx == 0 or blob_off + sig_idx >= blob_end:
        raise IncompatibleVersionError(f"Invalid MethodDef signature #Blob index: 0x{sig_idx:X}.")

    sig_len, p = read_compressed_uint(dll_bytes, blob_off + sig_idx, blob_end)
    sig_end = p + sig_len
    if sig_len < 3 or sig_end > blob_end:
        raise IncompatibleVersionError("Malformed or truncated MethodDefSig blob.")

    call_conv = dll_bytes[p]
    p += 1

    # Generic method signature includes GenParamCount before ParamCount
    if call_conv & 0x10:
        _, p = read_compressed_uint(dll_bytes, p, sig_end)

    # ParamCount
    _, p = read_compressed_uint(dll_bytes, p, sig_end)

    # Skip optional/required CustomMod prefixes (ELEMENT_TYPE_CMOD_REQD=0x1F, CMOD_OPT=0x20)
    while p < sig_end and dll_bytes[p] in (0x1F, 0x20):
        p += 1
        _, p = read_compressed_uint(dll_bytes, p, sig_end)

    if p >= sig_end:
        raise IncompatibleVersionError("Truncated RetType in MethodDefSig.")

    elem_type = dll_bytes[p]

    if elem_type == 0x01:  # ELEMENT_TYPE_VOID
        return b"\x2a", 0, "void (ret)"
    if elem_type == 0x02:  # ELEMENT_TYPE_BOOLEAN
        return b"\x16\x2a", 1, "bool (ldc.i4.0; ret)"
    if elem_type in (0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09):  # char, i1..u4
        return b"\x16\x2a", 1, f"int32-compat[0x{elem_type:02X}] (ldc.i4.0; ret)"
    if elem_type in (0x0A, 0x0B):  # i8, u8
        return b"\x16\x6a\x2a", 1, "int64 (ldc.i4.0; conv.i8; ret)"
    if elem_type == 0x0C:  # r4
        return b"\x22\x00\x00\x00\x00\x2a", 1, "float32 (ldc.r4 0.0; ret)"
    if elem_type == 0x0D:  # r8
        return b"\x23\x00\x00\x00\x00\x00\x00\x00\x00\x2a", 1, "float64 (ldc.r8 0.0; ret)"
    if elem_type in (0x0E, 0x12, 0x14, 0x1C, 0x1D):  # string, class, array, object, szarray
        return b"\x14\x2a", 1, f"ref[0x{elem_type:02X}] (ldnull; ret)"
    if elem_type == 0x15 and p + 1 < sig_end and dll_bytes[p + 1] == 0x12:  # generic class
        return b"\x14\x2a", 1, "generic-class (ldnull; ret)"
    if elem_type in (0x0F, 0x18, 0x19, 0x1B):  # ptr, native int/uint, fnptr
        return b"\x16\xd3\x2a", 1, "nint (ldc.i4.0; conv.i; ret)"

    raise IncompatibleVersionError(
        f"Unsupported MethodDef return ELEMENT_TYPE 0x{elem_type:02X}. "
        "Refusing to emit potentially stack-corrupting IL. Use --mode false|void only if verified."
    )


def build_method_patch_plan(dll_bytes, method_info, stub_bytes, req_stack, ret_label):
    """
    Inspects the CIL method header (Tiny or Fat per ECMA-335 II.25.4) and constructs
    a strict zero-shift in-place replacement plan.
    """
    off = method_info["file_offset"]
    sec_end = method_info["sec_end"]
    if off is None or off >= sec_end:
        raise IncompatibleVersionError(
            f"Method {method_info['full_name']} RVA 0x{method_info['rva']:X} could not be mapped to file."
        )

    b0 = dll_bytes[off]
    fmt = b0 & 0x03
    stub_len = len(stub_bytes)

    if fmt == 0x02:  # CorILMethod_TinyFormat (1-byte header)
        orig_code_size = b0 >> 2
        total_len = 1 + orig_code_size
        if off + total_len > sec_end:
            raise IncompatibleVersionError(
                f"Tiny method {method_info['full_name']} body (size={orig_code_size}) exceeds section bounds."
            )

        body = dll_bytes[off + 1:off + total_len]
        # Idempotency: already starts with exact stub and only zero padding follows
        if (
            orig_code_size >= stub_len
            and body[:stub_len] == stub_bytes
            and all(x == 0 for x in body[stub_len:])
        ):
            return {
                **method_info,
                "header_kind": "Tiny",
                "orig_code_size": orig_code_size,
                "ret_label": ret_label,
                "expected_orig": dll_bytes[off:off + total_len],
                "patch_bytes": dll_bytes[off:off + total_len],
                "is_already_patched": True,
            }

        if orig_code_size < stub_len:
            raise IncompatibleVersionError(
                f"Tiny method {method_info['full_name']} body ({orig_code_size} B) is smaller than "
                f"required stub ({stub_len} B)."
            )

        new_hdr = bytes([(stub_len << 2) | 0x02])
        new_payload = new_hdr + stub_bytes + (b"\x00" * (orig_code_size - stub_len))
        return {
            **method_info,
            "header_kind": "Tiny",
            "orig_code_size": orig_code_size,
            "ret_label": ret_label,
            "expected_orig": dll_bytes[off:off + total_len],
            "patch_bytes": new_payload,
            "is_already_patched": False,
        }

    if fmt == 0x03:  # CorILMethod_FatFormat (12-byte header)
        if off + 12 > sec_end:
            raise IncompatibleVersionError(
                f"Fat method {method_info['full_name']} header truncated at offset 0x{off:X}."
            )
        flags_and_size, max_stack, orig_code_size, local_var_sig_tok = struct.unpack_from("<HHII", dll_bytes, off)
        hdr_dwords = flags_and_size >> 12
        if hdr_dwords != 3:
            raise IncompatibleVersionError(
                f"Fat method {method_info['full_name']} has unexpected header size: {hdr_dwords} dwords."
            )
        flags = flags_and_size & 0x0FFF
        total_len = 12 + orig_code_size
        if off + total_len > sec_end:
            raise IncompatibleVersionError(
                f"Fat method {method_info['full_name']} body (size={orig_code_size}) exceeds section bounds."
            )

        body = dll_bytes[off + 12:off + total_len]
        has_more_sects = bool(flags & 0x0008)
        # Idempotency: EH clauses detached, stack sufficient, stub written with zero tail
        if (
            not has_more_sects
            and max_stack >= req_stack
            and orig_code_size >= stub_len
            and body[:stub_len] == stub_bytes
            and all(x == 0 for x in body[stub_len:])
        ):
            return {
                **method_info,
                "header_kind": "Fat",
                "orig_code_size": orig_code_size,
                "ret_label": ret_label,
                "expected_orig": dll_bytes[off:off + total_len],
                "patch_bytes": dll_bytes[off:off + total_len],
                "is_already_patched": True,
            }

        if orig_code_size < stub_len:
            raise IncompatibleVersionError(
                f"Fat method {method_info['full_name']} body ({orig_code_size} B) is smaller than "
                f"required stub ({stub_len} B)."
            )

        # Clear CorILMethod_MoreSects (0x0008) to safely detach EH clauses & InitLocals (0x0010)
        new_flags = flags & ~(0x0008 | 0x0010)
        new_flags_and_size = (hdr_dwords << 12) | new_flags
        new_max_stack = max(max_stack, req_stack)
        new_hdr = struct.pack("<HHII", new_flags_and_size, new_max_stack, stub_len, 0)
        new_payload = new_hdr + stub_bytes + (b"\x00" * (orig_code_size - stub_len))
        return {
            **method_info,
            "header_kind": "Fat",
            "orig_code_size": orig_code_size,
            "ret_label": ret_label,
            "expected_orig": dll_bytes[off:off + total_len],
            "patch_bytes": new_payload,
            "is_already_patched": False,
        }

    raise IncompatibleVersionError(
        f"Unrecognized CIL method header format bits 0x{fmt:02X} for {method_info['full_name']} "
        f"at offset 0x{off:X}."
    )


def locate_target_methods(dll_bytes, method_name="FnDrawMosaic", type_filter=None, force_mode="auto"):
    """
    Walks ECMA-335 TypeDef (0x02) and MethodDef (0x06) metadata tables to dynamically
    locate concrete CIL implementations of `method_name` without hardcoded offsets.
    """
    rva_to_offset_and_limit, streams = parse_cli_metadata(dll_bytes)

    tilde_stream = streams.get("#~") or streams.get("#-")
    if not tilde_stream:
        raise IncompatibleVersionError("CLI metadata is missing '#~' / '#-' tables stream.")
    if "#Strings" not in streams or "#Blob" not in streams:
        raise IncompatibleVersionError("CLI metadata is missing '#Strings' or '#Blob' heap.")

    t_off, t_size = tilde_stream
    s_off, s_size = streams["#Strings"]
    bl_off, bl_size = streams["#Blob"]
    t_end = t_off + t_size
    s_end = s_off + s_size

    if t_size < 24:
        raise IncompatibleVersionError("Truncated CLI '#~' metadata table header.")

    _, _, _, heap_sizes, _, valid_mask, _ = struct.unpack_from("<IBBBBQQ", dll_bytes, t_off)
    str_idx_sz = 4 if (heap_sizes & 0x01) else 2
    guid_idx_sz = 4 if (heap_sizes & 0x02) else 2
    blob_idx_sz = 4 if (heap_sizes & 0x04) else 2

    tp = t_off + 24
    rows = [0] * 64
    for tbl_id in range(64):
        if (valid_mask >> tbl_id) & 1:
            if tp + 4 > t_end:
                raise IncompatibleVersionError("Truncated CLI table row-count array.")
            rows[tbl_id] = struct.unpack_from("<I", dll_bytes, tp)[0]
            tp += 4

    # Optional ExtraData uint32 (bit 6 of HeapSizes in some CLR emitters)
    if heap_sizes & 0x40:
        if tp + 4 > t_end:
            raise IncompatibleVersionError("Truncated CLI ExtraData header.")
        tp += 4

    def tbl_idx_sz(t_id):
        return 4 if rows[t_id] >= 65536 else 2

    def coded_idx_sz(tag_bits, tbl_ids):
        limit = 1 << (16 - tag_bits)
        return 4 if max(rows[t] for t in tbl_ids) >= limit else 2

    def read_idx(offset, size):
        return struct.unpack_from("<I" if size == 4 else "<H", dll_bytes, offset)[0]

    def read_string(str_idx):
        if str_idx == 0:
            return ""
        if str_idx >= s_size:
            raise IncompatibleVersionError(f"#Strings index 0x{str_idx:X} out of bounds.")
        nul = dll_bytes.find(b"\x00", s_off + str_idx, s_end)
        if nul == -1:
            raise IncompatibleVersionError(f"Unterminated string in #Strings heap at 0x{str_idx:X}.")
        return dll_bytes[s_off + str_idx:nul].decode("utf-8", errors="replace")

    # Compute exact table row sizes up to 0x06 MethodDef (ECMA-335 II.22)
    mod_row_sz = 2 + str_idx_sz + 3 * guid_idx_sz
    tref_row_sz = coded_idx_sz(2, [0x00, 0x1A, 0x23, 0x01]) + 2 * str_idx_sz
    tdef_row_sz = (
        4
        + 2 * str_idx_sz
        + coded_idx_sz(2, [0x02, 0x01, 0x1B])
        + tbl_idx_sz(0x04)
        + tbl_idx_sz(0x06)
    )
    fptr_row_sz = tbl_idx_sz(0x04)
    fld_row_sz = 2 + str_idx_sz + blob_idx_sz
    mptr_row_sz = tbl_idx_sz(0x06)
    mdef_row_sz = 4 + 2 + 2 + str_idx_sz + blob_idx_sz + tbl_idx_sz(0x08)

    # Skip 0x00 Module & 0x01 TypeRef
    tp += rows[0x00] * mod_row_sz
    tp += rows[0x01] * tref_row_sz

    tdef_off = tp
    tdef_end = tdef_off + rows[0x02] * tdef_row_sz
    mdef_off = (
        tdef_end
        + rows[0x03] * fptr_row_sz
        + rows[0x04] * fld_row_sz
        + rows[0x05] * mptr_row_sz
    )
    mdef_end = mdef_off + rows[0x06] * mdef_row_sz

    if mdef_end > t_end:
        raise IncompatibleVersionError("CLI MethodDef table extends beyond '#~' stream bounds.")

    # Parse TypeDef table to resolve owning Namespace.TypeName for every MethodDef RID
    mlist_field_offset = tdef_row_sz - tbl_idx_sz(0x06)
    mlist_idx_sz = tbl_idx_sz(0x06)
    typedef_spans = []
    for i in range(rows[0x02]):
        ro = tdef_off + i * tdef_row_sz
        tname = read_string(read_idx(ro + 4, str_idx_sz))
        tns = read_string(read_idx(ro + 4 + str_idx_sz, str_idx_sz))
        mlist = read_idx(ro + mlist_field_offset, mlist_idx_sz)
        full_type = f"{tns}.{tname}" if tns else tname
        typedef_spans.append((mlist, full_type))

    def owner_type_for_method_rid(rid):
        """Binary search TypeDef MethodList spans to resolve declaring type of 1-based MethodDef RID."""
        if not typedef_spans:
            return "<Global>"
        lo, hi = 0, len(typedef_spans) - 1
        res = 0
        while lo <= hi:
            mid = (lo + hi) // 2
            if typedef_spans[mid][0] <= rid:
                res = mid
                lo = mid + 1
            else:
                hi = mid - 1
        return typedef_spans[res][1]

    # Walk MethodDef (0x06) rows: exact name first, fallback to case-insensitive if 0 exact hits
    exact_hits = []
    ci_hits = []
    target_lower = method_name.lower()

    for idx in range(rows[0x06]):
        ro = mdef_off + idx * mdef_row_sz
        rva, impl_flags, flags = struct.unpack_from("<IHH", dll_bytes, ro)

        # Concrete CIL methods only:
        #   rva != 0
        #   (impl_flags & 0x0003) == 0 -> miIL (excludes runtime delegate Invoke, native)
        #   (impl_flags & 0x1000) == 0 -> excludes miInternalCall
        #   (flags & 0x0400) == 0      -> excludes mdAbstract
        #   (flags & 0x2000) == 0      -> excludes mdPinvokeImpl
        if rva == 0 or (impl_flags & 0x1003) != 0 or (flags & 0x2400) != 0:
            continue

        m_name = read_string(read_idx(ro + 8, str_idx_sz))
        is_exact = (m_name == method_name)
        is_ci = (not is_exact) and (m_name.lower() == target_lower)
        if not (is_exact or is_ci):
            continue

        rid = idx + 1
        decl_type = owner_type_for_method_rid(rid)
        if type_filter and (type_filter.lower() not in decl_type.lower()):
            continue

        sig_idx = read_idx(ro + 8 + str_idx_sz, blob_idx_sz)
        file_off, sec_end = rva_to_offset_and_limit(rva)
        info = {
            "token": 0x06000000 | rid,
            "declaring_type": decl_type,
            "method_name": m_name,
            "full_name": f"{decl_type}::{m_name}",
            "rva": rva,
            "file_offset": file_off,
            "sec_end": sec_end,
            "sig_idx": sig_idx,
        }
        if is_exact:
            exact_hits.append(info)
        else:
            ci_hits.append(info)

    matched = exact_hits if exact_hits else ci_hits
    if not matched:
        raise IncompatibleVersionError(
            f"Unable to locate concrete CIL method '{method_name}' in MethodDef metadata table."
        )

    # Deduplicate by RVA while preserving deterministic order
    seen_rvas = set()
    plans = []
    for info in matched:
        if info["rva"] in seen_rvas:
            continue
        seen_rvas.add(info["rva"])
        stub_bytes, req_stack, ret_label = decode_return_type_stub(
            dll_bytes, bl_off, bl_size, info["sig_idx"], force_mode=force_mode
        )
        plan = build_method_patch_plan(dll_bytes, info, stub_bytes, req_stack, ret_label)
        plans.append(plan)

    return plans


# ---------------------------------------------------------------------------
# Atomic In-Place Binary Patching & Restoration
# ---------------------------------------------------------------------------
def patch_binary_atomic(target_path, patch_entries):
    """
    Atomically rewrites one or more non-overlapping equal-length regions via
    a same-directory temporary file + fsync + os.replace.
    patch_entries: list of (offset, expected_orig_bytes, new_patch_bytes)
    """
    if not patch_entries:
        return

    target_abs = os.path.abspath(target_path)
    if not os.path.isfile(target_abs):
        raise FileNotFoundError(f"Target assembly does not exist: {target_abs}")
    if not os.access(target_abs, os.W_OK):
        raise PermissionError(f"Target file is not writable: {target_abs}")

    file_size = os.path.getsize(target_abs)
    sorted_entries = sorted(patch_entries, key=lambda x: x[0])

    # Validate zero-shift invariance, boundaries, and non-overlap
    prev_end = 0
    for off, expected_orig, new_bytes in sorted_entries:
        if len(expected_orig) != len(new_bytes) or len(new_bytes) == 0:
            raise ValueError("Patch invariant violated: Replacement length mismatch.")
        if off < 0 or off + len(new_bytes) > file_size:
            raise ValueError(f"Patch offset 0x{off:X} exceeds file size ({file_size} bytes).")
        if off < prev_end:
            raise ValueError(f"Overlapping patch regions detected near offset 0x{off:X}.")
        prev_end = off + len(new_bytes)

    # Create safety backup (.orig) if not already present
    bak_path = target_abs + ".orig"
    if not os.path.exists(bak_path):
        shutil.copy2(target_abs, bak_path)
        print(f"[OK] Created backup at {bak_path}")

    dirname, basename = os.path.split(target_abs)
    tmp_name = None
    try:
        with tempfile.NamedTemporaryFile(
            "wb", dir=dirname, prefix=f".{basename}.", delete=False
        ) as tmp_f:
            tmp_name = tmp_f.name
            with open(target_abs, "rb") as orig_f:
                # 1. Pre-write circuit-breaker verification of all patch slices
                for off, expected_orig, _ in sorted_entries:
                    orig_f.seek(off)
                    actual = orig_f.read(len(expected_orig))
                    if actual != expected_orig:
                        raise SecurityVerificationError(
                            f"Pre-write signature mismatch at offset 0x{off:X}. "
                            "Circuit breaker triggered: Refusing to modify file."
                        )

                # 2. Single-pass streaming copy with exact in-place substitutions
                cursor = 0
                orig_f.seek(0)
                for off, _, new_bytes in sorted_entries:
                    gap = off - cursor
                    while gap > 0:
                        chunk = orig_f.read(min(65536, gap))
                        if not chunk:
                            raise RuntimeError("Unexpected EOF while streaming original assembly.")
                        tmp_f.write(chunk)
                        gap -= len(chunk)
                    tmp_f.write(new_bytes)
                    orig_f.seek(off + len(new_bytes))
                    cursor = off + len(new_bytes)

                while chunk := orig_f.read(65536):
                    tmp_f.write(chunk)

            tmp_f.flush()
            os.fsync(tmp_f.fileno())

        # 3. Verify strict file-size invariance before replacing target
        if os.path.getsize(tmp_name) != file_size:
            raise RuntimeError("Integrity check failed: File size altered during atomic patch.")

        # 4. Atomic swap
        os.replace(tmp_name, target_abs)
    except Exception:
        if tmp_name and os.path.exists(tmp_name):
            os.remove(tmp_name)
        raise


def restore_backup_atomic(dll_path, auto_sync_sha=True):
    """Atomically restores Assembly-CSharp.dll from its .orig backup and resyncs _sha.txt."""
    target_abs = os.path.abspath(dll_path)
    bak_path = target_abs + ".orig"
    if not os.path.isfile(bak_path):
        raise FileNotFoundError(f"Backup file not found: {bak_path}")

    dirname, basename = os.path.split(target_abs)
    tmp_name = None
    try:
        with tempfile.NamedTemporaryFile(
            "wb", dir=dirname, prefix=f".{basename}.restore.", delete=False
        ) as tmp_f:
            tmp_name = tmp_f.name
            with open(bak_path, "rb") as src_f:
                while chunk := src_f.read(65536):
                    tmp_f.write(chunk)
            tmp_f.flush()
            os.fsync(tmp_f.fileno())
        os.replace(tmp_name, target_abs)
    except Exception:
        if tmp_name and os.path.exists(tmp_name):
            os.remove(tmp_name)
        raise

    print(f"[OK] Restored {target_abs} from {bak_path} (atomic).")
    if auto_sync_sha:
        sha = compute_sha256(target_abs)
        print(f"[OK] Restored assembly SHA-256: {sha}")
        sync_assembly_sha(target_abs, sha, target_name=os.path.basename(target_abs))
    return True


def patch_assembly(
    dll_path,
    method_name="FnDrawMosaic",
    type_filter=None,
    force_mode="auto",
    auto_sync_sha=True,
    force_skip_sha=False,
):
    """High-level unmosaic patching pipeline with SHA-256 synchronization."""
    if not os.path.isfile(dll_path):
        raise FileNotFoundError(f"Target assembly does not exist: {dll_path}")

    print(f"Target assembly: {dll_path}")
    manifest_target = os.path.basename(dll_path)

    if force_skip_sha:
        print("[Notice] --force-skip-sha active: Skipping CIL patching; recalculating SHA-256 only.")
        new_sha = compute_sha256(dll_path)
        print(f"[OK] Assembly SHA-256: {new_sha}")
        synced = sync_assembly_sha(dll_path, new_sha, target_name=manifest_target)
        if not synced:
            print("[Warning] Could not locate StreamingAssets/_sha.txt to synchronize.")
            return False
        return True

    with open(dll_path, "rb") as f:
        dll_bytes = f.read()

    plans = locate_target_methods(
        dll_bytes,
        method_name=method_name,
        type_filter=type_filter,
        force_mode=force_mode,
    )

    pending = []
    for p in plans:
        status = "ALREADY PATCHED" if p["is_already_patched"] else "PENDING"
        print(
            f"[Locate] {p['full_name']} (Token=0x{p['token']:08X}, RVA=0x{p['rva']:X}, "
            f"Off=0x{p['file_offset']:X}, {p['header_kind']} CodeSize={p['orig_code_size']}, "
            f"Ret={p['ret_label']}) -> {status}"
        )
        if not p["is_already_patched"]:
            pending.append((p["file_offset"], p["expected_orig"], p["patch_bytes"]))

    if not pending:
        print(f"[OK] All {len(plans)} matched method(s) already patched. No binary changes needed.")
        if auto_sync_sha:
            cur_sha = compute_sha256(dll_path)
            sync_assembly_sha(dll_path, cur_sha, target_name=manifest_target)
        return True

    patch_binary_atomic(dll_path, pending)
    print(f"[OK] Successfully patched {len(pending)} method(s) in-place (atomic, zero-shift).")

    if auto_sync_sha:
        new_sha = compute_sha256(dll_path)
        print(f"[OK] New assembly SHA-256: {new_sha}")
        sync_assembly_sha(dll_path, new_sha, target_name=manifest_target)

    return True


# ---------------------------------------------------------------------------
# Target Path Resolution (Zero Hardcoded Private Paths)
# ---------------------------------------------------------------------------
def resolve_target_dll(cli_arg=None):
    """Resolves Assembly-CSharp.dll from CLI argument, AIC_GAME_DIR, or relative paths."""
    def _probe_dir(d):
        for sub in (
            "Assembly-CSharp.dll",
            os.path.join("AliceInCradle_Data", "Managed", "Assembly-CSharp.dll"),
            os.path.join("Managed", "Assembly-CSharp.dll"),
        ):
            cand = os.path.join(d, sub)
            if os.path.isfile(cand) and os.access(cand, os.R_OK):
                return os.path.abspath(cand)
        return None

    if cli_arg:
        if os.path.isfile(cli_arg) and os.access(cli_arg, os.R_OK):
            return os.path.abspath(cli_arg)
        if os.path.isdir(cli_arg):
            found = _probe_dir(cli_arg)
            if found:
                return found

    env_dir = os.environ.get("AIC_GAME_DIR")
    if env_dir and os.path.exists(env_dir):
        if os.path.isfile(env_dir) and os.access(env_dir, os.R_OK):
            return os.path.abspath(env_dir)
        if os.path.isdir(env_dir):
            found = _probe_dir(env_dir)
            if found:
                return found

    for rel in (
        "Assembly-CSharp.dll",
        os.path.join("Managed", "Assembly-CSharp.dll"),
        os.path.join("AliceInCradle_Data", "Managed", "Assembly-CSharp.dll"),
    ):
        if os.path.isfile(rel) and os.access(rel, os.R_OK):
            return os.path.abspath(rel)

    return None


# ---------------------------------------------------------------------------
# Self-Contained Synthetic PE/CLI Builder & Verification Test Suite
# ---------------------------------------------------------------------------
def _build_synthetic_dotnet_dll(is_64=False, method_specs=None):
    """
    Constructs a minimal, strictly ECMA-335 compliant PE32 or PE32+ .NET assembly in memory.
    Used by --test to validate metadata parsing, Tiny/Fat rewriting, and boundary defenses
    without requiring real game binaries or an external C# compiler.

    method_specs: list of dicts:
      {
        "ns": "nel", "type": "MosaicShower", "name": "FnDrawMosaic",
        "ret_elem": 0x02,  # 0x02=bool, 0x01=void
        "header_kind": "Tiny" | "Fat" | "None",  # "None" => RVA=0 (delegate/abstract)
        "impl_flags": 0x0000, "flags": 0x0006,
        "il_body": bytes,
        "fat_flags": 0x0013, "fat_max_stack": 4, "fat_local_tok": 0x11000001,
      }
    """
    if method_specs is None:
        method_specs = []

    text_va = 0x2000
    text_raw_ptr = 0x0200
    clr_rva = text_va  # 72 bytes at start of .text

    # 1. Lay out method bodies starting right after 72-byte CLR header (aligned to 4)
    il_cursor = 0x50
    il_blob = bytearray(0x50)  # placeholder for CLR header [0..72)
    method_rvas = []

    for spec in method_specs:
        hdr_kind = spec.get("header_kind", "Tiny")
        if hdr_kind == "None":
            method_rvas.append(0)
            continue
        # 4-byte align Fat headers
        while len(il_blob) % 4 != 0:
            il_blob.append(0x00)
        m_rva = text_va + len(il_blob)
        method_rvas.append(m_rva)
        body = spec["il_body"]
        if hdr_kind == "Tiny":
            assert len(body) < 64
            il_blob.append((len(body) << 2) | 0x02)
            il_blob.extend(body)
        elif hdr_kind == "Fat":
            fat_flags = spec.get("fat_flags", 0x0013)  # FatFormat | InitLocals
            fat_hdr = struct.pack(
                "<HHII",
                (3 << 12) | (fat_flags & 0x0FFF),
                spec.get("fat_max_stack", 4),
                len(body),
                spec.get("fat_local_tok", 0x11000001),
            )
            il_blob.extend(fat_hdr)
            il_blob.extend(body)
            # If MoreSects set, append a 12-byte mock tiny EH data section (4-byte aligned)
            if fat_flags & 0x0008:
                while len(il_blob) % 4 != 0:
                    il_blob.append(0x00)
                il_blob.extend(b"\x01\x10\x00\x00" + b"\x00" * 12)

    while len(il_blob) % 4 != 0:
        il_blob.append(0x00)

    # 2. Build #Strings, #Blob, #GUID heaps
    strings_heap = bytearray(b"\x00")
    str_cache = {"": 0}

    def add_str(s):
        if s in str_cache:
            return str_cache[s]
        idx = len(strings_heap)
        strings_heap.extend(s.encode("utf-8") + b"\x00")
        str_cache[s] = idx
        return idx

    blob_heap = bytearray(b"\x00")

    def add_method_sig(ret_elem):
        idx = len(blob_heap)
        # Length=3, HASTHIS(0x20), ParamCount=0, RetType=ret_elem
        sig = bytes([3, 0x20, 0x00, ret_elem])
        blob_heap.extend(sig)
        return idx

    guid_heap = b"\x11" * 16

    # Group methods by (ns, type) preserving order
    type_groups = []
    for spec, m_rva in zip(method_specs, method_rvas):
        key = (spec.get("ns", ""), spec.get("type", "MockClass"))
        if not type_groups or type_groups[-1][0] != key:
            type_groups.append((key, []))
        type_groups[-1][1].append((spec, m_rva))

    # Pre-populate strings & signatures
    mod_name_idx = add_str("Assembly-CSharp.dll")
    global_name_idx = add_str("<Module>")
    typedef_rows = []
    methoddef_rows = []

    # Row 1 of TypeDef: <Module>
    typedef_rows.append((0, global_name_idx, 0, 0, 1, 1))
    next_method_rid = 1
    for (ns, tname), m_items in type_groups:
        typedef_rows.append((0x00100001, add_str(tname), add_str(ns), 0, 1, next_method_rid))
        for spec, m_rva in m_items:
            sig_idx = add_method_sig(spec.get("ret_elem", 0x02))
            methoddef_rows.append((
                m_rva,
                spec.get("impl_flags", 0x0000),
                spec.get("flags", 0x0006),
                add_str(spec["name"]),
                sig_idx,
                1,
            ))
            next_method_rid += 1

    # 3. Build #~ stream (Tables present: 0x00 Module, 0x02 TypeDef, 0x06 MethodDef)
    valid_mask = (1 << 0x00) | (1 << 0x02) | (1 << 0x06)
    tilde = bytearray()
    tilde.extend(struct.pack("<IBBBBQQ", 0, 2, 0, 0, 1, valid_mask, 0))
    tilde.extend(struct.pack("<III", 1, len(typedef_rows), len(methoddef_rows)))
    # 0x00 Module (1 row): Generation(2) Name(2) Mvid(2) EncId(2) EncBaseId(2)
    tilde.extend(struct.pack("<HHHHH", 0, mod_name_idx, 1, 0, 0))
    # 0x02 TypeDef: Flags(4) Name(2) Namespace(2) Extends(2) FieldList(2) MethodList(2)
    for r in typedef_rows:
        tilde.extend(struct.pack("<IHHHHH", *r))
    # 0x06 MethodDef: RVA(4) ImplFlags(2) Flags(2) Name(2) Signature(2) ParamList(2)
    for r in methoddef_rows:
        tilde.extend(struct.pack("<IHHHHH", *r))

    # 4-byte pad all heaps
    for buf in (tilde, strings_heap, blob_heap):
        while len(buf) % 4 != 0:
            buf.append(0x00)

    # 4. Assemble CLI Metadata Root ("BSJB")
    ver_str = b"v4.0.30319\x00\x00"  # 12 bytes (4-byte aligned)
    stream_defs = [
        ("#~", tilde),
        ("#Strings", strings_heap),
        ("#GUID", guid_heap),
        ("#Blob", blob_heap),
    ]
    # Header size = 16 + len(ver_str) + 4 + sum(8 + aligned(len(name)+1))
    hdr_sz = 16 + len(ver_str) + 4
    for sname, _ in stream_defs:
        nlen = len(sname) + 1
        hdr_sz += 8 + ((nlen + 3) & ~3)

    meta_blob = bytearray()
    meta_blob.extend(struct.pack("<IHHII", 0x424A5342, 1, 1, 0, len(ver_str)))
    meta_blob.extend(ver_str)
    meta_blob.extend(struct.pack("<HH", 0, len(stream_defs)))

    cur_s_off = hdr_sz
    for sname, sdata in stream_defs:
        meta_blob.extend(struct.pack("<II", cur_s_off, len(sdata)))
        nb = sname.encode("ascii") + b"\x00"
        while len(nb) % 4 != 0:
            nb += b"\x00"
        meta_blob.extend(nb)
        cur_s_off += len(sdata)
    for _, sdata in stream_defs:
        meta_blob.extend(sdata)

    meta_rva = text_va + len(il_blob)
    il_blob.extend(meta_blob)

    # Patch 72-byte IMAGE_COR20_HEADER at start of .text
    cor_hdr = struct.pack("<IHHI II I", 72, 2, 5, meta_rva, len(meta_blob), 1, 0)
    cor_hdr = cor_hdr.ljust(72, b"\x00")
    il_blob[0:72] = cor_hdr

    # Align .text raw data to 0x200
    text_vsize = len(il_blob)
    text_raw_size = (text_vsize + 0x1FF) & ~0x1FF
    il_blob.extend(b"\x00" * (text_raw_size - text_vsize))

    # 5. Assemble DOS + PE32/PE32+ Headers + 1 Section Header (.text)
    pe_off = 0x80
    opt_hdr_size = 0xF0 if is_64 else 0xE0
    headers = bytearray(text_raw_ptr)
    headers[0:2] = b"MZ"
    struct.pack_into("<I", headers, 0x3C, pe_off)
    headers[pe_off:pe_off + 4] = b"PE\x00\x00"
    machine = 0x8664 if is_64 else 0x014C
    struct.pack_into("<HHIIIHH", headers, pe_off + 4, machine, 1, 0, 0, 0, opt_hdr_size, 0x2102)

    opt_off = pe_off + 0x18
    struct.pack_into("<H", headers, opt_off, 0x20B if is_64 else 0x10B)
    num_rva_off = opt_off + (108 if is_64 else 92)
    clr_dir_off = opt_off + (224 if is_64 else 208)
    struct.pack_into("<I", headers, num_rva_off, 16)
    struct.pack_into("<II", headers, clr_dir_off, clr_rva, 72)

    sec_off = opt_off + opt_hdr_size
    headers[sec_off:sec_off + 8] = b".text\x00\x00\x00"
    struct.pack_into("<IIII", headers, sec_off + 8, text_vsize, text_va, text_raw_size, text_raw_ptr)
    struct.pack_into("<I", headers, sec_off + 36, 0x60000020)

    return bytes(headers + il_blob)


def run_self_tests():
    """Comprehensive standalone verification suite (no real game DLL required)."""
    print("=== Running unmosaic.py Automated Verification Suite ===")

    # -----------------------------------------------------------------------
    # Test 1: Synthetic PE32 Tiny Method + Decoy Delegate/Abstract + _sha.txt Sync
    # -----------------------------------------------------------------------
    before_il = bytes.fromhex("021f2a582a")       # ldarg.0; ldc.i4.s 42; add; ret
    mosaic_il = bytes.fromhex("170000000000002a")  # ldc.i4.1; nop*6; ret (8 bytes)
    after_il = bytes.fromhex("021f0a5a2a")        # ldarg.0; ldc.i4.s 10; mul; ret

    synth_pe32 = _build_synthetic_dotnet_dll(
        is_64=False,
        method_specs=[
            # Decoy 1: MulticastDelegate Invoke (RVA=0, miRuntime=0x0003)
            {"ns": "nel", "type": "FnDrawMosaic", "name": "FnDrawMosaic", "header_kind": "None", "impl_flags": 0x0003},
            # Decoy 2: Abstract interface method (RVA=0, mdAbstract=0x0400)
            {"ns": "nel", "type": "IMosaic", "name": "FnDrawMosaic", "header_kind": "None", "flags": 0x0406},
            # Neighbour before
            {"ns": "nel", "type": "MosaicShower", "name": "BeforeMosaic", "ret_elem": 0x08, "header_kind": "Tiny", "il_body": before_il},
            # Target Tiny bool FnDrawMosaic
            {"ns": "nel", "type": "MosaicShower", "name": "FnDrawMosaic", "ret_elem": 0x02, "header_kind": "Tiny", "il_body": mosaic_il},
            # Neighbour after
            {"ns": "nel", "type": "MosaicShower", "name": "AfterMosaic", "ret_elem": 0x08, "header_kind": "Tiny", "il_body": after_il},
        ],
    )

    with tempfile.TemporaryDirectory() as td:
        managed_dir = os.path.join(td, "AliceInCradle_Data", "Managed")
        sa_dir = os.path.join(td, "AliceInCradle_Data", "StreamingAssets")
        os.makedirs(managed_dir)
        os.makedirs(sa_dir)

        dll_p = os.path.join(managed_dir, "Assembly-CSharp.dll")
        sha_p = os.path.join(sa_dir, "_sha.txt")
        with open(dll_p, "wb") as f:
            f.write(synth_pe32)
        with open(sha_p, "w", encoding="utf-8") as f:
            f.write(
                "Managed/Assembly-CSharp-firstpass.dll\nkeep_firstpass_hash\n"
                "Managed/Assembly-CSharp.dll\nold_assembly_hash\n"
                "Managed/unsafeAssem.dll\nkeep_unsafe_hash\n"
            )

        orig_sz = os.path.getsize(dll_p)
        ok = patch_assembly(dll_p, auto_sync_sha=True)
        assert ok, "Test 1 Failed: patch_assembly returned False"
        assert os.path.getsize(dll_p) == orig_sz, "Test 1 Failed: File size changed!"

        patched = open(dll_p, "rb").read()
        # Neighbouring methods intact
        assert before_il in patched, "Test 1 Failed: BeforeMosaic corrupted!"
        assert after_il in patched, "Test 1 Failed: AfterMosaic corrupted!"
        # Target method rewritten to Tiny CodeSize=2 (0x0A), ldc.i4.0 (0x16), ret (0x2A), 6x nop
        assert bytes([0x0A, 0x16, 0x2A]) + b"\x00" * 6 in patched, "Test 1 Failed: Tiny stub mismatch!"

        # SHA-256 sync checked (exact basename match: -firstpass.dll & unsafeAssem.dll untouched)
        new_sha = compute_sha256(dll_p)
        sha_txt = open(sha_p, "r", encoding="utf-8").read()
        assert f"Managed/Assembly-CSharp.dll\n{new_sha}\n" in sha_txt, "Test 1 Failed: _sha.txt not updated!"
        assert "keep_firstpass_hash" in sha_txt, "Test 1 Failed: Assembly-CSharp-firstpass.dll overwritten!"
        assert "keep_unsafe_hash" in sha_txt, "Test 1 Failed: unsafeAssem.dll overwritten!"
        print("[PASS] Test 1: Synthetic PE32 Tiny bool FnDrawMosaic + decoy isolation + exact _sha.txt sync")

        # Test 1b: Idempotency (re-run on already patched assembly)
        ok2 = patch_assembly(dll_p, auto_sync_sha=True)
        assert ok2 and open(dll_p, "rb").read() == patched, "Test 1b Failed: Idempotency broken!"

        # Test 1c: Atomic restore from .orig backup
        restore_backup_atomic(dll_p, auto_sync_sha=True)
        assert open(dll_p, "rb").read() == synth_pe32, "Test 1c Failed: Restore did not match original!"
        print("[PASS] Test 2: Idempotency and atomic .orig backup restoration verified")

    # -----------------------------------------------------------------------
    # Test 3: Synthetic PE32+ Fat Header (MoreSects EH + Locals) & void RetType Auto-Detection
    # -----------------------------------------------------------------------
    fat_body = b"\x00" * 79 + b"\x2a"  # 80-byte body
    synth_pe64 = _build_synthetic_dotnet_dll(
        is_64=True,
        method_specs=[
            {
                "ns": "nel", "type": "MosaicShower", "name": "fnDrawMosaic",  # case-insensitive fallback test
                "ret_elem": 0x02, "header_kind": "Fat", "il_body": fat_body,
                "fat_flags": 0x001B,  # FatFormat(0x03) | MoreSects(0x08) | InitLocals(0x10)
                "fat_max_stack": 0,   # verifies MaxStack auto-raised to >= 1
                "fat_local_tok": 0x11000007,
            },
            {
                "ns": "nel", "type": "MosaicHelper", "name": "FnDrawMosaicVoid",
                "ret_elem": 0x01, "header_kind": "Fat", "il_body": fat_body,
                "fat_flags": 0x001B, "fat_max_stack": 2, "fat_local_tok": 0x11000008,
            },
        ],
    )
    with tempfile.TemporaryDirectory() as td:
        dll64 = os.path.join(td, "Assembly-CSharp.dll")
        with open(dll64, "wb") as f:
            f.write(synth_pe64)
        orig64_sz = os.path.getsize(dll64)

        # Patch case-insensitive fnDrawMosaic (bool -> 16 2A)
        patch_assembly(dll64, method_name="FnDrawMosaic", auto_sync_sha=False)
        # Patch void method (void -> 2A)
        patch_assembly(dll64, method_name="FnDrawMosaicVoid", auto_sync_sha=False)
        assert os.path.getsize(dll64) == orig64_sz, "Test 3 Failed: PE32+ file size changed!"

        data64 = open(dll64, "rb").read()
        p_bool = locate_target_methods(data64, method_name="FnDrawMosaic")[0]
        p_void = locate_target_methods(data64, method_name="FnDrawMosaicVoid")[0]
        assert p_bool["is_already_patched"] and p_void["is_already_patched"], "Test 3 Failed: Re-parse mismatch!"

        fs_b, ms_b, csz_b, lvt_b = struct.unpack_from("<HHII", data64, p_bool["file_offset"])
        assert (fs_b & 0x0018) == 0, "Test 3 Failed: MoreSects/InitLocals not cleared on Fat bool method!"
        assert ms_b >= 1 and csz_b == 2 and lvt_b == 0, "Test 3 Failed: Fat bool header fields invalid!"
        assert data64[p_bool["file_offset"] + 12:p_bool["file_offset"] + 14] == b"\x16\x2a"

        fs_v, ms_v, csz_v, lvt_v = struct.unpack_from("<HHII", data64, p_void["file_offset"])
        assert (fs_v & 0x0018) == 0 and csz_v == 1 and lvt_v == 0, "Test 3 Failed: Fat void header invalid!"
        assert data64[p_void["file_offset"] + 12:p_void["file_offset"] + 13] == b"\x2a"
        print("[PASS] Test 3: PE32+ Fat Header (MoreSects EH detach, MaxStack, bool & void RetType auto-detection)")

    # -----------------------------------------------------------------------
    # Test 4: Negative / Malformed PE & Circuit Breaker Fuzzing
    # -----------------------------------------------------------------------
    malformed_samples = [
        ("Empty file", b""),
        ("Truncated MZ", b"MZ" + b"\x00" * 16),
        ("Bad PE signature", b"MZ" + b"\x00" * 0x3A + struct.pack("<I", 0x40) + b"NOPE" + b"\x00" * 64),
        ("Zeroed 4KiB buffer", b"\x00" * 4096),
        ("Missing FnDrawMosaic", _build_synthetic_dotnet_dll(
            is_64=False,
            method_specs=[{"ns": "nel", "type": "Other", "name": "Unrelated", "header_kind": "Tiny", "il_body": b"\x16\x2a"}],
        )),
    ]
    for label, blob in malformed_samples:
        try:
            locate_target_methods(blob, method_name="FnDrawMosaic")
            assert False, f"Test 4 Failed: '{label}' did not raise IncompatibleVersionError"
        except IncompatibleVersionError:
            pass
    print("[PASS] Test 4: Defensive boundary checks intercepted all 5 malformed/missing-method inputs safely")

    # -----------------------------------------------------------------------
    # Test 5: Real C# Compilation + Mono JIT Verification (mono --verify-all) if available
    # -----------------------------------------------------------------------
    if shutil.which("mcs") and shutil.which("mono"):
        with tempfile.TemporaryDirectory() as td:
            cs_lib = os.path.join(td, "MockGame.cs")
            cs_app = os.path.join(td, "Verifier.cs")
            dll_p = os.path.join(td, "Assembly-CSharp.dll")
            exe_p = os.path.join(td, "Verifier.exe")

            with open(cs_lib, "w", encoding="utf-8") as f:
                f.write(
                    "using System;\n"
                    "namespace nel {\n"
                    "  public delegate bool FnDrawMosaic(int x);\n"
                    "  public class MosaicShower {\n"
                    "    public int NeighbourBefore(int x) { return x + 100; }\n"
                    "    public bool FnDrawMosaic(int level) {\n"
                    "      int sum = 0;\n"
                    "      try {\n"
                    "        for (int i = 0; i < level + 5; i++) sum += i;\n"
                    "        return sum >= 0;\n"
                    "      } finally { sum = -1; }\n"
                    "    }\n"
                    "    public int NeighbourAfter(int x) { return x * 3; }\n"
                    "  }\n"
                    "}\n"
                )
            with open(cs_app, "w", encoding="utf-8") as f:
                f.write(
                    "using System;\n"
                    "using nel;\n"
                    "class Verifier {\n"
                    "  static int Main() {\n"
                    "    var s = new MosaicShower();\n"
                    "    if (s.NeighbourBefore(7) != 107) return 10;\n"
                    "    if (s.NeighbourAfter(9) != 27) return 11;\n"
                    "    return s.FnDrawMosaic(4) ? 2 : 0;\n"
                    "  }\n"
                    "}\n"
                )
            subprocess.run(["mcs", "-target:library", "-optimize+", f"-out:{dll_p}", cs_lib], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["mcs", f"-r:{dll_p}", f"-out:{exe_p}", cs_app], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            pre = subprocess.run(["mono", exe_p], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            assert pre.returncode == 2, f"Test 5 Failed: Expected pre-patch FnDrawMosaic()==True (rc=2), got {pre.returncode}"

            patch_assembly(dll_p, auto_sync_sha=False)
            if shutil.which("monodis"):
                md = subprocess.run(["monodis", dll_p], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                assert md.returncode == 0, "Test 5 Failed: monodis reported invalid IL after patching!"

            post = subprocess.run(["mono", "--verify-all", exe_p], capture_output=True, text=True)
            assert post.returncode == 0, f"Test 5 Failed: mono --verify-all failed (rc={post.returncode}): {post.stderr}"
            print("[PASS] Test 5: Real C# (mcs) + mono --verify-all JIT execution confirmed FnDrawMosaic() == False (0 errors)")
    else:
        print("[NOTICE] mcs/mono not found in PATH; skipped live C# JIT compilation test (Tests 1-4 already passed).")

    # -----------------------------------------------------------------------
    # Test 6: Optional Real Game Assembly Integration Test (Temp Copy Only)
    # -----------------------------------------------------------------------
    real_dll = resolve_target_dll()
    if real_dll:
        sample = (real_dll + ".orig") if (os.path.isfile(real_dll + ".orig") and os.access(real_dll + ".orig", os.R_OK)) else real_dll
        print(f"Running non-destructive integration check on real assembly copy: {sample}")
        with tempfile.TemporaryDirectory() as td:
            tmp_dll = os.path.join(td, "Assembly-CSharp.dll")
            shutil.copy2(sample, tmp_dll)
            orig_sz = os.path.getsize(tmp_dll)
            patch_assembly(tmp_dll, auto_sync_sha=False)
            assert os.path.getsize(tmp_dll) == orig_sz, "Test 6 Failed: Real assembly size changed!"
            patch_assembly(tmp_dll, auto_sync_sha=False)  # idempotency
            print("[PASS] Test 6: Real game Assembly-CSharp.dll integration round-trip passed cleanly")
    else:
        print("[NOTICE] Real readable Assembly-CSharp.dll not detected in environment; skipped Test 6.")
        print("         (Configure AIC_GAME_DIR or pass --target to verify against actual game binary.)")

    print("=== unmosaic.py Test Suite Completed Successfully ===")
    return True


# ---------------------------------------------------------------------------
# CLI Entry Point
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="ECMA-335 IL-Level Unmosaic Patcher for Alice in Cradle (Assembly-CSharp.dll)"
    )
    parser.add_argument(
        "target",
        nargs="?",
        help="Path to Assembly-CSharp.dll or AliceInCradle game directory.",
    )
    parser.add_argument(
        "--target",
        dest="target_opt",
        help="Explicit path to Assembly-CSharp.dll or game/debug directory.",
    )
    parser.add_argument(
        "--method",
        default="FnDrawMosaic",
        help="Target CIL method name to neutralize (default: FnDrawMosaic).",
    )
    parser.add_argument(
        "--type",
        dest="type_filter",
        default=None,
        help="Optional declaring type substring filter (e.g. MosaicShower).",
    )
    parser.add_argument(
        "--mode",
        choices=("auto", "false", "void"),
        default="auto",
        help="IL rewrite mode: 'auto' detects return type from #Blob signature (default), 'false' forces ldc.i4.0;ret, 'void' forces ret.",
    )
    parser.add_argument(
        "--no-sha",
        action="store_true",
        help="Disable automatic synchronization of StreamingAssets/_sha.txt.",
    )
    parser.add_argument(
        "--force-skip-sha",
        action="store_true",
        help="Skip CIL patching and force recalculate/sync Assembly-CSharp.dll SHA-256 to _sha.txt.",
    )
    parser.add_argument(
        "--restore",
        action="store_true",
        help="Atomically restore Assembly-CSharp.dll from .orig backup and resync _sha.txt.",
    )
    parser.add_argument(
        "--test", "-t",
        action="store_true",
        help="Run built-in standalone verification suite (no real DLL required).",
    )

    args = parser.parse_args()

    if args.test:
        ok = run_self_tests()
        sys.exit(0 if ok else 1)

    target_input = args.target_opt if args.target_opt else args.target
    target_path = resolve_target_dll(target_input)
    if not target_path:
        print("[Error] Target Assembly-CSharp.dll could not be located (or lacks read permission).")
        print("Please configure the AIC_GAME_DIR environment variable:")
        print("  export AIC_GAME_DIR=/path/to/AliceInCradle_ver030")
        print("Or specify the DLL path explicitly via --target:")
        print(f"  python3 {sys.argv[0]} --target /path/to/Assembly-CSharp.dll")
        sys.exit(1)

    try:
        if args.restore:
            ok = restore_backup_atomic(target_path, auto_sync_sha=(not args.no_sha))
            sys.exit(0 if ok else 1)

        ok = patch_assembly(
            target_path,
            method_name=args.method,
            type_filter=args.type_filter,
            force_mode=args.mode,
            auto_sync_sha=(not args.no_sha),
            force_skip_sha=args.force_skip_sha,
        )
        sys.exit(0 if ok else 1)
    except (IncompatibleVersionError, SecurityVerificationError, FileNotFoundError, PermissionError, RuntimeError) as e:
        print(f"[Error] {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
