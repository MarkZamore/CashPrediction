<#
.SYNOPSIS
Unit контракты acceptance и collector. Синтетические receipts никогда не native PASS.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdateConcurrentAcceptance.ps1')
Import-NativeConcurrentAcceptanceGuards
$script:checks=0

# Exact assertion не скрывает случайный ранний отказ guard.
function Assert-AcceptanceFixture([bool]$Value,[string]$Name) {
    if (-not $Value) {throw ('ACCEPTANCE_FIXTURE '+$Name)};$script:checks++
}

# Времена фиксированы: fixtures не спят и не зависят от текущей native сессии.
function Get-FixtureTime([int]$Second) {return [datetime]::new(2026,10,4,0,0,0,[DateTimeKind]::Utc).AddSeconds($Second).ToString('o')}

# Mock UI предназначен исключительно для pure wire guards.
function New-AcceptanceUi([int]$Number,[string]$Client,[string]$Root) {
    $birth=Get-ColdUtcTicks (Get-FixtureTime 1);$exe=Join-Path $Root (Get-ColdLauncherName $Client)
    $argv=@('--test-api','--home',$Root,'--registry-node','ru/cashprediction/selftest/11111111-1111-1111-1111-111111111111')
    if ($Client -ceq 'web') {$argv+=@('--no-browser','--no-window')}
    $lease=[pscustomobject][ordered]@{schemaVersion=1;leaseId=('00000000-0000-0000-0000-'+$Number.ToString('000000000000'));
        pid=5000+$Number;startedAtEpochMillis=([DateTimeOffset]::new([datetime]::new($birth,[DateTimeKind]::Utc))).ToUnixTimeMilliseconds();installationRoot=$Root;client=$Client}
    $witness=if ($Client -ceq 'web') {[pscustomobject]@{kind='owned-http';port=19000;status=200;bodySha256=('a'*64);owningProcess=5000+$Number}} else {
        [pscustomobject]@{kind='native-window';handle=7000+$Number;title='CashPrediction - fixture'}}
    return [pscustomobject]@{pid=5000+$Number;startedAtTicks=$birth;executablePath=$exe;modules=@((Join-Path $Root 'runtime/bin/server/jvm.dll'));
        witness=$witness;lease=$lease;commandLine=('"'+$exe+'" '+(($argv | ForEach-Object {'"'+$_+'"'}) -join ' '));args=$argv;observedAt=(Get-FixtureTime 2)}
}

# Отдельная identity сохраняет имена полей actual retained-process receipt.
function New-AcceptanceIdentity($Ui) {
    return [pscustomobject]@{ProcessId=$Ui.pid;StartedAtTicks=$Ui.startedAtTicks;ExecutablePath=$Ui.executablePath;OwnedRoot=$Ui.lease.installationRoot}
}

# Mock alive не вызывает Get-Process/CIM и никогда не пишет actual evidence.
function New-AcceptanceAlive($Ui,[int]$Second) {
    return [pscustomobject]@{ui=$Ui;identity=(New-AcceptanceIdentity $Ui);lease=$Ui.lease;
        leasePath=(Join-Path $Ui.lease.installationRoot ('CashMemory/Updates/processes/'+$Ui.lease.leaseId+'.json'));observedUtc=(Get-FixtureTime $Second)}
}

# Серия wire snapshots проверяет длительность и состав survivors, не unit sleep proof.
function New-AcceptanceBarrier($Uis,[int]$Start,[string]$Hash) {
    $samples=@()
    foreach ($offset in 0,3) {$samples+=@([pscustomobject]@{elapsedMillis=100L+1000*$offset;observedUtc=(Get-FixtureTime ($Start+$offset));
        alive=@($Uis | ForEach-Object {New-AcceptanceAlive $_ ($Start+$offset)});treeSha256=$Hash})}
    return [pscustomobject]@{scope='LIVE_CLIENT_LEASE_TREE_BARRIER';windowMillis=3000;status='OBSERVED';elapsedMillis=3100;samples=$samples}
}

