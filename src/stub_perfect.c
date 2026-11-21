#include <windows.h>

typedef int (*UnityMain2_t)(HINSTANCE, HINSTANCE, LPSTR, int);

/* 只补位不覆盖（Fallback-only）：优先尊重玩家在 Winlator 前端设置的环境变量 */
static void set_env_if_missing(const char *name, const char *val) {
    if (GetEnvironmentVariableA(name, NULL, 0) == 0) {
        SetEnvironmentVariableA(name, val);
    }
}

void WinMainCRTStartup(void) {
    SetCurrentDirectoryW(L".");

    /* Box64: 保稳定、控开销、提准度环境参数（只补位，不覆盖前端配置） */
    /* 保守基本块生成，避免激进重编译导致缓存失效与 CPU 尖峰开销 */
    set_env_if_missing("BOX64_DYNAREC_BIGBLOCK", "1");
    /* 优化状态标志寄存器模拟，兼顾指令安全性与分支效率 */
    set_env_if_missing("BOX64_DYNAREC_SAFEFLAGS", "1");
    /* 强制多线程原子操作内存对齐，避免 Unity 多线程锁竞争异常 */
    set_env_if_missing("BOX64_DYNAREC_ALIGNED_ATOMICS", "1");
    /* 模拟强内存模型（TSO），在 ARM 弱内存序硬件下保障内存屏障与防死锁 */
    set_env_if_missing("BOX64_DYNAREC_STRONGMEM", "1");

    /* DXVK & Mesa: 消除图形管线编译与着色器重构开销（只补位，不覆盖前端配置） */
    set_env_if_missing("DXVK_ASYNC", "1");
    set_env_if_missing("MESA_SHADER_CACHE", "1");

    /* 绝对持久化：动态解析当前路径并拼接专属目录，绝不硬编码任何盘符或挂载点 */
    char cache_dir[MAX_PATH];
    DWORD len = GetCurrentDirectoryA(MAX_PATH, cache_dir);
    if (len > 0 && len + 14 < MAX_PATH) {
        if (cache_dir[len - 1] != '\\') {
            cache_dir[len++] = '\\';
        }
        const char sub[] = "shader_cache";
        for (DWORD i = 0; i < sizeof(sub); i++) {
            cache_dir[len + i] = sub[i];
        }
        CreateDirectoryA(cache_dir, NULL);
        set_env_if_missing("MESA_SHADER_CACHE_DIR", cache_dir);
        set_env_if_missing("DXVK_STATE_CACHE_PATH", cache_dir);
    }

    HMODULE hUnity = LoadLibraryW(L"UnityPlayer.dll");
    if (!hUnity) {
        ExitProcess(1);
    }
    UnityMain2_t UnityMain2 = (UnityMain2_t)GetProcAddress(hUnity, "UnityMain2");
    if (!UnityMain2) {
        ExitProcess(2);
    }

    /* 动态分辨率软编码与无边框窗口化接管：彻底绕开游戏内切换模式时的 DXVK 交换链重建崩溃风险 */
    int screen_w = GetSystemMetrics(SM_CXSCREEN);
    int screen_h = GetSystemMetrics(SM_CYSCREEN);

    char cmdline[256];
    wsprintfA(cmdline, "-screen-width %d -screen-height %d -popupwindow -screen-fullscreen 0", screen_w, screen_h);

    int ret = UnityMain2(GetModuleHandleW(NULL), NULL, cmdline, SW_SHOW);
    ExitProcess(ret);
}
