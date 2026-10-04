<#
.SYNOPSIS
Только mock-контракты и AST genuine pinned helper; native приёмка остаётся PENDING.
.DESCRIPTION
CommandFile указывает существующий bootstrap-commands.json. Процессы не запускаются,
helper не исполняется целиком, исходные pinned файлы не изменяются. Save/Boundary
исполняются только в дочерней mock-области с запрещёнными OS/IO providers.
#>
[CmdletBinding()]param([Parameter(Mandatory)][string]$CommandFile)
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$tokens=$null;$errors=$null
$coldPath=Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'
$coldPin=(Get-FileHash -LiteralPath $coldPath).Hash
$coldAst=[Management.Automation.Language.Parser]::ParseFile($coldPath,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'FIXTURE_COLD_PARSE'}
foreach ($name in 'New-ColdInstrumentedHelper','Get-ColdCheckpoints','Assert-ColdKeys','Test-ColdInteger','Test-ColdInventoryEqual',
    'Assert-ColdCheckpoint','Get-ColdTreeHash') {
    $nodes=@($coldAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
    if ($nodes.Count -ne 1) {throw 'FIXTURE_DEPENDENCY'}
    . ([scriptblock]::Create($nodes[0].Extent.Text))
}
. (Join-Path $PSScriptRoot 'NativeUpdatePhaseScenarios.ps1')
$config=Get-Content -LiteralPath $CommandFile -Raw | ConvertFrom-Json
$helperPin=(Get-FileHash -LiteralPath $config.helperScript).Hash.ToLowerInvariant()
if ($helperPin -cne $config.helperSha256) {throw 'FIXTURE_HELPER_PIN'}
$original=[IO.File]::ReadAllText($config.helperScript)
$checks=0;$negative=0

# Требуется точный код отказа, иначе посторонняя ошибка не считается regression PASS.
function Assert-PhaseReject([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "Fixture rejection expected $Code; actual $caught"}
    $script:checks++;$script:negative++
}

$plan=@(Get-NativePhaseScenarioPlan)
$phases=@('PREPARED','WAITING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK')
$evidenceJava=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java') -Raw
$phaseLine=[regex]::Match($evidenceJava,'PHASES = List\.of\(([^;]+)\);')
$canonical=@([regex]::Matches($phaseLine.Groups[1].Value,'"([A-Z_]+)"') | ForEach-Object {$_.Groups[1].Value})
if ($plan.Count -ne 15 -or -not (Test-ColdInventoryEqual $phases $canonical) -or @($plan | Where-Object mapped).Count -ne 15) {throw 'FIXTURE_CANONICAL_PHASE_PLAN'}
$checks++
foreach ($scenario in 'journal-fault','per-move-fault') {
    if (-not (Test-ColdInventoryEqual @($plan | Where-Object scenario -CEQ $scenario | ForEach-Object phase) $phases)) {throw 'FIXTURE_PLAN_SHRUNK'}
    $checks++
}
foreach ($mapping in @($plan | Where-Object mode -CEQ 'durable-barrier')) {
    $expectedCheckpoint=if ($mapping.phase -eq 'PREPARED') {'INITIAL'} else {$mapping.phase}
    if ($mapping.checkpoint -cne $expectedCheckpoint -or $mapping.error -cne 'OWNED_DURABLE_BARRIER_INTERRUPTION' -or
        $mapping.requiresLease -ne ($mapping.phase -eq 'WAITING') -or $mapping.boundary -cin @('Save','Boundary')) {throw 'FIXTURE_BARRIER_PRETENDS_MOVE'}
    $checks++
}
Assert-PhaseReject {Get-NativePhaseMapping 'journal-fault' 'BOOTSTRAPPING'} 'PHASE_SCENARIO_IDENTITY'
Assert-PhaseReject {Get-NativePhaseMapping 'helper-runtime-death' 'COMMITTED'} 'PHASE_SCENARIO_IDENTITY'
Assert-PhaseReject {New-NativePhaseHelper $original ('0'*64)} 'PHASE_HELPER_PIN'
$instrumented=New-NativePhaseHelper $original $helperPin
$injectedAst=[Management.Automation.Language.Parser]::ParseInput($instrumented,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'FIXTURE_INSTRUMENTED_PARSE'}
foreach ($name in 'Save','Boundary','ColdCheckpoint','PhaseScenarioColdCheckpoint','CapturePhaseFault') {
    if (@($injectedAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true)).Count -ne 1) {throw 'FIXTURE_INSTRUMENTED_ANCHOR'}
    $checks++
}

