<#
.SYNOPSIS
Создаёт pinned LifecycleFile для frozen native runner, без запуска процессов.
.DESCRIPTION
Требует PowerShell 7. ArtifactDir содержит только update.json, полный ZIP и две
прямые дельты. HarnessClasspath содержит 1-4 готовых каталога/JAR; каждый файл
закрепляется SHA-256. OutputFile новый: прямой Temp/UUID либо существующий
каталог под dist/target. Возвращает объект path,sha256; sidecar не создаётся.
Проверка контейнеров здесь размер/digest; их семантику проверяют core/server.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ArtifactDir,
    [Parameter(Mandatory)][ValidateCount(1,4)][string[]]$HarnessClasspath,
    [Parameter(Mandatory)][string]$ExpectedManifestSha256,
    [Parameter(Mandatory)][string]$OutputFile
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3

# Импортируются только определения frozen runner, никогда его исполняемое тело.
function Import-LifecycleConfigContracts([string]$ScriptsRoot) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot 'Test-NativeUpdateLifecycle.ps1'),[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'LIFECYCLE_IMPORT_PARSE'}
    foreach ($name in 'Import-NativeDependencies','Assert-NativeAbsolute','Assert-NativeLifecycleConfig','Get-NativeUtcTicks') {
        $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($definitions.Count -ne 1) {throw 'LIFECYCLE_IMPORT_CONTRACT'}
        $text=$definitions[0].Extent.Text -replace ('^function '+[regex]::Escape($name)),('function global:'+$name)
        . ([scriptblock]::Create($text))
    }
    Import-NativeDependencies $ScriptsRoot
}

# JSON не допускает повторных ключей, exponent/fraction и неограниченной вложенности.
function Assert-LifecycleJsonNode($Node) {
    switch ($Node.ValueKind.ToString()) {
        'Object' {
            $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            foreach ($property in $Node.EnumerateObject()) {
                if (-not $seen.Add($property.Name)) {throw 'LIFECYCLE_JSON_DUPLICATE'}
                Assert-LifecycleJsonNode $property.Value
            }
        }
        'Array' {foreach ($item in $Node.EnumerateArray()) {Assert-LifecycleJsonNode $item}}
        'Number' {if ($Node.GetRawText() -cnotmatch '^-?(?:0|[1-9][0-9]*)$') {throw 'LIFECYCLE_JSON_INTEGER'}}
        'String' {[void]$Node.GetString()}
        'True' {}
        'False' {}
        default {throw 'LIFECYCLE_JSON_VALUE'}
    }
}

