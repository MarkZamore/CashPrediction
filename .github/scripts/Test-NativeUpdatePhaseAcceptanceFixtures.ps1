<#
.SYNOPSIS
Узкие mock receipt-contract fixtures: ни один mock не выдаёт native PASS.
.DESCRIPTION
Запуск: & .github/scripts/Test-NativeUpdatePhaseAcceptanceFixtures.ps1
Только AST чистых guards и новый acceptance. Никаких OS process/TCP/GUI providers.
Синтетические JSON для disk adapter создаются только в собственном UUID внутри Temp.
#>
[CmdletBinding()]param()
Set-StrictMode -Version 3;$ErrorActionPreference='Stop'
$frozen=@{}
# Читаем только необходимые definitions, не запускаем существующие runners.
foreach ($entry in @(
    @{file='Test-UpdateBootstrap.ps1';names=@('Assert-ColdKeys','Test-ColdInteger','Test-ColdInventoryEqual','Get-ColdTreeHash',
        'Get-ColdUtcTicks','ConvertFrom-ColdReceiptJson','Get-ColdLauncherName','Assert-ColdInventory','Assert-ColdImageInventory',
        'Assert-ColdCheckpoint','Test-ColdProtectedPayload','Assert-ColdProtectedEvidence','ConvertFrom-ColdCommandLine',
        'Assert-ColdUiReceipt','Assert-ColdSafeArgs','Assert-ColdRecoveryEvidence')},
    @{file='NativeUpdatePhaseScenarios.ps1';names=@('Get-NativePhaseMapping','Get-NativePhaseScenarioPlan',
        'Assert-NativePhaseCheckpoint','Assert-NativePhaseFault','Assert-NativePhaseWaitingLease')},
    @{file='Test-NativeUpdateLifecycle.ps1';names=@('Assert-NativeMainWindow')}
)) {
    $file=Join-Path $PSScriptRoot $entry.file;$frozen[$file]=(Get-FileHash -LiteralPath $file).Hash
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($file,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'FIXTURE_INPUT_PARSE'}
    foreach ($name in $entry.names) {
        $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($nodes.Count -ne 1) {throw 'FIXTURE_INPUT_ANCHOR'}
        . ([scriptblock]::Create($nodes[0].Extent.Text))
    }
}
if ($frozen[(Join-Path $PSScriptRoot 'NativeUpdatePhaseScenarios.ps1')] -cne
    '5648826EE802D3BC877FF0F64F534027E3777244B1F79391758748AED853BAA2') {throw 'FIXTURE_PHASE_NOT_FROZEN'}
. (Join-Path $PSScriptRoot 'NativeUpdatePhaseAcceptance.ps1')
$checks=0;$negative=0

# Только ожидаемый guard-code считается проверенным отрицательным случаем.
function Assert-AcceptanceReject([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "FIXTURE_REJECTION expected=$Code actual=$caught"}
    $script:checks++;$script:negative++
}

# Копия wire JSON сохраняет строки времени и arrays, как настоящий adapter.
function Copy-AcceptanceFixture($Value) {return ,(ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64))}

