import json
import os
import tempfile
import unittest

from launcher.reporter import write_diagnostic_report


class TestReporter(unittest.TestCase):
    def test_archives_log_and_records_rollback_info(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_file = os.path.join(tmp, "Player.log")
            with open(log_file, "w", encoding="utf-8") as f:
                f.write("SIGSEGV\n")
            rep_file = os.path.join(tmp, "diag.json")
            arch_dir = os.path.join(tmp, "archives")

            payload = write_diagnostic_report(
                rep_file,
                log_file,
                arch_dir,
                [{"id": "SIG-03", "action": "increase strongmem"}],
                {"previous_strongmem": "1", "restore_cmd": "set strongmem=1"},
            )
            self.assertTrue(os.path.isfile(payload["archived_log"]))
            with open(rep_file, encoding="utf-8") as f:
                loaded = json.load(f)
            self.assertEqual(loaded["rollback_info"]["previous_strongmem"], "1")


if __name__ == "__main__":
    unittest.main()
