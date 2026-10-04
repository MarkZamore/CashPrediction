<#
.SYNOPSIS
UNIT_MOCK authority guards: настоящие AST/pin/path/snapshot, без CLI/native/GUI.
.DESCRIPTION
Manifest/AppInfo/tree contracts ниже явно mocked, не native evidence.
Никакие ранее пройденные fixture suites не запускаются.
#>
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadScenarios.ps1')
Initialize-NativePayloadDependencies $PSScriptRoot
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadAuthority.ps1')
$script:checks=0

# Считаем только unit assertions, не native PASS.
function Assert-AuthorityFixture([bool]$Value,[string]$Name) {
    if (-not $Value) {throw ('UNIT_MOCK_ASSERT:'+ $Name)};$script:checks++
}

# Отказ обязан иметь нужную причину, случайная ошибка не negative control.
function Reject-AuthorityFixture([scriptblock]$Action,[string]$Code) {
    $reason='';try {& $Action | Out-Null} catch {$reason=$_.Exception.Message}
    Assert-AuthorityFixture ($reason.Contains($Code)) ($Code+' actual='+$reason)
}

# Только mock JSON внутри выделенного Temp UUID; production файлы не записываются.
function Write-AuthorityFixture([string]$Path,$Value) {
    [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 64),[Text.UTF8Encoding]::new($false))
    return $Path
}

# Mock command contract; реальные SHA/identity guards authority остаются настоящими.
function Assert-ColdCommand($Config,$Java,$Bases,$Target) {
    if (@($Config.baseManifests).Count -ne 2 -or $Java -cne $script:javaPath) {throw 'UNIT_MOCK_COMMAND'}
}

# Mock harness schema, реальные bytes входят в independent snapshot.
function Assert-NativeLifecycleConfig($Config) {
    if ($Config.harnessFiles.Count -ne 1) {throw 'UNIT_MOCK_HARNESS'}
}

# Mock archive/schema reader; pinned JSON и path resolution не подменяются.
function Read-LifecycleManifest($Directory,$Pin) {Read-ColdPinnedJson (Join-Path $Directory 'update.json') $Pin}

# Mock tree проверяет фактический файл; это не полный managed tree/AppInfo verifier.
function Assert-ColdTree($Root,$Manifest) {
    if ((Get-PayloadAuthorityHash (Join-Path $Root 'payload.bin')) -cne $Manifest.files[0].sha256) {throw 'UNIT_MOCK_TREE'}
}

# Явная mock AppInfo identity по реальному fixture manifest, не production JAR.
function Get-ColdVersion($Root) {return $script:versions[$Root]}

$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
$null=New-Item -ItemType Directory -Path $fixtureRoot
$mainRoot=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
$null=New-Item -ItemType Directory -Path $mainRoot
$bases=@();$entries=@();$script:versions=@{}
foreach ($n in 1,2,3) {
    $root=Join-Path $fixtureRoot ('image'+$n);$null=New-Item -ItemType Directory -Path $root
    [IO.File]::WriteAllText((Join-Path $root 'payload.bin'),('UNIT_MOCK_'+$n))
    $manifest=[pscustomobject]@{releaseNumber=$n;commitSha=([string]$n*40);version='1.0.0';publishedAtUtc='2026-10-04T00:00:00Z';
        treeSha256=([string]$n*64);files=@([pscustomobject]@{path='payload.bin';sha256=(Get-PayloadAuthorityHash (Join-Path $root 'payload.bin'));sizeBytes=(Get-Item (Join-Path $root 'payload.bin')).Length;readOnly=$false});deltaPatches=@()}
    $script:versions[$root]=[pscustomobject]@{releaseNumber=$n;commitSha=$manifest.commitSha}
    $manifestPath=Write-AuthorityFixture (Join-Path $fixtureRoot ('base'+$n+'.json')) $manifest
    if ($n -lt 3) {$bases+=@($root);$entries+=@([pscustomobject]@{portableDir=$root;manifest=$manifestPath;sha256=(Get-PayloadAuthorityHash $manifestPath)})}
    else {$targetRoot=$root;$target=$manifest}
}
$target.deltaPatches=@(foreach ($n in 1,2) {[pscustomobject]@{baseReleaseNumber=$n;baseCommitSha=([string]$n*40);baseTreeSha256=([string]$n*64)}})
$artifact=Join-Path $fixtureRoot 'artifacts';$null=New-Item -ItemType Directory -Path $artifact
$targetFile=Write-AuthorityFixture (Join-Path $artifact 'update.json') $target
$script:javaPath=Join-Path $fixtureRoot 'java.exe';[IO.File]::WriteAllText($script:javaPath,'UNIT_MOCK_NOT_EXECUTABLE')
$tool=Join-Path $fixtureRoot 'tool.jar';[IO.File]::WriteAllText($tool,'UNIT_MOCK_TOOL')
$cold=[pscustomobject]@{targetManifest=$targetFile;targetManifestSha256=(Get-PayloadAuthorityHash $targetFile);
    baseManifests=$entries;helperScript=$tool;toolFiles=@([pscustomobject]@{path=$tool;sha256=(Get-PayloadAuthorityHash $tool)})}
