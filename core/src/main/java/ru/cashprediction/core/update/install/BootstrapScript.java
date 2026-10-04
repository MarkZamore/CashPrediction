package ru.cashprediction.core.update.install;

/** Самодостаточные операции сохранения runtime и атомарных замен для portable-журнала. */
final class BootstrapScript {
    private BootstrapScript() { }

    static String functions() {
        return """
function Stable([string]$path) {
    return (@('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe',
              'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg','app/.jpackage.xml') -ccontains $path)
}

function Config([string]$path) {
    return (@('app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg') -ccontains $path)
}

function BootstrapPayload([string]$path) {
    return ($path.StartsWith('runtime/') -or $path -cmatch '^app/cashprediction-(core|ui-fx|ui-swing|web)-[A-Za-z0-9_.-]+\\.jar$')
}

function RequireModules($files) {
    foreach ($role in @('core','ui-fx','ui-swing','web')) {
        $matching=@($files | Where-Object { $_.path -cmatch ('^app/cashprediction-'+$role+'-[A-Za-z0-9_.-]+\\.jar$') })
        if ($matching.Count -ne 1 -or $matching[0].sizeBytes -le 0) {throw 'BOOTSTRAP_MODULE_LAYOUT'}
    }
}

function BootstrapPath([string]$name) {
    $id=([guid]::Parse($script:journal.transactionId)).ToString()
    if ($id -cne $script:journal.transactionId) { throw 'TRANSACTION_ID' }
    switch -CaseSensitive ($name) {
        'full' { return (Guard (Join-Path $updates 'Bootstrap')) }
        'partial' { return (Guard (Join-Path $updates ('Bootstrap.partial-'+$id))) }
        'work' { return (Guard (Join-Path $updates ('Work-'+$id))) }
        default { throw 'BOOTSTRAP_PATH' }
    }
}

function Owned([string]$directory,[bool]$create=$false) {
    [void](Guard $directory)
    $ownerPath=Guard (Join-Path $directory 'owner.json')
    if ([IO.Directory]::Exists($directory)) {
        if (-not [IO.File]::Exists($ownerPath)) {
            if (-not $create -or @(Get-ChildItem -LiteralPath $directory -Force).Count -ne 0) { throw 'BOOTSTRAP_FOREIGN' }
        } else {
            $owner=ReadJson $ownerPath 16384
            RequireKeys $owner @('schemaVersion','installationRoot','transactionId')
            if ($owner.schemaVersion -ne 1 -or $owner.installationRoot -cne $root -or $owner.transactionId -cne $script:journal.transactionId) { throw 'BOOTSTRAP_FOREIGN' }
            return
        }
    } elseif (-not $create) { throw 'BOOTSTRAP_MISSING' }
    [void][IO.Directory]::CreateDirectory($directory)
    [void](Guard $directory)
    AtomicJson $ownerPath ([ordered]@{schemaVersion=1;installationRoot=$root;transactionId=$script:journal.transactionId})
}

function SetReadOnly([string]$path,[bool]$value) {
    [void](Guard $path)
    $attributes=[IO.File]::GetAttributes($path)
    if ($value) { $attributes=$attributes -bor [IO.FileAttributes]::ReadOnly }
    else { $attributes=$attributes -band (-bnot [IO.FileAttributes]::ReadOnly) }
    [IO.File]::SetAttributes($path,$attributes)
}

function Content([string]$path,$entry) {
    [void](Guard $path)
    if (-not [IO.File]::Exists($path)) { return $false }
    $item=Get-Item -LiteralPath $path -Force
    if ($item.Length -ne $entry.sizeBytes) { return $false }
    $stream=[IO.File]::OpenRead($path)
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() -ceq $entry.sha256) }
    finally { $stream.Dispose(); $sha.Dispose() }
}

function WaitPortable {
    while ((ActualProcesses) -or (AliveLeases)) {
        $script:barrier.Dispose(); $script:barrier=$null
        Start-Sleep -Milliseconds 100
        while ($null -eq $script:barrier) {
            try { $script:barrier=OpenLock (Join-Path $updates 'lifecycle.lock') ([long]::MaxValue) }
            catch { Start-Sleep -Milliseconds 100 }
        }
    }
}

function FileOperation([string]$kind,[string]$path,[string]$source,[string]$destination,$entry,[bool]$replace,[byte[]]$bytes=$null) {
    [void](Guard $source); [void](Guard $destination)
    $work=BootstrapPath 'work'
    Owned $work $true
    $temporary=ResolveManaged (Join-Path $work $kind) $path
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($temporary))
    [void](Guard $temporary)
    $operation=[pscustomobject]@{kind=$kind;path=$path;state='BEFORE'}
    if (@($script:journal.operations).Count -ge 160000) { throw 'OPERATIONS_LIMIT' }
    $script:journal.operations=@($script:journal.operations)+@($operation)
    Save
    Log ('FILE_'+$kind+'_BEFORE')
    Boundary
    if ([IO.File]::Exists($temporary)) { SetReadOnly $temporary $false; [IO.File]::Delete($temporary) }
    $output=[IO.FileStream]::new($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None,65536,[IO.FileOptions]::WriteThrough)
    try {
        if ($null -ne $bytes) { $output.Write($bytes,0,$bytes.Length) }
        else {
            AssertFile $source $entry
            $sourceStream=[IO.File]::OpenRead($source)
            try {
                $buffer=New-Object byte[] 65536
                [long]$copied=0
                while (($count=$sourceStream.Read($buffer,0,$buffer.Length)) -gt 0) {
                    $copied+=$count
                    if ($copied -gt $entry.sizeBytes) { throw 'BOOTSTRAP_COPY_SIZE' }
                    $output.Write($buffer,0,$count)
                }
                if ($copied -ne $entry.sizeBytes) { throw 'BOOTSTRAP_COPY_SIZE' }
            } finally { $sourceStream.Dispose() }
        }
        $output.Flush($true)
    } finally { $output.Dispose() }
    SetReadOnly $temporary $entry.readOnly
    AssertFile $temporary $entry
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
    [void](Guard $temporary); [void](Guard $destination)
    if ($replace) {
        WaitPortable
        if (-not [IO.File]::Exists($destination)) { throw 'STABLE_FILE_MISSING' }
        SetReadOnly $temporary $false
        SetReadOnly $destination $false
        [IO.File]::Replace($temporary,$destination,[System.Management.Automation.Language.NullString]::Value)
    } else {
        if (Test-Path -LiteralPath $destination) { throw 'COPY_DESTINATION_EXISTS' }
        [IO.File]::Move($temporary,$destination)
    }
    Boundary
    SetReadOnly $destination $entry.readOnly
    AssertFile $destination $entry
    $operation.state='AFTER'
    Save
    Log ('FILE_'+$kind+'_AFTER')
}

function ValidateBootstrap {
    $b=$script:journal.bootstrap
    RequireKeys $b @('schemaVersion','state','publishState','redirectFiles','cfgTexts')
    if (-not (IntegerValue $b.schemaVersion) -or $b.schemaVersion -ne 1 -or $b.state -cnotmatch '^(INITIAL|COPYING|COPIED|ACTIVE|RESTORED|CLEANED)$' -or $b.publishState -cnotmatch '^(NONE|BEFORE|AFTER)$') { throw 'BOOTSTRAP_METADATA' }
    if (@($b.redirectFiles).Count -ne 3 -or @($b.cfgTexts).Count -ne 3) { throw 'BOOTSTRAP_CONFIGS' }
    [void](Entries $b.redirectFiles)
    $seen=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($cfg in $b.cfgTexts) {
        RequireKeys $cfg @('path','text')
        if (-not (Config $cfg.path) -or -not $seen.Add($cfg.path) -or $cfg.text -isnot [string]) { throw 'BOOTSTRAP_CFG_TEXT' }
        $entry=@($b.redirectFiles | Where-Object { $_.path -ceq $cfg.path })
        if ($entry.Count -ne 1 -or $utf8.GetByteCount($cfg.text) -gt 65536) { throw 'BOOTSTRAP_CFG_TEXT' }
        $bytes=$utf8.GetBytes($cfg.text)
        $sha=[Security.Cryptography.SHA256]::Create()
        try { $hash=[BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-','').ToLowerInvariant() }
        finally { $sha.Dispose() }
        if ($entry[0].sizeBytes -ne $bytes.Length -or $entry[0].sha256 -cne $hash) { throw 'BOOTSTRAP_CFG_HASH' }
        if ($cfg.text.Contains('java-options=$ROOTDIR\\CashMemory\\Updates\\Bootstrap\\app')) {RequireModules $script:journal.oldFiles}
    }
    if (@($script:journal.target.files | Where-Object { $_.path.StartsWith('app/') -and (BootstrapPayload $_.path) }).Count -gt 0) {RequireModules $script:journal.target.files}
    foreach ($path in @('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe',
                         'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg','app/.jpackage.xml',
                         'runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules')) {
        if (@($script:journal.oldFiles | Where-Object { $_.path -ceq $path }).Count -ne 1 -or
            @($script:journal.target.files | Where-Object { $_.path -ceq $path }).Count -ne 1) { throw 'BOOTSTRAP_IMAGE_LAYOUT' }
    }
}

function RedirectText([string]$text) {
    $newline=if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines=New-Object 'System.Collections.Generic.List[string]'
    $section=''
    $application=0
    $module=0
    $runtime=0
    $modulePaths=0
    $awaitingPath=$false
    foreach ($line in [regex]::Split($text,'\\r?\\n')) {
        if ($line.StartsWith('[')) { $section=$line }
        if ($awaitingPath) {
            if ($section -cne '[JavaOptions]' -or $line -cne 'java-options=$APPDIR') { throw 'BOOTSTRAP_MODULE_PATH' }
            $lines.Add('java-options=$ROOTDIR\\CashMemory\\Updates\\Bootstrap\\app')
            $awaitingPath=$false
            continue
        }
        if ($section -ceq '[JavaOptions]') {
            if ($line -ceq 'java-options=--module-path') {
                $modulePaths++
                if ($modulePaths -ne 1) {throw 'BOOTSTRAP_MODULE_PATH'}
                $awaitingPath=$true
            } elseif ($line.StartsWith('java-options=--module-path=') -or $line.StartsWith('java-options=-p') -or $line -ceq 'java-options=$APPDIR') {
                throw 'BOOTSTRAP_MODULE_PATH'
            }
        }
        if ($line -ceq '[Application]') { $application++ }
        if ($section -ceq '[Application]') {
            if ($line.StartsWith('app.mainmodule=')) {
                $module++
                if ($line -cnotmatch '^app.mainmodule=[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$') { throw 'BOOTSTRAP_ORIGINAL_MODULE' }
            }
            elseif ($line.StartsWith('app.runtime=')) {
                $runtime++
                if ($line -cne 'app.runtime=$ROOTDIR/runtime' -and $line -cne 'app.runtime=$ROOTDIR\\runtime') { throw 'BOOTSTRAP_ORIGINAL_RUNTIME' }
                continue
            } elseif ($line.StartsWith('app.') -and -not $line.StartsWith('app.version=')) { throw 'BOOTSTRAP_EXTERNAL_MODULE' }
        }
        $lines.Add($line)
        if ($line -ceq '[Application]') { $lines.Add('app.runtime=$ROOTDIR\\CashMemory\\Updates\\Bootstrap\\runtime') }
    }
    if ($application -ne 1 -or $module -ne 1 -or $runtime -gt 1 -or $awaitingPath) { throw 'BOOTSTRAP_ORIGINAL_CFG' }
    return ($lines -join $newline)
}

function VerifyBootstrapRuntime([string]$directory) {
    Owned $directory $false
    $runtime=@($script:journal.oldFiles | Where-Object { BootstrapPayload $_.path })
    VerifyTree $directory $runtime (InventoryDigest $runtime) $true
}

function PrepareBootstrap {
    $b=$script:journal.bootstrap
    $b.state='COPYING'; Save
    $partial=BootstrapPath 'partial'
    $full=BootstrapPath 'full'
    if ([IO.Directory]::Exists($full)) { VerifyBootstrapRuntime $full }
    else {
        Owned $partial $true
        foreach ($entry in $script:journal.oldFiles) {
            if (-not (BootstrapPayload $entry.path)) { continue }
            $destination=ResolveManaged $partial $entry.path
            if ([IO.File]::Exists($destination)) { AssertFile $destination $entry }
            else { FileOperation 'BOOT_COPY' $entry.path (ResolveManaged $root $entry.path) $destination $entry $false }
        }
        VerifyBootstrapRuntime $partial
        $b.publishState='BEFORE'; Save; Boundary
        [void](Guard $partial); [void](Guard $full)
        [IO.Directory]::Move($partial,$full)
        Boundary
        $b.publishState='AFTER'; Save
    }
    $b.state='COPIED'; Save
    foreach ($entry in $script:journal.oldFiles) {
        if (-not (Stable $entry.path)) { continue }
        $destination=ResolveManaged (Join-Path $updates 'Backup') $entry.path
        if ([IO.File]::Exists($destination)) { AssertFile $destination $entry }
        else { FileOperation 'BACKUP_COPY' $entry.path (ResolveManaged $root $entry.path) $destination $entry $false }
    }
    ActivateRedirects
}

function ActivateRedirects {
    VerifyBootstrapRuntime (BootstrapPath 'full')
    foreach ($cfg in $script:journal.bootstrap.cfgTexts) {
        $backup=ResolveManaged (Join-Path $updates 'Backup') $cfg.path
        $original=@($script:journal.oldFiles | Where-Object { $_.path -ceq $cfg.path })[0]
        AssertFile $backup $original
        $text=$utf8.GetString([IO.File]::ReadAllBytes($backup))
        if ((RedirectText $text) -cne $cfg.text) { throw 'BOOTSTRAP_REDIRECT_TEXT' }
        $entry=@($script:journal.bootstrap.redirectFiles | Where-Object { $_.path -ceq $cfg.path })[0]
        $destination=ResolveManaged $root $cfg.path
        if (Content $destination $entry) { SetReadOnly $destination $entry.readOnly; continue }
        FileOperation 'REDIRECT' $cfg.path $backup $destination $entry $true ($utf8.GetBytes($cfg.text))
    }
    $script:journal.bootstrap.state='ACTIVE'; Save
    WaitPortable
}

function SwitchConfigs([bool]$old) {
    $files=if ($old) { $script:journal.oldFiles } else { $script:journal.target.files }
    $source=if ($old) { Join-Path $updates 'Backup' } else { Join-Path $updates 'Ready/tree' }
    $kind=if ($old) { 'RESTORE_REPLACE' } else { 'CFG_SWITCH' }
    foreach ($entry in $files) {
        if (-not (Config $entry.path)) { continue }
        FileOperation $kind $entry.path (ResolveManaged $source $entry.path) (ResolveManaged $root $entry.path) $entry $true
    }
    $script:journal.bootstrap.state='RESTORED'; Save
}

function VerifyRedirectedTarget {
    $files=@($script:journal.target.files | Where-Object { -not (Config $_.path) })+@($script:journal.bootstrap.redirectFiles)
    VerifyTree $root $files (InventoryDigest $files) $true
}

function PortableRollback {
    $script:journal.outcome='PENDING'; Phase 'ROLLING_BACK'
    $changed=@($script:journal.operations | Where-Object { $_.kind -cmatch '^(BACKUP|INSTALL|REDIRECT|REPLACE|CFG_SWITCH|UNINSTALL|RESTORE|RESTORE_REPLACE)$' }).Count -gt 0
    if (-not $changed) {
        VerifyTree $root $script:journal.oldFiles $script:journal.oldTreeSha256 $true
    } else {
        ActivateRedirects
        $ready=Join-Path $updates 'Ready/tree'
        $backup=Join-Path $updates 'Backup'
        foreach ($entry in $script:journal.target.files) {
            if ((Stable $entry.path) -or -not (OperationExists 'INSTALL' $entry.path)) { continue }
            $source=ResolveManaged $root $entry.path
            $destination=ResolveManaged $ready $entry.path
            if ([IO.File]::Exists($destination)) { AssertFile $destination $entry }
            else { AssertFile $source $entry; WaitPortable; MoveJournal 'UNINSTALL' $entry.path $source $destination }
        }
        CleanEmptyDirectories $root @($script:journal.target.files | Where-Object { -not (Stable $_.path) }) $false
        foreach ($entry in $script:journal.oldFiles) {
            if (Stable $entry.path) {
                if (Config $entry.path) { continue }
                FileOperation 'RESTORE_REPLACE' $entry.path (ResolveManaged $backup $entry.path) (ResolveManaged $root $entry.path) $entry $true
            } else {
                $source=ResolveManaged $backup $entry.path
                $destination=ResolveManaged $root $entry.path
                if ([IO.File]::Exists($source)) { AssertFile $source $entry; WaitPortable; MoveJournal 'RESTORE' $entry.path $source $destination }
                else { AssertFile $destination $entry }
            }
        }
        $hybrid=@($script:journal.oldFiles | Where-Object { -not (Config $_.path) })+@($script:journal.bootstrap.redirectFiles)
        VerifyTree $root $hybrid (InventoryDigest $hybrid) $true
        SwitchConfigs $true
        VerifyTree $root $script:journal.oldFiles $script:journal.oldTreeSha256 $true
    }
    $script:journal.bootstrap.state='RESTORED'
    $script:journal.outcome='ROLLED_BACK'; Save
}

function CleanupBootstrap {
    WaitPortable
    foreach ($name in @('full','partial')) {
        $directory=BootstrapPath $name
        if (-not [IO.Directory]::Exists($directory)) { continue }
        if (@(Get-ChildItem -LiteralPath $directory -Force).Count -eq 0) { [IO.Directory]::Delete((Guard $directory)); continue }
        Owned $directory $false
        $runtime=@($script:journal.oldFiles | Where-Object { BootstrapPayload $_.path })
        CleanOwnedTree $directory $runtime
        if (@(Get-ChildItem -LiteralPath $directory -Force).Count -eq 1 -and [IO.File]::Exists((Join-Path $directory 'owner.json'))) {
            [IO.File]::Delete((Guard (Join-Path $directory 'owner.json')))
            [IO.Directory]::Delete((Guard $directory))
        }
    }
    $work=BootstrapPath 'work'
    if ([IO.Directory]::Exists($work)) {
        if (@(Get-ChildItem -LiteralPath $work -Force).Count -eq 0) {
            [IO.Directory]::Delete((Guard $work))
            $script:journal.bootstrap.state='CLEANED'; Save
            return
        }
        Owned $work $false
        foreach ($kind in @('BOOT_COPY','BACKUP_COPY','REDIRECT','REPLACE','RESTORE_REPLACE','CFG_SWITCH')) {
            $entries=@($script:journal.oldFiles)+@($script:journal.target.files)
            foreach ($entry in $entries) {
                if (-not (OperationExists $kind $entry.path)) { continue }
                $path=ResolveManaged (Join-Path $work $kind) $entry.path
                if ([IO.File]::Exists($path)) { SetReadOnly $path $false; [IO.File]::Delete((Guard $path)) }
            }
            CleanEmptyDirectories (Join-Path $work $kind) $entries $true
        }
        if (@(Get-ChildItem -LiteralPath $work -Force).Count -eq 1 -and [IO.File]::Exists((Join-Path $work 'owner.json'))) {
            [IO.File]::Delete((Guard (Join-Path $work 'owner.json')))
            [IO.Directory]::Delete((Guard $work))
        }
    }
    $remaining=[IO.Directory]::Exists((BootstrapPath 'full')) -or [IO.Directory]::Exists((BootstrapPath 'partial')) -or [IO.Directory]::Exists($work)
    $script:journal.bootstrap.state=if ($remaining) { 'RESTORED' } else { 'CLEANED' }
    Save
}
""";
    }
}
