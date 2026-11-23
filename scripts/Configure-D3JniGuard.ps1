#requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$StartupManifest,
    [string]$AdbPath = 'F:\D3SereinLab\sdk-win\platform-tools\adb.exe',
    [ValidateSet(5068)][int]$AdbPort = 5068,
    [ValidateSet('emulator-5668')][string]$Serial = 'emulator-5668',
    [string]$EvidenceRoot = 'F:\D3SereinLab\instances\collector-new\evidence'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. 'F:\D3SereinLab\deployment\template\scripts\vm-template\template-env.ps1'
$binding = Get-D3Binding 'collector-new'
$identity = Get-D3Arguments $binding
$instance = Get-SereinInstance @identity
$owner = Get-SereinOwner $instance
$startup = Read-SereinStartup $instance $owner $StartupManifest
if ($AdbPath -ine $instance.Adb -or $AdbPort -ne $instance.AdbServerPort -or
    $Serial -cne $instance.Serial -or
    [IO.Path]::GetFullPath($EvidenceRoot).TrimEnd('\') -ine $instance.Evidence) {
    throw 'ADB identity or evidence directory differs from the registered D3 instance.'
}
Assert-SereinEndpoints $instance $startup -VerifyImages
Assert-SereinAvd $instance $owner
$template = Read-D3Template $binding.TemplatePath
$operation = Enter-SereinOperation $instance
$record = $null
$recordPath = $null

function Invoke-D3GuardShell([string]$Command) {
    (Invoke-SereinAdb $instance $startup @('shell', $Command)).Trim()
}
function Save-D3GuardReceipt([string]$Directory, $Value) {
    # Template evidence is immutable: write a new stage record each time.
    $stagePath = Join-Path $Directory ("receipt-{0}.json" -f (New-SereinStamp))
    $Value.Receipt = $stagePath
    Write-SereinJson $stagePath $Value
}
function Read-D3GuardFile {
    $raw = Invoke-D3GuardShell @'
set -eu
p=/data/local/tmp/jni_guard_config
test ! -L "$p"
if test -e "$p"; then
    test -f "$p"
    echo PRESENT
    stat -c 'STAT %u|%g|%a|%s' "$p"
    sha256sum "$p"
    ls -Zd "$p"
else
    echo ABSENT
fi
'@
    if ($raw -ceq 'ABSENT') { return [pscustomobject]@{Exists=$false} }
    $stat = [regex]::Match($raw, '(?m)^STAT (\d+)\|(\d+)\|([0-7]+)\|(\d+)\r?$')
    $hash = [regex]::Match($raw, '(?m)^([a-f0-9]{64})\s+/data/local/tmp/jni_guard_config\r?$')
    $context = [regex]::Match($raw, '(?m)^(u:object_r:[^\s]+)\s+/data/local/tmp/jni_guard_config\r?$')
    if (!$stat.Success -or !$hash.Success -or !$context.Success) { throw 'JNI guard file metadata is incomplete.' }
    [pscustomobject]@{Exists=$true;Uid=[int]$stat.Groups[1].Value;Gid=[int]$stat.Groups[2].Value;
        Mode=$stat.Groups[3].Value;Size=[long]$stat.Groups[4].Value;SHA256=$hash.Groups[1].Value;
        SELinuxContext=$context.Groups[1].Value}
}

try {
    if ((Invoke-D3GuardShell 'id -u') -cne '0' -or
        (Invoke-D3GuardShell 'getenforce') -cne 'Enforcing' -or
        (Invoke-D3GuardShell 'getprop ro.build.version.sdk') -cne '30') {
        throw 'Root ADB, Android 11, and SELinux Enforcing are required.'
    }
    $runtime = Get-D3GuestRuntimeState $instance $startup $template
    if ($runtime.State -ne 'Healthy') { throw 'The verified D3 compatibility layer is not healthy.' }
    $approvedApk = 'dadb50924a2c2969601564c7294f6c3c2eb3f40b7f59c6a66bb9049102e4527d'
    $approvedJato = '30fbe34b889f67bb6d921ee5b84843285deef2cc7c871631be85abe39d1429e8'
    if ($template.app.sha256 -cne $approvedApk) { throw 'This guard applies only to the supplied original Douyin 40.9.0.' }
    $query = Invoke-D3GuardShell 'pm path com.ss.android.ugc.aweme'
    if ($query -cnotmatch '\Apackage:(/data/app/[A-Za-z0-9_=.~/-]+/base\.apk)\z' -or $matches[1] -match '/\.{1,2}/') {
        throw 'Expected one approved package installation.'
    }
    $guestApk = $matches[1]
    $guestJato = ($guestApk -replace '/base\.apk$', '') + '/lib/arm64/libjato.so'
    foreach ($check in @(@($guestApk,$approvedApk), @($guestJato,$approvedJato))) {
        $path = $check[0]
        $actual = Invoke-D3GuardShell "test -f '$path' && test ! -L '$path' && sha256sum '$path'"
        if ($actual -cnotmatch ('\A' + $check[1] + '\s+' + [regex]::Escape($path) + '\z')) {
            throw 'Installed APK or libjato differs from the supplied release.'
        }
    }
    $appFiles = '/data/user/0/com.ss.android.ugc.aweme/files'
    $appUid = Invoke-D3GuardShell "test ! -L '$appFiles' && stat -c %u '$appFiles'"
    if ($appUid -cnotmatch '^\d+$' -or [int]$appUid -lt 10000 -or [int]$appUid -gt 99999) {
        throw 'Expected an ordinary application UID.'
    }
    $details = Invoke-D3GuardShell 'dumpsys package com.ss.android.ugc.aweme'
    if ($details -notmatch "(?m)^\s+userId=$appUid\s*$" -or $details -notmatch '(?m)^\s+primaryCpuAbi=arm64-v8a\s*$') {
        throw 'Application ABI or data ownership differs from the approved package.'
    }
    $label = Invoke-D3GuardShell "ls -Zd '$appFiles'"
    $context = [regex]::Match($label, '\A(u:object_r:app_data_file:s0:c\d+(?:,c\d+)*)\s+').Groups[1].Value
    if (!$context) { throw 'Could not obtain the actual application data SELinux category.' }

    # Original APK-supported local blacklist; exact JNI symbol, one ASCII line.
    # Does not disable Jato globally or patch the APK/translation libraries.
    $method = 'Java_com_bytedance_common_jato_util_JNIHook_nativeInit'
    $target = '/data/local/tmp/jni_guard_config'
    $payload = [Text.Encoding]::ASCII.GetBytes($method + "`n")
    $expectedHash = '5a9949bbcd7a64b1baa2244a83dcb0e854c16746baecbec4731b07c9cbc91460'
    $evidence = Get-SereinPath (Join-Path $EvidenceRoot ("jni-guard-{0}" -f (New-SereinStamp))) $instance.Evidence
    $null = New-Item -ItemType Directory -Path $evidence
    $recordPath = Join-Path $evidence 'receipt.json'
    $before = Read-D3GuardFile
    $record = [ordered]@{CheckedAt=[DateTimeOffset]::Now.ToString('o');Status='Started';StartupManifest=$StartupManifest;
        BootId=$runtime.BootId;Serial=$Serial;AdbPort=$AdbPort;Target=$target;Method=$method;
        ApprovedApkSHA256=$approvedApk;ApprovedJatoSHA256=$approvedJato;ExpectedConfigSHA256=$expectedHash;
        Before=$before;After=$null;Changed=$false;BackupPath=$null;TemporaryPath=$null;RuntimeRecognitionVerified=$false;
        RestartedApplication=$false;SELinux='Enforcing';Receipt=$recordPath;
        Rollback=[ordered]@{OriginalFileExisted=[bool]$before.Exists;RemoveOnlyNewFile=$target;
            RequiredSHA256=$expectedHash;RequiredUid=[int]$appUid;RequiredSELinuxContext=$context;
            RequiresVerifiedInstance=$true;OriginalBackup=$null}}
    Save-D3GuardReceipt $evidence $record
    if ($before.Exists) {
        # Refuse an unknown file entirely; do not append, normalize, relabel, or replace it.
        if ($before.SHA256 -cne $expectedHash -or $before.Size -ne $payload.Length -or
            $before.Uid -ne [int]$appUid -or $before.Gid -ne [int]$appUid -or
            $before.Mode -cne '600' -or $before.SELinuxContext -cne $context) {
            throw 'Existing JNI guard config differs. It was not changed; inspect the recorded metadata.'
        }
        $backup = Join-Path $evidence 'original-config.bin'
        $null = Invoke-SereinAdb $instance $startup @('pull', $target, $backup)
        if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ine $before.SHA256) { throw 'Byte-preserving config backup hash differs.' }
        $record.BackupPath = $backup
        $record.Rollback.OriginalBackup = $backup
        $record.Rollback.RemoveOnlyNewFile = $null
    } else {
        $desired = Join-Path $evidence 'desired-config.bin'
        [IO.File]::WriteAllBytes($desired, $payload)
        if ((Get-FileHash -LiteralPath $desired -Algorithm SHA256).Hash -ine $expectedHash) { throw 'Host config payload hash differs.' }
        $temporary = '/data/local/tmp/d3-jni-guard-' + [guid]::NewGuid().ToString('N') + '.tmp'
        $record.TemporaryPath = $temporary
        Save-D3GuardReceipt $evidence $record
        try {
            $null = Invoke-SereinAdb $instance $startup @('push', $desired, $temporary)
            $create = @'
set -eu
test "$(getenforce)" = Enforcing
test ! -e '__TARGET__'
test ! -L '__TARGET__'
test -f '__TEMP__'
test ! -L '__TEMP__'
test "$(sha256sum '__TEMP__' | cut -d' ' -f1)" = __HASH__
chown __UID__:__UID__ '__TEMP__'
chmod 600 '__TEMP__'
chcon '__CONTEXT__' '__TEMP__'
# A hard link creates the absent destination without overwriting an existing file.
ln '__TEMP__' '__TARGET__'
test -f '__TARGET__'
test ! -L '__TARGET__'
test "$(sha256sum '__TARGET__' | cut -d' ' -f1)" = __HASH__
test "$(getenforce)" = Enforcing
'@
            $create = $create.Replace('__TARGET__',$target).Replace('__TEMP__',$temporary).
                Replace('__HASH__',$expectedHash).Replace('__UID__',$appUid).Replace('__CONTEXT__',$context)
            $null = Invoke-D3GuardShell $create
            $record.Changed = $true
        } finally {
            # Only remove this invocation's own temporary file with the approved contents.
            $null = Invoke-D3GuardShell "if test -f '$temporary' && test ! -L '$temporary' && test `"`$(sha256sum '$temporary' | cut -d' ' -f1)`" = '$expectedHash'; then rm -- '$temporary'; fi"
        }
    }
    $after = Read-D3GuardFile
    $record.After = $after
    if (!$after.Exists -or $after.SHA256 -cne $expectedHash -or $after.Uid -ne [int]$appUid -or
        $after.Gid -ne [int]$appUid -or $after.Mode -cne '600' -or $after.SELinuxContext -cne $context -or
        (Invoke-D3GuardShell 'getenforce') -cne 'Enforcing') { throw 'JNI guard file verification failed.' }
    $record.Status = if ($record.Changed) { 'ConfiguredPendingApplicationRecognition' } else { 'AlreadyConfiguredPendingApplicationRecognition' }
    Save-D3GuardReceipt $evidence $record
    [pscustomobject]$record
} catch {
    $failure = $_
    if ($record) {
        try {
            $record.After = Read-D3GuardFile
            if (!$record.Before.Exists -and $record.After.Exists -and $record.After.SHA256 -ceq $expectedHash) {
                $record.Changed = $true
            }
        } catch { }
        $record.Status='Failed'
        $record['Error']=$failure.Exception.Message
        Save-D3GuardReceipt $evidence $record
    }
    throw $failure
} finally { $operation.Dispose() }
