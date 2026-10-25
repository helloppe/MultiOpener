/* ============================================================
 * injector.exe —— 启动目标程序并注入 hook.dll（MultiOpener 自研组件，GPL-3.0）
 *
 * 用法：
 *   injector.exe "<目标exe路径>" ["<hook.dll 路径>"] ["@延迟毫秒"]
 *   输出：启动成功的进程 PID（stdout 首行）
 *
 * 两种注入模式：
 *   1) 挂起模式（默认）：CreateProcess(CREATE_SUSPENDED) → 写 hook.dll 路径 →
 *      CreateRemoteThread(LoadLibraryA) → ResumeThread。
 *      适合一般软件；但进程挂起时远程线程不执行，hook 生效时机与主线程竞争。
 *   2) 后注入模式（第三参数 @ms）：CreateProcess 正常启动 → Sleep(ms) →
 *      CreateRemoteThread(LoadLibraryA) → hook 在目标创建单实例互斥体前生效，
 *      适合启动早期就创建互斥体的软件（如游戏启动器）。
 *
 * 编译：D:\w64devkit\bin\gcc.exe -O2 -o injector.exe injector.c -lkernel32
 * ============================================================ */
#include <windows.h>
#include <wchar.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    LPWSTR cmd = GetCommandLineW();
    WCHAR exePath[2048] = {0};
    WCHAR hookPath[2048] = {0};
    WCHAR args[4096] = {0};
    WCHAR delayArg[64] = {0};
    int argi = 0;

    LPWSTR p = cmd;
    if (*p == L'"') { p++; while (*p && *p != L'"') p++; if (*p) p++; }
    else { while (*p && *p != L' ') p++; }
    while (*p == L' ') p++;

    for (argi = 0; argi < 3 && *p; argi++) {
        WCHAR buf[2048] = {0};
        int i = 0;
        if (*p == L'"') {
            p++;
            while (*p && *p != L'"') { if (i < 2047) buf[i++] = *p; p++; }
            if (*p) p++;
        } else {
            while (*p && *p != L' ') { if (i < 2047) buf[i++] = *p; p++; }
        }
        if (argi == 0) wcscpy(exePath, buf);
        else if (argi == 1) wcscpy(hookPath, buf);
        else wcscpy(delayArg, buf);
        while (*p == L' ') p++;
    }
    if (*p) wcscpy(args, p);

    if (exePath[0] == 0) {
        fprintf(stderr, "usage: injector.exe <exe> [hook.dll] [@delayMs]\n");
        return 1;
    }

    /* 解析后注入延迟 */
    int delayMs = -1;
    if (delayArg[0] == L'@') {
        delayMs = 0;
        for (int i = 1; delayArg[i]; i++) {
            if (delayArg[i] >= L'0' && delayArg[i] <= L'9') delayMs = delayMs * 10 + (delayArg[i] - L'0');
            else break;
        }
    }

    /* 默认 hook.dll 与 injector.exe 同目录 */
    if (hookPath[0] == 0) {
        GetModuleFileNameW(NULL, hookPath, 2048);
        WCHAR *slash = wcsrchr(hookPath, L'\\');
        if (slash) *(slash + 1) = 0;
        wcscat(hookPath, L"hook.dll");
    }
    if (GetFileAttributesW(hookPath) == INVALID_FILE_ATTRIBUTES) {
        fprintf(stderr, "hook.dll not found: %ls\n", hookPath);
        return 2;
    }

    /* 拼目标命令行 */
    WCHAR targetCmd[4096] = {0};
    if (wcsrchr(exePath, L' ')) { wcscat(targetCmd, L"\""); wcscat(targetCmd, exePath); wcscat(targetCmd, L"\""); }
    else wcscat(targetCmd, exePath);
    if (args[0]) { wcscat(targetCmd, L" "); wcscat(targetCmd, args); }

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si)); si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    DWORD flags = (delayMs < 0) ? CREATE_SUSPENDED : 0;
    if (!CreateProcessW(NULL, targetCmd, NULL, NULL, FALSE, flags,
                        NULL, NULL, &si, &pi)) {
        fprintf(stderr, "CreateProcess failed: %lu\n", GetLastError());
        return 3;
    }

    /* 后注入模式：先让目标跑 delayMs，再注入 */
    if (delayMs >= 0) {
        Sleep((DWORD)delayMs);
    }

    char hookAnsi[1024] = {0};
    WideCharToMultiByte(CP_ACP, 0, hookPath, -1, hookAnsi, 1024, NULL, NULL);
    SIZE_T lenA = strlen(hookAnsi) + 1;

    void *remote = VirtualAllocEx(pi.hProcess, NULL, lenA, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote) {
        fprintf(stderr, "VirtualAllocEx failed: %lu\n", GetLastError());
        TerminateProcess(pi.hProcess, 1);
        return 4;
    }
    SIZE_T written = 0;
    if (!WriteProcessMemory(pi.hProcess, remote, hookAnsi, lenA, &written)) {
        fprintf(stderr, "WriteProcessMemory failed: %lu\n", GetLastError());
        TerminateProcess(pi.hProcess, 1);
        return 5;
    }

    HMODULE k = GetModuleHandleA("kernel32.dll");
    FARPROC loadLib = GetProcAddress(k, "LoadLibraryA");
    if (!loadLib) {
        TerminateProcess(pi.hProcess, 1);
        return 6;
    }

    HANDLE hThread = CreateRemoteThread(pi.hProcess, NULL, 0,
                                        (LPTHREAD_START_ROUTINE)loadLib, remote, 0, NULL);
    if (!hThread) {
        fprintf(stderr, "CreateRemoteThread failed: %lu\n", GetLastError());
        TerminateProcess(pi.hProcess, 1);
        return 7;
    }
    WaitForSingleObject(hThread, 8000);
    CloseHandle(hThread);
    VirtualFreeEx(pi.hProcess, remote, 0, MEM_RELEASE);

    if (delayMs < 0) {
        ResumeThread(pi.hThread);
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);

    printf("%lu\n", pi.dwProcessId);
    return 0;
}
