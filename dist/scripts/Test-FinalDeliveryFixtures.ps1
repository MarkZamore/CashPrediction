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
param(
    [string] $IconSourceDirectory = (Join-Path $PSScriptRoot '../../core/src/main/resources/ru/cashprediction/core/ui/icons'),
    [string] $ReceiptPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$validator = Join-Path $PSScriptRoot 'Test-FinalDelivery.ps1'
# Быстрые guard regressions входят в существующий CI entry без повторения архивных fixtures.
& (Join-Path $PSScriptRoot 'Test-FinalCandidatePinsFixtures.ps1') | Out-Host
$testBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
$testRoot = Join-Path $testBase ('cp-final-delivery-fixtures-' + [guid]::NewGuid().ToString('N'))
$checks = 0
$cases = [Collections.Generic.List[object]]::new()
if ($ReceiptPath) {
    $ReceiptPath = [IO.Path]::GetFullPath($ReceiptPath)
    $parent = [IO.Path]::GetDirectoryName($ReceiptPath)
    if (-not $ReceiptPath.StartsWith($testBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $parent -PathType Container) -or (Test-Path -LiteralPath $ReceiptPath)) { throw 'FIXTURE_RECEIPT_SCOPE' }
    for ($ancestor = $parent; $ancestor; $ancestor = [IO.Path]::GetDirectoryName($ancestor)) {
        if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'FIXTURE_RECEIPT_LINK' }
    }
}

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
    foreach ($path in @('CashPrediction.exe', 'CashPrediction-Swing.exe', 'CashPrediction-Web.exe', 'app/app.jar', 'runtime/bin/java.exe', 'runtime/lib/modules')) {
        $destination = Join-Path $root ('CashPrediction/' + $path)
        if ($path.EndsWith('.exe')) {
            Assert-FixturePath $destination
            $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
            # Минимальная синтетическая PE сигнатура, не исполняемый продукт.
            $bytes = [byte[]]::new(128); $bytes[0] = 0x4d; $bytes[1] = 0x5a; $bytes[60] = 64
            $bytes[64] = 0x50; $bytes[65] = 0x45; $bytes[68] = 0x64; $bytes[69] = 0x86
            [IO.File]::WriteAllBytes($destination, $bytes)
        } elseif ($path -eq 'runtime/lib/modules') {
            # Только синтетическая bounded JImage структура; не runnable runtime и не native PASS.
            Assert-FixturePath $destination
            $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
            $bytes = [byte[]]::new(39)
            foreach ($entry in @(@(0,3405699802L),@(4,65536),@(12,1),@(16,1),@(20,1),@(24,1))) {
                [BitConverter]::GetBytes([uint32]$entry[1]).CopyTo($bytes,[int]$entry[0])
            }
            [IO.File]::WriteAllBytes($destination,$bytes)
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
    $logs = @{}
    foreach ($command in $receipt.commands) { $logs[$command.log] = [IO.File]::ReadAllText((Join-Path $evidence $command.log)) }
    $cases.Add([pscustomobject]@{name=[IO.Path]::GetFileName($Root); expected=$Expected;
        observed=$failure; receipt=$receipt; logs=$logs; mocksOnly=$true})
    Assert-Fixture (-not $receipt.fullAcceptance) 'Даже отказ не может получить fullAcceptance.'
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
foreach ($name in @('application.png','application.ico')) {
    $sourceFiles["core/src/main/resources/ru/cashprediction/core/ui/icons/$name"] = @{
        base64=[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $IconSourceDirectory $name)))
    }
}
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
        $value = $data.files[$path]
        $size = if ($value -is [hashtable]) { [Convert]::FromBase64String($value.base64).Length } else { [Text.Encoding]::UTF8.GetByteCount($value) }
        "Path = $path"; 'Size = ' + $size; 'Attributes = A'; ''
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
    if ($text -is [hashtable]) { [IO.File]::WriteAllBytes($destination,[Convert]::FromBase64String($text.base64)) }
    else { [IO.File]::WriteAllText($destination, $text, [Text.UTF8Encoding]::new($false)) }
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
if ($args.Count -ne 3 -or $args[0] -cne '-B' -or $args[2] -cne 'install' -or
    [IO.Path]::GetFileName($location) -cne 'source' -or
    [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($location)) -cne $temp -or
    [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($location)) -notmatch '^cp-final-delivery-[0-9a-f]{32}$') { throw 'MOCK_BUILD_SCOPE' }
