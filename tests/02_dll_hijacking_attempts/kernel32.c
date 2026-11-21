#include <windows.h>

void WINAPI MyExitProcess(UINT uExitCode) {
    // 拦截退出，什么都不做
}

BOOL WINAPI MyTerminateProcess(HANDLE hProcess, UINT uExitCode) {
    return TRUE; // 假装成功
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    return TRUE;
}