$command=Write-AuthorityFixture (Join-Path $fixtureRoot 'command.json') $cold
$life=[pscustomobject]@{artifactDir=$artifact;manifestSha256=$cold.targetManifestSha256;harnessClasspath=$tool;
    harnessFiles=@([pscustomobject]@{path=$tool;sha256=(Get-PayloadAuthorityHash $tool)})}
$lifeFile=Write-AuthorityFixture (Join-Path $fixtureRoot 'life.json') $life
$work=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
$owned=[pscustomobject]@{MainRoot=$mainRoot;WorkRoot=$work;ArtifactRoot=(Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString()));
    RunRoot=(Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString()));Nonce=[guid]::NewGuid().ToString();RequireFallback=$true}
$context=[pscustomobject]@{Row=[pscustomobject]@{scenario='cashmemory';base='B1';client='web';path='unicode';status='PENDING'};
    Source=$bases[0];PortableDir=$bases;TargetPortableDir=$targetRoot;CommandFile=$command;CommandFileSha256=(Get-PayloadAuthorityHash $command);
    LifecycleFile=$lifeFile;LifecycleFileSha256=(Get-PayloadAuthorityHash $lifeFile);Java=$script:javaPath;Evidence=$work;Timeout=180}
$contextFile=Write-AuthorityFixture (Join-Path $mainRoot 'context.json') $context
$ownedFile=Write-AuthorityFixture (Join-Path $mainRoot 'owned.json') $owned
$contextPin=Get-PayloadAuthorityHash $contextFile;$ownedPin=Get-PayloadAuthorityHash $ownedFile
$snapshot=Get-PayloadAuthorityInput $contextFile $contextPin
Reject-AuthorityFixture {New-PayloadAuthorityIntent $contextFile $contextPin $ownedFile $ownedPin ('0'*64) 'UNIT_MOCK'} 'AUTHORITY_SOURCE_CHANGED'
Reject-AuthorityFixture {Read-ColdPinnedJson $contextFile ('0'*64)} 'PIN'
$bad=$owned | ConvertTo-Json | ConvertFrom-Json;$bad.WorkRoot=Join-Path $fixtureRoot '../escape'
Reject-AuthorityFixture {Assert-PayloadAuthorityOwned $bad $snapshot $contextFile $ownedFile} 'NATIVE_ABSOLUTE'
$bad=$owned | ConvertTo-Json | ConvertFrom-Json;$bad.WorkRoot=$bad.ArtifactRoot
Reject-AuthorityFixture {Assert-PayloadAuthorityOwned $bad $snapshot $contextFile $ownedFile} 'AUTHORITY_ROOT_IDENTITY'
$bad=$owned | ConvertTo-Json | ConvertFrom-Json;$bad.WorkRoot=$bases[0]
Reject-AuthorityFixture {Assert-PayloadAuthorityOwned $bad $snapshot $contextFile $ownedFile} 'AUTHORITY_ROOT_SCOPE'
$intent=New-PayloadAuthorityIntent $contextFile $contextPin $ownedFile $ownedPin $snapshot.sha256 'UNIT_MOCK'
Assert-AuthorityFixture ($intent.status -ceq 'PENDING') 'INTENT_NOT_NATIVE_PASS'
Reject-AuthorityFixture {Invoke-PayloadAuthorityExecution $intent.file $intent.sha256} 'AUTHORITY_UNIT_MOCK_NO_EXECUTION'
$seal=Complete-PayloadAuthorityIntent $intent.file $intent.sha256
$saved=Read-ColdPinnedJson $seal.file $seal.sha256
Assert-AuthorityFixture ($global:NativePayloadAuthoritySealed.sha256 -ceq $seal.sha256) 'PIN_RETAINED_BEFORE_NATIVE'
Assert-AuthorityFixture ($saved.status -ceq 'PENDING' -and $saved.evidenceKind -ceq 'UNIT_MOCK') 'SEAL_NOT_NATIVE_PASS'
Assert-AuthorityFixture ($saved.Expected.SourceRoot -ceq $bases[0] -and $saved.Expected.TargetRoot -ceq $targetRoot) 'SOURCE_TARGET_IDENTITY'
Assert-AuthorityFixture ($saved.Expected.RequireFallback -eq $true) 'FALLBACK_NOT_LOOSENED'
Assert-AuthorityFixture ($saved.Expected.InstalledRoot -ceq (Join-Path (Join-Path $owned.RunRoot 'Δ 测试') 'CashPrediction')) 'EXACT_INSTALLED_ROOT'
foreach ($pair in 'BaseManifest','OriginalTargetManifest','Manifest','OriginalCommand','Command','OriginalLifecycle','Lifecycle') {
    Assert-AuthorityFixture ($null -ne $saved.Expected.PSObject.Properties[$pair+'File'] -and $saved.Expected.($pair+'Sha256') -cmatch '^[0-9a-f]{64}$') ('EXPECTED_'+$pair)
}
Reject-AuthorityFixture {Complete-PayloadAuthorityIntent $intent.file $intent.sha256} 'exists'
# Второй pre-native intent для derived Unicode: данные synthetic, contracts не native PASS.
$unicodeContext=$context | ConvertTo-Json -Depth 32 | ConvertFrom-Json
$unicodeContext.Row.scenario='unicode-payload'
$unicodeOwned=$owned | ConvertTo-Json | ConvertFrom-Json
$unicodeOwned.Nonce=[guid]::NewGuid().ToString()
$unicodeContextFile=Write-AuthorityFixture (Join-Path $mainRoot 'unicode-context.json') $unicodeContext
$unicodeOwnedFile=Write-AuthorityFixture (Join-Path $mainRoot 'unicode-owned.json') $unicodeOwned
$unicodeContextPin=Get-PayloadAuthorityHash $unicodeContextFile;$unicodeOwnedPin=Get-PayloadAuthorityHash $unicodeOwnedFile
$unicodeInput=Get-PayloadAuthorityInput $unicodeContextFile $unicodeContextPin
$unicodeIntent=New-PayloadAuthorityIntent $unicodeContextFile $unicodeContextPin $unicodeOwnedFile $unicodeOwnedPin $unicodeInput.sha256 'UNIT_MOCK'
$null=New-Item -ItemType Directory -Path $unicodeOwned.WorkRoot
$clone=Join-Path $unicodeOwned.WorkRoot 'target';Copy-Item -LiteralPath $targetRoot -Destination $clone -Recurse
$fixture=Get-NativePayloadFixture;$fixtureFile=Join-Path $clone $fixture.path
$null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($fixtureFile))
[IO.File]::WriteAllBytes($fixtureFile,$fixture.bytes)
$script:versions[$clone]=$script:versions[$targetRoot]
$derived=$target | ConvertTo-Json -Depth 32 | ConvertFrom-Json
$derived.treeSha256=('d'*64)
$derived.files+=@([pscustomobject]@{path=$fixture.path;sha256=$fixture.sha256;sizeBytes=$fixture.bytes.Length;readOnly=$false})
$newArtifact=Join-Path $unicodeOwned.ArtifactRoot 'artifacts';$null=New-Item -ItemType Directory -Path $newArtifact
$derivedFile=Write-AuthorityFixture (Join-Path $newArtifact 'update.json') $derived
$derivedSha=Get-PayloadAuthorityHash $derivedFile
$derivedCold=New-ArtifactCommand $cold $derivedFile $derivedSha
$derivedCommand=Write-AuthorityFixture (Join-Path $unicodeOwned.ArtifactRoot 'command.json') $derivedCold
$derivedLife=$life | ConvertTo-Json -Depth 32 | ConvertFrom-Json
$derivedLife.artifactDir=$newArtifact;$derivedLife.manifestSha256=$derivedSha
$null=Write-AuthorityFixture (Join-Path $unicodeOwned.WorkRoot 'derived-lifecycle.json') $derivedLife
$badCommand=$derivedCold | ConvertTo-Json -Depth 32 | ConvertFrom-Json;$badCommand.toolFiles[0].sha256=('0'*64)
$null=Write-AuthorityFixture $derivedCommand $badCommand
Reject-AuthorityFixture {Complete-PayloadAuthorityIntent $unicodeIntent.file $unicodeIntent.sha256} 'AUTHORITY_TOOL_OR_BASE_CHANGED'
$null=Write-AuthorityFixture $derivedCommand $derivedCold
$badLife=$derivedLife | ConvertTo-Json -Depth 32 | ConvertFrom-Json;$badLife.manifestSha256=('0'*64)
$null=Write-AuthorityFixture (Join-Path $unicodeOwned.WorkRoot 'derived-lifecycle.json') $badLife
Reject-AuthorityFixture {Complete-PayloadAuthorityIntent $unicodeIntent.file $unicodeIntent.sha256} 'AUTHORITY_PREPARED_CONFIG'
$null=Write-AuthorityFixture (Join-Path $unicodeOwned.WorkRoot 'derived-lifecycle.json') $derivedLife
$badDerived=$derived | ConvertTo-Json -Depth 32 | ConvertFrom-Json;$badDerived.commitSha=('f'*40)
$null=Write-AuthorityFixture $derivedFile $badDerived
$badSha=Get-PayloadAuthorityHash $derivedFile
$null=Write-AuthorityFixture $derivedCommand (New-ArtifactCommand $cold $derivedFile $badSha)
$derivedLife.manifestSha256=$badSha
$null=Write-AuthorityFixture (Join-Path $unicodeOwned.WorkRoot 'derived-lifecycle.json') $derivedLife
Reject-AuthorityFixture {Complete-PayloadAuthorityIntent $unicodeIntent.file $unicodeIntent.sha256} 'PAYLOAD_DERIVED_IDENTITY'
$null=Write-AuthorityFixture $derivedFile $derived
$derivedSha=Get-PayloadAuthorityHash $derivedFile
$null=Write-AuthorityFixture $derivedCommand (New-ArtifactCommand $cold $derivedFile $derivedSha)
$derivedLife.manifestSha256=$derivedSha
$null=Write-AuthorityFixture (Join-Path $unicodeOwned.WorkRoot 'derived-lifecycle.json') $derivedLife
$unicodeSeal=Complete-PayloadAuthorityIntent $unicodeIntent.file $unicodeIntent.sha256
$unicodeSaved=Read-ColdPinnedJson $unicodeSeal.file $unicodeSeal.sha256
Assert-AuthorityFixture ($unicodeSaved.Expected.TargetRoot -ceq $clone -and $unicodeSaved.Expected.OriginalTargetRoot -ceq $targetRoot) 'DERIVED_VS_RELEASE_TARGET'
Assert-AuthorityFixture ($unicodeSaved.Expected.ManifestSha256 -ceq $derivedSha -and $unicodeSaved.Expected.RequireFallback) 'DERIVED_PIN_AND_FALLBACK'
$null=New-Item -ItemType Directory -Path $unicodeOwned.RunRoot
Reject-AuthorityFixture {Complete-PayloadAuthorityIntent $unicodeIntent.file $unicodeIntent.sha256} 'AUTHORITY_NATIVE_ALREADY_STARTED'
[IO.File]::AppendAllText((Join-Path $bases[0] 'payload.bin'),'changed')
Reject-AuthorityFixture {Read-PayloadAuthorityIntent $intent.file $intent.sha256} 'UNIT_MOCK_TREE'
# Восстановление только собственного synthetic файла; никогда shared inputs.
[IO.File]::WriteAllText((Join-Path $bases[0] 'payload.bin'),'UNIT_MOCK_1')
[IO.File]::AppendAllText($tool,'changed')
Reject-AuthorityFixture {Read-PayloadAuthorityIntent $intent.file $intent.sha256} 'AUTHORITY_SOURCE_CHANGED'
[IO.File]::WriteAllText($tool,'UNIT_MOCK_TOOL')
$badContext=$context | ConvertTo-Json -Depth 32 | ConvertFrom-Json;$badContext.Source=$bases[1]
Reject-AuthorityFixture {Assert-NativePayloadContext $badContext} 'PAYLOAD_BASE_CONTEXT'
$badContext=$context | ConvertTo-Json -Depth 32 | ConvertFrom-Json;$badContext.TargetPortableDir=$bases[0]
Reject-AuthorityFixture {Assert-NativePayloadContext $badContext} 'PAYLOAD_INPUT_OVERLAP'
# Реальные AST frozen producer ещё не имеют review hook; исполнение должно оставаться запрещённым.
Assert-AuthorityFixture (-not (Get-Command Invoke-NativePayloadWorker).Parameters.ContainsKey('AuthorityFile')) 'FROZEN_WORKER_UNPATCHED'
Assert-AuthorityFixture (-not (Get-Command Invoke-NativeCell).Parameters.ContainsKey('OwnedRunRoot')) 'FROZEN_LIFECYCLE_UNPATCHED'
[pscustomobject]@{status='UNIT_MOCK';checks=$script:checks;nativeStatus='PENDING';fixtureRoot=$fixtureRoot;fullMatrix='PENDING';releaseProvenance='PENDING'} | ConvertTo-Json
