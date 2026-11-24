import json
import re
import unittest


class TestSignatures(unittest.TestCase):
    def test_all_8_signatures_match_synthetic_samples(self):
        with open("launcher/signatures.json", "r", encoding="utf-8") as f:
            sigs = json.load(f)
        self.assertEqual(len(sigs), 8)
        samples = [
            "图形指令未处理",
            "引擎动态库未找到",
            "访问违例",
            "执行引擎异常",
            "哈希不匹配",
            "内存分配失败",
            "引导配置未生效",
            "引擎日志未生成",
        ]
        for sig, sample in zip(sigs, samples):
            self.assertIsNotNone(re.search(sig["pattern"], sample), f"{sig['id']} failed on {sample}")


if __name__ == "__main__":
    unittest.main()