# Полный синтетический bundle даёт только SATISFIED, никогда native PASS.
function New-AcceptanceBundle {
    $run=Join-Path ([IO.Path]::GetTempPath()) 'run-11111111-1111-1111-1111-111111111111'
    $root=Join-Path $run 'plain/CashPrediction';$peerRoot=Join-Path $run 'independent-root/CashPrediction'
    $baseFiles=@([pscustomobject][ordered]@{path='CashPrediction.exe';sizeBytes=1L;sha256=('a'*64);readOnly=$false})
    $targetFiles=@([pscustomobject][ordered]@{path='CashPrediction.exe';sizeBytes=2L;sha256=('b'*64);readOnly=$true})
    $script:fixtureBase=[pscustomobject]@{releaseNumber=1;commitSha=('a'*40);treeSha256=(Get-ColdTreeHash $baseFiles);files=$baseFiles}
    $script:fixtureTarget=[pscustomobject]@{releaseNumber=2;commitSha=('b'*40);treeSha256=(Get-ColdTreeHash $targetFiles);files=$targetFiles}
    $uis=@((New-AcceptanceUi 1 'fx' $root),(New-AcceptanceUi 2 'swing' $root),(New-AcceptanceUi 3 'web' $root));$peer=New-AcceptanceUi 4 'fx' $peerRoot
    $b=[ordered]@{}
    $b.cell=[pscustomobject]@{scenario='three-clients-pid-root-isolation';phase='SESSION';executed=$true;status='PENDING';exitCode=0;skipped=0;failures=0;
        baseRelease=1;baseCommit=('a'*40);targetRelease=2;targetCommit=('b'*40);runRoot=$run;finishedAt=(Get-FixtureTime 47)}
    for ($i=0;$i -lt 3;$i++) {$b['launch'+$i]=[pscustomobject]@{ui=$uis[$i];launcher=(New-AcceptanceIdentity $uis[$i]);args=$uis[$i].args;startedAt=(Get-FixtureTime 1);manifestUri='http://127.0.0.1:19001/update.json'}}
    $b.peerLaunch=[pscustomobject]@{root=$peerRoot;ui=$peer;launcher=(New-AcceptanceIdentity $peer);args=$peer.args}
    $b.exit0=[pscustomobject]@{pid=$uis[0].pid;startedAtTicks=$uis[0].startedAtTicks;exitCode=0;exitedUtc=(Get-FixtureTime 20);remainingUi=2;survivors=@($uis[1],$uis[2]);kind='ordinary-intermediate-no-restart'}
    $b.exit1=[pscustomobject]@{pid=$uis[1].pid;startedAtTicks=$uis[1].startedAtTicks;exitCode=0;exitedUtc=(Get-FixtureTime 26);remainingUi=1;survivors=@($uis[2]);kind='ordinary-intermediate-no-restart'}
    $b.exitFinal=[pscustomobject]@{pid=$uis[2].pid;startedAtTicks=$uis[2].startedAtTicks;exitCode=0;exitedUtc=(Get-FixtureTime 32);remainingClients=0;kind='ordinary-no-restart'}
    $b.peerExit=[pscustomobject]@{pid=$peer.pid;startedAtTicks=$peer.startedAtTicks;exitCode=0;exitedUtc=(Get-FixtureTime 45);remainingClients=0;kind='ordinary-no-restart'}
    $b.barrierAll=New-AcceptanceBarrier $uis 4 $script:fixtureBase.treeSha256
    $b.barrierStale=New-AcceptanceBarrier $uis 12 $script:fixtureBase.treeSha256
    $b.barrierAfter0=New-AcceptanceBarrier @($uis[1],$uis[2]) 21 $script:fixtureBase.treeSha256
    $b.barrierAfter1=New-AcceptanceBarrier @($uis[2]) 27 $script:fixtureBase.treeSha256
    $lease=[pscustomobject][ordered]@{schemaVersion=1;leaseId='00000000-0000-0000-0000-000000000099';pid=[long]$peer.pid;
        startedAtEpochMillis=[long]($peer.lease.startedAtEpochMillis-10000);installationRoot=$root;client='fx'}
    $leaseHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $lease -Depth 8 -Compress)))).ToLowerInvariant()
    $b.injection=[pscustomobject]@{schemaVersion=1;scope='NATIVE_STALE_LEASE_BIRTH_MISMATCH';status='INJECTED';kernelPidReuseObserved=$false;
        leasePath=(Join-Path $root ('CashMemory/Updates/processes/'+$lease.leaseId+'.json'));lease=$lease;leaseSha256=$leaseHash;
        realPeerBefore=(New-AcceptanceAlive $peer 10);realPeerAfter=(New-AcceptanceAlive $peer 10);primary=$uis;injectedUtc=(Get-FixtureTime 11);evidence=(Join-Path $run 'stale-lease-injection.json')}
    $b.observation=[pscustomobject]@{schemaVersion=1;scope='NATIVE_STALE_LEASE_BIRTH_MISMATCH';status='OBSERVED';kernelPidReuseObserved=$false;
        acceptance='MAIN_PENDING';limitSeconds=60;injection=$b.injection.evidence;finalExit=$b.exitFinal;productionHelper=(Join-Path $root 'CashMemory/Updates/apply-update.ps1');
        helperSha256=('c'*64);peerAfterInstall=(New-AcceptanceAlive $peer 38);elapsedMillis=6000;
        samples=@([pscustomobject]@{elapsedMillis=0;observedUtc=(Get-FixtureTime 33);peer=(New-AcceptanceAlive $peer 33);staleLeasePresent=$true;phase='BACKING_UP'},
            [pscustomobject]@{elapsedMillis=1000;observedUtc=(Get-FixtureTime 34);peer=(New-AcceptanceAlive $peer 34);staleLeasePresent=$false;phase=$null});reason='OBSERVED'}
    $b.rootIsolation=[pscustomobject]@{scope='LIVE_INDEPENDENT_NATIVE_ROOT';status='OBSERVED';installedRoot=$root;independentRoot=$peerRoot;
        alive=(New-AcceptanceAlive $peer 39);current=$targetFiles;independent=$baseFiles}
    $b.productionHelperHash='c'*64
    $b.currentBefore=$baseFiles;$b.currentAfter=$targetFiles;$b.targetBefore=$targetFiles;$b.targetAfter=$targetFiles;$b.readyTree=$targetFiles;$b.readyManifest=$script:fixtureTarget
    foreach ($key in 'userBefore','userAfter','peerUserBefore','peerUserAfter') {$b[$key]=[pscustomobject][ordered]@{'CashMemory'=[pscustomobject][ordered]@{path='CashMemory';directory=$true;sizeBytes=0L;sha256='directory';readOnly=$false}}}
    foreach ($key in 'primaryControlledBefore','primaryControlledAfter','peerControlledBefore','peerControlledAfter') {$b[$key]=@()}
    $b.completion=[pscustomobject]@{scope='ACTUAL_NATIVE_CONCURRENT_COMPLETION';observedUtc=(Get-FixtureTime 43);lastInstall=[pscustomobject]@{outcome='UPDATED';targetCommitSha=('b'*40)};
        primaryProcesses=@();primaryLeases=@();staleLeasePresent=$false;installJournalPresent=$false;completedJournalPresent=$false;
        noRestartSamples=@([pscustomobject]@{observedUtc=(Get-FixtureTime 40);primaryProcesses=@();peer=(New-AcceptanceAlive $peer 40)},
            [pscustomobject]@{observedUtc=(Get-FixtureTime 43);primaryProcesses=@();peer=(New-AcceptanceAlive $peer 43)})}
    $b.updateLog='- '+(Get-FixtureTime 36)+' PHASE_COMMITTED'
    $stages=@{PrimaryBefore=0;PeerBefore=1;Ready=3;Installed=43;After=46}
    foreach ($stage in $stages.Keys) {$b['collector'+$stage]=[pscustomobject]@{scope='ACTUAL_NATIVE_CONCURRENT_SNAPSHOT';stage=$stage;primaryRoot=$root;peerRoot=$peerRoot;observedUtc=(Get-FixtureTime $stages[$stage])}}
    return $b
}

