<#
.SYNOPSIS
Focused UNIT_MOCK normal reader/tamper проверки, не GUI/CLI/native evidence.
.DESCRIPTION
Реальные SHA, JSON/path, inventory/tree, UI/exit и HTTP guards. AppInfo, Cold/Life
contracts только mock для synthetic файлов; публичный reader изолирует эти mocks.
#>
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadScenarios.ps1')
Initialize-NativePayloadDependencies $PSScriptRoot
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadAcceptance.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateNormalAcceptance.ps1')
$script:checks=0

# Только unit assertion, не native PASS.
function Assert-NormalFixture([bool]$Condition,[string]$Name) {
    if (-not $Condition) {throw ('UNIT_MOCK_ASSERT:'+ $Name)};$script:checks++
}

# Сохраняем synthetic данные только под собственным Temp UUID.
function Write-NormalFixture($Path,$Value) {
    [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 64),[Text.UTF8Encoding]::new($false));return $Path
}

# Настоящие bytes/paths остаются закреплёнными; launcher/runtime contracts явно mock.
function Assert-ColdCommand($Config,$Java,$Bases,$Target) {
    if ((Get-FileHash $Java).Hash.ToLowerInvariant() -cne $Config.runtimeSha256) {throw 'UNIT_MOCK_RUNTIME_PIN'}
}

# Synthetic файл не является настоящим server JAR, acceptance не исполняет его.
function Assert-NativeLifecycleConfig($Config) {
    if ($Config.harnessFiles.Count -ne 1) {throw 'UNIT_MOCK_HARNESS'}
}

# AppInfo mock по synthetic map; ни jar/java.exe не исполняются.
function Get-ColdVersion($Root) {return $script:versions[$Root]}

# MAIN-like индекс закрепляется самостоятельно, а не берётся из cell return.
function Save-NormalFixtureIndex {
    $files=@(Get-ChildItem -LiteralPath $script:cellDir -File | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{path=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()}})
    $null=Write-NormalFixture $script:indexFile ([pscustomobject]@{schemaVersion=1;CellEvidence=$script:cellDir;evidenceKind='UNIT_MOCK';files=$files})
    return (Get-FileHash $script:indexFile).Hash.ToLowerInvariant()
}

# Запуск только read-only внутренних guards с явно UNIT_MOCK индексом.
function Read-NormalFixture {
    $indexPin=Save-NormalFixtureIndex
    return Read-NormalAcceptance $script:expectedFile ((Get-FileHash $script:expectedFile).Hash.ToLowerInvariant()) $script:indexFile $indexPin 'UNIT_MOCK'
}

$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString());$null=New-Item -ItemType Directory $fixtureRoot
$run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
$script:cellDir=Join-Path $fixtureRoot ([IO.Path]::GetFileName($run));$null=New-Item -ItemType Directory $script:cellDir
$installed=Join-Path (Join-Path $run 'plain') 'CashPrediction'
$script:versions=@{};$roots=@();$manifests=@();$manifestFiles=@()
foreach ($n in 1,2,3) {
    $root=Join-Path $fixtureRoot ('image'+$n);$null=New-Item -ItemType Directory $root
    foreach ($path in @('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe','app/.jpackage.xml','app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg',
        'runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules','app/cashprediction-core-1.0.0.jar','app/cashprediction-ui-fx-1.0.0.jar','app/cashprediction-ui-swing-1.0.0.jar','app/cashprediction-web-1.0.0.jar')) {
        $file=Join-Path $root $path;$null=New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($file))
        [IO.File]::WriteAllText($file,('UNIT_MOCK_'+$n+'_'+$path))
    }
    $files=@(Get-ColdManagedInventory $root)
    $manifest=[pscustomobject]@{releaseNumber=$n;commitSha=([string]$n*40);files=$files;treeSha256=(Get-ColdTreeHash $files);deltaPatches=@()}
    $script:versions[$root]=[pscustomobject]@{releaseNumber=$n;commitSha=$manifest.commitSha;jar='cashprediction-core-1.0.0.jar';jarSha256=(Get-FileHash (Join-Path $root 'app/cashprediction-core-1.0.0.jar')).Hash.ToLowerInvariant()}
    $roots+=@($root);$manifests+=@($manifest)
    $manifestFiles+=@(Write-NormalFixture (Join-Path $fixtureRoot ('base'+$n+'.json')) $manifest)
}
$artifact=Join-Path $fixtureRoot 'artifacts';$null=New-Item -ItemType Directory $artifact
$target=$manifests[2];$target | Add-Member assetName 'CashPrediction-portable.zip';$target | Add-Member sizeBytes 10
$zip=Join-Path $artifact $target.assetName;[IO.File]::WriteAllText($zip,'0123456789');$target | Add-Member sha256 ((Get-FileHash $zip).Hash.ToLowerInvariant())
$target.deltaPatches=@(foreach ($n in 1,2) {
    $asset='CashPrediction.from-'+$n+'.cpdelta';$file=Join-Path $artifact $asset;[IO.File]::WriteAllText($file,'0123456789')
    [pscustomobject]@{baseReleaseNumber=$n;baseCommitSha=$manifests[$n-1].commitSha;baseTreeSha256=$manifests[$n-1].treeSha256;assetName=$asset;sizeBytes=10;sha256=(Get-FileHash $file).Hash.ToLowerInvariant()}
})
$targetFile=Write-NormalFixture (Join-Path $artifact 'update.json') $target
$java=Join-Path $fixtureRoot 'java.exe';[IO.File]::WriteAllText($java,'UNIT_MOCK_NOT_EXECUTABLE')
$entries=@(foreach ($n in 0,1) {[pscustomobject]@{portableDir=$roots[$n];manifest=$manifestFiles[$n];sha256=(Get-FileHash $manifestFiles[$n]).Hash.ToLowerInvariant()}})
$cold=[pscustomobject]@{baseManifests=$entries;targetManifest=$targetFile;targetManifestSha256=(Get-FileHash $targetFile).Hash.ToLowerInvariant();
    runtimeSha256=(Get-FileHash $java).Hash.ToLowerInvariant();helperScript=$java;toolFiles=@([pscustomobject]@{path=$java})}
