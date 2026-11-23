#requires -Version 5.1
[CmdletBinding()]
param([switch]$NoApp, [switch]$RestartApp, [switch]$OnlyIfRunning, [switch]$NoWatch,
    [string]$DirectInterface = 'WLAN',
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$InstanceName = 'Tiramisu64')

$ErrorActionPreference = 'Stop'
$player = 'C:\Program Files\BlueStacks_nxt\HD-Player.exe'
$adb = 'C:\Android\sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) { $adb = 'F:\D3SereinLab\sdk-win\platform-tools\adb.exe' }
$python = 'C:\Python313\python.exe'
$helper = Join-Path $PSScriptRoot 'scripts\d3-cdn-proxy.py'
$logs = Join-Path $PSScriptRoot 'target\setup\bluestacks-direct'
$null = New-Item -ItemType Directory -Path $logs -Force
foreach ($file in @($player,$adb,$python,$helper)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required local program is missing: $file" }
}

$configPath = 'C:\ProgramData\BlueStacks_nxt\bluestacks.conf'
$config = [IO.File]::ReadAllText($configPath)
$instancePrefix = '(?m)^bst\.instance\.' + [regex]::Escape($InstanceName)
if ($config -notmatch ($instancePrefix+'\.adb_port="\d+"\r?$')) { throw "The selected BlueStacks instance is not installed: $InstanceName" }
function Get-ActiveInstancePort {
    $current = [IO.File]::ReadAllText($configPath)
    # adb_port is a shared starting preference (5555). status.adb_port records
    # the actual port assigned to this instance, e.g. Android 13 uses 5556.
    $portMatch = [regex]::Match($current, ($instancePrefix+'\.status\.adb_port="(\d+)"\r?$'))
    if (!$portMatch.Success) { return $null }
    $candidate = [int]$portMatch.Groups[1].Value
    if ($candidate -lt 1024 -or $candidate -gt 65535) { return $null }
    $bound = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort $candidate -ErrorAction SilentlyContinue
    if (!$bound) { return $null }
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($bound.OwningProcess)"
    if ($owner.Name -ine 'HD-Player.exe') { return $null }
    if ($owner.CommandLine) {
        $ownerInstance = [regex]::Match($owner.CommandLine, '(?:^|\s)--instance(?:=|\s+)"?([^\s"]+)')
        if ($ownerInstance.Success -and $ownerInstance.Groups[1].Value -cne $InstanceName) { return $null }
    }
    return $candidate
}
$devicePort = Get-ActiveInstancePort
if (!$devicePort) {
    if ($OnlyIfRunning) { return }
    # The player is the interactive window requested by the user; helper consoles stay hidden.
    $null = Start-Process -FilePath $player -ArgumentList @('--instance',$InstanceName) -WindowStyle Normal -PassThru
}
$ready = $false
for ($attempt = 0; $attempt -lt 90; $attempt++) {
    $devicePort = Get-ActiveInstancePort
    if (!$devicePort) { Start-Sleep -Seconds 1; continue }
    $serial = "127.0.0.1:$devicePort"
    $ep = @('-H','127.0.0.1','-P','5037','-s',$serial)
    $null = & $adb -H 127.0.0.1 -P 5037 connect $serial 2>&1
    $boot = @(& $adb @ep shell getprop sys.boot_completed 2>&1)
    if ($LASTEXITCODE -eq 0 -and ($boot -join '').Trim() -eq '1') { $ready=$true; break }
    Start-Sleep -Seconds 1
}
if (!$ready) { throw 'BlueStacks did not finish booting; no application data was cleared.' }
$sdk = (@(& $adb @ep shell getprop ro.build.version.sdk) -join '').Trim()
if ($sdk -notin @('28','30','33')) { throw "Unsupported BlueStacks Android SDK: $sdk. Douyin was not launched." }
if ($InstanceName -match '^Pie64(?:_|$)' -and $sdk -cne '28') { throw "The selected Android 9 instance reports unexpected SDK $sdk." }
if ($InstanceName -match '^Tiramisu64(?:_|$)' -and $sdk -cne '33') { throw "The selected Android 13 instance reports unexpected SDK $sdk." }
if ($InstanceName -match '^Rvc64(?:_|$)' -and $sdk -cne '30') { throw "The selected Android 11 instance reports unexpected SDK $sdk." }

