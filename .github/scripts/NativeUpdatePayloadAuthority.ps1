<#
.SYNOPSIS
Предстартовая authority MAIN для выбранной payload клетки, не native acceptance.
.DESCRIPTION
MAIN сохраняет Context и Owned JSON под собственным прямым Temp UUID, закрепляет оба SHA.
Owned: MainRoot, WorkRoot (Context.Evidence), ArtifactRoot (новый Temp UUID),
RunRoot (новый Temp/run-UUID), Nonce (UUID), RequireFallback (bool).
Get-NativePayloadAuthorityInputPin возвращает independent snapshot pin до подготовки.
New-NativePayloadAuthority сохраняет intent.json CreateNew, не вызывает CLI/native.
Complete-NativePayloadAuthority перечитывает известные prepared paths ДО native вызова,
проверяет исходные pins/derived identity и сохраняет sealed Expected CreateNew.
Invoke-NativePayloadAuthorizedScenario работает только после review patch frozen producer.
Caller удерживает intent SHA и seal SHA вне непроверенной Row; UNIT_MOCK не native PASS.
MAIN callsite (после review patch, не запускать при live freeze):
1. Задать Row identity из selected cell, Source/PortableDir/TargetPortableDir и
   CommandFile/LifecycleFile SHA из независимых frozen Cold/Life pins, не из Row return.
   Задать Context.Evidence=Owned.WorkRoot; сохранить Context/Owned под Owned.MainRoot.
2. $inputPin=Get-NativePayloadAuthorityInputPin $contextFile $contextSha
   $authority=New-NativePayloadAuthority $contextFile $contextSha $ownedFile $ownedSha $inputPin.sha256 NATIVE
3. $run=Invoke-NativePayloadAuthorizedScenario $authority.file $authority.sha256
   Здесь seal создаёт Expected до native и удерживает SHA в private memory; payload return
   не является источником Expected. После native seal/input pins снова проверяются.
4. MAIN закрепляет SHA наблюдения по $run.observationFile и вызывает существующий
   Test-NativePayloadAcceptance с $run.Expected и явным EvidenceKind=NATIVE.
   Authority status=PENDING не gate PASS; RequireFallback=true остаётся неизменённым.
Producer chronology: MAIN intent -> derived clone/CLI artifacts -> authority seal ->
Invoke-NativeCell (launch/exit/install/inventories/finishedAt/finally cleanup/cell.json) ->
payload observation -> finally input-pin-check -> authority re-read -> existing acceptance.
RequireFallback=true текущим delta-only producer не покрыт и не ослабляется.
#>

# Загружает frozen определения в отдельном scope; тела builders/runners не исполняются.
function Invoke-PayloadAuthorityPrivate([string]$Operation,[object[]]$Arguments) {
    $worker=[PowerShell]::Create()
    try {
        [void]$worker.AddScript({param($root,$operation,$arguments)
            $ErrorActionPreference='Stop';Set-StrictMode -Version 3
            . (Join-Path $root 'NativeUpdatePayloadScenarios.ps1')
            Initialize-NativePayloadDependencies $root
            . (Join-Path $root 'NativeUpdatePayloadAuthority.ps1')
            & $operation @arguments
        }).AddArgument($PSScriptRoot).AddArgument($Operation).AddArgument($Arguments)
        $result=$worker.Invoke()
        if ($worker.HadErrors) {throw $worker.Streams.Error[0]}
        return $result
    } finally {$worker.Dispose()}
}

# Только чтение независимых входов; MAIN удерживает возвращённый pin до prepare/native.
function Get-NativePayloadAuthorityInputPin([string]$ContextFile,[string]$ContextSha256) {
    Invoke-PayloadAuthorityPrivate 'Get-PayloadAuthorityInput' @($ContextFile,$ContextSha256)
}

# MAIN передаёт ранее закреплённые context/config/snapshot; Row outcome не принимается.
function New-NativePayloadAuthority([string]$ContextFile,[string]$ContextSha256,
    [string]$OwnedFile,[string]$OwnedSha256,[string]$InputSha256,
    [Parameter(Mandatory)][ValidateSet('NATIVE','UNIT_MOCK')][string]$EvidenceKind) {
    Invoke-PayloadAuthorityPrivate 'New-PayloadAuthorityIntent' @($ContextFile,$ContextSha256,$OwnedFile,$OwnedSha256,$InputSha256,$EvidenceKind)
}

