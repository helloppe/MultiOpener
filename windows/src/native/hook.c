/* ============================================================
 * hook.dll —— 互斥体绕过 v2（MultiOpener 自研组件，GPL-3.0）
 *
 * 原理：把 kernel32 的 CreateMutexW/A/ExW/ExA 入口改写为 jmp 到
 * 本组件。本组件把互斥体名追加 "_MO<进程ID>" 后缀后调用原始实现
 * （trampoline）。每个进程的互斥体名都不同，任何按固定互斥体名
 * 检测单实例的软件都会认为没有其它实例，从而多开。
 *
 * 兼容性：Windows 10/11 上这些入口常是指向内部实现的
 * jmp [rip+disp32] 跳板（6 字节），trampoline 自动修正 rip 相对
 * 偏移；普通代码入口（5 字节）也能处理。
 *
 * 编译：D:\w64devkit\bin\gcc.exe -shared -O2 -o hook.dll hook.c
 * ============================================================ */
#include <windows.h>
#include <stdlib.h>
#include <string.h>

/* 四个入口的签名 */
typedef HANDLE(WINAPI *FnMutexW)(LPSECURITY_ATTRIBUTES, BOOL, LPCWSTR);
typedef HANDLE(WINAPI *FnMutexA)(LPSECURITY_ATTRIBUTES, BOOL, LPCSTR);
typedef HANDLE(WINAPI *FnMutexExW)(LPSECURITY_ATTRIBUTES, LPCWSTR, DWORD, DWORD);
typedef HANDLE(WINAPI *FnMutexExA)(LPSECURITY_ATTRIBUTES, LPCSTR, DWORD, DWORD);

typedef struct {
    const char *apiName;
    void *hookFn;
    BYTE orig[8];
    int len;
    BYTE *tramp;
    DWORD oldProt;
} HookSlot;

static HookSlot g_hooks[4];
static int g_nHooks = 0;

/* 数字转宽字符 */
static void u2w(unsigned long v, WCHAR *out) {
    WCHAR tmp[16];
    int n = 0;
    if (v == 0) tmp[n++] = L'0';
    while (v > 0) { tmp[n++] = (WCHAR)(L'0' + (v % 10)); v /= 10; }
    while (n > 0) *out++ = tmp[--n];
    *out = 0;
}

/* 构造 trampoline：原始字节 + jmp 回 target+len */
static int build_trampoline(HookSlot *s, BYTE *target) {
    int len = s->len;
    s->tramp = (BYTE*)VirtualAlloc(NULL, len + 5, MEM_COMMIT | MEM_RESERVE,
                                   PAGE_EXECUTE_READWRITE);
    if (!s->tramp) return 0;
    if (s->orig[0] == 0xFF && s->orig[1] == 0x25 && len >= 6) {
        INT32 disp = *(INT32*)(s->orig + 2);
        INT64 origNext = (INT64)target + 6;
        INT64 newBase = (INT64)s->tramp + 6;
        INT64 targetAddr = origNext + disp;
        INT32 newDisp = (INT32)(targetAddr - newBase);
        memcpy(s->tramp, s->orig, 6);
        *(INT32*)(s->tramp + 2) = newDisp;
        len = 6;
    } else {
        memcpy(s->tramp, s->orig, 5);
        len = 5;
    }
    s->tramp[len] = 0xE9;
    INT64 from = (INT64)(s->tramp + len + 5);
    INT64 to = (INT64)(target + len);
    *(INT32*)(s->tramp + len + 1) = (INT32)(to - from);
    return 1;
}

static void patch_one(HookSlot *s) {
    HMODULE k = GetModuleHandleW(L"kernel32.dll");
    BYTE *target = (BYTE*)GetProcAddress(k, s->apiName);
    if (!target) return;
    VirtualProtect(target, 8, PAGE_EXECUTE_READWRITE, &s->oldProt);
    memcpy(s->orig, target, 8);
    s->len = (s->orig[0] == 0xFF && s->orig[1] == 0x25) ? 6 : 5;
    if (!build_trampoline(s, target)) return;
    target[0] = 0xE9;
    *(INT32*)(target + 1) = (INT32)((INT64)s->hookFn - (INT64)target - 5);
    VirtualProtect(target, 8, s->oldProt, &s->oldProt);
}