# Полный фиктивный portable inventory, не настоящие исполняемые файлы.
function New-AcceptanceInventory([string]$Marker) {
    $sorted=[Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($path in @('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe','app/.jpackage.xml',
        'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg',
        'app/cashprediction-core-1.jar','app/cashprediction-ui-fx-1.jar','app/cashprediction-ui-swing-1.jar','app/cashprediction-web-1.jar',
        'runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules')) {
        $key=[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($path))
        $sorted.Add($key,[pscustomobject][ordered]@{path=$path;sizeBytes=10L;sha256=($Marker*64);readOnly=$false})
    }
    return @($sorted.Values)
}
$old=New-AcceptanceInventory 'a';$new=New-AcceptanceInventory 'b'
$base=[pscustomobject]@{commitSha=('a'*40);releaseNumber=1001L;treeSha256=(Get-ColdTreeHash $old);files=$old}
$target=[pscustomobject]@{commitSha=('b'*40);releaseNumber=1003L;treeSha256=(Get-ColdTreeHash $new);files=$new}
$java='C:\Jdk25\bin\java.exe'
$cold=[pscustomobject]@{toolArguments=@('--module-path','C:\pinned\core.jar;C:\pinned\tools.jar','-m','ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool');
    targetManifest='C:\pinned\target.json';baseManifests=@([pscustomobject]@{manifest='C:\pinned\base.json'})}
$time=[datetime]::Parse('2026-10-04T00:00:00Z').ToUniversalTime()

# Каждый комплект явно synthetic; ни один fixture не вызывает native execution helper.
function New-AcceptanceFixture($Mapping,[string]$Client='web') {
    $root='C:\phase-fixture\CashPrediction';$exe=Join-Path $root (Get-ColdLauncherName $Client)
    $identity=[pscustomobject]@{ProcessId=701;StartedAtTicks=$time.AddMilliseconds(100).Ticks;
        ExecutablePath=(Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe');OwnedRoot=$root}
    $initial=$Mapping.phase -in @('PREPARED','WAITING')
    $phase=if ($Mapping.phase -eq 'SESSION') {'BOOTSTRAPPING'} else {$Mapping.phase}
    $kind=switch ($phase) {'BACKING_UP' {'BACKUP'} 'INSTALLING' {'INSTALL'} 'VERIFYING' {'CFG_SWITCH'} 'ROLLING_BACK' {'RESTORE'} default {'INSTALL'}}
    $operation=if ($initial -or $Mapping.mode -eq 'durable-barrier') {$null} else {[pscustomobject]@{kind=$kind;path='app/CashPrediction.cfg';state='BEFORE'}}
    $protected=@($base.files | Where-Object {Test-ColdProtectedPayload $_.path})
    $cp=[pscustomobject][ordered]@{schemaVersion=1;checkpoint=$Mapping.checkpoint;installationRoot=$root;
        transactionId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';pid=$identity.ProcessId;startedAtTicks=$identity.StartedAtTicks;
        executablePath=$identity.ExecutablePath;hook=$(if ($Mapping.phase -eq 'SESSION') {'SAVE'} else {'PHASE'});phase=$phase;
        bootstrapState=$(if ($initial) {'INITIAL'} else {'ACTIVE'});publishState='NONE';operation=$operation;journalSha256=('c'*64);
        bootstrapVerified=(-not $initial);bootstrapFiles=@(if (-not $initial) {$protected});
        bootstrapTreeSha256=$(if ($initial) {Get-ColdTreeHash @()} else {Get-ColdTreeHash $protected})}
    $journal=[pscustomobject]@{schemaVersion=2;installationRoot=$root;transactionId=$cp.transactionId;phase=$phase;
        oldFiles=$base.files;oldTreeSha256=$base.treeSha256;target=$target;bootstrap=[pscustomobject]@{state=$cp.bootstrapState;publishState=$cp.publishState};
        operations=@(if ($null -ne $operation) {$operation});outcome=$(if ($phase -eq 'COMMITTED') {'UPDATED'} else {'PENDING'})}
    $fault=if ($Mapping.phase -eq 'SESSION') {[pscustomobject]@{scenario=$Mapping.scenario;phase='SESSION';error=$Mapping.error;checkpoint=$cp;identity=$identity;actualExit=-1}}
        else {[pscustomobject][ordered]@{schemaVersion=1;scenario=$Mapping.scenario;phase=$phase;boundary=$Mapping.boundary;error=$Mapping.error;
            installationRoot=$root;transactionId=$cp.transactionId;pid=$identity.ProcessId;startedAtTicks=$identity.StartedAtTicks;
            executablePath=$identity.ExecutablePath;journalSha256=$cp.journalSha256;durablePhase=$phase;journalWrites=1;step=1;operation=$operation}}
    $args=@('--home',$root);if ($Client -eq 'web') {$args+=@('--no-browser','--no-window')}
    $ui=[pscustomobject]@{pid=702;startedAtTicks=$time.AddMilliseconds(1250).Ticks;executablePath=$exe;
        modules=@((Join-Path $root 'runtime/bin/server/jvm.dll'));witness=$(if ($Client -eq 'web') {
            [pscustomobject]@{kind='owned-http';port=8080;status=200;bodySha256=('d'*64);owningProcess=702}
        } else {[pscustomobject]@{kind='native-window';handle=99;title='CashPrediction - fixture'}});
        lease=[pscustomobject]@{schemaVersion=1;leaseId='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';pid=702;
            startedAtEpochMillis=([DateTimeOffset]::new($time.AddMilliseconds(1250))).ToUnixTimeMilliseconds();installationRoot=$root;client=$Client};
        commandLine=($exe+' '+(($args+@('--updated-from',$target.commitSha)) -join ' '));
        args=@($args+@('--updated-from',$target.commitSha));observedAt=$time.AddMilliseconds(1800).ToString('o')}
    $launcher=[pscustomobject]@{ProcessId=703;StartedAtTicks=$time.AddMilliseconds(1210).Ticks;ExecutablePath=$exe;OwnedRoot=$root}
    $toolArgs=@('verify','--root',$root,'--manifest',$cold.targetManifest)
    $payload=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $toolArgs -Compress)))
    $tools=@(foreach ($n in 1,2) {[pscustomobject]@{executable=$java;args=@($cold.toolArguments)+$toolArgs;
        effectiveArguments=@('-XX:-UsePerfData')+@($cold.toolArguments)+@('--arguments-base64',$payload);actualExit=0;
        stdout="C:\evidence\tool-$n.out";stderr="C:\evidence\tool-$n.err"}})
    $user=[pscustomobject]@{'CashMemory/NativeLifecycle.md'=[pscustomobject][ordered]@{path='CashMemory/NativeLifecycle.md';sizeBytes=14L;sha256=('e'*64);readOnly=$false}}
    $row=[pscustomobject]@{scenario=$Mapping.scenario;phase=$Mapping.phase;base='B1';client=$Client;path='ascii';status='PASS';executed=$true;
        exitCode=0;failures=0;skipped=0;reason='';workRoot=$root;exe=$exe;baseCommit=$base.commitSha;baseRelease=$base.releaseNumber;
        targetCommit=$target.commitSha;targetRelease=$target.releaseNumber;args=$args;startedAt=$time.ToString('o');finishedAt=$time.AddSeconds(2).ToString('o');
        version=[pscustomobject]@{commitSha=$target.commitSha;releaseNumber=$target.releaseNumber}}
    $epoch=$time.AddSeconds(1)
    $set=[pscustomobject]@{checkpoint=$cp;journal=$journal;journalSha256=$cp.journalSha256;fault=$fault;
        death=[pscustomobject]@{identity=$identity;actualExit=-1;killedAt=$epoch.ToString('o');checkpoint='C:\evidence\checkpoint.json';actualDiagnostics='C:\evidence\helper-diagnostics.json'};
        epoch=[pscustomobject]@{helperKilledAt=$epoch.ToString('o');setupGoneAt=$null;observationEpoch=$epoch.ToString('o');freshLaunchAt=$time.AddMilliseconds(1200).ToString('o')};
        launch=[pscustomobject]@{launcher=$launcher;ui=$ui;args=$args;startedAt=$time.AddMilliseconds(1200).ToString('o')};
        exit=[pscustomobject]@{launcher=$launcher;actualExit=0;ui=$ui;uiCleanup='identity-bound copy cleanup, not ordinary UI exit'};
        recovery=[pscustomobject]@{schemaVersion=1;installationRoot=$root;transactionId=$cp.transactionId;pollingLimitMillis=1000;controlledSha256=('f'*64);
            samples=@([pscustomobject]@{startedAt=$epoch.ToString('o');finishedAt=$time.AddMilliseconds(1100).ToString('o');journalBefore=$true;journalAfter=$true;visibleCount=0;controlledSha256=('f'*64)},
                [pscustomobject]@{startedAt=$time.AddMilliseconds(1400).ToString('o');finishedAt=$time.AddMilliseconds(1500).ToString('o');journalBefore=$false;journalAfter=$false;visibleCount=1;controlledSha256=('f'*64)})};
        currentBefore=$base.files;currentAfter=$target.files;targetBefore=$target.files;targetAfter=$target.files;userBefore=$user;userAfter=(Copy-AcceptanceFixture $user);
        tools=$tools;phaseLog=@($Mapping.phase);httpTrace=@()}
    if ($Client -ne 'web') {$set | Add-Member physicalWindow ([pscustomobject]@{handle=99;pid=702;exists=$true;visible=$true;enabled=$true;processMainHandle=99})}
    if ($Mapping.phase -eq 'WAITING') {
        $previous=Copy-AcceptanceFixture $ui;$previous.pid=704;$previous.startedAtTicks=$time.AddMilliseconds(500).Ticks
        $previous.lease.pid=704;$previous.lease.startedAtEpochMillis=([DateTimeOffset]::new($time.AddMilliseconds(500))).ToUnixTimeMilliseconds()
        $previous.observedAt=$time.AddMilliseconds(900).ToString('o')
        if ($Client -eq 'web') {$previous.witness.owningProcess=704}
        $set | Add-Member waitingLive ([pscustomobject]@{scope='ACTUAL_OWNED_CLIENT_LEASE';launcher=$launcher;ui=$previous;args=$args;startedAt=$time.AddMilliseconds(400).ToString('o')})
        $set | Add-Member waitingAtCheckpoint (Copy-AcceptanceFixture $previous)
        $set | Add-Member waitingRemoved ([pscustomobject]@{scope='FAULT_SETUP_REMOVAL_NOT_SIGNOFF';exit=[pscustomobject]@{};
            helperIdentity=$identity;helperActualExit=-1;remainingClients=0;goneAt=$time.AddMilliseconds(1050).ToString('o')})
        $set.epoch.setupGoneAt=$time.AddMilliseconds(1060).ToString('o');$set.epoch.observationEpoch=$set.epoch.setupGoneAt
        $set.recovery.samples[0].startedAt=$set.epoch.setupGoneAt
    }
    return [pscustomobject]@{row=$row;set=$set}
}
$plan=@(Get-NativePhaseScenarioPlan)
foreach ($mapping in $plan) {
    $fixture=New-AcceptanceFixture $mapping
    $result=Assert-NativePhaseReceiptSet $fixture.row $fixture.set $base $target $cold $java
    if ($result.status -cne 'RECEIPT_CONTRACT_VALIDATED' -or $result.nativeExecutionPerformed -isnot [bool] -or
        $result.nativeExecutionPerformed -or $result.wholeMatrixSignoff -cne 'NOT_ASSESSED') {throw 'FIXTURE_NATIVE_PASS_FROM_MOCK'}
    $checks++
}
foreach ($client in 'fx','swing') {
    $fixture=New-AcceptanceFixture $plan[0] $client
    [void](Assert-NativePhaseReceiptSet $fixture.row $fixture.set $base $target $cold $java);$checks++
    $fixture.set.physicalWindow.enabled=$false
    Assert-AcceptanceReject {Assert-NativePhaseReceiptSet $fixture.row $fixture.set $base $target $cold $java} 'NATIVE_NORMAL_CLOSE_OWNER_DISABLED'
}
$good=New-AcceptanceFixture $plan[0]
foreach ($mutation in @(
    @{code='PHASE_ACCEPT_PENDING_OR_FAILED';edit={param($f) $f.row.status='PENDING'}},
    @{code='PHASE_ACCEPT_PENDING_OR_FAILED';edit={param($f) $f.row.executed='true'}},
    @{code='PHASE_ACCEPT_PENDING_OR_FAILED';edit={param($f) $f.row.exitCode='0'}},
    @{code='PHASE_ACCEPT_PENDING_OR_FAILED';edit={param($f) $f.row.skipped=1}},
    @{code='PHASE_ACCEPT_PENDING_OR_FAILED';edit={param($f) $f.row.failures=1}},
    @{code='PHASE_ACCEPT_MISSING';edit={param($f) $f.set.PSObject.Properties.Remove('checkpoint')}},
    @{code='PHASE_ACCEPT_CONTRADICTION';edit={param($f) $f.row | Add-Member cleanupFailure 'gone'}},
    @{code='PHASE_ACCEPT_CONTRADICTION';edit={param($f) $f.row.reason='UNMAPPED'}},
    @{code='PHASE_ACCEPT_PINS';edit={param($f) $f.row.targetCommit=('c'*40)}},
    @{code='PHASE_ACCEPT_PROCESS_IDENTITY';edit={param($f) $f.set.death.identity.ProcessId='701'}},
    @{code='COLD_CHECKPOINT_IDENTITY';edit={param($f) $f.set.checkpoint.pid++}},
    @{code='COLD_CHECKPOINT_IDENTITY';edit={param($f) $f.set.checkpoint.startedAtTicks++}},
    @{code='COLD_CHECKPOINT_IDENTITY';edit={param($f) $f.set.checkpoint.executablePath='C:\foreign.exe'}},
    @{code='PHASE_ACCEPT_INTERRUPTION';edit={param($f) $f.set.death.actualExit=0}},
    @{code='PHASE_ACCEPT_JOURNAL';edit={param($f) $f.set.journalSha256=('d'*64)}},
    @{code='PHASE_CHECKPOINT_DURABILITY';edit={param($f) $f.set.journal.phase='WAITING'}},
    @{code='COLD_CHECKPOINT_IDENTITY';edit={param($f) $f.set.checkpoint.checkpoint='PREPARED'}},
    @{code='PHASE_FAULT_IDENTITY';edit={param($f) $f.set.fault.durablePhase='WAITING'}},
    @{code='PHASE_FAULT_BOUNDARY';edit={param($f) $f.set.fault.boundary='Boundary'}},
    @{code='PHASE_ACCEPT_TIME';edit={param($f) $f.set.epoch.freshLaunchAt=$time.ToString('o')}},
    @{code='PHASE_ACCEPT_NATIVE_LAUNCH';edit={param($f) $f.set.exit.actualExit=1}},
    @{code='PHASE_ACCEPT_PROCESS_IDENTITY';edit={param($f) $f.set.launch.launcher.ExecutablePath='C:\Jdk\java.exe'}},
    @{code='COLD_REPORT_LAUNCH_IDENTITY';edit={param($f) $f.set.launch.ui.lease.pid++;$f.set.exit.ui=$f.set.launch.ui}},
    @{code='COLD_REPORT_LAUNCH_IDENTITY';edit={param($f) $f.set.launch.ui.witness.status=500;$f.set.exit.ui=$f.set.launch.ui}},
    @{code='COLD_REPORT_RECOVERY_EVIDENCE';edit={param($f) $f.set.recovery.samples[0].visibleCount=1}},
    @{code='PHASE_ACCEPT_TREE';edit={param($f) $f.set.targetAfter[0].readOnly=$true}},
    @{code='PHASE_ACCEPT_PARTIAL_TREE';edit={param($f) $f.set.currentAfter[0].sha256=('c'*64)}},
    @{code='PHASE_ACCEPT_VERSION';edit={param($f) $f.row.version.releaseNumber++}},
    @{code='PHASE_ACCEPT_USER_CHANGED';edit={param($f) $f.set.userAfter.'CashMemory/NativeLifecycle.md'.sha256=('f'*64)}},
    @{code='PHASE_ACCEPT_USER_CHANGED';edit={param($f) $f.set.userAfter=[pscustomobject]@{};$f.set.userBefore=[pscustomobject]@{}}},
    @{code='PHASE_ACCEPT_TOOL';edit={param($f) $f.set.tools[0].actualExit=1}},
    @{code='PHASE_ACCEPT_TOOL';edit={param($f) $f.set.tools=@($f.set.tools[0])}},
    @{code='PHASE_ACCEPT_TOOL';edit={param($f) $f.set.tools[1].stdout=$f.set.tools[0].stdout}},
    @{code='PHASE_ACCEPT_TOOL';edit={param($f) $f.set.tools[0].args[-1]='C:\foreign.json'}},
    @{code='PHASE_ACCEPT_EVIDENCE_ARRAY';edit={param($f) $f.set.phaseLog=@('INSTALLING')}}
)) {
    $bad=Copy-AcceptanceFixture $good;& $mutation.edit $bad
    Assert-AcceptanceReject {Assert-NativePhaseReceiptSet $bad.row $bad.set $base $target $cold $java} $mutation.code
}
# Полный ORIGINAL outcome допустим только с согласованными версиями и двумя verify исходной manifest.
$original=Copy-AcceptanceFixture $good;$original.set.currentAfter=$base.files;$original.row.version=[pscustomobject]@{commitSha=$base.commitSha;releaseNumber=$base.releaseNumber}
foreach ($tool in $original.set.tools) {
    $tool.args[-1]=$cold.baseManifests[0].manifest
    $tool.effectiveArguments[-1]=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject @($tool.args | Select-Object -Skip 4) -Compress)))
}
if ((Assert-NativePhaseReceiptSet $original.row $original.set $base $target $cold $java).treeOutcome -cne 'ORIGINAL') {throw 'FIXTURE_ORIGINAL'};$checks++
foreach ($mapping in @($plan | Where-Object phase -CEQ 'COMMITTED')) {
    $bad=New-AcceptanceFixture $mapping;$bad.set.fault.boundary='MADE_UP_MOVE'
    Assert-AcceptanceReject {Assert-NativePhaseReceiptSet $bad.row $bad.set $base $target $cold $java} 'PHASE_FAULT_BOUNDARY'
}
$waiting=New-AcceptanceFixture ($plan | Where-Object { $_.scenario -ceq 'journal-fault' -and $_.phase -ceq 'WAITING' })
foreach ($mutation in @(
    @{code='PHASE_ACCEPT_MISSING';edit={param($f) $f.set.PSObject.Properties.Remove('waitingAtCheckpoint')}},
    @{code='PHASE_ACCEPT_WAITING';edit={param($f) $f.set.waitingRemoved.remainingClients=1}},
    @{code='PHASE_ACCEPT_WAITING';edit={param($f) $f.set.waitingRemoved.scope='ordinary-exit'}},
    @{code='PHASE_WAITING_OWNED_LEASE_GONE';edit={param($f) $f.set.waitingAtCheckpoint.pid++;$f.set.waitingAtCheckpoint.lease.pid++;$f.set.waitingAtCheckpoint.witness.owningProcess++}},
    @{code='PHASE_ACCEPT_WAITING';edit={param($f) $f.set.waitingRemoved.goneAt=$time.AddMilliseconds(1300).ToString('o')}}
)) {
    $bad=Copy-AcceptanceFixture $waiting;& $mutation.edit $bad
    Assert-AcceptanceReject {Assert-NativePhaseReceiptSet $bad.row $bad.set $base $target $cold $java} $mutation.code
}
$death=New-AcceptanceFixture $plan[-1];$death.set.fault.actualExit=0
Assert-AcceptanceReject {Assert-NativePhaseReceiptSet $death.row $death.set $base $target $cold $java} 'PHASE_ACCEPT_INTERRUPTION'
$foreignMove=New-AcceptanceFixture ($plan | Where-Object { $_.scenario -ceq 'per-move-fault' -and $_.phase -ceq 'INSTALLING' })
$foreignMove.set.journal.operations[0].path='CashMemory/private.md'
Assert-AcceptanceReject {Assert-NativePhaseReceiptSet $foreignMove.row $foreignMove.set $base $target $cold $java} 'PHASE_ACCEPT_JOURNAL'
$badCommitted=New-AcceptanceFixture ($plan | Where-Object { $_.scenario -ceq 'per-move-fault' -and $_.phase -ceq 'COMMITTED' })
$badCommitted.set.currentAfter=$base.files
Assert-AcceptanceReject {Assert-NativePhaseReceiptSet $badCommitted.row $badCommitted.set $base $target $cold $java} 'PHASE_ACCEPT_TREE'

