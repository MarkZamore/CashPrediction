<#
.SYNOPSIS
Проверяет узкие отрицательные границы source-поставки без Maven и архиватора.
.DESCRIPTION
Создаёт только собственный Temp-стенд. Настоящий Pack-Source запускается с StageOnly;
настоящий final-listing проверяется через AST без запуска final delivery.
Отсутствующая защита означает FAILED, а не успешную характеристику дефекта.
Синтетические исходники не доказывают сборку или конечную приёмку.
#>
#requires -Version 7.0
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pack = Join-Path $PSScriptRoot 'Pack-Source.ps1'
$final = Join-Path $PSScriptRoot 'Test-FinalDelivery.ps1'
$pins = @{}
$definitions = @{}
foreach ($path in @($pack, $final)) {
    $pins[$path] = (Get-FileHash -LiteralPath $path).Hash
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'BOUNDARY_PARSE' }
    foreach ($node in $ast.FindAll({ param($n)
        $n -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $false)) {
        $definitions[$node.Name] = $node.Extent.Text
    }
}
foreach ($name in @('Test-WithinPath', 'Test-ExcludedDirectory', 'Test-IncludedFile',
        'Read-SourcePom', 'Assert-NoSourceSecret', 'Assert-DeliveredSource',
        'Assert-FinalArchivePath', 'Read-FinalArchiveListing')) {
    if (-not $definitions.ContainsKey($name)) { throw "BOUNDARY_FUNCTION: $name" }
    . ([scriptblock]::Create($definitions[$name]))
}
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
$owned = Join-Path $tempBase ('cp-source-boundary-' + [guid]::NewGuid().ToString('N'))
$fixture = Join-Path $owned 'source'
$results = [Collections.Generic.List[object]]::new()

# Ограничивает записи собственным UUID-корнем и исключает linked ancestors.
function Assert-Owned([string] $Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if ([IO.Path]::GetDirectoryName($owned) -cne $tempBase -or
        [IO.Path]::GetFileName($owned) -notmatch '^cp-source-boundary-[0-9a-f]{32}$' -or
        -not ($absolute -ceq $owned -or $absolute.StartsWith($owned + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase))) { throw 'BOUNDARY_SCOPE' }
    for ($p = $absolute; $p; $p = [IO.Path]::GetDirectoryName($p)) {
        if ((Test-Path -LiteralPath $p) -and
            ((Get-Item -LiteralPath $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'BOUNDARY_LINK'
        }
    }
}

# Создаёт только новый файл стенда, не перезаписывая существующие данные.
function Add-Owned([string] $Path, [string] $Text = 'synthetic fixture') {
    Assert-Owned $Path
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try { $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text); $stream.Write($bytes, 0, $bytes.Length) }
    finally { $stream.Dispose() }
}

# Регистрирует конкретный наблюдаемый результат; пропуск защиты остаётся FAILED.
function Record-Boundary([string] $Name, [bool] $Passed, [string] $Observation) {
    $results.Add([pscustomobject]@{name = $Name; status = $(if ($Passed) { 'PASS' } else { 'FAILED' }); observation = $Observation})
    Write-Host "$($results[-1].status): $Name : $Observation"
}

Assert-Owned $owned
$null = [IO.Directory]::CreateDirectory($owned)
$modulePom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>'
$rootPom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>core</module><module>update-tool</module><module>ui-fx</module><module>ui-swing</module><module>web</module></modules><profiles><profile><modules><module>dist</module><module>ui-parity</module></modules></profile></profiles></project>'
Add-Owned (Join-Path $fixture 'pom.xml') $rootPom
foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web', 'dist', 'ui-parity')) {
    Add-Owned (Join-Path $fixture "$module/pom.xml") $modulePom
    if ($module -notin @('dist', 'ui-parity')) {
        Add-Owned (Join-Path $fixture "$module/src/main/java/Example.java") 'class Example {}'
    }
}
foreach ($relative in @('docs/design/architecture.md', 'docs/design/techstack.md',
        'docs/design/edge-cases.md', 'docs/design/db-schema.md', 'docs/design/linx.md', 'docs/design/ui-kit.md',
        'dist/scripts/Set-LauncherUtf8.ps1',
        'dist/scripts/Test-Icon-Source.ps1', 'dist/scripts/Test-IconPayloadIntegrity.ps1',
        'dist/scripts/Normalize-AppModules.ps1', 'dist/icons/make-icon.ps1',
        'dist/launchers/swing.properties', 'dist/launchers/web.properties',
        '.github/scripts/Test-Portable.ps1', '.github/scripts/GhRetry.ps1',
        'core/src/main/java/ru/cashprediction/core/update/Example.java',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.png',
        'core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md',
        'LICENSE.md', 'LICENSE.txt', 'LICENCE', 'COPYING.md', 'NOTICE',
        'core/src/main/resources/LICENSE.md', 'core/src/test/resources/NOTICE.txt',
        'core/src/test/resources/session.plan.md', 'core/src/test/resources/agent-response.json')) {
    Add-Owned (Join-Path $fixture $relative)
}
$baseline = Join-Path $owned 'baseline-stage'
$null = & $pack -SourceRoot $fixture -StageDirectory $baseline -StageOnly
Record-Boundary 'baseline-stage' $true 'Actual StageOnly accepted complete synthetic source; no build.'
foreach ($relative in @('docs/design/architecture.md', 'docs/design/techstack.md',
        'docs/design/edge-cases.md', 'docs/design/db-schema.md', 'docs/design/linx.md', 'docs/design/ui-kit.md',
        'LICENSE.md', 'LICENSE.txt', 'LICENCE',
        'COPYING.md', 'NOTICE', 'core/src/main/resources/LICENSE.md',
        'core/src/test/resources/NOTICE.txt', 'core/src/test/resources/session.plan.md',
        'core/src/test/resources/agent-response.json', 'dist/scripts/Test-IconPayloadIntegrity.ps1',
        'dist/scripts/Normalize-AppModules.ps1')) {
    Record-Boundary "preserved:$relative" ((Get-FileHash -LiteralPath (Join-Path $fixture $relative)).Hash -ceq
        (Get-FileHash -LiteralPath (Join-Path $baseline $relative)).Hash) 'Actual staged SHA equals source SHA.'
}
Record-Boundary 'source-pom-unchanged' ([IO.File]::ReadAllText((Join-Path $fixture 'pom.xml')) -ceq $rootPom) 'Root fixture POM unchanged.'
foreach ($relative in @('docs/design/architecture.md', 'docs/design/techstack.md',
        'docs/design/edge-cases.md', 'docs/design/db-schema.md', 'docs/design/linx.md', 'docs/design/ui-kit.md',
        'dist/scripts/Set-LauncherUtf8.ps1',
        'dist/scripts/Test-IconPayloadIntegrity.ps1', 'dist/scripts/Normalize-AppModules.ps1')) {
    $path = Join-Path $fixture $relative; $saved = Join-Path $owned ([guid]::NewGuid().ToString('N'))
    Assert-Owned $path; Assert-Owned $saved
    Move-Item -LiteralPath $path -Destination $saved
    $errorText = $null
    try {
        try { Assert-DeliveredSource $fixture } catch { $errorText = $_.Exception.Message }
        $observation = if ($errorText) { $errorText } else { 'Composition accepted missing direct build dependency.' }
        Record-Boundary "missing:$relative" ([bool]($errorText -and $errorText.Contains($relative))) $observation
        $missingStage = Join-Path $owned ('missing-stage-' + [guid]::NewGuid().ToString('N'))
        $stageError = $null
        try { $null = & $pack -SourceRoot $fixture -StageDirectory $missingStage -StageOnly }
        catch { $stageError = $_.Exception.Message }
        Record-Boundary "stage-missing:$relative" ([bool]($stageError -and $stageError.Contains($relative))) "$stageError"
        Record-Boundary "missing-stage-cleanup:$relative" (-not (Test-Path -LiteralPath $missingStage)) 'Failed staging must be absent.'
    } finally { Move-Item -LiteralPath $saved -Destination $path }
}
$paths = @('core/src/test/resources/.agent.json', 'core/src/test/resources/.agents.json',
    'core/src/main/resources/.cursor.json', 'core/src/test/resources/.windsurfrules',
    'docs/design/stages.md', 'docs/ui-spec.md', 'core/src/test/resources/AGENTS.override.md',
    'dist/scripts/.AGENT.JSON', 'web/src/main/resources/.agents.settings.json',
    'ui-swing/src/test/resources/.windsurfrules.local', 'core/src/test/resources/.agent/config.json')
