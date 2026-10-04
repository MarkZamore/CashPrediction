<#
.SYNOPSIS
Выполняет автоматическую часть S6 на чистом checkout указанного коммита.
.DESCRIPTION
Не создаёт checkout, не переключает ветку и не удаляет пользовательские данные.
Не запускается до интеграции S5: Test-Portable обязан поддерживать -Parity.
Проверки идут последовательно. Зелёная квитанция не заменяет независимое
сравнение сценариев, аудит ТЗ и ручные проверки восстановления из плана S6.
#>
#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit,
    [string]$Repository = (Join-Path $PSScriptRoot '../..'),
    [switch]$PreflightOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Get-Item -LiteralPath $Repository).FullName
$verificationLock = $null
$lockOwned = $false
Push-Location -LiteralPath $root
try {
    $head = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $head -ne $ExpectedCommit) { throw 'Checkout commit differs from the requested S6 candidate.' }
    $dirty = @(& git status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) { throw 'S6 requires a clean isolated checkout; current files are preserved.' }
    foreach ($command in 'git','mvn','java') { $null = Get-Command $command -ErrorAction Stop }
    $portableScript = Join-Path $root '.github/scripts/Test-Portable.ps1'
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($portableScript, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0 -or $null -eq $ast.ParamBlock -or
        @($ast.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'Parity' }).Count -ne 1) {
        throw 'S5 prerequisite missing: Test-Portable.ps1 must implement -Parity, not a smoke-only substitution.'
    }
    if (Test-Path -LiteralPath (Join-Path $root 'dist/target/dist/CashPrediction/CashMemory')) {
        throw 'Existing image contains CashMemory; preserve it before running the S6 build.'
    }
    if ($PreflightOnly) { Write-Host "S6 preflight ready for $head; no verification has run."; return }
    # Два S6 runner не могут одновременно управлять Maven и общим рабочим столом Windows.
    $verificationLock = [Threading.Mutex]::new($false, 'Local\CashPrediction.S6Verification')
    try { $lockOwned = $verificationLock.WaitOne(0) }
    catch [Threading.AbandonedMutexException] {
        $lockOwned = $true
        throw 'Previous S6 runner was interrupted; inspect its owned processes before retrying.'
    }
    if (-not $lockOwned) { throw 'Another S6 verification owns Maven and the desktop.' }
    $run = Join-Path $root ('target/s6/' + [guid]::NewGuid().ToString())
    $null = New-Item -ItemType Directory -Path $run
    . (Join-Path $root '.github/scripts/Protect-GateText.ps1')
    $steps = [Collections.Generic.List[object]]::new()

    # Выделенный процесс не даёт PowerShell-ошибке скрыться за последующей успешной командой.
    function Invoke-S6Step([string]$Name, [string]$Executable, [string[]]$Arguments) {
        $started = [DateTimeOffset]::UtcNow
        & $Executable @Arguments 2>&1 | ForEach-Object { Protect-GateText "$_" } |
            Tee-Object -FilePath (Join-Path $run ($Name + '.log')) | Out-Host
        $code = $LASTEXITCODE
        $steps.Add([ordered]@{ name = $Name; startedAt = $started.ToString('O');
            finishedAt = [DateTimeOffset]::UtcNow.ToString('O'); exitCode = $code })
        if ($code -ne 0) { throw "S6 automatic step failed: $Name (exit $code)." }
    }
    # Нулевой Maven exit не доказывает исполнение opt-in: проверяем свежие testcase, не только counters.
    function Assert-S6Suite([string]$Reports, [string]$Class, [int]$Count, [string]$Method) {
        $files = @(Get-ChildItem -LiteralPath $Reports -File -Filter 'TEST-*.xml')
        if ($files.Count -ne 1) { throw "Missing unique fresh suite: $Class" }
        [xml]$xml = Get-Content -LiteralPath $files[0].FullName -Raw
        $suite = $xml.testsuite
        $cases = @($suite.SelectNodes('testcase'))
        if ($suite.name -notmatch "\.$([regex]::Escape($Class))$" -or [int]$suite.tests -ne $Count -or
            $cases.Count -ne $Count -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or
            [int]$suite.skipped -ne 0 -or @($cases | Where-Object { $_.SelectNodes('failure|error|skipped').Count }).Count -gt 0 -or
            @($cases | Where-Object { $_.name -eq $Method }).Count -ne 1) {
            throw "Incomplete actual suite: $Class"
        }
    }
    $pwsh = Join-Path $PSHOME 'pwsh.exe'
    try {
        Invoke-S6Step 'install' 'mvn' @('-B','-ntp','install')
        Invoke-S6Step 'strict-ui' $pwsh @('-NoProfile','-File','.github/scripts/Invoke-UiGates.ps1','-Mode','UI',
            '-EvidenceRoot',(Join-Path $run 'ui-gates'))
        Invoke-S6Step 'strict-e2e' $pwsh @('-NoProfile','-File','.github/scripts/Invoke-UiGates.ps1','-Mode','E2E',
            '-EvidenceRoot',(Join-Path $run 'e2e-gates'))
        foreach ($profile in 'ui-tests','e2e') {
            Invoke-S6Step ($profile + '-lifecycle') 'mvn' @('-B','-ntp',"-P$profile",'verify')
        }
        # S6 проверяет все dump/checkpoint всех 18 сценариев, а не четыре первых пробы S4.
        $visualReports = Join-Path $run 'reports-all-visual'
        $null = New-Item -ItemType Directory -Path $visualReports
        Invoke-S6Step 'all-checkpoint-visual' 'mvn' @('-B','-ntp','-Pui-tests','-pl','ui-parity','test',
            '-Dtest=VisualParityTest','-Dparity.realClients=true','-Dparity.visual=true',
            '-Dparity.clients=fx,swing,web','-Dparity.visual.scenarios=all','-Dparity.visual.checkpoints=all',
            "-Dparity.reports.directory=$visualReports","-Dparity.build.directory=$run/all-visual")
        Assert-S6Suite $visualReports 'VisualParityTest' 3 'realScreenshots'
        $performance = Join-Path $run 'performance'
        $performanceReports = Join-Path $run 'reports-performance'
        $null = New-Item -ItemType Directory -Path $performance
        $null = New-Item -ItemType Directory -Path $performanceReports
        Invoke-S6Step 'web-virtual-performance' 'mvn' @('-B','-ntp','-Pui-tests','-pl','ui-parity','test',
            '-Dtest=WebVirtualTablePerformanceTest','-Dparity.performance=true',
            "-Dparity.performance.output=$performance","-Dparity.reports.directory=$performanceReports",
            "-Dparity.performance.coreJar=$root/core/target/cashprediction-core-1.0.0.jar",
            "-Dparity.performance.webJar=$root/web/target/cashprediction-web-1.0.0.jar")
        Assert-S6Suite $performanceReports 'WebVirtualTablePerformanceTest' 1 'actualColdPagesAndNarrowViewport'
        Invoke-S6Step 'dist' 'mvn' @('-B','-ntp','-Pdist','-pl','dist','package')
        Invoke-S6Step 'portable-parity' $pwsh @('-NoProfile','-File','.github/scripts/Test-Portable.ps1',
            '-PortableDir',(Join-Path $root 'dist/target/dist/CashPrediction'),'-Parity',
            '-EvidenceRoot',(Join-Path $run 'portable'))
        Invoke-S6Step 'source-rebuild' $pwsh @('-NoProfile','-File','dist/scripts/Test-Pack-Source.ps1','-VerifyBuild')
        if ((& git rev-parse HEAD).Trim() -ne $head -or @(& git status --porcelain --untracked-files=normal).Count -ne 0) {
            throw 'Candidate source changed during S6 verification.'
        }
        [ordered]@{ schemaVersion = 1; commit = $head; status = 'AUTOMATED_ONLY';
            independentSignoff = $false; steps = @($steps.ToArray()) } | ConvertTo-Json -Depth 6 |
            Set-Content -LiteralPath (Join-Path $run 'automatic-results.json') -Encoding utf8
        Write-Host "S6 automatic evidence: $run. Independent sign-off is still required."
    } catch {
        [ordered]@{ schemaVersion = 1; commit = $head; status = 'FAILED';
            independentSignoff = $false; steps = @($steps.ToArray()) } | ConvertTo-Json -Depth 6 |
            Set-Content -LiteralPath (Join-Path $run 'automatic-results.json') -Encoding utf8
        throw
    }
} finally {
    if ($lockOwned) { $verificationLock.ReleaseMutex() }
    if ($null -ne $verificationLock) { $verificationLock.Dispose() }
    Pop-Location
}
