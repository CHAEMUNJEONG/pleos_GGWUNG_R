param(
    [switch]$SkipTests,
    [switch]$HostOnly
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$rustDir = Join-Path $repoRoot 'rust'
$generatedJni = Join-Path $repoRoot 'app\build\generated\rustJniLibs'

function Resolve-AndroidNdk {
    foreach ($varName in @('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'NDK_HOME')) {
        $val = [Environment]::GetEnvironmentVariable($varName)
        if ($val -and (Test-Path -LiteralPath $val)) {
            return (Get-Item -LiteralPath $val).FullName
        }
    }
    $sdkCandidates = @()
    foreach ($sdkVar in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $val = [Environment]::GetEnvironmentVariable($sdkVar)
        if ($val -and (Test-Path -LiteralPath $val)) {
            $sdkCandidates += $val
        }
    }
    $localProps = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $localProps) {
        $line = Get-Content -LiteralPath $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $sdkPath = ($line -replace '^sdk\.dir=', '').Trim() -replace '\\\\', '\' -replace '\\:', ':'
            if (Test-Path -LiteralPath $sdkPath) { $sdkCandidates += $sdkPath }
        }
    }
    foreach ($sdk in $sdkCandidates) {
        $ndkRoot = Join-Path $sdk 'ndk'
        if (Test-Path -LiteralPath $ndkRoot) {
            $latest = Get-ChildItem -LiteralPath $ndkRoot -Directory | Sort-Object Name | Select-Object -Last 1
            if ($latest) { return $latest.FullName }
        }
    }
    throw 'Android NDK not found. Set ANDROID_NDK_HOME (or ANDROID_HOME with ndk/<version> installed).'
}

Set-Location -LiteralPath $rustDir

if (-not $SkipTests) {
    Write-Output '==> Running Rust workspace tests (kkwongal-core + kkwongal-server)...'
    cargo test -p kkwongal-core -p kkwongal-server
    if ($LASTEXITCODE -ne 0) { throw 'Rust tests failed' }
}

Write-Output '==> Building host kkwongal-jni and kkwongal-server (release)...'
cargo build -p kkwongal-jni -p kkwongal-server --release
if ($LASTEXITCODE -ne 0) { throw 'Host release build failed' }

if (-not $HostOnly) {
    $ndkDir = Resolve-AndroidNdk
    $prebuiltRoot = Join-Path $ndkDir 'toolchains\llvm\prebuilt'
    $hostPrebuilt = Get-ChildItem -LiteralPath $prebuiltRoot -Directory | Select-Object -First 1
    if (-not $hostPrebuilt) { throw "NDK LLVM prebuilt toolchain not found under $prebuiltRoot" }
    $ndkBin = Join-Path $hostPrebuilt.FullName 'bin'
    $clangExt = if ($env:OS -eq 'Windows_NT') { '.cmd' } else { '' }
    $arExt = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }

    $env:ANDROID_NDK_HOME = $ndkDir
    $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = Join-Path $ndkBin "x86_64-linux-android28-clang$clangExt"
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $ndkBin "aarch64-linux-android28-clang$clangExt"
    $env:CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
    $env:CC_x86_64_linux_android = $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER
    $env:CC_aarch64_linux_android = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
    $env:AR_x86_64_linux_android = Join-Path $ndkBin "llvm-ar$arExt"
    $env:AR_aarch64_linux_android = Join-Path $ndkBin "llvm-ar$arExt"

    Write-Output "==> Building Android x86_64 libkkwongal_jni.so (NDK: $ndkDir)..."
    cargo build -p kkwongal-jni --release --target x86_64-linux-android
    if ($LASTEXITCODE -ne 0) { throw 'Android x86_64 build failed' }

    Write-Output "==> Building Android arm64-v8a libkkwongal_jni.so (NDK: $ndkDir)..."
    cargo build -p kkwongal-jni --release --target aarch64-linux-android
    if ($LASTEXITCODE -ne 0) { throw 'Android arm64-v8a build failed' }

    $x86Dst = Join-Path $generatedJni 'x86_64'
    $armDst = Join-Path $generatedJni 'arm64-v8a'
    New-Item -ItemType Directory -Path $x86Dst, $armDst -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $rustDir 'target\x86_64-linux-android\release\libkkwongal_jni.so') -Destination (Join-Path $x86Dst 'libkkwongal_jni.so') -Force
    Copy-Item -LiteralPath (Join-Path $rustDir 'target\aarch64-linux-android\release\libkkwongal_jni.so') -Destination (Join-Path $armDst 'libkkwongal_jni.so') -Force
}
