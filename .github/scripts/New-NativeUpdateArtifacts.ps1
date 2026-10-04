<#
.SYNOPSIS
Готовит реальные две прямые дельты штатным pinned UpdateTool, без native запуска.
.DESCRIPTION
PortableDir = B1,B2; TargetPortableDir = T. CommandFile/SHA имеют существующий
Bootstrap контракт. Исходный targetManifest содержит ноль дельт; полный ZIP
лежит рядом с ним под manifest.assetName. OutputDir - новый прямой Temp/UUID.
artifacts содержит только ZIP, две дельты и update.json; остальные доказательства
и новый CommandFile находятся рядом. Итог PREPARED, nativeStatus=PENDING.
CLI ограничен существующими create/manifest/apply/verify, ASCII Base64 transport.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CommandFile,
    [Parameter(Mandatory)][string]$CommandFileSha256,
    [Parameter(Mandatory)][string]$Runtime,
    [Parameter(Mandatory)][ValidateCount(2,2)][string[]]$PortableDir,
    [Parameter(Mandatory)][string]$TargetPortableDir,
    [Parameter(Mandatory)][string]$OutputDir
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3

# Только AST определения новых и frozen контрактов, без выполнения runner/builders.
function Import-ArtifactContracts([string]$ScriptsRoot) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot 'New-NativeUpdateLifecycleConfig.ps1'),[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'ARTIFACT_IMPORT_PARSE'}
    foreach ($definition in @($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))) {
        $text=$definition.Extent.Text -replace '^function ', 'function global:'
        . ([scriptblock]::Create($text))
    }
    Import-LifecycleConfigContracts $ScriptsRoot
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot 'Test-NativeUpdateLifecycle.ps1'),[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'ARTIFACT_IMPORT_PARSE'}
    $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Assert-NativeSeam'},$true))
    if ($definitions.Count -ne 1) {throw 'ARTIFACT_IMPORT_CONTRACT'}
    . ([scriptblock]::Create(($definitions[0].Extent.Text -replace '^function ', 'function global:')))
    # Get-ColdMainModule теперь зависит от строгого локального module-path parser.
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot 'Test-UpdateBootstrap.ps1'),[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'ARTIFACT_IMPORT_PARSE'}
    foreach ($name in 'Test-ColdExternalModules','Read-ColdConfig') {
        $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($definitions.Count -ne 1) {throw 'ARTIFACT_IMPORT_CONTRACT'}
        . ([scriptblock]::Create(($definitions[0].Extent.Text -replace '^function ', 'function global:')))
    }
}

# Все native кандидаты имеют локальную пару и одинаковый mainmodule соответствующего cfg.
function Assert-ArtifactLocalConfigs([string]$Root,[string]$Target) {
    foreach ($path in 'app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg') {
        $original=Read-ColdConfig (Join-Path $Root $path);$targetText=Read-ColdConfig (Join-Path $Target $path)
        if ((Get-ColdMainModule $original) -cne (Get-ColdMainModule $targetText)) {throw 'ARTIFACT_CFG_TARGET_MODULE'}
        if (-not (Test-ColdExternalModules $original) -or -not (Test-ColdExternalModules $targetText)) {throw 'COLD_CFG_MODULE_PATH'}
    }
}

# Единственный допустимый output - ещё не существующий прямой каталог Temp UUID.
function Assert-ArtifactOutput([string]$Path,[string[]]$Inputs) {
    $root=Assert-NativeAbsolute $Path;$temp=Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())
    if (-not [IO.Path]::GetDirectoryName($root).Equals($temp,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($root) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'ARTIFACT_OUTPUT_SCOPE'}
    if (Test-Path -LiteralPath $root) {throw 'ARTIFACT_OUTPUT_EXISTS'}
    foreach ($inputPath in $Inputs) {
        [void](Assert-NativeAbsolute $inputPath)
        if ((Test-PortablePathContains $root $inputPath) -or (Test-PortablePathContains $inputPath $root)) {throw 'ARTIFACT_OUTPUT_OVERLAP'}
    }
    return $root
}