$net = Get-NetIPConfiguration -InterfaceAlias $DirectInterface -ErrorAction Stop
$sourceIp = @($net.IPv4Address)[0].IPAddress
$dns = $net.IPv4DefaultGateway.NextHop
$index = [int]$net.InterfaceIndex
if (!$sourceIp -or !$dns) { throw 'The selected physical interface has no IPv4 address or gateway.' }
$url = 'http://127.0.0.1:18868/__d3_health__'
$listener = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort 18868 -ErrorAction SilentlyContinue
$health = $null
if ($listener) {
    $health = Invoke-RestMethod $url -TimeoutSec 3
    if ($health.service -ne 'd3-cdn-proxy' -or $health.version -ne 3) { throw 'The local forwarding port belongs to another service.' }
    if ($health.source_ip -ne $sourceIp -or $health.interface_index -ne $index -or $health.dns_server -ne $dns) {
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
        if (!$owner.CommandLine -or !$owner.CommandLine.Contains($helper) -or $owner.Name -ine 'python.exe') {
            throw 'The existing forwarding helper cannot be safely restarted.'
        }
        Stop-Process -Id $owner.ProcessId -ErrorAction Stop
        $health = $null
    }
}
if (!$health) {
    $null = Start-Process -FilePath $python -ArgumentList @('-u', ('"'+$helper+'"'),
        '--interface-index',"$index",'--source-ip',$sourceIp,'--dns-server',$dns,'--port','18868') `
        -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs 'proxy.out.log') `
        -RedirectStandardError (Join-Path $logs 'proxy.err.log')
    for ($attempt=0; $attempt -lt 20; $attempt++) {
        try { $health=Invoke-RestMethod $url -TimeoutSec 1; break } catch { Start-Sleep -Milliseconds 250 }
    }
    if (!$health -or $health.service -ne 'd3-cdn-proxy' -or $health.version -ne 3 -or
        $health.source_ip -ne $sourceIp -or $health.dns_server -ne $dns) { throw 'Local forwarding helper did not become ready.' }
}
$before = (@(& $adb @ep shell settings get global http_proxy) -join '').Trim()
if ($LASTEXITCODE -or $before -notin @('null','127.0.0.1:18868')) {
    throw 'An existing Android proxy differs; no settings were replaced.'
}
$reverse = @(& $adb @ep reverse --list)
if ($LASTEXITCODE) { throw 'Could not read the BlueStacks forwarding table.' }
if (!($reverse | Where-Object { $_ -match '^\S+\s+tcp:18868\s+tcp:18868\s*$' })) {
    $null = & $adb @ep reverse tcp:18868 tcp:18868
    if ($LASTEXITCODE) { throw 'Could not connect the BlueStacks loopback forwarding port.' }
}
if ($before -cne '127.0.0.1:18868') {
    $null = & $adb @ep shell settings put global http_proxy 127.0.0.1:18868
    if ($LASTEXITCODE) { throw 'Could not configure the BlueStacks HTTP/HTTPS route.' }
}
$after = (@(& $adb @ep shell settings get global http_proxy) -join '').Trim()
if ($after -cne '127.0.0.1:18868') { throw 'BlueStacks proxy setting did not verify.' }
$state = [ordered]@{CheckedAt=[DateTimeOffset]::Now.ToString('o');Engine=$InstanceName;AndroidSdk=$sdk;
    Serial=$serial;AdbPort=5037;HttpProxy=$after;DirectInterface=$DirectInterface;SourceIp=$sourceIp;
    Dns=$dns;WindowsVpnSettingsModified=$false;AuthenticationSubmitted=$false}
$state | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $logs 'network-state.json') -Encoding utf8
if (!$NoWatch) {
    $watcher = Join-Path $PSScriptRoot 'scripts\Watch-BlueStacksDouyinNetwork.ps1'
    if (!(Test-Path -LiteralPath $watcher)) { throw 'The BlueStacks network recovery helper is missing.' }
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $watchArgs = @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+$watcher+'"'),
        '-InstanceName',$InstanceName,'-DirectInterface',('"'+$DirectInterface+'"'))
    $null = Start-Process -FilePath $powershell -ArgumentList $watchArgs -WindowStyle Hidden -PassThru
}
if (!$NoApp) {
    if ($RestartApp) { $null = & $adb @ep shell am force-stop com.ss.android.ugc.aweme }
    $installed = @(& $adb @ep shell pm list packages com.ss.android.ugc.aweme)
    if ($LASTEXITCODE -or !($installed | Where-Object { $_.Trim() -ceq 'package:com.ss.android.ugc.aweme' })) {
        throw 'Douyin is not installed in the selected BlueStacks instance.'
    }
    # Use the same vendor launch path as the BlueStacks desktop app shortcut.
    $launch = Start-Process -FilePath $player -ArgumentList @('--instance',$InstanceName,
        '--cmd','launchApp','--package','com.ss.android.ugc.aweme','--source','desktop_shortcut') `
        -WindowStyle Hidden -PassThru
    if ($launch.HasExited -and $launch.ExitCode -ne 0) { throw 'BlueStacks rejected the Douyin launch.' }
}
Write-Host 'BlueStacks HTTP/HTTPS uses the physical WLAN connection through local forwarding. Windows VPN settings are unchanged.'
