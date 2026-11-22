#!/usr/bin/env python3
"""
patch_unsafeAssem.py - Atomic In-Place Binary Patcher for Alice in Cradle v0.30
Description:
    IL-level binary patcher for unsafeAssem.dll (v0.30).
    Performs dynamic UserString token resolution and contextual verification to:
      1. Dynamically locate XX.IN::Awake() anti-VM verification block via CLI metadata.
      2. Construct dynamic expected byte signatures from the resolved token.
      3. Perform crash-safe atomic in-place rewriting:
         - Converts conditional branch (brtrue.s) to unconditional branch (br.s).
         - Fills failure instructions with NOP (0x00) padding.
         - Exact byte-for-byte replacement (25 bytes -> 25 bytes), preserving file length.
      4. Verifies pre-write byte signatures with circuit breaker.
      5. Synchronizes SHA-256 manifest to StreamingAssets/_sha.txt atomically.

Usage:
    python3 patch_unsafeAssem.py [path_to_dll_or_game_dir]
    python3 patch_unsafeAssem.py --test
"""

import os
import sys
import shutil
import struct
import hashlib
import argparse
import tempfile

# ---------------------------------------------------------------------------
# Custom Exceptions
# ---------------------------------------------------------------------------
class IncompatibleVersionError(Exception):
    """Raised when the target assembly structure or VM check pattern cannot be found."""
    pass

class SecurityVerificationError(Exception):
    """Raised when pre-write signature verification fails, triggering the circuit breaker."""
    pass

# ---------------------------------------------------------------------------
# PE / CLI Metadata Parser Helpers
# ---------------------------------------------------------------------------
def parse_pe_sections(dll_bytes):
    """Parses PE header and returns section table mappings and CLR metadata location."""
    if len(dll_bytes) < 0x40 or dll_bytes[:2] != b"MZ":
        raise IncompatibleVersionError("Invalid PE format: Missing MZ header signature.")

    pe_off = struct.unpack_from("<I", dll_bytes, 0x3C)[0]
    if pe_off + 0x18 > len(dll_bytes) or dll_bytes[pe_off:pe_off + 4] != b"PE\x00\x00":
        raise IncompatibleVersionError("Invalid PE format: Missing PE signature.")

    magic = struct.unpack_from("<H", dll_bytes, pe_off + 0x18)[0]
    is_64 = (magic == 0x20B)

    clr_dir_off = pe_off + (0xF8 if is_64 else 0xE8)
    if clr_dir_off + 8 > len(dll_bytes):
        raise IncompatibleVersionError("Invalid PE format: Truncated Data Directories.")

    clr_rva, clr_size = struct.unpack_from("<II", dll_bytes, clr_dir_off)
    if clr_rva == 0 or clr_size == 0:
        raise IncompatibleVersionError("Not a valid .NET assembly: Missing CLR Runtime Header.")

    num_sections = struct.unpack_from("<H", dll_bytes, pe_off + 0x06)[0]
    opt_hdr_size = struct.unpack_from("<H", dll_bytes, pe_off + 0x14)[0]
    sec_hdr_off = pe_off + 0x18 + opt_hdr_size

    sections = []
    for i in range(num_sections):
        s_off = sec_hdr_off + i * 40
        if s_off + 40 > len(dll_bytes):
            break
        vsize = struct.unpack_from("<I", dll_bytes, s_off + 8)[0]
        va = struct.unpack_from("<I", dll_bytes, s_off + 12)[0]
        raw_size = struct.unpack_from("<I", dll_bytes, s_off + 16)[0]
        raw_ptr = struct.unpack_from("<I", dll_bytes, s_off + 20)[0]
        sections.append((va, vsize, raw_size, raw_ptr))

    def rva_to_offset(rva):
        for va, vsize, raw_size, raw_ptr in sections:
            # Map RVA using virtual address and file raw pointer
            span = max(vsize, raw_size)
            if va <= rva < va + span:
                delta = rva - va
                if delta < raw_size:
                    return raw_ptr + delta
        return None

    clr_off = rva_to_offset(clr_rva)
    if clr_off is None or clr_off + 16 > len(dll_bytes):
        raise IncompatibleVersionError("Unable to resolve CLR Runtime Header file offset.")

    meta_rva, meta_size = struct.unpack_from("<II", dll_bytes, clr_off + 8)
    meta_off = rva_to_offset(meta_rva)
    if meta_off is None or meta_off + 16 > len(dll_bytes):
        raise IncompatibleVersionError("Unable to resolve CLI Metadata Root offset.")

    sig = struct.unpack_from("<I", dll_bytes, meta_off)[0]
    if sig != 0x424A5342:  # "BSJB"
        raise IncompatibleVersionError("Invalid CLI Metadata Root signature.")

    vlen = struct.unpack_from("<I", dll_bytes, meta_off + 12)[0]
    streams_off = meta_off + 16 + vlen
    if streams_off + 4 > len(dll_bytes):
        raise IncompatibleVersionError("Truncated CLI Metadata Streams Header.")

    flags, num_streams = struct.unpack_from("<HH", dll_bytes, streams_off)
    p = streams_off + 4
    streams = {}
    for _ in range(num_streams):
        if p + 8 > len(dll_bytes):
            break
        s_r_off, s_size = struct.unpack_from("<II", dll_bytes, p)
        p += 8
        name_buf = bytearray()
        while p < len(dll_bytes) and dll_bytes[p] != 0:
            name_buf.append(dll_bytes[p])
            p += 1
        p += 1
        while (p - meta_off) % 4 != 0 and p < len(dll_bytes):
            p += 1
        s_name = name_buf.decode("ascii", errors="ignore")
        if meta_off + s_r_off + s_size <= len(dll_bytes):
            streams[s_name] = (meta_off + s_r_off, s_size)

    return rva_to_offset, streams

