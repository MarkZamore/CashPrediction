# Pin проверяется штатным reader; JSON затем читается без автоматического Local DateTime coercion.
function Read-NormalCollectorPinnedJson([string]$File,[string]$Pin) {
    [void](Read-ColdPinnedJson $File $Pin)
    $text=[Text.UTF8Encoding]::new($false,$true).GetString([IO.File]::ReadAllBytes($File))
    $document=[Text.Json.JsonDocument]::Parse($text)
    try {Assert-PayloadAcceptanceJson $document.RootElement} finally {$document.Dispose()}
    return (ConvertFrom-ColdReceiptJson $text)
}

# Закрытая опись producer, reader и orchestration для независимых source pins.
function Get-NativeNormalSourceFiles {
    @('NativeUpdateNormalAcceptanceCollector.ps1','NativeUpdateNormalAcceptance.ps1','NativeUpdatePayloadAcceptance.ps1',
      'NativeUpdatePayloadScenarios.ps1','Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1',
      'New-NativeUpdateArtifacts.ps1','New-NativeUpdateLifecycleConfig.ps1','New-UpdateBootstrapCommands.ps1','S7-Release.ps1',
      'NativeUpdateScenarioDispatch.ps1','NativeUpdateAcceptanceDispatch.ps1')
}

# MAIN держит этот объект до native; Row не служит authority или seal.
function New-NativeNormalCollector([string]$IntentFile,[string]$IntentSha256,
    [ValidateSet('UNVERIFIED','NATIVE','UNIT_MOCK')][string]$EvidenceKind='UNVERIFIED') {
    $intent=Read-NormalCollectorPinnedJson $IntentFile $IntentSha256
    if ($intent.Scenario -cnotin @('delta','corrupt-delta-full','corrupt-full-retain','cancel-next-session','offline','timeout','malformed','once-three-attempts','leases-normal-close') -or
        $intent.Base -cnotin @('B1','B2') -or $intent.Client -cnotin @('fx','swing','web') -or
        $intent.Path -cnotin @('ascii','cyrillic','unicode') -or $intent.Phase -cne 'SESSION') {throw 'NORMAL_INTENT_ROUTE'}
    foreach ($name in 'Scenario','Base','Client','Path','Phase','SourceRoot','TargetRoot','Java',
        'BaseManifestFile','BaseManifestSha256','TargetManifestFile','TargetManifestSha256',
        'CommandFile','CommandSha256','LifecycleFile','LifecycleSha256','InputSnapshotSha256') {
        if ($null -eq $intent.PSObject.Properties[$name]) {throw ('NORMAL_INTENT_'+$name)}
    }
    if ($intent.InputSnapshotSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-NormalAcceptanceInput $IntentFile $IntentSha256).sha256 -cne $intent.InputSnapshotSha256) {throw 'NORMAL_INTENT_INPUTS'}
    $sourcePins=@{}
    foreach ($file in Get-NativeNormalSourceFiles) {$sourcePins[$file]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash.ToLowerInvariant()}
    return [pscustomobject]@{origin='MAIN_PRE_NATIVE_NORMAL_COLLECTOR';sourcePins=$sourcePins;intent=$intent;intentFile=$IntentFile;intentSha256=$IntentSha256;kind=$EvidenceKind;
        nonce=[guid]::NewGuid().ToString();createdUtc=[datetime]::UtcNow.ToString('o');bound=$false;sealed=$false;
        expected=$null;expectedFile=$null;expectedSha256=$null;indexFile=$null;indexSha256=$null;
        observations=[Collections.Generic.List[object]]::new();pending=[Collections.Generic.List[string]]::new()}
}

# Настоящий known-scope callback привязывает выделенные runner корень/nonce ДО первого процесса.
# JSON timestamp строки и DateTime штатного pinned reader сравниваются по одному UTC значению.
function Convert-NormalBindingValue($Value,[int]$Depth=0) {
    if ($Depth -gt 64) {throw 'NORMAL_BIND_DEPTH'}
    if ($Value -is [datetime]) {return $Value.ToUniversalTime().ToString('o')}
    if ($Value -is [string] -and $Value -cmatch '^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,7})?(?:Z|[+-]\d\d:\d\d)$') {
        return ([DateTimeOffset]::Parse($Value,[Globalization.CultureInfo]::InvariantCulture)).UtcDateTime.ToString('o')
    }
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) {return $Value}
    if ($Value -is [array]) {
        return ,@($Value | ForEach-Object {Convert-NormalBindingValue $_ ($Depth+1)})
    }
    if ($Value -is [pscustomobject] -or $Value -is [Collections.IDictionary]) {
        $map=[ordered]@{}
        $names=if ($Value -is [Collections.IDictionary]) {@($Value.Keys | Sort-Object)} else {@($Value.PSObject.Properties.Name | Sort-Object)}
        foreach ($name in $names) {$map[$name]=Convert-NormalBindingValue $Value.$name ($Depth+1)}
        return [pscustomobject]$map
    }
    return $Value
}

