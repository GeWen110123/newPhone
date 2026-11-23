#requires -Version 7.4
[CmdletBinding()]
param([string]$DeploymentRoot = '')

$ErrorActionPreference = 'Stop'
if (-not $DeploymentRoot) {
    foreach ($candidate in @('F:\D3SereinLab\deployment\one-click', 'D:\D3SereinLab\deployment\one-click')) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'Deploy-D3CollectorVm.ps1')) {
            $DeploymentRoot = $candidate
            break
        }
    }
}
if (-not $DeploymentRoot) { throw 'D3 deployment directory not found. Pass -DeploymentRoot.' }
$root = [IO.Path]::GetFullPath($DeploymentRoot).TrimEnd('\')
if ($root -notmatch '(?i)^[A-Z]:\\D3SereinLab\\deployment\\one-click$') {
    throw "Unexpected D3 deployment directory: $root"
}
$launcher = Join-Path $root 'Deploy-D3CollectorVm.ps1'
$original = "$launcher.original"
$approvedSourceHash = '21d02fb15e603f1bad0ac7b8910ed04b915b69bc84d130362d5fd072f765b956'
if (!(Test-Path -LiteralPath $original)) {
    if ((Get-FileHash -LiteralPath $launcher).Hash -ine $approvedSourceHash) { throw 'Unrecognized D3 launcher; refusing to create an original backup.' }
    Copy-Item -LiteralPath $launcher -Destination $original
}
if ((Get-FileHash -LiteralPath $original).Hash -ine $approvedSourceHash) { throw 'D3 launcher source does not match the supplied release.' }
$source = [IO.File]::ReadAllText($original)
if (!$source.Contains('    [switch]$NoPreview,')) { throw 'D3 parameter declaration differs.' }
$source = $source.Replace('    [switch]$NoPreview,', '    [switch]$NoPreview,' + [Environment]::NewLine + '    [switch]$LoginOnly,')

$scriptsAnchor = '    $scripts = Join-Path $working ''scripts\vm-template'''
if (!$source.Contains($scriptsAnchor)) { throw 'D3 extraction entry differs.' }
$runtimeOverrides = @'
    if ($InstanceName -eq 'collector-new') {
        & (Join-Path $PSScriptRoot 'Configure-D3Network.ps1') -HostOnly | Out-Null
    }
    $guardFile = Join-Path $working 'scripts\vm-template\runtime\instance-env.ps1'
    $guardOriginal = [IO.File]::ReadAllText($guardFile)
    $guardBefore = 'timeout expired while waiting for device(?:\s+restarting adbd as root)?'
    $guardAfter = '(?:timeout expired while waiting for device(?:\s+restarting adbd as root)?|adb: unable to connect for root: closed)'
    if (-not $guardOriginal.Contains($guardBefore)) { throw 'Unexpected ADB restart guard source.' }
    [IO.File]::WriteAllText($guardFile, $guardOriginal.Replace($guardBefore, $guardAfter))
    if ($LoginOnly) {
        $openFile = Join-Path $working 'scripts\vm-template\Open-D3Douyin.ps1'
        $openSource = [IO.File]::ReadAllText($openFile)
        $mainActivity = 'com.ss.android.ugc.aweme/com.ss.android.ugc.aweme.splash.SplashActivity'
        $loginActivity = 'com.ss.android.ugc.aweme/com.ss.android.ugc.aweme.account.business.login.DYLoginActivity -f 0x10008000'
        if (-not $openSource.Contains($mainActivity)) { throw 'Unexpected Douyin launch entry.' }
        [IO.File]::WriteAllText($openFile, $openSource.Replace($mainActivity, $loginActivity))
    }

'@
$source = $source.Replace($scriptsAnchor, $runtimeOverrides + [Environment]::NewLine + $scriptsAnchor)

$llkdHelper = Join-Path $PSScriptRoot 'Configure-D3Llkd.ps1'
if (!(Test-Path -LiteralPath $llkdHelper -PathType Leaf)) { throw 'D3 llkd configuration helper is missing.' }
Copy-Item -LiteralPath $llkdHelper -Destination (Join-Path $root 'Configure-D3Llkd.ps1') -Force
foreach ($helperName in @('Configure-D3Network.ps1', 'd3-cdn-proxy.py')) {
    $helper = Join-Path $PSScriptRoot $helperName
    if (!(Test-Path -LiteralPath $helper -PathType Leaf)) { throw "D3 network helper is missing: $helperName" }
    Copy-Item -LiteralPath $helper -Destination (Join-Path $root $helperName) -Force
}
$launchAnchor = '    $launchPath = Get-SereinPath (Join-Path $instance.Evidence "one-click-$bootId.json") $instance.Evidence'
if (!$source.Contains($launchAnchor)) { throw 'D3 verified launch entry differs.' }
$llkdConfig = @'
    $llkd = & (Join-Path $PSScriptRoot 'Configure-D3Llkd.ps1') -AdbPath $tools.AdbPath -AdbPort $AdbServerPort -Serial $start.Serial -EvidenceRoot $instance.Evidence
    if ($llkd.BootId -ne $bootId) { throw 'llkd configuration belongs to a different Android boot.' }
    if ($InstanceName -eq 'collector-new') {
        & (Join-Path $PSScriptRoot 'Configure-D3Network.ps1') | Out-Null
    }

'@
$source = $source.Replace($launchAnchor, $llkdConfig + [Environment]::NewLine + $launchAnchor)

$receiptAnchor = '    $receipt = [pscustomobject]@{'
if (!$source.Contains($receiptAnchor)) { throw 'D3 launch receipt declaration differs.' }
$focusCheck = @'
    $activeStartup = Read-SereinStartup $instance (Get-SereinOwner $instance) $start.StartupManifest
    $foregroundCommand = "zygote=`$(getprop init.svc_debug_pid.zygote); ps -A -o PID,PPID,NAME | awk -v zygote=`"`$zygote`" '`$2 == zygote && `$3 == `"com.ss.android.ugc.aweme`" {print `$1}'; dumpsys window displays | grep mCurrentFocus"
    $foreground = Invoke-SereinAdb $instance $activeStartup @('shell', $foregroundCommand)
    $needsLaunch = -not (Test-D3DouyinForeground $foreground)
    if ($LoginOnly -and $foreground -notmatch 'mCurrentFocus=.*DYLoginActivity') { $needsLaunch = $true }
    if ($needsLaunch) {
        $launch = & "$scripts\Open-D3Douyin.ps1" -InstanceName $InstanceName -StartupManifest $start.StartupManifest
        if ($launch.AppLaunchVerified -ne $true -or $launch.BootId -ne $bootId) { throw 'Douyin did not reopen on the verified Android boot.' }
    }

