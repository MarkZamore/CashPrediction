<#
.SYNOPSIS
Собирает исходники CashPrediction в отдельной папке и, по запросу, в архиве .7z.
.DESCRIPTION
Нужны PowerShell 7 и установленный 7-Zip (только инструмент разработчика).
Скрипт не подтверждает прохождение S5/S6. Финальную доставку запускают после этих этапов.
Сохраняются исходники, конфигурация, лицензии, ресурсы приложения и тестов.
Включаются только шесть технических документов docs/design: architecture, techstack,
edge-cases, db-schema, linx, ui-kit (.md); рабочие docs/ai и прочие документы исключаются.
Markdown справки и настоящих ресурсов тестов сохраняется, лицензии сохраняются отдельно.
Модуль repository-doc-audits исключается только из доставки; его единственная запись
удаляется из staged pom.xml. Все модули приложения и профили тестов сохраняются.
Исходный POM репозитория не меняется. Полный install проверяется через Test-Pack-Source -VerifyBuild.
Репозиторные .github/workflows исключаются; .github/scripts для проверки portable сохраняются.
Ссылки и junction не обходятся. Существующие папки и архивы не перезаписываются.
Перед архивацией проверяются reactor, исходники обновления и обязательные файлы сборки.
Поставка использует разрешённые деревья проекта; секретные файлы и известные токены запрещены.
Во время упаковки дерево исходников должно оставаться неизменным.
.EXAMPLE
./dist/scripts/Pack-Source.ps1 -StageOnly
.EXAMPLE
./dist/scripts/Pack-Source.ps1 -OutputPath C:/Delivery/CashPrediction-source.7z
#>
#requires -Version 7.0
[CmdletBinding()]
param(
    [string] $SourceRoot = (Join-Path $PSScriptRoot '../..'),
    [string] $StageDirectory,
    [string] $OutputPath,
    [string] $SevenZipPath,
    [switch] $StageOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Проверяет вложенность нормализованных абсолютных путей, включая совпадение.
function Test-WithinPath([string] $Path, [string] $Parent) {
    return $Path.Equals($Parent, [StringComparison]::OrdinalIgnoreCase) -or
        $Path.StartsWith($Parent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

# Отсекает служебные каталоги до обхода их содержимого.
function Test-ExcludedDirectory([string] $Name, [string] $Relative = $Name) {
    $Relative = $Relative.Replace('\', '/')
    if ($Relative -eq '.github/workflows') { return $true }
    if ($Relative -eq 'ui-parity/docs') { return $true }
    if ($Relative -eq 'repository-doc-audits') { return $true }
    if ($Relative -match '^docs/ai(/|$)') { return $true }
    if ($Relative -match '^\.github/(agents|instructions|prompts|skills)(/|$)') { return $true }
    if ($Relative -notmatch '^[^/]+/src/(main|test)/' -and
        $Name -match '^(reports?|artifacts?|evidence|history|sessions|transcripts|scratch|coverage|test-results)$') { return $true }
    # Название продукта отсекается только в корне: пакет ru/cashprediction содержит исходники.
    if ($Relative -notmatch '[/\\]' -and $Name -match '^CashPrediction(?:-(?:Swing|Web))?(?:\.exe)?$') { return $true }
    return $Name -match '^(\.git|\.claude|\.codex|\.mcp|\.agents|\.agent|\.cursor|\.aider|\.gemini|\.copilot|\.windsurf|\.continue|\.roo|\.kilocode|\.opencode|\.history|\.specstory|\.idea|\.vscode|\.vs|\.playwright-mcp|\.ssh|\.aws|\.azure|\.gnupg|\.m2|secrets?|credentials|agent-history|agent-sessions|modernize|node_modules|target|targets|CashMemory|bin|obj|out|build|temp|tmp|staging|archives|binaries|__pycache__)$'
}

# Читает POM без DTD, внешних сущностей и сетевых обращений.
function Read-SourcePom([string] $Text) {
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create([IO.StringReader]::new($Text), $settings)
    try {
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
        return ,$document
    } finally { $reader.Dispose() }
}

# Удаляет ровно одну запись обязательного репозиторного аудитора; остальные байты POM сохраняются.
function ConvertTo-DeliveredPom([string] $Text) {
    $document = Read-SourcePom $Text
    $namespaces = [Xml.XmlNamespaceManager]::new($document.NameTable)
    $namespaces.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    if (-not $document.SelectSingleNode('/m:project/m:modules', $namespaces)) {
        throw 'Ожидается корневой Maven POM с явным reactor.'
    }
    $all = @($document.SelectNodes('//m:module[normalize-space(.)="repository-doc-audits"]', $namespaces))
    $direct = @($document.SelectNodes('/m:project/m:modules/m:module[normalize-space(.)="repository-doc-audits"]', $namespaces))
    if ($all.Count -ne $direct.Count -or $direct.Count -gt 1) {
        throw 'Аудитор должен иметь одну запись только в основном reactor.'
    }
    # Повторная упаковка уже доставленного дерева не требует отсутствующего репозиторного модуля.
    if ($direct.Count -eq 0) { return $Text }
    $pattern = '<module>\s*repository-doc-audits\s*</module>'
    $matches = [regex]::Matches($Text, $pattern)
    if ($matches.Count -ne 1) { throw 'Запись аудитора в POM неоднозначна или имеет неподдерживаемую форму.' }
    return $Text.Remove($matches[0].Index, $matches[0].Length)
}

# Не позволяет родительской ссылке вывести staging или архив за проверенный путь.
function Assert-NoLinkedAncestor([string] $Path) {
    $parent = [IO.Path]::GetDirectoryName($Path)
    while ($parent) {
        if (Test-Path -LiteralPath $parent) {
            if ((Get-Item -LiteralPath $parent -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Родительская папка является ссылкой: $parent"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($parent)
    }
}

# Сохраняет Markdown ресурсов, но исключает инструкции разработчика внутри ресурсов.
function Test-IncludedFile([string] $Relative) {
    $Relative = $Relative.Replace('\', '/')
    if ($Relative -match '(^/|:|(^|/)\.{1,2}(/|$))') { return $false }
    $parts = $Relative.Split('/')
    for ($index = 0; $index -lt $parts.Length - 1; $index++) {
        if (Test-ExcludedDirectory $parts[$index] ($parts[0..$index] -join '/')) { return $false }
    }
    $name = [IO.Path]::GetFileName($Relative)
    $resource = $Relative -match '^(core|update-tool|ui-fx|ui-swing|web|ui-parity)/src/(main|test)/resources(?:-filtered)?/'
    # Служебные agent-файлы запрещены по имени в любом дереве, включая ресурсы.
    # Не запрещаем обычные данные agent-response.json и другие предметные fixtures.
    if ($name -match '^\.(agents?|claude|codex|gemini|copilot|cursor|windsurf|continue|roo|kilocode|opencode|mcp)(?:[._-].*)?$' -or
        $name -match '^\.(cursor|windsurf|claude|codex)(rules|ignore)(?:\..*)?$') { return $false }
    # Конфигурация MCP исключается и при прямой проверке файла, и до обхода каталога.
    if ($Relative -match '(^|/)\.mcp/' -or $name -match '^\.?mcp\.json$') { return $false }
    # Именованные документы разработчика отсеиваются независимо от расширения и дерева ресурсов.
    if ($name -match '^(README|CHANGELOG|CONTRIBUTING|developer-(notes|guide))(\..*)?$') { return $false }
    if ($name -match '^(PROJECT_REQUIREMENTS|agent[-_.](history|sessions?|transcripts?|reports?)|implementation[-_.]report)(\..*)?$' -or
        $name -match '^\.(claude|codex|gemini|copilot|cursor|mcp)\.') { return $false }
    if (-not $resource -and $name -match '(^|[-_.])(reports?|results?|evidence|transcripts?|history|coverage)([-_.]|$)' -and
        $name -match '\.(jsonl?|xml|csv|tsv|html|pdf|png)$') { return $false }
    if ($name -match '^(\.env(\..*)?|\.(netrc|npmrc|pypirc)|settings-security\.xml|settings\.xml|id_(rsa|dsa|ecdsa|ed25519)(\..*)?|\.?credentials(\..*)?|\.?secrets?(\..*)?)$' -or
        $name -match '\.(pem|key|p12|pfx|jks|keystore)$') { return $false }
    if ($Relative -match '^ui-parity/docs/') { return $false }
    if ($name -match '^SKILL\.(md|markdown)$' -or
        $name -match '^(AGENTS|CLAUDE|GEMINI|COPILOT|INSTRUCTIONS|CurrentSprint|ContextDump|ChangeRequest|LegacyWarning)(\..*)?$' -or
        $name -match '^(\.aider.*|\.cursorrules|\.cursorignore|\.claudeignore|\.codexignore|\.mcp\.json|copilot-instructions\..*|.*\.iml)$') { return $false }
    if ($Relative -match '^\.github/(agents|instructions|prompts|skills|workflows)/') { return $false }
    if ($name -match '(\.(7z|zip|rar|tar|tgz|gz|bz2|xz|exe|dll|class|jmod|war|ear|msi|pdb|obj|pyc|log|tmp|temp|bak|swp|swo|orig)|~)$') { return $false }
    if ($name -match '\.jar$' -and $Relative -ne '.mvn/wrapper/maven-wrapper.jar') { return $false }
    if ($Relative -in @('docs/design/architecture.md', 'docs/design/techstack.md',
            'docs/design/edge-cases.md', 'docs/design/db-schema.md',
            'docs/design/linx.md', 'docs/design/ui-kit.md')) { return $true }
    if ($Relative -match '^docs/') { return $false }
    # Разрешены только исходники и поддерживаемые входы сборки, а не произвольные файлы репозитория.
    if ($name -match '\.(md|markdown|rst|adoc|txt|html|pdf|docx?)$' -and -not $resource -and
        $name -notmatch '^(LICENSE|LICENCE|COPYING|NOTICE)(\..*)?$') { return $false }
    if ($Relative -notmatch '/') {
        return $name -match '^(pom\.xml|mvnw(?:\.cmd)?|\.gitignore|\.gitattributes|(LICENSE|LICENCE|COPYING|NOTICE)(\..*)?)$'
    }
    return $Relative -match '^(core|update-tool|ui-fx|ui-swing|web|ui-parity)/(pom\.xml|src/.+)$' -or
        $Relative -eq 'ui-parity/Verify-Infrastructure.ps1' -or
        $Relative -match '^dist/(pom\.xml|scripts/.+|icons/.+|launchers/.+)$' -or
        $Relative -match '^\.github/scripts/.+\.ps1$' -or
        $Relative -match '^\.mvn/(maven\.config|jvm\.config|extensions\.xml|wrapper/(maven-wrapper\.(jar|properties)|MavenWrapperDownloader\.java))$'
}

# Отклоняет явный секрет в разрешённом файле; значение никогда не попадает в диагностику.
function Assert-NoSourceSecret([string] $Path, [string] $Relative) {
    $text = [IO.File]::ReadAllText($Path)
    if ($text -match '(?m)^\s*-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----' -or
        $text -match '\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,}|(?:AKIA|ASIA)[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]{20,})\b') {
        throw "В разрешённом файле обнаружен секрет: $Relative"
    }
}

# Проверяет полноту staged reactor, включая модули неактивных профилей, без запуска Maven.
function Assert-DeliveredSource([string] $Root) {
    $required = @('pom.xml', 'docs/design/architecture.md', 'docs/design/techstack.md',
        'docs/design/edge-cases.md', 'docs/design/db-schema.md', 'docs/design/linx.md',
        'docs/design/ui-kit.md', 'dist/pom.xml', 'ui-parity/pom.xml',
        'dist/scripts/Set-LauncherUtf8.ps1', 'dist/scripts/Test-Icon-Source.ps1', 'dist/icons/make-icon.ps1',
        'dist/scripts/Test-IconPayloadIntegrity.ps1', 'dist/scripts/Normalize-AppModules.ps1',
        'dist/launchers/swing.properties', 'dist/launchers/web.properties',
        '.github/scripts/Test-Portable.ps1', '.github/scripts/GhRetry.ps1',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.png',
        'core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md')
    foreach ($relative in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $Root $relative) -PathType Leaf)) {
            throw "Обязательный файл поставки отсутствует: $relative"
        }
    }
    $namespaces = [Xml.XmlNamespaceManager]::new([Xml.NameTable]::new())
    $namespaces.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    $rootPom = Read-SourcePom ([IO.File]::ReadAllText((Join-Path $Root 'pom.xml')))
    $mainModules = @($rootPom.SelectNodes('/m:project/m:modules/m:module', $namespaces) | ForEach-Object { $_.InnerText.Trim() })
    foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web')) {
        if ($mainModules -notcontains $module) { throw "Обязательный модуль отсутствует в основном reactor: $module" }
        $javaRoot = Join-Path $Root "$module/src/main/java"
        if (-not (Test-Path -LiteralPath $javaRoot -PathType Container) -or
            @(Get-ChildItem -LiteralPath $javaRoot -Recurse -File -Filter '*.java').Count -eq 0) {
            throw "Отсутствуют исходники обязательного модуля: $module"
        }
    }
    $updateRoot = Join-Path $Root 'core/src/main/java/ru/cashprediction/core/update'
    if (-not (Test-Path -LiteralPath $updateRoot -PathType Container) -or
        @(Get-ChildItem -LiteralPath $updateRoot -Recurse -File -Filter '*.java').Count -eq 0) {
        throw 'Отсутствуют исходники core.update.'
    }
    $pending = [Collections.Generic.Stack[string]]::new()
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $pending.Push('pom.xml')
    while ($pending.Count -gt 0) {
        $relative = $pending.Pop()
        if (-not $seen.Add($relative)) { continue }
        $pomPath = Join-Path $Root $relative
        if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) { throw "POM модуля отсутствует: $relative" }
        $document = Read-SourcePom ([IO.File]::ReadAllText($pomPath))
        if (-not $document.SelectSingleNode('/m:project', $namespaces)) { throw "Неверный корень Maven POM: $relative" }
        foreach ($group in $document.SelectNodes('//m:modules', $namespaces)) {
            $registered = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            foreach ($node in $group.SelectNodes('m:module', $namespaces)) {
                if (-not $registered.Add($node.InnerText.Trim())) { throw "Дублированный модуль в POM: $relative" }
            }
        }
        foreach ($node in $document.SelectNodes('//m:modules/m:module', $namespaces)) {
            $module = $node.InnerText.Trim()
            if ($module -notmatch '^(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+$') { throw "Небезопасный путь модуля: $module" }
            $modulePom = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetDirectoryName($pomPath)) "$module/pom.xml"))
            if (-not (Test-WithinPath $modulePom $Root)) { throw "POM вне поставки: $module" }
            $child = [IO.Path]::GetRelativePath($Root, $modulePom).Replace('\', '/')
            if (-not (Test-IncludedFile $child)) { throw "Модуль ссылается на исключённое дерево: $module" }
            $pending.Push($child)
        }
    }
    foreach ($module in @('dist', 'ui-parity')) {
        if (-not $seen.Contains("$module/pom.xml")) { throw "Модуль отсутствует в reactor и профилях: $module" }
    }
    foreach ($item in Get-ChildItem -LiteralPath $Root -Recurse -Force -File) {
        $relative = [IO.Path]::GetRelativePath($Root, $item.FullName).Replace('\', '/')
        if (-not (Test-IncludedFile $relative)) { throw "Запрещённый файл в поставке: $relative" }
        Assert-NoSourceSecret $item.FullName $relative
    }
}

# Закрепляет весь разрешённый набор, включая новые файлы, а не только уже скопированные.
function Get-SourceFreezeInventory([string] $Root) {
    $inventory = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($Root)
    while ($pending.Count -gt 0) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            $relative = [IO.Path]::GetRelativePath($Root, $item.FullName).Replace('\', '/')
            if ($item.PSIsContainer -and (Test-ExcludedDirectory $item.Name $relative)) { continue }
            if (-not $item.PSIsContainer -and -not (Test-IncludedFile $relative)) { continue }
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Ссылку нельзя включить в исходники: $relative"
            }
            if ($item.PSIsContainer) { $pending.Push($item.FullName); continue }
            $inventory.Add($relative, (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash)
        }
    }
    return ,$inventory
}

# Отвергает добавление, удаление и изменение любого доставляемого файла между обходами.
function Assert-SourceFreezeInventory($Before, $After) {
    if ($Before.Count -ne $After.Count) { throw 'Состав исходников изменился во время упаковки.' }
    foreach ($entry in $Before.GetEnumerator()) {
        if (-not $After.ContainsKey($entry.Key) -or $After[$entry.Key] -cne $entry.Value) {
            throw "Закреплённый исходник изменился во время упаковки: $($entry.Key)"
        }
    }
}

# Находит внешний архиватор, ничего не скачивая и не добавляя в приложение.
function Resolve-SevenZip {
    if ($SevenZipPath) { return (Get-Item -LiteralPath $SevenZipPath).FullName }
    $command = Get-Command 7z.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($base) {
            $candidate = Join-Path $base '7-Zip/7z.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        }
    }
    throw 'Установите 7-Zip или передайте -SevenZipPath.'
}

$source = (Get-Item -LiteralPath $SourceRoot).FullName.TrimEnd([IO.Path]::DirectorySeparatorChar)
Assert-NoLinkedAncestor $source
if (-not (Test-Path -LiteralPath (Join-Path $source 'pom.xml') -PathType Leaf)) {
    throw 'SourceRoot должен содержать корневой pom.xml.'
}
if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'SourceRoot не должен быть ссылкой.'
}
if ((Get-Item -LiteralPath (Join-Path $source 'pom.xml')).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'Корневой POM не должен быть ссылкой.'
}
$originalPom = [IO.File]::ReadAllText((Join-Path $source 'pom.xml'))
$deliveredPom = ConvertTo-DeliveredPom $originalPom
$hasRepositoryModule = Test-Path -LiteralPath (Join-Path $source 'repository-doc-audits') -PathType Container
if ($hasRepositoryModule -ne ($originalPom -cne $deliveredPom)) {
    throw 'Модуль repository-doc-audits и его обязательная запись в исходном reactor должны присутствовать вместе.'
}
if ($StageOnly -and $OutputPath) { throw 'StageOnly несовместим с OutputPath.' }
if (-not $StageOnly -and -not $OutputPath) { throw 'Укажите OutputPath либо StageOnly.' }
$stage = if ($StageDirectory) { [IO.Path]::GetFullPath($StageDirectory) } else {
    Join-Path (Join-Path ([IO.Path]::GetTempPath()) 'CashPredictionDev') ('CashPrediction-source-' + [guid]::NewGuid().ToString('N'))
}
$stage = $stage.TrimEnd([IO.Path]::DirectorySeparatorChar)
Assert-NoLinkedAncestor $stage
if ((Test-WithinPath $stage $source) -or (Test-WithinPath $source $stage)) {
    throw 'Временная папка должна находиться вне дерева исходников и не содержать его.'
}
if (Test-Path -LiteralPath $stage) { throw "Папка уже существует: $stage" }
if ($stage -match '[*?]' -or $stage.IndexOf(':', 2) -ge 0) { throw 'Staging не должен содержать маски или альтернативный поток.' }
$archive = $null
$sevenZip = $null
if (-not $StageOnly) {
    $archive = [IO.Path]::GetFullPath($OutputPath)
    Assert-NoLinkedAncestor $archive
    if ($archive -match '[*?]' -or $archive.IndexOf(':', 2) -ge 0) { throw 'OutputPath не должен содержать маски или альтернативный поток.' }
    if ([IO.Path]::GetExtension($archive) -ne '.7z') { throw 'OutputPath должен иметь расширение .7z.' }
    if (Test-WithinPath $archive $stage) { throw 'Архив должен находиться вне временной папки.' }
    if (Test-Path -LiteralPath $archive) { throw "Архив уже существует: $archive" }
    if (-not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($archive)) -PathType Container)) {
        throw 'Родительская папка OutputPath должна существовать.'
    }
    $sevenZip = Resolve-SevenZip
}

