package ru.cashprediction.core.update.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** Автономный помощник: установка и откат не требуют работоспособности runtime приложения. */
public final class PowerShellHelper {
    private PowerShellHelper() { }

    /** Публикует помощник в единственном разрешённом служебном каталоге. */
    public static void publish(Path updates) throws IOException {
        InstallFiles.write(updates.resolve("apply-update.ps1"), script());
    }

    /** Запускает PowerShell скрыто; пути передаются литералами через Unicode encoded command. */
    public static void start(Path root, Path updates) throws IOException {
        Path script = updates.resolve("apply-update.ps1");
        InstallFiles.guard(script);
        String command = "& '" + script.toString().replace("'", "''") + "' -InstallationRoot '"
                + root.toString().replace("'", "''") + "'";
        String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) throw new IOException("WINDOWS_SYSTEM_ROOT");
        new ProcessBuilder(Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    /**
     * Возвращает самодостаточный скрипт; FaultAt используется только явными тестовыми fixtures.
     * Lease удерживает барьер при совпадении PID и birth либо непроверяемой личности.
     * Подтверждённый другой birth означает устаревшую lease: удаляется только её guarded файл,
     * не чужой процесс. Только точный отказ поиска PID подтверждает отсутствие процесса;
     * ошибки доступа и неполные сведения не разрешают установку. Отдельный census сохраняет
     * защиту настоящих процессов текущего корня независимо от содержимого stale lease.
     */
    public static String script() {
        return """
param([Parameter(Mandatory=$true)][string]$InstallationRoot, [int]$FaultAt=0, [switch]$Crash, [int]$JournalFailAt=0, [switch]$Diagnostics,
      [ValidateSet('','PREPARED','WAITING','BOOTSTRAPPING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK')][string]$FaultPhase='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
$utf8=New-Object System.Text.UTF8Encoding($false,$true)
$root=[IO.Path]::GetFullPath($InstallationRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
$updates=Join-Path $root 'CashMemory/Updates'
$journalPath=Join-Path $updates 'install-journal.json'
$script:step=0
$script:journalWrites=0

function Guard([string]$path) {
    $full=[IO.Path]::GetFullPath($path)
    if ($full -ne $root -and -not $full.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'ROOT_ESCAPE' }
    $cursor=$full
    while ($cursor) {
        $item=$null
        try { $item=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop }
        catch [System.Management.Automation.ItemNotFoundException] { }
        if ($null -ne $item) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'REPARSE_PATH' }
        }
        $parent=[IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor=$parent
    }
    return $full
}

function SafePath([string]$value) {
    if (-not $value -or $value.Length -gt 4096 -or $value -cne $value.Normalize([Text.NormalizationForm]::FormC)) { throw 'MANAGED_PATH' }
    if ($value -cnotmatch '^(CashPrediction(?:-Swing|-Web)?\\.exe|(?:app|runtime)/.+)$') { throw 'MANAGED_PATH' }
    foreach ($part in $value.Split('/')) {
        if (-not $part -or $part -eq '.' -or $part -eq '..' -or $part -match '[\\\\:<>"|?*\\x00-\\x1f\\x7f-\\x9f]' -or $part -match '[. ]$' -or $part -match '^(?i:CON|PRN|AUX|NUL|CLOCK\\$|CONIN\\$|CONOUT\\$|COM[1-9\\u00b9\\u00b2\\u00b3]|LPT[1-9\\u00b9\\u00b2\\u00b3])(?:\\.|$)') { throw 'MANAGED_PATH' }
    }
    [void]$utf8.GetBytes($value)
    return $value
}

function ResolveManaged([string]$base,[string]$relative) {
    [void](SafePath $relative)
    return (Guard (Join-Path $base $relative.Replace('/',[IO.Path]::DirectorySeparatorChar)))
}

function CheckJson([string]$text) {
    $script:tokens=[regex]::Matches($text,'"(?:[^"\\\\\\x00-\\x1f]|\\\\(?:["\\\\/bfnrt]|u[0-9a-fA-F]{4}))*"|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null|[{}\\[\\],:]')
    $position=0
    foreach ($token in $script:tokens) {
        if ($text.Substring($position,$token.Index-$position) -notmatch '^[ \\t\\r\\n]*$') { throw 'JSON_TOKEN' }
        $position=$token.Index+$token.Length
    }
    if ($text.Substring($position) -notmatch '^[ \\t\\r\\n]*$') { throw 'JSON_TOKEN' }
    $script:tokenIndex=0
    JsonValue 0
    if ($script:tokenIndex -ne $script:tokens.Count) { throw 'JSON_TRAILING' }
}

function Token {
    if ($script:tokenIndex -ge $script:tokens.Count) { throw 'JSON_END' }
    return $script:tokens[$script:tokenIndex].Value
}

function Consume([string]$expected) {
    if ((Token) -cne $expected) { throw 'JSON_SYNTAX' }
    $script:tokenIndex++
}

function JsonValue([int]$depth) {
    if ($depth -gt 32) { throw 'JSON_DEPTH' }
    $value=Token
    if ($value -eq '{') {
        Consume '{'
        $keys=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        if ((Token) -eq '}') { Consume '}'; return }
        while ($true) {
            $key=Token
            if (-not $key.StartsWith('"')) { throw 'JSON_KEY' }
            $decoded=ConvertFrom-Json ('{"k":'+$key+'}')
            if (-not $keys.Add($decoded.k)) { throw 'JSON_DUPLICATE' }
            $script:tokenIndex++
            Consume ':'
            JsonValue ($depth+1)
            if ((Token) -eq '}') { Consume '}'; break }
            Consume ','
        }
    } elseif ($value -eq '[') {
        Consume '['
        if ((Token) -eq ']') { Consume ']'; return }
        while ($true) {
            JsonValue ($depth+1)
            if ((Token) -eq ']') { Consume ']'; break }
            Consume ','
        }
    } elseif ($value -eq '}' -or $value -eq ']' -or $value -eq ',' -or $value -eq ':') { throw 'JSON_VALUE' }
    else { $script:tokenIndex++ }
}

function ReadJson([string]$path,[int]$limit=33554432) {
    [void](Guard $path)
    $stream=[IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        if ($stream.Length -gt $limit) { throw 'METADATA_LIMIT' }
        $bytes=New-Object byte[] ([int]$stream.Length)
        $offset=0
        while ($offset -lt $bytes.Length) {
            $count=$stream.Read($bytes,$offset,$bytes.Length-$offset)
            if ($count -eq 0) { throw 'METADATA_SHORT' }
            $offset+=$count
        }
        $text=$utf8.GetString($bytes)
    } finally { $stream.Dispose() }
    CheckJson $text
    return (ConvertFrom-Json $text)
}

function RequireKeys($object,[string[]]$keys) {
    if ($null -eq $object -or $object -isnot [pscustomobject]) { throw 'JSON_OBJECT' }
    $actual=@($object.PSObject.Properties.Name)
    if ($actual.Count -ne $keys.Count) { throw 'JSON_FIELDS' }
    foreach ($key in $keys) { if ($actual -cnotcontains $key) { throw 'JSON_FIELDS' } }
}

function IntegerValue($value) { return ($value -is [int] -or $value -is [long]) }

function AtomicJson([string]$path,$value) {
    [void](Guard $path)
    $bytes=$utf8.GetBytes((ConvertTo-Json -InputObject $value -Depth 32 -Compress))
    if ($bytes.Length -gt 33554432) { throw 'METADATA_LIMIT' }
    $temporary=$path+'.tmp-'+[guid]::NewGuid().ToString()
    $stream=[IO.FileStream]::new($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None,4096,[IO.FileOptions]::WriteThrough)
    try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
    [void](Guard $path)
    if ([IO.File]::Exists($path)) {
        # PowerShell 5.1 converts an ordinary null argument to an empty string.
        # NullString preserves the .NET null backup path required by File.Replace.
        [IO.File]::Replace($temporary,$path,[System.Management.Automation.Language.NullString]::Value)
    }
    else { [IO.File]::Move($temporary,$path) }
}

function Log([string]$code) {
    $path=Guard (Join-Path $updates 'update-log.md')
    $stream=[IO.FileStream]::new($path,[IO.FileMode]::Append,[IO.FileAccess]::Write,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough)
    try {
        $bytes=$utf8.GetBytes('- '+[DateTime]::UtcNow.ToString('o')+' '+$code+[Environment]::NewLine)
        $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true)
    } finally { $stream.Dispose() }
}

function PhaseFault([string]$phase) {
    if ($FaultPhase -and $phase -ceq $FaultPhase) {
        if ($Crash) { Stop-Process -Id $PID -Force }
        throw 'INJECTED_PHASE'
    }
}

function Save {
    $script:journalWrites++
    if ($JournalFailAt -ne 0 -and ($JournalFailAt -lt 0 -or $JournalFailAt -eq $script:journalWrites)) { throw 'INJECTED_JOURNAL_WRITE' }
    AtomicJson $journalPath $script:journal
}
function Phase([string]$phase) { $script:journal.phase=$phase; Save; Log ('PHASE_'+$phase); PhaseFault $phase }

function Boundary {
    $script:step++
    if ($FaultAt -gt 0 -and $script:step -eq $FaultAt) {
        if ($Crash) { Stop-Process -Id $PID -Force }
        throw 'INJECTED_FAILURE'
    }
}

function MoveJournal([string]$kind,[string]$path,[string]$source,[string]$destination) {
    [void](Guard $source); [void](Guard $destination)
    if (-not [IO.File]::Exists($source) -or (Test-Path -LiteralPath $destination)) { throw 'MOVE_PRECONDITION' }
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
    [void](Guard $source); [void](Guard $destination)
    $operation=[pscustomobject]@{kind=$kind;path=$path;state='BEFORE'}
    $maximum=if ($script:journal.schemaVersion -eq 2) { 160000 } else { 80000 }
    if (@($script:journal.operations).Count -ge $maximum) { throw 'OPERATIONS_LIMIT' }
    $script:journal.operations=@($script:journal.operations)+@($operation)
    Save
    Log ('MOVE_'+$kind+'_BEFORE')
    Boundary
    [IO.File]::Move($source,$destination)
    Boundary
    $operation.state='AFTER'
    Save
    Log ('MOVE_'+$kind+'_AFTER')
}

function Entries($files) {
    if (@($files).Count -gt 20000) { throw 'FILES_LIMIT' }
    $result=New-Object 'System.Collections.Generic.SortedDictionary[string,object]' ([StringComparer]::Ordinal)
    $names=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $nodes=New-Object 'System.Collections.Generic.Dictionary[string,string]' ([StringComparer]::Ordinal)
    [long]$total=0
    foreach ($file in $files) {
        RequireKeys $file @('path','sizeBytes','sha256','readOnly')
        if ($file.path -isnot [string] -or $file.readOnly -isnot [bool] -or $file.sha256 -cnotmatch '^[0-9a-f]{64}$' -or -not (IntegerValue $file.sizeBytes) -or $file.sizeBytes -lt 0 -or $file.sizeBytes -gt 536870912) { throw 'FILE_ENTRY' }
        $path=SafePath $file.path
        $fold=$path.ToUpperInvariant()
        if (-not $names.Add($fold)) { throw 'PATH_COLLISION' }
        $node=$path
        while ($true) {
            $key=$node.ToUpperInvariant()
            if ($nodes.ContainsKey($key) -and $nodes[$key] -cne $node) { throw 'PATH_COLLISION' }
            $nodes[$key]=$node
            $slash=$node.LastIndexOf('/')
            if ($slash -lt 0) { break }
            $node=$node.Substring(0,$slash)
        }
        $key=[BitConverter]::ToString($utf8.GetBytes($path)).Replace('-','')
        $result.Add($key,$file)
        $total+=[long]$file.sizeBytes
        if ($total -gt 2147483648) { throw 'TREE_LIMIT' }
    }
    foreach ($file in $result.Values) {
        $parts=$file.path.Split('/')
        for ($i=1;$i -lt $parts.Length;$i++) {
            if ($names.Contains(($parts[0..($i-1)] -join '/').ToUpperInvariant())) { throw 'FILE_DIRECTORY_COLLISION' }
        }
    }
    return ,$result
}

function AssertFile([string]$path,$entry) {
    [void](Guard $path)
    $item=Get-Item -LiteralPath $path -Force
    if ($item.PSIsContainer -or $item.Length -ne $entry.sizeBytes -or (($item.Attributes -band [IO.FileAttributes]::ReadOnly) -ne 0) -ne $entry.readOnly) { throw 'FILE_IDENTITY' }
    $stream=[IO.File]::OpenRead($path)
    $sha=[Security.Cryptography.SHA256]::Create()
    try { $actual=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
    if ($actual -cne $entry.sha256) { throw 'FILE_HASH' }
}

function HashBlock($sha,[byte[]]$bytes) { [void]$sha.TransformBlock($bytes,0,$bytes.Length,$bytes,0) }
function BigEndian($number,[bool]$wide) {
    if ($wide) { $bytes=[BitConverter]::GetBytes([long]$number) } else { $bytes=[BitConverter]::GetBytes([int]$number) }
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($bytes) }
    return ,$bytes
}

function InventoryDigest($files) {
    $entries=Entries $files
    $sha=[Security.Cryptography.SHA256]::Create()
    try {
        HashBlock $sha ($utf8.GetBytes("cashprediction-tree-v1"+[char]0))
        foreach ($file in $entries.Values) {
            $bytes=$utf8.GetBytes($file.path)
            HashBlock $sha (BigEndian $bytes.Length $false)
            HashBlock $sha $bytes
            HashBlock $sha (BigEndian $file.sizeBytes $true)
            $hash=New-Object byte[] 32
            for ($i=0;$i -lt 32;$i++) { $hash[$i]=[Convert]::ToByte($file.sha256.Substring($i*2,2),16) }
            HashBlock $sha $hash
            HashBlock $sha ([byte[]]@([byte][bool]$file.readOnly))
        }
        [void]$sha.TransformFinalBlock([byte[]]@(),0,0)
        return [BitConverter]::ToString($sha.Hash).Replace('-','').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function VerifyTree([string]$base,$files,[string]$expected,[bool]$exact=$true) {
    [void](Guard $base)
    if ($expected -cnotmatch '^[0-9a-f]{64}$' -or (InventoryDigest $files) -cne $expected) { throw 'TREE_HASH' }
    $entries=Entries $files
    $names=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($file in $entries.Values) {
        AssertFile (ResolveManaged $base $file.path) $file
        [void]$names.Add($file.path)
    }
    if ($exact) {
        foreach ($child in Get-ChildItem -LiteralPath $base -Force) {
            foreach ($managed in @('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe','app','runtime')) {
                if ($child.Name -eq $managed -and $child.Name -cne $managed) { throw 'MANAGED_ROOT_COLLISION' }
            }
        }
        foreach ($top in @('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe','app','runtime')) {
            $path=Guard (Join-Path $base $top)
            if (-not (Test-Path -LiteralPath $path)) { continue }
            $queue=New-Object 'System.Collections.Generic.Queue[string]'
            $queue.Enqueue($path)
            while ($queue.Count -gt 0) {
                $next=Guard ($queue.Dequeue())
                $item=Get-Item -LiteralPath $next -Force
                if ($item.PSIsContainer) {
                    foreach ($child in Get-ChildItem -LiteralPath $next -Force) { $queue.Enqueue($child.FullName) }
                } else {
                    $relative=$next.Substring($base.Length+1).Replace([IO.Path]::DirectorySeparatorChar,'/')
                    if (-not $names.Contains($relative)) { throw 'UNEXPECTED_MANAGED_FILE' }
                }
            }
        }
    }
}

function OpenLock([string]$path,[long]$length) {
    [void](Guard $path)
    $handle=[IO.File]::Open($path,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
    try { $handle.Lock(0,$length); return $handle }
    catch { $handle.Dispose(); throw }
}

function AliveLeases {
    $directory=Guard (Join-Path $updates 'processes')
    if (-not (Test-Path -LiteralPath $directory)) { return $false }
    $leases=@(Get-ChildItem -LiteralPath $directory -Force)
    if ($leases.Count -gt 4096) { throw 'LEASE_LIMIT' }
    foreach ($item in $leases) {
        if ($item.PSIsContainer -or $item.Name -notmatch '^[0-9a-f-]{36}\\.json$') { throw 'LEASE_FILENAME' }
        $lease=ReadJson $item.FullName 16384
        RequireKeys $lease @('schemaVersion','leaseId','pid','startedAtEpochMillis','installationRoot','client')
        if (-not (IntegerValue $lease.schemaVersion) -or $lease.schemaVersion -ne 1 -or $lease.installationRoot -cne $root -or $lease.client -cnotmatch '^(fx|swing|web)$' -or -not (IntegerValue $lease.pid) -or -not (IntegerValue $lease.startedAtEpochMillis) -or $lease.pid -le 0 -or $lease.startedAtEpochMillis -le 0 -or $lease.leaseId -cne $item.BaseName) { throw 'LEASE_IDENTITY' }
        [void][guid]::Parse($lease.leaseId)
        $process=$null; $absent=$false
        try { $process=Get-Process -Id $lease.pid -ErrorAction Stop }
        catch {
            if ($_.FullyQualifiedErrorId -cne 'NoProcessFoundForGivenId,Microsoft.PowerShell.Commands.GetProcessCommand' -or
                $_.CategoryInfo.Category -ne [System.Management.Automation.ErrorCategory]::ObjectNotFound) { return $true }
            $absent=$true
        }
        if ($null -ne $process) {
            try {
                if ($process.Id -ne $lease.pid) { return $true }
                $birth=([DateTimeOffset]$process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
                if ($birth -le 0) { return $true }
            }
            catch { return $true }
            if ($birth -eq $lease.startedAtEpochMillis) { return $true }
        } elseif (-not $absent) { return $true }
        [void](Guard $item.FullName)
        [IO.File]::Delete($item.FullName)
    }
    return $false
}

function ActualProcesses {
    $launchers=@('CashPrediction','CashPrediction-Swing','CashPrediction-Web')
    foreach ($process in Get-Process) {
        $name=$process.ProcessName
        $application=($launchers -contains $name)
        $java=($name -eq 'java' -or $name -eq 'javaw')
        if (-not $application -and -not $java) { continue }
        try {
            $path=$process.Path
            if (-not $path) {
                if ($process.HasExited) { continue }
                return $true
            }
            $path=[IO.Path]::GetFullPath($path)
            if ($application) {
                foreach ($launcher in $launchers) {
                    if ($path.Equals((Join-Path $root ($launcher+'.exe')),[StringComparison]::OrdinalIgnoreCase)) { return $true }
                }
            } else {
                $runtime=Join-Path $root 'runtime'
                if ($path.StartsWith($runtime+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { return $true }
                $bootstrapRuntime=Join-Path $updates 'Bootstrap/runtime'
                if ($path.StartsWith($bootstrapRuntime+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { return $true }
            }
        } catch {
            return $true
        }
    }
    return $false
}

function ValidateJournal {
    $script:journal=ReadJson $journalPath
    $j=$script:journal
    $keys=@('schemaVersion','installationRoot','transactionId','target','phase','oldFiles','oldTreeSha256','operations','outcome')
    if ($j.schemaVersion -eq 2) { $keys+=@('bootstrap') }
    RequireKeys $j $keys
    if (-not (IntegerValue $j.schemaVersion) -or ($j.schemaVersion -ne 1 -and $j.schemaVersion -ne 2) -or $j.installationRoot -cne $root -or $j.phase -cnotmatch '^(PREPARED|WAITING|BOOTSTRAPPING|BACKING_UP|INSTALLING|VERIFYING|COMMITTED|ROLLING_BACK)$') { throw 'JOURNAL_IDENTITY' }
    [void][guid]::Parse($j.transactionId)
    RequireKeys $j.target @('schemaVersion','releaseNumber','commitSha','version','publishedAtUtc','assetName','sizeBytes','sha256','treeSha256','files','deltaPatches')
    if (-not (IntegerValue $j.target.schemaVersion) -or $j.target.schemaVersion -ne 2 -or $j.target.commitSha -cnotmatch '^[0-9a-f]{40}$' -or -not (IntegerValue $j.target.releaseNumber) -or $j.target.releaseNumber -le 0 -or @($j.target.deltaPatches).Count -gt 2) { throw 'TARGET_IDENTITY' }
    [void](Entries $j.target.files); [void](Entries $j.oldFiles)
    if ((InventoryDigest $j.target.files) -cne $j.target.treeSha256 -or (InventoryDigest $j.oldFiles) -cne $j.oldTreeSha256) { throw 'JOURNAL_TREE_HASH' }
    if ($j.outcome -cnotmatch '^(PENDING|UPDATED|ROLLED_BACK)$') { throw 'JOURNAL_OUTCOME' }
    if (($j.outcome -eq 'UPDATED' -and $j.phase -ne 'COMMITTED') -or ($j.outcome -eq 'ROLLED_BACK' -and $j.phase -ne 'ROLLING_BACK') -or ($j.phase -eq 'COMMITTED' -and $j.outcome -ne 'UPDATED')) { throw 'JOURNAL_OUTCOME_PHASE' }
    if ($j.schemaVersion -eq 2) { ValidateBootstrap }
    $maximum=if ($j.schemaVersion -eq 2) { 160000 } else { 80000 }
    if (@($j.operations).Count -gt $maximum) { throw 'OPERATIONS_LIMIT' }
    foreach ($operation in $j.operations) {
        RequireKeys $operation @('kind','path','state')
        [void](SafePath $operation.path)
        $pattern=if ($j.schemaVersion -eq 2) { '^(BACKUP|INSTALL|UNINSTALL|RESTORE|BOOT_COPY|BACKUP_COPY|REDIRECT|REPLACE|RESTORE_REPLACE|CFG_SWITCH)$' } else { '^(BACKUP|INSTALL|UNINSTALL|RESTORE)$' }
        if ($operation.kind -cnotmatch $pattern -or $operation.state -cnotmatch '^(BEFORE|AFTER)$') { throw 'JOURNAL_OPERATION' }
        $entries=if ($operation.kind -cmatch '^(BACKUP|RESTORE|BOOT_COPY|BACKUP_COPY|REDIRECT|RESTORE_REPLACE)$') { $j.oldFiles } else { $j.target.files }
        if (-not @($entries | Where-Object { $_.path -ceq $operation.path }).Count) { throw 'JOURNAL_OPERATION_PATH' }
        if ($operation.kind -ceq 'BOOT_COPY' -and -not (BootstrapPayload $operation.path)) { throw 'JOURNAL_OPERATION_PATH' }
        if ($operation.kind -cmatch '^(BACKUP_COPY|REPLACE|RESTORE_REPLACE)$' -and -not (Stable $operation.path)) { throw 'JOURNAL_OPERATION_PATH' }
        if ($operation.kind -cmatch '^(REDIRECT|CFG_SWITCH)$' -and -not (Config $operation.path)) { throw 'JOURNAL_OPERATION_PATH' }
    }
}

function OperationExists([string]$kind,[string]$path) {
    return (@($script:journal.operations | Where-Object { $_.kind -ceq $kind -and $_.path -ceq $path }).Count -gt 0)
}

function Rollback {
    if ($script:journal.schemaVersion -eq 2) { PortableRollback; return }
    $script:journal.outcome='PENDING'
    Phase 'ROLLING_BACK'
    $ready=Join-Path $updates 'Ready/tree'
    $backup=Join-Path $updates 'Backup'
    foreach ($file in $script:journal.target.files) {
        if (-not (OperationExists 'INSTALL' $file.path)) { continue }
        $source=ResolveManaged $root $file.path
        $destination=ResolveManaged $ready $file.path
        if ([IO.File]::Exists($destination)) { AssertFile $destination $file; continue }
        if ([IO.File]::Exists($source)) {
            AssertFile $source $file
            MoveJournal 'UNINSTALL' $file.path $source $destination
        } else { throw 'ROLLBACK_TARGET_MISSING' }
    }
    CleanEmptyDirectories $root $script:journal.target.files $false
    foreach ($file in $script:journal.oldFiles) {
        $source=ResolveManaged $backup $file.path
        $destination=ResolveManaged $root $file.path
        if ([IO.File]::Exists($source)) {
            AssertFile $source $file
            if (Test-Path -LiteralPath $destination) { throw 'ROLLBACK_DESTINATION_EXISTS' }
            MoveJournal 'RESTORE' $file.path $source $destination
        } else { AssertFile $destination $file }
    }
    VerifyTree $root $script:journal.oldFiles $script:journal.oldTreeSha256 $true
    $script:journal.outcome='ROLLED_BACK'
    Save
}

function CleanOwnedTree([string]$base,$files) {
    foreach ($file in $files) {
        $path=ResolveManaged $base $file.path
        if ([IO.File]::Exists($path)) {
            AssertFile $path $file
            [IO.File]::SetAttributes($path,[IO.FileAttributes]::Normal)
            [IO.File]::Delete($path)
        }
    }
    CleanEmptyDirectories $base $files $true
}

function CleanEmptyDirectories([string]$base,$files,[bool]$includeBase) {
    $directories=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($file in $files) {
        $directory=[IO.Path]::GetDirectoryName((ResolveManaged $base $file.path))
        while ($directory -and $directory.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)) {
            if ($directory -eq $base -and -not $includeBase) { break }
            [void]$directories.Add($directory)
            if ($directory -eq $base) { break }
            $directory=[IO.Path]::GetDirectoryName($directory)
        }
    }
    foreach ($directory in ($directories | Sort-Object Length -Descending)) {
        [void](Guard $directory)
        if ([IO.Directory]::Exists($directory) -and @(Get-ChildItem -LiteralPath $directory -Force).Count -eq 0) { [IO.Directory]::Delete($directory) }
    }
}

function QuoteArgument([string]$argument) {
    return '"'+[regex]::Replace([regex]::Replace($argument,'(\\\\*)"','$1$1\\"'),'(\\\\+)$','$1$1')+'"'
}

function RestartRequests {
    $directory=Guard (Join-Path $updates 'requests')
    if (-not (Test-Path -LiteralPath $directory)) { return }
    $requests=@(Get-ChildItem -LiteralPath $directory -Force)
    if ($requests.Count -gt 4096) { throw 'REQUEST_LIMIT' }
    foreach ($item in $requests) {
        if ($item.PSIsContainer -or $item.Name -notmatch '^[0-9a-f-]{36}\\.json$') { throw 'REQUEST_FILENAME' }
        $request=ReadJson $item.FullName 16384
        RequireKeys $request @('schemaVersion','transactionId','requestId','client','args')
        if (-not (IntegerValue $request.schemaVersion) -or $request.schemaVersion -ne 1 -or $request.transactionId -cne $script:journal.transactionId -or $request.requestId -cne $item.BaseName) { throw 'REQUEST_IDENTITY' }
        [void][guid]::Parse($request.requestId)
        $launcher=switch -CaseSensitive ($request.client) { 'fx' {'CashPrediction.exe'} 'swing' {'CashPrediction-Swing.exe'} 'web' {'CashPrediction-Web.exe'} default { throw 'CLIENT_ID' } }
        $arguments=@($request.args)
        if ($arguments.Count -lt 4 -or $arguments.Count -gt 6 -or $arguments[0] -cne '--home' -or $arguments[1] -cne $root -or $arguments[-2] -cne '--updated-from' -or $arguments[-1] -cne $script:journal.target.commitSha) { throw 'REQUEST_ARGUMENTS' }
        $flags=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        for ($i=2;$i -lt $arguments.Count-2;$i++) {
            if ($request.client -cne 'web' -or $arguments[$i] -cnotmatch '^--(no-browser|no-window)$' -or -not $flags.Add($arguments[$i])) { throw 'REQUEST_ARGUMENTS' }
        }
        $start=New-Object Diagnostics.ProcessStartInfo
        $start.FileName=ResolveManaged $root $launcher
        $start.WorkingDirectory=$root
        $start.UseShellExecute=$false
        $start.Arguments=($arguments | ForEach-Object { QuoteArgument $_ }) -join ' '
        [void][Diagnostics.Process]::Start($start)
        [void](Guard $item.FullName)
        [IO.File]::Delete($item.FullName)
    }
}

""" + BootstrapScript.functions() + """

$helper=$null
$barrier=$null
$preparation=$null
try {
    [void](Guard $updates)
    try { $helper=OpenLock (Join-Path $updates 'helper.lock') 1 } catch { exit 0 }
    $completed=Guard (Join-Path $updates 'completed-journal.json')
    if (-not [IO.File]::Exists($journalPath)) {
        if ([IO.File]::Exists($completed)) {
            $journalPath=$completed
            ValidateJournal
            RestartRequests
            [IO.File]::Delete($completed)
        }
        exit 0
    }
    while ($true) {
        try { $barrier=OpenLock (Join-Path $updates 'lifecycle.lock') ([long]::MaxValue) }
        catch { Start-Sleep -Milliseconds 100; continue }
        ValidateJournal
        PhaseFault $script:journal.phase
        if (-not (ActualProcesses) -and -not (AliveLeases)) { break }
        if ($script:journal.phase -eq 'PREPARED') { Phase 'WAITING' }
        $barrier.Dispose(); $barrier=$null
        Start-Sleep -Milliseconds 200
    }
    $preparation=OpenLock (Join-Path $updates 'prepare.lock') ([long]::MaxValue)
    if ($script:journal.phase -eq 'COMMITTED') {
        VerifyTree $root $script:journal.target.files $script:journal.target.treeSha256 $true
    } elseif ($script:journal.phase -eq 'ROLLING_BACK' -and $script:journal.outcome -eq 'ROLLED_BACK') {
        VerifyTree $root $script:journal.oldFiles $script:journal.oldTreeSha256 $true
    } elseif ($script:journal.phase -eq 'PREPARED' -or $script:journal.phase -eq 'WAITING') {
        try {
            $ready=Join-Path $updates 'Ready/tree'
            $backup=Guard (Join-Path $updates 'Backup')
            if (Test-Path -LiteralPath $backup) { throw 'ORPHAN_BACKUP' }
            VerifyTree $ready $script:journal.target.files $script:journal.target.treeSha256 $true
            VerifyTree $root $script:journal.oldFiles $script:journal.oldTreeSha256 $true
            if ($script:journal.schemaVersion -eq 2) { Phase 'BOOTSTRAPPING'; PrepareBootstrap }
            Phase 'BACKING_UP'
            foreach ($file in $script:journal.oldFiles) {
                if ($script:journal.schemaVersion -eq 2) { if (Stable $file.path) { continue }; WaitPortable }
                MoveJournal 'BACKUP' $file.path (ResolveManaged $root $file.path) (ResolveManaged $backup $file.path)
            }
            CleanEmptyDirectories $root $script:journal.oldFiles $false
            Phase 'INSTALLING'
            foreach ($file in $script:journal.target.files) {
                if ($script:journal.schemaVersion -eq 2) {
                    if (Config $file.path) { continue }
                    if (Stable $file.path) {
                        FileOperation 'REPLACE' $file.path (ResolveManaged $ready $file.path) (ResolveManaged $root $file.path) $file $true
                        continue
                    }
                    WaitPortable
                }
                MoveJournal 'INSTALL' $file.path (ResolveManaged $ready $file.path) (ResolveManaged $root $file.path)
            }
            Phase 'VERIFYING'
            if ($script:journal.schemaVersion -eq 2) { VerifyRedirectedTarget; SwitchConfigs $false }
            VerifyTree $root $script:journal.target.files $script:journal.target.treeSha256 $true
            $script:journal.outcome='UPDATED'
            Phase 'COMMITTED'
        } catch { Rollback }
    } else { Rollback }
    if ($script:journal.schemaVersion -eq 2) { CleanupBootstrap }
    $backupFiles=if ($script:journal.schemaVersion -eq 2) {
        @($script:journal.oldFiles | Where-Object { (OperationExists 'BACKUP' $_.path) -or (OperationExists 'BACKUP_COPY' $_.path) })
    } else { $script:journal.oldFiles }
    if ($script:journal.outcome -eq 'UPDATED') {
        CleanOwnedTree (Join-Path $updates 'Backup') $backupFiles
        CleanOwnedTree (Join-Path $updates 'Ready/tree') $script:journal.target.files
        $manifest=Guard (Join-Path $updates 'Ready/update.json')
        if ([IO.File]::Exists($manifest)) { [IO.File]::Delete($manifest) }
    } else {
        CleanOwnedTree (Join-Path $updates 'Backup') $backupFiles
    }
    AtomicJson (Join-Path $updates 'last-install.json') ([ordered]@{schemaVersion=1;transactionId=$script:journal.transactionId;outcome=$script:journal.outcome;targetCommitSha=$script:journal.target.commitSha})
    [void](Guard $journalPath); [void](Guard $completed)
    [IO.File]::Move($journalPath,$completed)
    $barrier.Dispose(); $barrier=$null
    $preparation.Dispose(); $preparation=$null
    RestartRequests
    [IO.File]::Delete($completed)
    exit 0
} catch {
    if ($Diagnostics) {
        [Console]::Error.WriteLine($_.Exception.ToString())
        [Console]::Error.WriteLine($_.ScriptStackTrace)
    }
    try {
        $log=Guard (Join-Path $updates 'update-log.md')
        $stream=[IO.FileStream]::new($log,[IO.FileMode]::Append,[IO.FileAccess]::Write,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough)
        try {
            $bytes=$utf8.GetBytes('- '+[DateTime]::UtcNow.ToString('o')+' INSTALL_FAILED'+[Environment]::NewLine)
            $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true)
        } finally { $stream.Dispose() }
    } catch { }
    exit 1
} finally {
    if ($null -ne $barrier) { $barrier.Dispose() }
    if ($null -ne $preparation) { $preparation.Dispose() }
    if ($null -ne $helper) { $helper.Dispose() }
}
""";
    }
}
