# ============================================================
# 构建 MultiOpener 原生组件（w64devkit 便携 GCC，纯 D 盘，免安装）
# 输出：windows\src\native\bin\injector.exe / hook.dll
# 依赖：D:\w64devkit\bin\gcc.exe（下载物，只存在 D:\w64devkit 一个目录）
# ============================================================
$ErrorActionPreference = "Stop"
$gcc = "D:\w64devkit\bin\gcc.exe"
if (-not (Test-Path -LiteralPath $gcc)) {
    Write-Error "找不到 GCC：$gcc（请先把 w64devkit 解压到 D:\w64devkit）"
    exit 1
}
$dir = "D:\MultiOpener\Shelter-gh\windows\src\native"
$bin = Join-Path $dir "bin"
New-Item -ItemType Directory -Path $bin -Force | Out-Null

& $gcc -shared -o (Join-Path $bin "hook.dll") (Join-Path $dir "hook.c") -lkernel32
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $gcc -mwindows -o (Join-Path $bin "injector.exe") (Join-Path $dir "injector.c") -lkernel32
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Output "native build OK:"
Get-ChildItem -LiteralPath $bin | ForEach-Object { Write-Output ("  {0}  {1} KB" -f $_.Name, [math]::Round($_.Length/1KB)) }
