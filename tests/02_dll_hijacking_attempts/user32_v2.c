#include <windows.h>
#include <stdio.h>

// 拦截设置窗口文本，不让它显示乱码或警告
BOOL WINAPI MySetWindowTextW(HWND hWnd, LPCWSTR lpString) {
    // 如果要查看是不是警告文字，可以取消下面这行的注释写进日志
    // FILE *f = fopen("window_text_log.txt", "a");
    // if (f) { fwprintf(f, L"%ls\n", lpString); fclose(f); }
    return TRUE; // 假装设置成功，但实际不设置
}

BOOL WINAPI MySetWindowTextA(HWND hWnd, LPCSTR lpString) {
    return TRUE; 
}

// 拦截退出消息
void WINAPI MyPostQuitMessage(int nExitCode) {
    // 什么都不做，阻止退出循环
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    return TRUE;
}
