<#
.SYNOPSIS
Отдельные fault-клетки UpdateEvidence; dot-source не запускает процессы.
.DESCRIPTION
Invoke-NativePhaseScenario принимает тот же контракт, что Invoke-NativeCell.
MAIN заранее импортирует cold/lifecycle определения через AST, проверяет pins и
терминальность предыдущей клетки. Нужны script:nativeTarget/nativeProject/nativeProfile.
Cold - закреплённый bootstrap-commands объект; Life - lifecycle config.
Дополнительные cold определения: New-ColdJournal, New-ColdInstrumentedHelper,
Get-ColdCheckpoints, Assert-ColdCheckpoint, Assert-ColdProtectedEvidence,
Start-ColdProcess, Save-ColdHelperDiagnostics. Existing runner не исполняется.
Дополнительно: Get-ColdRecoveryObservation, Assert-ColdRecoveryEvidence,
Assert-ColdSafeArgs и Initialize-NativeDomainSession. Recovery args не содержат
test-api: production RestartRequest намеренно удаляет тестовые CLI параметры.
Все семь фаз сохраняются в плане. PREPARED/WAITING - durable entry barriers,
COMMITTED per-move - terminal barrier без выдуманного дополнительного move.
WAITING требует настоящего owned клиента/lease до публикации Ready/journal.
Setup использует уже импортированные Start-NativeFixture/Start-NativeOwned/
Connect-NativeClient/Close-NativeNormally/Stop-NativeFixture. Его exit не учитывается
как ordinary signoff; fresh recovery epoch начинается только после setup removal.
Fault injection использует штатные JournalFailAt/FaultAt. Hook перехватывает только
их точные исключения, сохраняет durable journal и блокируется в существующем
ColdCheckpoint до identity-bound Kill. Recovery запускает только настоящий exe.
Mock fixtures проверяют контракт/инструментацию, никогда не выставляют native PASS.
#>

# Полный phase-план; отсутствие write/move boundary не заменяется похожей фазой.
function Get-NativePhaseMapping([string]$Scenario,[string]$Phase) {
    $phases=@('PREPARED','WAITING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK')
    if ($Scenario -ceq 'helper-runtime-death') {
        if ($Phase -cne 'SESSION') {throw 'PHASE_SCENARIO_IDENTITY'}
        return [pscustomobject]@{scenario=$Scenario;phase=$Phase;mapped=$true;checkpoint='ACTIVE';
            mode='runtime-death';requiresLease=$false;error='OWNED_HELPER_RUNTIME_DEATH';boundary='durable bootstrap ACTIVE';reason=''}
    }
    if ($Scenario -cnotin @('journal-fault','per-move-fault') -or $Phase -cnotin $phases) {throw 'PHASE_SCENARIO_IDENTITY'}
    $barrier=$Phase -in @('PREPARED','WAITING') -or ($Scenario -eq 'per-move-fault' -and $Phase -eq 'COMMITTED')
    $boundary=if ($Phase -eq 'PREPARED') {'INITIAL_DURABLE'} elseif ($Phase -eq 'WAITING') {'WAITING_DURABLE'}
        elseif ($barrier) {'TERMINAL_DURABLE'} elseif ($Scenario -eq 'journal-fault') {'Save'} else {'Boundary'}
    return [pscustomobject]@{scenario=$Scenario;phase=$Phase;mapped=$true;
        checkpoint=$(if ($Phase -eq 'PREPARED') {'INITIAL'} else {$Phase});requiresLease=($Phase -eq 'WAITING');
        mode=$(if ($barrier) {'durable-barrier'} else {'builtin-fault'});
        error=$(if ($barrier) {'OWNED_DURABLE_BARRIER_INTERRUPTION'} elseif ($Scenario -eq 'journal-fault') {'INJECTED_JOURNAL_WRITE'} else {'INJECTED_FAILURE'});
        boundary=$boundary;reason=''}
}

# Полный план без сокращения canonical UpdateEvidence множества фаз.
function Get-NativePhaseScenarioPlan {
    foreach ($scenario in 'journal-fault','per-move-fault') {
        foreach ($phase in 'PREPARED','WAITING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK') {
            Get-NativePhaseMapping $scenario $phase
        }
    }
    Get-NativePhaseMapping 'helper-runtime-death' 'SESSION'
}

