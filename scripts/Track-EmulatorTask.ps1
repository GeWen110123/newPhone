#requires -Version 5.1
param(
    [int]$TaskId = 456,
    [switch]$Loop,
    [int]$IntervalSeconds = 30,
    [string]$MysqlContainer = 'mysql8',
    [string]$RedisContainer = 'redis'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$mysqlPassword = $env:MYSQL_PASSWORD
$redisPassword = $env:REDIS_PASSWORD
if (-not $mysqlPassword) { throw 'Set MYSQL_PASSWORD before running this script.' }

function Get-TaskRow {
    $sql = "SELECT id,dev_id,douyin_id,status,run_num,task_name,update_time FROM video_tags WHERE id=$TaskId"
    docker exec -e "MYSQL_PWD=$mysqlPassword" $MysqlContainer mysql --default-character-set=utf8mb4 -uroot phone -e $sql
}

function Get-RecentLogs {
    $sql = "SELECT id,create_time,type,dev_id,LEFT(runing_detail,120) FROM run_log WHERE douyinId=(SELECT douyin_id FROM video_tags WHERE id=$TaskId) ORDER BY id DESC LIMIT 5"
    docker exec -e "MYSQL_PWD=$mysqlPassword" $MysqlContainer mysql --default-character-set=utf8mb4 -uroot phone -e $sql
}

function Get-DeviceLock {
    if (-not $redisPassword) { return '(REDIS_PASSWORD not set, skipped)' }
    docker exec $RedisContainer redis-cli -a $redisPassword --no-auth-warning --scan --pattern 'douyin:device:lock:*' 2>$null
}

function Get-PortState {
    foreach ($port in @(5068, 5668, 4725, 8099)) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
        if ($listener) { "  $port LISTEN pid=$($listener[0].OwningProcess)" }
        else { "  $port not listening" }
    }
}

do {
    Write-Host "===== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') task $TaskId =====" -ForegroundColor Cyan
    Write-Host '--- video_tags ---'
    Get-TaskRow
    Write-Host '--- recent run logs ---'
    Get-RecentLogs
    Write-Host '--- device locks ---'
    Get-DeviceLock
    Write-Host '--- key ports ---'
    Get-PortState
    if (-not $Loop) { break }
    Start-Sleep -Seconds $IntervalSeconds
} while ($true)
