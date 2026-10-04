<#
.SYNOPSIS
Проверяет исключение инструкций навыков из исходной поставки без упаковки и Maven.
.DESCRIPTION
Исполняет настоящие функции политики через AST. Все входы синтетические.
Receipt содержит pins, результаты и явный отказ от native/full acceptance.
#>
#requires -Version 7.0
[CmdletBinding()]
param(
    [string] $PolicyPath = (Join-Path $PSScriptRoot 'Pack-Source.ps1'),
    [Parameter(Mandatory)] [string] $ReceiptPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PolicyPath = [IO.Path]::GetFullPath($PolicyPath)
$receiptRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
$ReceiptPath = [IO.Path]::GetFullPath($ReceiptPath)
if (-not $ReceiptPath.StartsWith($receiptRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $ReceiptPath)) {
    throw 'AGENT_INSTRUCTIONS_RECEIPT_SCOPE'
}
for ($ancestor = [IO.Path]::GetDirectoryName($ReceiptPath); $ancestor;
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)) {
    if ((Test-Path -LiteralPath $ancestor) -and
        ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'AGENT_INSTRUCTIONS_LINK'
    }
}
$before = (Get-FileHash -LiteralPath $PolicyPath -Algorithm SHA256).Hash
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($PolicyPath, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'AGENT_INSTRUCTIONS_PARSE' }
foreach ($name in @('Test-ExcludedDirectory', 'Test-IncludedFile')) {
    $definitions = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
    }, $false))
    if ($definitions.Count -ne 1) { throw "AGENT_INSTRUCTIONS_FUNCTION: $name" }
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}
$cases = @(
    @{path='core/src/main/resources/SKILL.md'; included=$false},
    @{path='web/src/test/resources/nested/skill.MD'; included=$false},
    @{path='update-tool/src/main/resources-filtered/SKILL.markdown'; included=$false},
    @{path='ui-parity/src/test/resources/nested/SKILL.md'; included=$false},
    @{path='core/src/main/resources/skill-response.json'; included=$true},
    @{path='core/src/main/resources/SKILL.json'; included=$true},
    @{path='core/src/main/resources/SKILL.png'; included=$true},
    @{path='core/src/test/resources/manual-plan.md'; included=$true},
    @{path='core/src/main/resources/ru/cashprediction/core/ui/text/help-format_ru.md'; included=$true},
    @{path='docs/design/architecture.md'; included=$true},
    @{path='docs/design/techstack.md'; included=$true},
    @{path='docs/design/edge-cases.md'; included=$true},
    @{path='docs/design/db-schema.md'; included=$true},
    @{path='docs/design/linx.md'; included=$true},
    @{path='docs/design/ui-kit.md'; included=$true},
    @{path='docs/design/techstack.txt'; included=$false},
    @{path='docs/other/ui-kit.md'; included=$false},
    @{path='docs/design/extra.md'; included=$false},
    @{path='docs/ai/CurrentSprint.md'; included=$false},
    @{path='docs/AI/ContextDump.md'; included=$false},
    @{path='docs/ai/ChangeRequest.md'; included=$false},
    @{path='docs/ai/LegacyWarning.md'; included=$false},
    # Выходы Graphify - рабочий AI-контекст, не пользовательские resources.
    # Проверяем официальный nested output и альтернативный root graphify-out без изменения policy.
    @{path='docs/ai/graphify/project_graph.py'; included=$false},
    @{path='docs/ai/graphify/README.md'; included=$false},
    @{path='docs/ai/graphify/graphify-out/graph.json'; included=$false},
    @{path='docs/ai/graphify/graphify-out/graph.html'; included=$false},
    @{path='docs/ai/graphify/graphify-out/inventory.json'; included=$false},
    @{path='graphify-out/graph.json'; included=$false},
    @{path='graphify-out/graph.html'; included=$false},
    @{path='graphify-out/inventory.json'; included=$false},
    @{path='.codex/skills/graphify/SKILL.md'; included=$false},
    @{path='core/src/main/resources/nested/cUrReNtSpRiNt.MD'; included=$false},
    @{path='web/src/test/resources/nested/CONTEXTDUMP.markdown'; included=$false},
    @{path='update-tool/src/main/resources-filtered/nested/changerequest.txt'; included=$false},
    @{path='ui-parity/src/test/resources/nested/legacywarning.md'; included=$false},
    @{path='core/src/main/resources/current-sprint-data.md'; included=$true},
    @{path='core/src/test/resources/contextdump-response.json'; included=$true},
    @{path='core/src/test/resources/LICENSE.txt'; included=$true},
    @{path='core/src/test/resources/AGENTS.md'; included=$false}
)
$results = @($cases | ForEach-Object {
    $actual = [bool](Test-IncludedFile $_.path)
    [pscustomobject]@{path=$_.path; expectedIncluded=$_.included; actualIncluded=$actual;
        passed=($actual -eq $_.included)}
})
$after = (Get-FileHash -LiteralPath $PolicyPath -Algorithm SHA256).Hash
if ($before -cne $after) { throw 'AGENT_INSTRUCTIONS_INPUT_CHANGED' }
$failed = @($results | Where-Object { -not $_.passed }).Count
$receipt = [ordered]@{schema=1; scope='ACTUAL_POLICY_AST_SYNTHETIC_PATHS';
    policyPath=$PolicyPath; policySha256=$before; sourceUnchanged=$true;
    fixtureSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash;
    observedUtc=[DateTime]::UtcNow.ToString('o'); tests=$results.Count; failures=$failed;
    nativeExecuted=$false; guiExecuted=$false; mavenExecuted=$false; fullAcceptance=$false;
    results=$results}
$stream = [IO.File]::Open($ReceiptPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
try {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($receipt | ConvertTo-Json -Depth 8))
    $stream.Write($bytes, 0, $bytes.Length)
} finally { $stream.Dispose() }
Write-Host "Actual policy cases=$($results.Count), failed=$failed, receipt=$ReceiptPath"
if ($failed) { exit 1 }