# Только закреплённый production helper; AST anchors обязаны содержать штатные faults.
function New-NativePhaseHelper([string]$Original,[string]$Sha256) {
    $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Original))).ToLowerInvariant()
    if ($Sha256 -cnotmatch '^[0-9a-f]{64}$' -or $hash -cne $Sha256) {throw 'PHASE_HELPER_PIN'}
    $text=New-ColdInstrumentedHelper $Original
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'PHASE_HELPER_PARSE'}
    $edits=@()
    foreach ($name in 'Save','Boundary','ColdCheckpoint') {
        $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($nodes.Count -ne 1) {throw 'PHASE_HELPER_ANCHOR'}
        $node=$nodes[0]
        if ($name -eq 'ColdCheckpoint') {
            $replacement=$node.Extent.Text.Replace('function ColdCheckpoint(','function PhaseScenarioColdCheckpoint(')
        } else {
            $body=$node.Body.Extent.Text.Substring(1,$node.Body.Extent.Text.Length-2)
            $code=if ($name -eq 'Save') {'INJECTED_JOURNAL_WRITE'} else {'INJECTED_FAILURE'}
            $anchor=if ($name -eq 'Save') {'AtomicJson $journalPath $script:journal'} else {'$script:step++'}
            if (-not $body.Contains("throw '$code'") -or -not $body.Contains($anchor)) {throw 'PHASE_HELPER_FAULT_ANCHOR'}
            $arm=if ($name -eq 'Save') {
                # Сначала phase становится durable; fault следующего Save остаётся в той же фазе.
                "if (`$control.mode -ceq 'builtin-fault' -and `$control.scenario -ceq 'journal-fault' -and `$script:journal.phase -ceq `$control.phase -and -not `$script:phaseFaultArmed) {`$durablePhase=(ReadJson `$journalPath).phase;if (`$durablePhase -ceq `$control.phase) {`$JournalFailAt=`$script:journalWrites+1;`$script:phaseFaultArmed=`$true}}"
            } else {
                # Phase transitions и bootstrap publish Boundary не являются per-move.
                "if (`$control.mode -ceq 'builtin-fault' -and `$control.scenario -ceq 'per-move-fault' -and `$script:journal.phase -ceq `$control.phase -and -not `$script:phaseFaultArmed -and @(`$script:journal.operations).Count) {`$last=@(`$script:journal.operations)[-1];if (`$last.state -ceq 'BEFORE' -and `$last.kind -cin @('BACKUP','INSTALL','REPLACE','CFG_SWITCH','UNINSTALL','RESTORE','RESTORE_REPLACE')) {`$FaultAt=`$script:step+1;`$script:phaseFaultArmed=`$true}}"
            }
            $replacement="function $name {`n`$control=ReadJson (Join-Path `$updates 'phase-control.json') 16384`n$arm`ntry {`n$body`n} catch {`nif (`$_.Exception.Message -cne '$code' -or `$script:journal.phase -cne `$control.phase -or -not `$script:phaseFaultArmed) {throw}`nCapturePhaseFault '$code' '$name'`nthrow`n}`n}"
        }
        $edits+=@([pscustomobject]@{start=$node.Extent.StartOffset;length=$node.Extent.Text.Length;text=$replacement})
    }
    foreach ($edit in $edits | Sort-Object start -Descending) {$text=$text.Remove($edit.start,$edit.length).Insert($edit.start,$edit.text)}
    $support=@'
$script:phaseFaultArmed=$false
function ColdCheckpoint([string]$hook) {
    $control=ReadJson (Join-Path $updates 'phase-control.json') 16384
    if ($control.scenario -ceq 'helper-runtime-death') {PhaseScenarioColdCheckpoint $hook}
    elseif ($control.mode -ceq 'durable-barrier' -and $hook -ceq 'PHASE' -and $script:journal.phase -ceq $control.phase) {
        CapturePhaseFault $control.error $control.boundary
    }
}
function CapturePhaseFault([string]$error,[string]$boundary) {
    $control=ReadJson (Join-Path $updates 'phase-control.json') 16384
    $durable=ReadJson $journalPath
    $stream=[IO.File]::OpenRead($journalPath);$sha=[Security.Cryptography.SHA256]::Create()
    try {$digest=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}
    finally {$stream.Dispose();$sha.Dispose()}
    $last=$null;if (@($script:journal.operations).Count) {$last=@($script:journal.operations)[-1]}
    $process=[Diagnostics.Process]::GetCurrentProcess()
    try {
        AtomicJson (Join-Path $updates 'phased-fault.json') ([ordered]@{schemaVersion=1;
            scenario=$control.scenario;phase=$control.phase;boundary=$boundary;error=$error;
            installationRoot=$root;transactionId=$script:journal.transactionId;pid=[long]$PID;
            startedAtTicks=$process.StartTime.ToUniversalTime().Ticks;executablePath=$process.MainModule.FileName;
            journalSha256=$digest;durablePhase=$durable.phase;journalWrites=$script:journalWrites;
            step=$script:step;operation=$last})
    } finally {$process.Dispose()}
    # Builtin exception либо явно обозначенный durable barrier; никогда не обычный exit.
    PhaseScenarioColdCheckpoint 'PHASE'
    throw 'PHASE_FAULT_RELEASED_WITHOUT_KILL'
}

'@
    $ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    $guard=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Guard'},$true))
    if ($guard.Count -ne 1) {throw 'PHASE_HELPER_ANCHOR'}
    $text=$text.Insert($guard[0].Extent.StartOffset,$support)
    [void][Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'PHASE_HELPER_PARSE'}
    return $text
}