# Genuine production fault branches: все внешние действия ограничены mock providers.
& {
    foreach ($name in 'Save','Boundary','ColdCheckpoint','Phase','PhaseFault') {
        $node=@($injectedAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))[0]
        . ([scriptblock]::Create($node.Extent.Text))
    }
    $updates='C:\owned-fixture\CashMemory\Updates';$journalPath=Join-Path $updates 'install-journal.json'
    $Crash=$false;$JournalFailAt=0;$FaultAt=0;$FaultPhase=''
    function ReadJson([string]$Path) {if ($Path -ceq $journalPath) {return $script:fixtureDurable};return $script:fixtureControl}
    function AtomicJson {
        $script:fixtureWrites++;if ($script:fixtureWriterError) {throw 'REAL_WRITER_ERROR'}
        $script:fixtureDurable=[pscustomobject]@{phase=$script:journal.phase}
    }
    function Log {$script:fixtureLogs++}
    function PhaseScenarioColdCheckpoint {$script:fixtureDeathHooks++}
    function Stop-Process {throw 'FIXTURE_OS_KILL_FORBIDDEN'}
    function CapturePhaseFault([string]$Error,[string]$Boundary) {
        $script:fixtureCaptured=[pscustomobject]@{error=$Error;boundary=$Boundary;phase=$script:journal.phase}
        throw 'FIXTURE_CAPTURED_FAULT'
    }
    foreach ($mapping in @($plan | Where-Object mode -CEQ 'builtin-fault')) {
        $script:fixtureControl=$mapping;$script:phaseFaultArmed=$false;$script:fixtureCaptured=$null
        $script:fixtureWrites=0;$script:fixtureWriterError=$false;$script:journalWrites=0;$script:step=0
        $script:journal=[pscustomobject]@{phase=$mapping.phase;operations=@([pscustomobject]@{kind='BACKUP';path='app/a.jar';state='BEFORE'})}
        $script:fixtureDurable=[pscustomobject]@{phase=$mapping.phase}
        $action=if ($mapping.scenario -eq 'journal-fault') {{Save}} else {{Boundary}}
        Assert-PhaseReject $action 'FIXTURE_CAPTURED_FAULT'
        if ($script:fixtureCaptured.error -cne $mapping.error -or $script:fixtureCaptured.phase -cne $mapping.phase -or
            $script:fixtureWrites -ne 0 -or -not $script:phaseFaultArmed) {throw 'FIXTURE_BUILTIN_FAULT_NOT_REACHED'}
        $script:checks++
    }
    foreach ($mapping in @($plan | Where-Object mode -CEQ 'durable-barrier')) {
        $script:fixtureControl=$mapping;$script:phaseFaultArmed=$false;$script:fixtureCaptured=$null
        $script:fixtureWrites=0;$script:fixtureWriterError=$false;$script:journalWrites=0;$script:step=0;$script:fixtureLogs=0
        $prior=if ($mapping.phase -eq 'WAITING') {'PREPARED'} elseif ($mapping.phase -eq 'COMMITTED') {'VERIFYING'} else {'PREPARED'}
        $script:journal=[pscustomobject]@{phase=$prior;operations=@()}
        $script:fixtureDurable=[pscustomobject]@{phase=$prior}
        $action=if ($mapping.phase -eq 'PREPARED') {{PhaseFault 'PREPARED'}} else {{Phase $mapping.phase}}
        Assert-PhaseReject $action 'FIXTURE_CAPTURED_FAULT'
        $writes=if ($mapping.phase -eq 'PREPARED') {0} else {1}
        if ($script:fixtureCaptured.error -cne $mapping.error -or $script:fixtureCaptured.boundary -cne $mapping.boundary -or
            $script:fixtureDurable.phase -cne $mapping.phase -or $script:fixtureWrites -ne $writes -or
            $script:step -ne 0 -or $script:phaseFaultArmed) {throw 'FIXTURE_BARRIER_NOT_DURABLE'}
        $script:checks++
    }
    $script:fixtureControl=Get-NativePhaseMapping 'journal-fault' 'INSTALLING'
    $script:phaseFaultArmed=$false;$script:journalWrites=0;$script:fixtureWrites=0;$script:fixtureCaptured=$null
    $script:journal=[pscustomobject]@{phase='BACKING_UP';operations=@([pscustomobject]@{kind='BOOT_COPY';path='app/a.jar';state='BEFORE'})};Save
    if ($script:fixtureWrites -ne 1 -or $null -ne $script:fixtureCaptured) {throw 'FIXTURE_WRONG_PHASE_FAULT'}
    $script:checks++
    $script:fixtureWriterError=$true
    Assert-PhaseReject {Save} 'REAL_WRITER_ERROR'
    $script:fixtureWriterError=$false
    # Не fault первой phase-publishing записи: сначала та же фаза должна стать durable.
    $script:journal.phase='INSTALLING';$script:fixtureDurable.phase='BACKING_UP';$script:phaseFaultArmed=$false
    $script:fixtureCaptured=$null;$script:fixtureWrites=0;Save
    if ($script:fixtureWrites -ne 1 -or $script:fixtureDurable.phase -cne 'INSTALLING' -or $script:phaseFaultArmed) {throw 'FIXTURE_PHASE_PUBLISH_INTERRUPTED'}
    Assert-PhaseReject {Save} 'FIXTURE_CAPTURED_FAULT'
    $script:fixtureControl=Get-NativePhaseMapping 'per-move-fault' 'BACKING_UP'
    $script:phaseFaultArmed=$false;$script:step=0;$script:fixtureCaptured=$null
    $script:journal.operations[0].kind='BOOT_COPY';Boundary
    if ($script:phaseFaultArmed -or $null -ne $script:fixtureCaptured -or $script:step -ne 1) {throw 'FIXTURE_NONMOVE_FAULT'}
    $script:checks++
    $script:fixtureDeathHooks=0;ColdCheckpoint 'PHASE'
    if ($script:fixtureDeathHooks -ne 0) {throw 'FIXTURE_PREFAULT_CHECKPOINT'}
    $script:fixtureControl=Get-NativePhaseMapping 'helper-runtime-death' 'SESSION';ColdCheckpoint 'SAVE'
    if ($script:fixtureDeathHooks -ne 1) {throw 'FIXTURE_DEATH_CHECKPOINT_NOT_REUSED'}
    $script:checks++
}

