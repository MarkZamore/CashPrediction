<#
.SYNOPSIS
Проверяет настоящий Prepare workflow на локальных Git-моках без корневого CHANGELOG.
.DESCRIPTION
Не запускает Git, Maven, Java, GUI, сеть или publication; workflow только читается.
Фикстуры сохраняются в уникальном Temp, переменные окружения и LASTEXITCODE восстанавливаются.
#>
#requires -Version 7.0
param([string]$WorkflowPath = (Join-Path $PSScriptRoot '../workflows/release.yml'))
$ErrorActionPreference = 'Stop'
$workflow = [IO.File]::ReadAllText($WorkflowPath)
$section = [regex]::Match($workflow,
    '(?ms)^      - name: Prepare\r?\n.*?        run: \|\r?\n(?<body>.*?)(?=^      - name: )')
if (-not $section.Success) { throw 'RELEASE_PREPARE_FIXTURE_SECTION_MISSING' }
$prepareText = [regex]::Replace($section.Groups['body'].Value, '(?m)^          ', '')
$tokens = $null; $parseErrors = $null
$null = [Management.Automation.Language.Parser]::ParseInput($prepareText, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'RELEASE_PREPARE_FIXTURE_SYNTAX' }
$prepare = [scriptblock]::Create($prepareText)

# Полностью перекрывает executable Git и запрещает любые непредусмотренные команды.
function git {
    $command = $args -join ' '
    $script:prepareCalls.Add($command)
    if ($command -ceq 'rev-list --count HEAD') {
        $global:LASTEXITCODE = $script:countExit
        return '23'
    }
    if ($command -ceq 'log -1 --format=%s HEAD') {
        $global:LASTEXITCODE = $script:noteExit
        return $script:noteText
    }
    throw "RELEASE_PREPARE_FIXTURE_UNEXPECTED_GIT: $command"
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('cp-release-prepare-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixtureRoot)
$savedLocation = Get-Location
$savedExitCode = $global:LASTEXITCODE
$savedEnvironment = @{}
foreach ($name in 'GITHUB_REF', 'GITHUB_ENV', 'GITHUB_WORKSPACE') {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
$script:checks = 0

# Исполняет реальный блок; отказ должен иметь точную причину и не оставлять частичный GITHUB_ENV.
function Test-PrepareCase {
    param([string]$Name, [AllowEmptyString()][string]$Note = '  Исправлено сохранение плана  ',
        [int]$NoteExit = 0, [int]$CountExit = 0,
        [string]$Ref = 'refs/heads/main', [string]$Failure = '',
        [string[]]$Calls = @('rev-list --count HEAD', 'log -1 --format=%s HEAD'))
    $workspace = Join-Path $fixtureRoot ([guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($workspace)
    Set-Location -LiteralPath $workspace
    $env:GITHUB_WORKSPACE = $workspace
    $env:GITHUB_REF = $Ref
    $env:GITHUB_ENV = Join-Path $workspace 'github-env.txt'
    foreach ($removed in 'README.md', 'CHANGELOG.md', 'CLAUDE.md', 'AGENTS.md') {
        if (Test-Path -LiteralPath (Join-Path $workspace $removed)) {
            throw "RELEASE_PREPARE_FIXTURE_DELETED_FILE_PRESENT: $removed"
        }
    }
    $script:noteText = $Note; $script:noteExit = $NoteExit; $script:countExit = $CountExit
    $script:prepareCalls = [Collections.Generic.List[string]]::new()
    # Проверяет, что успешные Git-моки не наследуют stale native exit.
    $global:LASTEXITCODE = 128
    $problem = $null
    try { & $prepare | Out-Null } catch { $problem = $_.Exception.Message }
    if (($script:prepareCalls -join '|') -cne ($Calls -join '|')) {
        throw "RELEASE_PREPARE_FIXTURE_CALLS: ${Name}: $($script:prepareCalls -join '|')"
    }
    if ($Failure) {
        if ($problem -cne $Failure) { throw "RELEASE_PREPARE_FIXTURE_FAILURE: ${Name}: $problem" }
        if (Test-Path -LiteralPath $env:GITHUB_ENV) { throw "RELEASE_PREPARE_FIXTURE_PARTIAL_ENV: $Name" }
    } else {
        if ($null -ne $problem) { throw "RELEASE_PREPARE_FIXTURE_UNEXPECTED_FAILURE: ${Name}: $problem" }
        $actual = @([IO.File]::ReadAllLines($env:GITHUB_ENV))
        $expected = @('RELEASE_NUMBER=23', ('RELEASE_NOTE=' + $Note.Trim()))
        if (($actual -join '|') -cne ($expected -join '|')) {
            throw "RELEASE_PREPARE_FIXTURE_ENV: ${Name}: $($actual -join '|')"
        }
    }
    $script:checks++
    Write-Output "PASS: $Name"
}

try {
    Test-PrepareCase 'git subject succeeds without README/CHANGELOG/CLAUDE/AGENTS'
    Test-PrepareCase 'empty subject rejected' -Note '' -Failure 'RELEASE_GIT_NOTE_FAILED'
    Test-PrepareCase 'whitespace subject rejected' -Note " `t " -Failure 'RELEASE_GIT_NOTE_FAILED'
    Test-PrepareCase 'Git note failure rejects nonblank stdout' -Note 'not trustworthy' -NoteExit 128 -Failure 'RELEASE_GIT_NOTE_FAILED'
    Test-PrepareCase 'Git count failure stops before note' -CountExit 128 -Failure 'RELEASE_GIT_COUNT_FAILED' -Calls @('rev-list --count HEAD')
    Test-PrepareCase 'non-main stops before Git or environment writes' -Ref 'refs/heads/feature' -Failure 'RELEASE_MAIN_ONLY' -Calls @()
    Write-Output "Release Prepare: PASS ($script:checks focused cases; mocks only; fixtures preserved at $fixtureRoot)"
} finally {
    Set-Location -LiteralPath $savedLocation.Path
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name])
    }
    $global:LASTEXITCODE = $savedExitCode
}
