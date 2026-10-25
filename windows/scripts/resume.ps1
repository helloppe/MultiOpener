# 恢复指定进程（参数：进程 ID）
param([long]$Pid)
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class ProcRes {
    [DllImport("ntdll.dll")] public static extern int NtResumeProcess(IntPtr h);
}
"@
$p = Get-Process -Id $Pid -ErrorAction SilentlyContinue
if ($p) {
    [ProcRes]::NtResumeProcess($p.Handle) | Out-Null
}
