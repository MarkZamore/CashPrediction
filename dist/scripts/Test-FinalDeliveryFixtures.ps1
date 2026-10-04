<#
.SYNOPSIS
Проверяет FinalDelivery на изолированных fixtures с mock 7-Zip и mock Maven.
.DESCRIPTION
Не создаёт финальную доставку, не запускает Maven, упаковщик, exe или GUI.
Синтетический контейнер содержит свои исходники; mock извлекает их из контейнера,
а не копирует repository. Все fixtures и receipts удаляются из собственного Temp-корня.
#>
#requires -Version 7.0
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$validator = Join-Path $PSScriptRoot 'Test-FinalDelivery.ps1'
$testBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
$testRoot = Join-Path $testBase ('cp-final-delivery-fixtures-' + [guid]::NewGuid().ToString('N'))
$checks = 0

# Защищает каждую запись и удаление собственным абсолютным корнем и запретом ссылок.
function Assert-FixturePath([string] $Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (-not ($absolute -ceq $testRoot -or $absolute.StartsWith($testRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) -or
        [IO.Path]::GetDirectoryName($testRoot) -cne $testBase -or
        [IO.Path]::GetFileName($testRoot) -notmatch '^cp-final-delivery-fixtures-[0-9a-f]{32}$') { throw 'FIXTURE_SCOPE' }
    for ($ancestor = $absolute; $ancestor; $ancestor = [IO.Path]::GetDirectoryName($ancestor)) {
        if ((Test-Path -LiteralPath $ancestor) -and
            ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'FIXTURE_LINK' }
    }
}

# Создаёт новый синтетический файл без перезаписи чужих результатов.
function Write-Fixture([string] $Path, [string] $Text) {
    Assert-FixturePath $Path
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try { $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text); $stream.Write($bytes, 0, $bytes.Length) }
    finally { $stream.Dispose() }
}

# Требует конкретного результата, учитывая каждую отдельную проверку.
function Assert-Fixture([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }; $script:checks++
}

# Создаёт новый локальный вариант контейнера и portable fixtures с dummy файлами.
function New-Case([string] $Name, [string] $Mode = '', [hashtable] $Files = $sourceFiles) {
    $root = Join-Path $testRoot $Name
    Assert-FixturePath $root
    $null = [IO.Directory]::CreateDirectory($root)
    foreach ($path in @('CashPrediction.exe', 'CashPrediction-Swing.exe', 'CashPrediction-Web.exe', 'app/app.jar', 'runtime/lib/modules')) {
        $destination = Join-Path $root ('CashPrediction/' + $path)
        if ($path.EndsWith('.exe')) {
            Assert-FixturePath $destination
            $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
            # Минимальная синтетическая PE сигнатура, не исполняемый продукт.
            $bytes = [byte[]]::new(128); $bytes[0] = 0x4d; $bytes[1] = 0x5a; $bytes[60] = 64
            $bytes[64] = 0x50; $bytes[65] = 0x45; $bytes[68] = 0x64; $bytes[69] = 0x86
            [IO.File]::WriteAllBytes($destination, $bytes)
        } else { Write-Fixture $destination 'synthetic-portable' }
    }
    $archive = Join-Path $root 'CashPrediction-source.7z'
    # Это намеренно mock-контейнер с 7z magic, не настоящий архив или релиз.
    $json = ConvertTo-Json -InputObject @{mode = $Mode; files = $Files} -Depth 8
    Assert-FixturePath $archive
    [IO.File]::WriteAllBytes($archive, ([byte[]]@(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) + [Text.Encoding]::UTF8.GetBytes($json)))
    return $root
}