'@
$source = $source.Replace($receiptAnchor, $focusCheck + [Environment]::NewLine + $receiptAnchor)
$source = $source.Replace('        CalibrationPath = $start.CalibrationPath',
    '        CalibrationPath = $start.CalibrationPath' + [Environment]::NewLine + '        LoginOnly = [bool]$LoginOnly')

$previewAnchor = '    if (-not $NoPreview) {'
if (!$source.Contains($previewAnchor)) { throw 'D3 preview entry differs.' }
$previewCheck = @'
    $previewRunning = @(Get-CimInstance Win32_Process -Filter "Name='scrcpy.exe'" | Where-Object {
        $_.ExecutablePath -eq $tools.ScrcpyPath -and $_.CommandLine -like "*--serial=$($start.Serial)*"
    }).Count -gt 0
    if (-not $NoPreview -and -not $previewRunning) {
'@
$source = $source.Replace($previewAnchor, $previewCheck.TrimEnd())
$tokens = $null
$errors = $null
$null = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "Generated D3 launcher has syntax errors: $($errors -join '; ')" }
if ([IO.File]::ReadAllText($launcher) -cne $source) { [IO.File]::WriteAllText($launcher, $source) }
[pscustomobject]@{Launcher=$launcher;OriginalSHA256=$approvedSourceHash;SHA256=(Get-FileHash -LiteralPath $launcher).Hash;LoginOnlySupported=$true}