$identity=[pscustomobject]@{ProcessId=731;StartedAtTicks=123450;ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'}
$operation=[pscustomobject]@{kind='BACKUP';path='app/a.jar';state='BEFORE'}
$checkpoint=[pscustomobject]@{installationRoot='C:\owned-fixture';transactionId=[guid]::NewGuid().ToString();
    phase='BACKING_UP';journalSha256=('a'*64);operation=$operation}
$journal=[pscustomobject]@{phase='BACKING_UP';operations=@($operation)}
$fault=[pscustomobject][ordered]@{schemaVersion=1;scenario='per-move-fault';phase='BACKING_UP';boundary='Boundary';
    error='INJECTED_FAILURE';installationRoot=$checkpoint.installationRoot;transactionId=$checkpoint.transactionId;
    pid=$identity.ProcessId;startedAtTicks=$identity.StartedAtTicks;executablePath=$identity.ExecutablePath;
    journalSha256=$checkpoint.journalSha256;durablePhase=$journal.phase;journalWrites=1;step=1;operation=$operation}
$mapping=Get-NativePhaseMapping 'per-move-fault' 'BACKING_UP'
Assert-NativePhaseFault $fault $mapping $checkpoint $identity $journal;$checks++
foreach ($field in 'scenario','phase','error','pid','startedAtTicks','executablePath','journalSha256','durablePhase','transactionId') {
    $bad=$fault | ConvertTo-Json -Depth 12 | ConvertFrom-Json
    $bad.$field=if ($field -in @('pid','startedAtTicks')) {$bad.$field+1} else {'foreign'}
    Assert-PhaseReject {Assert-NativePhaseFault $bad $mapping $checkpoint $identity $journal} 'PHASE_FAULT_IDENTITY'
}
foreach ($field in 'boundary','step','operation') {
    $bad=$fault | ConvertTo-Json -Depth 12 | ConvertFrom-Json
    switch ($field) {'boundary' {$bad.boundary='Save'} 'step' {$bad.step=0} 'operation' {$bad.operation.state='AFTER'}}
    Assert-PhaseReject {Assert-NativePhaseFault $bad $mapping $checkpoint $identity $journal} 'PHASE_FAULT_BOUNDARY'
}
$badJournal=[pscustomobject]@{phase='BACKING_UP';operations=@()}
Assert-PhaseReject {Assert-NativePhaseFault $fault $mapping $checkpoint $identity $badJournal} 'PHASE_FAULT_DURABILITY'
$wrongKind=$fault | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$wrongKind.operation.kind='CFG_SWITCH'
Assert-PhaseReject {Assert-NativePhaseFault $wrongKind $mapping $checkpoint $identity $journal} 'PHASE_FAULT_BOUNDARY'
$journalMapping=Get-NativePhaseMapping 'journal-fault' 'BACKING_UP'
$journalFault=$fault | ConvertTo-Json -Depth 12 | ConvertFrom-Json
$journalFault.scenario='journal-fault';$journalFault.error='INJECTED_JOURNAL_WRITE';$journalFault.boundary='Save'
Assert-NativePhaseFault $journalFault $journalMapping $checkpoint $identity $journal;$checks++
$previousJournal=[pscustomobject]@{phase='BOOTSTRAPPING';operations=@($operation)}
$journalFault.durablePhase='BOOTSTRAPPING'
Assert-PhaseReject {Assert-NativePhaseFault $journalFault $journalMapping $checkpoint $identity $previousJournal} 'PHASE_FAULT_IDENTITY'
# Неподтверждённые причины не становятся ожидаемым journal-fault.
$journalFault.error='REAL_WRITER_ERROR'
Assert-PhaseReject {Assert-NativePhaseFault $journalFault $journalMapping $checkpoint $identity $previousJournal} 'PHASE_FAULT_IDENTITY'

# Все пять новых mappings: точная durable phase, checkpoint и явное отсутствие file-move.
foreach ($barrier in @($plan | Where-Object mode -CEQ 'durable-barrier')) {
    $cp=[pscustomobject][ordered]@{schemaVersion=1;checkpoint=$barrier.checkpoint;installationRoot='C:\owned-fixture';
        transactionId=$checkpoint.transactionId;pid=$identity.ProcessId;startedAtTicks=$identity.StartedAtTicks;
        executablePath=$identity.ExecutablePath;hook='PHASE';phase=$barrier.phase;
        bootstrapState='INITIAL';publishState='NONE';operation=$null;journalSha256=('a'*64);
        bootstrapVerified=$false;bootstrapFiles=@();bootstrapTreeSha256=(Get-ColdTreeHash @())}
    $durable=[pscustomobject]@{phase=$barrier.phase;transactionId=$cp.transactionId;outcome='PENDING';
        operations=@();bootstrap=[pscustomobject]@{state='INITIAL'}}
    $bf=[pscustomobject][ordered]@{schemaVersion=1;scenario=$barrier.scenario;phase=$barrier.phase;boundary=$barrier.boundary;
        error=$barrier.error;installationRoot=$cp.installationRoot;transactionId=$cp.transactionId;
        pid=$identity.ProcessId;startedAtTicks=$identity.StartedAtTicks;executablePath=$identity.ExecutablePath;
        journalSha256=$cp.journalSha256;durablePhase=$durable.phase;journalWrites=0;step=0;operation=$null}
    if ($barrier.phase -eq 'COMMITTED') {$durable.outcome='UPDATED'}
    Assert-NativePhaseFault $bf $barrier $cp $identity $durable;$checks++
    if ($barrier.phase -in @('PREPARED','WAITING')) {
        Assert-NativePhaseCheckpoint $barrier $cp $identity $cp.installationRoot $durable;$checks++
        $wrongDurable=$durable | ConvertTo-Json -Depth 12 | ConvertFrom-Json;$wrongDurable.phase='INSTALLING'
        Assert-PhaseReject {Assert-NativePhaseCheckpoint $barrier $cp $identity $cp.installationRoot $wrongDurable} 'PHASE_CHECKPOINT_DURABILITY'
        $wrongCp=$cp | ConvertTo-Json -Depth 12 | ConvertFrom-Json;$wrongCp.phase='INSTALLING'
        Assert-PhaseReject {Assert-NativePhaseCheckpoint $barrier $wrongCp $identity $cp.installationRoot $durable} 'PHASE_CHECKPOINT_DURABILITY'
    }
    $wrongFault=$bf | ConvertTo-Json -Depth 12 | ConvertFrom-Json;$wrongFault.boundary='Boundary'
    Assert-PhaseReject {Assert-NativePhaseFault $wrongFault $barrier $cp $identity $durable} 'PHASE_FAULT_BOUNDARY'
    if ($barrier.phase -eq 'COMMITTED') {
        $durable.outcome='PENDING'
        Assert-PhaseReject {Assert-NativePhaseFault $bf $barrier $cp $identity $durable} 'PHASE_FAULT_BOUNDARY'
    }
}

# Только binding-контракт leases, без выдачи mock JSON за реально живой PID.
& {
    function Assert-ColdUiReceipt {$script:fixtureUiValidations++}
    $script:fixtureUiValidations=0
    $previous=[pscustomobject]@{pid=731;startedAtTicks=123450;lease=[pscustomobject]@{
        leaseId=[guid]::NewGuid().ToString();installationRoot='C:\owned-fixture';client='fx'}}
    $current=$previous | ConvertTo-Json -Depth 12 | ConvertFrom-Json
    Assert-NativePhaseWaitingLease $previous $current 'C:\owned-fixture' 'fx';$script:checks++
    if ($script:fixtureUiValidations -ne 2) {throw 'FIXTURE_WAITING_BYPASSED_UI_VALIDATOR'}
    Assert-PhaseReject {Assert-NativePhaseWaitingLease $previous $null 'C:\owned-fixture' 'fx'} 'PHASE_WAITING_OWNED_LEASE_GONE'
    foreach ($field in 'pid','birth','lease','root','client') {
        $bad=$current | ConvertTo-Json -Depth 12 | ConvertFrom-Json
        switch ($field) {
            'pid' {$bad.pid++} 'birth' {$bad.startedAtTicks++} 'lease' {$bad.lease.leaseId=[guid]::NewGuid().ToString()}
            'root' {$bad.lease.installationRoot='C:\foreign'} 'client' {$bad.lease.client='web'}
        }
        Assert-PhaseReject {Assert-NativePhaseWaitingLease $previous $bad 'C:\owned-fixture' 'fx'} 'PHASE_WAITING_OWNED_LEASE_GONE'
    }
}

if ((Get-FileHash -LiteralPath $coldPath).Hash -cne $coldPin -or
    (Get-FileHash -LiteralPath $config.helperScript).Hash.ToLowerInvariant() -cne $helperPin) {throw 'FIXTURE_FROZEN_INPUT_CHANGED'}
Write-Output "Phase scenario MOCK fixtures: PASS ($checks checks; $negative exact rejections; all 15 canonical mappings; 5 explicit durable barriers, no fabricated moves/leases; genuine pinned helper AST/builtin fault branches; native execution NOT RUN, native signoff PENDING)."
