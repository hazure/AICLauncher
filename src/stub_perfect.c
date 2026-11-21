#include <windows.h>

typedef int (*UnityMain2_t)(HINSTANCE, HINSTANCE, LPSTR, int);

void WinMainCRTStartup(void) {
    SetCurrentDirectoryW(L".");

    /* Box64 Dynarec execution accuracy & thread lock safety */
    SetEnvironmentVariableA("BOX64_DYNAREC_BIGBLOCK", "2");
    SetEnvironmentVariableA("BOX64_DYNAREC_SAFEFLAGS", "1");
    SetEnvironmentVariableA("BOX64_DYNAREC_ALIGNED_ATOMICS", "1");
    SetEnvironmentVariableA("BOX64_DYNAREC_STRONGMEM", "1");

    /* DXVK & Mesa pipeline accuracy and caching */
    SetEnvironmentVariableA("DXVK_ASYNC", "1");
    SetEnvironmentVariableA("MESA_SHADER_CACHE", "1");

    /* Absolute persistence: dynamically resolve and bind shader cache directory */
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
        SetEnvironmentVariableA("MESA_SHADER_CACHE_DIR", cache_dir);
        SetEnvironmentVariableA("DXVK_STATE_CACHE_PATH", cache_dir);
    }

    HMODULE hUnity = LoadLibraryW(L"UnityPlayer.dll");
    if (!hUnity) {
        ExitProcess(1);
    }
    UnityMain2_t UnityMain2 = (UnityMain2_t)GetProcAddress(hUnity, "UnityMain2");
    if (!UnityMain2) {
        ExitProcess(2);
    }
    int ret = UnityMain2(GetModuleHandleW(NULL), NULL, GetCommandLineA(), SW_SHOW);
    ExitProcess(ret);
}
