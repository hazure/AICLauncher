import os
import sys
import tempfile
import unittest

from launcher.spawner import resolve_log_path, spawn_game


class TestSpawner(unittest.TestCase):
    def test_cwd_and_log_path_resolution(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg = {
                "paths": {"game_dir": tmp, "stub": sys.executable},
                "watchdog": {"log": "Player.log"},
            }
            self.assertEqual(resolve_log_path(cfg), os.path.join(tmp, "Player.log"))
            proc = spawn_game(cfg, {"AIC_TEST_ENV": "1"}, ["-c", "import os; assert os.getcwd()"])
            rc = proc.wait(timeout=5)
            self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