# Запускает production entry point с явными mock-инструментами и проверяет receipt.
function Invoke-Case([string] $Root, [string] $Expected = '', [switch] $Build, [switch] $FailedBuild, [switch] $ChangedBuild) {
    $evidence = Join-Path $testRoot ('evidence-' + [guid]::NewGuid().ToString('N'))
    $options = @{DeliveryRoot = $Root; EvidenceRoot = $evidence; SevenZipPath = $mockZip}
    if ($Build) { $options.VerifyBuild = $true; $options.MavenPath = $(if ($FailedBuild) { $mockFailMaven } elseif ($ChangedBuild) { $mockChangeMaven } else { $mockMaven }) }
    $failure = $null
    try { $result = & $validator @options } catch { $failure = $_.Exception.Message }
    $receipt = Get-Content -LiteralPath (Join-Path $evidence 'receipt.json') -Raw | ConvertFrom-Json
    if ($Expected) {
        Assert-Fixture ($null -ne $failure -and $failure -match $Expected) "Нет ожидаемого отказа $Expected : $failure"
        Assert-Fixture ($receipt.status -ceq 'FAILED') 'Отказ получил успешный receipt.'
    } else {
        Assert-Fixture ($null -eq $failure) "Неожиданный отказ: $failure"
        Assert-Fixture ($receipt.status -ceq $(if ($Build) { 'VERIFIED_COMPOSITION_AND_BUILD' } else { 'VERIFIED_COMPOSITION' })) 'Неверный статус.'
        Assert-Fixture (-not $receipt.fullAcceptance -and -not $result.fullAcceptance) 'Fixture не может означать полную приёмку.'
    }
    Assert-Fixture ($receipt.cleanup -in @('NOT_CREATED', 'REMOVED')) 'Временное дерево осталось после проверки.'
    foreach ($command in $receipt.commands) {
        Assert-Fixture ($command.logSha256 -ceq (Get-FileHash -LiteralPath (Join-Path $evidence $command.log)).Hash) 'Неверный SHA журнала.'
        if ($command.arguments[0] -ceq 'x') {
            $extracted = @($command.arguments | Where-Object { $_ -like '-o*' })[0].Substring(2)
            Assert-Fixture (-not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($extracted)))) 'Owned Temp остался после cleanup.'
        }
    }
    return [pscustomobject]@{receipt = $receipt; evidence = $evidence}
}

