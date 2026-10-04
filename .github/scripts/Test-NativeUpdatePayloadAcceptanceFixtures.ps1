<#
.SYNOPSIS
Независимые UNIT_MOCK acceptance fixtures: реальные JSON/JAR/hash чтения, без Java/native.
.DESCRIPTION
Fake exe/UI/HTTP помечены UNIT_MOCK. Даже полный стенд обязан вернуть PENDING,
никогда native PASS. Проверяется proofComplete только для coverage алгоритма.
#>
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadScenarios.ps1')
Initialize-NativePayloadDependencies $PSScriptRoot
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadAcceptance.ps1')
$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
$null=New-Item -ItemType Directory -Path $fixtureRoot
$script:acceptanceChecks=0

# Только собственные файлы UUID стенда.
function Write-AcceptanceMockJson([string]$Path,$Value) {
    [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 64 -Compress),[Text.UTF8Encoding]::new($false))
}

# Digest реальных mock bytes, не утверждение native исполнения.
function Get-AcceptanceMockPin([string]$Path) {return (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}

# Tiny image с настоящими ZIP/JAR metadata, но не исполняемыми binaries.
function New-AcceptanceMockImage([string]$Root,[int]$Release,[string]$Commit) {
    foreach ($dir in 'app','runtime/bin/server','runtime/lib') {$null=New-Item -ItemType Directory -Path (Join-Path $Root $dir) -Force}
    foreach ($name in 'CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe','app/.jpackage.xml',
        'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg','runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules') {
        [IO.File]::WriteAllText((Join-Path $Root $name),'UNIT_MOCK_NOT_NATIVE_'+$Release)
    }
    foreach ($name in 'core','ui-fx','ui-swing','web') {
        $zip=[IO.Compression.ZipFile]::Open((Join-Path $Root ('app/cashprediction-'+$name+'-1.0.0.jar')),[IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry=$zip.CreateEntry($(if ($name -ceq 'core') {'ru/cashprediction/core/app.properties'} else {'mock.txt'}))
            $stream=$entry.Open();$writer=[IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false))
            try {$writer.Write($(if ($name -ceq 'core') {"release=$Release`ncommit=$Commit`n"} else {'UNIT_MOCK'}))} finally {$writer.Dispose()}
        } finally {$zip.Dispose()}
    }
}

# Каждая проверка выводит только статус алгоритма над UNIT_MOCK.
function Assert-AcceptanceMock([bool]$Value,[string]$Name) {
    if (-not $Value) {throw ('ACCEPTANCE_MOCK_ASSERT: '+$Name)};$script:acceptanceChecks++
}

# Возвращает fresh JSON модель без разделяемых мутируемых полей.
function Copy-AcceptanceMock($Value) {return (ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64 -Compress))}