# Seal вызван producer до Invoke-NativeCell; не получает helper result либо status Row.
function Complete-NativePayloadAuthority([string]$AuthorityFile,[string]$AuthoritySha256) {
    Invoke-PayloadAuthorityPrivate 'Complete-PayloadAuthorityIntent' @($AuthorityFile,$AuthoritySha256)
}

# Явный экспорт исполнения MAIN; без согласованного patch отказывает до worker/native.
function Invoke-NativePayloadAuthorizedScenario([string]$AuthorityFile,[string]$AuthoritySha256) {
    Invoke-PayloadAuthorityPrivate 'Invoke-PayloadAuthorityExecution' @($AuthorityFile,$AuthoritySha256)
}

# Файл разрешается canonical guard до чтения SHA, включая все существующие ancestors.
function Get-PayloadAuthorityHash([string]$Path) {
    [void](Assert-NativeAbsolute $Path);[void](Resolve-PortableSafetyPath $Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {throw 'AUTHORITY_FILE_MISSING'}
    return (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()
}

# Снимок исходных roots, runtime/tool/harness и исходников PS импортируемых контрактов.
function Get-PayloadAuthorityInput([string]$ContextFile,[string]$ContextSha256) {
    $context=Read-ColdPinnedJson $ContextFile $ContextSha256;Assert-NativePayloadContext $context
    $cold=Read-ColdPinnedJson $context.CommandFile $context.CommandFileSha256
    $life=Read-ColdPinnedJson $context.LifecycleFile $context.LifecycleFileSha256
    Assert-ColdCommand $cold $context.Java $context.PortableDir $context.TargetPortableDir
    Assert-NativeLifecycleConfig $life
    if ($cold.targetManifestSha256 -cne $life.manifestSha256 -or
        $cold.targetManifest -cne (Join-Path $life.artifactDir 'update.json')) {throw 'AUTHORITY_ORIGINAL_CONFIG'}
    $target=Read-LifecycleManifest $life.artifactDir $life.manifestSha256
    $bases=@(foreach ($root in $context.PortableDir) {
        $entry=@($cold.baseManifests | Where-Object portableDir -CEQ $root)
        if ($entry.Count -ne 1) {throw 'AUTHORITY_BASE_IDENTITY'}
        $manifest=Read-ColdPinnedJson $entry[0].manifest $entry[0].sha256
        [void](Assert-ColdTree $root $manifest)
        $version=Get-ColdVersion $root
        if ($version.commitSha -cne $manifest.commitSha -or $version.releaseNumber -ne $manifest.releaseNumber) {throw 'AUTHORITY_APPINFO'}
        $manifest
    })
    [void](Assert-ColdTree $context.TargetPortableDir $target)
    $version=Get-ColdVersion $context.TargetPortableDir
    if ($version.commitSha -cne $target.commitSha -or $version.releaseNumber -ne $target.releaseNumber -or
        @($bases).Count -ne 2 -or @($bases | Where-Object releaseNumber -GE $target.releaseNumber).Count) {throw 'AUTHORITY_TARGET_IDENTITY'}
    $protected=@($context.PortableDir)+@($context.TargetPortableDir,$ContextFile,$context.CommandFile,
        $context.LifecycleFile,$context.Java,$cold.helperScript,$life.artifactDir)+
        @($cold.toolFiles | ForEach-Object path)+@($cold.baseManifests | ForEach-Object manifest)+@($life.harnessClasspath.Split(';'))
    # Закрепляем только реальные imported producer sources, а не чужие disjoint sidecars.
    $protected+=@('NativeUpdatePayloadAuthority.ps1','NativeUpdatePayloadScenarios.ps1','New-NativeUpdateArtifacts.ps1',
        'New-NativeUpdateLifecycleConfig.ps1','Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1',
        'Test-Portable.ps1','New-UpdateBootstrapCommands.ps1','S7-Release.ps1' | ForEach-Object {Join-Path $PSScriptRoot $_})
    $snapshot=Get-NativePayloadSnapshot $protected
    $pin=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($snapshot))).ToLowerInvariant()
    return [pscustomobject]@{sha256=$pin;context=$context;cold=$cold;life=$life;target=$target;bases=$bases;protected=$protected}
}