$command=Write-NormalFixture (Join-Path $fixtureRoot 'command.json') $cold
$life=[pscustomobject]@{artifactDir=$artifact;manifestSha256=$cold.targetManifestSha256;harnessClasspath=$java;harnessFiles=@([pscustomobject]@{path=$java})}
$lifeFile=Write-NormalFixture (Join-Path $fixtureRoot 'life.json') $life
$null=New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($installed));Copy-Item -LiteralPath $roots[2] -Destination $installed -Recurse
$script:versions[$installed]=$script:versions[$roots[2]]
foreach ($path in 'CashMemory/NativeLifecycle.md','CashMemory/settings.md','CashMemory/protected-user.txt','protected-root.txt') {
    $file=Join-Path $installed $path;$null=New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($file));[IO.File]::WriteAllText($file,'UNIT_MOCK_USERDATA')
}
$node='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString()
$expected=[pscustomobject]@{schemaVersion=1;Scenario='delta';Base='B1';Client='web';Path='ascii';Phase='SESSION';SourceRoot=$roots[0];TargetRoot=$roots[2];InstalledRoot=$installed;
    CellEvidence=$script:cellDir;RegistryNode=$node;Java=$java;PreparedUtc='2026-10-04T10:00:00Z';InputSnapshotSha256='';
    BaseManifestFile=$manifestFiles[0];BaseManifestSha256=$entries[0].sha256;TargetManifestFile=$targetFile;TargetManifestSha256=$cold.targetManifestSha256;
    CommandFile=$command;CommandSha256=(Get-FileHash $command).Hash.ToLowerInvariant();LifecycleFile=$lifeFile;LifecycleSha256=(Get-FileHash $lifeFile).Hash.ToLowerInvariant()}
$script:expectedFile=Write-NormalFixture (Join-Path $fixtureRoot 'expected.json') $expected
$expected.InputSnapshotSha256=(Get-NormalAcceptanceInput $script:expectedFile ((Get-FileHash $script:expectedFile).Hash.ToLowerInvariant())).sha256
$null=Write-NormalFixture $script:expectedFile $expected
$script:indexFile=Join-Path $fixtureRoot 'index.json'
$args=@('--test-api','--home',$installed,'--registry-node',$node,'--no-browser','--no-window')
$started=[datetime]::Parse('2026-10-04T10:00:01Z').ToUniversalTime();$ticks=$started.Ticks
$exe=Join-Path $installed 'CashPrediction-Web.exe'
$ui=[pscustomobject]@{pid=1001;startedAtTicks=$ticks;executablePath=$exe;args=$args;commandLine=(@($exe)+$args | ForEach-Object {'"'+$_+'"'}) -join ' ';
    observedAt='2026-10-04T10:00:02Z';modules=@((Join-Path $installed 'runtime/bin/server/jvm.dll'));
    lease=[pscustomobject]@{schemaVersion=1;leaseId=[guid]::NewGuid().ToString();pid=1001;startedAtEpochMillis=([DateTimeOffset]::new($started)).ToUnixTimeMilliseconds();installationRoot=$installed;client='web'};
    witness=[pscustomobject]@{kind='owned-http';port=12345;status=200;bodySha256=('a'*64);owningProcess=1001}}
