import unittest

from launcher.envbuilder import build_env_and_args


class TestEnvBuilder(unittest.TestCase):
    def test_low_preset_env_and_args(self):
        cfg = {"graphics": {"preset": "low", "hud_debug": "0", "strongmem": "2"}}
        env, args = build_env_and_args(cfg)
        self.assertEqual(env["BOX64_DYNAREC_STRONGMEM"], "2")
        self.assertEqual(env["DXVK_HUD"], "0")
        self.assertEqual(env["WINEDEBUG"], "-all")
        self.assertEqual(args, ["-screen-quality", "Fastest"])


if __name__ == "__main__":
    unittest.main()