if (-not (Test-Path -LiteralPath (Join-Path $location 'pom.xml'))) { throw 'MOCK_BUILD_SOURCE' }
# Проверяет новый пустой repository именно рядом со свежим извлечением.
$repository = Join-Path ([IO.Path]::GetDirectoryName($location)) 'maven-repository'
if ($args[1] -cne "-Dmaven.repo.local=$repository" -or
    -not (Test-Path -LiteralPath $repository -PathType Container) -or
    (Get-Item -LiteralPath $repository -Force).Attributes -band [IO.FileAttributes]::ReparsePoint -or
    @(Get-ChildItem -LiteralPath $repository -Force).Count -ne 0) { throw 'MOCK_BUILD_REPOSITORY' }
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
    $arguments = @($built.receipt.commands[-1].arguments)
    Assert-Fixture ($arguments.Count -eq 3 -and $arguments[0] -ceq '-B' -and $arguments[2] -ceq 'install') 'Неверный вызов full install.'
    Assert-Fixture ($arguments[1] -ceq "-Dmaven.repo.local=$($built.receipt.maven.repository)") 'Неверный repository в receipt.'
    Assert-Fixture (-not (Test-Path -LiteralPath $built.receipt.maven.repository)) 'Repository остался после cleanup.'
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
            'AGENTS.md', 'core/src/test/resources/CLAUDE.md', 'docs/FORMAT.md', '.github/workflows/release.yml', 'core/target/X.class',
            'core/src/test/resources/.agent.json','web/src/main/resources/.agents.settings.json',
            'dist/scripts/.AGENT.JSON','core/src/test/resources/.windsurfrules','core/src/test/resources/.agent/config.json')) {
        $files = $sourceFiles.Clone(); $files[$path] = 'forbidden'
        $root = New-Case ('bad-path-' + [guid]::NewGuid().ToString('N')) '' $files
        $failure = Invoke-Case $root 'FINAL_'
        Assert-Fixture (@($failure.receipt.commands | Where-Object { $_.arguments[0] -ceq 'x' }).Count -eq 0) 'Опасный путь дошёл до extraction.'
    }
    # Проверяет интегрированный SKILL guard по точной причине, до извлечения архива.
    foreach ($path in @('core/src/test/resources/nested/SKILL.md',
            'update-tool/src/main/resources-filtered/nested/skill.MARKDOWN')) {
        $files = $sourceFiles.Clone(); $files[$path] = 'agent skill instructions'
        $root = New-Case ('skill-instruction-' + [guid]::NewGuid().ToString('N')) '' $files
        $failure = Invoke-Case $root ('^FINAL_SOURCE_EXCLUDED_FILE: ' + [regex]::Escape($path) + '$')
        Assert-Fixture (@($failure.receipt.commands | Where-Object { $_.arguments[0] -ceq 'x' }).Count -eq 0) 'SKILL извлечён до отказа.'
    }
    $missing = $sourceFiles.Clone(); $missing.Remove('update-tool/src/main/java/Example.java')
    $null = Invoke-Case (New-Case 'missing-source' '' $missing) 'исходники обязательного модуля'
    foreach ($document in @('architecture', 'techstack', 'edge-cases', 'db-schema', 'linx', 'ui-kit')) {
        $relative = "docs/design/$document.md"
        $missing = $sourceFiles.Clone(); $missing.Remove($relative)
        $failure = Invoke-Case (New-Case ("missing-$document") '' $missing) `
            ('^Обязательный файл поставки отсутствует: ' + [regex]::Escape($relative) + '$') -Build
        Assert-Fixture (@($failure.receipt.commands | Where-Object { $_.log -ceq 'source-build.log' }).Count -eq 0) 'Build запущен без обязательного документа.'
    }
    foreach ($relative in @('docs/design/extra.md', 'docs/design/techstack.txt', 'docs/other/ui-kit.md',
            'docs/ai/CurrentSprint.md', 'docs/AI/ContextDump.md', 'docs/ai/ChangeRequest.md', 'docs/ai/LegacyWarning.md',
            'core/src/main/resources/nested/currentsprint.md', 'web/src/test/resources/CONTEXTDUMP.md',
            'update-tool/src/main/resources-filtered/nested/ChangeRequest.txt', 'ui-parity/src/test/resources/legacywarning.MD')) {
        $extra = $sourceFiles.Clone(); $extra[$relative] = 'Excluded developer or AI document fixture'
        # docs/ai запрещён целиком как каталог; вложенные AI-файлы в разрешённых resources - как файлы.
        # Категории независимы от production-фильтра: посторонний отказ не засчитывается.
        $expected = if ($relative -match '\Adocs/ai/') { '\AFINAL_SOURCE_EXCLUDED_DIRECTORY\z' }
            else { '\AFINAL_SOURCE_EXCLUDED_FILE: ' + [regex]::Escape($relative) + '\z' }
        $failure = Invoke-Case (New-Case ('excluded-doc-' + [guid]::NewGuid().ToString('N')) '' $extra) `
            $expected -Build
        Assert-Fixture (@($failure.receipt.commands | Where-Object { $_.arguments[0] -ceq 'x' -or $_.log -ceq 'source-build.log' }).Count -eq 0) 'Forbidden doc извлечён или передан build.'
    }
    foreach ($name in @('application.png','application.ico')) {
        $relative = "core/src/main/resources/ru/cashprediction/core/ui/icons/$name"
        $missing = $sourceFiles.Clone(); $missing.Remove($relative)
        $null = Invoke-Case (New-Case ("missing-$name") '' $missing) ('Обязательный файл поставки отсутствует: ' + [regex]::Escape($relative))
        $corrupt = $sourceFiles.Clone(); $corrupt[$relative] = @{base64=[Convert]::ToBase64String([byte[]]@(0))}
        $null = Invoke-Case (New-Case ("corrupt-$name") '' $corrupt) 'ICON_PNG_SIGNATURE_OR_SIZE|FINAL_SOURCE_ICON_ICO'
    }
    $corrupt = $sourceFiles.Clone()
    $png = [Convert]::FromBase64String($sourceFiles['core/src/main/resources/ru/cashprediction/core/ui/icons/application.png'].base64)
    $png[29] = $png[29] -bxor 1
    $corrupt['core/src/main/resources/ru/cashprediction/core/ui/icons/application.png'] = @{base64=[Convert]::ToBase64String($png)}
    $null = Invoke-Case (New-Case 'png-crc' '' $corrupt) 'ICON_PNG_CRC'
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
    foreach ($relative in @('runtime/bin/java.exe','runtime/lib/modules')) {
        $root = New-Case ('missing-runtime-file-' + [guid]::NewGuid().ToString('N'))
        $path = Join-Path $root ('CashPrediction/' + $relative); Assert-FixturePath $path
        Remove-Item -LiteralPath $path
        $null = Invoke-Case $root 'FINAL_RUNTIME_MISSING'
    }
    $root = New-Case 'empty-runtime'; $path = Join-Path $root 'CashPrediction/runtime/lib/modules'
    Assert-FixturePath $path; [IO.File]::WriteAllBytes($path,[byte[]]@())
    $null = Invoke-Case $root 'FINAL_RUNTIME_JIMAGE_HEADER'
    $root = New-Case 'corrupt-runtime'; $path = Join-Path $root 'CashPrediction/runtime/lib/modules'
    Assert-FixturePath $path; [IO.File]::WriteAllText($path,'not jimage')
    $null = Invoke-Case $root 'FINAL_RUNTIME_JIMAGE_HEADER'
    $root = New-Case 'runtime-index-overrun'; $path = Join-Path $root 'CashPrediction/runtime/lib/modules'
    Assert-FixturePath $path; $bytes = [IO.File]::ReadAllBytes($path)
    [BitConverter]::GetBytes([uint32]4294967295L).CopyTo($bytes,16); [IO.File]::WriteAllBytes($path,$bytes)
    $null = Invoke-Case $root 'FINAL_RUNTIME_JIMAGE_INDEX'
    $root = New-Case 'runtime-java-invalid'; $path = Join-Path $root 'CashPrediction/runtime/bin/java.exe'
    Assert-FixturePath $path; [IO.File]::WriteAllText($path,'not PE')
    $null = Invoke-Case $root 'FINAL_PORTABLE_PE'
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
    if ($ReceiptPath) {
        $summary = [ordered]@{scope='actual delivery validators with explicitly mocked external tools and synthetic artifacts';
            fullAcceptance=$false; nativeExecuted=$false; guiExecuted=$false; mavenExecuted=$false;
            checks=$checks; cases=@($cases.ToArray()); validatorSha256=(Get-FileHash -LiteralPath $validator).Hash;
            policySha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'Pack-Source.ps1')).Hash;
            fixtureSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash}
        $stream = [IO.File]::Open($ReceiptPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
        try { $bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $summary -Depth 20)); $stream.Write($bytes,0,$bytes.Length) }
        finally { $stream.Dispose() }
    }
    Assert-FixturePath $testRoot
    # Проверяет все дочерние записи перед рекурсивным удалением точного owned root.
    $pending = [Collections.Generic.Stack[string]]::new(); $pending.Push($testRoot)
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Pop() -Force) { Assert-FixturePath $item.FullName; if ($item.PSIsContainer) { $pending.Push($item.FullName) } }
    }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
