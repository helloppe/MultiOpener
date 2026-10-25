/* ============================================================
 * injector.exe —— 挂起启动目标程序 → 注入 hook.dll → 恢复运行
 * （MultiOpener 自研组件，GPL-3.0）
 *
 * 用法：injector.exe "<目标exe路径>" ["<hook.dll 路径>"]
 * 输出：启动成功的进程 PID（stdout 首行）
 *
 * 流程：CreateProcess(CREATE_SUSPENDED) → 远程写 hook.dll
 * 全路径 → CreateRemoteThread(LoadLibraryA) → 等待加载完成 →
 * ResumeThread → 打印 PID。
 *
 * 编译：D:\tcc\win64\tcc.exe -o injector.exe injector.c -lkernel32
 * ============================================================ */
#include <windows.h>
#include <wchar.h>
#include <stdio.h>

int main(void) {
    LPWSTR cmd = GetCommandLineW();
    WCHAR exePath[2048] = {0};
    WCHAR hookPath[2048] = {0};
    WCHAR args[4096] = {0};
    int argi = 0;

    /* 手工解析命令行（兼容带空格路径） */
    LPWSTR p = cmd;
    if (*p == L'"') { p++; while (*p && *p != L'"') p++; if (*p) p++; }
    else { while (*p && *p != L' ') p++; }
    while (*p == L' ') p++;

    for (argi = 0; argi < 2 && *p; argi++) {
        WCHAR buf[2048] = {0};
        int i = 0;
        if (*p == L'"') {
            p++;
            while (*p && *p != L'"') { if (i < 2047) buf[i++] = *p; p++; }
            if (*p) p++;
        } else {
            while (*p && *p != L' ') { if (i < 2047) buf[i++] = *p; p++; }
        }
        if (argi == 0) wcscpy(exePath, buf); else wcscpy(hookPath, buf);
        while (*p == L' ') p++;
    }
    /* 剩余全部作为目标程序的参数 */
    if (*p) wcscpy(args, p);

    if (exePath[0] == 0) {
        fprintf(stderr, "usage: injector.exe <exe> [hook.dll]\n");
        return 1;
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

    /* 拼目标命令行：exe（带引号）+ 参数 */
    WCHAR targetCmd[4096] = {0};
    if (wcsrchr(exePath, L' ')) { wcscat(targetCmd, L"\""); wcscat(targetCmd, exePath); wcscat(targetCmd, L"\""); }
    else wcscat(targetCmd, exePath);
    if (args[0]) { wcscat(targetCmd, L" "); wcscat(targetCmd, args); }

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si)); si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    if (!CreateProcessW(NULL, targetCmd, NULL, NULL, FALSE, CREATE_SUSPENDED,
                        NULL, NULL, &si, &pi)) {
        fprintf(stderr, "CreateProcess failed: %lu\n", GetLastError());
        return 3;
    }

    /* hook 路径转 ANSI，写到目标进程 */
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

    ResumeThread(pi.hThread);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);

    /* 输出 PID 供 Java 读取 */
    printf("%lu\n", pi.dwProcessId);
    return 0;
}
