import json
import os
import tempfile
import unittest

from launcher.modmanager import sync_mods


class TestModManager(unittest.TestCase):
    def test_idempotent_enable_and_disable(self):
        with tempfile.TemporaryDirectory() as tmp:
            pool = os.path.join(tmp, "mods_pool")
            plugins = os.path.join(tmp, "plugins")
            mod_dir = os.path.join(pool, "unmosaic")
            os.makedirs(mod_dir)
            with open(os.path.join(mod_dir, "manifest.json"), "w", encoding="utf-8") as f:
                json.dump({"id": "unmosaic", "dll": "UnmosaicPlugin.dll"}, f)
            with open(os.path.join(mod_dir, "UnmosaicPlugin.dll"), "wb") as f:
                f.write(b"MZ_TEST")

            sync_mods(pool, plugins, {"unmosaic": "on"})
            sync_mods(pool, plugins, {"unmosaic": "on"})
            self.assertTrue(os.path.isfile(os.path.join(plugins, "UnmosaicPlugin.dll")))

            sync_mods(pool, plugins, {"unmosaic": "off"})
            sync_mods(pool, plugins, {"unmosaic": "off"})
            self.assertFalse(os.path.isfile(os.path.join(plugins, "UnmosaicPlugin.dll")))


if __name__ == "__main__":
    unittest.main()
