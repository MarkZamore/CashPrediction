# Проверяет настоящую функцию gate с подставным Maven; сборки и GUI не запускаются.
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Invoke-UiGates.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Ошибка синтаксиса gate-скрипта.' }
$functions = @($ast.FindAll({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-ActualTest'
}, $true))
if ($functions.Count -ne 1) { throw 'Не найдена единственная настоящая функция gate.' }
# Загружается только функция: верхний уровень с jar, Maven и UI-матрицей не выполняется.
Invoke-Expression $functions[0].Extent.Text
. (Join-Path $PSScriptRoot 'Protect-GateText.ps1')

# Локальная функция перехватывает каждый вызов Maven и пишет только временные фикстуры.
function mvn {
    $script:mockCalls++
    $reportArgument = @($args | Where-Object { "$_" -like '-Dparity.reports.directory=*' })
    if ($reportArgument.Count -ne 1) { throw 'Не передан изолированный каталог отчётов.' }
    $reportDirectory = "$($reportArgument[0])".Substring('-Dparity.reports.directory='.Length)
    if (@(Get-ChildItem -LiteralPath $reportDirectory).Count) { throw 'Maven получил непустой каталог.' }
    for ($i = 0; $i -lt $script:fixture.Reports.Count; $i++) {
        Set-Content -LiteralPath (Join-Path $reportDirectory "TEST-fixture-$i.xml") -Value $script:fixture.Reports[$i] -Encoding utf8
    }
    Write-Output $script:sensitive
    $script:LASTEXITCODE = $script:fixture.ExitCode
    if ($script:fixture.Throw) { throw 'Подставная ошибка запуска Maven.' }
}

$Mode = 'UI'; $Browser = ''
$script:secretToken = 'b' * 48
$script:secretKey = 'a' * 64
$script:sensitive = "http://127.0.0.1/?t=$script:secretToken&amp;tab=table Authorization: Bearer $script:secretToken key=$script:secretKey ordinary=Ctrl+S"
$clean = "<testsuite name='fixture.GateFixture' tests='1' failures='0' errors='0' skipped='0'><testcase name='actual'/><system-out>$script:sensitive</system-out></testsuite>"
$skipped = $clean.Replace("skipped='0'", "skipped='1'").Replace("<testcase name='actual'/>", "<testcase name='actual'><skipped/></testcase>")
$fixtures = @(
    @{ Name = 'success'; Reports = @($clean); ExitCode = 0; Reject = ''; Count = 1; Method = 'actual' },
    @{ Name = 'maven-failure'; Reports = @($clean.Replace("failures='0'", "failures='1'").Replace("<testcase name='actual'/>", "<testcase name='actual'><failure>Assertion failed</failure></testcase>")); ExitCode = 1; Reject = 'Gate failed'; Count = 1; Method = 'actual' },
    @{ Name = 'maven-failure-missing'; Reports = @(); ExitCode = 1; Reject = 'Gate failed'; Count = 1; Method = 'actual' },
    @{ Name = 'maven-throws'; Reports = @($clean); ExitCode = 0; Throw = $true; Reject = 'Подставная ошибка'; Count = 1; Method = 'actual' },
    @{ Name = 'skipped'; Reports = @($skipped); ExitCode = 0; Reject = 'Invalid actual suite'; Count = 1; Method = 'actual' },
    @{ Name = 'malformed'; Reports = @("<testsuite><system-out>$script:sensitive</system-out>"); ExitCode = 0; Reject = '*'; Count = 1; Method = 'actual' },
    @{ Name = 'wrong-suite'; Reports = @($clean.Replace('fixture.GateFixture', 'fixture.Other')); ExitCode = 0; Reject = 'Invalid actual suite'; Count = 1; Method = 'actual' },
    @{ Name = 'wrong-count'; Reports = @($clean); ExitCode = 0; Reject = 'Unexpected case count'; Count = 2; Method = 'actual' },
    @{ Name = 'wrong-method'; Reports = @($clean); ExitCode = 0; Reject = 'Required actual method absent'; Count = 1; Method = 'missing' },
    @{ Name = 'counter-mismatch'; Reports = @($clean.Replace("tests='1'", "tests='2'")); ExitCode = 0; Reject = 'Testcase payload contradicts'; Count = 1; Method = 'actual' },
    @{ Name = 'multiple'; Reports = @($clean, $skipped); ExitCode = 0; Reject = 'Expected one fresh XML suite'; Count = 1; Method = 'actual' },
    @{ Name = 'missing'; Reports = @(); ExitCode = 0; Reject = 'Expected one fresh XML suite'; Count = 1; Method = 'actual' },
    @{ Name = 'stale'; Reports = @(); ExitCode = 0; Reject = '*'; Count = 1; Method = 'actual'; Stale = $true }
)
foreach ($outcome in 'failure','error','skipped') {
    $fixtures += @{ Name = "hidden-$outcome"; Reports = @($clean.Replace("<testcase name='actual'/>", "<testcase name='actual'><$outcome/></testcase>")); ExitCode = 0; Reject = 'Testcase payload contradicts'; Count = 1; Method = 'actual' }
}