# Берёт обязательные имена fixture из AST реального контракта, без копии allowlist упаковщика.
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Pack-Source.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'FIXTURE_POLICY_PARSE' }
$function = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Assert-DeliveredSource' }, $false))
$assignments = @($function[0].FindAll({ param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$required' }, $true))
if ($function.Count -ne 1 -or $assignments.Count -ne 1) { throw 'FIXTURE_REQUIRED_AST' }
. ([scriptblock]::Create($assignments[0].Extent.Text))
$sourceFiles = @{}
foreach ($relative in $required) { $sourceFiles[$relative] = 'archive-only-fixture' }
$modulePom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>'
foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web', 'ui-parity', 'dist')) { $sourceFiles["$module/pom.xml"] = $modulePom }
foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web')) { $sourceFiles["$module/src/main/java/Example.java"] = 'class Example {}' }
$sourceFiles['core/src/main/java/ru/cashprediction/core/update/Example.java'] = 'class Example {}'
$sourceFiles['core/src/test/resources/fixture [1].md'] = 'archive-only-test-resource'
$sourceFiles['pom.xml'] = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>core</module><module>update-tool</module><module>ui-fx</module><module>ui-swing</module><module>web</module></modules><profiles><profile><id>dist</id><modules><module>dist</module></modules></profile><profile><id>ui-tests</id><modules><module>ui-parity</module></modules></profile></profiles></project>'

Assert-FixturePath $testRoot
$null = New-Item -ItemType Directory -Path $testRoot
try {
    $mockZip = Join-Path $testRoot 'mock-sevenzip.ps1'
    $mockMaven = Join-Path $testRoot 'mock-maven.ps1'
    $mockFailMaven = Join-Path $testRoot 'mock-failed-maven.ps1'
    $mockChangeMaven = Join-Path $testRoot 'mock-changed-source-maven.ps1'
    Write-Fixture $mockZip @'
# Читает только свой синтетический контейнер; никогда не читает repository.
$ErrorActionPreference = 'Stop'
$archive = $args[-1]
if (-not [IO.Path]::GetFullPath($archive).StartsWith($PSScriptRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'MOCK_ARCHIVE_SCOPE' }
$bytes = [IO.File]::ReadAllBytes($archive)
$data = [Text.Encoding]::UTF8.GetString($bytes, 6, $bytes.Length - 6) | ConvertFrom-Json -AsHashtable
if ($args[0] -eq 'l') {
    'Type = ' + $(if ($data.mode -eq 'zip') { 'zip' } else { '7z' }); '----------'
    foreach ($path in $data.files.Keys) {
        "Path = $path"; 'Size = ' + [Text.Encoding]::UTF8.GetByteCount($data.files[$path]); 'Attributes = A'; ''
    }
    if ($data.mode -eq 'link') { 'Path = dist/scripts/link.ps1'; 'Size = 1'; 'Symbolic Link = /outside'; '' }
    if ($data.mode -eq 'hardlink') { 'Path = dist/scripts/link.ps1'; 'Size = 1'; 'Hard Link = ../outside'; '' }
    if ($data.mode -eq 'collision') { 'Path = POM.XML'; 'Size = 1'; '' }
    if ($data.mode -eq 'duplicate-field') { 'Path = dist/scripts/test.ps1'; 'Path = dist/scripts/other.ps1'; 'Size = 1'; '' }
    if ($data.mode -eq 'file-parent') { 'Path = core'; 'Size = 1'; '' }
    exit 0
}
if ($args[0] -eq 't') { if ($data.mode -eq 'bad-test') { exit 2 }; 'mock container test'; exit 0 }
if ($args[0] -ne 'x') { exit 3 }
$output = [IO.Path]::GetFullPath(@($args | Where-Object { $_ -like '-o*' })[0].Substring(2))
$temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
if ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($output)) -cne $temp -or
    [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($output)) -notmatch '^cp-final-delivery-[0-9a-f]{32}$' -or
    [IO.Path]::GetFileName($output) -cne 'source' -or (Test-Path -LiteralPath $output)) { throw 'MOCK_EXTRACT_SCOPE' }
$null = [IO.Directory]::CreateDirectory($output)
foreach ($path in $data.files.Keys) {
    $destination = [IO.Path]::GetFullPath((Join-Path $output $path))
    if (-not $destination.StartsWith($output + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'MOCK_ESCAPE' }
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
    $text = $data.files[$path]
    if ($data.mode -eq 'mismatch' -and $path -eq 'pom.xml') { $text += 'changed' }
    [IO.File]::WriteAllText($destination, $text, [Text.UTF8Encoding]::new($false))
}
if ($data.mode -eq 'extra') { [IO.File]::WriteAllText((Join-Path $output 'extra.json'), 'extra') }
if ($data.mode -eq 'portable-change') {
    [IO.File]::WriteAllText((Join-Path ([IO.Path]::GetDirectoryName($archive)) 'CashPrediction/runtime/lib/modules'), 'changed fixture')
}
'mock extraction from container'; exit 0
'@
    Write-Fixture $mockMaven @'
# Требует полный install именно в свежем извлечении и сохраняет наблюдённый вызов.
$location = (Get-Location).Path
$temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
if (($args -join ' ') -cne '-B install' -or [IO.Path]::GetFileName($location) -cne 'source' -or
    [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($location)) -cne $temp -or
    [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($location)) -notmatch '^cp-final-delivery-[0-9a-f]{32}$') { throw 'MOCK_BUILD_SCOPE' }
if (-not (Test-Path -LiteralPath (Join-Path $location 'pom.xml'))) { throw 'MOCK_BUILD_SOURCE' }
'mock full mvn -B install'; exit 0
'@
    Write-Fixture $mockFailMaven 'exit 7'
    $mavenText = [IO.File]::ReadAllText($mockMaven)
    Write-Fixture $mockChangeMaven ($mavenText.Replace("'mock full mvn -B install'; exit 0",
        "[IO.File]::WriteAllText((Join-Path `$location 'pom.xml'), 'modified fixture'); exit 0"))
    $good = New-Case 'source [1] пробелы'
    $result = Invoke-Case $good
    Assert-Fixture ($result.receipt.buildStatus -ceq 'NOT_RUN') 'Build объявлен без запуска.'
    $inventory = Get-Content -LiteralPath (Join-Path $result.evidence 'source-inventory.json') -Raw | ConvertFrom-Json
    $entry = @($inventory | Where-Object { $_.path -ceq 'docs/design/architecture.md' })[0]
    $expectedHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes('archive-only-fixture')))
    Assert-Fixture ($entry.sha256 -ceq $expectedHash) 'Доказательство взято не из архива.'
    Assert-Fixture ($result.receipt.archive.sha256 -ceq (Get-FileHash -LiteralPath (Join-Path $good 'CashPrediction-source.7z')).Hash) 'Неверный SHA архива.'
    Assert-Fixture ($result.receipt.source.inventorySha256 -ceq (Get-FileHash -LiteralPath (Join-Path $result.evidence 'source-inventory.json')).Hash) 'Неверный SHA inventory.'
    $built = Invoke-Case $good -Build
    Assert-Fixture ($built.receipt.commands[-1].arguments -join ' ' -ceq '-B install') 'Неверный вызов full install.'
    $null = Invoke-Case $good 'FINAL_TOOL_EXIT.*7' -Build -FailedBuild
    $null = Invoke-Case $good 'FINAL_BUILD_CHANGED_SOURCE' -Build -ChangedBuild
    $empty = Join-Path $testRoot 'missing'; $null = New-Item -ItemType Directory -Path $empty
    $null = Invoke-Case $empty 'FINAL_ARTIFACTS_MISSING'
    foreach ($mode in @('zip', 'link', 'hardlink', 'collision', 'duplicate-field', 'file-parent', 'bad-test', 'mismatch', 'extra', 'portable-change')) {
        $root = New-Case $mode $mode
        $failure = Invoke-Case $root 'FINAL_'
        if ($mode -in @('zip', 'link', 'hardlink', 'collision', 'duplicate-field', 'file-parent')) {
            Assert-Fixture (@($failure.receipt.commands | Where-Object { $_.arguments[0] -ceq 'x' }).Count -eq 0) 'Опасный listing дошёл до extraction.'
        }
    }
    foreach ($path in @('../escaped.ps1', '/absolute.ps1', 'C:/escape.ps1', 'dist/scripts/x:stream.ps1',
            'dist/scripts/CON.ps1', 'dist/scripts/end .ps1.', 'dist//scripts/x.ps1', 'dist/scripts/../x.ps1',
            'AGENTS.md', 'core/src/test/resources/CLAUDE.md', 'docs/FORMAT.md', '.github/workflows/release.yml', 'core/target/X.class')) {
        $files = $sourceFiles.Clone(); $files[$path] = 'forbidden'
        $root = New-Case ('bad-path-' + [guid]::NewGuid().ToString('N')) '' $files
        $failure = Invoke-Case $root 'FINAL_'
        Assert-Fixture (@($failure.receipt.commands | Where-Object { $_.arguments[0] -ceq 'x' }).Count -eq 0) 'Опасный путь дошёл до extraction.'
    }
    $missing = $sourceFiles.Clone(); $missing.Remove('update-tool/src/main/java/Example.java')
    $null = Invoke-Case (New-Case 'missing-source' '' $missing) 'исходники обязательного модуля'
    $missing = $sourceFiles.Clone(); $missing.Remove('docs/design/architecture.md')
    $null = Invoke-Case (New-Case 'missing-architecture' '' $missing) 'Обязательный файл поставки отсутствует'
    $invalid = $sourceFiles.Clone(); $invalid['pom.xml'] = $sourceFiles['pom.xml'].Replace('<module>dist</module>', '<module>../outside</module>')
    $null = Invoke-Case (New-Case 'reactor-escape' '' $invalid) 'Небезопасный путь модуля'
    $secret = $sourceFiles.Clone(); $secret['core/src/test/resources/data.json'] = 'ghp_' + ('A' * 36)
    $null = Invoke-Case (New-Case 'secret-source' '' $secret) 'обнаружен секрет'
    $cash = New-Case 'cashmemory'; Write-Fixture (Join-Path $cash 'CashPrediction/CashMemory/plan.md') 'keep'
    $null = Invoke-Case $cash 'FINAL_PORTABLE_CASHMEMORY'
    $noExe = New-Case 'missing-exe'; $path = Join-Path $noExe 'CashPrediction/CashPrediction-Web.exe'
    Assert-FixturePath $path; Remove-Item -LiteralPath $path
    $null = Invoke-Case $noExe 'FINAL_PORTABLE_EXE'
    $noRuntime = New-Case 'missing-runtime'; $path = Join-Path $noRuntime 'CashPrediction/runtime'
    Assert-FixturePath $path; Remove-Item -LiteralPath $path -Recurse -Force
    $null = Invoke-Case $noRuntime 'FINAL_PORTABLE_TREE'
    $badPe = New-Case 'bad-pe'; $path = Join-Path $badPe 'CashPrediction/CashPrediction.exe'
    Assert-FixturePath $path; [IO.File]::WriteAllText($path, 'not an executable')
    $null = Invoke-Case $badPe 'FINAL_PORTABLE_PE'
    $badMagic = New-Case 'bad-magic'; $path = Join-Path $badMagic 'CashPrediction-source.7z'
    Assert-FixturePath $path; [IO.File]::WriteAllText($path, 'not 7z')
    $null = Invoke-Case $badMagic 'FINAL_ARCHIVE_SIGNATURE'
    $preserved = Join-Path $testRoot 'existing-evidence'; $null = New-Item -ItemType Directory -Path $preserved
    Write-Fixture (Join-Path $preserved 'keep.txt') 'keep'
    $failure = $null
    try { & $validator -DeliveryRoot $good -EvidenceRoot $preserved -SevenZipPath $mockZip | Out-Null } catch { $failure = $_.Exception.Message }
    Assert-Fixture ($failure -match 'FINAL_EVIDENCE_EXISTS') 'Evidence перезаписан.'
    Assert-Fixture ([IO.File]::ReadAllText((Join-Path $preserved 'keep.txt')) -ceq 'keep') 'Чужой output изменён.'
    $failure = $null
    try { & $validator -DeliveryRoot $good -EvidenceRoot (Join-Path $good 'evidence') -SevenZipPath $mockZip | Out-Null } catch { $failure = $_.Exception.Message }
    Assert-Fixture ($failure -match 'FINAL_EVIDENCE_SCOPE') 'Evidence внутри доставки разрешён.'
    $link = Join-Path $good 'CashPrediction/app/link'
    $null = New-Item -ItemType Junction -Path $link -Target $good
    try { $null = Invoke-Case $good 'FINAL_LINKED_PATH' } finally { Remove-Item -LiteralPath $link -Force }
    Write-Host "PASS: $checks focused checks; mock container/tools only; no final artifacts, Maven or GUI."
} finally {
    Assert-FixturePath $testRoot
    # Проверяет все дочерние записи перед рекурсивным удалением точного owned root.
    $pending = [Collections.Generic.Stack[string]]::new(); $pending.Push($testRoot)
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Pop() -Force) { Assert-FixturePath $item.FullName; if ($item.PSIsContainer) { $pending.Push($item.FullName) } }
    }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
