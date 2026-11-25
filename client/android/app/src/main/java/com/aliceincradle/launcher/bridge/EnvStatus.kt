package com.aliceincradle.launcher.bridge

enum class EnvStatus {
    READY,            // 环境就绪，可正常启动
    MISSING_PYTHON,   // 缺少 Python 解释器
    MISSING_GAME,     // 缺少游戏资源目录
    UNKNOWN_ERROR     // 未知错误（权限不足、IO 异常等）
}