# Каждый mutation использует свежий bundle, чтобы ошибки не маскировали друг друга.
function Assert-AcceptanceMutation([scriptblock]$Mutation,[string]$Code) {
    $b=New-AcceptanceBundle;& $Mutation $b
    $result=Test-NativeConcurrentReceiptContract $b $script:fixtureBase $script:fixtureTarget ('c'*64)
    Assert-AcceptanceFixture ($result.contractStatus -ceq 'FAIL' -and ($result.contradictions -join ';') -clike ('*'+$Code+'*')) $Code
}

# Ни один mock не имеет доступа к процессам, GUI, сети, registry, sleep или artifact writes.
function Get-CopyProcesses {throw 'ACCEPTANCE_FIXTURE_FORBIDDEN_CIM'}
function Start-Process {throw 'ACCEPTANCE_FIXTURE_FORBIDDEN_PROCESS'}
function Stop-Process {throw 'ACCEPTANCE_FIXTURE_FORBIDDEN_KILL'}
function Invoke-RestMethod {throw 'ACCEPTANCE_FIXTURE_FORBIDDEN_NETWORK'}
function Start-Sleep {throw 'ACCEPTANCE_FIXTURE_FORBIDDEN_SLEEP'}
function Write-NativeAcceptanceArtifact {throw 'ACCEPTANCE_FIXTURE_FORBIDDEN_WRITE'}