# Формат schema2 и canonical tree проверяются существующим cold inventory contract.
function Read-LifecycleManifest([string]$Directory,[string]$ExpectedHash) {
    if ($ExpectedHash -cnotmatch '^[0-9a-f]{64}$') {throw 'LIFECYCLE_MANIFEST_PIN'}
    $path=Join-Path $Directory 'update.json';$item=Get-Item -LiteralPath $path -Force
    if ($item.PSIsContainer -or $item.Length -gt 8388608 -or (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $ExpectedHash) {throw 'LIFECYCLE_MANIFEST_PIN'}
    $utf8=[Text.UTF8Encoding]::new($false,$true)
    $bytes=[IO.File]::ReadAllBytes($path)
    if ($bytes.Length -gt 8388608 -or [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $ExpectedHash) {throw 'LIFECYCLE_MANIFEST_PIN'}
    $text=$utf8.GetString($bytes)
    $options=[Text.Json.JsonDocumentOptions]::new();$options.MaxDepth=64
    $document=[Text.Json.JsonDocument]::Parse($text,$options)
    try {
        Assert-LifecycleJsonNode $document.RootElement
        $m=ConvertFrom-ColdReceiptJson $text
        # Старый PowerShell конвертирует даты; wire поле сохраняется строковым.
        $m.publishedAtUtc=$document.RootElement.GetProperty('publishedAtUtc').GetString()
    } finally {$document.Dispose()}
    Assert-ColdKeys $m @('schemaVersion','releaseNumber','commitSha','version','publishedAtUtc','assetName','sizeBytes','sha256','treeSha256','files','deltaPatches')
    if (-not (Test-ColdInteger $m.schemaVersion 2) -or $m.schemaVersion -ne 2 -or
        -not (Test-ColdInteger $m.releaseNumber 1) -or $m.releaseNumber -gt [int]::MaxValue -or
        $m.commitSha -isnot [string] -or $m.commitSha -cnotmatch '^[0-9a-f]{40}$' -or
        $m.version -isnot [string] -or [string]::IsNullOrWhiteSpace($m.version) -or
        $m.assetName -cne 'CashPrediction-portable.zip' -or $m.files -isnot [array] -or $m.deltaPatches -isnot [array] -or
        $m.deltaPatches.Count -ne 2) {throw 'LIFECYCLE_MANIFEST_SCHEMA'}
    [void](Get-NativeUtcTicks $m.publishedAtUtc)
    Assert-ColdImageInventory $m.files $m.treeSha256
    # Дополнительные directory/file коллизии core не должны теряться при PS разборе.
    $nodes=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    $files=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($file in $m.files) {[void]$files.Add($file.path)}
    foreach ($file in $m.files) {
        foreach ($segment in $file.path.Split('/')) {
            if ($segment -match '^(?i:CLOCK\$|CONIN\$|CONOUT\$)(?:\.|$)' -or [Text.Encoding]::UTF8.GetByteCount($file.path) -gt 65535) {throw 'LIFECYCLE_TREE_PATH'}
            foreach ($character in $segment.ToCharArray()) {if ([char]::IsControl($character)) {throw 'LIFECYCLE_TREE_PATH'}}
        }
        $node=$file.path
        while ($node) {
            if ($nodes.ContainsKey($node) -and $nodes[$node] -cne $node) {throw 'LIFECYCLE_TREE_COLLISION'}
            $nodes[$node]=$node
            $slash=$node.LastIndexOf('/');if ($slash -lt 0) {break}
            $node=$node.Substring(0,$slash)
            if ($files.Contains($node)) {throw 'LIFECYCLE_TREE_COLLISION'}
        }
    }
    $releases=[Collections.Generic.HashSet[long]]::new();$commits=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    [void]$names.Add('update.json');[void]$names.Add($m.assetName)
    foreach ($delta in $m.deltaPatches) {
        Assert-ColdKeys $delta @('algorithm','algorithmVersion','baseReleaseNumber','baseCommitSha','baseTreeSha256','assetName','sizeBytes','sha256')
        if ($delta.algorithm -cne 'cashprediction-tree-delta' -or -not (Test-ColdInteger $delta.algorithmVersion 1) -or $delta.algorithmVersion -ne 1 -or
            -not (Test-ColdInteger $delta.baseReleaseNumber 1) -or $delta.baseReleaseNumber -ge $m.releaseNumber -or
            $delta.baseCommitSha -isnot [string] -or $delta.baseCommitSha -cnotmatch '^[0-9a-f]{40}$' -or $delta.baseCommitSha -ceq $m.commitSha -or
            $delta.baseTreeSha256 -isnot [string] -or $delta.baseTreeSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            -not $releases.Add($delta.baseReleaseNumber) -or -not $commits.Add($delta.baseCommitSha) -or
            ($delta.assetName -cne 'CashPrediction.cpdelta' -and $delta.assetName -cne ('CashPrediction.from-'+$delta.baseReleaseNumber+'.cpdelta')) -or
            -not $names.Add($delta.assetName)) {throw 'LIFECYCLE_DIRECT_DELTAS'}
    }
    foreach ($asset in @($m)+@($m.deltaPatches)) {
        if (-not (Test-ColdInteger $asset.sizeBytes 1) -or $asset.sizeBytes -gt 536870912 -or
            $asset.sha256 -isnot [string] -or $asset.sha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'LIFECYCLE_PAYLOAD_PIN'}
        $payload=Get-Item -LiteralPath (Join-Path $Directory $asset.assetName) -Force
        if ($payload.PSIsContainer -or $payload.Length -ne $asset.sizeBytes -or
            (Get-FileHash -LiteralPath $payload.FullName).Hash.ToLowerInvariant() -cne $asset.sha256) {throw 'LIFECYCLE_PAYLOAD_PIN'}
    }
    $entries=@(Get-ChildItem -LiteralPath $Directory -Force)
    if ($entries.Count -ne $names.Count) {throw 'LIFECYCLE_ARTIFACT_SET'}
    foreach ($entry in $entries) {if ($entry.PSIsContainer -or -not $names.Contains($entry.Name)) {throw 'LIFECYCLE_ARTIFACT_SET'}}
    return $m
}

# Выход не пересекается с входами; Temp допускает только ещё не созданный прямой UUID.
function Assert-LifecycleOutput([string]$Path,[string]$Project,[string[]]$Inputs) {
    $resolved=Assert-NativeAbsolute $Path
    if (Test-Path -LiteralPath $resolved) {throw 'LIFECYCLE_OUTPUT_EXISTS'}
    if ([IO.Path]::GetFileName($resolved) -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*\.json$') {throw 'LIFECYCLE_OUTPUT_NAME'}
    foreach ($inputPath in $Inputs) {
        if ((Test-PortablePathContains $inputPath $resolved) -or (Test-PortablePathContains $resolved $inputPath)) {throw 'LIFECYCLE_OUTPUT_OVERLAP'}
    }
    $parent=[IO.Path]::GetDirectoryName($resolved)
    $temp=Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())
    $target=Resolve-PortableSafetyPath (Join-Path $Project 'dist/target')
    $newTemp=([IO.Path]::GetDirectoryName($parent).Equals($temp,[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($parent) -cmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$')
    if ($newTemp) {if (Test-Path -LiteralPath $parent) {throw 'LIFECYCLE_TEMP_EXISTS'}}
    elseif (-not (Test-PortablePathContains $target $parent) -or -not (Test-Path -LiteralPath $parent -PathType Container)) {throw 'LIFECYCLE_OUTPUT_SCOPE'}
    return [pscustomobject]@{path=$resolved;parent=$parent;newTemp=$newTemp}
}

# Список pins полный, без выбора файлов по расширению или имени.
function New-LifecyclePinnedConfig([string]$Directory,[string[]]$Classpath,[string]$ExpectedHash) {
    if ($Classpath.Count -lt 1 -or $Classpath.Count -gt 4) {throw 'LIFECYCLE_CLASSPATH_COUNT'}
    [void](Assert-NativeAbsolute $Directory);Assert-PortableTreeHasNoLinks $Directory
    [void](Read-LifecycleManifest $Directory $ExpectedHash)
    $entries=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $pins=[Collections.Generic.List[object]]::new()
    foreach ($entry in $Classpath) {
        [void](Assert-NativeAbsolute $entry)
        if (-not $entries.Add($entry)) {throw 'LIFECYCLE_CLASSPATH_DUPLICATE'}
        Assert-PortableTreeHasNoLinks $entry
        if (Test-Path -LiteralPath $entry -PathType Container) {$items=@(Get-ChildItem -LiteralPath $entry -Recurse -File -Force)}
        else {if ([IO.Path]::GetExtension($entry) -cne '.jar') {throw 'NATIVE_CLASSPATH'};$items=@(Get-Item -LiteralPath $entry)}
        foreach ($item in $items) {
            if (-not $seen.Add($item.FullName) -or $seen.Count -gt 20000 -or $item.Length -gt 536870912) {throw 'LIFECYCLE_HARNESS_SET'}
            $pins.Add([pscustomobject][ordered]@{path=$item.FullName;sha256=(Get-FileHash -LiteralPath $item.FullName).Hash.ToLowerInvariant()})
        }
    }
    $config=[pscustomobject][ordered]@{schemaVersion=1;artifactDir=$Directory;manifestSha256=$ExpectedHash;
        harnessClasspath=($Classpath -join ';');harnessFiles=$pins.ToArray()}
    Assert-NativeLifecycleConfig $config
    return $config
}

# Atomic rename без overwrite; ошибка сохраняется, ни старый output, ни входы не удаляются.
function Publish-LifecycleConfig($Config,$Destination) {
    Assert-NativeLifecycleConfig $Config
    [void](Read-LifecycleManifest $Config.artifactDir $Config.manifestSha256)
    if ($Destination.newTemp) {
        if (Test-Path -LiteralPath $Destination.parent) {throw 'LIFECYCLE_TEMP_EXISTS'}
        $null=New-Item -ItemType Directory -Path $Destination.parent -ErrorAction Stop
    }
    [void](Resolve-PortableSafetyPath $Destination.parent)
    $temporary=Join-Path $Destination.parent ('.lifecycle-'+[guid]::NewGuid().ToString()+'.tmp')
    $bytes=[Text.UTF8Encoding]::new($false,$true).GetBytes((ConvertTo-Json -InputObject $Config -Depth 64 -Compress))
    $stream=[IO.FileStream]::new($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
    # File.Move без третьего параметра никогда не заменяет существующий файл.
    [IO.File]::Move($temporary,$Destination.path)
    return [pscustomobject][ordered]@{path=$Destination.path;sha256=(Get-FileHash -LiteralPath $Destination.path).Hash.ToLowerInvariant()}
}

if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'LIFECYCLE_WINDOWS_POWERSHELL7'}
Import-LifecycleConfigContracts $PSScriptRoot
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$destination=Assert-LifecycleOutput $OutputFile $project (@($ArtifactDir)+@($HarnessClasspath))
$config=New-LifecyclePinnedConfig $ArtifactDir $HarnessClasspath $ExpectedManifestSha256
Publish-LifecycleConfig $config $destination
