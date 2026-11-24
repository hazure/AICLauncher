import os
import tempfile
import time
import unittest

from launcher.watchdog import LogWatchdog


class TestWatchdog(unittest.TestCase):
    def test_captures_appended_log_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_file = os.path.join(tmp, "Player.log")
            with open(log_file, "w", encoding="utf-8") as f:
                f.write("init\n")
            seen: list[str] = []
            wd = LogWatchdog(log_file, seen.append, tail_ms=20)
            wd.start()
            try:
                time.sleep(0.05)
                with open(log_file, "a", encoding="utf-8") as f:
                    f.write("line_two\n")
                time.sleep(0.08)
            finally:
                wd.stop()
            self.assertIn("init", seen)
            self.assertIn("line_two", seen)


if __name__ == "__main__":
    unittest.main()
