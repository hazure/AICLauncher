# 快速上手（无头启动器）

1. 准备配置文件 `launcher.ini`（参考 `launcher/launcher.ini.example`）。
2. 执行一条命令完成预检、模组同步、环境构建与进程拉起：
   ```bash
   python3 -m launcher.cli --config launcher.ini --baseline docs/baseline/baseline.json
   ```
3. 若预检失败（退出码 `2`），请按错误提示核对基线哈希清单。
