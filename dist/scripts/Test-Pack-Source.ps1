<#
.SYNOPSIS
Проверяет упаковку исходников без создания финального артефакта доставки.
.DESCRIPTION
Нужны PowerShell 7 и установленный 7-Zip. Проверки выполняются в отдельном
временном каталоге, включая пробелы и русские имена. Все результаты удаляются.
VerifyBuild дополнительно запускает mvn -B install в распакованных исходниках;
нужны JDK 25, Maven 3.9+ и зависимости Maven. S6/S7 этим не подтверждаются.
FiltersOnly проверяет предикаты упаковщика в памяти без записи файлов и очистки.
Этот режим не подтверждает сборку; VerifyBuild по-прежнему запускает все тесты.
FixturesOnly проверяет staging и временный архив искусственного проекта, не копируя меняющиеся исходники.
.EXAMPLE
./dist/scripts/Test-Pack-Source.ps1 -VerifyBuild
.EXAMPLE
./dist/scripts/Test-Pack-Source.ps1 -FiltersOnly
.EXAMPLE
./dist/scripts/Test-Pack-Source.ps1 -FixturesOnly
#>
#requires -Version 7.0
[CmdletBinding()]
param(
    [string] $SourceRoot = (Join-Path $PSScriptRoot '../..'),
    [string] $SevenZipPath,
    [switch] $VerifyBuild,
    [switch] $FiltersOnly,
    [switch] $FixturesOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = (Get-Item -LiteralPath $SourceRoot).FullName
$pack = Join-Path $PSScriptRoot 'Pack-Source.ps1'

# Сообщает точную нарушенную проверку без внешнего тестового фреймворка.
function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

# Создаёт искусственные входные файлы только внутри временного стенда.
function Add-Fixture([string] $Relative, [string] $Content = 'fixture') {
    $path = Join-Path $fixture $Relative
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
}

# Проверяет ожидаемый отказ, не скрывая исключение самой проверки.
function Assert-Rejected([scriptblock] $Action, [string] $Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-True $rejected $Message
}

# Загружает только определения чистых предикатов; основной код упаковки не выполняется.
function Test-SourceFilters {
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($pack, [ref]$tokens, [ref]$errors)
    Assert-True ($errors.Count -eq 0) 'Синтаксическая ошибка упаковщика.'
    foreach ($name in @('Test-ExcludedDirectory', 'Test-IncludedFile', 'Read-SourcePom', 'ConvertTo-DeliveredPom')) {
        $definitions = @($ast.FindAll({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
        }, $false))
        Assert-True ($definitions.Count -eq 1) "Нет единственного определения: $name"
        . ([scriptblock]::Create($definitions[0].Extent.Text))
    }
    $rejectedFiles = @('AGENTS.md', 'AGENTS.override.md', 'CLAUDE.md', 'nested/agents.MD',
        'core/src/main/resources/CLAUDE.md', 'core/src/test/resources/AGENTS.md',
        'GEMINI.md', 'COPILOT.md', 'INSTRUCTIONS.md', '.codexignore', '.claudeignore', '.mcp.json',
        '.mcp/config.json', 'nested/.MCP/config.json', 'nested\.mcp\config.json',
        'mcp.json', 'nested/MCP.JSON', 'core/src/main/resources/mcp.json',
        'core/src/test/resources/.mcp/config.json',
        'README.txt', 'nested/readme.TXT', 'developer-notes.txt', 'nested/developer-notes.md',
        'core/src/main/resources/README.txt', 'core/src/test/resources/developer-notes.txt',
        'core/src/main/resources/developer-notes.md', 'core/src/test/resources/CHANGELOG.txt',
        'core/src/test/resources/developer-guide.md', 'nested/developer-guide.txt',
        'core/src/test/resources/CONTRIBUTING.txt', 'nested/architecture.md',
        'docs/design/architecture.txt', 'docs/architecture.md',
        '.cursorrules', '.github/copilot-instructions.md', '.github/agents/build.agent.md',
        '.github/instructions/build.instructions.md', '.github/prompts/build.prompt.md', '.github/skills/tool.ps1',
        '.github/workflows/ci.yml', '.github/workflows/release.yml', '.github/workflows/nested/manual.yaml',
        '.github\workflows\ci.yml',
        'PROJECT_REQUIREMENTS.md', 'nested/PROJECT_REQUIREMENTS.txt', 'docs/LICENSE.txt',
        'reports/result.json', 'update-tool/build-integration.patch', '.env', 'core/src/test/resources/.env.local',
        'core/src/main/resources/private.key', 'dist/scripts/credentials.json', 'core/src/test/resources/agent-history.jsonl',
        'README.md', 'docs/ui-spec.md', 'docs/design/ui-spec-v2.md', 'docs/FORMAT.md', 'docs/ui-protocol.md',
        'docs/design/stages.md', 'core/src/test/resources/README.md',
        'CashPrediction.exe', 'CashPrediction-Swing.exe', 'CashPrediction-Web.exe')
    foreach ($path in $rejectedFiles) {
        Assert-True (-not (Test-IncludedFile $path)) "Запрещённый файл разрешён фильтром: $path"
    }
    foreach ($path in @('ui-parity/docs/allowance-audit-20261002-1632.json',
            'ui-parity/docs/nested/report.txt', 'ui-parity\docs\report.json')) {
        Assert-True (-not (Test-IncludedFile $path)) "Отчёт разработчика разрешён фильтром: $path"
    }
    foreach ($relative in @('ui-parity/docs', 'ui-parity\docs')) {
        Assert-True (Test-ExcludedDirectory 'docs' $relative) "Папка отчётов разрешена: $relative"
    }
    Assert-True (-not (Test-ExcludedDirectory 'docs' 'ui-parity/src/test/resources/docs')) 'Папка docs ресурсов ошибочно исключена.'
    $rejectedDirectories = @('.git', '.claude', '.codex', '.mcp', '.MCP', '.agents', '.agent', '.cursor', '.aider',
        '.gemini', '.copilot', '.windsurf', '.continue', '.roo', '.kilocode', '.opencode',
        '.vscode', '.idea', 'target', 'targets', 'CashMemory', 'binaries', 'archives', 'temp', 'tmp',
        'CashPrediction', 'CashPrediction-Swing', 'CashPrediction-Web',
        'CashPrediction.exe', 'CashPrediction-Swing.exe', 'CashPrediction-Web.exe', 'cashprediction-swing.EXE', 'repository-doc-audits')
    foreach ($name in $rejectedDirectories) {
        Assert-True (Test-ExcludedDirectory $name) "Запрещённая папка разрешена фильтром: $name"
    }
    foreach ($relative in @('nested/.mcp', 'core/src/test/resources/.mcp', 'nested\.MCP')) {
        Assert-True (Test-ExcludedDirectory '.mcp' $relative) "Папка конфигурации MCP разрешена: $relative"
    }
    foreach ($relative in @('.github/workflows', '.github\workflows')) {
        Assert-True (Test-ExcludedDirectory 'workflows' $relative) "Репозиторные workflows разрешены: $relative"
    }
    foreach ($relative in @('workflows', 'web/src/test/resources/workflows', '.github/scripts')) {
        Assert-True (-not (Test-ExcludedDirectory ([IO.Path]::GetFileName($relative)) $relative)) "Папка вне репозиторных workflows исключена: $relative"
    }
    foreach ($name in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web', 'ui-parity', 'dist', '.mvn', 'CashPredictionSource')) {
        Assert-True (-not (Test-ExcludedDirectory $name)) "Папка исходников запрещена фильтром: $name"
    }
    foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web', 'ui-parity')) {
        foreach ($tree in @('main/java', 'test/java', 'main/resources', 'main/resources-filtered', 'test/resources')) {
            $package = "$module/src/$tree/ru/cashprediction"
            Assert-True (-not (Test-ExcludedDirectory 'cashprediction' $package)) "Пакет ошибочно принят за портативную папку: $package"
            Assert-True (-not (Test-ExcludedDirectory 'cashprediction' $package.Replace('/', '\'))) "Пакет с разделителями Windows ошибочно исключён: $package"
        }
    }
    # Проверка настоящих Java-файлов не создаёт staging и не изменяет исходники.
    $javaCount = 0
    foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web', 'ui-parity')) {
        foreach ($tree in @('main/java', 'test/java')) {
            $javaRoot = Join-Path $source "$module/src/$tree"
            if (-not (Test-Path -LiteralPath $javaRoot -PathType Container)) { continue }
            foreach ($file in Get-ChildItem -LiteralPath $javaRoot -Recurse -File -Filter '*.java') {
                $relative = [IO.Path]::GetRelativePath($source, $file.FullName).Replace('\', '/')
                Assert-True (Test-IncludedFile $relative) "Фильтр исключает исходник Java: $relative"
                $parts = $relative.Split('/')
                for ($index = 0; $index -lt $parts.Length - 1; $index++) {
                    $ancestor = ($parts[0..$index] -join '/')
                    Assert-True (-not (Test-ExcludedDirectory $parts[$index] $ancestor)) "Фильтр исключает родителя исходника Java: $ancestor"
                }
                $javaCount++
            }
        }
    }
    Assert-True ($javaCount -gt 100) 'Проверка настоящих Java-пакетов оказалась пустой.'
    # Изменение reactor явно задано и идемпотентно; профиль parity и любые остальные настройки не трогаются.
    $pomFixture = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>core</module><module>repository-doc-audits</module><module>web</module></modules><profiles><profile><id>ui-tests</id><modules><module>ui-parity</module></modules></profile></profiles><properties><skipTests>false</skipTests></properties></project>'
    $expectedPom = $pomFixture.Replace('<module>repository-doc-audits</module>', '')
    Assert-True ((ConvertTo-DeliveredPom $pomFixture) -ceq $expectedPom) 'Изменение delivered POM затронуло лишние данные.'
    Assert-True ((ConvertTo-DeliveredPom $expectedPom) -ceq $expectedPom) 'Повторная упаковка меняет delivered POM.'
    Assert-Rejected { ConvertTo-DeliveredPom ($pomFixture.Replace('<module>repository-doc-audits</module>', '<module>repository-doc-audits</module><module>repository-doc-audits</module>')) } 'Дублированная запись аудитора должна отклоняться.'
    Assert-Rejected { ConvertTo-DeliveredPom ($expectedPom.Replace('<module>ui-parity</module>', '<module>repository-doc-audits</module>')) } 'Запись аудитора в профиле должна отклоняться.'
    foreach ($path in @('docs/design/architecture.md', 'LICENSE.md', 'LICENSE.txt', 'LICENCE', 'COPYING.md', 'NOTICE',
            'core/src/main/resources/LICENSE.md', 'core/src/test/resources/NOTICE.txt',
            'core/src/main/resources/architecture.md', 'core/src/test/resources/docs/architecture.md',
            'core/src/main/resources/help.txt', 'core/src/test/resources/requests.txt',
            'core/src/test/resources/selftest.txt', 'core/src/test/resources/mcp-response.json',
            'core/src/test/resources/mcp/fixture.json', 'core/src/test/resources/mcp.json.sample',
            'core/src/test/resources/README-data.txt', 'core/src/test/resources/developer-notes-data.txt',
            'pom.xml', 'mvnw.cmd', 'update-tool/pom.xml',
            'update-tool/src/main/java/ru/cashprediction/updatetool/UpdateTool.java',
            'update-tool/src/test/java/ru/cashprediction/updatetool/UpdateToolTest.java',
            'core/src/main/java/ru/cashprediction/core/update/net/UpdatePreparer.java',
            '.mvn/wrapper/maven-wrapper.jar', 'dist/scripts/Test-Icon-Source.ps1', 'dist/icons/make-icon.ps1',
            'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico',
            'core/src/main/resources/ru/cashprediction/core/ui/icons/application.png',
            'core/src/main/resources/ru/cashprediction/core/ui/icons/calendar.png',
            '.github/scripts/Test-Portable.ps1', '.github/scripts/GhRetry.ps1',
            'core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md',
            'core/src/test/resources/session/legacy/web/goal-calculator/web-session.md',
            'core/src/test/resources/session/legacy/web/goal-calculator/web-session.plan.md')) {
        Assert-True (Test-IncludedFile $path) "Обязательный файл запрещён фильтром: $path"
    }
    # Текущие ресурсы проверяются без staging; исключаются только известные служебные имена.
    $resourceCount = 0
    $developerResourceCount = 0
    foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web', 'ui-parity')) {
        foreach ($tree in @('main/resources', 'main/resources-filtered', 'test/resources')) {
            $resourceRoot = Join-Path $source "$module/src/$tree"
            if (-not (Test-Path -LiteralPath $resourceRoot -PathType Container)) { continue }
            foreach ($file in Get-ChildItem -LiteralPath $resourceRoot -Recurse -Force -File) {
                $relative = [IO.Path]::GetRelativePath($source, $file.FullName).Replace('\', '/')
                if ($file.Name -match '^(README|CHANGELOG|CONTRIBUTING|developer-(notes|guide))(\..*)?$') {
                    Assert-True (-not (Test-IncludedFile $relative)) "Документ разработчика разрешён: $relative"
                    $developerResourceCount++
                    continue
                }
                Assert-True (Test-IncludedFile $relative) "Фильтр исключает настоящий ресурс: $relative"
                $parts = $relative.Split('/')
                for ($index = 0; $index -lt $parts.Length - 1; $index++) {
                    $ancestor = ($parts[0..$index] -join '/')
                    Assert-True (-not (Test-ExcludedDirectory $parts[$index] $ancestor)) "Фильтр исключает родителя ресурса: $ancestor"
                }
                $resourceCount++
            }
        }
    }
    Assert-True ($resourceCount -gt 100) 'Проверка настоящих ресурсов оказалась пустой.'
    Write-Host "OK: фильтры, delivered reactor, $javaCount Java-файлов, $resourceCount ресурсов и $developerResourceCount документов разработчика проверены без записи файлов."
}

# Сохраняет общие иконки побайтно, включая все текущие и будущие ресурсы каталога core.
function Test-DeliveredIcons([string] $Original, [string] $Delivered) {
    $relative = 'core/src/main/resources/ru/cashprediction/core/ui/icons'
    $originalIcons = Join-Path $Original $relative
    foreach ($name in @('application.png', 'application.ico')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $originalIcons $name) -PathType Leaf) "Нет канонической иконки: $name"
        Assert-True (Test-Path -LiteralPath (Join-Path $Delivered "$relative/$name") -PathType Leaf) "Каноническая иконка потеряна: $name"
    }
    foreach ($icon in Get-ChildItem -LiteralPath $originalIcons -Recurse -File) {
        $path = [IO.Path]::GetRelativePath($Original, $icon.FullName)
        $copy = Join-Path $Delivered $path
        Assert-True (Test-Path -LiteralPath $copy -PathType Leaf) "Общая иконка потеряна: $path"
        Assert-True ((Get-FileHash -LiteralPath $icon.FullName).Hash -eq
            (Get-FileHash -LiteralPath $copy).Hash) "Общая иконка изменена: $path"
    }
}

# Проверяет весь staged POM по точному разрешённому преобразованию и обязательное отсутствие документов.
function Test-DeliveredReactor([string] $Original, [string] $Delivered) {
    $before = [IO.File]::ReadAllText((Join-Path $Original 'pom.xml'))
    # Здесь используется независимый ожидаемый результат, а не повторный вызов преобразования упаковщика.
    $expected = $before.Replace('<module>repository-doc-audits</module>', '')
    $after = [IO.File]::ReadAllText((Join-Path $Delivered 'pom.xml'))
    Assert-True ($after -ceq $expected) 'Staged POM не соответствует единственному разрешённому удалению модуля.'
    [xml]$deliveredXml = $after
    Assert-True (@($deliveredXml.project.modules.module) -notcontains 'repository-doc-audits') 'Репозиторный аудит остался в delivered reactor.'
    Assert-True (@($deliveredXml.project.modules.module) -contains 'update-tool') 'update-tool потерян в delivered reactor.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $Delivered 'repository-doc-audits'))) 'Репозиторный модуль попал в доставку.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $Delivered '.github/workflows'))) 'Репозиторные workflows попали в доставку.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $Delivered 'ui-parity/docs'))) 'Отчёты разработчика ui-parity/docs попали в доставку.'
    foreach ($script in @('.github/scripts/Test-Portable.ps1', '.github/scripts/GhRetry.ps1')) {
        $deliveredScript = Join-Path $Delivered $script
        Assert-True (Test-Path -LiteralPath $deliveredScript -PathType Leaf) "Обязательный скрипт исключён: $script"
        Assert-True ((Get-FileHash -LiteralPath (Join-Path $Original $script)).Hash -eq
            (Get-FileHash -LiteralPath $deliveredScript).Hash) "Обязательный скрипт изменён: $script"
    }
    foreach ($document in @('README.md', 'docs/ui-spec.md', 'docs/design/ui-spec-v2.md', 'docs/FORMAT.md', 'docs/ui-protocol.md')) {
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $Delivered $document))) "Документ разработчика попал в доставку: $document"
    }
    $deliveredDocs = @(Get-ChildItem -LiteralPath (Join-Path $Delivered 'docs') -Recurse -File | ForEach-Object {
        [IO.Path]::GetRelativePath($Delivered, $_.FullName).Replace('\', '/')
    })
    Assert-True ($deliveredDocs.Count -eq 1 -and $deliveredDocs[0] -eq 'docs/design/architecture.md') 'В docs должен остаться только architecture.md.'
    Test-DeliveredIcons $Original $Delivered
}