# Заранее выделенные MAIN roots непересекающиеся, новые и находятся прямо в Temp.
function Assert-PayloadAuthorityOwned($Owned,$authorityInput,[string]$ContextFile,[string]$OwnedFile) {
    Assert-ColdKeys $Owned @('MainRoot','WorkRoot','ArtifactRoot','RunRoot','Nonce','RequireFallback')
    if ($Owned.Nonce -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or $Owned.RequireFallback -isnot [bool]) {throw 'AUTHORITY_OWNER_CONFIG'}
    $temp=Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())
    $roots=@($Owned.MainRoot,$Owned.WorkRoot,$Owned.ArtifactRoot,$Owned.RunRoot)
    foreach ($inputRoot in @($authorityInput.context.PortableDir)+@($authorityInput.context.TargetPortableDir,$authorityInput.life.artifactDir,$PSScriptRoot)) {
        if ((Test-PortablePathContains $Owned.MainRoot $inputRoot) -or (Test-PortablePathContains $inputRoot $Owned.MainRoot)) {throw 'AUTHORITY_MAIN_INPUT_OVERLAP'}
    }
    foreach ($root in $roots) {
        [void](Assert-NativeAbsolute $root);[void](Resolve-PortableSafetyPath $root)
        $pattern=if ($root -ceq $Owned.RunRoot) {'^run-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$'} else {'^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$'}
        if (-not [IO.Path]::GetDirectoryName($root).Equals($temp,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($root) -cnotmatch $pattern) {throw 'AUTHORITY_ROOT_SCOPE'}
        foreach ($other in $roots) {if ($root -cne $other -and ((Test-PortablePathContains $root $other) -or (Test-PortablePathContains $other $root))) {throw 'AUTHORITY_ROOT_OVERLAP'}}
    }
    if (@($roots | Sort-Object -Unique).Count -ne 4 -or $Owned.WorkRoot -cne $authorityInput.context.Evidence) {throw 'AUTHORITY_ROOT_IDENTITY'}
    if (-not (Test-Path -LiteralPath $Owned.MainRoot -PathType Container) -or
        -not (Test-PortablePathContains $Owned.MainRoot $ContextFile) -or
        -not (Test-PortablePathContains $Owned.MainRoot $OwnedFile)) {throw 'AUTHORITY_MAIN_CONFIG_SCOPE'}
    foreach ($root in $roots[1..3]) {
        if (Test-Path -LiteralPath $root) {throw 'AUTHORITY_ROOT_ALREADY_USED'}
        foreach ($inputPath in $authorityInput.protected) {
            if ((Test-PortablePathContains $inputPath $root) -or (Test-PortablePathContains $root $inputPath)) {throw 'AUTHORITY_INPUT_OVERLAP'}
        }
    }
}

# Только CreateNew: повторное использование nonce либо sealed config невозможно молча.
function Write-PayloadAuthorityNew([string]$Path,$Value) {
    [void](Resolve-PortableSafetyPath $Path)
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $Value -Depth 64))
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
    return [pscustomobject]@{file=$Path;sha256=(Get-PayloadAuthorityHash $Path);status='PENDING'}
}

# Independent intent сохраняется до derived CLI preparation и до любого native запуска.
function New-PayloadAuthorityIntent($ContextFile,$ContextSha256,$OwnedFile,$OwnedSha256,$InputSha256,$EvidenceKind) {
    $authorityInput=Get-PayloadAuthorityInput $ContextFile $ContextSha256
    if ($InputSha256 -cnotmatch '^[0-9a-f]{64}$' -or $authorityInput.sha256 -cne $InputSha256) {throw 'AUTHORITY_SOURCE_CHANGED'}
    $owned=Read-ColdPinnedJson $OwnedFile $OwnedSha256
    Assert-PayloadAuthorityOwned $owned $authorityInput $ContextFile $OwnedFile
    if ($EvidenceKind -cnotin @('NATIVE','UNIT_MOCK')) {throw 'AUTHORITY_KIND'}
    return Write-PayloadAuthorityNew (Join-Path $owned.MainRoot ($owned.Nonce+'-intent.json')) ([ordered]@{
        schemaVersion=1;status='PENDING';evidenceKind=$EvidenceKind;contextFile=$ContextFile;contextSha256=$ContextSha256;
        ownedFile=$OwnedFile;ownedSha256=$OwnedSha256;inputSha256=$InputSha256;preparedAt=[datetime]::UtcNow.ToString('o')})
}

