#define WIN32_LEAN_AND_MEAN
#include <windows.h>

typedef int (*UnityMain2_t)(HINSTANCE, HINSTANCE, LPSTR, int);

/*
 * Thin Launcher Stub:
 * 遵循单一职责原则（SRP）：
 * 1. 锚定工作目录至当前进程所在目录；
 * 2. 动态加载 UnityPlayer.dll 并获取 UnityMain2 导出函数；
 * 3. 完整透传系统原始命令行参数（跳过自身 exe 路径，保留用户所有参数）；
 * 4. 出现错误时弹出明确的错误对话框（彻底消灭黑盒静默退出）。
 * 
 * 环境变量注入（Box64/Mesa/DXVK）与显示分辨率配置已完全交还外部启动脚本 launcher.sh。
 */
void WinMainCRTStartup(void) {
    SetCurrentDirectoryW(L".");

    HMODULE hUnity = LoadLibraryW(L"UnityPlayer.dll");
    if (!hUnity) {
        DWORD err = GetLastError();
        char msg[128];
        char code[16];
        int idx = 0;
        const char prefix[] = "Fatal: Failed to load UnityPlayer.dll (ErrorCode: ";
        for (int i = 0; prefix[i]; i++) {
            msg[idx++] = prefix[i];
        }

        DWORD tmp = err;
        int c_idx = 0;
        if (tmp == 0) {
            code[c_idx++] = '0';
        } else {
            while (tmp > 0 && c_idx < 10) {
                code[c_idx++] = (char)('0' + (tmp % 10));
                tmp /= 10;
            }
        }
        for (int i = c_idx - 1; i >= 0; i--) {
            msg[idx++] = code[i];
        }
        msg[idx++] = ')';
        msg[idx] = '\0';

        MessageBoxA(NULL, msg, "AliceInCradle Launcher Error", MB_ICONERROR | MB_OK);
        ExitProcess(1);
    }

    UnityMain2_t pUnityMain2 = (UnityMain2_t)GetProcAddress(hUnity, "UnityMain2");
    if (!pUnityMain2) {
        MessageBoxA(NULL, "Fatal: Export 'UnityMain2' not found in UnityPlayer.dll", "AliceInCradle Launcher Error", MB_ICONERROR | MB_OK);
        ExitProcess(2);
    }

    /* 正确解析并透传 Command Line 参数（跳过 argv[0] 可执行文件路径） */
    LPSTR lpCmdLine = GetCommandLineA();
    if (lpCmdLine) {
        if (*lpCmdLine == '"') {
            lpCmdLine++;
            while (*lpCmdLine && *lpCmdLine != '"') lpCmdLine++;
            if (*lpCmdLine == '"') lpCmdLine++;
        } else {
            while (*lpCmdLine && *lpCmdLine > ' ') lpCmdLine++;
        }
        while (*lpCmdLine && *lpCmdLine <= ' ') lpCmdLine++;
    } else {
        lpCmdLine = "";
    }

    int ret = pUnityMain2(GetModuleHandleW(NULL), NULL, lpCmdLine, SW_SHOW);
    ExitProcess(ret);
}
