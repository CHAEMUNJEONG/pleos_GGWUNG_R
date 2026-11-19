param(
    [string]$AdbPath = 'adb',
    [string]$Serial = 'emulator-6000',
    [ValidateRange(1000, 60000)][int]$WaitMs = 20000,
    [string]$ApkPath = (Join-Path $PSScriptRoot '../app/build/outputs/apk/debug/app-debug.apk'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../sdk-probe-results')
)
$ErrorActionPreference = 'Stop'
& $AdbPath -s $Serial get-state
if ($LASTEXITCODE -ne 0) { throw 'ADB device unavailable; check serial and USB debugging approval.' }
& $AdbPath -s $Serial install -r -g $ApkPath
if ($LASTEXITCODE -ne 0) { throw 'Debug APK installation failed.' }
$raw = (& $AdbPath -s $Serial shell am instrument -w -e waitMs $WaitMs `
    com.kkwongal.drive/.debug.PleosSdkProbe 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Instrumentation failed: $raw" }
$line = $raw -split "`n" | Where-Object { $_ -match '^INSTRUMENTATION_RESULT: sdkProbe=' } | Select-Object -First 1
if (-not $line) { throw "SDK probe returned no structured result: $raw" }
$result = $line.Substring($line.IndexOf('=') + 1) | ConvertFrom-Json
$result | Add-Member -NotePropertyName 'deviceSerial' -NotePropertyValue $Serial
$result | Add-Member -NotePropertyName 'observedAtUtc' -NotePropertyValue ([DateTime]::UtcNow.ToString('o'))
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$path = Join-Path $OutputDirectory ("sdk-probe-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding utf8
$result | ConvertTo-Json -Depth 8
Write-Output "Saved SDK report: $path"
if (-not $result.serviceConnected) { Write-Warning 'SDK service connection was not confirmed.' }
if ($result.locationCallbacks -eq 0) { Write-Warning 'Location reception remains unverified.' }
if ($result.arrivalCallbacks -eq 0) { Write-Warning 'Destination arrival remains unverified.' }
if ($null -ne $result.probeError) { throw 'SDK probe reported an exception; inspect the saved report.' }
