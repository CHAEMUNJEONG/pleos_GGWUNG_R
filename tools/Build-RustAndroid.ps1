param(
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$pleosRoot = Split-Path -Parent (Split-Path -Parent $repoRoot)
if (Test-Path -LiteralPath (Join-Path $pleosRoot 'Enter-PleosRust.ps1')) {
    . (Join-Path $pleosRoot 'Enter-PleosRust.ps1')
}

$rustDir = Join-Path $repoRoot 'rust'
$jniLibs = Join-Path $repoRoot 'app\src\main\jniLibs'
$rustBin = Join-Path $rustDir 'bin'

Set-Location -LiteralPath $rustDir

if (-not $SkipTests) {
    Write-Output '==> Running Rust workspace tests (kkwongal-core + kkwongal-server)...'
    cargo test -p kkwongal-core -p kkwongal-server
    if ($LASTEXITCODE -ne 0) { throw 'Rust tests failed' }
}

Write-Output '==> Building host Windows kkwongal_jni.dll and kkwongal-server.exe (release)...'
cargo build -p kkwongal-jni -p kkwongal-server --release
if ($LASTEXITCODE -ne 0) { throw 'Host release build failed' }

Write-Output '==> Building Android x86_64 libkkwongal_jni.so (release)...'
cargo build -p kkwongal-jni --release --target x86_64-linux-android
if ($LASTEXITCODE -ne 0) { throw 'Android x86_64 build failed' }

Write-Output '==> Building Android arm64-v8a libkkwongal_jni.so (release)...'
cargo build -p kkwongal-jni --release --target aarch64-linux-android
if ($LASTEXITCODE -ne 0) { throw 'Android arm64-v8a build failed' }

$x86Dst = Join-Path $jniLibs 'x86_64'
$armDst = Join-Path $jniLibs 'arm64-v8a'
$hostDst = Join-Path $jniLibs 'host-windows-x86_64'
New-Item -ItemType Directory -Path $x86Dst, $armDst, $hostDst, $rustBin -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $rustDir 'target\x86_64-linux-android\release\libkkwongal_jni.so') -Destination (Join-Path $x86Dst 'libkkwongal_jni.so') -Force
Copy-Item -LiteralPath (Join-Path $rustDir 'target\aarch64-linux-android\release\libkkwongal_jni.so') -Destination (Join-Path $armDst 'libkkwongal_jni.so') -Force
Copy-Item -LiteralPath (Join-Path $rustDir 'target\release\kkwongal_jni.dll') -Destination (Join-Path $hostDst 'kkwongal_jni.dll') -Force
Copy-Item -LiteralPath (Join-Path $rustDir 'target\release\kkwongal-server.exe') -Destination (Join-Path $rustBin 'kkwongal-server.exe') -Force
