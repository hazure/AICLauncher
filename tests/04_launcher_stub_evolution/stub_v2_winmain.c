#include <windows.h>

typedef int (*UnityMain2_t)(HINSTANCE, HINSTANCE, LPSTR, int);

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nShowCmd) {
    SetCurrentDirectoryW(L".");
    HMODULE hUnity = LoadLibraryW(L"UnityPlayer.dll");
    if (!hUnity) return 1;
    UnityMain2_t UnityMain2 = (UnityMain2_t)GetProcAddress(hUnity, "UnityMain2");
    if (!UnityMain2) return 1;
    return UnityMain2(hInstance, hPrevInstance, lpCmdLine, nShowCmd);
}
