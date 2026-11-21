#include <windows.h>
#include <stdio.h>

// 记录并拦截 MessageBoxA
int WINAPI MyMessageBoxA(HWND hWnd, LPCSTR lpText, LPCSTR lpCaption, UINT uType) {
    FILE *f = fopen("msg_blocked.txt", "a");
    if (f) {
        fprintf(f, "[MessageBoxA] Text: %s | Caption: %s\n", lpText ? lpText : "NULL", lpCaption ? lpCaption : "NULL");
        fclose(f);
    }
    return 0; 
}

// 记录并拦截 MessageBoxW
int WINAPI MyMessageBoxW(HWND hWnd, LPCWSTR lpText, LPCWSTR lpCaption, UINT uType) {
    FILE *f = fopen("msg_blocked.txt", "a");
    if (f) {
        // 因为是宽字符，简单记录一下有弹窗就行
        fprintf(f, "[MessageBoxW] Triggered (WideString).\n");
        fclose(f);
    }
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    return TRUE;
}