# Удержанные pins и in-memory intent сверяются с исходным MAIN файлом на каждой границе.
function Assert-NormalCollectorAuthority($Authority) {
    $files=@(Get-NativeNormalSourceFiles)
    if ($Authority.sourcePins -isnot [Collections.IDictionary] -or $Authority.sourcePins.Count -ne $files.Count) {throw 'NORMAL_SOURCE_PINS_CLOSURE'}
    foreach ($file in $files) {
        $pin=$Authority.sourcePins[$file]
        if ($pin -cnotmatch '^[0-9a-f]{64}$' -or
            $pin -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash.ToLowerInvariant()) {throw ('NORMAL_SOURCE_PIN:'+ $file)}
    }
    $intent=Read-NormalCollectorPinnedJson $Authority.intentFile $Authority.intentSha256
    if (-not (Test-ColdInventoryEqual (Convert-NormalBindingValue $intent) (Convert-NormalBindingValue $Authority.intent))) {throw 'NORMAL_INTENT_MEMORY_CHANGED'}
}

function Bind-NativeNormalCollector($Authority,$Context) {
    if ($Authority.bound -or $Authority.sealed) {throw 'NORMAL_REBIND'}
    Assert-NormalCollectorAuthority $Authority
    if ((Get-NormalAcceptanceInput $Authority.intentFile $Authority.intentSha256).sha256 -cne $Authority.intent.InputSnapshotSha256) {throw 'NORMAL_INPUTS_CHANGED'}
    foreach ($pair in @(@('scenario','Scenario'),@('base','Base'),@('client','Client'),@('path','Path'),@('phase','Phase'),
        @('source','SourceRoot'),@('target','TargetRoot'),@('java','Java'))) {
        if ($Context.($pair[0]) -cne $Authority.intent.($pair[1])) {throw ('NORMAL_BIND_'+$pair[0])}
    }
    foreach ($pair in @(@('baseManifest','BaseManifest'),@('targetManifest','TargetManifest'),
        @('cold','Command'),@('life','Lifecycle'))) {
        $p=$pair[1];$pinned=Read-NormalCollectorPinnedJson $Authority.intent.($p+'File') $Authority.intent.($p+'Sha256')
        $left=Convert-NormalBindingValue $pinned;$right=Convert-NormalBindingValue $Context.($pair[0])
        if (-not (Test-ColdInventoryEqual $left $right)) {throw ('NORMAL_BIND_CONFIG_'+$p)}
    }
    $run=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($Context.installedRoot))
    [void](Assert-ColdOwnedRun $run ([IO.Path]::GetTempPath()))
    $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
    if ($Context.installedRoot -cne (Join-Path (Join-Path $run $variants[$Context.path]) 'CashPrediction') -or
        [IO.Path]::GetFileName($Context.cellEvidence) -cne [IO.Path]::GetFileName($run) -or
        $Context.registryNode -cnotmatch '^ru/cashprediction/selftest/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'NORMAL_BIND_OWNERSHIP'}
    foreach ($source in $Context.source,$Context.target) {
        foreach ($output in $Context.installedRoot,$Context.cellEvidence) {
            if ((Test-PortablePathContains $source $output) -or (Test-PortablePathContains $output $source)) {throw 'NORMAL_BIND_OVERLAP'}
        }
    }
    $expected=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Authority.intent -Depth 64)
    foreach ($entry in @{InstalledRoot=$Context.installedRoot;CellEvidence=$Context.cellEvidence;RegistryNode=$Context.registryNode;
        PreparedUtc=[datetime]::UtcNow.ToString('o');CollectorNonce=$Authority.nonce}.GetEnumerator()) {
        $expected | Add-Member $entry.Key $entry.Value -Force
    }
    $file=Join-Path $Context.cellEvidence 'normal-expected.json';Write-ColdJson $file $expected
    $Authority.expected=$expected;$Authority.expectedFile=$file
    $Authority.expectedSha256=(Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant();$Authority.bound=$true
    return $Authority
}

# Эпоха сверяется по удержанному handle, в том числе после exit, но всегда до Dispose.
function Get-NormalRetainedIdentity($Process,$Expected,[string]$Root,[bool]$Exited) {
    $actual=Get-ColdProcessReceipt $Process $Root
    if ($actual.ProcessId -ne $Expected.ProcessId -or $actual.StartedAtTicks -ne $Expected.StartedAtTicks -or
        $actual.ExecutablePath -cne $Expected.ExecutablePath -or $actual.OwnedRoot -cne $Root -or
        [long]$actual.StartedAtTicks -le 0 -or $Process.HasExited -ne $Exited) {throw 'NORMAL_RETAINED_EPOCH'}
    return $actual
}

# Census helper использует тот же точный encoded command и birth cutoff, что cleanup.
function Get-NormalRecoveryCensus([string]$Root,[datetime]$Started) {
    $ps=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $path=Join-Path $Root 'CashMemory/Updates/apply-update.ps1'
    $command="& '"+$path.Replace("'","''")+"' -InstallationRoot '"+$Root.Replace("'","''")+"'"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    return @(Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -and
        $_.ExecutablePath.Equals($ps,[StringComparison]::OrdinalIgnoreCase) -and
        $_.CreationDate.ToUniversalTime() -ge $Started.ToUniversalTime() -and
        $_.CommandLine -match ('(?i)-EncodedCommand\s+"?'+[regex]::Escape($encoded)+'"?\s*$')} |
        ForEach-Object {[pscustomobject]@{pid=$_.ProcessId;startedAtTicks=$_.CreationDate.ToUniversalTime().Ticks;
            executablePath=$_.ExecutablePath;commandLine=$_.CommandLine}})
}

