<#
.SYNOPSIS
Узкие route/API/mock fixtures bridge, без native PASS, GUI, Java или сборок.
.DESCRIPTION
Только новый suite. Existing acceptor suites не повторяются. Positive mocks всегда
UNIT_MOCK и PENDING; actual read-only acceptor проверяется только на missing evidence.
Собственный Temp UUID содержит dummy bytes, не независимые native observations.
#>
[CmdletBinding()]param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdateAcceptanceDispatch.ps1')
$script:bridgeChecks=0;$script:bridgeCalls=[Collections.Generic.List[object]]::new()
$pins=@{}
foreach ($file in @('NativeUpdateScenarioDispatch.ps1','Test-NativeUpdateLifecycle.ps1',
    'NativeUpdateConcurrentAcceptance.ps1','NativeUpdateReadyAcceptance.ps1','NativeUpdatePhaseAcceptance.ps1',
    'NativeUpdateRollbackAcceptance.ps1','NativeUpdatePayloadAcceptance.ps1',
    'NativeUpdateReadyAcceptanceCollector.ps1','NativeUpdateRollbackAcceptanceCollector.ps1')) {
    $pins[$file]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash
}

# Только fixture assertion, не native acceptance.
function Assert-BridgeFixture([bool]$Value,[string]$Code) {
    if (-not $Value) {throw ('BRIDGE_FIXTURE_ASSERT:'+ $Code)};$script:bridgeChecks++
}

# Читает определение из actual source без выполнения runner body.
function Get-BridgeFixtureDefinition([string]$File,[string]$Name) {
    $tokens=$null;$issues=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $File),[ref]$tokens,[ref]$issues)
    if ($issues.Count) {throw 'BRIDGE_FIXTURE_PARSE'}
    $defs=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $Name})
    Assert-BridgeFixture ($defs.Count -eq 1) ('actual export '+$Name)
    return $defs[0]
}