# Input JSON сохраняет wire типы; pin и strict JSON проверяются до операций CLI.
function Read-ArtifactManifest([string]$Path,[string]$Hash) {
    $m=Read-ColdPinnedJson $Path $Hash
    $bytes=[IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -gt 8388608 -or [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $Hash) {throw 'ARTIFACT_MANIFEST_PIN'}
    $text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
    $options=[Text.Json.JsonDocumentOptions]::new();$options.MaxDepth=64
    $doc=[Text.Json.JsonDocument]::Parse($text,$options)
    try {Assert-LifecycleJsonNode $doc.RootElement;$m.publishedAtUtc=$doc.RootElement.GetProperty('publishedAtUtc').GetString()} finally {$doc.Dispose()}
    Assert-ColdKeys $m @('schemaVersion','releaseNumber','commitSha','version','publishedAtUtc','assetName','sizeBytes','sha256','treeSha256','files','deltaPatches')
    if (-not (Test-ColdInteger $m.schemaVersion 2) -or $m.schemaVersion -ne 2 -or
        -not (Test-ColdInteger $m.releaseNumber 1) -or $m.releaseNumber -gt [int]::MaxValue -or
        $m.commitSha -isnot [string] -or $m.commitSha -cnotmatch '^[0-9a-f]{40}$' -or
        $m.version -isnot [string] -or [string]::IsNullOrWhiteSpace($m.version) -or
        $m.assetName -cne 'CashPrediction-portable.zip' -or -not (Test-ColdInteger $m.sizeBytes 1) -or $m.sizeBytes -gt 536870912 -or
        $m.sha256 -isnot [string] -or $m.sha256 -cnotmatch '^[0-9a-f]{64}$' -or $m.files -isnot [array] -or $m.deltaPatches -isnot [array]) {throw 'ARTIFACT_MANIFEST_SCHEMA'}
    [void](Get-NativeUtcTicks $m.publishedAtUtc);Assert-ColdImageInventory $m.files $m.treeSha256
    return $m
}

# Pinned исходные деревья должны иметь положительные, distinct, forward identities.
function Assert-ArtifactBases($Bases,$Target) {
    if (@($Bases).Count -ne 2 -or @($Target.deltaPatches).Count -ne 0) {throw 'ARTIFACT_BASE_COUNT'}
    if ($Bases[0].releaseNumber -eq $Bases[1].releaseNumber -or $Bases[0].commitSha -ceq $Bases[1].commitSha) {throw 'ARTIFACT_BASE_IDENTITY'}
    foreach ($base in $Bases) {
        if (-not (Test-ColdInteger $base.releaseNumber 1) -or $base.releaseNumber -ge $Target.releaseNumber -or $base.commitSha -ceq $Target.commitSha) {throw 'ARTIFACT_FORWARD_RELEASE'}
    }
}

# Создаёт JSON исключительно из verified base identity и реально созданного patch.
function New-ArtifactDescriptor($Base,[string]$Patch) {
    $item=Get-Item -LiteralPath (Resolve-PortableSafetyPath $Patch) -Force
    if ($item.PSIsContainer -or $item.Length -lt 1 -or $item.Length -gt 536870912 -or
        $item.Name -cne ('CashPrediction.from-'+$Base.releaseNumber+'.cpdelta') -or
        -not (Test-ColdInteger $Base.releaseNumber 1) -or $Base.commitSha -cnotmatch '^[0-9a-f]{40}$' -or $Base.treeSha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'ARTIFACT_DESCRIPTOR'}
    return [pscustomobject][ordered]@{algorithm='cashprediction-tree-delta';algorithmVersion=1;baseReleaseNumber=$Base.releaseNumber;
        baseCommitSha=$Base.commitSha;baseTreeSha256=$Base.treeSha256;assetName=$item.Name;sizeBytes=[long]$item.Length;
        sha256=(Get-FileHash -LiteralPath $Patch).Hash.ToLowerInvariant()}
}

# Сохранение новых JSON без overwrite, с forced flush; ошибки оставляют собственные доказательства.
function Write-ArtifactJson([string]$Path,$Value) {
    [void](Resolve-PortableSafetyPath $Path)
    $bytes=[Text.UTF8Encoding]::new($false,$true).GetBytes((ConvertTo-Json -InputObject $Value -Depth 64 -Compress))
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
}

# Единственная процессная граница повторяет frozen pins; Invoke-ColdTool уже даёт
# bounded PID/birth cleanup, stdout/stderr и ASCII --arguments-base64.
function Invoke-ArtifactTool($Context,[string[]]$Arguments) {
    if (-not $Arguments.Count -or $Arguments[0] -cnotin @('create','manifest','apply','verify')) {throw 'ARTIFACT_CLI_COMMAND'}
    [void](Read-ColdPinnedJson $Context.commandPath $Context.commandSha)
    Assert-ColdCommand $Context.command $Context.java $Context.sources $Context.targetRoot
    return (Invoke-ColdTool $Context.command $Context.java $Arguments $Context.logs $Context.output)
}

# Новый CommandFile меняет только targetManifest и targetManifestSha256.
function New-ArtifactCommand($Original,[string]$Manifest,[string]$Hash) {
    $copy=ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Original -Depth 64 -Compress)
    $copy.targetManifest=$Manifest;$copy.targetManifestSha256=$Hash
    foreach ($property in $Original.PSObject.Properties) {
        if ($property.Name -in @('targetManifest','targetManifestSha256')) {continue}
        if ((ConvertTo-Json -InputObject $property.Value -Depth 64 -Compress) -cne
            (ConvertTo-Json -InputObject $copy.($property.Name) -Depth 64 -Compress)) {throw 'ARTIFACT_COMMAND_CLONE'}
    }
    return $copy
}

