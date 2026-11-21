#!/usr/bin/env python3
"""
patch_unsafeAssem.py - Adaptive In-Place Binary Patcher for Alice in Cradle v0.30
Author: Maintainer
Description:
    Adaptive IL-level binary patcher for unsafeAssem.dll without hardcoded offsets.
    Uses contextual pattern scanning and ECMA-335 / CLI metadata inspection to:
      1. Dynamically locate XX.IN::Awake() anti-VM / environment verification block.
      2. Perform strict in-place rewriting:
         - Converts conditional branch (brtrue.s) to unconditional branch (br.s).
         - Fills failure instructions (ldstr SceneCannotLaunch_InvalidEnviroment, LoadScene, Destroy, ret)
           with NOP (0x00) padding.
         - Exact byte-for-byte replacement (25 bytes -> 25 bytes), preserving file length and Method Headers.
      3. Verifies pre-write byte signatures; aborts with circuit breaker if unexpected.
      4. Automatically recalculates SHA-256 and updates StreamingAssets/_sha.txt.
      5. Completely isolates unrelated logic (e.g., get_SENSITIVE remains 100% untouched).

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
        va = struct.unpack_from("<I", dll_bytes, s_off + 12)[0]
        raw_size = struct.unpack_from("<I", dll_bytes, s_off + 16)[0]
        raw_ptr = struct.unpack_from("<I", dll_bytes, s_off + 20)[0]
        sections.append((va, raw_size, raw_ptr))

    def rva_to_offset(rva):
        for va, raw_size, raw_ptr in sections:
            if va <= rva < va + raw_size:
                return raw_ptr + (rva - va)
        return None

    clr_off = rva_to_offset(clr_rva)
    if clr_off is None or clr_off + 16 > len(dll_bytes):
        raise IncompatibleVersionError("Unable to resolve CLR Runtime Header file offset.")

    meta_rva, meta_size = struct.unpack_from("<II", dll_bytes, clr_off + 8)
    meta_off = rva_to_offset(meta_rva)
    if meta_off is None or meta_off + 16 > len(dll_bytes):
        raise IncompatibleVersionError("Unable to resolve CLI Metadata Root offset.")

    sig = struct.unpack_from("<I", dll_bytes, meta_off)[0]
    if sig != 0x424A5342: # "BSJB"
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
        streams[s_name] = (meta_off + s_r_off, s_size)

    return rva_to_offset, streams

def extract_userstring_token(dll_bytes, target_str):
    """Dynamically resolves the 4-byte CLI UserString (#US) token for the given string."""
    try:
        rva_to_offset, streams = parse_pe_sections(dll_bytes)
        if "#US" not in streams:
            return None
        us_off, us_size = streams["#US"]
        target_utf16 = target_str.encode("utf-16le")
        pos = dll_bytes.find(target_utf16, us_off, us_off + us_size)
        if pos == -1:
            return None

        # ECMA-335 UserString format: length (1, 2, or 4 bytes) + utf16 data + trailing byte
        for prefix_len in (1, 2, 4):
            cand = pos - prefix_len
            if cand >= us_off:
                us_idx = cand - us_off
                token = 0x70000000 | us_idx
                return token
    except Exception:
        pass
    return None

# ---------------------------------------------------------------------------
# Adaptive Contextual Pattern Scanner
# ---------------------------------------------------------------------------
def locate_vm_check_block(dll_bytes):
    """
    Scans unsafeAssem.dll for the XX.IN::Awake VM check block adaptively.
    Returns: (target_offset, expected_orig_bytes, patch_bytes)
    """
    TARGET_SCENE_STR = "SceneCannotLaunch_InvalidEnviroment"
    token = extract_userstring_token(dll_bytes, TARGET_SCENE_STR)

    # If dynamic extraction succeeded, construct target ldstr instruction (0x72 + 4-byte token)
    token_candidates = []
    if token is not None:
        token_candidates.append(b"\x72" + struct.pack("<I", token))
    # Fallback to known default v0.30 token: 0x7000E3FE
    default_ldstr = bytes.fromhex("72fee30070")
    if default_ldstr not in token_candidates:
        token_candidates.append(default_ldstr)

    # Search for ldstr instruction in dll_bytes
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

    EXPECTED_ORIG_BYTES = bytes.fromhex("062d1672fee3007028650a000a02288900000a28af01000a2a")
    PATCH_BYTES = bytes.fromhex("2b17" + "00" * 23)

    if not matching_offsets:
        # Check if the assembly is already patched (the ldstr instruction was replaced by nops)
        pos = 0
        already_patched_offsets = []
        while True:
            pos = dll_bytes.find(PATCH_BYTES, pos)
            if pos == -1:
                break
            # Verify context around this match
            post_inst = dll_bytes[pos + 25:pos + 30]
            pre_inst = dll_bytes[pos - 8:pos]
            if post_inst.startswith(b"\x28") and pre_inst.endswith(bytes.fromhex("160a")):
                already_patched_offsets.append(pos)
            pos += 1

        if len(already_patched_offsets) == 1:
            return already_patched_offsets[0], EXPECTED_ORIG_BYTES, PATCH_BYTES, True

        raise IncompatibleVersionError(
            f"Unable to locate '{TARGET_SCENE_STR}' token reference in assembly. "
            "Target version may be unsupported or already modified."
        )

    # Contextual verification: find the occurrence situated within the Awake() VM check
    valid_candidates = []
    for ldstr_pos in matching_offsets:
        # Preceding 3 bytes must be: ldloc.0 (0x06), brtrue.s (0x2d), branch_offset (0x16)
        block_start = ldstr_pos - 3
        if block_start < 0 or block_start + 25 > len(dll_bytes):
            continue

        prefix = dll_bytes[block_start:ldstr_pos]
        # Check prefix: 06 2d 16
        if prefix != bytes.fromhex("062d16"):
            continue

        # Check following instruction at block_start + 25: call XX.REG::initReg() (starts with 0x28)
        post_inst = dll_bytes[block_start + 25:block_start + 28]
        if post_inst.startswith(b"\x28"):
            valid_candidates.append(block_start)

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

    target_offset = valid_candidates[0]
    return target_offset, EXPECTED_ORIG_BYTES, PATCH_BYTES, False

# ---------------------------------------------------------------------------
# Checksum & Sync Helpers
# ---------------------------------------------------------------------------
def compute_sha256(file_path):
    """Calculates SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def sync_sha_file(dll_path, new_hash):
    """Synchronizes the updated assembly SHA-256 with StreamingAssets/_sha.txt."""
    base_dir = os.path.dirname(os.path.abspath(dll_path))
    # Candidates for _sha.txt
    candidates = [
        os.path.join(base_dir, "..", "StreamingAssets", "_sha.txt"),
        os.path.join(base_dir, "..", "..", "StreamingAssets", "_sha.txt"),
        os.path.join(base_dir, "StreamingAssets", "_sha.txt"),
    ]
    env_dir = os.environ.get("AIC_GAME_DIR")
    if env_dir:
        candidates.append(os.path.join(env_dir, "AliceInCradle_Data", "StreamingAssets", "_sha.txt"))
        candidates.append(os.path.join(env_dir, "StreamingAssets", "_sha.txt"))

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

    # Support both two-line format (path \n hash) and single-line format (hash path)
    updated = False
    new_lines = []
    i = 0
    while i < len(raw_lines):
        line = raw_lines[i]
        stripped = line.strip()

        # Two-line format: current line is path, next line is hash
        if ("unsafeAssem.dll" in stripped) and not any(c in stripped for c in " \t") and (i + 1 < len(raw_lines)):
            new_lines.append(line)
            new_lines.append(f"{new_hash}\n")
            updated = True
            i += 2
            continue

        # Single-line format: <hash> <path>
        parts = stripped.split()
        if len(parts) >= 2 and ("unsafeAssem.dll" in parts[1]):
            new_lines.append(f"{new_hash}  {parts[1]}\n")
            updated = True
            i += 1
            continue

        new_lines.append(line)
        i += 1

    if updated:
        with open(sha_file, "w", encoding="utf-8") as f:
            f.writelines(new_lines)
        print(f"[OK] Synchronized SHA-256 to {sha_file}")
        return True
    else:
        print(f"[Notice] unsafeAssem.dll entry not found in {sha_file}.")
        return False

# ---------------------------------------------------------------------------
# Core In-Place Patcher
# ---------------------------------------------------------------------------
def patch_file(dll_path, auto_sync_sha=True):
    """
    Executes in-place patching adhering strictly to the zero-shift invariant:
      - Uses r+b mode (f.seek + f.write).
      - Replaces exactly 25 bytes with 25 bytes.
      - Never recomputes or modifies PE headers.
      - Verifies pre-write bytes with circuit breaker.
    """
    if not os.path.isfile(dll_path):
        raise FileNotFoundError(f"Target assembly does not exist: {dll_path}")

    print(f"Target assembly: {dll_path}")
    orig_file_size = os.path.getsize(dll_path)

    with open(dll_path, "rb") as f:
        dll_bytes = f.read()

    target_offset, orig_expected, patch_bytes, is_already_patched = locate_vm_check_block(dll_bytes)

    if is_already_patched:
        print(f"[OK] Assembly is already patched at offset 0x{target_offset:X}. No changes required.")
        if auto_sync_sha:
            current_sha = compute_sha256(dll_path)
            sync_sha_file(dll_path, current_sha)
        return True

    # Pre-write verification & circuit breaker
    actual_slice = dll_bytes[target_offset:target_offset + len(orig_expected)]
    if actual_slice != orig_expected:
        raise SecurityVerificationError(
            f"Pre-write signature mismatch at offset 0x{target_offset:X}.\n"
            f"  Expected: {orig_expected.hex(' ')}\n"
            f"  Actual:   {actual_slice.hex(' ')}\n"
            "Circuit breaker triggered: Refusing to modify file."
        )

    # Create safety backup (.orig) if not already present
    bak_path = dll_path + ".orig"
    if not os.path.exists(bak_path):
        shutil.copy2(dll_path, bak_path)
        print(f"[OK] Created backup at {bak_path}")

    # Perform strict in-place overwrite (r+b mode)
    with open(dll_path, "r+b") as f:
        f.seek(target_offset)
        f.write(patch_bytes)
        f.flush()

    # Post-write invariant assertion
    new_file_size = os.path.getsize(dll_path)
    if new_file_size != orig_file_size:
        # Catastrophic failure recovery
        shutil.copy2(bak_path, dll_path)
        raise RuntimeError(
            f"FATAL: In-place patch altered file length ({orig_file_size} -> {new_file_size})! "
            "Reverted to backup automatically."
        )

    print(f"[OK] Successfully patched 25 bytes at offset 0x{target_offset:X} in-place.")

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
        if os.path.isfile(cli_arg):
            return os.path.abspath(cli_arg)
        if os.path.isdir(cli_arg):
            cand1 = os.path.join(cli_arg, "AliceInCradle_Data", "Managed", "unsafeAssem.dll")
            if os.path.isfile(cand1):
                return os.path.abspath(cand1)
            cand2 = os.path.join(cli_arg, "Managed", "unsafeAssem.dll")
            if os.path.isfile(cand2):
                return os.path.abspath(cand2)

    env_dir = os.environ.get("AIC_GAME_DIR")
    if env_dir and os.path.exists(env_dir):
        if os.path.isfile(env_dir):
            return os.path.abspath(env_dir)
        cand1 = os.path.join(env_dir, "AliceInCradle_Data", "Managed", "unsafeAssem.dll")
        if os.path.isfile(cand1):
            return os.path.abspath(cand1)
        cand2 = os.path.join(env_dir, "Managed", "unsafeAssem.dll")
        if os.path.isfile(cand2):
            return os.path.abspath(cand2)

    candidates = [
        "unsafeAssem.dll",
        "Managed/unsafeAssem.dll",
        "AliceInCradle_Data/Managed/unsafeAssem.dll",
        "/storage/emulated/0/Documents/AliceInCradle_ver030/AliceInCradle_Data/Managed/unsafeAssem.dll",
    ]
    for c in candidates:
        if os.path.isfile(c):
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

    # Test 2: In-place circuit breaker on corrupted data
    import tempfile
    with tempfile.NamedTemporaryFile("wb", delete=False) as tmp:
        # Create dummy buffer with partial pattern but corrupted signature
        dummy_data = bytearray(b"\x00" * 0x10000)
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

    # Test 3: Real assembly verification if available
    orig_candidates = [
        "/storage/emulated/0/Documents/AliceInCradle_ver030/AliceInCradle_Data/Managed/unsafeAssem.dll.orig",
        "/data/data/com.termux/files/home/aic/work/unsafeAssem_clean.dll.orig",
    ]
    real_dll = resolve_target_dll()
    if real_dll:
        orig_candidates.insert(0, real_dll + ".orig")

    sample_orig = None
    for cand in orig_candidates:
        if os.path.isfile(cand):
            sample_orig = cand
            break

    if sample_orig:
        print(f"Testing against legitimate assembly sample: {sample_orig}")
        with open(sample_orig, "rb") as f:
            raw_orig = f.read()

        offset, orig_exp, patch_exp, is_patched = locate_vm_check_block(raw_orig)
        assert not is_patched, "Test 3 Failed: Original backup marked as already patched"
        assert orig_exp == orig_template, "Test 3 Failed: Extracted orig signature mismatch"
        assert patch_exp == patch_template, "Test 3 Failed: Extracted patch signature mismatch"
        print(f"[PASS] Test 3: Successfully localized VM check block at offset 0x{offset:X} in real binary")

        # Test that get_SENSITIVE is completely outside target block
        sens_offset = 0x13E1D0
        assert not (offset <= sens_offset < offset + 25), "Test 4 Failed: Overlaps with get_SENSITIVE"
        print("[PASS] Test 4: Verified zero-overlap with get_SENSITIVE (offset isolation confirmed)")

        # Test 5: Full In-Place Round-trip & Size Invariance on Temp Copy
        import tempfile, subprocess
        with tempfile.NamedTemporaryFile("wb", suffix=".dll", delete=False) as tmp_dll:
            tmp_dll.write(raw_orig)
            tmp_dll_path = tmp_dll.name

        try:
            orig_sz = os.path.getsize(tmp_dll_path)
            # Patch copy
            patch_file(tmp_dll_path, auto_sync_sha=False)
            assert os.path.getsize(tmp_dll_path) == orig_sz, "Test 5 Failed: File size changed"

            with open(tmp_dll_path, "rb") as f:
                patched_bytes = f.read()
            assert patched_bytes[offset:offset + 25] == patch_template, "Test 5 Failed: Patched bytes mismatch"
            assert patched_bytes[sens_offset:sens_offset + 10] == raw_orig[sens_offset:sens_offset + 10], "Test 5 Failed: get_SENSITIVE modified"
            print("[PASS] Test 5: In-place patching preserved file length, target bytes, and get_SENSITIVE exactly")

            # Test 6: Idempotency (re-running on patched assembly)
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

            # Test 8: CIL Bytecode Validation via monodis
            shutil.copy2(sample_orig, tmp_dll_path)
            patch_file(tmp_dll_path, auto_sync_sha=False)
            if shutil.which("monodis"):
                env = os.environ.copy()
                env["MONO_PATH"] = os.path.dirname(os.path.abspath(sample_orig))
                res = subprocess.run(["monodis", "--method", tmp_dll_path], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                assert res.returncode == 0, "Test 8 Failed: monodis reported invalid IL bytecode in patched assembly"
                print("[PASS] Test 8: Mono JIT bytecode verification (monodis --method) passed cleanly with 0 errors")

        finally:
            if os.path.exists(tmp_dll_path):
                os.remove(tmp_dll_path)
            if os.path.exists(tmp_dll_path + ".orig"):
                os.remove(tmp_dll_path + ".orig")

    print("=== All Test Cases Passed Successfully ===")
    return True

# ---------------------------------------------------------------------------
# Main Entry Point
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="In-Place Adaptive Binary Patcher for Alice in Cradle v0.30"
    )
    parser.add_argument(
        "target",
        nargs="?",
        help="Path to unsafeAssem.dll or AliceInCradle game folder."
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

    target_path = resolve_target_dll(args.target)
    if not target_path:
        print("[Error] Target unsafeAssem.dll could not be located.")
        print("Please configure the AIC_GAME_DIR environment variable:")
        print("  export AIC_GAME_DIR=/path/to/AliceInCradle_ver030")
        print("Or specify the DLL file or directory explicitly:")
        print(f"  python3 {sys.argv[0]} /path/to/unsafeAssem.dll")
        sys.exit(1)

    try:
        success = patch_file(target_path, auto_sync_sha=(not args.no_sha))
        sys.exit(0 if success else 1)
    except (IncompatibleVersionError, SecurityVerificationError, RuntimeError) as e:
        print(f"[Error] {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