# Adapter проверяется на настоящем frozen source, но generated function не исполняется.
$frozenHelper=Join-Path $PSScriptRoot 'NativeUpdateConcurrentScenarios.ps1'
$frozenSha=(Get-FileHash -LiteralPath $frozenHelper).Hash.ToLowerInvariant()
$definition=Get-NativeConcurrentCollectedDefinition $frozenSha
$positions=@()
foreach ($stage in 'PrimaryBefore','Ready','PeerBefore','Installed','After') {
    $needle='Save-NativeConcurrentAcceptanceSnapshot -Stage '+$stage
    Assert-AcceptanceFixture ([regex]::Matches($definition,[regex]::Escape($needle)).Count -eq 1) ('AST producer '+$stage)
    $positions+=@($definition.IndexOf($needle,[StringComparison]::Ordinal))
}
Assert-AcceptanceFixture (($positions -join ',') -ceq (($positions | Sort-Object) -join ',')) 'AST actual chronology before launch/ready/peer/install/after'
Assert-AcceptanceFixture ($definition.IndexOf('-Stage After') -lt $definition.IndexOf('finishedAt=[datetime]::UtcNow')) 'collector before finishedAt'
Assert-AcceptanceFixture ($definition.IndexOf('finishedAt=[datetime]::UtcNow') -lt $definition.IndexOf('CONCURRENT_FORCED_CLEANUP_REQUIRED')) 'finishedAt before cleanup, acceptance after finally'
$pinRejected=$false;try {Get-NativeConcurrentCollectedDefinition ('0'*64) | Out-Null} catch {$pinRejected=$_.Exception.Message -ceq 'ACCEPTANCE_CONCURRENT_PIN'}
Assert-AcceptanceFixture $pinRejected 'adapter wrong pin before execution'
Assert-AcceptanceFixture ((Get-FileHash -LiteralPath $frozenHelper).Hash.ToLowerInvariant() -ceq $frozenSha) 'adapter never writes frozen source'