$launch=[pscustomobject]@{executable=$exe;launcher=[pscustomobject]@{ProcessId=1000};ui=$ui;args=$args;manifestUri='http://127.0.0.1:12345/update.json';startedAt='2026-10-04T10:00:01Z'}
$null=Write-NormalFixture (Join-Path $cellDir 'launch-0.json') $launch
$null=Write-NormalFixture (Join-Path $cellDir 'launch-started-0.json') ([pscustomobject]@{launcher=$launch.launcher;args=$args;manifestUri=$launch.manifestUri;startedAt=$launch.startedAt})
$exit=[pscustomobject]@{pid=1001;startedAtTicks=$ticks;exitCode=0;exitedUtc='2026-10-04T10:00:10Z';remainingClients=0;kind='ordinary-no-restart'}
$null=Write-NormalFixture (Join-Path $cellDir 'exit-0.json') $exit
$trace=@();$id=0
foreach ($path in '/update.json','/CashPrediction.from-1.cpdelta') {
    $id++;$trace+=@([pscustomobject]@{event='START';id=$id;path=$path;nanos=($id*100);utc='2026-10-04T10:00:03Z';query='';method='GET'},
        [pscustomobject]@{event='FINISH';id=$id;path=$path;startedNanos=($id*100);finishedNanos=($id*100+50);startedUtc='2026-10-04T10:00:03Z';finishedUtc='2026-10-04T10:00:04Z';query='';method='GET';status=200;bytes=10})
}
$stats=[pscustomobject]@{schemaVersion=1;requests=2;completed=2;bytes=20;counts=[pscustomobject]@{'/update.json'=1;'/CashPrediction.from-1.cpdelta'=1};active=0;closed=$true;cancelled=$false;healthy=$true}
$null=Write-NormalFixture (Join-Path $cellDir 'server-0-stats.json') $stats
$null=Write-NormalFixture (Join-Path $cellDir 'server-0-trace.json') $trace
$null=Write-NormalFixture (Join-Path $cellDir 'raw-server-0-server-receipt.json') ([pscustomobject]@{mode='VALID';manifestSha256=$cold.targetManifestSha256;artifactDir=$artifact;manifestUri=$launch.manifestUri})
$user=Get-NativeUserObject $installed
$inventories=@{currentBefore=$manifests[0].files;currentAfter=$target.files;targetBefore=$target.files;targetAfter=$target.files;userBefore=$user;userAfter=$user;
    httpTrace=@($trace | Where-Object event -CEQ 'FINISH');phaseLog=@('SESSION')}
$cell=[pscustomobject]@{scenario='delta';base='B1';client='web';path='ascii';phase='SESSION';status='PASS';reason='NATIVE_LIFECYCLE_EXECUTED';executed=$true;
    exe=$exe;baseRelease=1;baseCommit=$manifests[0].commitSha;targetRelease=3;targetCommit=$target.commitSha;exitCode=0;failures=0;skipped=0;
    startedAt='2026-10-04T10:00:00.500Z';finishedAt='2026-10-04T10:00:20Z'}