def extract_userstring_token(dll_bytes, target_str):
    """
    Dynamically resolves the 4-byte CLI UserString (#US) token for the given string
    strictly conforming to ECMA-335 Partition II, Section 24.2.4.
    """
    try:
        rva_to_offset, streams = parse_pe_sections(dll_bytes)
        if "#US" not in streams:
            return None
        us_off, us_size = streams["#US"]
        us_end = us_off + us_size
        target_utf16 = target_str.encode("utf-16le")
        expected_len = len(target_utf16) + 1  # includes 1-byte trailing flag

        pos = us_off
        while True:
            pos = dll_bytes.find(target_utf16, pos, us_end)
            if pos == -1:
                break

            # Trailing byte must be 0x00 or 0x01
            trail_pos = pos + len(target_utf16)
            if trail_pos < us_end and dll_bytes[trail_pos] in (0x00, 0x01):
                # 1-byte compressed int: 0xxxxxxx (0 <= val <= 0x7F)
                if pos - 1 >= us_off:
                    b0 = dll_bytes[pos - 1]
                    if (b0 & 0x80) == 0 and b0 == expected_len:
                        return 0x70000000 | (pos - 1 - us_off)

                # 2-byte compressed int: 10xxxxxx xxxxxxxx (0x80 <= val <= 0x3FFF)
                if pos - 2 >= us_off:
                    b0, b1 = dll_bytes[pos - 2], dll_bytes[pos - 1]
                    if (b0 & 0xC0) == 0x80:
                        val = ((b0 & 0x3F) << 8) | b1
                        if val == expected_len:
                            return 0x70000000 | (pos - 2 - us_off)

                # 4-byte compressed int: 110xxxxx xxxxxxxx xxxxxxxx xxxxxxxx
                if pos - 4 >= us_off:
                    b0, b1, b2, b3 = dll_bytes[pos - 4:pos]
                    if (b0 & 0xE0) == 0xC0:
                        val = ((b0 & 0x1F) << 24) | (b1 << 16) | (b2 << 8) | b3
                        if val == expected_len:
                            return 0x70000000 | (pos - 4 - us_off)

            pos += len(target_utf16)
    except Exception:
        pass
    return None