# Fault-квитанция связывается с точным checkpoint, durable bytes и удержанным helper.
function Assert-NativePhaseFault($Fault,$Mapping,$Checkpoint,$Identity,$Journal) {
    if (-not $Mapping.mapped -or $Mapping.scenario -eq 'helper-runtime-death') {throw 'PHASE_FAULT_MAPPING'}
    Assert-ColdKeys $Fault @('schemaVersion','scenario','phase','boundary','error','installationRoot','transactionId',
        'pid','startedAtTicks','executablePath','journalSha256','durablePhase','journalWrites','step','operation')
    if (-not (Test-ColdInteger $Fault.schemaVersion 1) -or -not (Test-ColdInteger $Fault.pid 1) -or
        -not (Test-ColdInteger $Fault.startedAtTicks 1) -or $Fault.schemaVersion -ne 1 -or $Fault.scenario -cne $Mapping.scenario -or $Fault.phase -cne $Mapping.phase -or
        $Fault.error -cne $Mapping.error -or $Fault.installationRoot -cne $Checkpoint.installationRoot -or
        $Fault.transactionId -cne $Checkpoint.transactionId -or $Fault.pid -ne $Identity.ProcessId -or
        $Fault.startedAtTicks -ne $Identity.StartedAtTicks -or $Fault.executablePath -cne $Identity.ExecutablePath -or
        $Fault.journalSha256 -cne $Checkpoint.journalSha256 -or $Fault.durablePhase -cne $Journal.phase -or
        $Checkpoint.phase -cne $Mapping.phase -or $Journal.phase -cne $Mapping.phase -or
        -not (Test-ColdInteger $Fault.journalWrites) -or
        -not (Test-ColdInteger $Fault.step)) {throw 'PHASE_FAULT_IDENTITY'}
    if ($Mapping.mode -eq 'durable-barrier') {
        if ($Fault.boundary -cne $Mapping.boundary -or
            ($Mapping.phase -eq 'COMMITTED' -and $Journal.outcome -cne 'UPDATED')) {throw 'PHASE_FAULT_BOUNDARY'}
        return
    }
    if ($Fault.journalWrites -lt 1) {throw 'PHASE_FAULT_BOUNDARY'}
    if ($Mapping.scenario -eq 'journal-fault') {
        if ($Fault.boundary -cne 'Save') {throw 'PHASE_FAULT_BOUNDARY'}
    } else {
        $phaseKinds=switch ($Mapping.phase) {
            'BACKING_UP' {@('BACKUP')}
            'INSTALLING' {@('INSTALL','REPLACE')}
            'VERIFYING' {@('CFG_SWITCH')}
            'ROLLING_BACK' {@('UNINSTALL','RESTORE','RESTORE_REPLACE')}
            default {@()}
        }
        if ($Fault.boundary -cne 'Boundary' -or $Fault.step -lt 1 -or $null -eq $Fault.operation -or
            $Fault.operation.state -cne 'BEFORE' -or $Fault.operation.kind -cnotin @($phaseKinds) -or
            -not (Test-ColdInventoryEqual $Fault.operation $Checkpoint.operation)) {throw 'PHASE_FAULT_BOUNDARY'}
        # Per-move fault происходит после durable BEFORE Save, до первой mutation.
        if (@($Journal.operations).Count -eq 0 -or
            -not (Test-ColdInventoryEqual $Fault.operation @($Journal.operations)[-1])) {throw 'PHASE_FAULT_DURABILITY'}
    }
}

# WAITING отсутствует в старом checkpoint list, но genuine PHASE hook его поддерживает.
# PREPARED отображается только в INITIAL; durable phase не нормализуется и не подменяется.
function Assert-NativePhaseCheckpoint($Mapping,$Checkpoint,$Identity,[string]$Root,$Journal) {
    Assert-ColdCheckpoint $Checkpoint $Identity $Root $Mapping.checkpoint $Journal.transactionId
    if ($Mapping.scenario -ne 'helper-runtime-death' -and
        ($Checkpoint.phase -cne $Mapping.phase -or $Journal.phase -cne $Mapping.phase -or $Checkpoint.hook -cne 'PHASE')) {throw 'PHASE_CHECKPOINT_DURABILITY'}
    if ($Mapping.phase -in @('PREPARED','WAITING')) {
        if ($Checkpoint.bootstrapState -cne 'INITIAL' -or $Journal.bootstrap.state -cne 'INITIAL' -or
            $Checkpoint.publishState -cne 'NONE' -or $Checkpoint.bootstrapVerified -or
            @($Checkpoint.bootstrapFiles).Count -ne 0 -or @($Journal.operations).Count -ne 0 -or
            $Checkpoint.bootstrapTreeSha256 -cne (Get-ColdTreeHash @())) {throw 'PHASE_CHECKPOINT_DURABILITY'}
    } elseif ($Mapping.scenario -eq 'journal-fault' -and $Mapping.phase -eq 'COMMITTED' -and
        $Checkpoint.bootstrapState -eq 'CLEANED' -and -not $Checkpoint.bootstrapVerified) {
        # CleanupBootstrap удаляет full ДО последнего Save(CLEANED). Это не потерянный pin:
        # old payload уже удалён штатно, полный target дополнительно проверяет caller до Kill.
        if ($Journal.outcome -cne 'UPDATED' -or @($Checkpoint.bootstrapFiles).Count -ne 0 -or
            $Checkpoint.bootstrapTreeSha256 -cne (Get-ColdTreeHash @())) {throw 'PHASE_CHECKPOINT_DURABILITY'}
    } else {Assert-ColdProtectedEvidence $Checkpoint $Journal}
}

