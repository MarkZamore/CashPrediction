<#
.SYNOPSIS
Собирает исходники CashPrediction в отдельной папке и, по запросу, в архиве .7z.
.DESCRIPTION
Нужны PowerShell 7 и установленный 7-Zip (только инструмент разработчика).
Скрипт не подтверждает прохождение S6/S7. Финальную доставку запускают после этих этапов.
Сохраняются исходники, конфигурация, лицензии, ресурсы приложения и тестов.
Документация разработчика исключается, кроме docs/design/architecture.md.
Markdown справки и настоящих ресурсов тестов сохраняется, лицензии сохраняются отдельно.
Модуль repository-doc-audits исключается только из доставки; его единственная запись
удаляется из staged pom.xml. Все модули приложения и профили тестов сохраняются.
Исходный POM репозитория не меняется. Полный install проверяется через Test-Pack-Source -VerifyBuild.
Репозиторные .github/workflows исключаются; .github/scripts для проверки portable сохраняются.
Ссылки и junction не обходятся. Существующие папки и архивы не перезаписываются.
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
    # Название продукта отсекается только в корне: пакет ru/cashprediction содержит исходники.
    if ($Relative -notmatch '[/\\]' -and $Name -match '^CashPrediction(?:-(?:Swing|Web))?(?:\.exe)?$') { return $true }
    return $Name -match '^(\.git|\.claude|\.codex|\.agents|\.agent|\.cursor|\.aider|\.gemini|\.copilot|\.windsurf|\.continue|\.roo|\.kilocode|\.opencode|\.idea|\.vscode|\.vs|\.playwright-mcp|modernize|node_modules|target|targets|CashMemory|bin|obj|out|build|temp|tmp|staging|archives|binaries|__pycache__)$'
}

# Удаляет ровно одну запись обязательного репозиторного аудитора; остальные байты POM сохраняются.
function ConvertTo-DeliveredPom([string] $Text) {
    $document = [Xml.XmlDocument]::new()
    $document.LoadXml($Text)
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
    $name = [IO.Path]::GetFileName($Relative)
    if ($Relative -match '^ui-parity/docs/') { return $false }
    if ($name -match '^(AGENTS|CLAUDE|GEMINI|COPILOT|INSTRUCTIONS)(\..*)?$' -or
        $name -match '^(\.aider.*|\.cursorrules|\.cursorignore|\.claudeignore|\.codexignore|\.mcp\.json|copilot-instructions\..*|.*\.iml)$') { return $false }
    if ($Relative -match '^\.github/(agents|instructions|prompts|skills|workflows)/') { return $false }
    if ($name -match '(\.(7z|zip|rar|tar|tgz|gz|bz2|xz|exe|dll|class|jmod|war|ear|msi|pdb|obj|pyc|log|tmp|temp|bak|swp|swo|orig)|~)$') { return $false }
    if ($name -match '\.jar$' -and $Relative -ne '.mvn/wrapper/maven-wrapper.jar') { return $false }
    if ($Relative -eq 'docs/design/architecture.md') { return $true }
    if ($name -match '^(LICENSE|LICENCE|COPYING|NOTICE)(\..*)?$') { return $true }
    if ($Relative -match '^docs/') { return $false }
    if ($name -match '\.(md|markdown|rst|adoc)$') {
        return $Relative -match '^[^/]+/src/(main|test)/resources/' -and
            $name -notmatch '^(README|CHANGELOG|CONTRIBUTING)(\..*)?$'
    }
    return $true
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
if (-not (Test-Path -LiteralPath (Join-Path $source 'pom.xml') -PathType Leaf)) {
    throw 'SourceRoot должен содержать корневой pom.xml.'
}
if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'SourceRoot не должен быть ссылкой.'
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
    Join-Path ([IO.Path]::GetTempPath()) ('CashPrediction-source-' + [guid]::NewGuid().ToString('N'))
}
$stage = $stage.TrimEnd([IO.Path]::DirectorySeparatorChar)
Assert-NoLinkedAncestor $stage
if ((Test-WithinPath $stage $source) -or (Test-WithinPath $source $stage)) {
    throw 'Временная папка должна находиться вне дерева исходников и не содержать его.'
}
if (Test-Path -LiteralPath $stage) { throw "Папка уже существует: $stage" }
$archive = $null
$sevenZip = $null
if (-not $StageOnly) {
    $archive = [IO.Path]::GetFullPath($OutputPath)
    Assert-NoLinkedAncestor $archive
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
    $null = New-Item -ItemType Directory -Path $stage
    $createdStage = $true
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($source)
    $count = 0
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
            } else { Copy-Item -LiteralPath $item.FullName -Destination $destination }
            $count++
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $stage 'docs/design/architecture.md') -PathType Leaf)) {
        throw 'Не найдена docs/design/architecture.md.'
    }
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
        if ((Test-WithinPath $stage $source) -or (Test-WithinPath $source $stage)) {
            throw 'Небезопасный путь очистки.'
        }
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}
