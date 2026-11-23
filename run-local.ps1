param(
    [switch]$Build,
    [string]$Device,
    [string]$JarPath = 'target\phone.jar',
    [string]$AdbPath = $env:ADB_PATH,
    [ValidateRange(1, 65535)][int]$AdbServerPort = $(if ($env:ADB_SERVER_PORT) { [int]$env:ADB_SERVER_PORT } else { 5037 }),
    [string]$AppiumUrl = $(if ($env:APPIUM_URL) { $env:APPIUM_URL } else { 'http://127.0.0.1:4723/wd/hub' })
)

$ErrorActionPreference = 'Stop'
$jdkHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
if (!$jdkHome) { $jdkHome = $env:JAVA_HOME }
$java = Join-Path $jdkHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'Set JAVA_HOME to an installed JDK 21.' }
$version = (& $java -version 2>&1 | Out-String)
if ($version -notmatch 'version "21\.') { throw "JDK 21 is required: $java" }
$env:JAVA_HOME = $jdkHome
$paths = @((Join-Path $jdkHome 'bin'))
if ($env:ANDROID_HOME) { $paths += Join-Path $env:ANDROID_HOME 'platform-tools' }
$env:Path = ($paths -join ';') + ';' + $env:Path

Push-Location $PSScriptRoot
try {
    if (Get-NetTCPConnection -State Listen -LocalPort 8099 -ErrorAction SilentlyContinue) {
        throw 'Port 8099 is already in use. Stop the existing backend before rebuilding or starting.'
    }
    if ($Build) {
        if ([IO.Path]::GetFileName($JarPath) -ne 'phone.jar') { throw 'The build output must be named phone.jar.' }
        $buildDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($JarPath))
        & mvn.cmd -B -ntp "-Dphone.build.directory=$buildDirectory" package
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    }
    if (!(Test-Path -LiteralPath $JarPath)) { throw "Backend JAR is missing: $JarPath" }
    $env:ADB_SERVER_PORT = "$AdbServerPort"
    $env:ANDROID_ADB_SERVER_PORT = "$AdbServerPort"
    $env:ADB_SERVER_SOCKET = "tcp:127.0.0.1:$AdbServerPort"
    $env:APPIUM_URL = $AppiumUrl
    if (!$AdbPath -and $env:ANDROID_HOME) { $AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
    if (!$AdbPath -and $Device) { $AdbPath = (Get-Command adb.exe -ErrorAction Stop).Source }
    if ($AdbPath) { $env:ADB_PATH = $AdbPath }
    if ($Device) {
        $Device = $Device.Trim()
        $state = @(& $AdbPath -P $AdbServerPort -s $Device get-state 2>&1)
        if ($LASTEXITCODE -ne 0 -or !($state | Where-Object { "$_".Trim() -eq 'device' })) {
            throw "The task device is offline or unauthorized: $Device"
        }
        $null = Invoke-RestMethod ($AppiumUrl.TrimEnd('/') + '/status') -TimeoutSec 5
        Write-Host "Task device: $Device; ADB: $AdbServerPort; Appium: $AppiumUrl"
    }
    # A specified device enables only that device's task queue.
    $env:DOUYIN_SCHEDULER_ENABLED = if ($Device) { 'true' } else { 'false' }
    $env:DOUYIN_SCHEDULER_DEVICE_ID = $Device
    $env:DOUYIN_MAINTENANCE_ENABLED = 'false'
    & $java '-Duser.timezone=Asia/Shanghai' -jar $JarPath --server.port=8099
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
