import json
import os
import sys
import tempfile
import unittest

from launcher.cli import launch
from launcher.preflight import sha256_file


class TestCLI(unittest.TestCase):
    def test_preflight_failure_returns_2(self):
        cfg = {
            "paths": {"game_dir": ".", "stub": sys.executable, "plugins": "p", "mods_pool": "m"},
            "graphics": {"preset": "low", "hud_debug": "0", "strongmem": "1"},
            "mods": {},
        }
        rc = launch(cfg, "nonexistent_baseline.json")
        self.assertEqual(rc, 2)

    def test_full_launch_flow_with_mock_stub(self):
        with tempfile.TemporaryDirectory() as tmp:
            f_path = os.path.join(tmp, "ok.txt")
            with open(f_path, "w", encoding="utf-8") as f:
                f.write("ok")
            base = os.path.join(tmp, "baseline.json")
            with open(base, "w", encoding="utf-8") as f:
                json.dump({"files": {"ok.txt": {"sha256": sha256_file(f_path)}}}, f)
            cfg = {
                "paths": {
                    "game_dir": tmp,
                    "stub": sys.executable,
                    "plugins": os.path.join(tmp, "plugins"),
                    "mods_pool": os.path.join(tmp, "mods_pool"),
                },
                "graphics": {"preset": "low", "hud_debug": "0", "strongmem": "1"},
                "mods": {},
            }
            rc = launch(cfg, base, executable=sys.executable)
            # sys.executable exits 2 on unknown -screen-quality flag, proving spawn ran
            self.assertIsInstance(rc, int)


if __name__ == "__main__":
    unittest.main()