foreach ($name in $inventories.Keys) {$file=Write-NormalFixture (Join-Path $cellDir ($name+'.json')) $inventories[$name];$cell | Add-Member $name $file}
$cellFile=Write-NormalFixture (Join-Path $cellDir 'cell.json') $cell
$good=Read-NormalFixture
Assert-NormalFixture ($good.status -ceq 'PENDING' -and $good.errors.Count -eq 0) ('MISSING_REAL_OBSERVERS '+($good.errors -join ';'))
Assert-NormalFixture ('POST_FINALLY_CLIENT_HELPER_SERVER_REGISTRY_CENSUS_NOT_PERSISTED' -cin $good.missing) ('CLEANUP_NOT_INFERRED_FROM_PASS '+($good.missing -join ';'))
Assert-NormalFixture ('BIRTH_UI_ORDINARY_EXIT_SESSION_0' -cin $good.checked) 'REAL_UI_EXIT_GUARDS'
Assert-NormalFixture ('HTTP_COUNTER_TRACE_SESSION_0' -cin $good.checked) 'REAL_HTTP_GUARDS'
$indexPin=Save-NormalFixtureIndex
$public=Test-NativeNormalAcceptance '' '' '' ''
Assert-NormalFixture ($public.status -ceq 'PENDING' -and -not $public.proofComplete) 'PUBLIC_DEFAULT_UNVERIFIED'
$pinFail=Read-NormalAcceptance $expectedFile ('0'*64) $indexFile $indexPin 'UNIT_MOCK'
Assert-NormalFixture ($pinFail.status -ceq 'FAIL') 'OLD_EXPECTED_PIN'
[IO.File]::AppendAllText($cellFile,' ')
$tamper=Read-NormalAcceptance $expectedFile ((Get-FileHash $expectedFile).Hash.ToLowerInvariant()) $indexFile $indexPin 'UNIT_MOCK'
Assert-NormalFixture ($tamper.status -ceq 'FAIL') 'EVIDENCE_BYTES_TAMPER'
$null=Write-NormalFixture $cellFile $cell
$launch.ui.lease.pid=1002;$null=Write-NormalFixture (Join-Path $cellDir 'launch-0.json') $launch
Assert-NormalFixture ((Read-NormalFixture).status -ceq 'FAIL') 'REPINNED_WRONG_UI_BIRTH'
$launch.ui.lease.pid=1001;$null=Write-NormalFixture (Join-Path $cellDir 'launch-0.json') $launch
$stats.requests=3;$null=Write-NormalFixture (Join-Path $cellDir 'server-0-stats.json') $stats
Assert-NormalFixture ((Read-NormalFixture).status -ceq 'FAIL') 'REPINNED_HTTP_COUNTER_MISMATCH'
$stats.requests=2;$null=Write-NormalFixture (Join-Path $cellDir 'server-0-stats.json') $stats
$null=Write-NormalFixture (Join-Path $cellDir 'currentAfter.json') $manifests[0].files
Assert-NormalFixture ((Read-NormalFixture).status -ceq 'FAIL') 'REPINNED_WRONG_INSTALLED_TREE'
$null=Write-NormalFixture (Join-Path $cellDir 'currentAfter.json') $target.files
$cell.targetCommit=('f'*40);$null=Write-NormalFixture $cellFile $cell
Assert-NormalFixture ((Read-NormalFixture).status -ceq 'FAIL') 'REPINNED_WRONG_TARGET_IDENTITY'
$cell.targetCommit=$target.commitSha;$null=Write-NormalFixture $cellFile $cell
$indexPin=Save-NormalFixtureIndex;$badIndex=Get-Content $indexFile -Raw | ConvertFrom-Json -Depth 64
$badIndex.files[0].path='../cell.json';$null=Write-NormalFixture $indexFile $badIndex
$escape=Read-NormalAcceptance $expectedFile ((Get-FileHash $expectedFile).Hash.ToLowerInvariant()) $indexFile ((Get-FileHash $indexFile).Hash.ToLowerInvariant()) 'UNIT_MOCK'
Assert-NormalFixture ($escape.status -ceq 'FAIL') 'REPINNED_PATH_ESCAPE'
# Nine route contracts тестируются отдельно, без утверждения об исполненном native сценарии.
foreach ($scenario in 'delta','corrupt-delta-full','corrupt-full-retain','cancel-next-session','offline','timeout','malformed','once-three-attempts','leases-normal-close') {
    $events=@();$metaCount=if ($scenario -cin @('offline','timeout','malformed','once-three-attempts')) {3} else {1}
    for ($n=0;$n -lt $metaCount;$n++) {
        $status=if ($scenario -ceq 'offline') {0} elseif ($scenario -ceq 'once-three-attempts' -and $n -lt 2) {503} else {200}
        $bytes=if ($scenario -ceq 'offline') {0} elseif ($scenario -ceq 'malformed') {1} else {10}
        $events+=@([pscustomobject]@{path='/update.json';status=$status;bytes=$bytes;startedNanos=0;finishedNanos=1})
    }
    if ($scenario -cnotin @('offline','timeout','malformed','corrupt-full-retain')) {
        $bytes=if ($scenario -ceq 'cancel-next-session') {5} else {10}
        $events+=@([pscustomobject]@{path='/CashPrediction.from-1.cpdelta';status=200;bytes=$bytes;startedNanos=2;finishedNanos=3})
    }
    if ($scenario -cin @('corrupt-delta-full','corrupt-full-retain')) {$events+=@([pscustomobject]@{path='/CashPrediction-portable.zip';status=200;bytes=10;startedNanos=4;finishedNanos=5})}
    $routeState=[pscustomobject]@{missing=[Collections.Generic.List[string]]::new()}
    Test-NormalHttpRoute $scenario 0 $events $target.deltaPatches[0] $target $routeState
    Assert-NormalFixture $true ('UNIT_ROUTE_'+$scenario)
}
$badRoute=@([pscustomobject]@{path='/update.json';status=200;bytes=10},[pscustomobject]@{path='/CashPrediction.from-1.cpdelta';status=200;bytes=10;finishedNanos=5},
    [pscustomobject]@{path='/CashPrediction-portable.zip';status=200;bytes=10;startedNanos=4})
