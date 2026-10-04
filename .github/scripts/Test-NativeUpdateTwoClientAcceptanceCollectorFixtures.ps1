<#
.SYNOPSIS
Bounded collector fixtures: actual AST hooks/imports/producer guards, mocks только leaf readers.
.DESCRIPTION
Старые suites не выполняются. Native/GUI/Java/Maven/сеть/реестр/kill не вызываются.
Synthetic observations никогда не EvidenceKind=NATIVE и не native PASS.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$script:checks=0;$asts=@{};$pins=@{}
foreach ($file in 'NativeUpdateTwoClientAcceptanceCollector.ps1','NativeUpdateTwoClientAcceptance.ps1',
    'Test-NativeUpdateTwoClientAcceptanceFixtures.ps1','NativeUpdateConcurrentAcceptanceFixtures.ps1',
    'NativeUpdateConcurrentAcceptance.ps1','NativeUpdateConcurrentScenarios.ps1','Test-NativeUpdateLifecycle.ps1',
    'Test-UpdateBootstrap.ps1','Test-Portable.ps1') {
    $path=Join-Path $PSScriptRoot $file;$pins[$file]=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
    $tokens=$null;$errors=$null;$asts[$file]=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw ('TWO_COLLECT_FIXTURE_PARSE:'+ $file)}
}
# Считает только scoped unit checks, никогда matrix PASS.
function Assert-CollectorFixture([bool]$Value,[string]$Code) {
    if (-not $Value) {throw ('TWO_COLLECT_FIXTURE_ASSERT:'+ $Code)};$script:checks++
}
# Отказ должен совпасть с guard, не быть случайной ошибкой mock.
function Assert-CollectorRejected([scriptblock]$Action,[string]$Code) {
    $message=$null;try {& $Action | Out-Null} catch {$message=$_.Exception.Message}
    Assert-CollectorFixture ($message -ceq $Code) ('expected='+$Code+' actual='+$message)
}
foreach ($def in $asts['NativeUpdateTwoClientAcceptanceCollector.ps1'].EndBlock.Statements) {
    if ($def -isnot [Management.Automation.Language.FunctionDefinitionAst]) {throw 'TWO_COLLECT_FIXTURE_BODY'}
    . ([scriptblock]::Create($def.Extent.Text.Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'"))))
}
Import-NativeTwoClientCollectorDependencies $PSScriptRoot
Assert-NativeTwoClientCollectorSources $PSScriptRoot $pins
$path=Join-Path $PSScriptRoot 'NativeUpdateConcurrentScenarios.ps1'
$generated=Get-NativeTwoClientCollectedDefinitions $path $pins['NativeUpdateConcurrentScenarios.ps1']
Assert-CollectorRejected {Get-NativeTwoClientCollectedDefinitions $path ('0'*64)} 'TWO_COLLECT_CONCURRENT_PIN'
$badPins=$pins.Clone();$badPins['NativeUpdateTwoClientAcceptance.ps1']='0'*64
Assert-CollectorRejected {Assert-NativeTwoClientCollectorSources $PSScriptRoot $badPins} 'TWO_COLLECT_SOURCE_CHANGED:NativeUpdateTwoClientAcceptance.ps1'
Assert-CollectorRejected {Assert-NativeTwoClientCollectorSources $PSScriptRoot @{}} 'TWO_COLLECT_SOURCE_PIN:Test-NativeUpdateLifecycle.ps1'
$tokens=$null;$errors=$null;$generatedAst=[Management.Automation.Language.Parser]::ParseInput($generated,[ref]$tokens,[ref]$errors)
Assert-CollectorFixture ($errors.Count -eq 0 -and $generatedAst.EndBlock.Statements.Count -eq 2) 'TWO_LOCAL_DEFINITIONS_ONLY'
foreach ($stage in 'PrimaryBefore','PeerBefore','Ready','Completion','Cleanup','BeginWait','TreeWait','EndWait') {
    Assert-CollectorFixture ([regex]::Matches($generated,'-Stage '+$stage+'\b').Count -eq 1) ('EXACT_HOOK_'+$stage)
}
foreach ($guard in 'Assert-NativeConcurrentOldTree $Root $Base',
    'if ($samples.Count -ge 2 -and $clock.ElapsedMilliseconds -ge 3000) {break}',
    'Start-Sleep -Milliseconds 250','if ($failure -or $cleanup.Count) {throw $Row.reason}') {
    Assert-CollectorFixture ($generated.Contains($guard)) ('ORIGINAL_GUARD_UNCHANGED_'+$guard)
}
Assert-CollectorFixture ($generated.IndexOf('-Stage PrimaryBefore') -lt $generated.IndexOf('Start-NativeOwned') -and
    $generated.IndexOf('-Stage PeerBefore') -lt $generated.IndexOf('$client=$clientNames[$i]') -and
    $generated.IndexOf('-Stage Ready') -lt $generated.IndexOf('$proofs.Add((Observe-NativeTwoClientObservedBarrier')) 'HOOKS_BEFORE_LAUNCH_AND_WINDOWS'
Assert-CollectorFixture ($generated.IndexOf('-Stage Completion') -lt $generated.IndexOf('finishedAt=[datetime]::UtcNow') -and
    $generated.IndexOf('-Stage Cleanup') -gt $generated.IndexOf('CONCURRENT_FORCED_CLEANUP_REQUIRED')) 'ACTUAL_COMPLETION_FINISHEDAT_CLEANUP_ORDER'
$barrier=$generatedAst.EndBlock.Statements[0].Extent.Text
Assert-CollectorFixture ($barrier.IndexOf('-Stage BeginWait') -lt $barrier.IndexOf('Assert-NativeConcurrentOldTree') -and
    $barrier.IndexOf('-Stage TreeWait') -gt $barrier.IndexOf('Assert-NativeConcurrentOldTree') -and
    $barrier.IndexOf('-Stage EndWait') -lt $barrier.IndexOf('$samples.Add')) 'REAL_TREE_READ_BRACKETED_INSIDE_EXISTING_WINDOW'

# Реальное импортированное замыкание adapter + исходного helper проверяется до mocks.
. ([scriptblock]::Create($generated))
$queue=[Collections.Generic.Queue[string]]::new();$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($name in 'Invoke-NativeTwoClientCollectedScenario','Invoke-NativeConcurrentScenario','Read-NativeTwoClientFreshAlive','Invoke-NativeTwoClientCollectorObservation') {$queue.Enqueue($name)}
while ($queue.Count) {
    $name=$queue.Dequeue();if (-not $seen.Add($name)) {continue}
    $command=Get-Command $name -ErrorAction Stop
    if ($command.CommandType -ne 'Function' -or $command.ModuleName) {continue}
    $defs=@(foreach ($file in $asts.Keys | Where-Object {$_ -notlike '*Fixtures.ps1'}) {
        $asts[$file].FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true)
    })
    if ($name -cin @('Invoke-NativeTwoClientObservedScenario','Observe-NativeTwoClientObservedBarrier')) {
        $defs=@($generatedAst.EndBlock.Statements | Where-Object {$_.Name -ceq $name})
    }
    if ($name -ceq 'Get-ColdSessionWords') {
        $defs=@($asts['Test-UpdateBootstrap.ps1'].EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $name})
    }
    Assert-CollectorFixture ($defs.Count -eq 1) ('EXACT_SOURCE_COUNT_'+$name)
    $unbound=& {param($text,$n) . ([scriptblock]::Create($text));(Get-Command $n).Definition} $defs[0].Extent.Text $name
    $boundText=$defs[0].Extent.Text.Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'"))
    if ($name -ceq 'Import-NativeDependencies') {$boundText=$defs[0].Extent.Text}
    $bound=& {param($text,$n) . ([scriptblock]::Create($text));(Get-Command $n).Definition} $boundText $name
    # Import-NativeDependencies содержит quoted $PSScriptRoot для последующего AST bind; его literal не меняется.
    if ($name -ceq 'Import-NativeDependencies') {
        $boundText=$defs[0].Extent.Text
        foreach ($v in @($defs[0].FindAll({param($n) $n -is [Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -ceq 'PSScriptRoot'},$true) | Sort-Object {$_.Extent.StartOffset} -Descending)) {
            $boundText=$boundText.Remove($v.Extent.StartOffset-$defs[0].Extent.StartOffset,$v.Extent.Text.Length).Insert($v.Extent.StartOffset-$defs[0].Extent.StartOffset,("'"+$PSScriptRoot.Replace("'","''")+"'"))
        }
        $bound=& {param($text,$n) . ([scriptblock]::Create($text));(Get-Command $n).Definition} $boundText $name
    }
    Assert-CollectorFixture ($command.Definition -ceq $unbound -or $command.Definition -ceq $bound) ('EXACT_SOURCE_BODY_'+$name)
    foreach ($call in $defs[0].FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true)) {
        $callee=$call.GetCommandName();if ($callee -and $callee -cne 'Check') {$queue.Enqueue($callee)}
    }
}
Assert-CollectorFixture ($seen.Contains('Get-ColdCurrentProcess') -and $seen.Contains('Get-ColdUiReceipt') -and
    $seen.Contains('Get-PortableRealRegistrySnapshot') -and $seen.Contains('Assert-NativeScenarioHttp')) 'ACTUAL_IMPORT_CLOSURE'