try {
    $source=Join-Path $fixtureRoot 'B1';$source2=Join-Path $fixtureRoot 'B2';$target=Join-Path $fixtureRoot 'T'
    New-AcceptanceMockImage $source 1001 ('1'*40);New-AcceptanceMockImage $source2 1002 ('2'*40);New-AcceptanceMockImage $target 1003 ('3'*40)
    $baseFiles=@(Get-ColdManagedInventory $source);$base2Files=@(Get-ColdManagedInventory $source2);$targetFiles=@(Get-ColdManagedInventory $target)
    $artifacts=Join-Path $fixtureRoot 'artifacts';$null=New-Item -ItemType Directory -Path $artifacts
    $full=Join-Path $artifacts 'CashPrediction-portable.zip';[IO.File]::WriteAllText($full,'UNIT_MOCK_FULL')
    $descriptors=@()
    foreach ($number in 1,2) {
        $name='CashPrediction.from-'+(1000+$number)+'.cpdelta';$path=Join-Path $artifacts $name;[IO.File]::WriteAllText($path,'UNIT_MOCK_DELTA_'+$number)
        $files=if ($number -eq 1) {$baseFiles} else {$base2Files}
        $descriptors+=@([pscustomobject]@{algorithm='cashprediction-tree-delta';algorithmVersion=1;baseReleaseNumber=(1000+$number);
            baseCommitSha=([string]$number)*40;baseTreeSha256=(Get-ColdTreeHash $files);assetName=$name;sizeBytes=[long](Get-Item $path).Length;sha256=(Get-AcceptanceMockPin $path)})
    }
    $manifest=[pscustomobject]@{schemaVersion=2;releaseNumber=1003;commitSha=('3'*40);version='1.0.0';publishedAtUtc='2026-10-04T00:00:00Z';
        assetName='CashPrediction-portable.zip';sizeBytes=[long](Get-Item $full).Length;sha256=(Get-AcceptanceMockPin $full);
        treeSha256=(Get-ColdTreeHash $targetFiles);files=$targetFiles;deltaPatches=$descriptors}
    $manifestFile=Join-Path $artifacts 'update.json';Write-AcceptanceMockJson $manifestFile $manifest
    $base=Copy-AcceptanceMock $manifest;$base.releaseNumber=1001;$base.commitSha='1'*40;$base.files=$baseFiles;$base.treeSha256=Get-ColdTreeHash $baseFiles;$base.deltaPatches=@()
    $baseFile=Join-Path $fixtureRoot 'base.json';Write-AcceptanceMockJson $baseFile $base
    $command=[pscustomobject]@{targetManifest=$manifestFile;targetManifestSha256=(Get-AcceptanceMockPin $manifestFile);
        baseManifests=@([pscustomobject]@{portableDir=$source;manifest=$baseFile;sha256=(Get-AcceptanceMockPin $baseFile)},
            [pscustomobject]@{portableDir=$source2;manifest='UNIT_MOCK_UNUSED';sha256=('a'*64)})}
    $commandFile=Join-Path $fixtureRoot 'command.json';Write-AcceptanceMockJson $commandFile $command
    $life=[pscustomobject]@{artifactDir=$artifacts;manifestSha256=(Get-AcceptanceMockPin $manifestFile)}
    $lifeFile=Join-Path $fixtureRoot 'life.json';Write-AcceptanceMockJson $lifeFile $life
    $installed=Join-Path $fixtureRoot 'installed';Copy-Item -LiteralPath $target -Destination $installed -Recurse
    $null=New-Item -ItemType Directory -Path (Join-Path $installed 'CashMemory/Updates')
    foreach ($name in 'CashMemory/protected-user.txt','CashMemory/NativeLifecycle.md','CashMemory/settings.md','protected-root.txt') {[IO.File]::WriteAllText((Join-Path $installed $name),'UNIT_MOCK_USER')}
    $evidence=Join-Path $fixtureRoot 'evidence';$cellDir=Join-Path $evidence 'cell';$null=New-Item -ItemType Directory -Path $cellDir
    $row=[pscustomobject]@{scenario='cashmemory';base='B1';client='fx';path='ascii';status='PASS';executed=$true;
        baseRelease=1001;baseCommit=('1'*40);targetRelease=1003;targetCommit=('3'*40);payloadTreeSha256=$manifest.treeSha256;
        reason='NATIVE_LIFECYCLE_EXECUTED';exitCode=0;failures=0;skipped=0;exe=(Join-Path $installed 'CashPrediction.exe');
        payloadScope='SCOPED_EXECUTED_NOT_MATRIX_SIGNOFF';payloadFullMatrix='PENDING';payloadReleaseProvenance='NOT_PROVEN';
        payloadArtifactDir=$artifacts;payloadManifestSha256=(Get-AcceptanceMockPin $manifestFile);payloadTargetRoot=$target;
        payloadCommandFile=$commandFile;payloadCommandSha256=(Get-AcceptanceMockPin $commandFile);
        payloadLifecycleFile=$lifeFile;payloadLifecycleSha256=(Get-AcceptanceMockPin $lifeFile);payloadInputPins=(Join-Path $evidence 'input-pin-check.json')}
    Write-AcceptanceMockJson $row.payloadInputPins ([pscustomobject]@{status='VERIFIED';commandSha256=(Get-AcceptanceMockPin $commandFile);lifecycleSha256=(Get-AcceptanceMockPin $lifeFile)})
    $user=Get-NativeUserObject $installed
    foreach ($entry in @{currentBefore=$baseFiles;currentAfter=$targetFiles;targetBefore=$targetFiles;targetAfter=$targetFiles;userBefore=$user;userAfter=$user;
        phaseLog=@('SESSION','BOOTSTRAPPING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED');httpTrace=@(
            [pscustomobject]@{event='FINISH';path='/update.json';status=200;bytes=100L},
            [pscustomobject]@{event='FINISH';path=('/'+$descriptors[0].assetName);status=200;bytes=$descriptors[0].sizeBytes;finishedNanos=100L})}.GetEnumerator()) {
        $path=Join-Path $cellDir ($entry.Key+'.json');Write-AcceptanceMockJson $path $entry.Value;$row | Add-Member $entry.Key $path
    }
    $birth=[datetime]::new(2026,10,4,0,0,0,[DateTimeKind]::Utc);$node='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString()
    $row | Add-Member finishedAt $birth.AddSeconds(9).ToString('o')
    $args=@('--test-api','--home',$installed,'--registry-node',$node)
    $ui=[pscustomobject]@{pid=1234;startedAtTicks=$birth.Ticks;executablePath=$row.exe;
        modules=@((Join-Path $installed 'runtime/bin/server/jvm.dll'));witness=[pscustomobject]@{kind='native-window';handle=1234;title='CashPrediction - UNIT_MOCK'};
        lease=[pscustomobject]@{schemaVersion=1;leaseId=[guid]::NewGuid().ToString();pid=1234;startedAtEpochMillis=([DateTimeOffset]::new($birth)).ToUnixTimeMilliseconds();installationRoot=$installed;client='fx'};
        commandLine=('"'+$row.exe+'" '+(($args | ForEach-Object {'"'+$_+'"'}) -join ' '));args=$args;observedAt=$birth.AddSeconds(1).ToString('o')}
    $row | Add-Member command (Join-Path $cellDir 'launch-0.json')
    Write-AcceptanceMockJson $row.command ([pscustomobject]@{ui=$ui;executable=$row.exe;args=$args;evidenceKind='UNIT_MOCK'})
    Write-AcceptanceMockJson (Join-Path $cellDir 'exit-0.json') ([pscustomobject]@{pid=1234;startedAtTicks=$birth.Ticks;exitCode=0;remainingClients=0;kind='ordinary-no-restart';exitedUtc=$birth.AddSeconds(2).ToString('o')})
    Write-AcceptanceMockJson (Join-Path $installed 'CashMemory/Updates/last-install.json') ([pscustomobject]@{outcome='UPDATED';targetCommitSha=('3'*40)})
    $mockLog=@();$phaseIndex=3
    foreach ($phase in 'BOOTSTRAPPING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED') {$mockLog+=@('- '+$birth.AddSeconds($phaseIndex).ToString('o')+' PHASE_'+$phase);$phaseIndex++}
    [IO.File]::WriteAllLines((Join-Path $installed 'CashMemory/Updates/update-log.md'),$mockLog,[Text.UTF8Encoding]::new($false))
    $serverFile=Join-Path $cellDir 'raw-server-0-server-receipt.json'
    Write-AcceptanceMockJson $serverFile ([pscustomobject]@{mode='VALID';manifestSha256=(Get-AcceptanceMockPin $manifestFile);artifactDir=$artifacts})
    $observation=[pscustomobject]@{schemaVersion=1;status='SCOPED_EVIDENCE';nativeStatus='PENDING';normalFlow='delta';scope='cashmemory';fullMatrix='PENDING';releaseProvenance='NOT_PROVEN';cell=$row;evidenceKind='UNIT_MOCK'}
    $terminal=Copy-AcceptanceMock $row;$terminal.scenario='delta';Write-AcceptanceMockJson (Join-Path $cellDir 'cell.json') $terminal
    $observationFile=Join-Path $evidence 'payload-observation.json';Write-AcceptanceMockJson $observationFile $observation
    $expected=[pscustomobject]@{Scenario='cashmemory';Base='B1';Client='fx';Path='ascii';SourceRoot=$source;OriginalTargetRoot=$target;TargetRoot=$target;InstalledRoot=$installed;RequireFallback=$false}
    foreach ($pair in @{BaseManifest=$baseFile;OriginalTargetManifest=$manifestFile;Manifest=$manifestFile;OriginalCommand=$commandFile;Command=$commandFile;OriginalLifecycle=$lifeFile;Lifecycle=$lifeFile}.GetEnumerator()) {
        $expected | Add-Member ($pair.Key+'File') $pair.Value;$expected | Add-Member ($pair.Key+'Sha256') (Get-AcceptanceMockPin $pair.Value)
    }
    $good=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
    if ($good.status -cne 'PENDING' -or -not $good.receiptChainComplete -or $good.proofComplete) {throw ('GOOD_MOCK: '+(ConvertTo-Json $good -Depth 8 -Compress))}
    Assert-AcceptanceMock $true 'COMPLETE_MOCK_IS_PENDING_NOT_PASS'
    $label=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind NATIVE
    Assert-AcceptanceMock ($label.status -ceq 'PENDING') 'UNIT_MOCK_MARKER_CANNOT_RELABEL_NATIVE'
    $missing=Test-NativePayloadAcceptance (Join-Path $evidence 'absent.json') ('a'*64) $expected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($missing.status -ceq 'PENDING') 'MISSING_OBSERVATION'
    $partial=Copy-AcceptanceMock $observation;$partial.cell.PSObject.Properties.Remove('userAfter');Write-AcceptanceMockJson $observationFile $partial
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'userAfter' -cin $result.missing) 'MISSING_USER_AFTER'
    Write-AcceptanceMockJson $observationFile $observation
    $returnOnly=[pscustomobject]@{schemaVersion=1;status='SCOPED_EVIDENCE';nativeStatus='PENDING';normalFlow='delta';scope='cashmemory';fullMatrix='PENDING';releaseProvenance='NOT_PROVEN';evidenceKind='UNIT_MOCK'}
    Write-AcceptanceMockJson $observationFile $returnOnly
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'NATIVE_CELL' -cin $result.missing -and -not $result.proofComplete) 'HELPER_RETURN_ONLY_IS_NOT_NATIVE'
    $returnOnly.status='UNIT_MOCK';Write-AcceptanceMockJson $observationFile $returnOnly
    Assert-AcceptanceMock ((Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK).status -ceq 'PENDING') 'MERE_UNIT_MOCK_IS_PENDING'
    Write-AcceptanceMockJson $observationFile $observation
    $exitPath=Join-Path $cellDir 'exit-0.json';$heldExit=Join-Path $cellDir 'exit-held.json'
    if (-not (Test-PortablePathContains $fixtureRoot $exitPath) -or -not (Test-PortablePathContains $fixtureRoot $heldExit)) {throw 'MOCK_MOVE_SCOPE'}
    [IO.File]::Move($exitPath,$heldExit)
    try {
        $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
        Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'NATIVE_EXIT' -cin $result.missing) 'MISSING_EXIT_NOT_FAIL_OR_PASS'
    } finally {[IO.File]::Move($heldExit,$exitPath)}
    # Неполный receipt не доказывает native exit; строковый код не равен целому нулю.
    $savedExit=[IO.File]::ReadAllBytes($exitPath)
    try {
        $partialExit=ConvertFrom-ColdReceiptJson ([Text.Encoding]::UTF8.GetString($savedExit))
        $partialExit.PSObject.Properties.Remove('exitedUtc');Write-AcceptanceMockJson $exitPath $partialExit
        $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
        Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'EXIT_exitedUtc' -cin $result.missing) 'PARTIAL_EXIT_IS_PENDING'
        Write-AcceptanceMockJson $exitPath $null
        $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
        Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'NATIVE_EXIT' -cin $result.missing) 'NULL_EXIT_IS_PENDING'
        $wrongExit=ConvertFrom-ColdReceiptJson ([Text.Encoding]::UTF8.GetString($savedExit))
        $wrongExit.exitCode='0';Write-AcceptanceMockJson $exitPath $wrongExit
        $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
        Assert-AcceptanceMock ($result.status -ceq 'FAIL' -and 'EXIT_CODE' -cin $result.errors) 'STRING_EXIT_CODE_IS_CONTRADICTION'
    } finally {[IO.File]::WriteAllBytes($exitPath,$savedExit)}
    $savedLaunch=[IO.File]::ReadAllBytes($row.command)
    try {
        $partialLaunch=ConvertFrom-ColdReceiptJson ([Text.Encoding]::UTF8.GetString($savedLaunch))
        $partialLaunch.ui.lease.PSObject.Properties.Remove('leaseId');Write-AcceptanceMockJson $row.command $partialLaunch
        $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
        Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'UI_lease_leaseId' -cin $result.missing) 'MISSING_REAL_UI_LEASE_FIELD_IS_PENDING'
    } finally {[IO.File]::WriteAllBytes($row.command,$savedLaunch)}
    $terminalFile=Join-Path $cellDir 'cell.json';$failedTerminal=Copy-AcceptanceMock $terminal;$failedTerminal.status='FAIL'
    $heldTerminal=Join-Path $cellDir 'cell-held.json'
    if (-not (Test-PortablePathContains $fixtureRoot $terminalFile) -or -not (Test-PortablePathContains $fixtureRoot $heldTerminal)) {throw 'MOCK_MOVE_SCOPE'}
    [IO.File]::Move($terminalFile,$heldTerminal)
    try {
        $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
        Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'POST_CLEANUP_CELL' -cin $result.missing) 'MISSING_POST_CLEANUP_PRODUCER_RECEIPT'
    } finally {[IO.File]::Move($heldTerminal,$terminalFile)}
    Write-AcceptanceMockJson $terminalFile $failedTerminal
    Assert-AcceptanceMock ((Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK).status -ceq 'FAIL') 'POST_CLEANUP_FAIL_OVERRIDES_HELPER_PASS'
    Write-AcceptanceMockJson $terminalFile $terminal
    $early=Copy-AcceptanceMock $observation;$early.cell.finishedAt=$birth.AddSeconds(1).ToString('o');Write-AcceptanceMockJson $observationFile $early
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'FAIL' -and @($result.errors | Where-Object {$_ -like '*INSTALL_AFTER_FINISHED_AT*'}).Count -gt 0) 'REAL_FINISHED_AT_IS_PRE_CLEANUP_BOUNDARY'
    Write-AcceptanceMockJson $observationFile $observation
    $protectedFile=Join-Path $installed 'CashMemory/protected-user.txt';$protectedBytes=[IO.File]::ReadAllBytes($protectedFile);[IO.File]::WriteAllText($protectedFile,'changed-user')
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'FAIL' -and 'CURRENT_USERDATA_CHANGED' -cin $result.errors) 'ACTUAL_CASHMEMORY_BYTES_CHANGED'
    [IO.File]::WriteAllBytes($protectedFile,$protectedBytes)
    $savedLife=[IO.File]::ReadAllBytes($lifeFile);$badLife=Copy-AcceptanceMock $life;$badLife.manifestSha256='f'*64;Write-AcceptanceMockJson $lifeFile $badLife
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'FAIL') 'CONFIG_FILE_PIN_MISMATCH'
    $semanticExpected=Copy-AcceptanceMock $expected;$semanticExpected.LifecycleSha256=Get-AcceptanceMockPin $lifeFile;$semanticExpected.OriginalLifecycleSha256=$semanticExpected.LifecycleSha256
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $semanticExpected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'FAIL' -and 'Lifecycle_MANIFEST_PIN' -cin $result.errors) 'CONFIG_PIN_MATCHES_BUT_WRONG_MANIFEST'
    [IO.File]::WriteAllBytes($lifeFile,$savedLife)
    $badExpected=Copy-AcceptanceMock $expected;$badExpected.SourceRoot=$target
    Assert-AcceptanceMock ((Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $badExpected -EvidenceKind UNIT_MOCK).status -ceq 'FAIL') 'WRONG_SOURCE_IDENTITY'
    $badExpected=Copy-AcceptanceMock $expected;$badExpected.TargetRoot=$source
    Assert-AcceptanceMock ((Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $badExpected -EvidenceKind UNIT_MOCK).status -ceq 'FAIL') 'WRONG_TARGET_IDENTITY'
    $artifactBytes=[IO.File]::ReadAllBytes($full);[IO.File]::WriteAllText($full,'corrupt')
    Write-AcceptanceMockJson $observationFile $partial
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'FAIL' -and 'userAfter' -cin $result.missing) 'CONTRADICTION_BEATS_MISSING_EVIDENCE'
    Write-AcceptanceMockJson $observationFile $observation
    Assert-AcceptanceMock ((Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $expected -EvidenceKind UNIT_MOCK).status -ceq 'FAIL') 'ARTIFACT_BYTES_PIN_MISMATCH'
    [IO.File]::WriteAllBytes($full,$artifactBytes)
    $fallbackExpected=Copy-AcceptanceMock $expected;$fallbackExpected.RequireFallback=$true
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $fallbackExpected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'VERIFIED_FULL_FALLBACK' -cin $result.missing) 'MISSING_FALLBACK_NOT_PASS'
    $events=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $row.httpTrace -Raw)
    $events+=@([pscustomobject]@{event='FINISH';path='/CashPrediction-portable.zip';status=200;bytes=$manifest.sizeBytes;startedNanos=101L})
    Write-AcceptanceMockJson $row.httpTrace $events
    Write-AcceptanceMockJson $serverFile ([pscustomobject]@{mode='CORRUPTDELTA';manifestSha256=(Get-AcceptanceMockPin $manifestFile);artifactDir=$artifacts})
    $result=Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $fallbackExpected -EvidenceKind UNIT_MOCK
    Assert-AcceptanceMock ($result.status -ceq 'PENDING' -and 'PAYLOAD_FALLBACK_ROUTE_NOT_PRODUCED' -cin $result.missing -and -not $result.proofComplete) 'FALLBACK_MOCK_HAS_NO_FROZEN_PAYLOAD_PRODUCER'
    $events[2].bytes++ ;Write-AcceptanceMockJson $row.httpTrace $events
    Assert-AcceptanceMock ((Test-NativePayloadAcceptance $observationFile (Get-AcceptanceMockPin $observationFile) $fallbackExpected -EvidenceKind UNIT_MOCK).status -ceq 'FAIL') 'FALLBACK_WRONG_FULL_BYTES'
    Write-Output ('Payload acceptance fixtures: '+$script:acceptanceChecks+' checks; UNIT_MOCK; native PASS not asserted; CLI/native not executed.')
} finally {
    $absolute=[IO.Path]::GetFullPath($fixtureRoot)
    if ([IO.Path]::GetDirectoryName($absolute) -cne [IO.Path]::GetTempPath().TrimEnd('\','/') -or
        [IO.Path]::GetFileName($absolute) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'ACCEPTANCE_FIXTURE_CLEANUP_SCOPE'}
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