if ($FiltersOnly -and $VerifyBuild) { throw 'FiltersOnly несовместим с VerifyBuild.' }
if ($FixturesOnly -and ($FiltersOnly -or $VerifyBuild)) { throw 'FixturesOnly несовместим с FiltersOnly и VerifyBuild.' }
Test-SourceFilters
if ($FiltersOnly) { return }
& (Join-Path $PSScriptRoot 'Test-Pack-SourcePolicy.ps1')
$testBase = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) 'CashPredictionDev')).TrimEnd([IO.Path]::DirectorySeparatorChar)
$testRoot = Join-Path $testBase ('CashPrediction-pack-test-' + [guid]::NewGuid().ToString('N'))
# Проверяется вся цепочка родителей до первой записи в уникальный временный стенд.
$ancestor = $testRoot
while ($ancestor) {
    if ((Test-Path -LiteralPath $ancestor) -and
        ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Тестовый путь содержит ссылку.' }
    $ancestor = [IO.Path]::GetDirectoryName($ancestor)
}
$null = New-Item -ItemType Directory -Path $testRoot

try {
    if (-not $FixturesOnly) {
    $stage = Join-Path $testRoot 'реальные исходники'
    $result = & $pack -SourceRoot $source -StageDirectory $stage -StageOnly
    Test-DeliveredReactor $source $stage
    $inventory = @(Get-ChildItem -LiteralPath $stage -Recurse -Force -File | ForEach-Object {
        [IO.Path]::GetRelativePath($stage, $_.FullName).Replace('\', '/')
    })
    Assert-True ($result.FileCount -eq $inventory.Count) 'Число файлов не соответствует содержимому.'
    foreach ($path in $inventory) {
        Assert-True ($path -notmatch '(^|/)(\.git|\.claude|\.codex|\.mcp|\.agents|target|targets|CashMemory|temp|tmp|binaries|archives)(/|$)') "Запрещённый каталог: $path"
        Assert-True ($path -notmatch '^CashPrediction(?:-(?:Swing|Web))?(?:\.exe)?/') "Портативная папка попала в исходники: $path"
        Assert-True ($path -notmatch '(^|/)(AGENTS|CLAUDE|GEMINI|COPILOT)(\..*)?$') "Файл агента: $path"
        Assert-True ([IO.Path]::GetFileName($path) -notmatch '^\.?mcp\.json$') "Конфигурация MCP: $path"
        Assert-True ([IO.Path]::GetFileName($path) -notmatch '^(README|CHANGELOG|CONTRIBUTING|developer-(notes|guide))(\..*)?$') "Документ разработчика: $path"
        Assert-True ($path -notmatch '\.(exe|dll|class|jmod|7z|zip|tmp|log)$') "Артефакт: $path"
    }
    foreach ($path in $inventory | Where-Object { $_ -match '\.(md|markdown|rst|adoc)$' }) {
        Assert-True ($path -eq 'docs/design/architecture.md' -or
            $path -match '^[^/]+/src/(main|test)/resources/' -or
            [IO.Path]::GetFileName($path) -match '^(LICENSE|LICENCE|COPYING|NOTICE)(\..*)?$') "Лишняя документация: $path"
    }
    foreach ($path in @('pom.xml', 'core/pom.xml', 'update-tool/pom.xml', 'ui-fx/pom.xml', 'ui-swing/pom.xml', 'web/pom.xml',
            'ui-parity/pom.xml', 'dist/pom.xml', 'dist/scripts/Set-LauncherUtf8.ps1',
            'dist/scripts/Test-Icon-Source.ps1', 'dist/icons/make-icon.ps1',
            'dist/launchers/swing.properties', 'dist/launchers/web.properties',
            'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico',
            'core/src/main/resources/ru/cashprediction/core/ui/icons/application.png',
            'docs/design/architecture.md', 'core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md')) {
        Assert-True ($path -in $inventory) "Обязательный файл отсутствует: $path"
    }
    # Сравнение всех исходников и ресурсов по байтам, включая Markdown снимков legacy и локализацию.
    $essential = @(Get-ChildItem -LiteralPath $source -Directory | Where-Object { $_.Name -in @('core','update-tool','ui-fx','ui-swing','web','ui-parity') } |
        ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src') -Recurse -File } |
        Where-Object { $_.Extension -eq '.java' -or
            ($_.FullName -match '[/\\]resources[/\\]' -and
                $_.Name -notmatch '^(README|CHANGELOG|CONTRIBUTING|developer-(notes|guide)|AGENTS|CLAUDE)(\..*)?$' -and
                $_.Name -notmatch '^\.?mcp\.json$' -and $_.FullName -notmatch '[/\\]\.mcp[/\\]') })
    Assert-True ($essential.Count -gt 100) 'Проверка исходников и ресурсов оказалась пустой.'
    foreach ($file in $essential) {
        $relative = [IO.Path]::GetRelativePath($source, $file.FullName)
        $copy = Join-Path $stage $relative
        Assert-True (Test-Path -LiteralPath $copy -PathType Leaf) "Ресурс потерян: $relative"
        Assert-True ((Get-FileHash -LiteralPath $file.FullName).Hash -eq (Get-FileHash -LiteralPath $copy).Hash) "Ресурс изменён: $relative"
    }
    Assert-Rejected { & $pack -SourceRoot $source -StageDirectory $stage -StageOnly } 'Существующий staging должен быть защищён.'
    Assert-Rejected { & $pack -SourceRoot $source -StageDirectory (Join-Path $source 'temp-pack') -StageOnly } 'Staging внутри исходников запрещён.'
    }

    $fixture = Join-Path $testRoot 'синтетический проект'
    $kept = @('pom.xml', 'core/pom.xml', 'update-tool/pom.xml', 'ui-fx/pom.xml', 'ui-swing/pom.xml', 'web/pom.xml', 'ui-parity/pom.xml', 'dist/pom.xml',
        'docs/design/architecture.md', '.mvn/wrapper/maven-wrapper.jar', '.mvn/wrapper/maven-wrapper.properties',
        '.mvn/maven.config', 'mvnw', 'mvnw.cmd', '.gitignore', '.gitattributes', 'LICENSE.md', 'NOTICE',
        'LICENSE.txt', 'LICENCE', 'COPYING.md',
        'core/src/main/resources/LICENSE.md', 'core/src/test/resources/NOTICE.txt',
        'core/src/main/resources/architecture.md', 'core/src/test/resources/docs/architecture.md',
        'core/src/main/resources/help.txt', 'core/src/test/resources/requests.txt',
        'core/src/test/resources/selftest.txt', 'core/src/test/resources/mcp-response.json',
        'core/src/test/resources/mcp/fixture.json', 'core/src/test/resources/mcp.json.sample',
        'core/src/test/resources/README-data.txt', 'core/src/test/resources/developer-notes-data.txt',
        '.github/scripts/Test-Portable.ps1', '.github/scripts/GhRetry.ps1',
        'dist/launchers/swing.properties', 'dist/launchers/web.properties',
        'dist/scripts/Test-Icon-Source.ps1', 'dist/scripts/Set-LauncherUtf8.ps1', 'dist/icons/make-icon.ps1',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.png',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/calendar.png',
        'core/src/main/resources/help_ru.md', 'core/src/main/resources/words_ru.properties',
        'core/src/main/java/ru/cashprediction/core/Example.java',
        'core/src/main/java/ru/cashprediction/core/update/Example.java',
        'update-tool/src/main/java/ru/cashprediction/updatetool/Example.java',
        'update-tool/src/test/java/ru/cashprediction/updatetool/ExampleTest.java',
        'update-tool/src/main/resources/update-data.json', 'update-tool/src/test/resources/patch [1].json',
        'core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md',
        'core/src/main/resources/ru/cashprediction/core/ui/text/app_ru.properties',
        'core/src/test/resources/legacy/web-session.md', 'core/src/test/resources/fixture [1].json',
        'core/src/main/resources-filtered/ru/cashprediction/core/app.properties',
        'ui-fx/src/main/java/ru/cashprediction/fx/Example.java', 'ui-fx/src/test/resources/icon.png',
        'ui-swing/src/main/java/ru/cashprediction/swing/Example.java', 'ui-swing/src/main/resources/icon.png',
        'web/src/main/java/ru/cashprediction/web/Example.java', 'web/src/main/resources/web/app.js',
        'web/src/test/resources/workflows/fixture.json',
        'ui-parity/src/test/resources/docs/fixture.json', 'ui-parity/src/test/resources/docs/session.md',
        'ui-parity/src/test/java/ru/cashprediction/parity/ExampleTest.java', 'ui-parity/src/test/resources/scenario.json')
    $excluded = @('.git/config', '.claude/settings.json', '.codex/config.toml', '.agents/skill.md', '.cursor/rules/a.txt',
        '.github/copilot-instructions.md', '.github/agents/build.agent.md', '.github/instructions/build.instructions.md',
        '.github/modernize/tool.ps1', '.codexignore', '.mcp.json', 'AGENTS.md', 'CLAUDE.md', 'nested/agents.MD',
        '.mcp/config.json', 'nested/.MCP/config.json', 'mcp.json', 'nested/MCP.JSON',
        'core/src/main/resources/mcp.json', 'core/src/test/resources/.mcp/config.json',
        'README.txt', 'nested/readme.TXT', 'developer-notes.txt', 'nested/developer-notes.md',
        'core/src/main/resources/README.txt', 'core/src/test/resources/developer-notes.txt',
        'core/src/main/resources/developer-notes.md', 'core/src/test/resources/CHANGELOG.txt',
        'core/src/test/resources/developer-guide.md', 'nested/developer-guide.txt',
        'core/src/test/resources/CONTRIBUTING.txt', 'nested/architecture.md',
        'docs/design/architecture.txt', 'docs/architecture.md',
        '.github/workflows/ci.yml', '.github/workflows/release.yml', '.github/workflows/nested/manual.yaml',
        'core/src/main/resources/CLAUDE.md', 'core/src/test/resources/README.md', 'core/src/test/java/README.md',
        'README.md', 'docs/ui-spec.md', 'docs/design/ui-spec-v2.md', 'docs/FORMAT.md', 'docs/ui-protocol.md',
        'docs/BUILD.md', 'docs/design/stages.md', 'docs/notes.txt', 'CHANGELOG.md',
        'ui-parity/docs/allowance-audit-20261002-1632.json', 'ui-parity/docs/nested/report.txt',
        'repository-doc-audits/pom.xml', 'repository-doc-audits/src/test/java/ru/cashprediction/audit/UiSpecCopyTest.java',
        'CashPrediction/app/settings.properties', 'CashPrediction-Swing/app/settings.properties', 'CashPrediction-Web/app/settings.properties',
        'CashPrediction.exe/app/settings.properties', 'CashPrediction-Swing.exe/app/settings.properties', 'CashPrediction-Web.exe/app/settings.properties',
        'core/target/classes/A.class', 'nested/targets/a.txt', 'CashMemory/private.md', '.vscode/settings.json',
        'binaries/a.txt', 'archives/a.txt', 'temp/a.txt', 'tmp/a.txt', 'nested/a.EXE', 'nested/a.dll',
        'nested/a.jar', 'nested/a.7z', 'nested/a.zip', 'nested/a.tar.gz', 'nested/a.log', 'nested/a.tmp', 'nested/a.bak',
        'PROJECT_REQUIREMENTS.md', 'docs/LICENSE.txt', '.env', 'core/src/test/resources/.env.local',
        'core/src/main/resources/private.key', 'dist/scripts/credentials.json',
        'reports/result.json', 'agent-history.jsonl', 'core/src/test/resources/agent-history.jsonl',
        'update-tool/build-integration.patch', 'dist/scripts/notes.html')
    foreach ($path in $kept + $excluded) { Add-Fixture $path }
    # Стенд содержит настоящие PNG/ICO, а не текстовые заглушки бинарных ресурсов.
    foreach ($path in $kept | Where-Object { $_ -like 'core/src/main/resources/ru/cashprediction/core/ui/icons/*' }) {
        Copy-Item -LiteralPath (Join-Path $source $path) -Destination (Join-Path $fixture $path)
    }
    $modulePom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>'
    foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web', 'ui-parity', 'dist')) { Add-Fixture "$module/pom.xml" $modulePom }
    Add-Fixture 'pom.xml' '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>core</module><module>update-tool</module><module>ui-fx</module><module>ui-swing</module><module>web</module><module>repository-doc-audits</module></modules><profiles><profile><id>dist</id><modules><module>dist</module></modules></profile><profile><id>ui-tests</id><modules><module>ui-parity</module></modules></profile></profiles></project>'
    $fixtureStage = Join-Path $testRoot 'стенд с пробелами'
    $null = & $pack -SourceRoot $fixture -StageDirectory $fixtureStage -StageOnly
    Test-DeliveredReactor $fixture $fixtureStage
    foreach ($path in $kept) { Assert-True (Test-Path -LiteralPath (Join-Path $fixtureStage $path)) "Нужный файл исключён: $path" }
    foreach ($path in $excluded) { Assert-True (-not (Test-Path -LiteralPath (Join-Path $fixtureStage $path))) "Лишний файл сохранён: $path" }
    Assert-Rejected { Test-DeliveredIcons $fixture (Join-Path $testRoot 'нет иконок') } 'Потеря канонических иконок должна отклоняться.'
    # Повреждение каждого вида общей иконки проверяется только в синтетическом staging.
    foreach ($name in @('application.png', 'application.ico', 'calendar.png')) {
        $relative = "core/src/main/resources/ru/cashprediction/core/ui/icons/$name"
        $stagedIcon = Join-Path $fixtureStage $relative
        [IO.File]::WriteAllBytes($stagedIcon, [byte[]]@(0))
        Assert-Rejected { Test-DeliveredIcons $fixture $fixtureStage } "Повреждение общей иконки должно отклоняться: $name"
        Copy-Item -LiteralPath (Join-Path $fixture $relative) -Destination $stagedIcon
    }
    Test-DeliveredIcons $fixture $fixtureStage
    $fixturePom = [IO.File]::ReadAllText((Join-Path $fixture 'pom.xml'))
    Add-Fixture 'pom.xml' ($fixturePom.Replace('<module>repository-doc-audits</module>', ''))
    Assert-Rejected { & $pack -SourceRoot $fixture -StageDirectory (Join-Path $testRoot 'потерянная регистрация') -StageOnly } 'Отсутствие регистрации существующего аудитора должно отклоняться.'
    Add-Fixture 'pom.xml' $fixturePom
    Assert-Rejected { & $pack -SourceRoot $fixture -OutputPath (Join-Path $testRoot 'bad.zip') } 'Неверное расширение должно отклоняться.'
    Assert-Rejected { & $pack -SourceRoot $fixture -StageOnly -OutputPath (Join-Path $testRoot 'bad.7z') } 'Несовместимые параметры должны отклоняться.'
    # Junction создаётся без прав администратора и указывает только в созданный тестовый стенд.
    $link = Join-Path $fixture 'core/src/linked-source'
    $null = New-Item -ItemType Junction -Path $link -Target $fixtureStage
    $failedStage = Join-Path $testRoot 'отклонённая ссылка'
    try {
        Assert-Rejected { & $pack -SourceRoot $fixture -StageDirectory $failedStage -StageOnly } 'Ссылка в исходниках должна отклоняться.'
        Assert-True (-not (Test-Path -LiteralPath $failedStage)) 'Staging после ошибки должен удаляться.'
        Assert-Rejected { & $pack -SourceRoot $fixtureStage -StageDirectory (Join-Path $link 'child') -StageOnly } 'Родительская ссылка staging должна отклоняться.'
    } finally { Remove-Item -LiteralPath $link -Force }
    # Подмена только для проверки отказа внешнего процесса и очистки его staging.
    $failedTool = Join-Path $testRoot 'failed-sevenzip.ps1'
    [IO.File]::WriteAllText($failedTool, 'exit 2', [Text.UTF8Encoding]::new($false))
    $failedArchive = Join-Path $testRoot 'failed.7z'
    $failedStage = Join-Path $testRoot 'ошибка архиватора'
    Assert-Rejected { & $pack -SourceRoot $fixture -OutputPath $failedArchive -StageDirectory $failedStage -SevenZipPath $failedTool } 'Ошибка архиватора должна передаваться вызывающему коду.'
    Assert-True (-not (Test-Path -LiteralPath $failedStage)) 'Staging после ошибки архиватора должен удаляться.'
    Assert-True (-not (Test-Path -LiteralPath $failedArchive)) 'Ошибка архиватора не должна публиковать результат.'
    if ($FixturesOnly) {
        $stage = $fixtureStage
        $inventory = $kept
    }
    # Используется временный архив реального staging; это проверка, а не финальная доставка.
    $archive = Join-Path $testRoot 'тест исходников.7z'
    $arguments = @{ SourceRoot = $stage; OutputPath = $archive; StageDirectory = (Join-Path $testRoot 'архивный стенд [1]') }
    if ($SevenZipPath) { $arguments.SevenZipPath = $SevenZipPath }
    $archiveResult = & $pack @arguments
    Assert-True ($archiveResult.FileCount -eq $inventory.Count) 'Повторная упаковка изменила инвентарь.'
    Assert-Rejected { & $pack @arguments } 'Существующий архив должен быть защищён.'
    $sevenZip = $archiveResult.SevenZipPath
    $extracted = Join-Path $testRoot 'распакованные исходники'
    & $sevenZip x -bd -y "-o$extracted" -- $archive | Out-Host
    Assert-True ($LASTEXITCODE -eq 0) 'Не удалось распаковать тестовый архив.'
    $extractedFiles = @(Get-ChildItem -LiteralPath $extracted -Recurse -Force -File)
    Assert-True ($extractedFiles.Count -eq $inventory.Count) 'В архиве другой набор файлов.'
    foreach ($path in $inventory) {
        $before = Join-Path $stage $path
        $after = Join-Path $extracted $path
        Assert-True (Test-Path -LiteralPath $after -PathType Leaf) "Файл отсутствует в архиве: $path"
        Assert-True ((Get-FileHash -LiteralPath $before).Hash -eq (Get-FileHash -LiteralPath $after).Hash) "Архив изменил файл: $path"
    }
    $deliveryOriginal = if ($FixturesOnly) { $fixture } else { $source }
    Test-DeliveredReactor $deliveryOriginal $extracted
    if ($VerifyBuild) {
        Push-Location -LiteralPath $extracted
        try {
            & mvn -B install
            Assert-True ($LASTEXITCODE -eq 0) 'mvn -B install в распакованных исходниках завершился с ошибкой.'
        } finally { Pop-Location }
    }
    Write-Host "OK: $($inventory.Count) файлов; исключения, ресурсы и архив проверены. FixturesOnly=$FixturesOnly; VerifyBuild=$VerifyBuild."
} finally {
    # Ограничение удаления проверяется по абсолютному пути созданного стенда.
    $tempBase = $testBase
    if (-not $testRoot.StartsWith($tempBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($testRoot) -notmatch '^CashPrediction-pack-test-[0-9a-f]{32}$') {
        throw 'Небезопасный путь очистки тестового стенда.'
    }
    $ancestor = $testRoot
    while ($ancestor) {
        if ((Test-Path -LiteralPath $ancestor) -and
            ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Тестовый путь очистки содержит ссылку.' }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
