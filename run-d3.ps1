#requires -Version 7.4
param(
    [switch]$Build,
    [switch]$StartBackend,
    [switch]$Collect,
    [switch]$NoPreview,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,59}$')][string]$InstanceName = 'collector-new'
)

$ErrorActionPreference = 'Stop'
$lab = 'F:\D3SereinLab'
$deploy = Join-Path $lab 'deployment\one-click\Deploy-D3CollectorVm.ps1'
if (!(Test-Path -LiteralPath $deploy)) { throw "D3 installer is missing: $deploy" }
$null = & (Join-Path $PSScriptRoot 'scripts\Configure-D3Launcher.ps1')
if ($Build -and (Get-NetTCPConnection -State Listen -LocalPort 8099 -ErrorAction SilentlyContinue)) {
    throw 'Stop the existing backend before rebuilding.'
}

# Always use the template entry point: its compatibility layer must be restored after a cold boot.
# Serial/ports come from the one-click receipt so a renamed instance or changed port keeps working.
$evidence = Join-Path $lab "instances\$InstanceName\evidence"
$prior = Get-ChildItem -LiteralPath $evidence -Filter 'one-click-*.json' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
$priorReceipt = if ($prior) { Get-Content -LiteralPath $prior.FullName -Raw | ConvertFrom-Json } else { $null }
$serial = if ($priorReceipt -and $priorReceipt.Serial) { $priorReceipt.Serial } else { 'emulator-5668' }
$adbPort = if ($priorReceipt -and $priorReceipt.AdbServerPort) { [int]$priorReceipt.AdbServerPort } else { 5068 }
$emulatorPort = if ($serial -match '^emulator-(\d+)$') { [int]$Matches[1] } else { 5668 }

$previewPath = Join-Path $lab 'tools\scrcpy\4.1\scrcpy.exe'
$previewRunning = @(Get-CimInstance Win32_Process -Filter "Name='scrcpy.exe'" | Where-Object {
    $_.ExecutablePath -eq $previewPath -and $_.CommandLine -like "*--serial=$serial*"
}).Count -gt 0
$receipt = & $deploy -InstanceName $InstanceName -EmulatorPort $emulatorPort -AdbServerPort $adbPort `
    -NoPreview:($NoPreview -or $previewRunning) -LoginOnly:(!$Collect)
if (!$receipt.RuntimeReady -or !$receipt.AppLaunchVerified -or
    $receipt.Serial -ne $serial -or $receipt.AdbServerPort -ne $adbPort) {
    throw "The D3 instance $InstanceName ($serial / ADB $adbPort) is not ready."
}

$env:ANDROID_HOME = Join-Path $lab 'sdk-win'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ADB_PATH = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$env:ADB_SERVER_PORT = "$adbPort"
$env:ANDROID_ADB_SERVER_PORT = "$adbPort"
$env:ADB_SERVER_SOCKET = "tcp:127.0.0.1:$adbPort"
$env:APPIUM_URL = 'http://127.0.0.1:4725/wd/hub'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:DOUYIN_APP_PACKAGE = 'com.ss.android.ugc.aweme'
$env:DOUYIN_APP_ACTIVITY = '.main.MainActivity'
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' +
    [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + $env:Path

# Appium needs build tools for its helper APKs; these are separate from the emulator pin.
if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat'))) {
    throw 'Install Android SDK build-tools 35.0.0 into F:\D3SereinLab\sdk-win before starting Appium.'
}
$logs = Join-Path $PSScriptRoot 'target\setup'
$null = New-Item -ItemType Directory -Path $logs -Force
$appiumEntry = Join-Path $env:APPDATA 'npm\node_modules\appium\build\lib\main.js'
if (!(Test-Path -LiteralPath $appiumEntry)) { throw 'Install Appium 1.22.3 before running this script.' }
$listener = @(Get-NetTCPConnection -State Listen -LocalPort 4725 -ErrorAction SilentlyContinue)
if ($listener.Count) {
    $server = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
    if (!$server.CommandLine -or !$server.CommandLine.Contains($appiumEntry)) {
        throw 'Port 4725 belongs to another application.'
    }
} else {
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    $appium = Start-Process -FilePath $node -ArgumentList @(
        ('"' + $appiumEntry + '"'), '--address', '127.0.0.1', '--port', '4725', '--log-timestamp'
    ) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (
        Join-Path $logs 'd3-appium.out.log'
    ) -RedirectStandardError (Join-Path $logs 'd3-appium.err.log')
    $appium.Id | Set-Content -LiteralPath (Join-Path $logs 'd3-appium.pid')
}
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $status = Invoke-RestMethod "$env:APPIUM_URL/status" -TimeoutSec 2
        if ($status.value.build.version -ne '1.22.3') { throw 'Appium 1.22.3 is required.' }
        $ready = $true
        break
    } catch { Start-Sleep -Seconds 1 }
}
if (!$ready) { throw "D3 Appium did not become ready. See $logs\d3-appium.err.log" }

if ($Build) {
    Push-Location $PSScriptRoot
    try {
        & mvn.cmd -B -ntp package
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    } finally { Pop-Location }
}
Write-Host "D3 is ready: $serial, private ADB $adbPort, Appium 4725."
if ($Collect) {
    & "$PSScriptRoot\run-local.ps1" -Device $serial
} elseif ($StartBackend) {
    & "$PSScriptRoot\run-local.ps1"
} else {
    Write-Host 'Login page ready. Continuous video collection still requires stability acceptance; see docs/d3-runtime-observations-2026-11-19.md.'
}