# Только generators прежних fixtures; их suites и mocks не выполняются повторно.
foreach ($entry in @(
    @{file='NativeUpdateConcurrentAcceptanceFixtures.ps1';names=@('Get-FixtureTime','New-AcceptanceUi','New-AcceptanceIdentity','New-AcceptanceAlive','New-AcceptanceBarrier')},
    @{file='Test-NativeUpdateTwoClientAcceptanceFixtures.ps1';names=@('New-TwoWait','New-TwoFixture')}
)) {
    foreach ($name in $entry.names) {
        $def=@($asts[$entry.file].EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $name})[0]
        $text=$def.Extent.Text
        # Фабрика использует mixed slash; actual helper строит canonical nested Join-Path.
        if ($name -ceq 'New-TwoFixture') {$text=$text.Replace('$root=Join-Path $run ''plain/CashPrediction''','$root=Join-Path (Join-Path $run ''plain'') ''CashPrediction''')}
        . ([scriptblock]::Create($text))
    }
}
function Start-Process {throw 'TWO_COLLECT_FIXTURE_FORBIDDEN_PROCESS'}
function Invoke-RestMethod {throw 'TWO_COLLECT_FIXTURE_FORBIDDEN_NETWORK'}
function Start-Sleep {throw 'TWO_COLLECT_FIXTURE_FORBIDDEN_SLEEP'}

