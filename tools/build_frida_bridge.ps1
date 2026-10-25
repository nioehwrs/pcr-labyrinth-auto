# 编译方案 B 的 native 桥：libfrida_bridge.so（android-x86_64，给 MuMu 用）
#
# 依赖（都装好了，路径可按需覆盖）：
#   NDK    : D:\Environment\android-sdk\ndk\29.0.14206865
#   devkit : D:\Environment\frida-devkit-17.16.4 （libfrida-core.a + frida-core.h）
#   ★版本必须和设备上那份 frida-server 对上（当前 17.16.4，资产见 assets\frida-server-17.16.4-x86_64，
#     来源是 pcrtool\assets\frida\huabanxie-server，md5 B6D6FA07…）
#
# 用法：
#   powershell -File tools\build_frida_bridge.ps1
# 产物：
#   android\app\src\main\jniLibs\x86_64\libfrida_bridge.so（Gradle 会自动打进 APK）
param(
    [string]$Ndk = 'D:\Environment\android-sdk\ndk\29.0.14206865',
    [string]$Devkit = 'D:\Environment\frida-devkit-17.16.4',
    [string]$ApiLevel = '26',
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $root 'android\app\src\main\jniLibs\x86_64'
}
$cc = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$src = Join-Path $PSScriptRoot 'frida_bridge\bridge.c'
$out = Join-Path $OutDir 'libfrida_bridge.so'

foreach ($p in @($cc, (Join-Path $Devkit 'frida-core.h'), (Join-Path $Devkit 'libfrida-core.a'), $src)) {
    if (-not (Test-Path $p)) { throw "缺文件: $p" }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$t0 = Get-Date

& $cc "--target=x86_64-linux-android$ApiLevel" `
    -shared -fPIC -O2 -ffunction-sections -fdata-sections -s `
    -I"$Devkit" `
    -o $out $src `
    -L"$Devkit" -lfrida-core -llog -ldl -lm -pthread `
    '-Wl,--gc-sections'
# 说明：产物约 116MB —— libfrida-core.a 里静态带进来了 V8/GLib/gum 一大堆，
#       gc-sections 砍不掉（运行时注册表引用着它们），只好接受。
#       -s 是链接期剥离符号；如果忘了它，产物会是 156MB。

if ($LASTEXITCODE -ne 0) { throw "编译失败（clang 退出码 $LASTEXITCODE）" }

$f = Get-Item $out
"编译完成：{0}（{1} MB，用时 {2:N1}s）" -f $f.FullName, [math]::Round($f.Length / 1MB, 2), ((Get-Date) - $t0).TotalSeconds
