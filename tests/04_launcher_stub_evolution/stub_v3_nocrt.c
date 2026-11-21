#include <windows.h>

typedef int (*UnityMain2_t)(HINSTANCE, HINSTANCE, LPSTR, int);

void WinMainCRTStartup(void) {
    SetCurrentDirectoryW(L".");
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