# Fresh producer выполняется реально; только native/filesystem leaf readers синтетические.
& {
    $script:f=New-TwoFixture;$script:reads=[Collections.Generic.List[string]]::new();$script:noUi=$false;$script:wrongBirth=$false
    $entries=@()
    foreach ($i in 0,1) {
        $ui=$script:f.bundle['launch'+$i].ui
        $process=[pscustomobject]@{Id=$ui.pid;HasExited=$false;StartTime=[datetime]::new($ui.startedAtTicks,[DateTimeKind]::Utc);
            MainModule=[pscustomobject]@{FileName=$ui.executablePath}}
        $entries+=@([pscustomobject]@{root=$ui.lease.installationRoot;client=$ui.lease.client;native=[pscustomobject]@{ui=$ui;uiProcess=$process}})
    }
    $script:entries=$entries
    function Get-CimInstance {
        $script:reads.Add('cim')
        return @($script:entries | ForEach-Object {[pscustomobject]@{ProcessId=$_.native.ui.pid;CreationDate=$_.native.uiProcess.StartTime;ExecutablePath=$_.native.ui.executablePath;CommandLine=$_.native.ui.commandLine}})
    }
    function Get-ColdUiReceipt($Root,$Client,$Started) {
        $script:reads.Add('fresh-ui')
        if ($script:noUi) {return $null}
        $ui=($script:entries | Where-Object {$_.client -ceq $Client})[0].native.ui
        $fresh=ConvertFrom-ColdReceiptJson (ConvertTo-Json -Depth 64 -InputObject $ui);$fresh.observedAt=[datetime]::UtcNow.ToString('o');return $fresh
    }
    function Get-ColdProcessReceipt($Process,$Root) {
        $script:reads.Add('retained')
        return [pscustomobject]@{ProcessId=$Process.Id;StartedAtTicks=$Process.StartTime.Ticks+$(if ($script:wrongBirth) {1} else {0});ExecutablePath=$Process.MainModule.FileName;OwnedRoot=$Root}
    }
    function Read-NativeAcceptanceJson($Path,$ExpectedSha256='') {
        $script:reads.Add('json')
        if ($Path.EndsWith('update.json')) {return $script:f.target}
        if ($Path.EndsWith('last-install.json')) {return $script:f.independent.completion.lastInstall}
        return ($script:entries | Where-Object {$Path.Contains($_.native.ui.lease.leaseId)})[0].native.ui.lease
    }
    function Get-CopyProcesses {return @()}
    function Assert-ColdTree($Root,$Manifest) {$script:reads.Add('tree-bytes');return $Manifest.files}
    function Get-NativeUserObject {return $script:f.independent.userBefore}
    function Get-PortableRealRegistrySnapshot {return 'mock-registry'}
    function Get-ChildItem {return @()}
    function Test-Path {return $false}
    function Resolve-PortableSafetyPath($Path) {
        if ($Path.EndsWith('apply-update.ps1')) {return (Join-Path $PSScriptRoot 'NativeUpdateConcurrentScenarios.ps1')}
        return [IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    }
    function Write-NativeTwoClientCollectorArtifact($Directory,$Name,$Value,[switch]$Bytes) {
        $script:reads.Add('artifact:'+ $Name)
        return [pscustomobject]@{path=(Join-Path $Directory $Name);sha256=('c'*64)}
    }
    $state=[pscustomobject]@{proof=[ordered]@{schemaVersion=1;cellKey=$script:f.independent.cellKey};stages=[ordered]@{};
        samples=@{allAlive=[Collections.Generic.List[object]]::new();peerAlive=[Collections.Generic.List[object]]::new()};
        pending=$null;window=$null;base=$script:f.base;target=$script:f.target;helperPin=$pins['NativeUpdateConcurrentScenarios.ps1'];
        evidence=$null;registryBefore='mock-registry';gaps=[Collections.Generic.List[string]]::new()}
    $data=[pscustomobject]@{root=$entries[0].root;targetRoot='C:\mock-target';evidence='C:\mock-evidence';entries=@();errors=@()}
    Invoke-NativeTwoClientCollectorObservation $state 'PrimaryBefore' $data
    $data.entries=@($entries[0]);Invoke-NativeTwoClientCollectorObservation $state 'PeerBefore' $data
    $data.entries=$entries;Invoke-NativeTwoClientCollectorObservation $state 'Ready' $data
    Assert-CollectorFixture ($state.gaps.Count -eq 0 -and $state.proof.Contains('readyObservedAt') -and $state.stages.PeerBefore.initialAlive.ui.pid -eq $entries[0].native.ui.pid) ('REAL_BASELINES_PEER_READY '+($state.gaps -join ';'))
    Assert-CollectorFixture (-not $script:reads.Contains('artifact:production-apply-update.ps1')) 'NO_HELPER_FILE_ASSUMED_AT_READY'
    $wait=[pscustomobject]@{root=$data.root;entries=$entries;path='C:\mock-evidence\barrier-all.json'}
    $offset=$script:reads.Count
    foreach ($stage in 'BeginWait','TreeWait','EndWait') {Invoke-NativeTwoClientCollectorObservation $state $stage $wait}
    $calls=@($script:reads.ToArray()[$offset..($script:reads.Count-1)])
    Assert-CollectorFixture ($state.gaps.Count -eq 0 -and $state.samples.allAlive.Count -eq 1 -and [Array]::IndexOf($calls,'fresh-ui') -lt [Array]::IndexOf($calls,'tree-bytes') -and [Array]::LastIndexOf($calls,'fresh-ui') -gt [Array]::IndexOf($calls,'tree-bytes')) ('FRESH_READS_BRACKET_FULL_TREE '+($state.gaps -join ';')+' calls='+($calls -join ','))
    $sample=$state.samples.allAlive[0]
    Assert-CollectorFixture ($sample.aliveBefore.Count -eq 2 -and $sample.aliveAfter.Count -eq 2 -and $sample.treeFiles[0].sha256 -ceq $script:f.base.files[0].sha256) 'ACTUAL_SAMPLE_SHAPE'
    $wait.path='C:\mock-evidence\barrier-after-exit-0.json';$wait.entries=@($entries[1])
    foreach ($stage in 'BeginWait','TreeWait','EndWait') {Invoke-NativeTwoClientCollectorObservation $state $stage $wait}
    Assert-CollectorFixture ($state.samples.peerAlive.Count -eq 1 -and $state.samples.peerAlive[0].aliveAfter[0].ui.lease.client -ceq $entries[1].client) 'REAL_PEER_WINDOW'
    Invoke-NativeTwoClientCollectorObservation $state 'Completion' $data
    Invoke-NativeTwoClientCollectorObservation $state 'Cleanup' $data
    Assert-CollectorFixture ($state.gaps.Count -eq 0 -and $script:reads.Contains('artifact:production-apply-update.ps1') -and $state.proof.cleanup.registryUnchanged -and $state.proof.completion.currentAfter[0].sha256 -ceq $script:f.target.files[0].sha256) ('REAL_COMPLETION_CLEANUP '+($state.gaps -join ';'))
    Assert-CollectorFixture ((Get-ColdUtcTicks $state.proof.cleanup.observedAt) -ge (Get-ColdUtcTicks $state.proof.completion.observedAt)) 'CLEANUP_AFTER_COMPLETION_ONLY'
    $produced=[ordered]@{};foreach ($key in $state.proof.Keys) {$produced[$key]=$state.proof[$key]}
    foreach ($name in 'allAlive','peerAlive') {$produced[$name]=@($state.samples[$name].ToArray())}
    Assert-CollectorFixture ((@($produced.Keys | Sort-Object) -join '/') -ceq (@($script:f.independent.PSObject.Properties.Name | Sort-Object) -join '/')) 'ACTUAL_PRODUCER_FIELDS_MATCH_FROZEN_124_SCHEMA'
    $script:noUi=$true;$wait.entries=@($entries[1])
    Invoke-NativeTwoClientCollectorObservation $state 'BeginWait' $wait
    Assert-CollectorFixture ($state.gaps[-1] -clike '*TWO_COLLECT_UI_NOT_OBSERVED' -and $null -eq $state.pending -and $state.samples.peerAlive.Count -eq 1) 'MISSING_UI_NOT_FABRICATED'
    $script:noUi=$false;$script:wrongBirth=$true
    Assert-CollectorRejected {Read-NativeTwoClientFreshAlive $entries[1] ([datetime]::UtcNow.Ticks)} 'COLD_PROCESS_IDENTITY'
    $script:wrongBirth=$false
    Invoke-NativeTwoClientCollectorObservation $state 'Ready' $data
    Assert-CollectorFixture ($state.gaps[-1] -clike '*TWO_COLLECT_DUPLICATE') 'NO_BASELINE_OVERWRITE'
}

# Adapter plumbing: synthetic observations только UNIT_MOCK; helper получает отдельную Row.
& {
    $script:mockFixture=New-TwoFixture;$script:omitWindow=$false;$script:shortWindow=$false;$script:acceptCalls=0;$script:written=@{}
    function Assert-NativeTwoClientCollectorSources {}
    function Import-NativeTwoClientCollectorDependencies {}
    function Get-PortableRealRegistrySnapshot {return 'mock'}
    function Read-NativeAcceptanceJson($Path,$ExpectedSha256='') {if ($Path -ceq 'C:\base.json') {return $script:mockFixture.base};return $script:mockFixture.target}
    function Read-NativeTwoClientCollectorRaw {return $script:mockFixture.bundle}
    function Get-NativeTwoClientCollectedDefinitions {
        return @'
function Invoke-NativeTwoClientObservedScenario($Row,$Source,$Base,$Target,$Life,$Cold,$Java,$Evidence,$Timeout) {
    $Row.status='PASS'
    $twoCollectorState.evidence='C:\synthetic-evidence'
    $twoCollectorState.proof=[ordered]@{}
    foreach ($p in $script:mockFixture.independent.PSObject.Properties) {$twoCollectorState.proof[$p.Name]=$p.Value}
    foreach ($stage in 'PrimaryBefore','PeerBefore','Ready','Completion','Cleanup') {$twoCollectorState.stages[$stage]=[pscustomobject]@{synthetic=$true}}
    foreach ($sample in $script:mockFixture.independent.allAlive) {$twoCollectorState.samples.allAlive.Add($sample)}
    if (-not $script:omitWindow) {foreach ($sample in $script:mockFixture.independent.peerAlive) {$twoCollectorState.samples.peerAlive.Add($sample)}}
    if ($script:shortWindow -and $twoCollectorState.samples.peerAlive.Count) {
        $samples=$twoCollectorState.samples.peerAlive
        $last=ConvertFrom-ColdReceiptJson (ConvertTo-Json -Depth 64 -InputObject $samples[-1])
        $last.finishedAt=([datetime]$samples[0].startedAt).AddSeconds(2).ToUniversalTime().ToString('o')
        $samples[$samples.Count-1]=$last
    }
    return [pscustomobject]@{syntheticHelperReturn='retained'}
}
'@
    }
    function Write-NativeTwoClientCollectorArtifact($Directory,$Name,$Value,[switch]$Bytes) {
        $script:written[$Name]=$Value;return [pscustomobject]@{path=(Join-Path $Directory $Name);sha256=('d'*64)}
    }
    function Test-NativeTwoClientAcceptance {
        param($CellEvidence,$BaseManifest,$BaseSha256,$TargetManifest,$TargetSha256,$ExpectedHelperSha256,$SupplementalDirectory,$IndependentSha256,$EvidenceKind)
        if ($EvidenceKind -ceq 'NATIVE' -or $IndependentSha256 -cne ('d'*64)) {throw 'TWO_COLLECT_FIXTURE_FALSE_NATIVE'}
        $script:acceptCalls++;return [pscustomobject]@{status='PENDING';scope='NATIVE_TWO_CLIENT_WAIT_BARRIER';proofComplete=$false;missing=@('mock provenance');contradictions=@()}
    }
    $authority=[pscustomobject]@{baseManifest='C:\base.json';baseSha256=('a'*64);targetManifest='C:\target.json';targetSha256=('b'*64);helperSha256=('c'*64);sourcePins=$pins}
    $row=[pscustomobject]@{scenario='two-clients';base='B1';client='fx';path='ascii';phase='SESSION';status='PENDING'}
    $before=ConvertTo-Json -Depth 64 -InputObject $row
    $r=Invoke-NativeTwoClientCollectedScenario $row 'source' $script:mockFixture.base $script:mockFixture.target $null ([pscustomobject]@{helperSha256=('c'*64)}) 'java' 'evidence' 180 $authority -EvidenceKind UNIT_MOCK
    Assert-CollectorFixture ($r.status -ceq 'PENDING' -and $r.observationStatus -ceq 'OBSERVED' -and $r.helperRow.status -ceq 'PASS' -and (ConvertTo-Json -Depth 64 -InputObject $row) -ceq $before) 'CANONICAL_ROW_UNCHANGED_DESPITE_HELPER_PASS'
    Assert-CollectorFixture ([object]::ReferenceEquals($r.rawReceipts,$script:mockFixture.bundle) -and $r.helperOutputs[0].syntheticHelperReturn -ceq 'retained') 'RAW_RECEIPTS_PRESERVED'
    Assert-CollectorFixture ($r.Evidence.IndependentSha256 -ceq ('d'*64) -and $r.independentFile.EndsWith('two-client-independent.json') -and $script:acceptCalls -eq 1) 'BRIDGE_EVIDENCE_INTERFACE'
    $script:omitWindow=$true
    $r=Invoke-NativeTwoClientCollectedScenario $row 'source' $script:mockFixture.base $script:mockFixture.target $null ([pscustomobject]@{helperSha256=('c'*64)}) 'java' 'evidence' 180 $authority
    Assert-CollectorFixture ($r.observationStatus -ceq 'NOTPROVEN' -and $r.status -ceq 'PENDING' -and $script:acceptCalls -eq 1 -and $r.evidenceKind -ceq 'UNVERIFIED') 'MISSING_WINDOW_NEVER_HEALED_FROM_HELPER_PASS'
    Assert-CollectorFixture ($script:written['two-client-observer.json'].gaps -ccontains 'NOTPROVEN_WINDOW:peerAlive') 'NOTPROVEN_DIAGNOSTIC_SAVED'
    $script:omitWindow=$false;$script:shortWindow=$true
    $r=Invoke-NativeTwoClientCollectedScenario $row 'source' $script:mockFixture.base $script:mockFixture.target $null ([pscustomobject]@{helperSha256=('c'*64)}) 'java' 'evidence' 180 $authority -EvidenceKind UNIT_MOCK
    Assert-CollectorFixture ($r.status -ceq 'PENDING' -and $r.observationStatus -ceq 'NOTPROVEN' -and $script:acceptCalls -eq 1) 'SHORT_WINDOW_NOTPROVEN_NOT_TIMESTAMP_PADDING'
}

# Нет requests directory - реальное допустимое состояние; file/subdirectory не скрываются как empty.
& {
    $script:directoryMode='absent'
    function Resolve-PortableSafetyPath($Path) {return $Path}
    function Test-Path($LiteralPath,$PathType) {
        if ($script:directoryMode -ceq 'absent') {return $false}
        if ($PathType -ceq 'Container') {return $script:directoryMode -ceq 'directory'}
        return $true
    }
    function Get-ChildItem {return @([pscustomobject]@{Name='unexpected-subdirectory'})}
    Assert-CollectorFixture (@(Read-NativeTwoClientDirectoryEntries 'C:\mock\requests').Count -eq 0) 'ABSENT_REQUESTS_OBSERVED_EMPTY'
    $script:directoryMode='file'
    Assert-CollectorRejected {Read-NativeTwoClientDirectoryEntries 'C:\mock\requests'} 'TWO_COLLECT_EXPECTED_DIRECTORY'
    $script:directoryMode='directory'
    Assert-CollectorFixture (@(Read-NativeTwoClientDirectoryEntries 'C:\mock\requests')[0] -ceq 'unexpected-subdirectory') 'DIRECTORY_REMAINS_NOT_FILTERED_AWAY'
}
# Реальный CreateNew writer в собственном Temp UUID; bytes не запускаются и не native receipts.
$fixtureDirectory=Join-Path ([IO.Path]::GetTempPath()) ('cp-two-client-collector-fixture-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($fixtureDirectory)
$artifact=Write-NativeTwoClientCollectorArtifact $fixtureDirectory 'two-client-observer.json' ([pscustomobject]@{syntheticFixture=$true;nativeExecuted=$false})
Assert-CollectorFixture ($artifact.sha256 -ceq (Get-FileHash -LiteralPath $artifact.path).Hash.ToLowerInvariant()) 'REAL_JSON_WRITER_BYTE_ARRAY_PIN'
$copiedBytes=[Text.Encoding]::UTF8.GetBytes('# UNIT_MOCK bytes only, not production')
$artifact=Write-NativeTwoClientCollectorArtifact $fixtureDirectory 'production-apply-update.ps1' $copiedBytes -Bytes
Assert-CollectorFixture ($artifact.sha256 -ceq (Get-FileHash -LiteralPath $artifact.path).Hash.ToLowerInvariant() -and
    [Convert]::ToHexString([IO.File]::ReadAllBytes($artifact.path)) -ceq [Convert]::ToHexString($copiedBytes)) 'REAL_BYTES_WRITER_EXACT_COPY'
$overwritten=$false;try {Write-NativeTwoClientCollectorArtifact $fixtureDirectory 'two-client-observer.json' @{} | Out-Null} catch {$overwritten=$true}
Assert-CollectorFixture $overwritten 'CREATE_NEW_REJECTS_OVERWRITE'
Assert-CollectorRejected {Write-NativeTwoClientCollectorArtifact $fixtureDirectory '../outside.json' @{}} 'TWO_COLLECT_ARTIFACT_NAME'
Assert-CollectorRejected {Write-NativeTwoClientCollectorArtifact $fixtureDirectory 'production-apply-update.ps1' 'not-bytes' -Bytes} 'TWO_COLLECT_ARTIFACT_SIZE'
Write-Host ('Synthetic writer fixture directory: '+$fixtureDirectory)
foreach ($file in $pins.Keys) {Assert-CollectorFixture ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash.ToLowerInvariant() -ceq $pins[$file]) ('SOURCE_FROZEN_'+$file)}
Write-Host ('Two-client collector fixtures PASS: '+$script:checks+' checks; AST/pure/mocks only; nativeExecuted=false; no native PASS')
Write-Host ('Collector SHA256: '+(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'NativeUpdateTwoClientAcceptanceCollector.ps1')).Hash.ToLowerInvariant())
Write-Host ('Fixture SHA256: '+(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant())
