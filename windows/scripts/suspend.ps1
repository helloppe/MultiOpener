# 挂起指定进程（参数：进程 ID）
param([long]$Pid)
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class ProcSus {
    [DllImport("ntdll.dll")] public static extern int NtSuspendProcess(IntPtr h);
}
"@
$p = Get-Process -Id $Pid -ErrorAction SilentlyContinue
if ($p) {
    [ProcSus]::NtSuspendProcess($p.Handle) | Out-Null
}