# Сохранённый и свежий native receipts обязаны описывать один настоящий owned lease.
# Только caller проверяет retained.HasExited; fixture-объект не является native доказательством.
function Assert-NativePhaseWaitingLease($Previous,$Current,[string]$Root,[string]$Client) {
    if ($null -eq $Current) {throw 'PHASE_WAITING_OWNED_LEASE_GONE'}
    Assert-ColdUiReceipt $Previous $Root $Client;Assert-ColdUiReceipt $Current $Root $Client
    if ($Current.pid -ne $Previous.pid -or $Current.startedAtTicks -ne $Previous.startedAtTicks -or
        $Current.lease.leaseId -cne $Previous.lease.leaseId -or $Current.lease.installationRoot -cne $Root -or
        $Current.lease.client -cne $Client) {throw 'PHASE_WAITING_OWNED_LEASE_GONE'}
}

# Adapter к Invoke-NativeCell: MAIN вызывает только после терминальности предыдущего run.
function Invoke-NativePhaseScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    $mapping=Get-NativePhaseMapping $Row.scenario $Row.phase
    $Row | Add-Member status 'PENDING' -Force;$Row | Add-Member executed $false -Force
    if (-not $mapping.mapped) {$Row | Add-Member reason $mapping.reason -Force;throw 'PHASE_SCENARIO_UNMAPPED'}
    if ($Timeout -lt 10 -or $Timeout -gt 180 -or $Row.base -cnotin @('B1','B2') -or $Row.client -cnotin @('fx','swing','web') -or
        $Row.path -cnotin @('ascii','cyrillic','unicode')) {throw 'PHASE_CONTEXT'}
    foreach ($name in 'New-ColdJournal','New-ColdInstrumentedHelper','Get-ColdCheckpoints','Assert-ColdCheckpoint',
        'Assert-ColdProtectedEvidence','Start-ColdProcess','Save-ColdHelperDiagnostics','Initialize-NativeDomainSession',
        'Get-ColdRecoveryObservation','Assert-ColdRecoveryEvidence','Assert-ColdSafeArgs','Get-NativeMainWindow','Assert-NativeMainWindow') {
        if (-not (Get-Command $name -CommandType Function -ErrorAction SilentlyContinue)) {throw "PHASE_DEPENDENCY $name"}
    }
    Assert-ColdCommand $Cold $Java @($Cold.baseManifests | ForEach-Object portableDir) $script:nativeTarget
    Assert-NativeLifecycleConfig $Life
    $basePin=@($Cold.baseManifests | Where-Object portableDir -CEQ $Source)
    if ($basePin.Count -ne 1 -or -not (Test-ColdInventoryEqual $Base (Read-ColdPinnedJson $basePin[0].manifest $basePin[0].sha256)) -or
        -not (Test-ColdInventoryEqual $Target (Read-ColdPinnedJson $Cold.targetManifest $Cold.targetManifestSha256))) {throw 'PHASE_MANIFEST_PIN'}
    if ($mapping.checkpoint -cnotin @(Get-ColdCheckpoints) -and
        -not ($mapping.phase -ceq 'WAITING' -and $mapping.checkpoint -ceq 'WAITING')) {throw 'PHASE_CHECKPOINT_UNMAPPED'}
    $original=[IO.File]::ReadAllText($Cold.helperScript)
    $instrumented=New-NativePhaseHelper $original $Cold.helperSha256
    $run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
    [void](Assert-ColdOwnedRun $run ([IO.Path]::GetTempPath()))
    $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
    $root=Join-Path (Join-Path $run $variants[$Row.path]) 'CashPrediction';$WorkDir=$run
    foreach ($protected in @($Source,$script:nativeTarget,$Life.artifactDir)) {
        [void](Get-ValidatedPortablePaths $protected $run $script:nativeProject $script:nativeProfile)
        Assert-PortableTreeHasNoLinks $protected
    }
    if (@(Get-CopyProcesses $Source).Count -or @(Get-CopyProcesses $script:nativeTarget).Count) {throw 'PHASE_SOURCE_LIVE'}
    $evidenceRoot=Resolve-PortableSafetyPath $Evidence
    if (-not [IO.Path]::GetDirectoryName($evidenceRoot).Equals((Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())),[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($evidenceRoot) -cnotmatch '^cp-native-(lifecycle|phase)-evidence-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {throw 'PHASE_EVIDENCE_SCOPE'}
    $cellEvidence=Join-Path $evidenceRoot ([IO.Path]::GetFileName($run));[void][IO.Directory]::CreateDirectory($cellEvidence)
    $Row | Add-Member workRoot $root -Force;$Row | Add-Member evidence $cellEvidence -Force
    $helper=$null;$identity=$null;$native=$null;$setupClient=$null;$setupServer=$null;$failure=$null;$cleanup=[Collections.Generic.List[string]]::new()
    $started=[datetime]::UtcNow;$powerShell=Resolve-PortableSafetyPath (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe')
    $node='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString()
    try {
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($root))
        Copy-Item -LiteralPath $Source -Destination $root -Recurse
        Assert-PortableTreeHasNoLinks $root;Assert-ColdNativeImage $root
        $updates=Join-Path $root 'CashMemory/Updates';[void][IO.Directory]::CreateDirectory($updates)
        $domain=Initialize-NativeDomainSession $root $Java $Cold $cellEvidence
        $Row | Add-Member domainEvidence $domain -Force
        $before=@(Assert-ColdTree $root $Base);$targetBefore=@(Assert-ColdTree $script:nativeTarget $Target)
        $userBefore=Get-NativeUserObject $root;$controlled=@(Get-ColdControlledInventory $root)
        if ($Row.phase -eq 'WAITING') {
            # Клиент начинает с целого old image БЕЗ journal/Ready; synthetic lease запрещён.
            foreach ($name in 'Start-NativeFixture','Start-NativeOwned','Connect-NativeClient','Close-NativeNormally','Stop-NativeFixture') {
                if (-not (Get-Command $name -CommandType Function -ErrorAction SilentlyContinue)) {throw "PHASE_DEPENDENCY $name"}
            }
            $setupServer=Start-NativeFixture $Life $Java 'offline-close' 0
            $script:nativeNode=$node
            $setupArgs=@(Get-NativeArguments $root $Row.client $node);$setupAt=[datetime]::UtcNow
            $setupClient=Start-NativeOwned (Join-Path $root (Get-ColdLauncherName $Row.client)) $setupArgs $root $setupServer.receipt.manifestUri -Web:($Row.client -eq 'web')
            if ($setupClient.process -isnot [Diagnostics.Process]) {throw 'PHASE_ACTUAL_PROCESS_REQUIRED'}
            Connect-NativeClient $setupClient $root $Row.client $setupAt $Timeout
            Assert-ColdUiReceipt $setupClient.ui $root $Row.client
            Write-ColdJson (Join-Path $cellEvidence 'waiting-client-live.json') ([ordered]@{scope='ACTUAL_OWNED_CLIENT_LEASE';
                launcher=$setupClient.identity;ui=$setupClient.ui;args=$setupArgs;startedAt=$setupAt.ToString('o')})
        }
        $ready=Join-Path $updates 'Ready';[void][IO.Directory]::CreateDirectory($ready)
        Copy-Item -LiteralPath $script:nativeTarget -Destination (Join-Path $ready 'tree') -Recurse
        [void](Assert-ColdTree (Join-Path $ready 'tree') $Target);Write-ColdJson (Join-Path $ready 'update.json') $Target
        $journal=New-ColdJournal $root $Base $Target;Write-ColdJson (Join-Path $updates 'install-journal.json') $journal
        [IO.File]::WriteAllText((Join-Path $updates 'apply-update.ps1'),$original,[Text.UTF8Encoding]::new($false))
        $faultHelper=Join-Path $updates 'phase-fault-helper.ps1'
        [IO.File]::WriteAllText($faultHelper,$instrumented,[Text.UTF8Encoding]::new($false))
        Write-ColdJson (Join-Path $updates 'phase-control.json') ([ordered]@{scenario=$Row.scenario;phase=$Row.phase;
            mode=$(if ($Row.scenario -eq 'helper-runtime-death') {'runtime-death'} else {$mapping.mode});error=$mapping.error;boundary=$mapping.boundary})
        Write-ColdJson (Join-Path $updates 'cold-control.json') ([ordered]@{checkpoint=$mapping.checkpoint;nonce=[guid]::NewGuid().ToString()})
        # Прогрев read-only provider до Kill не входит в recovery polling budget.
        $null=@(Get-NetTCPConnection -State Listen -ErrorAction Stop)
        $arguments=@('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',
            $faultHelper,'-InstallationRoot',$root,'-Diagnostics')
        if ($Row.phase -eq 'ROLLING_BACK') {$arguments+=@('-FaultPhase','INSTALLING')}
        $helper=Start-ColdProcess $powerShell $arguments $root -DiagnosticsDirectory $cellEvidence
        if ($helper -isnot [Diagnostics.Process]) {throw 'PHASE_ACTUAL_PROCESS_REQUIRED'}
        $identity=Get-ColdProcessReceipt $helper $root
        $checkpointPath=Join-Path $updates 'cold-checkpoint.json'
        Wait-NativeCondition {if ($helper.HasExited) {throw 'PHASE_CHECKPOINT_HELPER_EXIT'};Test-Path -LiteralPath $checkpointPath} $Timeout 'PHASE_CHECKPOINT_TIMEOUT'
        $checkpoint=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $checkpointPath -Raw)
        $durablePath=Join-Path $updates 'install-journal.json'
        $durable=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $durablePath -Raw)
        if ($durable.transactionId -cne $journal.transactionId) {throw 'PHASE_JOURNAL_CHANGED'}
        if ((Get-FileHash -LiteralPath $durablePath).Hash.ToLowerInvariant() -cne $checkpoint.journalSha256) {throw 'PHASE_JOURNAL_CHANGED'}
        Assert-NativePhaseCheckpoint $mapping $checkpoint $identity $root $durable
        if ($Row.phase -eq 'COMMITTED') {[void](Assert-ColdTree $root $Target)}
        if ($Row.phase -eq 'WAITING') {
            # Повторный native receipt доказывает живой lease ТОЙ ЖЕ JVM в момент durable WAITING.
            $live=Get-ColdUiReceipt $root $Row.client $setupAt
            if ($setupClient.uiProcess.HasExited) {throw 'PHASE_WAITING_OWNED_LEASE_GONE'}
            Assert-NativePhaseWaitingLease $setupClient.ui $live $root $Row.client
            Write-ColdJson (Join-Path $cellEvidence 'waiting-lease-at-checkpoint.json') $live
        }
        Copy-Item -LiteralPath $checkpointPath -Destination (Join-Path $cellEvidence 'checkpoint.json')
        Copy-Item -LiteralPath $durablePath -Destination (Join-Path $cellEvidence 'journal-at-fault.json')
        if ($Row.scenario -ne 'helper-runtime-death') {
            $faultPath=Join-Path $updates 'phased-fault.json'
            $fault=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $faultPath -Raw)
            Assert-NativePhaseFault $fault $mapping $checkpoint $identity $durable
            Copy-Item -LiteralPath $faultPath -Destination (Join-Path $cellEvidence 'phased-fault.json')
        }
        if ((Get-FileHash -LiteralPath $durablePath).Hash.ToLowerInvariant() -cne $checkpoint.journalSha256) {throw 'PHASE_JOURNAL_CHANGED'}
        Stop-ColdRetainedProcess $helper $identity $root
        if (-not $helper.HasExited -or $helper.ExitCode -eq 0) {throw 'PHASE_HELPER_DEATH_NOT_PROVED'}
        $helperKilledAt=[datetime]::UtcNow;$observationEpoch=$helperKilledAt;$setupGoneAt=$null
        $diagnostics=Save-ColdHelperDiagnostics $helper $identity $root $cellEvidence
        if ($null -ne $setupClient) {
            # Закрытие служит только setup removal, не ordinary-exit приёмкой этой клетки.
            $setupExit=Close-NativeNormally $setupClient $root $Row.client $Timeout $cellEvidence
            Stop-ColdRecoveryHelpers $root $powerShell $started
            if (@(Get-CopyProcesses $root).Count -ne 0 -or -not $setupClient.uiProcess.HasExited) {throw 'PHASE_WAITING_SETUP_STILL_ALIVE'}
            Write-ColdJson (Join-Path $cellEvidence 'waiting-client-removed.json') ([ordered]@{scope='FAULT_SETUP_REMOVAL_NOT_SIGNOFF';
                exit=$setupExit;helperIdentity=$identity;helperActualExit=$helper.ExitCode;remainingClients=0;goneAt=[datetime]::UtcNow.ToString('o')})
            [void](Stop-NativeFixture $setupServer $cellEvidence 'waiting-server')
            # Запись закрытой старой session разрешена только здесь, до fresh observer epoch.
            $controlled=@(Get-ColdControlledInventory $root)
            if (-not (Test-ColdInventoryEqual $userBefore (Get-NativeUserObject $root))) {throw 'PHASE_USER_CHANGED'}
            $setupGoneAt=[datetime]::UtcNow;$observationEpoch=$setupGoneAt
        }
        Write-ColdJson (Join-Path $cellEvidence 'helper-death.json') ([ordered]@{identity=$identity;
            actualExit=$helper.ExitCode;killedAt=$helperKilledAt.ToString('o');checkpoint=(Join-Path $cellEvidence 'checkpoint.json');actualDiagnostics=$diagnostics})
        if ($Row.scenario -eq 'helper-runtime-death') {
            Write-ColdJson (Join-Path $cellEvidence 'phased-fault.json') ([ordered]@{scenario=$Row.scenario;phase=$Row.phase;
                error=$mapping.error;checkpoint=$checkpoint;identity=$identity;actualExit=$helper.ExitCode})
        }
        # Никакого java -m вместо GUI: единственный recovery launcher - image exe.
        $nativeArgs=@('--home',$root)
        if ($Row.client -eq 'web') {$nativeArgs+=@('--no-browser','--no-window')}
        $samples=[Collections.Generic.List[object]]::new()
        $samples.Add((Get-ColdRecoveryObservation $root $started))
        $launchAt=[datetime]::UtcNow
        Write-ColdJson (Join-Path $cellEvidence 'recovery-epoch.json') ([ordered]@{helperKilledAt=$helperKilledAt.ToString('o');
            setupGoneAt=$(if ($null -ne $setupGoneAt) {$setupGoneAt.ToString('o')} else {$null});
            observationEpoch=$observationEpoch.ToString('o');freshLaunchAt=$launchAt.ToString('o')})
        $native=[pscustomobject]@{process=(Start-ColdProcess (Join-Path $root (Get-ColdLauncherName $Row.client)) $nativeArgs $root $node);
            identity=$null;ui=$null;uiProcess=$null}
        if ($native.process -isnot [Diagnostics.Process]) {throw 'PHASE_ACTUAL_PROCESS_REQUIRED'}
        $native.identity=Get-ColdProcessReceipt $native.process $root
        Wait-NativeCondition {
            $sample=Get-ColdRecoveryObservation $root $launchAt;$samples.Add($sample)
            if ($sample.journalBefore -and $sample.journalAfter -and $sample.controlledSha256 -cne (Get-ColdObjectHash $controlled)) {throw 'PHASE_EARLY_CONTROLLED_CHANGE'}
            if (-not (Test-Path -LiteralPath (Join-Path $updates 'install-journal.json'))) {
                $native.ui=Get-ColdUiReceipt $root $Row.client $launchAt
            }
            return $null -ne $native.ui
        } $Timeout 'PHASE_NATIVE_RECOVERY_NOT_READY'
        Assert-ColdUiReceipt $native.ui $root $Row.client
        Assert-ColdSafeArgs $nativeArgs $native.ui.args $root $Row.client $Target.commitSha
        $native.uiProcess=Open-PortableProcess ([int]$native.ui.pid)
        Assert-ColdProcessIdentity ([pscustomobject]@{ProcessId=$native.ui.pid;StartedAtTicks=$native.ui.startedAtTicks;
            ExecutablePath=$native.ui.executablePath;OwnedRoot=$root}) (Get-ColdProcessReceipt $native.uiProcess $root) $root $native.ui.executablePath
        if ($Row.client -ne 'web') {
            $window=Get-NativeMainWindow $native;Assert-NativeMainWindow $window $native.ui
            Write-ColdJson (Join-Path $cellEvidence 'physical-main-window.json') $window
        }
        $recovery=[ordered]@{schemaVersion=1;installationRoot=$root;transactionId=$journal.transactionId;
            pollingLimitMillis=1000;controlledSha256=(Get-ColdObjectHash $controlled);samples=@($samples.ToArray())}
        Assert-ColdRecoveryEvidence $recovery $root $journal.transactionId $observationEpoch.Ticks $launchAt.Ticks (Get-ColdUtcTicks $native.ui.observedAt)
        Write-ColdJson (Join-Path $cellEvidence 'recovery-observations.json') $recovery
        $Row | Add-Member executed $true -Force
        $command=Join-Path $cellEvidence 'native-launch.json'
        Write-ColdJson $command ([ordered]@{launcher=$native.identity;ui=$native.ui;args=$nativeArgs;startedAt=$launchAt.ToString('o')})
        $files=@(Get-ColdManagedInventory $root);$tree=Get-ColdTreeHash $files
        $chosen=if ($tree -ceq $Base.treeSha256) {$Base} elseif ($tree -ceq $Target.treeSha256) {$Target} else {throw 'PHASE_PARTIAL_TREE'}
        [void](Assert-ColdTree $root $chosen)
        $version=Get-ColdVersion $root
        if ($version.commitSha -cne $chosen.commitSha -or $version.releaseNumber -ne $chosen.releaseNumber) {throw 'PHASE_RECOVERED_VERSION'}
        $manifest=if ($chosen.commitSha -ceq $Base.commitSha) {$basePin[0].manifest} else {$Cold.targetManifest}
        $tools=@(Invoke-ColdTool $Cold $Java @('verify','--root',$root,'--manifest',$manifest) $cellEvidence $root)
        Stop-ColdRecoveryHelpers $root $powerShell $started
        Stop-ColdCopyProcesses $root
        if (-not $native.process.WaitForExit(5000) -or $native.process.ExitCode -ne 0) {throw 'PHASE_RECOVERY_LAUNCHER_EXIT'}
        Write-ColdJson (Join-Path $cellEvidence 'native-exit.json') ([ordered]@{launcher=$native.identity;
            actualExit=$native.process.ExitCode;ui=$native.ui;uiCleanup='identity-bound copy cleanup, not ordinary UI exit'})
        if (@(Get-CopyProcesses $root).Count) {throw 'PHASE_NATIVE_PROCESSES_ALIVE'}
        $after=@(Get-ColdManagedInventory $root);$afterTree=Get-ColdTreeHash $after
        $final=if ($afterTree -ceq $Base.treeSha256) {$Base} elseif ($afterTree -ceq $Target.treeSha256) {$Target} else {throw 'PHASE_PARTIAL_TREE'}
        [void](Assert-ColdTree $root $final)
        $version=Get-ColdVersion $root
        if ($version.commitSha -cne $final.commitSha -or $version.releaseNumber -ne $final.releaseNumber) {throw 'PHASE_RECOVERED_VERSION'}
        $manifest=if ($final.commitSha -ceq $Base.commitSha) {$basePin[0].manifest} else {$Cold.targetManifest}
        $tools+=@(Invoke-ColdTool $Cold $Java @('verify','--root',$root,'--manifest',$manifest) $cellEvidence $root)
        $userAfter=Get-NativeUserObject $root
        if (-not (Test-ColdInventoryEqual $userBefore $userAfter)) {throw 'PHASE_USER_CHANGED'}
        Assert-ColdControlledChanges $controlled @(Get-ColdControlledInventory $root) $Row.client $native.ui $root ([datetime]::UtcNow.Ticks)
        $phaseLog=@();$log=Join-Path $updates 'update-log.md'
        if (Test-Path -LiteralPath $log) {
            Copy-Item -LiteralPath $log -Destination (Join-Path $cellEvidence 'update-log.md')
            foreach ($line in Get-Content -LiteralPath $log) {if ($line -cmatch ' PHASE_([A-Z_]+)$') {$phaseLog+=@($Matches[1])}}
        }
        # Save-fault предшествует PHASE log: наличие фазы доказывает fault receipt, не выдуманный Log.
        $phaseLog+=@($Row.phase)
        $values=@{currentBefore=$before;currentAfter=$after;targetBefore=$targetBefore;targetAfter=@(Assert-ColdTree $script:nativeTarget $Target);
            userBefore=$userBefore;userAfter=$userAfter;httpTrace=@();phaseLog=$phaseLog}
        foreach ($key in $values.Keys) {$path=Join-Path $cellEvidence ($key+'.json');Write-ColdJson $path $values[$key];$Row | Add-Member $key $path -Force}
        Write-ColdJson (Join-Path $cellEvidence 'tool-receipts.json') $tools
        $fields=@{exe=(Join-Path $root (Get-ColdLauncherName $Row.client));args=$nativeArgs;command=$command;
            baseCommit=$Base.commitSha;baseRelease=$Base.releaseNumber;targetCommit=$Target.commitSha;targetRelease=$Target.releaseNumber;
            startedAt=$started.ToString('o');finishedAt=[datetime]::UtcNow.ToString('o');exitCode=0;skipped=0;failures=0;
            faultReceipt=(Join-Path $cellEvidence 'phased-fault.json');reason='';version=$version}
        foreach ($key in $fields.Keys) {$Row | Add-Member $key $fields[$key] -Force}
    } catch {$failure=$_.Exception.Message}
    finally {
        # Первичная ошибка сохраняется; cleanup никогда не маскирует её.
        if ($null -ne $identity) {
            try {Stop-ColdRetainedProcess $helper $identity $root} catch {$cleanup.Add($_.Exception.Message)}
            try {if ($helper.HasExited) {[void](Save-ColdHelperDiagnostics $helper $identity $root $cellEvidence)}} catch {$cleanup.Add($_.Exception.Message)}
        }
        try {Stop-ColdRecoveryHelpers $root $powerShell $started} catch {$cleanup.Add($_.Exception.Message)}
        try {Stop-ColdCopyProcesses $root} catch {$cleanup.Add($_.Exception.Message)}
        if ($null -ne $native) {if ($null -ne $native.uiProcess) {$native.uiProcess.Dispose()};$native.process.Dispose()}
        if ($null -ne $setupClient) {if ($null -ne $setupClient.uiProcess) {$setupClient.uiProcess.Dispose()};$setupClient.process.Dispose()}
        if ($null -ne $setupServer) {
            try {if (-not $setupServer.process.HasExited) {[void](Stop-NativeFixture $setupServer $cellEvidence 'cleanup-waiting-server')}} catch {$cleanup.Add($_.Exception.Message)}
            try {Stop-ColdRetainedProcess $setupServer.process $setupServer.identity $setupServer.owned} catch {$cleanup.Add($_.Exception.Message)}
            $setupServer.process.Dispose()
        }
        if ($null -ne $helper) {$helper.Dispose()}
        if ($cleanup.Count) {$Row | Add-Member cleanupFailure ($cleanup -join '; ') -Force}
        # Не удаляем clone/evidence. Только точный собственный registry UUID после успешного cleanup.
        if (-not $cleanup.Count) {
            try {$registry=Get-PortableRegistryPath $node;if (Test-Path -LiteralPath $registry) {Remove-Item -LiteralPath $registry -Recurse -Force}}
            catch {$cleanup.Add($_.Exception.Message);$Row | Add-Member cleanupFailure ($cleanup -join '; ') -Force}
        }
    }
    if ($failure -or $cleanup.Count) {
        $Row | Add-Member status 'FAIL' -Force;$Row | Add-Member failures 1 -Force
        $Row | Add-Member reason $(if ($failure) {$failure} else {$cleanup -join '; '}) -Force
    } else {$Row | Add-Member status 'PASS' -Force}
    Write-ColdJson (Join-Path $cellEvidence 'phase-result.json') $Row
    return $Row
}