foreach ($relative in $paths) { Add-Owned (Join-Path $fixture $relative) '{"fixture":"agent-or-developer-metadata"}' }
$stage = Join-Path $owned 'metadata-stage'
$null = & $pack -SourceRoot $fixture -StageDirectory $stage -StageOnly
foreach ($relative in $paths) {
    $leaked = Test-Path -LiteralPath (Join-Path $stage $relative)
    Record-Boundary "stage-excludes:$relative" (-not $leaked) "Actual staged file present=$leaked"
    $listingError = $null
    try { $null = Read-FinalArchiveListing @('Type = 7z', '----------', "Path = $relative", 'Size = 1', '') }
    catch { $listingError = $_.Exception.Message }
    $observation = if ($listingError) { $listingError } else { 'Actual final-listing validator accepted metadata path.' }
    Record-Boundary "listing-excludes:$relative" ([bool]($listingError -and $listingError.StartsWith('FINAL_SOURCE_EXCLUDED_'))) $observation
}
foreach ($path in $pins.Keys) {
    if ((Get-FileHash -LiteralPath $path).Hash -cne $pins[$path]) { throw 'BOUNDARY_INPUT_CHANGED' }
}
$receipt = [ordered]@{scope = 'synthetic StageOnly and actual AST validators; no archives/build/native';
    status = $(if (@($results | Where-Object status -eq 'FAILED').Count) { 'FAILED' } else { 'PASS' });
    policySha256 = $pins[$pack]; finalSha256 = $pins[$final]; results = @($results); retainedRoot = $owned}
Add-Owned (Join-Path $owned 'receipt.json') (ConvertTo-Json -InputObject $receipt -Depth 8)
Write-Host "Receipt: $(Join-Path $owned 'receipt.json')"
if ($receipt.status -ne 'PASS') { throw 'SOURCE_BOUNDARY_REGRESSIONS_FAILED' }