# ---------------------------------------------------------------------------
# Contextual Pattern Scanner & Target Resolution
# ---------------------------------------------------------------------------
def locate_vm_check_block(dll_bytes):
    """
    Scans unsafeAssem.dll for the XX.IN::Awake VM check block.
    Dynamically generates expected_orig_bytes based on the resolved UserString token.
    Returns: (target_offset, expected_orig_bytes, patch_bytes, is_already_patched)
    """
    TARGET_SCENE_STR = "SceneCannotLaunch_InvalidEnviroment"
    token = extract_userstring_token(dll_bytes, TARGET_SCENE_STR)

    # Instruction parts of XX.IN::Awake() VM verification block
    PREFIX_BYTES = bytes.fromhex("062d16")  # ldloc.0, brtrue.s +22
    TAIL_BYTES = bytes.fromhex("28650a000a02288900000a28af01000a2a")  # LoadScene, get_gameObject, Destroy, ret
    PATCH_BYTES = bytes.fromhex("2b17" + "00" * 23)  # br.s +23, 23 NOPs

    # Build target candidates
    token_candidates = []
    if token is not None:
        token_candidates.append(b"\x72" + struct.pack("<I", token))
    # Fallback to known default v0.30 token: 0x7000E3FE
    default_ldstr = bytes.fromhex("72fee30070")
    if default_ldstr not in token_candidates:
        token_candidates.append(default_ldstr)

    matching_offsets = []
    for cand in token_candidates:
        idx = 0
        while True:
            pos = dll_bytes.find(cand, idx)
            if pos == -1:
                break
            matching_offsets.append(pos)
            idx = pos + 1

    matching_offsets = sorted(list(set(matching_offsets)))

    if not matching_offsets:
        # Check if already patched
        pos = 0
        already_patched_offsets = []
        while True:
            pos = dll_bytes.find(PATCH_BYTES, pos)
            if pos == -1:
                break
            post_inst = dll_bytes[pos + 25:pos + 28]
            if post_inst.startswith(b"\x28"):
                already_patched_offsets.append(pos)
            pos += 1

        if len(already_patched_offsets) == 1:
            resolved_token = token if token is not None else 0x7000E3FE
            expected_orig = PREFIX_BYTES + b"\x72" + struct.pack("<I", resolved_token) + TAIL_BYTES
            return already_patched_offsets[0], expected_orig, PATCH_BYTES, True

        raise IncompatibleVersionError(
            f"Unable to locate '{TARGET_SCENE_STR}' token reference in assembly. "
            "Target version may be unsupported or already modified."
        )

    # Contextual verification
    valid_candidates = []
    for ldstr_pos in matching_offsets:
        block_start = ldstr_pos - len(PREFIX_BYTES)
        if block_start < 0 or block_start + 25 > len(dll_bytes):
            continue

        if dll_bytes[block_start:ldstr_pos] != PREFIX_BYTES:
            continue

        actual_tail = dll_bytes[ldstr_pos + 5:block_start + 25]
        if actual_tail != TAIL_BYTES:
            continue

        post_inst = dll_bytes[block_start + 25:block_start + 28]
        if post_inst.startswith(b"\x28"):
            actual_token = struct.unpack_from("<I", dll_bytes, ldstr_pos + 1)[0]
            valid_candidates.append((block_start, actual_token))

    if len(valid_candidates) == 0:
        raise IncompatibleVersionError(
            "Found string token but instruction context around XX.IN::Awake() did not match. "
            "Aborting to avoid corrupting unrecognized code structures."
        )

    if len(valid_candidates) > 1:
        raise IncompatibleVersionError(
            f"Ambiguous context: Found {len(valid_candidates)} matching VM check candidates. "
            "Cannot safely determine patch offset."
        )

    target_offset, matched_token = valid_candidates[0]
    # DYNAMICALLY generate expected_orig_bytes from the matched token
    expected_orig_bytes = PREFIX_BYTES + b"\x72" + struct.pack("<I", matched_token) + TAIL_BYTES
    return target_offset, expected_orig_bytes, PATCH_BYTES, False

