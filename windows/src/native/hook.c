/* ============================================================
 * hook.dll —— 互斥体绕过（MultiOpener 自研组件，GPL-3.0）
 *
 * 原理：把 kernel32!CreateMutexW 入口改写为 jmp 到本函数。
 * 本函数把互斥体名追加 "_MO<进程ID>" 后缀后，调用保存的
 * 原始实现（trampoline）。每个进程的互斥体名都不同，任何按
 * "固定互斥体名"检测单实例的软件（PCL2、游戏登录器等）都会
 * 认为没有其它实例，从而无限多开。
 *
 * 兼容性：Windows 10/11 上 kernel32!CreateMutexW 常是指向
 * 内部实现的 jmp [rip+disp32] 跳板（6 字节），trampoline 会
 * 自动修正 rip 相对偏移；普通代码入口（5 字节）也能处理。
 *
 * 编译：D:\tcc\win64\tcc.exe -shared -o hook.dll hook.c
 * ============================================================ */
#include <windows.h>
#include <wchar.h>

typedef HANDLE(WINAPI *CreateMutexWFn)(LPSECURITY_ATTRIBUTES, BOOL, LPCWSTR);

static BYTE  g_origBytes[8];     /* 原始入口字节 */
static int   g_origLen = 5;      /* 需要恢复的长度 */
static BYTE *g_trampoline = NULL;
static DWORD g_oldProtect = 0;

/* 数字转宽字符（不用 CRT，兼容 tcc） */
static void u2w(unsigned long v, WCHAR *out) {
    WCHAR tmp[16];
    int n = 0;
    if (v == 0) tmp[n++] = L'0';
    while (v > 0) { tmp[n++] = (WCHAR)(L'0' + (v % 10)); v /= 10; }
    while (n > 0) *out++ = tmp[--n];
    *out = 0;
}

/* 构造 trampoline：原始字节 + jmp 回 target+len */
static int build_trampoline(BYTE *target) {
    int len = g_origLen;
    g_trampoline = (BYTE*)VirtualAlloc(NULL, len + 5, MEM_COMMIT | MEM_RESERVE,
                                       PAGE_EXECUTE_READWRITE);
    if (!g_trampoline) return 0;

    /* 若是 jmp [rip+disp32]（FF 25），修正相对偏移 */
    if (g_origBytes[0] == 0xFF && g_origBytes[1] == 0x25 && len >= 6) {
        INT32 disp = *(INT32*)(g_origBytes + 2);
        INT64 origNext = (INT64)target + 6;              /* 原始 disp 基准 */
        INT64 newBase  = (INT64)g_trampoline + 6;        /* 新位置基准 */
        INT64 targetAddr = origNext + disp;              /* 跳转目标 */
        INT32 newDisp = (INT32)(targetAddr - newBase);
        memcpy(g_trampoline, g_origBytes, 6);
        *(INT32*)(g_trampoline + 2) = newDisp;
        len = 6;
    } else {
        memcpy(g_trampoline, g_origBytes, 5);
        len = 5;
    }
    g_trampoline[len] = 0xE9;
    INT64 from = (INT64)(g_trampoline + len + 5);
    INT64 to   = (INT64)(target + len);
    *(INT32*)(g_trampoline + len + 1) = (INT32)(to - from);
    return 1;
}

/* Hook 本体：互斥体名加进程 ID 后缀后走原始实现 */
static HANDLE WINAPI CreateMutexWHook(LPSECURITY_ATTRIBUTES sa, BOOL inherit, LPCWSTR name) {
    WCHAR newName[520];
    if (name != NULL && name[0] != L'\0') {
        int n = 0;
        while (name[n] && n < 470) n++;
        if (n > 0) {
            memcpy(newName, name, n * sizeof(WCHAR));
            wcscpy(newName + n, L"_MO");
            n += 3;
            u2w(GetCurrentProcessId(), newName + n);
            name = newName;
        }
    }
    return ((CreateMutexWFn)g_trampoline)(sa, inherit, name);
}

/* 改写 CreateMutexW 入口 */
static DWORD WINAPI patch_thread(LPVOID unused) {
    HMODULE k = GetModuleHandleW(L"kernel32.dll");
    BYTE *target = (BYTE*)GetProcAddress(k, "CreateMutexW");
    if (!target) return 1;

    /* 读入口字节，判断指令模式 */
    VirtualProtect(target, 8, PAGE_EXECUTE_READWRITE, &g_oldProtect);
    memcpy(g_origBytes, target, 8);
    g_origLen = (g_origBytes[0] == 0xFF && g_origBytes[1] == 0x25) ? 6 : 5;

    if (!build_trampoline(target)) return 2;
    if (!g_trampoline) return 3;

    /* 入口改写为 jmp Hook */
    target[0] = 0xE9;
    *(INT32*)(target + 1) = (INT32)((INT64)CreateMutexWHook - (INT64)target - 5);
    VirtualProtect(target, 8, g_oldProtect, &g_oldProtect);
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE hDll, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hDll);
        /* 独立线程打补丁，避免在 loader lock 里做危险操作 */
        HANDLE t = CreateThread(NULL, 0, patch_thread, NULL, 0, NULL);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
