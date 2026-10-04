<# Проверяет отказ S6 runner до сборки: неверный commit, dirty checkout и отсутствующий portable parity. #>
#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$runner = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'Invoke-S6Verification.ps1'))
$temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$fixture = Join-Path $temporary ('cp-s6-readiness-' + [guid]::NewGuid().ToString())
$null = New-Item -ItemType Directory -Path $fixture
$fixture = (Get-Item -LiteralPath $fixture).FullName
$portable = Join-Path $fixture '.github/scripts/Test-Portable.ps1'
$null = New-Item -ItemType Directory -Path (Split-Path $portable)
$passed = 0

# Команды git меняют только заново созданный тестовый репозиторий без remote.
function Invoke-FixtureGit([string[]]$Arguments) {
    & git -C $fixture @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Fixture git command failed.' }
}

# Проверяет именно ожидаемую причину отказа; иной сбой не считается успехом.
function Assert-S6Rejected([string]$Commit, [string]$Reason) {
    $rejected = $false
    try { & $runner -Repository $fixture -ExpectedCommit $Commit -PreflightOnly }
    catch {
        if ($_.Exception.Message -notlike "*$Reason*") { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw "S6 accepted invalid prerequisite: $Reason" }
    $script:passed++
}
try {
    Invoke-FixtureGit @('init','--quiet')
    Invoke-FixtureGit @('config','user.name','S6 fixture')
    Invoke-FixtureGit @('config','user.email','fixture@invalid.test')
    [IO.File]::WriteAllText($portable, 'param([string]$PortableDir, [switch]$Parity)')
    Invoke-FixtureGit @('add','--all')
    Invoke-FixtureGit @('commit','--quiet','-m','Fixture baseline')
    $commit = (& git -C $fixture rev-parse HEAD).Trim()
    & $runner -Repository $fixture -ExpectedCommit $commit -PreflightOnly
    $passed++
    Assert-S6Rejected ('0' * 40) 'commit differs'
    [IO.File]::WriteAllText((Join-Path $fixture 'unexpected.txt'), 'owned fixture')
    Assert-S6Rejected $commit 'clean isolated checkout'
    Remove-Item -LiteralPath (Join-Path $fixture 'unexpected.txt')
    [IO.File]::WriteAllText($portable, 'param([string]$PortableDir)')
    Invoke-FixtureGit @('add','--all')
    Invoke-FixtureGit @('commit','--quiet','-m','Smoke only')
    $commit = (& git -C $fixture rev-parse HEAD).Trim()
    Assert-S6Rejected $commit 'S5 prerequisite missing'
    [IO.File]::WriteAllText($portable, 'param([switch]$Parity')
    Invoke-FixtureGit @('add','--all')
    Invoke-FixtureGit @('commit','--quiet','-m','Malformed portable script')
    $commit = (& git -C $fixture rev-parse HEAD).Trim()
    Assert-S6Rejected $commit 'S5 prerequisite missing'
    if (Test-Path -LiteralPath (Join-Path $fixture 'target')) { throw 'Preflight unexpectedly ran verification.' }
    $passed++
    # Из доверенного runner извлекается только валидатор XML, без запуска его build-потока.
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($runner, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'S6 runner syntax errors.' }
    $validators = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-S6Suite'
    }, $true))
    if ($validators.Count -ne 1) { throw 'Expected exactly one actual-suite validator.' }
    Invoke-Expression $validators[0].Extent.Text
    $reports = Join-Path $fixture 'suite-fixtures'
    $null = New-Item -ItemType Directory -Path $reports
    $report = Join-Path $reports 'TEST-fixture.xml'
    $valid = '<testsuite name="fixture.RequiredSuite" tests="1" failures="0" errors="0" skipped="0"><testcase name="required" /></testsuite>'
    [IO.File]::WriteAllText($report, $valid)
    Assert-S6Suite $reports 'RequiredSuite' 1 'required'
    $passed++
    $mutations = @(
        $valid.Replace('fixture.RequiredSuite', 'fixture.WrongSuite'),
        $valid.Replace('tests="1"', 'tests="2"'),
        $valid.Replace('failures="0"', 'failures="1"'),
        $valid.Replace('errors="0"', 'errors="1"'),
        $valid.Replace('skipped="0"', 'skipped="1"'),
        $valid.Replace('name="required"', 'name="other"'),
        $valid.Replace('<testcase name="required" />', '<testcase name="required"><skipped /></testcase>'),
        $valid.Replace('<testcase name="required" />', '<testcase name="required"><failure /></testcase>'),
        $valid.Replace('<testcase name="required" />', '')
    )
    foreach ($mutation in $mutations) {
        [IO.File]::WriteAllText($report, $mutation)
        $rejected = $false
        try { Assert-S6Suite $reports 'RequiredSuite' 1 'required' }
        catch { if ($_.Exception.Message -notlike '*Incomplete actual suite*') { throw }; $rejected = $true }
        if (-not $rejected) { throw 'S6 accepted a fabricated successful XML result.' }
        $passed++
    }
    Remove-Item -LiteralPath $report
    $rejected = $false
    try { Assert-S6Suite $reports 'RequiredSuite' 1 'required' }
    catch { if ($_.Exception.Message -notlike '*Missing unique fresh suite*') { throw }; $rejected = $true }
    if (-not $rejected) { throw 'S6 accepted missing actual report.' }
    $passed++
    Write-Host "S6 readiness fixtures: PASS ($passed). No build, GUI, registry or network was used."
} finally {
    # Проверяем окончательный абсолютный путь непосредственно перед удалением своего fixture.
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ([IO.Path]::GetDirectoryName($resolved) -ne $temporary -or
        [IO.Path]::GetFileName($resolved) -notlike 'cp-s6-readiness-*' -or
        (Get-Item -LiteralPath $resolved).LinkType) { throw 'Refusing unsafe fixture cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