/* ---------- Hook 本体：互斥体名加进程 ID 后缀 ---------- */
static HANDLE WINAPI HookMutexW(LPSECURITY_ATTRIBUTES sa, BOOL inherit, LPCWSTR name) {
    WCHAR newName[520];
    if (name != NULL && name[0] != L'\0') {
        int n = 0;
        while (name[n] && n < 470) n++;
        memcpy(newName, name, n * sizeof(WCHAR));
        wcscpy(newName + n, L"_MO");
        n += 3;
        u2w(GetCurrentProcessId(), newName + n);
        name = newName;
    }
    return ((FnMutexW)g_hooks[0].tramp)(sa, inherit, name);
}

static HANDLE WINAPI HookMutexExW(LPSECURITY_ATTRIBUTES sa, LPCWSTR name, DWORD flags, DWORD access) {
    WCHAR newName[520];
    if (name != NULL && name[0] != L'\0') {
        int n = 0;
        while (name[n] && n < 470) n++;
        memcpy(newName, name, n * sizeof(WCHAR));
        wcscpy(newName + n, L"_MO");
        n += 3;
        u2w(GetCurrentProcessId(), newName + n);
        name = newName;
    }
    return ((FnMutexExW)g_hooks[1].tramp)(sa, name, flags, access);
}

static HANDLE WINAPI HookMutexA(LPSECURITY_ATTRIBUTES sa, BOOL inherit, LPCSTR name) {
    char newName[520];
    if (name != NULL && name[0] != '\0') {
        int n = 0;
        while (name[n] && n < 470) n++;
        memcpy(newName, name, n);
        strcpy(newName + n, "_MO");
        n += 3;
        char buf[16];
        _ultoa_s(GetCurrentProcessId(), buf, 16, 10);
        strcpy(newName + n, buf);
        name = newName;
    }
    return ((FnMutexA)g_hooks[2].tramp)(sa, inherit, name);
}

static HANDLE WINAPI HookMutexExA(LPSECURITY_ATTRIBUTES sa, LPCSTR name, DWORD flags, DWORD access) {
    char newName[520];
    if (name != NULL && name[0] != '\0') {
        int n = 0;
        while (name[n] && n < 470) n++;
        memcpy(newName, name, n);
        strcpy(newName + n, "_MO");
        n += 3;
        char buf[16];
        _ultoa_s(GetCurrentProcessId(), buf, 16, 10);
        strcpy(newName + n, buf);
        name = newName;
    }
    return ((FnMutexExA)g_hooks[3].tramp)(sa, name, flags, access);
}

/* 供 injector 使用的"强制立即打补丁"导出：进程启动后注入也能生效 */
__declspec(dllexport) void ForcePatch(void) {
    int i;
    for (i = 0; i < g_nHooks; i++) {
        if (!g_hooks[i].tramp) patch_one(&g_hooks[i]);
    }
}

static DWORD WINAPI patch_thread(LPVOID unused) {
    ForcePatch();
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE hDll, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hDll);
        g_hooks[0].apiName = "CreateMutexW";   g_hooks[0].hookFn = HookMutexW;
        g_hooks[1].apiName = "CreateMutexExW"; g_hooks[1].hookFn = HookMutexExW;
        g_hooks[2].apiName = "CreateMutexA";   g_hooks[2].hookFn = HookMutexA;
        g_hooks[3].apiName = "CreateMutexExA"; g_hooks[3].hookFn = HookMutexExA;
        g_nHooks = 4;
        HANDLE t = CreateThread(NULL, 0, patch_thread, NULL, 0, NULL);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