$b=New-AcceptanceBundle
$result=Test-NativeConcurrentReceiptContract $b $script:fixtureBase $script:fixtureTarget ('c'*64)
Assert-AcceptanceFixture ($result.contractStatus -ceq 'SATISFIED') ('positive pure contract: '+($result.contradictions -join ';'))
Assert-AcceptanceFixture (-not $result.kernelPidReuseObserved -and $null -eq $result.PSObject.Properties['status']) 'no native PASS from mocks'
foreach ($key in @($b.Keys)) {
    $copy=New-AcceptanceBundle;$copy[$key]=$null
    $r=Test-NativeConcurrentReceiptContract $copy $script:fixtureBase $script:fixtureTarget ('c'*64)
    Assert-AcceptanceFixture ($r.contractStatus -ceq 'PENDING' -and $r.missing -ccontains $key) ('missing '+$key)
}
Assert-AcceptanceMutation {param($b) $b.cell.status='PASS';$b.barrierAll.samples[0].treeSha256='0'*64} 'ACCEPTANCE_BARRIER_SAMPLE'
Assert-AcceptanceMutation {param($b) $b.cell.status='FAIL'} 'ACCEPTANCE_ROW_IDENTITY'
Assert-AcceptanceMutation {param($b) $b.launch1.ui.pid=$b.launch0.ui.pid} 'COLD_REPORT_LAUNCH_IDENTITY'
Assert-AcceptanceMutation {param($b) $b.barrierAll.samples[0].alive[0].identity.StartedAtTicks++} 'COLD_PROCESS_IDENTITY'
Assert-AcceptanceMutation {param($b) $b.barrierAfter0.samples[0].alive=@($b.barrierAfter0.samples[0].alive[0])} 'ACCEPTANCE_BARRIER_SAMPLE'
Assert-AcceptanceMutation {param($b) $b.barrierAfter1.elapsedMillis=2999} 'ACCEPTANCE_BARRIER_WINDOW'
Assert-AcceptanceMutation {param($b) $b.exit0.kind='killed'} 'ACCEPTANCE_INTERMEDIATE_EXIT'
Assert-AcceptanceMutation {param($b) $b.exitFinal.exitCode=1} 'NATIVE_EXIT_RECEIPT'
Assert-AcceptanceMutation {param($b) $b.peerExit.exitedUtc=Get-FixtureTime 31} 'ACCEPTANCE_PEER_EARLY_EXIT'
Assert-AcceptanceMutation {param($b) $b.injection.kernelPidReuseObserved=$true} 'ACCEPTANCE_STALE_SCOPE'
Assert-AcceptanceMutation {param($b) $b.injection.lease.startedAtEpochMillis=$b.peerLaunch.ui.lease.startedAtEpochMillis} 'CONCURRENT_STALE_LEASE_WIRE'
foreach ($pidValue in @([uint32]5004,0,([long][int]::MaxValue+1),$true,'5004')) {
    $badPid=$pidValue
    Assert-AcceptanceMutation {param($b) $b.injection.lease.pid=$badPid} 'CONCURRENT_STALE_LEASE_WIRE'
}
Assert-AcceptanceMutation {param($b) $b.injection.leaseSha256='0'*64} 'ACCEPTANCE_STALE_HASH'
Assert-AcceptanceMutation {param($b) $b.productionHelperHash='0'*64} 'ACCEPTANCE_PRODUCTION_HELPER'
Assert-AcceptanceMutation {param($b) $b.observation.peerAfterInstall.identity.OwnedRoot=$b.launch0.ui.lease.installationRoot} 'COLD_PROCESS_IDENTITY'
Assert-AcceptanceMutation {param($b) $b.rootIsolation.independent=$b.currentAfter} 'COLD_INVENTORY'
Assert-AcceptanceMutation {param($b) $b.currentAfter[0].readOnly=$false} 'COLD_INVENTORY'
Assert-AcceptanceMutation {param($b) $b.peerUserAfter.CashMemory.readOnly=$true} 'ACCEPTANCE_CASHMEMORY_CHANGED'
Assert-AcceptanceMutation {param($b) $b.completion.staleLeasePresent=$true} 'ACCEPTANCE_COMPLETION_REMAINS'
Assert-AcceptanceMutation {param($b) $b.completion.noRestartSamples[1].primaryProcesses=@(5001)} 'ACCEPTANCE_RESTART'
Assert-AcceptanceMutation {param($b) $b.updateLog='- '+(Get-FixtureTime 31)+' PHASE_COMMITTED'} 'ACCEPTANCE_REPLACEMENT_BEFORE_EXIT'
Assert-AcceptanceMutation {param($b) $b.collectorAfter.observedUtc=Get-FixtureTime 48} 'ACCEPTANCE_COLLECTOR_CHRONOLOGY'
Assert-AcceptanceMutation {param($b) $b.primaryControlledAfter=@([pscustomobject]@{path='CashMemory/unknown.md'})} 'ACCEPTANCE_CONTROLLED_PATH'

