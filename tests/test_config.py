import os
import tempfile
import unittest

from launcher.config import DEFAULTS, load_config


class TestConfig(unittest.TestCase):
    def test_missing_file_uses_defaults_and_logs(self):
        with self.assertLogs("launcher.config", level="WARNING") as cm:
            cfg = load_config("nonexistent_launcher.ini")
        self.assertEqual(cfg, DEFAULTS)
        self.assertTrue(any("Missing" in m or "missing" in m for m in cm.output))

    def test_partial_ini_overrides_and_fallbacks(self):
        with tempfile.NamedTemporaryFile("w", suffix=".ini", delete=False) as f:
            f.write("[graphics]\npreset = high\n")
            tmp_path = f.name
        try:
            with self.assertLogs("launcher.config", level="WARNING"):
                cfg = load_config(tmp_path)
            self.assertEqual(cfg["graphics"]["preset"], "high")
            self.assertEqual(cfg["watchdog"]["tail_ms"], "800")
        finally:
            os.remove(tmp_path)


if __name__ == "__main__":
    unittest.main()
