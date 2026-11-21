#include <windows.h>

typedef int (*UnityMain2_t)(HINSTANCE, HINSTANCE, LPSTR, int);

void WinMainCRTStartup(void) {
    SetCurrentDirectoryW(L".");

    /* Performance & translation environment optimization */
    SetEnvironmentVariableA("BOX64_DYNAREC_BIGBLOCK", "2");
    SetEnvironmentVariableA("BOX64_DYNAREC_SAFEFLAGS", "1");
    SetEnvironmentVariableA("DXVK_ASYNC", "1");
    SetEnvironmentVariableA("MESA_SHADER_CACHE", "1");

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