# Missing поля и unfinished observer не становятся ложными contradiction FAIL.
foreach ($case in @('rowField','uiField','leaseField','pendingObserver','pendingBarrier')) {
    $partial=New-AcceptanceBundle
    switch ($case) {
        'rowField' {$partial.cell.PSObject.Properties.Remove('exitCode')}
        'uiField' {$partial.launch1.ui.PSObject.Properties.Remove('startedAtTicks')}
        'leaseField' {$partial.launch1.ui.lease.PSObject.Properties.Remove('installationRoot')}
        'pendingObserver' {$partial.observation.status='PENDING';$partial.observation.peerAfterInstall=$null}
        'pendingBarrier' {$partial.barrierStale.status='PENDING';$partial.barrierStale.elapsedMillis=0}
    }
    $r=Test-NativeConcurrentReceiptContract $partial $script:fixtureBase $script:fixtureTarget ('c'*64)
    Assert-AcceptanceFixture ($r.contractStatus -ceq 'PENDING' -and $r.missing.Count -gt 0) ('partial '+$case)
}
$partial=New-AcceptanceBundle;$partial.observation=$null;$partial.cell.status='FAIL'
$r=Test-NativeConcurrentReceiptContract $partial $script:fixtureBase $script:fixtureTarget ('c'*64)
Assert-AcceptanceFixture ($r.contractStatus -ceq 'FAIL' -and $r.missing.Count -gt 0) 'contradiction dominates missing'

# Desktop owner проверяется без HWND API и без реального close; это wire tests.
$b=New-AcceptanceBundle;$ui=$b.launch0.ui
$close=[pscustomobject]@{observedUtc=(Get-FixtureTime 19);ui=$ui;
    window=[pscustomobject]@{handle=$ui.witness.handle;pid=$ui.pid;exists=$true;visible=$true;enabled=$true;processMainHandle=$ui.witness.handle}}
Assert-AcceptanceFixture (@(Test-NativeAcceptanceCloseReceipt $close $ui $b.exit0).Count -eq 0) 'physical owner positive wire'
Assert-AcceptanceFixture (@(Test-NativeAcceptanceCloseReceipt $null $ui $b.exit0).Count -eq 1) 'physical owner missing PENDING'
$close.window.PSObject.Properties.Remove('enabled')
Assert-AcceptanceFixture (@(Test-NativeAcceptanceCloseReceipt $close $ui $b.exit0) -ccontains 'physical-close.window.enabled') 'physical owner field missing PENDING'
$close.window | Add-Member enabled $false
$rejected=$false;try {Test-NativeAcceptanceCloseReceipt $close $ui $b.exit0 | Out-Null} catch {$rejected=$_.Exception.Message -ceq 'NATIVE_NORMAL_CLOSE_OWNER_DISABLED'}
Assert-AcceptanceFixture $rejected 'disabled owner must reject'
$close.window.enabled=$true;$close.window.pid++
$rejected=$false;try {Test-NativeAcceptanceCloseReceipt $close $ui $b.exit0 | Out-Null} catch {$rejected=$_.Exception.Message -ceq 'NATIVE_NORMAL_CLOSE_WINDOW_IDENTITY'}
Assert-AcceptanceFixture $rejected 'foreign owner must reject'

