#include <windows.h>
#include <stdio.h>

typedef void* (*UnityMain2_t)(HINSTANCE, HINSTANCE, char*, int);

int main(int argc, char** argv) {
    SetCurrentDirectoryW(L"./");

    HMODULE hUnity = LoadLibraryW(L"UnityPlayer.dll");
    if (!hUnity) {
        fprintf(stderr, "LoadLibrary UnityPlayer.dll failed: %lu\n", GetLastError());
        return 1;
    }

    UnityMain2_t UnityMain2 =
        (UnityMain2_t)GetProcAddress(hUnity, "UnityMain2");
    if (!UnityMain2) {
        fprintf(stderr, "GetProcAddress UnityMain2 failed: %lu\n", GetLastError());
        return 1;
    }

    /* 把 argv[1..] 拼成一条 lpCmdLine，argv[0] 是 stub.exe 自己 */
    char cmdline[4096] = "";
    for (int i = 1; i < argc && strlen(cmdline) + strlen(argv[i]) + 1 < sizeof(cmdline); i++) {
        if (i > 1) strcat(cmdline, " ");
        strcat(cmdline, argv[i]);
    }

    return (int)(size_t)UnityMain2(
        GetModuleHandle(NULL), NULL, cmdline, SW_SHOW
    );
}
