#include <windows.h>

// 拦截 MessageBoxA，直接返回 0（什么都不做）
int WINAPI MyMessageBoxA(HWND hWnd, LPCSTR lpText, LPCSTR lpCaption, UINT uType) {
    // 可以选择在控制台打印一下被拦截的内容（如果有的话）
    // printf("Blocked MessageBox: %s\n", lpText);
    return 0; 
}

// 转发所有其他函数给系统真正的 user32.dll
#pragma comment(linker, "/export:MessageBoxW=user32.MessageBoxW")
// 由于 Mingw 的局限，纯 C 代码大量导出转发比较麻烦，我们用最经典的 .def 文件来实现