# Re-read caller pinned intent/config; inputs должны остаться исходными на обеих границах.
function Read-PayloadAuthorityIntent($AuthorityFile,$AuthoritySha256) {
    $intent=Read-ColdPinnedJson $AuthorityFile $AuthoritySha256
    Assert-ColdKeys $intent @('schemaVersion','status','evidenceKind','contextFile','contextSha256','ownedFile','ownedSha256','inputSha256','preparedAt')
    if ($intent.schemaVersion -ne 1 -or $intent.status -cne 'PENDING' -or $intent.evidenceKind -cnotin @('NATIVE','UNIT_MOCK')) {throw 'AUTHORITY_INTENT'}
    $owned=Read-ColdPinnedJson $intent.ownedFile $intent.ownedSha256
    if ($AuthorityFile -cne (Join-Path $owned.MainRoot ($owned.Nonce+'-intent.json'))) {throw 'AUTHORITY_INTENT_SCOPE'}
    $authorityInput=Get-PayloadAuthorityInput $intent.contextFile $intent.contextSha256
    if ($authorityInput.sha256 -cne $intent.inputSha256) {throw 'AUTHORITY_SOURCE_CHANGED'}
    return [pscustomobject]@{intent=$intent;owned=$owned;input=$authorityInput}
}

# Seal выбирает paths только из intent, не из preparation return/Row/observation.
function Complete-PayloadAuthorityIntent($AuthorityFile,$AuthoritySha256) {
    $ticket=Read-PayloadAuthorityIntent $AuthorityFile $AuthoritySha256
    $owned=$ticket.owned;$authorityInput=$ticket.input;$context=$authorityInput.context
    if (Test-Path -LiteralPath $owned.RunRoot) {throw 'AUTHORITY_NATIVE_ALREADY_STARTED'}
    $manifestFile=$authorityInput.cold.targetManifest;$commandFile=$context.CommandFile;$lifecycleFile=$context.LifecycleFile
    $targetRoot=$context.TargetPortableDir
    if ($context.Row.scenario -ceq 'unicode-payload') {
        $targetRoot=Join-Path $owned.WorkRoot 'target';$manifestFile=Join-Path $owned.ArtifactRoot 'artifacts/update.json'
        $commandFile=Join-Path $owned.ArtifactRoot 'command.json';$lifecycleFile=Join-Path $owned.WorkRoot 'derived-lifecycle.json'
    }
    $manifestSha=Get-PayloadAuthorityHash $manifestFile;$commandSha=Get-PayloadAuthorityHash $commandFile;$lifeSha=Get-PayloadAuthorityHash $lifecycleFile
    $target=Read-LifecycleManifest ([IO.Path]::GetDirectoryName($manifestFile)) $manifestSha
    $cold=Read-ColdPinnedJson $commandFile $commandSha;Assert-ColdCommand $cold $context.Java $context.PortableDir $targetRoot
    $expectedCommand=New-ArtifactCommand $authorityInput.cold $manifestFile $manifestSha
    if (-not (Test-ColdInventoryEqual $cold $expectedCommand)) {throw 'AUTHORITY_TOOL_OR_BASE_CHANGED'}
    $life=Read-ColdPinnedJson $lifecycleFile $lifeSha;Assert-NativeLifecycleConfig $life
    if ($cold.targetManifest -cne $manifestFile -or $cold.targetManifestSha256 -cne $manifestSha -or
        $life.artifactDir -cne [IO.Path]::GetDirectoryName($manifestFile) -or $life.manifestSha256 -cne $manifestSha -or
        -not (Test-ColdInventoryEqual $life.harnessFiles $authorityInput.life.harnessFiles) -or $life.harnessClasspath -cne $authorityInput.life.harnessClasspath) {throw 'AUTHORITY_PREPARED_CONFIG'}
    [void](Assert-ColdTree $targetRoot $target)
    foreach ($base in $authorityInput.bases) {
        $deltas=@($target.deltaPatches | Where-Object {$_.baseReleaseNumber -eq $base.releaseNumber -and
            $_.baseCommitSha -ceq $base.commitSha -and $_.baseTreeSha256 -ceq $base.treeSha256})
        if ($base.releaseNumber -ge $target.releaseNumber -or $deltas.Count -ne 1) {throw 'AUTHORITY_DELTA_IDENTITY'}
    }
    if ($context.Row.scenario -ceq 'unicode-payload') {
        Assert-NativePayloadDerived $authorityInput.target $target (Get-NativePayloadFixture) (Get-ColdVersion $context.TargetPortableDir) (Get-ColdVersion $targetRoot)
    } elseif (-not (Test-ColdInventoryEqual $authorityInput.target $target)) {throw 'AUTHORITY_TARGET_CHANGED'}
    if ((Get-PayloadAuthorityInput $ticket.intent.contextFile $ticket.intent.contextSha256).sha256 -cne $ticket.intent.inputSha256) {throw 'AUTHORITY_SOURCE_CHANGED'}
    $index=if ($context.Row.base -ceq 'B1') {0} else {1};$entry=$authorityInput.cold.baseManifests[$index]
    $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
    $expected=[ordered]@{Scenario=$context.Row.scenario;Base=$context.Row.base;Client=$context.Row.client;Path=$context.Row.path;
        SourceRoot=$context.Source;OriginalTargetRoot=$context.TargetPortableDir;TargetRoot=$targetRoot;
        InstalledRoot=(Join-Path (Join-Path $owned.RunRoot $variants[$context.Row.path]) 'CashPrediction');RequireFallback=$owned.RequireFallback;
        BaseManifestFile=$entry.manifest;BaseManifestSha256=$entry.sha256;OriginalTargetManifestFile=$authorityInput.cold.targetManifest;
        OriginalTargetManifestSha256=$authorityInput.cold.targetManifestSha256;ManifestFile=$manifestFile;ManifestSha256=$manifestSha;
        OriginalCommandFile=$context.CommandFile;OriginalCommandSha256=$context.CommandFileSha256;CommandFile=$commandFile;CommandSha256=$commandSha;
        OriginalLifecycleFile=$context.LifecycleFile;OriginalLifecycleSha256=$context.LifecycleFileSha256;LifecycleFile=$lifecycleFile;LifecycleSha256=$lifeSha}
    $seal=Write-PayloadAuthorityNew (Join-Path $owned.MainRoot ($owned.Nonce+'-expected.json')) ([ordered]@{
        schemaVersion=1;status='PENDING';evidenceKind=$ticket.intent.evidenceKind;nonce=$owned.Nonce;
        authorityFile=$AuthorityFile;authoritySha256=$AuthoritySha256;sealedBeforeNativeAt=[datetime]::UtcNow.ToString('o');
        observationFile=(Join-Path $owned.WorkRoot 'payload-observation.json');Expected=$expected;fullMatrix='PENDING';releaseProvenance='PENDING'})
    # Только собственный private authority runspace удерживает pin ДО Invoke-NativeCell.
    $global:NativePayloadAuthoritySealed=$seal
    return $seal
}

