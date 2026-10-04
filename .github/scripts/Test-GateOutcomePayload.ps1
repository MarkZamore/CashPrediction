# Проверяет реальный predicate Invoke-UiGates без исполнения Maven или top-level скрипта.
$ErrorActionPreference = 'Stop'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Invoke-UiGates.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Ошибка синтаксиса gate-скрипта.' }
$predicates = @($ast.FindAll({ param($node)
    $node -is [System.Management.Automation.Language.ScriptBlockExpressionAst] -and
        $node.Extent.Text -match "SelectNodes\('failure\|error\|skipped'\)"
}, $true))
if ($predicates.Count -ne 1) { throw 'Не найден единственный настоящий XML outcome predicate.' }
$text = $predicates[0].Extent.Text.Trim()
$predicate = [scriptblock]::Create($text.Substring(1, $text.Length - 2))
foreach ($name in 'failure','error','skipped') {
    [xml]$xml = "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase name='actual'><$name/></testcase></testsuite>"
    if (@($xml.testsuite.testcase | Where-Object $predicate).Count -ne 1) { throw 'Пустой error/failure/skipped скрыт нулевым счётчиком.' }
}
[xml]$clean = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="actual"/></testsuite>'
if (@($clean.testsuite.testcase | Where-Object $predicate).Count) { throw 'Чистый testcase ошибочно отклонён.' }
Write-Output 'Gate outcome payload: PASS (3 negative and 1 positive fixtures; actual predicate, no Maven)'
