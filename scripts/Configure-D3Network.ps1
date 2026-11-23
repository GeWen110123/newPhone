#requires -Version 7.4
[CmdletBinding()]
param([switch]$Enable, [switch]$Disable, [switch]$HostOnly)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Enable -and $Disable) { throw 'Choose Enable or Disable.' }
$lab = $env:D3_LAB_ROOT
if (-not $lab) {
    foreach ($candidate in @('F:\D3SereinLab', 'D:\D3SereinLab')) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'instances\collector-new')) {
            $lab = $candidate
            break
        }
    }
}
if (-not $lab) { throw 'D3 lab root not found. Set D3_LAB_ROOT.' }
$statePath = Join-Path $lab 'instances\collector-new\evidence\network-direct-proxy.json'
$state = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json }
if (!$Enable -and !$Disable -and (!$state -or !$state.Enabled)) { return }
$adb = Join-Path $lab 'sdk-win\platform-tools\adb.exe'
$port = 18868
$proxy = "10.0.2.2:$port"
$endpoint = @('-H','127.0.0.1','-P','5068','-s','emulator-5668')

function Read-DeviceProxy {
    $value = @(& $adb @endpoint shell settings get global http_proxy 2>&1)
    if ($LASTEXITCODE) { throw 'The D3 device is unavailable; proxy settings were not changed.' }
    ($value -join "`n").Trim()
}
function Stop-OwnedProxy($Listener) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($Listener.OwningProcess)"
    $allowed = @(
        'D:\IdeaProjects\newPhone\scripts\d3-cdn-proxy.py',
        (Join-Path $lab 'deployment\one-click\d3-cdn-proxy.py')
    ) | Where-Object { $process.CommandLine -and $process.CommandLine.Contains($_) }
    if (!$allowed -or $process.Name -notmatch '^python(?:w)?\.exe$') {
        throw 'Port 18868 is not owned by the D3 helper; no process was stopped.'
    }
    Stop-Process -Id $process.ProcessId -ErrorAction Stop
}

if ($Disable) {
    if (!$state -or !$state.Enabled) { return }
    if ((Read-DeviceProxy) -cne $proxy) { throw 'Device proxy differs; refusing to replace another configuration.' }
    if ($state.OriginalProxy -cne 'null') { throw 'Unexpected saved original proxy.' }
    $null = & $adb @endpoint shell settings delete global http_proxy
    if ($LASTEXITCODE -or (Read-DeviceProxy) -cne 'null') { throw 'Could not restore the original device proxy.' }
    $state.Enabled = $false
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    $listener = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort $port -ErrorAction SilentlyContinue
    if ($listener) { Stop-OwnedProxy $listener }
    Write-Host 'D3 original network configuration restored.'
    return
}

$network = Get-NetIPConfiguration -InterfaceAlias 'WLAN' -ErrorAction Stop
$sourceIp = @($network.IPv4Address | Where-Object { $_.IPAddress -notlike '169.254.*' })[0].IPAddress
if (!$sourceIp -or !$network.IPv4DefaultGateway) { throw 'An active WLAN IPv4 connection is required.' }
$index = [int]$network.InterfaceIndex
$dnsServer = $network.IPv4DefaultGateway.NextHop
$healthUrl = "http://127.0.0.1:$port/__d3_health__"
$listener = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort $port -ErrorAction SilentlyContinue
$health = $null
if ($listener) {
    $health = Invoke-RestMethod $healthUrl -TimeoutSec 3
    if ($health.service -ne 'd3-cdn-proxy' -or $health.version -notin @(1,2,3)) { throw 'Port 18868 belongs to another service.' }
    if ($health.source_ip -ne $sourceIp -or $health.interface_index -ne $index -or $health.version -ne 3 -or
        $health.dns_server -ne $dnsServer) {
        Stop-OwnedProxy $listener
        $health = $null
    }
}
if (!$health) {
    $python = 'C:\Python313\python.exe'
    $helper = Join-Path $PSScriptRoot 'd3-cdn-proxy.py'
    if (!(Test-Path -LiteralPath $python) -or !(Test-Path -LiteralPath $helper)) { throw 'D3 network helper or Python 3.13 is missing.' }
    $logs = Join-Path $lab 'instances\collector-new\logs'
    $process = Start-Process -FilePath $python -ArgumentList @('-u', ('"' + $helper + '"'),
        '--interface-index', "$index", '--source-ip', $sourceIp, '--dns-server', $dnsServer, '--port', "$port") `
        -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs 'cdn-proxy.out.log') `
        -RedirectStandardError (Join-Path $logs 'cdn-proxy.err.log')
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try { $health = Invoke-RestMethod $healthUrl -TimeoutSec 1; break }
        catch { Start-Sleep -Milliseconds 250 }
    }
    if (!$health -or $health.service -ne 'd3-cdn-proxy' -or $health.version -ne 3 -or $health.source_ip -ne $sourceIp -or
        $health.interface_index -ne $index) { throw 'D3 network helper did not become ready.' }
}
if ($HostOnly) { return }

$before = Read-DeviceProxy
if ($before -cne 'null' -and $before -cne $proxy) { throw 'An existing device proxy differs; no settings were replaced.' }
if (!$state) {
    if ($before -cne 'null') { throw 'Existing proxy has no original configuration receipt.' }
    $state = [pscustomobject]@{Enabled=$false;OriginalProxy=$before;Proxy=$proxy;Serial='emulator-5668';AdbPort=5068}
}
if ($state.Proxy -cne $proxy -or $state.Serial -cne 'emulator-5668' -or $state.AdbPort -ne 5068) {
    throw 'Network receipt belongs to a different device.'
}
if ($before -cne $proxy) {
    $null = & $adb @endpoint shell settings put global http_proxy $proxy
    if ($LASTEXITCODE -or (Read-DeviceProxy) -cne $proxy) { throw 'D3 proxy setting did not verify.' }
}
$state.Enabled = $true
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
Write-Host 'D3 public HTTP/HTTPS uses WLAN; Windows application routes are unchanged. HTTPS is not decrypted.'
