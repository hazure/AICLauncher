import json
import os
import tempfile
import unittest

from launcher.preflight import run_preflight, sha256_file


class TestPreflight(unittest.TestCase):
    def test_valid_and_tampered_file_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = os.path.join(tmp, "sample.txt")
            with open(target, "w", encoding="utf-8") as f:
                f.write("clean")
            base = os.path.join(tmp, "baseline.json")
            with open(base, "w", encoding="utf-8") as f:
                json.dump({"files": {"sample.txt": {"sha256": sha256_file(target)}}}, f)

            ok, errs = run_preflight(tmp, base)
            self.assertTrue(ok)
            self.assertEqual(errs, [])

            with open(target, "w", encoding="utf-8") as f:
                f.write("tampered")
            ok2, errs2 = run_preflight(tmp, base)
            self.assertFalse(ok2)
            self.assertTrue(any("hash mismatch" in e for e in errs2))


if __name__ == "__main__":
    unittest.main()