# ---------------------------------------------------------------------------
# Atomic In-Place Patching & Checksum Sync
# ---------------------------------------------------------------------------
def compute_sha256(file_path):
    """Calculates SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def patch_binary_atomic(target_path, patch_offset, expected_orig, patch_bytes):
    """
    Performs atomic, crash-safe in-place rewriting using a temporary file and os.replace.
    Guarantees zero-shift invariance and ensures target file is never left in half-written state.
    """
    if len(expected_orig) != len(patch_bytes):
        raise ValueError("Patch invariant violated: Length mismatch.")

    target_abs = os.path.abspath(target_path)
    if not os.path.isfile(target_abs):
        raise FileNotFoundError(f"Target assembly does not exist: {target_abs}")
    if not os.access(target_abs, os.W_OK):
        raise PermissionError(f"Target file is not writable: {target_abs}")

    file_size = os.path.getsize(target_abs)
    if patch_offset + len(patch_bytes) > file_size:
        raise ValueError(f"Offset 0x{patch_offset:X} exceeds file boundary ({file_size} bytes).")

    # Ensure safety backup (.orig) exists
    bak_path = target_abs + ".orig"
    if not os.path.exists(bak_path):
        shutil.copy2(target_abs, bak_path)
        print(f"[OK] Created backup at {bak_path}")

    dirname, basename = os.path.split(target_abs)
    # Write to a temporary file in the same directory (ensures same filesystem for atomic os.replace)
    with tempfile.NamedTemporaryFile("wb", dir=dirname, prefix=f".{basename}.", delete=False) as tmp_f:
        tmp_name = tmp_f.name
        try:
            with open(target_abs, "rb") as orig_f:
                # 1. Verify pre-write bytes at target offset
                orig_f.seek(patch_offset)
                current_bytes = orig_f.read(len(expected_orig))
                if current_bytes != expected_orig:
                    raise SecurityVerificationError(
                        f"Pre-write signature mismatch at offset 0x{patch_offset:X}.\n"
                        f"  Expected: {expected_orig.hex(' ')}\n"
                        f"  Actual:   {current_bytes.hex(' ')}\n"
                        "Circuit breaker triggered: Refusing to modify file."
                    )

                # 2. Stream copy with exact byte replacement
                orig_f.seek(0)
                tmp_f.write(orig_f.read(patch_offset))
                tmp_f.write(patch_bytes)
                orig_f.seek(patch_offset + len(patch_bytes))
                while chunk := orig_f.read(65536):
                    tmp_f.write(chunk)

            tmp_f.flush()
            os.fsync(tmp_f.fileno())
            tmp_f.close()

            # 3. Verify size invariance before replacement
            if os.path.getsize(tmp_name) != file_size:
                raise RuntimeError("Integrity check failed: File size altered during patching.")

            # 4. Atomic replacement
            os.replace(tmp_name, target_abs)
        except Exception:
            if os.path.exists(tmp_name):
                os.remove(tmp_name)
            raise

def sync_sha_file(dll_path, new_hash):
    """Synchronizes the updated assembly SHA-256 with StreamingAssets/_sha.txt atomically."""
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

    with open(sha_file, "r", encoding="utf-8", errors="ignore") as f:
        raw_lines = f.readlines()

    updated = False
    new_lines = []
    i = 0
    while i < len(raw_lines):
        line = raw_lines[i]
        stripped = line.strip()

        # Two-line format: path \n hash
        if ("unsafeAssem.dll" in stripped) and not any(c in stripped for c in " \t") and (i + 1 < len(raw_lines)):
            new_lines.append(line)
            new_lines.append(f"{new_hash}\n")
            updated = True
            i += 2
            continue

        # Single-line format: hash path
        parts = stripped.split()
        if len(parts) >= 2 and ("unsafeAssem.dll" in parts[1]):
            new_lines.append(f"{new_hash}  {parts[1]}\n")
            updated = True
            i += 1
            continue

        new_lines.append(line)
        i += 1

    if updated:
        dirname, basename = os.path.split(sha_file)
        with tempfile.NamedTemporaryFile("w", dir=dirname, prefix=f".{basename}.", delete=False, encoding="utf-8") as tmp_f:
            tmp_name = tmp_f.name
            tmp_f.writelines(new_lines)
            tmp_f.flush()
            os.fsync(tmp_f.fileno())
        os.replace(tmp_name, sha_file)
        print(f"[OK] Synchronized SHA-256 to {sha_file}")
        return True
    else:
        print(f"[Notice] unsafeAssem.dll entry not found in {sha_file}.")
        return False

def patch_file(dll_path, auto_sync_sha=True, force_skip_sha=False):
    """
    Executes in-place patching adhering strictly to the zero-shift invariant.
    If force_skip_sha is True, skips bytecode scanning/patching and directly
    recalculates and synchronizes SHA-256 for modded assemblies.
    """
    if not os.path.isfile(dll_path):
        raise FileNotFoundError(f"Target assembly does not exist: {dll_path}")

    print(f"Target assembly: {dll_path}")

    if force_skip_sha:
        print("[Notice] --force-skip-sha active: Skipping bytecode patching and verification.")
        new_sha = compute_sha256(dll_path)
        print(f"[OK] Assembly SHA-256: {new_sha}")
        synced = sync_sha_file(dll_path, new_sha)
        if not synced:
            print("[Warning] Could not locate StreamingAssets/_sha.txt to synchronize.")
            return False
        print(f"[OK] Force synchronized SHA-256 manifest for: {dll_path}")
        return True

    with open(dll_path, "rb") as f:
        dll_bytes = f.read()

    target_offset, orig_expected, patch_bytes, is_already_patched = locate_vm_check_block(dll_bytes)

    if is_already_patched:
        print(f"[OK] Assembly is already patched at offset 0x{target_offset:X}. No changes required.")
        if auto_sync_sha:
            current_sha = compute_sha256(dll_path)
            sync_sha_file(dll_path, current_sha)
        return True

    patch_binary_atomic(dll_path, target_offset, orig_expected, patch_bytes)
    print(f"[OK] Successfully patched 25 bytes at offset 0x{target_offset:X} in-place (atomic).")

    if auto_sync_sha:
        new_sha = compute_sha256(dll_path)
        print(f"[OK] New assembly SHA-256: {new_sha}")
        sync_sha_file(dll_path, new_sha)

    return True

# ---------------------------------------------------------------------------
# Target Resolution Helper
# ---------------------------------------------------------------------------
def resolve_target_dll(cli_arg=None):
    """Resolves target unsafeAssem.dll from CLI arg, env var, or search paths."""
    if cli_arg:
        if os.path.isfile(cli_arg) and os.access(cli_arg, os.R_OK):
            return os.path.abspath(cli_arg)
        if os.path.isdir(cli_arg):
            cand0 = os.path.join(cli_arg, "unsafeAssem.dll")
            if os.path.isfile(cand0) and os.access(cand0, os.R_OK):
                return os.path.abspath(cand0)
            cand1 = os.path.join(cli_arg, "AliceInCradle_Data", "Managed", "unsafeAssem.dll")
            if os.path.isfile(cand1) and os.access(cand1, os.R_OK):
                return os.path.abspath(cand1)
            cand2 = os.path.join(cli_arg, "Managed", "unsafeAssem.dll")
            if os.path.isfile(cand2) and os.access(cand2, os.R_OK):
                return os.path.abspath(cand2)

    env_dir = os.environ.get("AIC_GAME_DIR")
    if env_dir and os.path.exists(env_dir):
        if os.path.isfile(env_dir) and os.access(env_dir, os.R_OK):
            return os.path.abspath(env_dir)
        cand1 = os.path.join(env_dir, "AliceInCradle_Data", "Managed", "unsafeAssem.dll")
        if os.path.isfile(cand1) and os.access(cand1, os.R_OK):
            return os.path.abspath(cand1)
        cand2 = os.path.join(env_dir, "Managed", "unsafeAssem.dll")
        if os.path.isfile(cand2) and os.access(cand2, os.R_OK):
            return os.path.abspath(cand2)

    candidates = [
        "unsafeAssem.dll",
        "Managed/unsafeAssem.dll",
        "AliceInCradle_Data/Managed/unsafeAssem.dll",
        "unsafeAssem_clean.dll",
    ]
    for c in candidates:
        if os.path.isfile(c) and os.access(c, os.R_OK):
            return os.path.abspath(c)

    return None

# ---------------------------------------------------------------------------
# Self-Test Suite
# ---------------------------------------------------------------------------
def run_self_tests():
    """Runs automated verification unit tests."""
    print("=== Running patch_unsafeAssem Automated Test Suite ===")

    # Test 1: Byte-for-byte template lengths
    orig_template = bytes.fromhex("062d1672fee3007028650a000a02288900000a28af01000a2a")
    patch_template = bytes.fromhex("2b17" + "00" * 23)
    assert len(orig_template) == 25, "Test 1 Failed: orig_template length != 25"
    assert len(patch_template) == 25, "Test 1 Failed: patch_template length != 25"
    print("[PASS] Test 1: Template length equivalence (25 bytes == 25 bytes)")

    # Test 2: IncompatibleVersionError on corrupted/empty assembly
    with tempfile.NamedTemporaryFile("wb", delete=False) as tmp:
        dummy_data = bytearray(b"\x00" * 0x1000)
        tmp.write(dummy_data)
        tmp_name = tmp.name

    try:
        try:
            patch_file(tmp_name, auto_sync_sha=False)
            assert False, "Test 2 Failed: Should have raised IncompatibleVersionError"
        except IncompatibleVersionError:
            print("[PASS] Test 2: IncompatibleVersionError raised on non-matching assembly")
    finally:
        if os.path.exists(tmp_name):
            os.remove(tmp_name)

    # Test 3: Standalone synthetic atomic patching and size invariance
    with tempfile.NamedTemporaryFile("wb", delete=False) as tmp:
        test_buf = bytearray(b"\x90" * 200)
        offset = 50
        test_buf[offset:offset + 25] = orig_template
        tmp.write(test_buf)
        tmp_name = tmp.name

    try:
        orig_sz = os.path.getsize(tmp_name)
        patch_binary_atomic(tmp_name, offset, orig_template, patch_template)
        assert os.path.getsize(tmp_name) == orig_sz, "Test 3 Failed: Atomic patch altered file size"
        with open(tmp_name, "rb") as f:
            content = f.read()
        assert content[offset:offset + 25] == patch_template, "Test 3 Failed: Patched bytes mismatch"
        print("[PASS] Test 3: Atomic patcher verified (zero-shift & size invariance)")
    finally:
        if os.path.exists(tmp_name):
            os.remove(tmp_name)
        if os.path.exists(tmp_name + ".orig"):
            os.remove(tmp_name + ".orig")

    # Test 4: Real assembly integration test (if present in environment)
    real_dll = resolve_target_dll()
    sample_orig = None
    if real_dll:
        if os.path.isfile(real_dll + ".orig") and os.access(real_dll + ".orig", os.R_OK):
            sample_orig = real_dll + ".orig"
        elif os.path.isfile(real_dll) and os.access(real_dll, os.R_OK):
            sample_orig = real_dll

    if sample_orig:
        print(f"Running integration tests against detected assembly: {sample_orig}")
        with open(sample_orig, "rb") as f:
            raw_orig = f.read()

        offset, orig_exp, patch_exp, is_patched = locate_vm_check_block(raw_orig)
        assert len(orig_exp) == 25, "Test 4 Failed: Expected orig signature length != 25"
        assert len(patch_exp) == 25, "Test 4 Failed: Patch signature length != 25"
        print(f"[PASS] Test 4: Successfully localized VM check block at offset 0x{offset:X} in real binary")

        # Test 5: Full In-Place Round-trip & Size Invariance on Temp Copy
        import subprocess
        with tempfile.NamedTemporaryFile("wb", suffix=".dll", delete=False) as tmp_dll:
            tmp_dll.write(raw_orig)
            tmp_dll_path = tmp_dll.name

        try:
            orig_sz = os.path.getsize(tmp_dll_path)
            patch_file(tmp_dll_path, auto_sync_sha=False)
            assert os.path.getsize(tmp_dll_path) == orig_sz, "Test 5 Failed: File size changed"

            with open(tmp_dll_path, "rb") as f:
                patched_bytes = f.read()
            assert patched_bytes[offset:offset + 25] == patch_template, "Test 5 Failed: Patched bytes mismatch"
            print("[PASS] Test 5: Real assembly atomic patching preserved file length and target bytes")

            # Test 6: Idempotency (re-running on already patched assembly)
            patch_file(tmp_dll_path, auto_sync_sha=False)
            print("[PASS] Test 6: Idempotent execution on already-patched assembly")

            # Test 7: Tamper circuit breaker
            with open(tmp_dll_path, "r+b") as f:
                f.seek(offset)
                f.write(b"\xAA\xBB\xCC")
            try:
                patch_file(tmp_dll_path, auto_sync_sha=False)
                assert False, "Test 7 Failed: Circuit breaker did not trigger on tampered byte stream"
            except (SecurityVerificationError, IncompatibleVersionError):
                print("[PASS] Test 7: Circuit breaker successfully intercepted tampered byte stream")

            # Test 8: CIL Metadata Validation via monodis (if installed)
            shutil.copy2(sample_orig, tmp_dll_path)
            patch_file(tmp_dll_path, auto_sync_sha=False)
            if shutil.which("monodis"):
                env = os.environ.copy()
                env["MONO_PATH"] = os.path.dirname(os.path.abspath(sample_orig))
                res = subprocess.run(["monodis", "--typedef", tmp_dll_path], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                assert res.returncode == 0, "Test 8 Failed: monodis reported invalid metadata in patched assembly"
                print("[PASS] Test 8: Mono CLI verification (monodis --typedef) passed cleanly with 0 errors")

        finally:
            if os.path.exists(tmp_dll_path):
                os.remove(tmp_dll_path)
            if os.path.exists(tmp_dll_path + ".orig"):
                os.remove(tmp_dll_path + ".orig")
    else:
        print("[NOTICE] Real game assembly not detected in environment. Skipped integration tests 4-8.")
        print("         (Configure AIC_GAME_DIR to run full integration suite on real assets.)")

    print("=== Test Suite Execution Completed ===")
    return True

# ---------------------------------------------------------------------------
# Main Entry Point
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="Atomic In-Place Binary Patcher for Alice in Cradle v0.30"
    )
    parser.add_argument(
        "target",
        nargs="?",
        help="Path to unsafeAssem.dll or AliceInCradle game folder."
    )
    parser.add_argument(
        "--target",
        dest="target_opt",
        help="Explicit path to unsafeAssem.dll or AliceInCradle game folder (supports debug/mod directories)."
    )
    parser.add_argument(
        "--force-skip-sha",
        action="store_true",
        help="Skip bytecode patching and verification; force recalculate and synchronize SHA-256 to _sha.txt for third-party modded assemblies."
    )
    parser.add_argument(
        "--test", "-t",
        action="store_true",
        help="Run built-in test suite and verify invariants."
    )
    parser.add_argument(
        "--no-sha",
        action="store_true",
        help="Disable automatic synchronization of StreamingAssets/_sha.txt."
    )

    args = parser.parse_args()

    if args.test:
        success = run_self_tests()
        sys.exit(0 if success else 1)

    target_input = args.target_opt if args.target_opt else args.target
    target_path = resolve_target_dll(target_input)
    if not target_path:
        print("[Error] Target unsafeAssem.dll could not be located.")
        print("Please configure the AIC_GAME_DIR environment variable:")
        print("  export AIC_GAME_DIR=/path/to/AliceInCradle_ver030")
        print("Or specify the DLL file or directory explicitly via --target:")
        print(f"  python3 {sys.argv[0]} --target /path/to/unsafeAssem.dll")
        sys.exit(1)

    try:
        success = patch_file(
            target_path,
            auto_sync_sha=(not args.no_sha),
            force_skip_sha=args.force_skip_sha
        )
        sys.exit(0 if success else 1)
    except (IncompatibleVersionError, SecurityVerificationError, RuntimeError) as e:
        print(f"[Error] {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