# Полный inventory apply tree сравнивается с frozen target, не только digest.
function Assert-ArtifactAppliedInventory($Actual,$TargetFiles) {
    if (-not (Test-ColdInventoryEqual $Actual $TargetFiles)) {throw 'ARTIFACT_APPLIED_INVENTORY'}
}

# Фактический штатный create -> manifest(--delta twice) -> apply+verify каждой базы.
function Invoke-ArtifactPreparation($Context) {
    $artifacts=Join-Path $Context.output 'artifacts';$descriptors=Join-Path $Context.output 'descriptors';$proof=Join-Path $Context.output 'proof'
    foreach ($directory in $artifacts,$descriptors,$proof,$Context.logs) {$null=New-Item -ItemType Directory -Path $directory -ErrorAction Stop}
    $archive=Join-Path $artifacts $Context.target.assetName
    [IO.File]::Copy($Context.archive,$archive,$false)
    if ((Get-Item -LiteralPath $archive).Length -ne $Context.target.sizeBytes -or
        (Get-FileHash -LiteralPath $archive).Hash.ToLowerInvariant() -cne $Context.target.sha256) {throw 'ARTIFACT_FULL_COPY'}
    $receipts=[Collections.Generic.List[object]]::new();$patches=@();$descriptorPaths=@();$descriptorHashes=@();$expectedDescriptors=@()
    for ($index=0;$index -lt 2;$index++) {
        $base=$Context.bases[$index];$source=$Context.sources[$index]
        $patch=Join-Path $artifacts ('CashPrediction.from-'+$base.releaseNumber+'.cpdelta')
        $receipts.Add((Invoke-ArtifactTool $Context @('create','--base',$source,'--base-release',[string]$base.releaseNumber,
            '--base-commit',$base.commitSha,'--target',$Context.targetRoot,'--manifest',$Context.command.targetManifest,'--out',$patch)))
        $descriptor=New-ArtifactDescriptor $base $patch;$descriptorPath=Join-Path $descriptors ('B'+($index+1)+'.json')
        Write-ArtifactJson $descriptorPath $descriptor
        $descriptorHashes+=@((Get-FileHash -LiteralPath $descriptorPath).Hash.ToLowerInvariant())
        $patches+=@($patch);$descriptorPaths+=@($descriptorPath);$expectedDescriptors+=@($descriptor)
    }
    $manifest=Join-Path $artifacts 'update.json'
    $receipts.Add((Invoke-ArtifactTool $Context @('manifest','--root',$Context.targetRoot,'--archive',$archive,
        '--release',[string]$Context.target.releaseNumber,'--commit',$Context.target.commitSha,'--version',$Context.target.version,
        '--published-at',$Context.target.publishedAtUtc,'--out',$manifest,'--delta',$descriptorPaths[0],'--delta',$descriptorPaths[1])))
    $manifestSha=(Get-FileHash -LiteralPath $manifest).Hash.ToLowerInvariant()
    $generated=Read-LifecycleManifest $artifacts $manifestSha
    if ($generated.releaseNumber -ne $Context.target.releaseNumber -or $generated.commitSha -cne $Context.target.commitSha -or
        $generated.version -cne $Context.target.version -or $generated.publishedAtUtc -cne $Context.target.publishedAtUtc -or
        $generated.sha256 -cne $Context.target.sha256 -or $generated.sizeBytes -ne $Context.target.sizeBytes -or
        $generated.treeSha256 -cne $Context.target.treeSha256 -or -not (Test-ColdInventoryEqual $generated.files $Context.target.files) -or
        -not (Test-ColdInventoryEqual $generated.deltaPatches $expectedDescriptors)) {throw 'ARTIFACT_GENERATED_IDENTITY'}
    $proofs=@()
    for ($index=0;$index -lt 2;$index++) {
        $base=$Context.bases[$index];$tree=Join-Path $proof ('B'+($index+1))
        $receipts.Add((Invoke-ArtifactTool $Context @('apply','--base',$Context.sources[$index],'--base-release',[string]$base.releaseNumber,
            '--base-commit',$base.commitSha,'--patch',$patches[$index],'--manifest',$manifest,'--out',$tree)))
        $receipts.Add((Invoke-ArtifactTool $Context @('verify','--root',$tree,'--manifest',$manifest)))
        $actual=@(Assert-ColdTree $tree $generated)
        Assert-ArtifactAppliedInventory $actual $Context.target.files
        $proofs+=@([pscustomobject]@{base=$base.releaseNumber;root=$tree;files=$actual;treeSha256=(Get-ColdTreeHash $actual)})
    }
    # Ни CLI, ни копирование не могут менять frozen input trees/archive.
    for ($index=0;$index -lt 2;$index++) {[void](Assert-ColdTree $Context.sources[$index] $Context.bases[$index])}
    [void](Assert-ColdTree $Context.targetRoot $Context.target)
    if ((Get-FileHash -LiteralPath $Context.archive).Hash.ToLowerInvariant() -cne $Context.target.sha256) {throw 'ARTIFACT_INPUT_CHANGED'}
    # Последующий apply/verify не должен незаметно испортить раннее proof дерево,
    # patch, fallback ZIP или descriptor. Проверяем всё снова перед PREPARED.
    [void](Read-LifecycleManifest $artifacts $manifestSha)
    for ($index=0;$index -lt 2;$index++) {
        [void](Read-ColdPinnedJson $descriptorPaths[$index] $descriptorHashes[$index])
        $finalFiles=@(Assert-ColdTree $proofs[$index].root $generated)
        Assert-ArtifactAppliedInventory $finalFiles $Context.target.files
    }
    $newCommand=New-ArtifactCommand $Context.command $manifest $manifestSha
    Assert-ColdCommand $newCommand $Context.java $Context.sources $Context.targetRoot
    $commandPath=Join-Path $Context.output 'command.json';Write-ArtifactJson $commandPath $newCommand
    $commandSha=(Get-FileHash -LiteralPath $commandPath).Hash.ToLowerInvariant()
    $result=[pscustomobject][ordered]@{schemaVersion=1;status='PREPARED';nativeStatus='PENDING';artifactDir=$artifacts;
        manifest=$manifest;manifestSha256=$manifestSha;commandFile=$commandPath;commandFileSha256=$commandSha;proofs=$proofs;toolReceipts=$receipts.ToArray()}
    Write-ArtifactJson (Join-Path $Context.output 'preparation.json') $result
    return $result
}