# Снимок BEFORE/READY/nonReady/cancel/sessionBoundary берётся сейчас, не из Row/логов задним числом.
function Save-NormalStage($Authority,[string]$Stage,[int]$Session,[string]$Root,$Base,$Target,$Native=$null) {
    if (-not $Authority.bound -or $Authority.sealed -or $Root -cne $Authority.expected.InstalledRoot) {throw 'NORMAL_STAGE_CONTEXT'}
    $ui=$null
    if ($null -ne $Native) {
        if ($null -eq $Native.uiProcess -or $null -eq $Native.ui) {$Authority.pending.Add('ACTUAL_UI:'+ $Stage)}
        else {
            $epoch=[pscustomobject]@{ProcessId=$Native.ui.pid;StartedAtTicks=$Native.ui.startedAtTicks;ExecutablePath=$Native.ui.executablePath}
            $ui=Get-NormalRetainedIdentity $Native.uiProcess $epoch $Root ($Stage -ceq 'sessionBoundary')
        }
    }
    $ready=Join-Path $Root 'CashMemory/Updates/Ready/update.json'
    $lease=if ($null -ne $Native -and $null -ne $Native.ui) {Join-Path $Root ('CashMemory/Updates/processes/'+$Native.ui.lease.leaseId+'.json')} else {$null}
    $snapshot=[pscustomobject]@{stage=$Stage;session=$Session;observedUtc=[datetime]::UtcNow.ToString('o');ui=$ui;
        files=@(Get-ColdManagedInventory $Root);ready=[bool](Test-Path -LiteralPath $ready);
        staged=if (Test-Path -LiteralPath $ready) {@(Get-ColdManagedInventory (Join-Path $Root 'CashMemory/Updates/Ready/tree'))} else {@()};
        preparationFinished=[bool](Test-NativePreparationFinished $Root);
        download=if (Test-Path -LiteralPath (Join-Path $Root 'CashMemory/Updates/payload.download')) {(Get-Item -LiteralPath (Join-Path $Root 'CashMemory/Updates/payload.download')).Length} else {-1L};
        staging=[bool](Test-Path -LiteralPath (Join-Path $Root 'CashMemory/Updates/Staging'));
        leasePath=$lease;leasePresent=if ($lease) {[bool](Test-Path -LiteralPath $lease)} else {$null}}
    Write-ColdJson (Join-Path $Authority.expected.CellEvidence ('normal-'+$Stage+'-'+$Session+'.json')) $snapshot
    return $snapshot
}

# У cancel stream активен; проверяется только отсутствие новых metadata START, не fake idle.
function Read-NormalCancelHttp($Server) {
    $before=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString((Read-NativeSharedBytes (Join-Path $Server.owned 'server-stats.json'))))
    $trace=Read-NativeTraceSnapshot (Join-Path $Server.owned 'server-trace.jsonl')
    $after=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString((Read-NativeSharedBytes (Join-Path $Server.owned 'server-stats.json'))))
    $starts=@($trace.events | Where-Object {$_.event -ceq 'START' -and $_.path -ceq '/update.json'})
    if ($before.closed -or $after.closed -or -not $before.healthy -or -not $after.healthy -or
        $before.cancelled -or $after.cancelled -or $starts.Count -ne 1 -or
        $before.counts.'/update.json' -ne 1 -or $after.counts.'/update.json' -ne 1) {throw 'NORMAL_CANCEL_METADATA'}
    return [pscustomobject]@{stats=$after;metadata=$starts;trace=$trace}
}

# Монотонное окно всегда 5000 ms; actual server/UI birth проверяются в каждом sample.
function Observe-NormalQuiet($Authority,$Server,$Native,[string]$Root,$Base,$Target,$Delta,[int]$Session,[int]$Timeout) {
    if ($null -eq $Native.uiProcess -or $null -eq $Native.ui) {$Authority.pending.Add('ACTUAL_UI:quiet');return}
    $cancel=$Authority.expected.Scenario -ceq 'cancel-next-session' -and $Session -eq 0
    $baseline=$null
    if ($cancel) {$baseline=Read-NormalCancelHttp $Server}
    else {
        $capture=[pscustomobject]@{snapshot=$null}
        Wait-NativeCondition {
            if (-not (Test-NativePreparationFinished $Root)) {return $false}
            try {$capture.snapshot=Read-NativeLiveHttp $Server;return $true}
            catch {if ($_.Exception.Message -cin @('NATIVE_NONPOLLING_BUSY','NATIVE_HTTP_COUNTERS','NATIVE_TRACE_UNSTABLE','NATIVE_TRACE_INCOMPLETE')) {return $false};throw}
        } $Timeout 'NORMAL_QUIET_SETTLE'
        $baseline=$capture.snapshot
        Assert-NativeScenarioHttp $Authority.expected.Scenario $baseline $Delta.assetName $Delta.sizeBytes $Target.sizeBytes
    }
    $samples=[Collections.Generic.List[object]]::new();$clock=[Diagnostics.Stopwatch]::StartNew()
    $readyPath=Join-Path $Root 'CashMemory/Updates/Ready/update.json'
    $readyPin=if (Test-Path -LiteralPath $readyPath) {(Get-FileHash -LiteralPath $readyPath).Hash.ToLowerInvariant()} else {$null}
    do {
        $elapsed=if ($samples.Count) {$clock.ElapsedMilliseconds} else {0L}
        $serverEpoch=Get-NormalRetainedIdentity $Server.process $Server.identity $Server.owned $false
        $uiEpoch=Get-NormalRetainedIdentity $Native.uiProcess ([pscustomobject]@{ProcessId=$Native.ui.pid;
            StartedAtTicks=$Native.ui.startedAtTicks;ExecutablePath=$Native.ui.executablePath}) $Root $false
        $http=if ($cancel) {Read-NormalCancelHttp $Server} else {Read-NativeLiveHttp $Server}
        if ($cancel) {
            if (-not (Test-ColdInventoryEqual $baseline.metadata $http.metadata)) {throw 'NORMAL_CANCEL_POLLING'}
            $download=Join-Path $Root 'CashMemory/Updates/payload.download'
            if ((Test-Path -LiteralPath $readyPath) -or -not (Test-Path -LiteralPath $download) -or
                (Get-Item -LiteralPath $download).Length -le 0 -or (Get-Item -LiteralPath $download).Length -ge $Delta.sizeBytes) {throw 'NORMAL_CANCEL_NOT_PARTIAL'}
        } else {
            Assert-NativeNonPollingSnapshot $baseline $http
            if ($readyPin) {
                if (-not (Test-Path -LiteralPath $readyPath) -or (Get-FileHash -LiteralPath $readyPath).Hash.ToLowerInvariant() -cne $readyPin) {throw 'NORMAL_READY_CHANGED'}
                [void](Assert-ColdTree (Join-Path $Root 'CashMemory/Updates/Ready/tree') $Target)
            } elseif (Test-Path -LiteralPath $readyPath) {throw 'NORMAL_NONREADY_CHANGED'}
        }
        [void](Assert-ColdTree $Root $Base)
        $samples.Add([pscustomobject]@{elapsedMillis=$elapsed;observedUtc=[datetime]::UtcNow.ToString('o');server=$serverEpoch;ui=$uiEpoch;http=$http})
        if ($elapsed -ge 5000) {break}
        Start-Sleep -Milliseconds 250
    } while ($true)
    Write-ColdJson (Join-Path $Authority.expected.CellEvidence ('normal-quiet-'+$Session+'.json')) ([ordered]@{
        nonce=$Authority.nonce;session=$Session;cancelStream=$cancel;windowMillis=5000;elapsedMillis=$clock.ElapsedMilliseconds;
        readyManifestSha256=$readyPin;samples=$samples.ToArray()})
}