# Никаких mock/native переходов: требует реальный reviewed hook и pinned NATIVE intent.
function Invoke-PayloadAuthorityExecution($AuthorityFile,$AuthoritySha256) {
    $ticket=Read-PayloadAuthorityIntent $AuthorityFile $AuthoritySha256
    if ($ticket.intent.evidenceKind -cne 'NATIVE') {throw 'AUTHORITY_UNIT_MOCK_NO_EXECUTION'}
    if (-not (Get-Command Invoke-NativePayloadWorker).Parameters.ContainsKey('AuthorityFile') -or
        $null -eq (Get-Command Invoke-NativeCellOwnedContext -ErrorAction SilentlyContinue) -or
        -not (Get-Command Invoke-NativeCellOwnedContext).Parameters.ContainsKey('OwnedRunRoot')) {throw 'AUTHORITY_PRODUCER_PATCH_PENDING'}
    $global:NativePayloadAuthoritySealed=$null
    $payload=Invoke-NativePayloadWorker $ticket.input.context $PSScriptRoot $AuthorityFile $AuthoritySha256
    if ($null -eq $global:NativePayloadAuthoritySealed) {throw 'AUTHORITY_PRE_NATIVE_SEAL_MISSING'}
    $seal=$global:NativePayloadAuthoritySealed
    $sealed=Read-ColdPinnedJson $seal.file $seal.sha256
    if ($sealed.authorityFile -cne $AuthorityFile -or $sealed.authoritySha256 -cne $AuthoritySha256 -or
        $sealed.nonce -cne $ticket.owned.Nonce) {throw 'AUTHORITY_SEAL_BINDING'}
    [void](Read-PayloadAuthorityIntent $AuthorityFile $AuthoritySha256)
    return [pscustomobject]@{status='PENDING';evidenceKind='NATIVE';authority=$seal;Expected=$sealed.Expected;
        observationFile=$sealed.observationFile;payload=$payload;fullMatrix='PENDING';releaseProvenance='PENDING'}
}