if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'ARTIFACT_WINDOWS_POWERSHELL7'}
Import-ArtifactContracts $PSScriptRoot
$sources=@($PortableDir | ForEach-Object {Assert-NativeAbsolute $_});$targetRoot=Assert-NativeAbsolute $TargetPortableDir;$java=Assert-NativeAbsolute $Runtime
if ($sources.Count -ne 2 -or $sources[0].Equals($sources[1],[StringComparison]::OrdinalIgnoreCase)) {throw 'ARTIFACT_BASE_COUNT'}
$command=Read-ColdPinnedJson $CommandFile $CommandFileSha256;Assert-ColdCommand $command $java $sources $targetRoot
$target=Read-ArtifactManifest $command.targetManifest $command.targetManifestSha256;$bases=@()
foreach ($source in $sources) {
    $entry=@($command.baseManifests | Where-Object {$_.portableDir -ceq $source})[0]
    $bases+=@(Read-ArtifactManifest $entry.manifest $entry.sha256)
}
Assert-ArtifactBases $bases $target
$roots=@($sources)+@($targetRoot)
for ($index=0;$index -lt 3;$index++) {
    $root=$roots[$index];Assert-PortableTreeHasNoLinks $root;Assert-PortableSourceEntries @(Get-ChildItem -LiteralPath $root -Force)
    Assert-ColdNativeImage $root;Assert-NativeSeam $root
    Assert-ArtifactLocalConfigs $root $targetRoot
    foreach ($other in $roots) {if ($root -cne $other -and (Test-PortablePathContains $root $other)) {throw 'ARTIFACT_INPUT_OVERLAP'}}
    $m=if ($index -lt 2) {$bases[$index]} else {$target}
    [void](Assert-ColdTree $root $m);$version=Get-ColdVersion $root
    if ($version.releaseNumber -ne $m.releaseNumber -or $version.commitSha -cne $m.commitSha) {throw 'ARTIFACT_VERSION_PIN'}
}
$archive=Assert-NativeAbsolute (Join-Path ([IO.Path]::GetDirectoryName($command.targetManifest)) $target.assetName)
$item=Get-Item -LiteralPath $archive -Force
if ($item.PSIsContainer -or $item.Length -ne $target.sizeBytes -or (Get-FileHash -LiteralPath $archive).Hash.ToLowerInvariant() -cne $target.sha256) {throw 'ARTIFACT_ARCHIVE_PIN'}
$inputs=@($roots)+@($java,$CommandFile,$archive,$command.targetManifest,$command.helperScript)+@($command.toolFiles | ForEach-Object {$_.path})+@($command.baseManifests | ForEach-Object {$_.manifest})
$output=Assert-ArtifactOutput $OutputDir $inputs
$null=New-Item -ItemType Directory -Path $output -ErrorAction Stop
$context=[pscustomobject]@{output=$output;logs=(Join-Path $output 'logs');command=$command;commandPath=$CommandFile;commandSha=$CommandFileSha256;
    java=$java;sources=$sources;targetRoot=$targetRoot;target=$target;bases=$bases;archive=$archive}
try {Invoke-ArtifactPreparation $context} catch {
    Write-ArtifactJson (Join-Path $output 'failure.json') ([ordered]@{schemaVersion=1;status='FAILED';nativeStatus='PENDING';reason=$_.Exception.Message})
    throw
}