$createdStage = $false
$temporaryArchive = $null
$success = $false
try {
    $sourceFreeze = Get-SourceFreezeInventory $source
    $null = New-Item -ItemType Directory -Path $stage
    $createdStage = $true
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($source)
    $count = 0
    $hashes = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    while ($pending.Count -gt 0) {
        $directory = $pending.Pop()
        foreach ($item in Get-ChildItem -LiteralPath $directory -Force) {
            $relative = [IO.Path]::GetRelativePath($source, $item.FullName).Replace('\', '/')
            if ($item.PSIsContainer -and (Test-ExcludedDirectory $item.Name $relative)) { continue }
            if (-not $item.PSIsContainer -and -not (Test-IncludedFile $relative)) { continue }
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Ссылку нельзя включить в исходники: $relative"
            }
            if ($item.PSIsContainer) { $pending.Push($item.FullName); continue }
            # Используется буквальный путь; имена с пробелами и скобками не являются масками.
            $destination = Join-Path $stage $relative
            $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
            if ($relative -eq 'pom.xml') {
                if ([IO.File]::ReadAllText($item.FullName) -cne $originalPom) { throw 'Корневой POM изменился во время упаковки.' }
                [IO.File]::WriteAllText($destination, $deliveredPom, [Text.UTF8Encoding]::new($false))
            } else {
                $hash = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash
                Copy-Item -LiteralPath $item.FullName -Destination $destination
                if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne $hash) {
                    throw "Исходник изменился при копировании: $relative"
                }
                $hashes.Add($relative, $hash)
            }
            $count++
        }
    }
    Assert-DeliveredSource $stage
    if ([IO.File]::ReadAllText((Join-Path $source 'pom.xml')) -cne $originalPom) { throw 'Корневой POM изменился во время упаковки.' }
    foreach ($entry in $hashes.GetEnumerator()) {
        if ((Get-FileHash -LiteralPath (Join-Path $source $entry.Key) -Algorithm SHA256).Hash -cne $entry.Value) {
            throw "Исходник изменился во время упаковки: $($entry.Key)"
        }
    }
    Assert-SourceFreezeInventory $sourceFreeze (Get-SourceFreezeInventory $source)
    if ($archive) {
        # Архив создаётся под случайным именем рядом с результатом и публикуется без перезаписи.
        $temporaryArchive = Join-Path ([IO.Path]::GetDirectoryName($archive)) ([guid]::NewGuid().ToString('N') + '.7z')
        Push-Location -LiteralPath $stage
        try {
            & $sevenZip a -t7z -mx=9 -bd -y -- $temporaryArchive '.' | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "7-Zip a завершился с кодом $LASTEXITCODE." }
            & $sevenZip t -bd -- $temporaryArchive | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "7-Zip t завершился с кодом $LASTEXITCODE." }
        } finally { Pop-Location }
        Assert-SourceFreezeInventory $sourceFreeze (Get-SourceFreezeInventory $source)
        [IO.File]::Move($temporaryArchive, $archive)
    }
    $success = $true
    [pscustomobject]@{ StageDirectory = $(if ($StageOnly) { $stage } else { $null });
        OutputPath = $archive; SevenZipPath = $sevenZip; FileCount = $count }
} finally {
    if ($temporaryArchive -and (Test-Path -LiteralPath $temporaryArchive)) {
        Remove-Item -LiteralPath $temporaryArchive -Force
    }
    # Удаляется только созданная этим запуском папка после проверки её абсолютного пути.
    if ($createdStage -and (-not $StageOnly -or -not $success)) {
        Assert-NoLinkedAncestor $stage
        if ((Get-Item -LiteralPath $stage -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Staging подменён ссылкой; автоматическая очистка запрещена.'
        }
        if ((Test-WithinPath $stage $source) -or (Test-WithinPath $source $stage)) {
            throw 'Небезопасный путь очистки.'
        }
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}