$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureRoot = Join-Path $temporaryRoot ('cashprediction-gate-export-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
try {
    foreach ($script:fixture in $fixtures) {
        $run = Join-Path $fixtureRoot $script:fixture.Name
        $build = Join-Path $run 'actual'
        $publish = Join-Path $run 'upload'
        New-Item -ItemType Directory -Path $publish | Out-Null
        $script:step = 0; $script:mockCalls = 0
        # Старый XML вне текущего каталога не должен заменять отсутствующий свежий отчёт.
        $oldReports = Join-Path $run 'reports-00-GateFixture'
        New-Item -ItemType Directory -Path $oldReports | Out-Null
        Set-Content -LiteralPath (Join-Path $oldReports 'TEST-old.xml') -Value $clean -Encoding utf8
        if ($script:fixture.Stale) {
            $existing = Join-Path $run 'reports-01-GateFixture'
            New-Item -ItemType Directory -Path $existing | Out-Null
            Set-Content -LiteralPath (Join-Path $existing 'TEST-old.xml') -Value $clean -Encoding utf8
        }
        $caught = $null
        try { Invoke-ActualTest 'GateFixture' $script:fixture.Count $script:fixture.Method }
        catch { $caught = $_ }
        if ($script:fixture.Reject) {
            if ($null -eq $caught) { throw "Ошибочный gate принят: $($script:fixture.Name)" }
            if ($script:fixture.Reject -ne '*' -and $caught.Exception.Message -notlike "*$($script:fixture.Reject)*") {
                throw "Неожиданная причина отказа: $($script:fixture.Name): $caught"
            }
        } elseif ($null -ne $caught) { throw "Чистый gate отклонён: $caught" }
        $expectedCalls = if ($script:fixture.Stale) { 0 } else { 1 }
        if ($script:mockCalls -ne $expectedCalls) { throw 'Неверное число вызовов подставного Maven.' }
        $exports = @(Get-ChildItem -LiteralPath $publish -File -Filter '*.xml' | Sort-Object Name)
        if ($exports.Count -ne $script:fixture.Reports.Count) { throw "Потеряны свежие XML: $($script:fixture.Name)" }
        for ($i = 0; $i -lt $exports.Count; $i++) {
            $actual = Get-Content -LiteralPath $exports[$i].FullName -Raw
            $expected = Protect-GateText $script:fixture.Reports[$i]
            if ($actual.TrimEnd("`r", "`n") -cne $expected) { throw 'Экспорт изменил полезный XML payload.' }
            if ($actual.Contains($script:secretToken) -or $actual.Contains($script:secretKey) -or
                -not $actual.Contains('[redacted]') -or -not $actual.Contains('ordinary=Ctrl+S')) {
                throw 'Секреты XML не удалены или обычный текст потерян.'
            }
        }
        if ($exports.Count -eq 1 -and $exports[0].Name -ne 'GateFixture.xml') { throw 'Имя одиночного отчёта изменилось.' }
        $log = Join-Path $publish 'GateFixture.log'
        if ($script:mockCalls) {
            $logText = Get-Content -LiteralPath $log -Raw
            if ($logText.Contains($script:secretToken) -or $logText.Contains($script:secretKey) -or -not $logText.Contains('[redacted]')) {
                throw 'Секреты консоли не удалены.'
            }
        }
    }
} finally {
    # Удаляется только созданный этой проверкой временный каталог с точным путём.
    $resolvedRoot = [IO.Path]::GetFullPath($fixtureRoot)
    if ([IO.Path]::GetDirectoryName($resolvedRoot).TrimEnd('\','/') -ne $temporaryRoot.TrimEnd('\','/') -or
        [IO.Path]::GetFileName($resolvedRoot) -notlike 'cashprediction-gate-export-*') {
        throw 'Удаление вне временного каталога запрещено.'
    }
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
}
Write-Output "Gate report export: PASS ($($fixtures.Count) mocked fixtures; no Maven or GUI)"