# Контракты проверяются по frozen текущим интерфейсам, не копиям validators.
foreach ($entry in @(
    @('NativeUpdateConcurrentAcceptance.ps1','Test-NativeConcurrentAcceptance','CellEvidence/BaseManifest/BaseSha256/TargetManifest/TargetSha256/ExpectedHelperSha256/SupplementalDirectory'),
    @('NativeUpdateTwoClientAcceptance.ps1','Test-NativeTwoClientAcceptance','CellEvidence/BaseManifest/BaseSha256/TargetManifest/TargetSha256/ExpectedHelperSha256/SupplementalDirectory/IndependentSha256/EvidenceKind'),
    @('NativeUpdateReadyAcceptance.ps1','Test-NativeReadyAcceptance','Row/Receipt/Base/Target/ReceiptSha256/IndependentFile/IndependentSha256'),
    @('NativeUpdatePhaseAcceptance.ps1','Assert-NativePhaseAcceptance','Row/Base/Target/Cold/Java'),
    @('NativeUpdateRollbackAcceptance.ps1','Test-NativeRollbackAcceptance','Row/Authority/RetainedHelper'),
    @('NativeUpdatePayloadAcceptance.ps1','Test-NativePayloadAcceptance','ObservationFile/ObservationSha256/Expected/EvidenceKind'),
    @('NativeUpdateConcurrentAcceptance.ps1','Invoke-NativeConcurrentScenarioWithAcceptance','Row/Source/Base/Target/Life/Cold/Java/Evidence/Timeout/AcceptancePins'),
    @('NativeUpdateReadyAcceptanceCollector.ps1','Invoke-NativeReadyAcceptedCell','Row/Source/Base/Target/Life/Cold/Java/Evidence/Timeout'),
    @('NativeUpdateReadyAcceptanceCollector.ps1','Import-NativeReadyCollectorDependencies','ScriptsRoot'),
    @('NativeUpdateRollbackAcceptanceCollector.ps1','Invoke-NativeRollbackCollectedAcceptance','Adapter/Row/Source/Base/Target/Life/Cold/Java/Evidence/Timeout/Authority/AcceptanceObserver'))) {
    $def=Get-BridgeFixtureDefinition $entry[0] $entry[1]
    $parameters=if ($null -ne $def.Body.ParamBlock) {$def.Body.ParamBlock.Parameters} else {$def.Parameters}
    Assert-BridgeFixture ((@($parameters | ForEach-Object {$_.Name.VariablePath.UserPath}) -join '/') -ceq $entry[2]) ('actual signature '+$entry[1])
}
$planDefinition=Get-BridgeFixtureDefinition 'Test-NativeUpdateLifecycle.ps1' 'Get-NativeEvidencePlan'
. ([scriptblock]::Create($planDefinition.Extent.Text))
$rows=@(Get-NativeEvidencePlan (Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'))
Assert-BridgeFixture ($rows.Count -eq 612 -and @($rows.scenario | Select-Object -Unique).Count -eq 22) 'canonical 22/612'
$routeDefinition=Get-BridgeFixtureDefinition 'NativeUpdateScenarioDispatch.ps1' 'Get-NativeDispatchRoute'
. ([scriptblock]::Create($routeDefinition.Extent.Text))
foreach ($row in $rows) {
    Assert-BridgeFixture ((Get-NativeAcceptanceRoute $row.scenario) -ceq (Get-NativeDispatchRoute $row.scenario)) 'same canonical route and phase'
    $verdict=Invoke-NativeUpdateAcceptanceDispatch $row $null $null $null $null '' $null
    Assert-BridgeFixture ($verdict.status -ceq 'PENDING' -and $row.status -ceq 'PENDING' -and $verdict.fullMatrix -ceq 'PENDING') 'unexecuted cells not accepted'
}
$owned=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
$tempParent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
if ((Test-Path -LiteralPath $owned) -or [IO.Path]::GetDirectoryName($owned) -cne $tempParent) {throw 'BRIDGE_FIXTURE_TEMP_SCOPE'}
$realReader=${function:Invoke-NativeAcceptanceRouteRead}
try {
    [void][IO.Directory]::CreateDirectory($owned)
    $dummy=Join-Path $owned 'dummy.json';[IO.File]::WriteAllText($dummy,'{}',[Text.UTF8Encoding]::new($false))
    $dummyPin=(Get-FileHash -LiteralPath $dummy).Hash.ToLowerInvariant()
    # Mock fields используют существующие dummy bytes, никогда не native executable/independent evidence.
    function New-BridgeFixtureRow([string]$Scenario,[string]$Phase='SESSION') {
        $r=[pscustomobject]@{scenario=$Scenario;base='B1';client='web';path='ascii';phase=$Phase;status='PENDING';reason='DEMOTED';
            dispatchHelperStatus='PASS';dispatchHelperReason='';executed=$true;exitCode=0;failures=0;skipped=0;
            exe=$dummy;baseCommit=('a'*40);targetCommit=('b'*40);baseRelease=1001;targetRelease=1003;args=@('--test-api');
            startedAt='2026-10-04T00:00:00Z';finishedAt='2026-10-04T00:00:01Z';workRoot=$owned;evidence=$owned;
            evidenceDirectory=$owned;concurrentCellEvidence=$owned;payloadObservation=$dummy}
        foreach ($field in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog','command') {$r | Add-Member $field $dummy}
        return $r
    }
    # Реальные manifests здесь не создаются: только data для mock передачи и missing actual acceptors.
    $base=[pscustomobject]@{commitSha=('a'*40);releaseNumber=1001};$target=[pscustomobject]@{commitSha=('b'*40);releaseNumber=1003}
    $contexts=@{
        ready=[pscustomobject]@{Receipt=[pscustomobject]@{scope='MOCK_NOT_NATIVE'};ReceiptSha256=$dummyPin;IndependentFile=$dummy;IndependentSha256=$dummyPin}
        concurrent=[pscustomobject]@{CellEvidence=$owned;BaseManifest=$dummy;BaseSha256=$dummyPin;TargetManifest=$dummy;TargetSha256=$dummyPin;ExpectedHelperSha256=$dummyPin;SupplementalDirectory=$owned}
        rollback=[pscustomobject]@{Authority=[pscustomobject]@{evidenceKind='UNIT_MOCK'};RetainedHelper=[pscustomobject]@{notAProcess=$true}}
        payload=[pscustomobject]@{ObservationFile=$dummy;ObservationSha256=$dummyPin;Expected=[pscustomobject]@{Scenario='cashmemory';Base='B1';Client='web';Path='ascii'}}
        phase=[pscustomobject]@{}
    }
    # Positive contracts дают только route validation, внешний kind UNIT_MOCK не разрешает native PASS.
    function Invoke-NativeAcceptanceRouteRead([string]$Route,$Row,$Receipt,$Base,$Target,$Cold,[string]$Java,$Evidence,[string]$EvidenceKind) {
        $script:bridgeCalls.Add([pscustomobject]@{route=$Route;row=$Row;receipt=$Receipt;evidence=$Evidence})
        switch ($Route) {
            'ready' {return [pscustomobject]@{status='PENDING';scope='READY_CELL_ONLY';cellEvidenceValidated=$true}}
            'phase' {return [pscustomobject]@{status='RECEIPT_CONTRACT_VALIDATED'}}
            'concurrent' {
                if ($Row.scenario -ceq 'two-clients') {return [pscustomobject]@{status='PASS';scope='NATIVE_TWO_CLIENT_WAIT_BARRIER';proofComplete=$true}}
                return [pscustomobject]@{status='PASS';scope='NATIVE_STALE_LEASE_BIRTH_MISMATCH'}
            }
            'rollback' {return [pscustomobject]@{status='PASS';scope='SINGLE_CELL_GUARDED_HELPER_CODE_NOT_OS_WIDE_TRACE';nativePass=$true}}
            'payload' {return [pscustomobject]@{status='PASS';scope=$Row.scenario;proofComplete=$true}}
        }
    }
    foreach ($scenario in 'three-clients-pid-root-isolation','abrupt-ready-restart','launch-applying-safe-args','journal-fault','per-move-fault','helper-runtime-death','locked-rollback','readonly-rollback','disk-full-rollback','cashmemory','unmanaged-old-or-new','unicode-payload') {
        $route=Get-NativeAcceptanceRoute $scenario;$phase=if ($scenario -cin @('journal-fault','per-move-fault')) {'WAITING'} else {'SESSION'}
        $r=New-BridgeFixtureRow $scenario $phase
        if ($route -ceq 'payload') {$contexts.payload.Expected.Scenario=$scenario}
        $receipt=[pscustomobject]@{status='MOCK_ONLY'}
        $envelope=[pscustomobject]@{status='PENDING';scope='NATIVE_HELPER_RECEIPTS_ONLY';cellKey=($scenario+'/B1/web/ascii/'+$phase);helperReceipts=@($receipt)}
        $v=Invoke-NativeUpdateAcceptanceDispatch $r $envelope $base $target ([pscustomobject]@{}) $dummy $contexts[$route] -EvidenceKind UNIT_MOCK
        Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.routeEvidenceValidated -and $v.gaps -ccontains 'MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED') ('mock not native '+$scenario)
        Assert-BridgeFixture ($r.status -ceq 'PENDING' -and $r.reason -ceq 'DEMOTED' -and $r.dispatchHelperStatus -ceq 'PASS') 'canonical status and original helper status untouched'
        Assert-BridgeFixture ([object]::ReferenceEquals($v.envelope,$envelope) -and [object]::ReferenceEquals($v.envelope.helperReceipts[0],$receipt)) 'raw receipts preserved'
        if ($route -ceq 'phase') {Assert-BridgeFixture ($v.copiedRow.status -ceq 'PASS' -and $v.copiedRow.reason -ceq '' -and $v.gaps -ccontains 'PHASE_CONTROLLED_SESSION_INDEPENDENT_RECEIPTS_NOT_PRODUCED') 'pre-demotion phase copy and explicit proof gap'}
    }
    $r=New-BridgeFixtureRow 'two-clients'
    $before=$script:bridgeCalls.Count;$v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $contexts.concurrent -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'EVIDENCE:concurrent:IndependentSha256' -and $script:bridgeCalls.Count -eq $before) 'no wrong three-client acceptor fallback'
    # Explicit frozen two-client schema; UNIT_MOCK даже с положительным validator не принимает native клетку.
    $twoContext=$contexts.concurrent | Select-Object *
    $twoContext | Add-Member IndependentSha256 $dummyPin
    $twoContext | Add-Member CollectorObservationStatus 'OBSERVED'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $twoContext -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.routeEvidenceValidated -and $v.acceptorVerdict.scope -ceq 'NATIVE_TWO_CLIENT_WAIT_BARRIER' -and
        $v.gaps -ccontains 'MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED' -and $r.status -ceq 'PENDING') 'two-client mock proof never native PASS'
    $twoContext.CollectorObservationStatus='NOTPROVEN';$before=$script:bridgeCalls.Count
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $twoContext -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'TWO_CLIENT_OBSERVATIONS_NOT_PROVEN' -and $script:bridgeCalls.Count -eq $before) 'two-client incomplete observer blocks acceptor'
    $twoContext.CollectorObservationStatus='OBSERVED'
    $r=New-BridgeFixtureRow 'abrupt-ready-restart'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $null -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'EVIDENCE:ready:IndependentFile') 'helper return does not synthesize independent'
    $r.status='FAIL';$v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $null
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'HELPER_REPORTED_FAILURE') 'failure outranks missing'
    $r=New-BridgeFixtureRow 'Delta';$v=Invoke-NativeUpdateAcceptanceDispatch $r $null $null $null $null '' $null
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'ACCEPT_DISPATCH_UNKNOWN_SCENARIO') 'case-sensitive routes'
    $r=New-BridgeFixtureRow 'abrupt-ready-restart';$r.exitCode=7
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $null
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'COMMON_FAILURE:exitCode') 'common contradiction outranks absent proof'
    $r=New-BridgeFixtureRow 'cashmemory';$r.payloadObservation=Join-Path $owned 'different.json';$contexts.payload.Expected.Scenario='cashmemory'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $contexts.payload -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'PAYLOAD_OBSERVATION_CONTEXT') 'payload row substitution rejected'
    $r=New-BridgeFixtureRow 'journal-fault' 'WAITING';$r.dispatchHelperStatus='PENDING'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target ([pscustomobject]@{}) $dummy $contexts.phase
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.copiedRow.status -ceq 'PENDING') 'no invented pre-demotion pass'
    $r=New-BridgeFixtureRow 'abrupt-ready-restart'
    $wrongEnvelope=[pscustomobject]@{status='PENDING';scope='NATIVE_HELPER_RECEIPTS_ONLY';cellKey='WRONG_CELL';helperReceipts=@()}
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $wrongEnvelope $base $target $null '' $contexts.ready -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'ACCEPT_DISPATCH_ENVELOPE_CONTEXT') 'receipt envelope cannot substitute cell'
    $r.PSObject.Properties.Remove('args')
    $before=$script:bridgeCalls.Count
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $contexts.ready -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'COMMON_FIELD:args' -and $script:bridgeCalls.Count -eq $before) 'missing common artifact contract prevents acceptance'
    function Invoke-NativeAcceptanceRouteRead([string]$Route,$Row,$Receipt,$Base,$Target,$Cold,[string]$Java,$Evidence,[string]$EvidenceKind) {
        return [pscustomobject]@{status='PASS';scope='WRONG_SCOPE';nativePass=$true}
    }
    $r=New-BridgeFixtureRow 'locked-rollback'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $contexts.rollback -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'ACCEPT_DISPATCH_VERDICT_SCOPE') 'wrong acceptance scope is contradiction'
    # Actual isolated runspace и текущий rollback acceptor, только UNIT_MOCK missing path.
    Set-Item Function:Invoke-NativeAcceptanceRouteRead $realReader
    $r=New-BridgeFixtureRow 'delta'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $null -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'EVIDENCE:normal:Authority' -and $v.gaps -cnotcontains 'NORMAL_ACCEPTANCE_OUTSIDE_BRIDGE_SCOPE') 'normal missing pre-native authority pending'
    $normal=[pscustomobject]@{Authority=[pscustomobject]@{origin='MAIN_PRE_NATIVE_NORMAL_COLLECTOR';sourcePins=@{};intent=@{};
        intentFile=$dummy;intentSha256=$dummyPin;createdUtc='2026-10-04T00:00:00Z';nonce='UNIT_MOCK';bound=$false;sealed=$false};SourcePins=@{}}
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $normal -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'NORMAL_PRE_NATIVE_BIND_POST_CLEANUP_SEAL_REQUIRED') 'normal partial collector pending'
    $normal.Authority.bound=$true;$normal.Authority.sealed=$true
    foreach ($field in 'expectedFile','indexFile') {$normal.Authority | Add-Member $field $dummy}
    foreach ($field in 'expectedSha256','indexSha256') {$normal.Authority | Add-Member $field $dummyPin}
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $normal -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and @($v.contradictions | Where-Object {$_ -like 'NORMAL_MAIN_SOURCE_PIN:*'}).Count -gt 0) ('actual private normal reader rejects missing source inventory: '+($v.contradictions -join ';'))
    # Настоящий private reader проходит source guards, затем отвергает wrong cell, не mock verdict.
    $normalFiles=@('NativeUpdateNormalAcceptanceCollector.ps1','NativeUpdateNormalAcceptance.ps1','NativeUpdatePayloadAcceptance.ps1',
        'NativeUpdatePayloadScenarios.ps1','Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1',
        'New-NativeUpdateArtifacts.ps1','New-NativeUpdateLifecycleConfig.ps1','New-UpdateBootstrapCommands.ps1','S7-Release.ps1',
        'NativeUpdateScenarioDispatch.ps1','NativeUpdateAcceptanceDispatch.ps1')
    foreach ($file in $normalFiles) {
        $pin=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash.ToLowerInvariant()
        $normal.SourcePins[$file]=$pin;$normal.Authority.sourcePins[$file]=$pin
    }
    $normal.Authority.intent=[pscustomobject]@{Scenario='offline';Base='B1';Client='web';Path='ascii';Phase='SESSION'}
    $normal.Authority | Add-Member kind 'UNIT_MOCK'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $normal -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'NORMAL_MAIN_CELL_IDENTITY') 'actual normal reader source-good wrong cell rejected'
    $normal.Authority.intent.Scenario='delta';$normal.SourcePins['NativeUpdateNormalAcceptanceCollector.ps1']='0'*64
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $normal -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'NORMAL_MAIN_SOURCE_PIN:NativeUpdateNormalAcceptanceCollector.ps1') 'independent closure source pin tamper rejected'
    $normal.Authority.origin='ROW_RETURN'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $normal -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'FAIL' -and $v.contradictions -ccontains 'NORMAL_MAIN_AUTHORITY_CONTEXT') 'late row authority rejected'
    $r=New-BridgeFixtureRow 'locked-rollback'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $contexts.rollback -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'ACCEPTOR:UNIT_MOCK_NOT_NATIVE_EVIDENCE') ('actual reader and rollback entry no process: '+($v.contradictions -join ';')+' gaps='+($v.gaps -join ';'))
    $r=New-BridgeFixtureRow 'journal-fault' 'WAITING'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target ([pscustomobject]@{}) $dummy $contexts.phase -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'PHASE_ACCEPT_MISSING') ('actual phase incomplete row: '+($v.contradictions -join ';'))
    $r=New-BridgeFixtureRow 'cashmemory';$contexts.payload.Expected.Scenario='cashmemory'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $contexts.payload
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'ACCEPTOR:EXPECTED_SourceRoot') ('actual payload incomplete independent context: '+($v.contradictions -join ';'))
    $r=New-BridgeFixtureRow 'three-clients-pid-root-isolation'
    $contexts.concurrent.BaseManifest=Join-Path $owned 'not-produced-base.json'
    $contexts.concurrent.TargetManifest=Join-Path $owned 'not-produced-target.json'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $contexts.concurrent -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'ACCEPTOR:pinned manifests') ('actual concurrent missing manifests: '+($v.contradictions -join ';'))
    $r=New-BridgeFixtureRow 'two-clients'
    $twoContext.BaseManifest=Join-Path $owned 'not-produced-base.json';$twoContext.TargetManifest=Join-Path $owned 'not-produced-target.json'
    $v=Invoke-NativeUpdateAcceptanceDispatch $r $null $base $target $null '' $twoContext -EvidenceKind UNIT_MOCK
    Assert-BridgeFixture ($v.status -ceq 'PENDING' -and $v.gaps -ccontains 'ACCEPTOR:pinned manifests' -and $r.status -ceq 'PENDING') 'actual isolated two-client entry missing evidence pending'
    Assert-BridgeFixture (@($rows | Where-Object status -CNE 'PENDING').Count -eq 0) 'all 612 canonical rows remain pending'
} finally {
    Set-Item Function:Invoke-NativeAcceptanceRouteRead $realReader
    if (Test-Path -LiteralPath $owned) {
        $full=[IO.Path]::GetFullPath($owned)
        if ($full -cne $owned -or [IO.Path]::GetDirectoryName($full) -cne $tempParent -or
            [IO.Path]::GetFileName($full) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
            ((Get-Item -LiteralPath $full).Attributes -band [IO.FileAttributes]::ReparsePoint)) {throw 'BRIDGE_FIXTURE_CLEANUP_SCOPE'}
        Remove-Item -LiteralPath $full -Recurse -Force
    }
}
foreach ($file in $pins.Keys) {Assert-BridgeFixture ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash -ceq $pins[$file]) ('frozen owner source unchanged '+$file)}
[pscustomobject]@{scope='ACCEPTANCE_BRIDGE_API_AND_MOCK_ONLY';checks=$script:bridgeChecks;nativeExecuted=$false;nativeStatus='PENDING';canonicalCells=612;fullMatrix='PENDING'}
