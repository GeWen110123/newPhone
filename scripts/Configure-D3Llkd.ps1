#requires -Version 7.4
[CmdletBinding()]
param(
    [string]$AdbPath = 'F:\D3SereinLab\sdk-win\platform-tools\adb.exe',
    [int]$AdbPort = 5068,
    [string]$Serial = 'emulator-5668',
    [string]$EvidenceRoot = 'F:\D3SereinLab\instances\collector-new\evidence'
)

$ErrorActionPreference = 'Stop'
if ($AdbPort -ne 5068 -or $Serial -cne 'emulator-5668') { throw 'This workaround is limited to the verified local D3 instance.' }
function Invoke-D3Shell([string]$Command) {
    $lines = @(& $AdbPath -H 127.0.0.1 -P $AdbPort -s $Serial shell $Command 2>&1)
    if ($LASTEXITCODE) { throw "D3 shell command failed: $($lines -join ' ')" }
    ($lines -join "`n").Trim()
}
if ((Invoke-D3Shell 'id -u') -cne '0') { throw 'The verified D3 root ADB transport is required.' }
if ((Invoke-D3Shell 'getenforce') -cne 'Enforcing') { throw 'SELinux must remain Enforcing.' }
if ((Invoke-D3Shell 'getprop ro.build.version.sdk') -cne '30') { throw 'This configuration was verified for Android 11 only.' }

# Android 11 llkd supports a leading comma to retain all default ignore entries.
# Match only this parent AND its zombie logcat child; do not exempt the app itself.
# https://android.googlesource.com/platform/system/core/+/refs/heads/android11-release/llkd/README.md
$property = 'ro.llk.ignorelist.parent'
$expected = ',com.ss.android.ugc.aweme&[logcat]'
$before = Invoke-D3Shell "getprop $property"
if ($before -and $before -cne $expected) { throw 'An existing llkd parent configuration differs; refusing to replace it.' }
$oldPid = Invoke-D3Shell 'pidof llkd'
if ($oldPid -notmatch '^\d+$') { throw 'Expected one running llkd process.' }
$restarted = $false
if (!$before) {
    Invoke-D3Shell "setprop $property '$expected'" | Out-Null
    if ((Invoke-D3Shell "getprop $property") -cne $expected) { throw 'The llkd property was not accepted.' }
    Invoke-D3Shell 'setprop llk.enable true' | Out-Null
    Invoke-D3Shell 'setprop ctl.restart llkd-1' | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 500
        $newPid = Invoke-D3Shell 'pidof llkd'
    } while (($newPid -notmatch '^\d+$' -or $newPid -eq $oldPid) -and [DateTime]::UtcNow -lt $deadline)
    if ($newPid -notmatch '^\d+$' -or $newPid -eq $oldPid) { throw 'llkd did not restart with its supported configuration.' }
    $restarted = $true
}
$currentPid = Invoke-D3Shell 'pidof llkd'
if ((Invoke-D3Shell 'getprop init.svc.llkd-1') -cne 'running' -or
    (Invoke-D3Shell "readlink /proc/$currentPid/exe") -cne '/system/bin/llkd' -or
    (Invoke-D3Shell 'getenforce') -cne 'Enforcing') { throw 'llkd/SELinux verification failed.' }
$bootId = Invoke-D3Shell 'cat /proc/sys/kernel/random/boot_id'
$startTicks = ((Invoke-D3Shell "cat /proc/$currentPid/stat") -split '\s+')[21]
$receiptPath = Join-Path $EvidenceRoot 'llkd-parent-workaround.json'
$prior = if (Test-Path -LiteralPath $receiptPath) { Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json }
$verifiedPreviously = $prior -and $prior.BootId -eq $bootId -and $prior.CurrentPid -eq $currentPid -and
    $prior.StartTicks -eq $startTicks -and $prior.Value -ceq $expected -and $prior.EffectiveConfigurationVerified
if (!$verifiedPreviously) {
    $effective = (Invoke-D3Shell 'logcat -d -v threadtime -s livelock') -split "`n" | Where-Object {
        $_ -match "\s$currentPid\s+$currentPid\s+I\s+livelock: ro\.llk\.ignorelist\.parent=" -and
        $_.Contains('com.ss.android.ugc.aweme&[logcat]') -and $_.Contains('adbd&[setsid]')
    }
    if (!$effective) { throw 'No startup evidence that the current llkd loaded the narrow exception and defaults.' }
}
$receipt = [pscustomobject]@{
    CheckedAt = [DateTimeOffset]::Now.ToString('o')
    BootId = $bootId
    Property = $property
    Value = $expected
    PreviousPid = $oldPid
    CurrentPid = $currentPid
    StartTicks = $startTicks
    Restarted = $restarted
    EffectiveConfigurationVerified = $true
    Service = 'running'
    SELinux = 'Enforcing'
}
if (!(Test-Path -LiteralPath $EvidenceRoot -PathType Container)) { throw 'Verified instance evidence directory is missing.' }
$receipt | ConvertTo-Json | Set-Content -LiteralPath $receiptPath -Encoding utf8
if ($restarted -or !$verifiedPreviously) {
    $receipt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceRoot "llkd-parent-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff').json") -Encoding utf8
}
$receipt
