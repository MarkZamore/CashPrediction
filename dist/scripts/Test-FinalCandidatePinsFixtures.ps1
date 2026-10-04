<# Проверяет фактический candidate guard и AST связи без исполнения delivery backend. #>
#requires -Version 7.0
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$validator = Join-Path $PSScriptRoot 'Test-FinalDelivery.ps1'
$validatorSha = (Get-FileHash -LiteralPath $validator).Hash
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($validator, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'CANDIDATE_PARSE' }
$definitions = @($ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'Assert-FinalCandidatePins'
}, $true))
if ($definitions.Count -ne 1) { throw 'GUARD_MISSING' }
# Импортируется только настоящий guard, без упаковки, Maven, EXE и верхнего тела validator.
. ([scriptblock]::Create($definitions[0].Extent.Text))
$archiveExpected = 'a' * 64; $portableExpected = 'b' * 64; $oldCandidate = 'c' * 64
$checks = 0
if (Assert-FinalCandidatePins $archiveExpected $portableExpected '' '') { throw 'UNBOUND_ACCEPTED' }; $checks++
if (-not (Assert-FinalCandidatePins $archiveExpected $portableExpected $archiveExpected.ToUpperInvariant() $portableExpected)) {
    throw 'MATCH_REJECTED'
}; $checks++
# Ожидаемые SHA независимы от actual и не вычисляются из проверяемого candidate.
foreach ($case in @(
    @($archiveExpected, $portableExpected, $archiveExpected, '', 'FINAL_CANDIDATE_PIN_PAIR'),
    @($archiveExpected, $portableExpected, '', $portableExpected, 'FINAL_CANDIDATE_PIN_PAIR'),
    @($archiveExpected, $portableExpected, 'invalid', $portableExpected, 'FINAL_CANDIDATE_PIN_PAIR'),
    @($oldCandidate, $portableExpected, $archiveExpected, $portableExpected, 'FINAL_CANDIDATE_ARCHIVE_MISMATCH'),
    @($archiveExpected, $oldCandidate, $archiveExpected, $portableExpected, 'FINAL_CANDIDATE_PORTABLE_MISMATCH'),
    # Новые format negatives: длина, whitespace и LF должны давать PIN_PAIR, не mismatch.
    @($archiveExpected, $portableExpected, $archiveExpected, ('b' * 63), 'FINAL_CANDIDATE_PIN_PAIR'),
    @($archiveExpected, $portableExpected, $archiveExpected, ('b' * 65), 'FINAL_CANDIDATE_PIN_PAIR'),
    @($archiveExpected, $portableExpected, $archiveExpected, (' ' + $portableExpected), 'FINAL_CANDIDATE_PIN_PAIR'),
    @($archiveExpected, $portableExpected, $archiveExpected, ($portableExpected + "`n"), 'FINAL_CANDIDATE_PIN_PAIR'),
    @($archiveExpected, $portableExpected, ($archiveExpected + "`n"), $portableExpected, 'FINAL_CANDIDATE_PIN_PAIR'),
    # Пустой actual SHA не подтверждает independently expected archive.
    @('', $portableExpected, $archiveExpected, $portableExpected, 'FINAL_CANDIDATE_ARCHIVE_MISMATCH'))) {
    $caught = $null
    try { $null = Assert-FinalCandidatePins $case[0] $case[1] $case[2] $case[3] }
    catch { $caught = $_.Exception.Message }
    if ($caught -cne $case[4]) { throw "WRONG_CATEGORY: $caught" }; $checks++
}
$calls = @($ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
    $node.GetCommandName() -ceq 'Assert-FinalCandidatePins'
}, $true))
$extractions = @($ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
    $node.GetCommandName() -ceq 'Invoke-FinalTool' -and
    $node.Extent.Text -match "'archive-extract\.log'"
}, $true))
if ($calls.Count -ne 1 -or $extractions.Count -ne 1 -or
    $calls[0].Extent.StartOffset -ge $extractions[0].Extent.StartOffset) { throw 'PIN_CALL_BEFORE_EXTRACTION' }; $checks++
if (($calls[0].CommandElements.Extent.Text -join ' ') -cne
    'Assert-FinalCandidatePins $archiveSha $receipt.portable.inventorySha256 $ExpectedArchiveSha256 $ExpectedPortableInventorySha256') {
    throw 'PIN_CALL_ARGUMENTS'
}; $checks++
# Composition и возврат validator обязаны оставаться fullAcceptance=false даже с matching pins.
$acceptanceValues = @($ast.FindAll({ param($node)
    $node -is [Management.Automation.Language.HashtableAst]
}, $true) | ForEach-Object {
    foreach ($pair in $_.KeyValuePairs) {
        if ($pair.Item1.Extent.Text -ceq 'fullAcceptance') { $pair.Item2.Extent.Text }
    }
})
if ($acceptanceValues.Count -ne 2 -or @($acceptanceValues | Where-Object { $_ -cne '$false' }).Count -or
    @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left.Extent.Text -match '\.fullAcceptance$'
    }, $true)).Count) { throw 'FULL_ACCEPTANCE_SCOPE' }; $checks++
if ((Get-FileHash -LiteralPath $validator).Hash -cne $validatorSha) { throw 'VALIDATOR_CHANGED' }
[ordered]@{ scope = 'CANDIDATE_PIN_GUARD_ONLY'; status = 'PASS'; checks = $checks;
    validatorSha256 = $validatorSha; testSha256 = (Get-FileHash -LiteralPath $PSCommandPath).Hash;
    archiveExecuted = $false; mavenExecuted = $false; nativeExecuted = $false; fullAcceptance = $false } | ConvertTo-Json