# Collector не может тихо стать mock-PASS: на baseline census он обязан вызвать actual guard.
$collectorFailed=$false
try {
    # Ссылочные guards не выполняются: это только call ordering fixture, не actual filesystem.
    function Resolve-PortableSafetyPath([string]$Path) {return $Path}
    function Assert-ColdOwnedRun {return 'mock'}
    function Assert-PortableTreeHasNoLinks {}
    Save-NativeConcurrentAcceptanceSnapshot -Stage PrimaryBefore -CellEvidence (Join-Path ([IO.Path]::GetTempPath()) 'mock-evidence/run-11111111-1111-1111-1111-111111111111') -PrimaryRoot $b.launch0.ui.lease.installationRoot -PeerRoot $b.peerLaunch.root -Base $script:fixtureBase -Target $script:fixtureTarget
} catch {$collectorFailed=$_.Exception.Message -ceq 'ACCEPTANCE_FIXTURE_FORBIDDEN_CIM'}
Assert-AcceptanceFixture $collectorFailed 'collector census first; no mocks as native proof'

# Spy collector проверяет actual producer call ordering; артефакты остаются только в памяти.
$script:producerCalls=[Collections.Generic.List[string]]::new();$script:producerArtifacts=@{}
function Get-CopyProcesses([string]$Root) {$script:producerCalls.Add('census');return @()}
function Assert-ColdTree([string]$Root,$Manifest) {$script:producerCalls.Add('tree');return $Manifest.files}
function Get-ColdControlledInventory([string]$Root) {$script:producerCalls.Add('controlled');return @()}
function Get-NativeUserObject([string]$Root) {$script:producerCalls.Add('user');return [ordered]@{}}
function Write-NativeAcceptanceArtifact([string]$Directory,[string]$Name,$Value) {$script:producerCalls.Add('write:'+ $Name);$script:producerArtifacts[$Name]=$Value}
function Read-NativeAcceptanceJson {return $script:fixtureTarget}
$params=@{CellEvidence=(Join-Path ([IO.Path]::GetTempPath()) 'mock-evidence/run-11111111-1111-1111-1111-111111111111');
    PrimaryRoot=$b.launch0.ui.lease.installationRoot;PeerRoot=$b.peerLaunch.root;Base=$script:fixtureBase;Target=$script:fixtureTarget}
foreach ($stage in 'PrimaryBefore','PeerBefore','Ready','After') {Save-NativeConcurrentAcceptanceSnapshot -Stage $stage @params}
Assert-AcceptanceFixture (($script:producerCalls | Select-Object -First 5) -join ',' -ceq 'census,tree,controlled,write:primaryControlledBefore,write:collectorPrimaryBefore') 'baseline ordering'
foreach ($name in 'primaryControlledBefore','peerUserBefore','peerControlledBefore','readyManifest','readyTree','primaryControlledAfter','peerControlledAfter','peerUserAfter',
    'collectorPrimaryBefore','collectorPeerBefore','collectorReady','collectorAfter') {
    Assert-AcceptanceFixture $script:producerArtifacts.ContainsKey($name) ('producer '+$name)
}
$rejected=$false;try {Save-NativeConcurrentAcceptanceSnapshot -Stage Installed @params} catch {$rejected=$_.Exception.Message -ceq 'ACCEPTANCE_COLLECTOR_CONTEXT'}
Assert-AcceptanceFixture $rejected 'installed requires retained peer and exact final exit'
function Get-CopyProcesses {return @([pscustomobject]@{ProcessId=5001})}
$rejected=$false;try {Save-NativeConcurrentAcceptanceSnapshot -Stage PrimaryBefore @params} catch {$rejected=$_.Exception.Message -ceq 'ACCEPTANCE_BASELINE_TOO_LATE'}
Assert-AcceptanceFixture $rejected 'cannot reconstruct baseline after launch'
$rejected=$false;try {Save-NativeConcurrentAcceptanceSnapshot -Stage After @params} catch {$rejected=$_.Exception.Message -ceq 'ACCEPTANCE_AFTER_STILL_ALIVE'}
Assert-AcceptanceFixture $rejected 'after requires ordinary clients already exited'
[pscustomobject]@{suite='NativeUpdateConcurrentAcceptanceFixtures';checks=$script:checks;status='PASS';nativeExecuted=$false;nativeAcceptance='NOT_RUN'} | ConvertTo-Json -Compress