# После штатного close, но до Dispose, удерживаются все server/launcher/UI личности.
function Save-NormalRetainedExit($Authority,[string]$Kind,[int]$Index,$Process,$Identity,[string]$Root) {
    if ($null -eq $Authority) {return}
    $epoch=Get-NormalRetainedIdentity $Process $Identity $Root $true
    $Authority.observations.Add([pscustomobject]@{kind=$Kind;index=$Index;identity=$epoch;exited=$true;
        observedUtc=[datetime]::UtcNow.ToString('o')})
}

# AFTER finally: fresh census и seal, SHA удержан MAIN authority, не Row.return.
function Complete-NormalCollector($Authority,[string]$Root,[datetime]$Started,$Cleanup) {
    if ($null -eq $Authority) {return}
    if (-not $Authority.bound -or $Authority.sealed -or $Root -cne $Authority.expected.InstalledRoot) {throw 'NORMAL_SEAL_CONTEXT'}
    Assert-NormalCollectorAuthority $Authority
    [void](Read-NormalCollectorPinnedJson $Authority.expectedFile $Authority.expectedSha256)
    [void](Read-NormalCollectorPinnedJson $Authority.intentFile $Authority.intentSha256)
    if ((Get-NormalAcceptanceInput $Authority.expectedFile $Authority.expectedSha256).sha256 -cne $Authority.intent.InputSnapshotSha256) {throw 'NORMAL_INPUTS_CHANGED'}
    $cleanupReceipt=[ordered]@{nonce=$Authority.nonce;evidenceKind=$Authority.kind;observedUtc=[datetime]::UtcNow.ToString('o');
        retained=$Authority.observations.ToArray();clients=@(Get-CopyProcesses $Root | ForEach-Object {
            [pscustomobject]@{pid=$_.ProcessId;startedAtTicks=$_.CreationDate.ToUniversalTime().Ticks;executablePath=$_.ExecutablePath}});
        helpers=@(Get-NormalRecoveryCensus $Root $Started);registryPresent=[bool](Test-Path -LiteralPath (Get-PortableRegistryPath $Authority.expected.RegistryNode));
        leases=@(Get-ChildItem -LiteralPath (Join-Path $Root 'CashMemory/Updates/processes') -Filter '*.json' -File -ErrorAction SilentlyContinue | ForEach-Object Name);
        errors=@($Cleanup | Where-Object {$null -ne $_});pending=$Authority.pending.ToArray()}
    Write-ColdJson (Join-Path $Authority.expected.CellEvidence 'normal-cleanup.json') $cleanupReceipt
    $files=@(Get-ChildItem -LiteralPath $Authority.expected.CellEvidence -File | Where-Object {
        $_.Name -cne 'normal-index.json' -and $_.Name -cmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$'} |
        Sort-Object Name | ForEach-Object {[pscustomobject]@{path=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
    if ($files.Count -gt 128) {throw 'NORMAL_INDEX_LIMIT'}
    $Authority.indexFile=Join-Path $Authority.expected.CellEvidence 'normal-index.json'
    Write-ColdJson $Authority.indexFile ([ordered]@{schemaVersion=1;CellEvidence=$Authority.expected.CellEvidence;
        nonce=$Authority.nonce;evidenceKind=$Authority.kind;sealedUtc=[datetime]::UtcNow.ToString('o');files=$files})
    $Authority.indexSha256=(Get-FileHash -LiteralPath $Authority.indexFile).Hash.ToLowerInvariant();$Authority.sealed=$true
}

# Receipt guards являются read-only; отсутствующая UI/epoch остаётся PENDING, не synthesized PASS.
function Assert-NormalCollectedQuiet($Receipt,$Launch,$Server,[datetime]$Prepared,[datetime]$Exit,[bool]$Cancel) {
    if (-not (Test-NormalFields $Receipt @('windowMillis','elapsedMillis','cancelStream','samples'))) {throw 'NORMAL_QUIET_EVIDENCE_PENDING'}
    foreach ($sample in $Receipt.samples) {
        if (-not (Test-NormalFields $sample @('elapsedMillis','observedUtc','ui','server','http')) -or
            -not (Test-NormalFields $sample.ui @('ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot')) -or
            -not (Test-NormalFields $sample.server @('ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot'))) {throw 'NORMAL_QUIET_EVIDENCE_PENDING'}
    }
    if ($Receipt.windowMillis -ne 5000 -or $Receipt.elapsedMillis -lt 5000 -or $Receipt.cancelStream -ne $Cancel -or
        @($Receipt.samples).Count -lt 2 -or $Receipt.samples[0].elapsedMillis -ne 0 -or
        $Receipt.samples[-1].elapsedMillis -lt 5000 -or $Receipt.samples[-1].elapsedMillis -gt $Receipt.elapsedMillis) {throw 'NORMAL_QUIET_WINDOW'}
    $last=-1L;$lastUtc=$Prepared
    foreach ($sample in $Receipt.samples) {
        $utc=[datetime]$sample.observedUtc
        if ($sample.elapsedMillis -le $last -or $utc -lt $lastUtc -or $utc -gt $Exit -or
            $sample.ui.ProcessId -ne $Launch.ui.pid -or $sample.ui.StartedAtTicks -ne $Launch.ui.startedAtTicks -or
            $sample.ui.ExecutablePath -cne $Launch.ui.executablePath -or $sample.server.ProcessId -ne $Server.ProcessId -or
            $sample.server.StartedAtTicks -ne $Server.StartedAtTicks -or $sample.server.ExecutablePath -cne $Server.ExecutablePath -or
            $sample.server.OwnedRoot -cne $Server.OwnedRoot -or $sample.ui.OwnedRoot -cne $Launch.ui.lease.installationRoot -or
            [long]$sample.ui.StartedAtTicks -le 0 -or [long]$sample.server.StartedAtTicks -le 0) {throw 'NORMAL_QUIET_EPOCH_TIME'}
        if ($Cancel) {
            if (-not (Test-ColdInventoryEqual $Receipt.samples[0].http.metadata $sample.http.metadata)) {throw 'NORMAL_CANCEL_POLLING'}
        } else {Assert-NativeNonPollingSnapshot $Receipt.samples[0].http $sample.http}
        $last=$sample.elapsedMillis;$lastUtc=$utc
    }
    if ((([datetime]$Receipt.samples[-1].observedUtc)-([datetime]$Receipt.samples[0].observedUtc)).TotalMilliseconds -lt 5000) {throw 'NORMAL_QUIET_WALL_WINDOW'}
}

# Отсутствие produced поля означает PENDING, противоречивое значение означает FAIL.
function Test-NormalFields($Value,[string[]]$Names) {
    foreach ($name in $Names) {if ($null -eq $Value -or $null -eq $Value.PSObject.Properties[$name]) {return $false}}
    return $true
}

# MAIN вызывает эту обёртку в том же собственном scope, что imported exact9 body.
function Invoke-NativeNormalCollectedCell($Authority,$Row,[string]$Source,$Base,$Target,$Life,$Cold,
    [string]$Java,[string]$Evidence,[int]$Timeout) {
    if ($Authority.bound -or $Authority.sealed) {throw 'NORMAL_AUTHORITY_REUSE'}
    if ($Row.status -cne 'PENDING') {throw 'NORMAL_ROW_NOT_PENDING'}
    Assert-NativeDispatchCommand 'Invoke-NativeCell'
    Assert-NormalCollectorAuthority $Authority
    $previous=Get-Variable NativeNormalBeforeNativeCollector -Scope Script -ErrorAction SilentlyContinue
    $previousValue=if ($null -eq $previous) {$null} else {$previous.Value}
    $ticket=$Authority
    # Удерживаем настоящий binder, а не повторяем поиск имени из dynamic closure module.
    $bindCommand=Get-Command Bind-NativeNormalCollector -ErrorAction Stop
    if ($bindCommand.CommandType -ne 'Function' -or $bindCommand.Name -cne 'Bind-NativeNormalCollector') {throw 'NORMAL_BINDER_KIND'}
    $binder=$bindCommand.ScriptBlock
    $script:NativeNormalBeforeNativeCollector={param($context) & $binder $ticket $context}.GetNewClosure()
    $executionError=$null
    # Helper получает отдельную Row: даже его PASS не становится canonical/native proof.
    $helperRow=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Row -Depth 64)
    try {
        $null=Invoke-NativeCell $helperRow $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
    } catch {$executionError=$_.Exception.Message}
    finally {
        if ($null -eq $previous) {Remove-Variable NativeNormalBeforeNativeCollector -Scope Script -ErrorAction SilentlyContinue}
        else {$script:NativeNormalBeforeNativeCollector=$previousValue}
    }
    $verdict=Test-NativeNormalCollectedAcceptance $Authority
    if ($executionError) {$verdict.status='FAIL';$verdict.proofComplete=$false;$verdict.errors=@($verdict.errors)+@('ACTUAL_EXECUTION_ERROR:'+ $executionError)}
    $verdict | Add-Member helperRow $helperRow
    $verdict | Add-Member expectedFile $Authority.expectedFile
    $verdict | Add-Member expectedSha256 $Authority.expectedSha256
    $verdict | Add-Member indexFile $Authority.indexFile
    $verdict | Add-Member indexSha256 $Authority.indexSha256
    return $verdict
}

# Читает только независимо удержанные Expected/index pins; status Row не используется.
function Test-NativeNormalCollectedAcceptance($Authority) {
    $errors=[Collections.Generic.List[string]]::new();$missing=[Collections.Generic.List[string]]::new()
    $checked=[Collections.Generic.List[string]]::new()
    if (-not $Authority.bound -or -not $Authority.sealed) {
        return [pscustomobject]@{status='PENDING';proofComplete=$false;missing=@('MAIN_PRE_NATIVE_BIND_AND_POST_CLEANUP_SEAL');errors=@();fullMatrix='PENDING';releaseProvenance='PENDING'}
    }
    try {
        $expected=Read-NormalCollectorPinnedJson $Authority.expectedFile $Authority.expectedSha256
        if ($Authority.origin -cne 'MAIN_PRE_NATIVE_NORMAL_COLLECTOR' -or [datetime]$Authority.createdUtc -gt [datetime]$expected.PreparedUtc) {throw 'NORMAL_PRE_NATIVE_AUTHORITY'}
        Assert-NormalCollectorAuthority $Authority
        $index=Read-NormalCollectorPinnedJson $Authority.indexFile $Authority.indexSha256
        if ($index.nonce -cne $Authority.nonce -or $index.evidenceKind -cne $Authority.kind -or $expected.CollectorNonce -cne $Authority.nonce -or
            $index.CellEvidence -cne $expected.CellEvidence) {throw 'NORMAL_AUTHORITY_CONTEXT'}
        [void](Read-NormalCollectorPinnedJson $Authority.intentFile $Authority.intentSha256)
        if ((Get-NormalAcceptanceInput $Authority.expectedFile $Authority.expectedSha256).sha256 -cne $Authority.intent.InputSnapshotSha256) {throw 'NORMAL_INPUTS_CHANGED'}
        $result=Read-NormalAcceptance $Authority.expectedFile $Authority.expectedSha256 $Authority.indexFile $Authority.indexSha256 $Authority.kind
        foreach ($e in $result.errors) {$errors.Add($e)}
        foreach ($m in $result.missing) {$missing.Add($m)}
        foreach ($c in $result.checked) {$checked.Add($c)}
        $state=[pscustomobject]@{errors=$errors;missing=$missing;checked=$checked;mock=($Authority.kind -cne 'NATIVE')}
        $base=Read-NormalCollectorPinnedJson $expected.BaseManifestFile $expected.BaseManifestSha256
        $target=Read-NormalCollectorPinnedJson $expected.TargetManifestFile $expected.TargetManifestSha256
        $cell=Read-NormalReceipt 'cell.json' $index $expected $state
        $cleanup=Read-NormalReceipt 'normal-cleanup.json' $index $expected $state
        $before=Read-NormalReceipt 'normal-BEFORE--1.json' $index $expected $state
        if ($null -eq $cell -or $null -eq $cleanup -or $null -eq $before) {throw 'NORMAL_COLLECTOR_REQUIRED_RECEIPTS_PENDING'}
        if (-not (Test-NormalFields $cleanup @('nonce','observedUtc','retained','clients','helpers','errors','registryPresent','leases','pending')) -or
            -not (Test-NormalFields $before @('stage','session','observedUtc','ready','files'))) {
            $missing.Add('COLLECTOR_CLEANUP_BEFORE_FIELDS');throw 'NORMAL_COLLECTOR_REQUIRED_RECEIPTS_PENDING'
        }
        $prepared=[datetime]$expected.PreparedUtc;$cellEnd=[datetime]$cell.finishedAt
        if ($before.stage -cne 'BEFORE' -or $before.session -ne -1 -or $before.ready -or
            [datetime]$before.observedUtc -lt $prepared -or -not (Test-ColdInventoryEqual $before.files $base.files)) {throw 'NORMAL_BEFORE'}
        if ($cleanup.nonce -cne $Authority.nonce) {throw 'NORMAL_CLEANUP_NONCE'}
        if ([datetime]$cleanup.observedUtc -lt $cellEnd -or [datetime]$index.sealedUtc -lt [datetime]$cleanup.observedUtc) {throw 'NORMAL_CLEANUP_TIME'}
        if (@($cleanup.clients).Count -or @($cleanup.helpers).Count) {throw 'NORMAL_CLEANUP_PROCESSES'}
        if (@($cleanup.errors).Count) {throw 'NORMAL_CLEANUP_ERRORS'}
        if ($cleanup.registryPresent -or @($cleanup.leases).Count) {throw 'NORMAL_CLEANUP_REGISTRY_LEASE'}
        foreach ($m in $cleanup.pending) {$missing.Add($m)}
        $passes=if ($expected.Scenario -ceq 'cancel-next-session') {2} else {1}
        $allQuiet=$true;$allStage=$true;$allRetained=$true
        $previous=$prepared
        for ($session=0;$session -lt $passes;$session++) {
            $launch=Read-NormalReceipt ('launch-'+$session+'.json') $index $expected $state
            $exit=Read-NormalReceipt ('exit-'+$session+'.json') $index $expected $state
            $quiet=Read-NormalReceipt ('normal-quiet-'+$session+'.json') $index $expected $state
            $config=Read-NormalReceipt ('normal-server-config-'+$session+'.json') $index $expected $state
            $cancel=$expected.Scenario -ceq 'cancel-next-session' -and $session -eq 0
            $stageName=if ($cancel) {'cancel'} elseif ($expected.Scenario -cin @('offline','timeout','malformed','corrupt-full-retain')) {'nonReady'} else {'READY'}
            $stage=Read-NormalReceipt ('normal-'+$stageName+'-'+$session+'.json') $index $expected $state
            if ($null -ne $stage -and (-not (Test-NormalFields $stage @('stage','session','observedUtc','ui','files','ready','staged','preparationFinished','download','staging','leasePath','leasePresent')) -or
                ($null -ne $stage.ui -and -not (Test-NormalFields $stage.ui @('ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot'))))) {
                $missing.Add('STAGE_FIELDS:'+ $session);$stage=$null
            }
            if ($null -eq $launch -or $null -eq $exit -or $null -eq $launch.ui) {
                $missing.Add('ACTUAL_UI_AND_EXIT:'+ $session);$allQuiet=$false;$allStage=$false;$allRetained=$false;continue
            }
            if ([datetime]$launch.startedAt -lt $previous -or [datetime]$before.observedUtc -gt [datetime]$launch.startedAt) {throw 'NORMAL_SESSION_CHRONOLOGY'}
            if ($null -eq $config) {$allQuiet=$false;$allRetained=$false}
            else {
                $decoded=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString([Convert]::FromBase64String($config.encoded)))
                if (-not (Test-ColdInventoryEqual $decoded $config.config) -or
                    $config.config.ownedTempUuid -cne $config.identity.OwnedRoot -or
                    [datetime]$config.capturedUtc -gt [datetime]$launch.startedAt -or $config.identity.StartedAtTicks -le 0) {throw 'NORMAL_SERVER_CONFIG'}
                $delay=if ($expected.Scenario -ceq 'timeout' -or $cancel) {60000} else {0}
                if ($config.config.delayMillis -ne $delay -or $config.config.maxLifetimeMillis -ne 600000 -or
                    $config.config.chunkBytes -ne $(if ($cancel) {32} else {65536})) {throw 'NORMAL_SERVER_DELAY'}
            }
            if ($null -eq $quiet -or $null -eq $config) {$allQuiet=$false}
            else {
                if ($quiet.nonce -cne $Authority.nonce -or $quiet.session -ne $session) {throw 'NORMAL_QUIET_CONTEXT'}
                try {Assert-NormalCollectedQuiet $quiet $launch $config.identity ([datetime]$launch.startedAt) ([datetime]$exit.exitedUtc) $cancel}
                catch {
                    if ($_.Exception.Message -cne 'NORMAL_QUIET_EVIDENCE_PENDING') {throw}
                    $missing.Add('QUIET_UI_SERVER_EPOCH:'+ $session);$allQuiet=$false
                }
                if ($null -ne $stage -and [datetime]$stage.observedUtc -gt [datetime]$quiet.samples[0].observedUtc) {throw 'NORMAL_STAGE_AFTER_QUIET'}
            }
            if ($null -eq $stage -or $null -eq $stage.ui) {$allStage=$false;$missing.Add('ACTUAL_UI_STAGE:'+ $session)}
            else {
                if ($stage.stage -cne $stageName -or $stage.session -ne $session -or
                    $stage.ui.ProcessId -ne $launch.ui.pid -or $stage.ui.StartedAtTicks -ne $launch.ui.startedAtTicks -or
                    $stage.ui.StartedAtTicks -le 0 -or $stage.ui.ExecutablePath -cne $launch.ui.executablePath -or
                    $stage.ui.OwnedRoot -cne $expected.InstalledRoot -or
                    [datetime]$stage.observedUtc -lt [datetime]$launch.startedAt -or [datetime]$stage.observedUtc -gt [datetime]$exit.exitedUtc -or
                    -not (Test-ColdInventoryEqual $stage.files $base.files)) {throw 'NORMAL_STAGE_IDENTITY_TIME_TREE'}
                if ($stageName -ceq 'READY') {
                    if (-not $stage.ready -or -not (Test-ColdInventoryEqual $stage.staged $target.files)) {throw 'NORMAL_STAGE_READY'}
                } elseif ($stage.ready) {throw 'NORMAL_STAGE_UNEXPECTED_READY'}
                if ($expected.Scenario -ceq 'leases-normal-close' -and ($stage.leasePresent -ne $true -or
                    $stage.leasePath -cne (Join-Path $expected.InstalledRoot ('CashMemory/Updates/processes/'+$launch.ui.lease.leaseId+'.json')))) {throw 'NORMAL_LEASE_NOT_OBSERVED'}
                if ($cancel) {
                    if ($stage.download -le 0 -or $stage.preparationFinished) {throw 'NORMAL_STAGE_CANCEL'}
                    $boundary=Read-NormalReceipt ('normal-sessionBoundary-'+$session+'.json') $index $expected $state
                    if ($null -eq $boundary -or $null -eq $boundary.ui) {$allStage=$false}
                    else {
                        if ($boundary.stage -cne 'sessionBoundary' -or $boundary.session -ne $session -or
                            $boundary.ui.ProcessId -ne $launch.ui.pid -or $boundary.ui.StartedAtTicks -ne $launch.ui.startedAtTicks -or
                            [datetime]$boundary.observedUtc -lt [datetime]$exit.exitedUtc -or $boundary.ready -or
                            $boundary.staging -or $boundary.download -ne -1 -or -not (Test-ColdInventoryEqual $boundary.files $base.files)) {throw 'NORMAL_SESSION_BOUNDARY'}
                        $previous=[datetime]$boundary.observedUtc
                    }
                } elseif ($stageName -ceq 'nonReady' -and -not $stage.preparationFinished) {throw 'NORMAL_STAGE_PREPARATION_ACTIVE'}
            }
            foreach ($kind in 'server','launcher','ui') {
                $retained=@($cleanup.retained | Where-Object {$_.kind -ceq $kind -and $_.index -eq $session})
                if ($retained.Count -ne 1) {$missing.Add('RETAINED_BEFORE_DISPOSE:'+ $kind+':'+$session);$allRetained=$false;continue}
                if (-not (Test-NormalFields $retained[0] @('identity','exited','observedUtc')) -or
                    -not (Test-NormalFields $retained[0].identity @('ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot'))) {
                    $missing.Add('RETAINED_EPOCH_FIELDS:'+ $kind+':'+$session);$allRetained=$false;continue
                }
                $identity=if ($kind -ceq 'server') {if ($null -eq $config) {$null} else {$config.identity}} elseif ($kind -ceq 'launcher') {$launch.launcher} else {
                    [pscustomobject]@{ProcessId=$launch.ui.pid;StartedAtTicks=$launch.ui.startedAtTicks;ExecutablePath=$launch.ui.executablePath;OwnedRoot=$expected.InstalledRoot}}
                if ($null -eq $identity) {$allRetained=$false;continue}
                if (-not $retained[0].exited -or -not (Test-ColdInventoryEqual $retained[0].identity $identity) -or
                    [long]$retained[0].identity.StartedAtTicks -le 0 -or
                    [datetime]$retained[0].observedUtc -lt [datetime]$exit.exitedUtc -or
                    [datetime]$retained[0].observedUtc -gt [datetime]$cleanup.observedUtc) {throw 'NORMAL_RETAINED_EXIT'}
            }
            if (-not $cancel) {$previous=[datetime]$exit.exitedUtc}
        }
        if ($allQuiet) {
            [void]$missing.Remove('LIVE_NONPOLLING_OBSERVATION_NOT_PRODUCED:'+ $expected.Scenario)
            $checked.Add('ALL_TRANSPORT_LIVE_5000MS')
        }
        if ($allStage) {
            [void]$missing.Remove('PREPARATION_FINISHED_NO_READY_AT_EXIT_NOT_PERSISTED')
            [void]$missing.Remove('CANCEL_NO_READY_AND_OLD_TREE_BETWEEN_SESSIONS_NOT_PERSISTED')
            $checked.Add('BEFORE_STAGE_SESSION_BOUNDARY')
        }
        if ($allRetained -and -not @($cleanup.pending).Count) {
            [void]$missing.Remove('POST_FINALLY_CLIENT_HELPER_SERVER_REGISTRY_CENSUS_NOT_PERSISTED')
            [void]$missing.Remove('POST_EXIT_LEASE_REMOVAL_OBSERVATION_NOT_PERSISTED')
            $checked.Add('FRESH_CLEANUP_AND_RETAINED_EPOCHS')
        }
        if ($expected.Scenario -ceq 'corrupt-full-retain') {
            $samples=Read-NormalReceipt 'normal-retained-interval.json' $index $expected $state
            if ($null -ne $samples) {
                if (@($samples).Count -lt 2 -or $samples[0].elapsedMillis -gt 250 -or $samples[-1].elapsedMillis -lt 3000 -or
                    (([datetime]$samples[-1].observedUtc)-([datetime]$samples[0].observedUtc)).TotalMilliseconds -lt 3000) {throw 'NORMAL_RETAINED_WINDOW'}
                $last=-1L;$lastTime=$previous
                foreach ($sample in $samples) {
                    if ($sample.elapsedMillis -le $last -or @($sample.clients).Count -or
                        [datetime]$sample.observedUtc -lt $lastTime -or [datetime]$sample.observedUtc -gt $cellEnd -or
                        -not (Test-ColdInventoryEqual $sample.files $base.files)) {throw 'NORMAL_RETAINED_CENSUS'}
                    $last=$sample.elapsedMillis;$lastTime=[datetime]$sample.observedUtc
                }
                [void]$missing.Remove('RETAINED_INTERVAL_PROCESS_CENSUS_NOT_PERSISTED')
            }
        }
        # Клиентский timeout budget пока не имеет независимо сохраняемого producer.
        # Даже реальный server delay не закрывает эту вторую независимую часть требования.
    } catch {
        if ($_.Exception.Message -cne 'NORMAL_COLLECTOR_REQUIRED_RECEIPTS_PENDING') {$errors.Add($_.Exception.Message)}
    }
    if ($Authority.kind -cne 'NATIVE') {$missing.Add('MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED')}
    $status=if ($errors.Count) {'FAIL'} elseif ($missing.Count) {'PENDING'} else {'PASS'}
    return [pscustomobject]@{status=$status;scope='NATIVE_NORMAL_TRANSPORT_CELL';proofComplete=($status -ceq 'PASS');evidenceKind=$Authority.kind;
        errors=$errors.ToArray();missing=@($missing.ToArray() | Select-Object -Unique);checked=$checked.ToArray();fullMatrix='PENDING';releaseProvenance='PENDING'}
}