# Реальный disk adapter над явно MOCK bytes: проверяет filenames/hash pins, но не запускает native.
$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ('cp-phase-acceptance-fixtures-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($fixtureRoot)
function Write-AcceptanceMock([string]$Name,$Value) {
    $path=Join-Path $fixtureRoot $Name
    [IO.File]::WriteAllText($path,(ConvertTo-Json -InputObject $Value -Depth 64),[Text.UTF8Encoding]::new($false))
    return $path
}
$disk=Copy-AcceptanceFixture $good
$disk.row | Add-Member evidence $fixtureRoot
foreach ($field in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','phaseLog','httpTrace') {
    $disk.row | Add-Member $field (Write-AcceptanceMock ($field+'.json') $disk.set.$field)
}
$disk.row | Add-Member command (Join-Path $fixtureRoot 'native-launch.json')
$disk.row | Add-Member faultReceipt (Join-Path $fixtureRoot 'phased-fault.json')
$disk.set.death.checkpoint=Join-Path $fixtureRoot 'checkpoint.json'
$disk.set.death.actualDiagnostics=Join-Path $fixtureRoot 'helper-diagnostics.json'
$diskJava=(Get-Command java -CommandType Application).Source
$diskCold=Copy-AcceptanceFixture $cold
$baseFile=Write-AcceptanceMock 'base-manifest.json' $base;$targetFile=Write-AcceptanceMock 'target-manifest.json' $target
$diskCold.baseManifests=@([pscustomobject]@{manifest=$baseFile;sha256=(Get-FileHash -LiteralPath $baseFile).Hash.ToLowerInvariant()})
$diskCold.targetManifest=$targetFile
$diskCold | Add-Member targetManifestSha256 (Get-FileHash -LiteralPath $targetFile).Hash.ToLowerInvariant()
$helperFile=Join-Path $fixtureRoot 'mock-helper.txt'
[IO.File]::WriteAllText($helperFile,'MOCK_ONLY_NOT_EXECUTABLE',[Text.UTF8Encoding]::new($false))
$diskCold | Add-Member helperScript $helperFile
$diskCold | Add-Member helperSha256 (Get-FileHash -LiteralPath $helperFile).Hash.ToLowerInvariant()
$diskCold | Add-Member runtimeSha256 (Get-FileHash -LiteralPath $diskJava).Hash.ToLowerInvariant()
$jarFiles=@(foreach ($name in 'mock-core.jar','mock-tools.jar') {
    $path=Join-Path $fixtureRoot $name;[IO.File]::WriteAllText($path,'MOCK_ONLY_NOT_JAVA_CODE',[Text.UTF8Encoding]::new($false))
    [pscustomobject]@{path=$path;sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
})
$diskCold | Add-Member toolFiles $jarFiles
$diskCold.toolArguments[1]=(@($jarFiles | ForEach-Object path) -join ';')
foreach ($tool in $disk.set.tools) {
    $tool.executable=$diskJava
    $tool.args=@($diskCold.toolArguments)+@('verify','--root',$disk.row.workRoot,'--manifest',$targetFile)
    $payload=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject @($tool.args | Select-Object -Skip 4) -Compress)))
    $tool.effectiveArguments=@('-XX:-UsePerfData')+@($diskCold.toolArguments)+@('--arguments-base64',$payload)
    $tool.stdout=Join-Path $fixtureRoot ([IO.Path]::GetFileName($tool.stdout))
    $tool.stderr=Join-Path $fixtureRoot ([IO.Path]::GetFileName($tool.stderr))
    [IO.File]::WriteAllText($tool.stdout,'MOCK_NOT_EXECUTED');[IO.File]::WriteAllText($tool.stderr,'MOCK_NOT_EXECUTED')
}
$journalFile=Write-AcceptanceMock 'journal-at-fault.json' $disk.set.journal
$digest=(Get-FileHash -LiteralPath $journalFile).Hash.ToLowerInvariant()
$disk.set.checkpoint.journalSha256=$digest;$disk.set.fault.journalSha256=$digest
$diagOut=Join-Path $fixtureRoot 'helper.stdout.txt';$diagErr=Join-Path $fixtureRoot 'helper.stderr.txt'
[IO.File]::WriteAllText($diagOut,'MOCK_NOT_EXECUTED');[IO.File]::WriteAllText($diagErr,'MOCK_NOT_EXECUTED')
$diag=[pscustomobject]@{schemaVersion=1;identity=$disk.set.death.identity;actualExit=$disk.set.death.actualExit;
    actualDiagnostics=[pscustomobject]@{stdout=$diagOut;stderr=$diagErr;drained=$true;failure=$null}}
