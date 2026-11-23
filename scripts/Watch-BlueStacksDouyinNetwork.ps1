#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$InstanceName = 'Pie64',
    [string]$DirectInterface = 'WLAN',
    [ValidateRange(1,360)][int]$IdleMinutes = 10
)

# This helper repairs only this instance's loopback forwarding. It never launches
# the player or Douyin, clears data, submits login requests, or changes host VPN.
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$start = Join-Path $project 'Start-BlueStacksDouyin.ps1'
$logs = Join-Path $project 'target\setup\bluestacks-direct'
$null = New-Item -ItemType Directory -Path $logs -Force
$created = $false
$mutex = New-Object System.Threading.Mutex($true, ('Local\NewPhone_BlueStacksNetwork_'+$InstanceName), [ref]$created)
if (!$created) { $mutex.Dispose(); return }
$watchStatePath = Join-Path $logs ('watcher-'+$InstanceName+'.json')
$lastActive = [DateTimeOffset]::Now
$lastError = $null
try {
    while ($true) {
        $active = $false
        try {
            $config = [IO.File]::ReadAllText('C:\ProgramData\BlueStacks_nxt\bluestacks.conf')
            $match = [regex]::Match($config, ('(?m)^bst\.instance\.'+[regex]::Escape($InstanceName)+'\.status\.adb_port="(\d+)"\r?$'))
            if (!$match.Success) { throw 'The selected instance has no active ADB endpoint.' }
            $listener = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort ([int]$match.Groups[1].Value) -ErrorAction SilentlyContinue
            if ($listener) {
                $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
                if ($owner.Name -ine 'HD-Player.exe') { throw 'The instance port belongs to another application.' }
                & $start -InstanceName $InstanceName -DirectInterface $DirectInterface -NoApp -OnlyIfRunning -NoWatch *> $null
                $active = $true
                $lastActive = [DateTimeOffset]::Now
            }
            $lastError = $null
        } catch {
            $lastError = $_.Exception.Message
        }
        [ordered]@{ CheckedAt=[DateTimeOffset]::Now.ToString('o'); ProcessId=$PID;
            Instance=$InstanceName; Active=$active; LastError=$lastError;
            WindowsVpnSettingsModified=$false; AppLaunches=0 } |
            ConvertTo-Json | Set-Content -LiteralPath $watchStatePath -Encoding utf8
        if (!$active -and ([DateTimeOffset]::Now-$lastActive).TotalMinutes -ge $IdleMinutes) { break }
        Start-Sleep -Seconds 10
    }
} finally {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
