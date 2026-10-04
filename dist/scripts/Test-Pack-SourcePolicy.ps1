<#
.SYNOPSIS
Проверяет защитную политику исходной поставки на независимых негативных fixtures.
.DESCRIPTION
Не запускает Maven, GUI и упаковку настоящего проекта. Все записи находятся
в уникальной папке Temp/CashPredictionDev; временные результаты удаляются.
Проверяет настоящие функции упаковщика и его отказы до публикации архива.
#>
#requires -Version 7.0
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pack = Join-Path $PSScriptRoot 'Pack-Source.ps1'
$testBase = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) 'CashPredictionDev')).TrimEnd([IO.Path]::DirectorySeparatorChar)
$testRoot = Join-Path $testBase ('CashPrediction-source-policy-' + [guid]::NewGuid().ToString('N'))
$checks = 0

# Ограничивает каждую запись и очистку уникальным абсолютным путём текущего стенда.
function Assert-FixturePath([string] $Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (-not ($absolute.Equals($testRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $absolute.StartsWith($testRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) -or
        [IO.Path]::GetFileName($testRoot) -notmatch '^CashPrediction-source-policy-[0-9a-f]{32}$' -or
        -not $testRoot.StartsWith($testBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Небезопасный путь fixture.'
    }
    $ancestor = $absolute
    while ($ancestor) {
        if ((Test-Path -LiteralPath $ancestor) -and
            ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Путь записи или очистки fixture содержит ссылку.'
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
}

# Считает успешные проверки и сообщает конкретное нарушение.
function Assert-Policy([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}

# Создаёт только синтетический файл внутри проверенного стенда.
function Write-Fixture([string] $Relative, [string] $Content = 'fixture') {
    $path = Join-Path $fixture $Relative
    Assert-FixturePath $path
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
}

# Требует отказа именно по ожидаемой причине и очистки собственного staging.
function Assert-PackFailure([string] $Name, [string] $Expected, [hashtable] $Options = @{}) {
    $stage = Join-Path $testRoot ('отказ ' + $Name)
    Assert-FixturePath $stage
    $arguments = @{ SourceRoot = $fixture; StageDirectory = $stage; StageOnly = $true }
    foreach ($key in $Options.Keys) { $arguments[$key] = $Options[$key] }
    $failure = $null
    try { & $pack @arguments | Out-Null } catch { $failure = $_.Exception.Message }
    Assert-Policy ($null -ne $failure -and $failure -match $Expected) "Нет ожидаемого отказа '$Name': $failure"
    Assert-Policy (-not (Test-Path -LiteralPath $stage)) "После отказа остался staging: $Name"
}

# Временно прячет только проверенный файл fixture, затем восстанавливает его.
function Test-MissingFixture([string] $Relative, [string] $Expected) {
    $path = Join-Path $fixture $Relative
    $saved = Join-Path $testRoot ('сохранённый файл ' + [guid]::NewGuid().ToString('N'))
    Assert-FixturePath $path
    Assert-FixturePath $saved
    Move-Item -LiteralPath $path -Destination $saved
    try { Assert-PackFailure ([IO.Path]::GetFileName($Relative)) $Expected }
    finally { Move-Item -LiteralPath $saved -Destination $path }
}

# Загружает определения через AST без исполнения основного кода упаковщика.
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($pack, [ref]$tokens, [ref]$errors)
Assert-Policy ($errors.Count -eq 0) 'Синтаксическая ошибка упаковщика.'
foreach ($definition in $ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst]
}, $false)) { . ([scriptblock]::Create($definition.Extent.Text)) }

Assert-FixturePath $testRoot
$null = New-Item -ItemType Directory -Path $testRoot
try {
    $fixture = Join-Path $testRoot 'исходники [1] с пробелами'
    $modulePom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>'
    $rootPom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>core</module><module>update-tool</module><module>ui-fx</module><module>ui-swing</module><module>web</module><module>repository-doc-audits</module></modules><profiles><profile><id>dist</id><modules><module>dist</module></modules></profile><profile><id>ui-tests</id><modules><module>ui-parity</module></modules></profile></profiles></project>'
    $kept = @('pom.xml', 'docs/design/architecture.md', 'repository-doc-audits/pom.xml',
        'dist/scripts/Set-LauncherUtf8.ps1', 'dist/scripts/Test-Icon-Source.ps1', 'dist/icons/make-icon.ps1',
        'dist/scripts/Test-IconPayloadIntegrity.ps1', 'dist/scripts/Normalize-AppModules.ps1',
        'dist/launchers/swing.properties', 'dist/launchers/web.properties',
        '.github/scripts/Test-Portable.ps1', '.github/scripts/GhRetry.ps1',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.png',
        'core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md',
        'core/src/main/java/ru/cashprediction/core/update/Update.java',
        'core/src/test/java/ru/cashprediction/core/update/UpdateTest.java',
        'core/src/main/resources-filtered/ru/cashprediction/core/app.properties',
        'update-tool/src/test/java/ru/cashprediction/updatetool/ToolTest.java',
        'update-tool/src/test/resources/fixture [2].json',
        'update-tool/src/main/resources-filtered/help.md',
        'core/src/test/resources/docs/session.plan.md', 'core/src/test/resources/reports/sample.json',
        'core/src/test/resources/requests.txt', 'web/src/main/resources/web/index.html',
        '.mvn/extensions.xml', '.mvn/wrapper/maven-wrapper.jar', '.mvn/wrapper/maven-wrapper.properties',
        '.mvn/jvm.config', 'mvnw.cmd', 'LICENSE.txt')
    foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web', 'ui-parity', 'dist')) {
        $kept += "$module/pom.xml"
        Write-Fixture "$module/pom.xml" $modulePom
    }
    foreach ($module in @('core', 'update-tool', 'ui-fx', 'ui-swing', 'web')) {
        $kept += "$module/src/main/java/Example.java"
    }
    $excluded = @('PROJECT_REQUIREMENTS.md', 'core/src/test/resources/project_requirements.txt',
        '.claude/history.jsonl', '.codex/sessions/session.jsonl', 'core/src/main/resources/.claude/settings.json',
        '.github/agents/build.agent.md', '.github/workflows/ci.yml', 'nested/AGENTS.override.md',
        'core/src/test/resources/AGENTS.md', 'core/target/classes/Example.class',
        'update-tool/target/update.jar', 'update-tool/build-integration.patch',
        'reports/build.json', 'ui-parity/docs/report.json', 'dist/scripts/notes.html', 'dist/scripts/build-report.json',
        'docs/LICENSE.txt', 'docs/other/architecture.md', 'update-tool/implementation-report.md',
        'core/src/test/resources/agent-history.jsonl', 'agent-sessions.json', '.env.production',
        'core/src/main/resources/.env', 'core/src/test/resources/id_ed25519',
        'core/src/test/resources/private.pem', 'core/src/test/resources/private.pfx',
        '.mvn/settings.xml', '.mvn/settings-security.xml', '.github/scripts/credentials.json',
        'core/src/main/resources/.aws/credentials', 'dist/scripts/secrets.json',
        'core/src/test/resources/.credentials.json', 'core/src/test/resources/.claude.json',
        'core/src/test/resources/.history/copy.java',
        'core/src/test/resources/.agent/transcript.txt', 'core/src/test/resources/README.md')
    foreach ($relative in $kept) {
        if ($relative -notlike '*/pom.xml') { Write-Fixture $relative }
    }
    foreach ($relative in $excluded) { Write-Fixture $relative }
    Write-Fixture 'repository-doc-audits/pom.xml' $modulePom
    Write-Fixture 'pom.xml' $rootPom
    $kept = @($kept | Where-Object { $_ -ne 'repository-doc-audits/pom.xml' })
    $stage = Join-Path $testRoot 'доставка [3] с пробелами'
    Assert-FixturePath $stage
    $result = & $pack -SourceRoot $fixture -StageDirectory $stage -StageOnly
    $inventory = @(Get-ChildItem -LiteralPath $stage -Recurse -Force -File | ForEach-Object {
        [IO.Path]::GetRelativePath($stage, $_.FullName).Replace('\', '/')
    })
    Assert-Policy ($result.FileCount -eq $kept.Count -and $inventory.Count -eq $kept.Count) 'Неверное число файлов baseline.'
    foreach ($relative in $kept) {
        Assert-Policy ($inventory -contains $relative) "Нужный файл потерян: $relative"
        if ($relative -ne 'pom.xml') {
            Assert-Policy ((Get-FileHash -LiteralPath (Join-Path $fixture $relative)).Hash -eq
                (Get-FileHash -LiteralPath (Join-Path $stage $relative)).Hash) "Нужный файл изменён: $relative"
        }
    }
    foreach ($relative in $excluded) {
        Assert-Policy (-not (Test-Path -LiteralPath (Join-Path $stage $relative))) "Запрещённый файл прошёл: $relative"
        Assert-Policy (-not (Test-IncludedFile $relative)) "Предикат разрешает запрещённый файл: $relative"
    }
    Assert-Policy ([IO.File]::ReadAllText((Join-Path $fixture 'pom.xml')) -ceq $rootPom) 'Исходный POM был изменён.'
    Assert-Policy ([IO.File]::ReadAllText((Join-Path $stage 'pom.xml')) -ceq
        $rootPom.Replace('<module>repository-doc-audits</module>', '')) 'POM профилей или приложения изменён.'
    foreach ($relative in @('../pom.xml', '/core/pom.xml', 'core/../pom.xml', 'core/src/file:secret', 'core\..\pom.xml')) {
        Assert-Policy (-not (Test-IncludedFile $relative)) "Опасный относительный путь разрешён: $relative"
    }
    foreach ($relative in @('update-tool/pom.xml', 'dist/scripts/Set-LauncherUtf8.ps1',
        'core/src/main/resources/ru/cashprediction/core/ui/icons/application.ico')) {
        Test-MissingFixture $relative 'Обязательный файл|POM модуля'
    }
    # Каждый новый вход сборки должен отклоняться именно по своему отсутствующему пути.
    foreach ($relative in @('dist/scripts/Test-IconPayloadIntegrity.ps1', 'dist/scripts/Normalize-AppModules.ps1')) {
        Test-MissingFixture $relative ('Обязательный файл поставки отсутствует: ' + [regex]::Escape($relative))
    }
    Test-MissingFixture 'core/src/main/java/ru/cashprediction/core/update/Update.java' 'core\.update'
    Test-MissingFixture 'update-tool/src/main/java/Example.java' 'исходники обязательного модуля: update-tool'
    foreach ($case in @(
        @{ Name = 'потерянный update-tool'; Text = $rootPom.Replace('<module>update-tool</module>', ''); Error = 'основном reactor: update-tool' },
        @{ Name = 'внешний модуль'; Text = $rootPom.Replace('<module>dist</module>', '<module>../outside</module>'); Error = 'Небезопасный путь модуля' },
        @{ Name = 'исключённый модуль'; Text = $rootPom.Replace('<module>dist</module>', '<module>reports</module>'); Error = 'исключённое дерево' },
        @{ Name = 'неактивный профиль'; Text = $rootPom.Replace('<module>dist</module>', '<module>core/src/missing</module>'); Error = 'POM модуля отсутствует' },
        @{ Name = 'профиль без parity'; Text = $rootPom.Replace('<module>ui-parity</module>', ''); Error = 'reactor и профилях: ui-parity' },
        @{ Name = 'дубликат аудитора'; Text = $rootPom.Replace('<module>repository-doc-audits</module>', '<module>repository-doc-audits</module><module>repository-doc-audits</module>'); Error = 'одну запись' },
        @{ Name = 'дубликат приложения'; Text = $rootPom.Replace('<module>core</module>', '<module>core</module><module>core</module>'); Error = 'Дублированный модуль' },
        @{ Name = 'DTD'; Text = '<!DOCTYPE project [<!ENTITY data SYSTEM "file:///missing">]>' + $rootPom; Error = 'DTD' }
    )) {
        Write-Fixture 'pom.xml' $case.Text
        try { Assert-PackFailure $case.Name $case.Error } finally { Write-Fixture 'pom.xml' $rootPom }
    }
    Write-Fixture 'update-tool/pom.xml' '<configuration />'
    try { Assert-PackFailure 'неверный POM модуля' 'Неверный корень Maven POM' }
    finally { Write-Fixture 'update-tool/pom.xml' $modulePom }
    # Значения синтетические; объединяются во время теста, чтобы сам тест не содержал токен.
    foreach ($secret in @(('ghp_' + ('A' * 36)), ('github_pat_' + ('B' * 70)),
        ('AKIA' + ('C' * 16)), ('-----BEGIN ' + 'PRIVATE KEY-----' + "`nfixture"))) {
        Write-Fixture 'update-tool/src/main/resources/payload.json' $secret
        try { Assert-PackFailure 'секрет в ресурсе' 'обнаружен секрет' }
        finally {
            $payload = Join-Path $fixture 'update-tool/src/main/resources/payload.json'
            Assert-FixturePath $payload
            Remove-Item -LiteralPath $payload -Force
        }
    }
    Assert-PackFailure 'staging внутри исходников' 'вне дерева исходников' @{ StageDirectory = (Join-Path $fixture 'stage') }
    Assert-PackFailure 'staging содержит исходники' 'не содержать его' @{ StageDirectory = $testRoot }
    Assert-PackFailure 'альтернативный поток staging' 'альтернативный поток' @{ StageDirectory = (Join-Path $testRoot 'data:stage') }
    Assert-PackFailure 'альтернативный поток архива' 'альтернативный поток' @{
        StageOnly = $false; OutputPath = (Join-Path $testRoot 'data:result.7z')
    }
    Assert-PackFailure 'архив внутри staging' 'вне временной папки' @{
        StageOnly = $false; OutputPath = (Join-Path $testRoot 'отказ архив внутри staging/result.7z')
    }
    # Уже существующие результаты должны сохраняться побайтно.
    $existingArchive = Join-Path $testRoot 'существующий.7z'
    Assert-FixturePath $existingArchive
    [IO.File]::WriteAllText($existingArchive, 'keep')
    Assert-PackFailure 'существующий архив' 'Архив уже существует' @{ StageOnly = $false; OutputPath = $existingArchive }
    Assert-Policy ([IO.File]::ReadAllText($existingArchive) -ceq 'keep') 'Существующий архив изменён.'
    $preserved = (Get-FileHash -LiteralPath (Join-Path $stage 'pom.xml')).Hash
    $failure = $null
    try { & $pack -SourceRoot $fixture -StageDirectory $stage -StageOnly | Out-Null } catch { $failure = $_.Exception.Message }
    Assert-Policy ($failure -match 'Папка уже существует') 'Существующий staging не защищён.'
    Assert-Policy ((Get-FileHash -LiteralPath (Join-Path $stage 'pom.xml')).Hash -eq $preserved) 'Существующий staging изменён.'
    # Junction указывает только в этот стенд; перед общей очисткой удаляется сама ссылка.
    $link = Join-Path $testRoot 'ссылка'
    Assert-FixturePath $link
    $null = New-Item -ItemType Junction -Path $link -Target $fixture
    try {
        Assert-PackFailure 'исходная ссылка' 'SourceRoot не должен быть ссылкой' @{ SourceRoot = $link }
        Assert-PackFailure 'родитель исходников' 'Родительская папка является ссылкой' @{ SourceRoot = (Join-Path $link 'core') }
        Assert-PackFailure 'родитель staging' 'Родительская папка является ссылкой' @{ StageDirectory = (Join-Path $link 'stage') }
        Assert-PackFailure 'родитель архива' 'Родительская папка является ссылкой' @{
            StageOnly = $false; OutputPath = (Join-Path $link 'result.7z')
        }
    } finally { Remove-Item -LiteralPath $link -Force }
    $linkedSource = Join-Path $fixture 'core/src/main/resources/linked'
    Assert-FixturePath $linkedSource
    $null = New-Item -ItemType Junction -Path $linkedSource -Target $stage
    try { Assert-PackFailure 'ссылка в ресурсах' 'Ссылку нельзя включить' }
    finally { Remove-Item -LiteralPath $linkedSource -Force }
    # Поддельный архиватор создаёт непубликуемый временный файл и проваливает проверку t.
    $failedTool = Join-Path $testRoot 'failed-test-sevenzip.ps1'
    Assert-FixturePath $failedTool
    $mock = @'
if ($args[0] -eq 'a') {
    $archivePath = [IO.Path]::GetFullPath($args[-2])
    $allowedRoot = $PSScriptRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $archivePath.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($archivePath) -notmatch '^[0-9a-f]{32}\.7z$') { throw 'Unsafe mock archive path.' }
    [IO.File]::WriteAllBytes($archivePath, [byte[]]@(0))
    exit 0
}
exit 2
'@
    [IO.File]::WriteAllText($failedTool, $mock, [Text.UTF8Encoding]::new($false))
    $failedArchive = Join-Path $testRoot 'отказ архиватора.7z'
    Assert-PackFailure 'ошибка проверки архива' '7-Zip t завершился с кодом 2' @{
        StageOnly = $false; OutputPath = $failedArchive; SevenZipPath = $failedTool
    }
    Assert-Policy (-not (Test-Path -LiteralPath $failedArchive)) 'Ошибка t опубликовала архив.'
    Assert-Policy (@(Get-ChildItem -LiteralPath $testRoot -File -Filter '*.7z').Count -eq 1) 'Временный архив после отказа t остался.'
    Write-Host "OK: $checks проверок политики; независимые fixtures, отказы и очистка без Maven и финального архива."
} finally {
    Assert-FixturePath $testRoot
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}
