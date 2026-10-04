<#
.SYNOPSIS
Новые focused final assembly fixtures. Dummy data никогда не дают native signoff.
.DESCRIPTION
Полный mock merge возвращает ASSEMBLY_NOT_SIGNOFF; final publication с mocks не вызывается.
Проверка actual JDK CLI только отрицательная PENDING, в own Temp без GUI/Maven/build.
Другие fixture suites не выполняются. Artifacts сохраняются как MOCK_ONLY.
#>
[CmdletBinding()]param([switch]$SkipJdk)
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'Test-NativeUpdateEvidenceSignoff.ps1')
$checks=0;$rejects=0
function Assert-SignoffFixture([bool]$Condition,[string]$Code) {if (-not $Condition) {throw "FIXTURE_ASSERT:$Code"};$script:checks++}
function Reject-SignoffFixture([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "FIXTURE_REJECT expected=$Code actual=$caught"};$script:checks++;$script:rejects++
}
function Copy-SignoffFixture($Object) {
    $options=@{InputObject=(ConvertTo-Json -InputObject $Object -Depth 64);Depth=64;NoEnumerate=$true}
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {$options.DateKind='String'}
    return ,(ConvertFrom-Json @options)
}
$owned=Join-Path ([IO.Path]::GetTempPath()) ('cp-signoff-fixtures-'+[guid]::NewGuid().ToString())
$null=New-Item -ItemType Directory -Path $owned
function Mock-SignoffJson([string]$Name,$Object) {return Write-SignoffNew (Join-Path $owned $Name) $Object}
function Mock-SignoffBytes([string]$Name,[string]$Text) {
    $path=Join-Path $owned $Name;Write-SignoffBytesNew $path ([Text.Encoding]::UTF8.GetBytes($Text));return New-SignoffPin $path
}
$sourceFiles=@(
    (Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'),
    (Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidenceVerifier.java'),
    (Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'),(Join-Path $PSScriptRoot 'NativeUpdateAcceptanceDispatch.ps1')
)
$sourcePins=@($sourceFiles | ForEach-Object {New-SignoffPin ([IO.Path]::GetFullPath($_))})
$clock=[datetime]::UtcNow.AddMinutes(-1)
$manifest=[pscustomobject]@{schemaVersion=2;commitSha=('b'*40);releaseNumber=1003;treeSha256=('d'*64);files=@()}
$base1=[pscustomobject]@{schemaVersion=2;commitSha=('a'*40);releaseNumber=1001;treeSha256=('e'*64);files=@()}
$base2=[pscustomobject]@{schemaVersion=2;commitSha=('c'*40);releaseNumber=1002;treeSha256=('f'*64);files=@()}
$targetPin=Mock-SignoffJson 'target.json' $manifest
$basePin1=Mock-SignoffJson 'base1.json' $base1;$basePin2=Mock-SignoffJson 'base2.json' $base2
$served=Mock-SignoffJson 'update.json' $manifest
$helper=Mock-SignoffBytes 'helper.txt' 'MOCK_NOT_PRODUCTION'
$core=Mock-SignoffBytes 'core.jar' 'MOCK_NOT_JAVA_CODE'
$tool=Mock-SignoffBytes 'tool.jar' 'MOCK_NOT_JAVA_CODE'
$cold=[pscustomobject]@{schemaVersion=1;helperScript=$helper.path;helperSha256=$helper.sha256;runtimeSha256=('a'*64);
    toolFiles=@($core,$tool);toolArguments=@('--module-path',($core.path+';'+$tool.path),'-m','ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool');targetManifest=$targetPin.path;targetManifestSha256=$targetPin.sha256;
    baseManifests=@([pscustomobject]@{manifest=$basePin1.path;sha256=$basePin1.sha256},[pscustomobject]@{manifest=$basePin2.path;sha256=$basePin2.sha256})}
$life=[pscustomobject]@{schemaVersion=1;artifactDir=$owned;manifestSha256=$served.sha256;harnessClasspath=$core.path;harnessFiles=@($core)}
$coldPin=Mock-SignoffJson 'cold.json' $cold;$lifePin=Mock-SignoffJson 'life.json' $life
$context=[pscustomobject][ordered]@{schemaVersion=1;sourceIdentity=[guid]::NewGuid().ToString();freshAfterUtc=$clock.ToString('o');
    sourcePins=$sourcePins;coldPin=$coldPin;lifePin=$lifePin;planSourcePin=$sourcePins[0];planReaderPin=$sourcePins[2]}
$config=Assert-SignoffContext $context @{}
$badCold=Copy-SignoffFixture $cold;$badCold.toolFiles=@($core,$core)
$badColdPin=Mock-SignoffJson 'duplicate-tool-config.json' $badCold
$badContext=Copy-SignoffFixture $context;$badContext.coldPin=$badColdPin
Reject-SignoffFixture {Assert-SignoffContext $badContext @{}} 'SIGNOFF_CONFIG'
$badCold=Copy-SignoffFixture $cold;$badCold.toolArguments[1]=$core.path+';'+$helper.path
$badColdPin=Mock-SignoffJson 'wrong-tool-config.json' $badCold
$badContext=Copy-SignoffFixture $context;$badContext.coldPin=$badColdPin
Reject-SignoffFixture {Assert-SignoffContext $badContext @{}} 'SIGNOFF_CONFIG'
$badLife=Copy-SignoffFixture $life;$badLife.harnessClasspath=$tool.path
$badLifePin=Mock-SignoffJson 'wrong-harness-config.json' $badLife
$badContext=Copy-SignoffFixture $context;$badContext.lifePin=$badLifePin
Reject-SignoffFixture {Assert-SignoffContext $badContext @{}} 'SIGNOFF_CONFIG'
$badLife=Copy-SignoffFixture $life;$badLife.harnessFiles=@($core,$core)
$badLifePin=Mock-SignoffJson 'duplicate-harness-config.json' $badLife
$badContext=Copy-SignoffFixture $context;$badContext.lifePin=$badLifePin
Reject-SignoffFixture {Assert-SignoffContext $badContext @{}} 'SIGNOFF_CONFIG'
$plan=@(Get-SignoffPlan $context @{})
Assert-SignoffFixture ($plan.Count -eq 612) 'REUSES_CURRENT_FROZEN_PLAN'
$rows=@(foreach ($planned in $plan) {
    $row=Copy-SignoffFixture $planned;$row.status='PASS';$row.reason='';$row | Add-Member executed $true
    foreach ($field in 'exitCode','failures','skipped') {$row | Add-Member $field 0}
    $row | Add-Member startedAt $clock.AddSeconds(1).ToString('o');$row | Add-Member finishedAt $clock.AddSeconds(2).ToString('o')
    $row | Add-Member baseCommit $(if ($row.base -eq 'B1') {$base1.commitSha} else {$base2.commitSha})
    $row | Add-Member baseRelease $(if ($row.base -eq 'B1') {1001} else {1002})
    $row | Add-Member targetCommit $manifest.commitSha;$row | Add-Member targetRelease 1003
    $row | Add-Member exe (Join-Path $owned 'MOCK_NOT_EXECUTABLE.exe');$row | Add-Member args @('--MOCK-NOT-NATIVE')
    $row
})
$artifactPins=@(foreach ($name in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog','command') {
    Mock-SignoffJson ($name+'.json') ([pscustomobject]@{kind='UNIT_MOCK_NOT_NATIVE'})
})
foreach ($row in $rows) {
    for ($i=0;$i -lt $artifactPins.Count;$i++) {$row | Add-Member ([IO.Path]::GetFileNameWithoutExtension($artifactPins[$i].path)) $artifactPins[$i].path}
}
$verdict=[pscustomobject]@{status='PASS';scope='SELECTED_NATIVE_CELL_ACCEPTANCE_ONLY';cellKey='';route='MOCK';routeEvidenceValidated=$true;
    canonicalRowUnchanged=$true;gaps=@();contradictions=@();acceptorVerdict=[pscustomobject]@{kind='UNIT_MOCK'};copiedRow=$null;envelope=$null;
    evidenceKind='NATIVE';fullMatrix='PENDING';releaseProvenance='PENDING'}
# Синтетические NATIVE-shaped объекты только проверяют wire-контракт, не final publication.
$proofs=@(foreach ($row in $rows) {
    $decision=Copy-SignoffFixture $verdict;$decision.cellKey=Get-SignoffCellKey $row;$decision.copiedRow=Copy-SignoffFixture $row
    [pscustomobject][ordered]@{schemaVersion=1;scope='MAIN_ACCEPTED_NATIVE_CELL';evidenceKind='NATIVE';sourceIdentity=$context.sourceIdentity;
        contextSha256=(Get-SignoffDigest $context);cellKey=$decision.cellKey;rowSha256=(Get-SignoffDigest $row);
        acceptedAt=$clock.AddSeconds(3).ToString('o');verdict=$decision;evidencePins=$artifactPins}
})
$good=$proofs[0];$row=$rows[0]
Assert-SignoffFixture ((Assert-SignoffAcceptedProof $good $row $context $owned @{}) -ceq (Get-SignoffCellKey $row)) 'MOCK_PROOF_CONTRACT_ONLY'
foreach ($mutation in @(
    @{code='SIGNOFF_PROOF_BINDING';edit={param($p) $p.evidenceKind='UNIT_MOCK'}},
    @{code='SIGNOFF_PROOF_BINDING';edit={param($p) $p.sourceIdentity=[guid]::NewGuid().ToString()}},
    @{code='SIGNOFF_PROOF_BINDING';edit={param($p) $p.contextSha256=('f'*64)}},
    @{code='SIGNOFF_PROOF_BINDING';edit={param($p) $p.rowSha256=('f'*64)}},
    @{code='SIGNOFF_ACCEPTANCE_PENDING';edit={param($p) $p.verdict.status='PENDING'}},
    @{code='SIGNOFF_ACCEPTANCE_PENDING';edit={param($p) $p.verdict.evidenceKind='UNIT_MOCK'}},
    @{code='SIGNOFF_ACCEPTANCE_PENDING';edit={param($p) $p.verdict.routeEvidenceValidated='true'}},
    @{code='SIGNOFF_ACCEPTANCE_PENDING';edit={param($p) $p.verdict.gaps=@('MISSING_OBSERVER')}},
    @{code='SIGNOFF_ACCEPTANCE_PENDING';edit={param($p) $p.verdict.contradictions=@('CHANGED')}},
    @{code='SIGNOFF_PROOF_ARTIFACTS';edit={param($p) $p.evidencePins=@($p.evidencePins | Select-Object -First 8)}},
    @{code='SIGNOFF_OLD_PROVENANCE';edit={param($p) $p.acceptedAt='2000-01-01T00:00:00Z'}}
)) {
    $bad=Copy-SignoffFixture $good;& $mutation.edit $bad
    Reject-SignoffFixture {Assert-SignoffAcceptedProof $bad $row $context $owned @{}} $mutation.code
}
$oldContext=Copy-SignoffFixture $context;$oldContext.freshAfterUtc=$clock.AddSeconds(10).ToString('o')
$oldProof=Copy-SignoffFixture $good;$oldProof.contextSha256=Get-SignoffDigest $oldContext
Reject-SignoffFixture {Assert-SignoffAcceptedProof $oldProof $row $oldContext $owned @{}} 'SIGNOFF_OLD_PROVENANCE'
$pendingRow=Copy-SignoffFixture $row;$pendingRow.status='PENDING';$pendingProof=Copy-SignoffFixture $good;$pendingProof.rowSha256=Get-SignoffDigest $pendingRow
Reject-SignoffFixture {Assert-SignoffAcceptedProof $pendingProof $pendingRow $context $owned @{}} 'SIGNOFF_ROW_NOT_ACCEPTED'
$wrongRow=Copy-SignoffFixture $row;$wrongRow.executed='true';$wrongProof=Copy-SignoffFixture $good;$wrongProof.rowSha256=Get-SignoffDigest $wrongRow
$wrongProof.verdict.copiedRow=$wrongRow
Reject-SignoffFixture {Assert-SignoffAcceptedProof $wrongProof $wrongRow $context $owned @{}} 'SIGNOFF_ROW_NOT_ACCEPTED'
$duplicateJson=Mock-SignoffBytes 'duplicate.json' '{"schemaVersion":1,"schemaVersion":1}'
Reject-SignoffFixture {Read-SignoffJson $duplicateJson @{}} 'SIGNOFF_JSON_DUPLICATE'
$changed=Mock-SignoffBytes 'changed.txt' 'BEFORE';[IO.File]::WriteAllText($changed.path,'AFTER')
Reject-SignoffFixture {Read-SignoffPin $changed @{}} 'SIGNOFF_CHANGED'
$hugePath=Join-Path $owned 'huge.txt';Write-SignoffBytesNew $hugePath ([byte[]]::new(8388609));$huge=New-SignoffPin $hugePath
Reject-SignoffFixture {Read-SignoffPin $huge @{}} 'SIGNOFF_SIZE'
$mockVerdict=Copy-SignoffFixture $verdict;$mockVerdict.cellKey=Get-SignoffCellKey $row;$mockVerdict.evidenceKind='UNIT_MOCK'
Reject-SignoffFixture {Save-NativeEvidenceCellProof $row $mockVerdict $context $owned $artifactPins (Join-Path $owned 'forbidden-proof.json')} 'SIGNOFF_ACCEPTANCE_PENDING'
Assert-SignoffFixture (-not (Test-Path -LiteralPath (Join-Path $owned 'forbidden-proof.json'))) 'MOCK_NOT_SEALED_AS_NATIVE'

# Только reader/merge provider scoped mock: диск хранит one proof, остальные in-memory mocks.
$resultPin=Mock-SignoffJson 'batch-results.json' ([pscustomobject]@{schemaVersion=1;status='PENDING';cells=$rows})
$proofPin=Mock-SignoffJson 'one-proof.json' $good
$seal=[pscustomobject][ordered]@{schemaVersion=1;scope='FROZEN_NATIVE_BATCH';sourceIdentity=$context.sourceIdentity;
    contextSha256=(Get-SignoffDigest $context);evidenceRoot=$owned;sealedAt=$clock.AddSeconds(4).ToString('o');results=$resultPin;proofs=@($proofPin)}
$sealPin=Mock-SignoffJson 'batch-seal.json' $seal
$partial=Merge-NativeEvidenceBatches $context @($sealPin) $plan $config @{}
Assert-SignoffFixture ($partial.rows.Count -eq 1 -and $partial.missing.Count -eq 611 -and $partial.status -ceq 'ASSEMBLY_NOT_SIGNOFF') 'NO_HELPER_PASS_PROMOTION'
Reject-SignoffFixture {Merge-NativeEvidenceBatches $context @($sealPin,$sealPin) $plan $config @{}} 'SIGNOFF_BATCH_DUPLICATE'
$duplicateSeal=Copy-SignoffFixture $seal;$duplicateSeal.proofs=@($proofPin,$proofPin)
$duplicateSealPin=Mock-SignoffJson 'duplicate-seal.json' $duplicateSeal
Reject-SignoffFixture {Merge-NativeEvidenceBatches $context @($duplicateSealPin) $plan $config @{}} 'SIGNOFF_PROOF_DUPLICATE'
$wrongSeal=Copy-SignoffFixture $seal;$wrongSeal.sourceIdentity=[guid]::NewGuid().ToString()
$wrongSealPin=Mock-SignoffJson 'wrong-source-seal.json' $wrongSeal
Reject-SignoffFixture {Merge-NativeEvidenceBatches $context @($wrongSealPin) $plan $config @{}} 'SIGNOFF_BATCH_CONTEXT'
$otherResultPin=Mock-SignoffJson 'other-batch-results.json' ([pscustomobject]@{schemaVersion=1;status='PENDING';cells=$rows})
$otherSeal=Copy-SignoffFixture $seal;$otherSeal.results=$otherResultPin
$otherSealPin=Mock-SignoffJson 'other-batch-seal.json' $otherSeal
Reject-SignoffFixture {Merge-NativeEvidenceBatches $context @($sealPin,$otherSealPin) $plan $config @{}} 'SIGNOFF_ACCEPTED_DUPLICATE'
$missingResults=Mock-SignoffJson 'missing-cell-results.json' ([pscustomobject]@{schemaVersion=1;status='PENDING';cells=@($rows | Select-Object -Skip 1)})
$missingSeal=Copy-SignoffFixture $seal;$missingSeal.results=$missingResults
$missingSealPin=Mock-SignoffJson 'missing-cell-seal.json' $missingSeal
Reject-SignoffFixture {Merge-NativeEvidenceBatches $context @($missingSealPin) $plan $config @{}} 'SIGNOFF_BATCH_RESULTS'
$duplicateRows=Copy-SignoffFixture $rows;$duplicateRows[-1]=$duplicateRows[0]
$duplicateResults=Mock-SignoffJson 'duplicate-row-results.json' ([pscustomobject]@{schemaVersion=1;status='PENDING';cells=$duplicateRows})
$duplicateRowSeal=Copy-SignoffFixture $seal;$duplicateRowSeal.results=$duplicateResults
$duplicateRowSealPin=Mock-SignoffJson 'duplicate-row-seal.json' $duplicateRowSeal
Reject-SignoffFixture {Merge-NativeEvidenceBatches $context @($duplicateRowSealPin) $plan $config @{}} 'SIGNOFF_CELL_IDENTITY'
$oldConfigSeal=Copy-SignoffFixture $seal;$oldConfigSeal.contextSha256=('f'*64)
$oldConfigPin=Mock-SignoffJson 'config-mismatch-seal.json' $oldConfigSeal
Reject-SignoffFixture {Merge-NativeEvidenceBatches $context @($oldConfigPin) $plan $config @{}} 'SIGNOFF_BATCH_CONTEXT'
$rowChangedProof=Copy-SignoffFixture $good;$rowChangedProof.verdict.copiedRow.command='OTHER'
Reject-SignoffFixture {Assert-SignoffAcceptedProof $rowChangedProof $row $context $owned @{}} 'SIGNOFF_ACCEPTED_ROW_CHANGED'
$badConfig=Copy-SignoffFixture $config;$badConfig.target.commitSha=('e'*40)
Reject-SignoffFixture {Merge-NativeEvidenceBatches $context @($sealPin) $plan $badConfig @{}} 'SIGNOFF_CONFIG_ROW'
$realReader=${function:Read-SignoffJson}
& {
    function Read-SignoffJson($Pin,$Seen) {
        if ($Pin.path -ceq $sealPin.path) {$full=Copy-SignoffFixture $seal;$full.proofs=@($proofs | ForEach-Object {[pscustomobject]@{path=(Join-Path $owned ($_.cellKey.Replace('/','_')+'.proof'));sha256=('a'*64)}});return $full}
        if ($Pin.path.EndsWith('.proof')) {return $proofs | Where-Object {(Join-Path $owned ($_.cellKey.Replace('/','_')+'.proof')) -ceq $Pin.path}}
        return & $realReader $Pin $Seen
    }
    $full=Merge-NativeEvidenceBatches $context @($sealPin) $plan $config @{}
    Assert-SignoffFixture ($full.rows.Count -eq 612 -and $full.missing.Count -eq 0 -and $full.status -ceq 'ASSEMBLY_NOT_SIGNOFF') 'FULL_MOCK_ASSEMBLY_NOT_NATIVE_SIGNOFF'
}
$request=[pscustomobject]@{schemaVersion=1;context=$context;batches=@($sealPin);verifier=[pscustomobject]@{};
    outputDirectory=(Join-Path ([IO.Path]::GetTempPath()) ('cp-native-signoff-'+[guid]::NewGuid().ToString()))}
$requestPin=Mock-SignoffJson 'partial-request.json' $request
$diagnostic=Invoke-NativeUpdateEvidenceSignoff $requestPin.path $requestPin.sha256
Assert-SignoffFixture ($diagnostic.status -ceq 'PENDING' -and $null -eq $diagnostic.results -and
    -not (Test-Path -LiteralPath (Join-Path $request.outputDirectory 'results.json'))) 'PARTIAL_DIAGNOSTIC_NO_COMPLETION'
$request.outputDirectory=Join-Path ([IO.Path]::GetTempPath()) ('cp-native-signoff-'+[guid]::NewGuid().ToString())
$signoffRequest=Mock-SignoffJson 'reject-signoff-request.json' $request
Reject-SignoffFixture {Invoke-NativeUpdateEvidenceSignoff $signoffRequest.path $signoffRequest.sha256 -Signoff} 'SIGNOFF_INCOMPLETE'
Assert-SignoffFixture (-not (Test-Path -LiteralPath (Join-Path $request.outputDirectory 'results.json'))) 'SIGNOFF_REJECT_HAS_NO_PASS_RESULTS'
if (-not $SkipJdk) {
    # Проверяем текущий собранный стенд, а не историческую папку разработчика в Temp.
    $projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $jdkClasses=Join-Path $projectRoot 'ui-parity/target/test-classes'
    $classPins=@(foreach ($name in 'UpdateEvidenceVerifier','UpdateEvidence','FixtureAuthority') {
        New-SignoffPin (Join-Path $jdkClasses ('ru/cashprediction/parity/update/'+$name+'.class'))
    })
    $javaPath=if ($env:JAVA_HOME) {Join-Path $env:JAVA_HOME 'bin/java.exe'} else {(Get-Command java.exe -ErrorAction Stop).Source}
    $builtCore=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core/target') -File -Filter 'cashprediction-core-*.jar' |
        Where-Object {$_.Name -notmatch '-(tests|sources|javadoc)\.jar$'})
    if ($builtCore.Count -ne 1) {throw 'SIGNOFF_FIXTURE_BUILD_REQUIRED'}
    $javaPin=New-SignoffPin $javaPath;$cliCore=New-SignoffPin $builtCore[0].FullName
    $cliConfig=Copy-SignoffFixture $config;$cliConfig.cold.runtimeSha256=$javaPin.sha256;$cliConfig.cold.toolFiles=@($cliCore)
    $cliOutput=Join-Path $owned 'negative-cli';[void][IO.Directory]::CreateDirectory($cliOutput)
    $pendingPath=Join-Path $cliOutput 'results.json';[void](Write-SignoffNew $pendingPath ([pscustomobject]@{schemaVersion=1;status='PENDING';cells=@()}))
    $verifier=[pscustomobject]@{javaPin=$javaPin;corePin=$cliCore;classPins=$classPins}
    Reject-SignoffFixture {Invoke-SignoffJdk $verifier $pendingPath $cliOutput $cliConfig @{}} 'SIGNOFF_CLI_REJECTED'
}
foreach ($pin in $sourcePins) {[void](Read-SignoffPin $pin $null)}
Write-Output "Finalizer focused fixtures: PASS ($checks checks; $rejects exact rejections). MOCK assembly only; actual JDK CLI negative only; native signoff PENDING. Artifacts=$owned"