$reason='';try {Test-NormalHttpRoute 'corrupt-delta-full' 0 $badRoute $target.deltaPatches[0] $target $routeState} catch {$reason=$_.Exception.Message}
Assert-NormalFixture ($reason -ceq 'NORMAL_FALLBACK_ORDER_OR_BYTES') 'FALLBACK_NEGATIVE_ORDER'
$retry=@([pscustomobject]@{path='/update.json';status=503;bytes=10},[pscustomobject]@{path='/update.json';status=200;bytes=10},[pscustomobject]@{path='/update.json';status=503;bytes=10},
    [pscustomobject]@{path='/CashPrediction.from-1.cpdelta';status=200;bytes=10})
$reason='';try {Test-NormalHttpRoute 'once-three-attempts' 0 $retry $target.deltaPatches[0] $target $routeState} catch {$reason=$_.Exception.Message}
Assert-NormalFixture ($reason -ceq 'NORMAL_RETRY_ORDER') 'RETRY_NEGATIVE_ORDER'
$indexPin=Save-NormalFixtureIndex
$mockAsNative=Read-NormalAcceptance $expectedFile ((Get-FileHash $expectedFile).Hash.ToLowerInvariant()) $indexFile $indexPin 'NATIVE'
Assert-NormalFixture ($mockAsNative.status -ceq 'PENDING' -and 'MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED' -cin $mockAsNative.missing) 'UNIT_MOCK_NOT_NATIVE_PASS'
$launch.ui=$null;$null=Write-NormalFixture (Join-Path $cellDir 'launch-0.json') $launch
$missingUi=Read-NormalFixture
Assert-NormalFixture ($missingUi.status -ceq 'PENDING' -and $missingUi.errors.Count -eq 0 -and 'RECEIPT_FIELD:launch-0.json:ui' -cin $missingUi.missing) 'MISSING_UI_PENDING_NOT_FAIL'
$launch.ui=$ui;$null=Write-NormalFixture (Join-Path $cellDir 'launch-0.json') $launch
$badExpected=$expected | ConvertTo-Json -Depth 64 | ConvertFrom-Json;$badExpected.SourceRoot=$roots[1]
$null=Write-NormalFixture $expectedFile $badExpected
Assert-NormalFixture ((Read-NormalFixture).errors -ccontains 'NORMAL_SOURCE_IDENTITY') 'WRONG_PINNED_SOURCE_BINDING'
$null=Write-NormalFixture $expectedFile $expected
$indexPin=Save-NormalFixtureIndex;$badIndex=Get-Content $indexFile -Raw | ConvertFrom-Json -Depth 64
$badIndex.files+=@([pscustomobject]@{path=$badIndex.files[0].path.ToUpperInvariant();sha256=$badIndex.files[0].sha256})
$null=Write-NormalFixture $indexFile $badIndex
$duplicate=Read-NormalAcceptance $expectedFile ((Get-FileHash $expectedFile).Hash.ToLowerInvariant()) $indexFile ((Get-FileHash $indexFile).Hash.ToLowerInvariant()) 'UNIT_MOCK'
Assert-NormalFixture ($duplicate.status -ceq 'FAIL') 'CASE_ALIAS_RECEIPT_DUPLICATE'
[IO.File]::AppendAllText((Join-Path $roots[0] 'app/CashPrediction.cfg'),'changed')
Assert-NormalFixture ((Read-NormalFixture).errors -ccontains 'NORMAL_INPUTS_CHANGED') 'SOURCE_MUTATION'
[pscustomobject]@{status='UNIT_MOCK';checks=$script:checks;nativeStatus='PENDING';fixtureRoot=$fixtureRoot;fullMatrix='PENDING';releaseProvenance='PENDING'} | ConvertTo-Json
