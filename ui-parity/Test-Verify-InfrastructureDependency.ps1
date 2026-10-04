# Изолированная проверка поиска JUnit: основной скрипт, Java и Maven не запускаются.
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'Verify-Infrastructure.ps1'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Infrastructure script has syntax errors.' }
$definitions = @($ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Resolve-InfrastructureJunit'
}, $false))
if ($definitions.Count -ne 1) { throw 'Expected one JUnit resolver.' }
. ([scriptblock]::Create($definitions[0].Extent.Text))

# Проверяет условие без внешнего тестового фреймворка.
function Assert-Dependency([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

# Проверяет понятный отказ, не запуская сборку.
function Assert-DependencyFailure([scriptblock]$Action, [string]$Expected) {
    $message = ''
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    Assert-Dependency ($message.Contains($Expected)) "Expected failure containing '$Expected'; got '$message'."
}

# Выполняет только файловые команды основного скрипта на изолированных заглушках.
function Test-InfrastructureBracketPath([string]$Fixture, [string]$PSScriptRoot) {
    $taskRoot = $Fixture
    $CoreClasses = Join-Path $Fixture 'classes [1]'
    $taskMain = Join-Path $Fixture 'copied-core'
    $taskTests = Join-Path $Fixture 'copied-tests'
    foreach ($directory in @($CoreClasses, $taskMain, $taskTests,
        (Join-Path $Fixture 'core/src/main/java'),
        (Join-Path $Fixture 'core/src/main/resources'),
        (Join-Path $Fixture 'core/src/main/resources-filtered'),
        (Join-Path $PSScriptRoot 'src/test/java'),
        (Join-Path $PSScriptRoot 'src/test/resources'))) {
        $null = [IO.Directory]::CreateDirectory($directory)
    }
    foreach ($entry in @(@($CoreClasses, 'class.txt'),
        @((Join-Path $Fixture 'core/src/main/java'), 'Core.java'),
        @((Join-Path $Fixture 'core/src/main/resources'), 'resource.txt'),
        @((Join-Path $Fixture 'core/src/main/resources-filtered'), 'filtered.txt'),
        @((Join-Path $PSScriptRoot 'src/test/java'), 'Parity.java'),
        @((Join-Path $PSScriptRoot 'src/test/resources'), 'test-resource.txt'))) {
        [IO.File]::WriteAllText((Join-Path $entry[0] $entry[1]), 'fixture')
    }
    $null = [IO.Directory]::CreateDirectory((Join-Path $Fixture 'classes 1'))
    [IO.File]::WriteAllText((Join-Path $Fixture 'classes 1/decoy.txt'), 'wrong directory')
    # Берём реальные команды после объявления taskRoot, исключая компилятор и JVM.
    $mainStart = @($ast.EndBlock.Statements | Where-Object { $_.Extent.Text -like '$taskRoot =*' })[0].Extent.StartOffset
    $commands = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.CommandAst] -and
        $node.GetCommandName() -in @('Copy-Item', 'Copy-InfrastructureContents', 'Get-ChildItem', 'Test-Path') -and
        $node.Extent.StartOffset -gt $mainStart
    }, $true))
    Assert-Dependency ($commands.Count -eq 7) 'Expected seven infrastructure filesystem operations.'
    foreach ($command in $commands) {
        # У динамического блока нет собственного файла: передаём корень стенда явно.
        $operation = [scriptblock]::Create('param([string]$PSScriptRoot)' + "`n" + $command.Extent.Text)
        $result = @(& $operation -PSScriptRoot $PSScriptRoot)
        if ($command.GetCommandName() -eq 'Test-Path') {
            Assert-Dependency ($result.Count -eq 1 -and $result[0] -eq $true) 'bracketPath: filtered resources must exist.'
        } elseif ($command.GetCommandName() -eq 'Get-ChildItem') {
            Assert-Dependency ($result.Count -eq 1 -and $result[0].Extension -eq '.java') 'bracketPath: source enumeration must find the literal directory.'
        }
    }
    foreach ($name in @('class.txt', 'resource.txt', 'filtered.txt')) {
        Assert-Dependency (Test-Path -LiteralPath (Join-Path $taskMain $name)) "bracketPath: missing copied $name."
    }
    Assert-Dependency (Test-Path -LiteralPath (Join-Path $taskTests 'test-resource.txt')) 'bracketPath: missing test resource.'
    Assert-Dependency (-not (Test-Path -LiteralPath (Join-Path $taskMain 'decoy.txt'))) 'bracketPath: copied a wildcard-matching sibling.'
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('CashPrediction-junit-discovery-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $fixtureRoot
try {
    $repository = Join-Path $fixtureRoot 'cache with spaces [1]'
    $jarPath = Join-Path $repository 'org/junit/platform/junit-platform-console-standalone/1.14.4/junit-platform-console-standalone-1.14.4.jar'
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($jarPath))
    # Пустая заглушка проверяет только поиск пути, а не содержимое JAR.
    [IO.File]::WriteAllBytes($jarPath, [byte[]]@())
    $pomPath = Join-Path $fixtureRoot 'pom.xml'
    [IO.File]::WriteAllText($pomPath, '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties><junit.version>5.14.4</junit.version></properties></project>')
    Assert-Dependency ((Resolve-InfrastructureJunit -Root $fixtureRoot -Repository $repository) -eq $jarPath) 'Root POM version must resolve the cache JAR.'
    Assert-Dependency ((Resolve-InfrastructureJunit -Root 'missing-root' -Jar $jarPath) -eq $jarPath) 'Explicit JAR must bypass POM discovery.'
    Assert-Dependency ((Resolve-InfrastructureJunit -Root 'missing-root' -Repository $repository -PlatformVersion '1.14.4') -eq $jarPath) 'Explicit version must bypass POM discovery.'
    # Собираем оба результата, чтобы до исправления были видны обе независимые ошибки.
    $pathFailures = @()
    try {
        $relativeRoot = Join-Path $fixtureRoot 'relative root [1]'
        $null = [IO.Directory]::CreateDirectory($relativeRoot)
        Copy-Item -LiteralPath $pomPath -Destination (Join-Path $relativeRoot 'pom.xml')
        Push-Location -LiteralPath $fixtureRoot
        try {
            Assert-Dependency ((Resolve-InfrastructureJunit -Root 'relative root [1]' -Repository $repository) -eq $jarPath) 'relativeRoot: POM must resolve relative to PowerShell location.'
        } finally { Pop-Location }
        Write-Output 'PASS: relativeRoot'
    } catch { $pathFailures += "relativeRoot: $($_.Exception.Message)" }
    try {
        $copyDefinition = @($ast.FindAll({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -eq 'Copy-InfrastructureContents'
        }, $false))
        if ($copyDefinition.Count) { . ([scriptblock]::Create($copyDefinition[0].Extent.Text)) }
        $bracketRoot = Join-Path $fixtureRoot 'checkout [1]'
        Test-InfrastructureBracketPath -Fixture $bracketRoot -PSScriptRoot (Join-Path $bracketRoot 'ui-parity')
        Write-Output 'PASS: bracketPath'
    } catch { $pathFailures += "bracketPath: $($_.Exception.Message)" }
    if ($pathFailures.Count) { throw ($pathFailures -join "`n") }
    Assert-DependencyFailure { Resolve-InfrastructureJunit -Root $fixtureRoot -Repository (Join-Path $fixtureRoot 'empty-cache') } '-JunitJar'
    Assert-DependencyFailure { Resolve-InfrastructureJunit -Jar $fixtureRoot } 'JUnit console JAR not found'
    Assert-DependencyFailure { Resolve-InfrastructureJunit -Root $fixtureRoot -Repository $repository -PlatformVersion '../escape' } 'Invalid -JunitPlatformVersion'
    [IO.File]::WriteAllText($pomPath, '<project/>')
    Assert-DependencyFailure { Resolve-InfrastructureJunit -Root $fixtureRoot -Repository $repository } 'Cannot determine JUnit Platform version'
    [IO.File]::WriteAllText($pomPath, '<!DOCTYPE project [<!ENTITY version "5.14.4">]><project/>')
    Assert-DependencyFailure { Resolve-InfrastructureJunit -Root $fixtureRoot -Repository $repository } 'Cannot determine JUnit Platform version'

    # Пользовательский кэш моделируется подменой только системного API, без записи в настоящий профиль.
    $resolverText = $definitions[0].Extent.Text.Replace(
        '[Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)', '$mockProfile')
    Assert-Dependency ($resolverText -ne $definitions[0].Extent.Text) 'User profile lookup must be mockable.'
    . ([scriptblock]::Create($resolverText))
    $mockProfile = Join-Path $fixtureRoot 'recipient profile'
    $userJar = Join-Path $mockProfile '.m2/repository/org/junit/platform/junit-platform-console-standalone/1.14.4/junit-platform-console-standalone-1.14.4.jar'
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($userJar))
    [IO.File]::WriteAllBytes($userJar, [byte[]]@())
    Assert-Dependency ((Resolve-InfrastructureJunit -PlatformVersion '1.14.4') -eq $userJar) 'Default cache must belong to the current user.'
    $mockProfile = ''
    Assert-DependencyFailure { Resolve-InfrastructureJunit -PlatformVersion '1.14.4' } 'Cannot locate user profile'
    Write-Output 'PASS: isolated JUnit dependency discovery; no Maven, Java or compilation executed.'
} finally {
    # Удаляется только уникальный временный стенд этого теста после проверки абсолютного пути.
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $resolvedFixture.StartsWith($temporaryBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolvedFixture) -notmatch '^CashPrediction-junit-discovery-[0-9a-f]{32}$') {
        throw 'Unsafe fixture cleanup path.'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