[void](Write-AcceptanceMock 'helper-diagnostics.json' $diag)
foreach ($entry in @{checkpoint='checkpoint.json';fault='phased-fault.json';death='helper-death.json';epoch='recovery-epoch.json';
    launch='native-launch.json';exit='native-exit.json';recovery='recovery-observations.json';tools='tool-receipts.json'}.GetEnumerator()) {
    [void](Write-AcceptanceMock $entry.Value $disk.set.($entry.Key))
}
[void](Write-AcceptanceMock 'phase-result.json' $disk.row)
$adapter=Assert-NativePhaseAcceptance $disk.row $base $target $diskCold $diskJava
if ($adapter.status -cne 'RECEIPT_CONTRACT_VALIDATED' -or $adapter.nativeExecutionPerformed) {throw 'FIXTURE_ADAPTER_NATIVE_CLAIM'};$checks++
$badRow=Copy-AcceptanceFixture $disk.row;$badRow.status='PENDING'
Assert-AcceptanceReject {Assert-NativePhaseAcceptance $badRow $base $target $diskCold $diskJava} 'PHASE_ACCEPT_SAVED_RESULT'
$badRow=Copy-AcceptanceFixture $disk.row;$badRow.command=Join-Path $fixtureRoot 'other-launch.json'
[void](Write-AcceptanceMock 'phase-result.json' $badRow)
Assert-AcceptanceReject {Assert-NativePhaseAcceptance $badRow $base $target $diskCold $diskJava} 'PHASE_ACCEPT_ARTIFACT_BINDING'
[void](Write-AcceptanceMock 'phase-result.json' $disk.row)
$tampered=Copy-AcceptanceFixture $disk.set.journal;$tampered.outcome='BROKEN'
[void](Write-AcceptanceMock 'journal-at-fault.json' $tampered)
Assert-AcceptanceReject {Assert-NativePhaseAcceptance $disk.row $base $target $diskCold $diskJava} 'PHASE_ACCEPT_JOURNAL'
[void](Write-AcceptanceMock 'journal-at-fault.json' $disk.set.journal)
$diag.actualDiagnostics.drained=$false;[void](Write-AcceptanceMock 'helper-diagnostics.json' $diag)
Assert-AcceptanceReject {Assert-NativePhaseAcceptance $disk.row $base $target $diskCold $diskJava} 'PHASE_ACCEPT_DIAGNOSTICS'
$diag.actualDiagnostics.drained=$true;[void](Write-AcceptanceMock 'helper-diagnostics.json' $diag)
[IO.File]::WriteAllText($jarFiles[0].path,'MOCK_TAMPERED')
Assert-AcceptanceReject {Assert-NativePhaseAcceptance $disk.row $base $target $diskCold $diskJava} 'PHASE_ACCEPT_PINS'
Assert-AcceptanceReject {Read-PhaseAcceptanceArtifact $fixtureRoot (Join-Path $PSScriptRoot 'NativeUpdatePhaseAcceptance.ps1')} 'PHASE_ACCEPT_ARTIFACT_SCOPE'
Assert-AcceptanceReject {Read-PhaseAcceptanceArtifact $fixtureRoot '..\outside.json'} 'PHASE_ACCEPT_PATH'
Write-Output "Mock disk artifacts retained: $fixtureRoot (MOCK_ONLY, not native evidence)."
foreach ($file in $frozen.Keys) {if ((Get-FileHash -LiteralPath $file).Hash -cne $frozen[$file]) {throw 'FIXTURE_FROZEN_INPUT_CHANGED'}}
Write-Output "Phase acceptance MOCK fixtures: PASS ($checks checks; $negative exact rejections). Native/GUI/processes NOT RUN; native signoff PENDING."
