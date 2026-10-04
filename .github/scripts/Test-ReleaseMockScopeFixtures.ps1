<#
.SYNOPSIS
Проверяет восстановление прежнего global mock state по настоящему AST release safety.
.DESCRIPTION
Не исполняет workflow или publisher. Запускает только save/restore присваивания.
#>
#requires -Version 7.0
param([string]$SafetyPath = (Join-Path $PSScriptRoot 'Test-ReleaseSafety.ps1'),
    [Parameter(Mandatory)][string]$ReceiptPath)
$ErrorActionPreference = 'Stop'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($SafetyPath,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'MOCK_SCOPE_PARSE'}
$assignments=@($ast.FindAll({param($n)
    $n -is [Management.Automation.Language.AssignmentStatementAst] -and
    $n.Left -is [Management.Automation.Language.VariableExpressionAst] -and
    $n.Left.VariablePath.UserPath -in 'savedMockState','savedMockValue'
},$true))
$restore=@($ast.FindAll({param($n)
    $n -is [Management.Automation.Language.IfStatementAst] -and
    $n.Extent.Text -match '^if \(\$savedMockState\)' -and
    $n.Extent.Text -match 'global:CashPredictionReleaseSafetyMockState'
},$true))
if($restore.Count -ne 1){throw 'MOCK_SCOPE_RESTORE'}
$priorVariable=Get-Variable CashPredictionReleaseSafetyMockState -Scope Global -ErrorAction SilentlyContinue
$hadPrior=$null -ne $priorVariable
$priorValue=if($hadPrior){$priorVariable.Value}else{$null}
$results=@()
try {
    foreach($present in $true,$false){
        $expected=@{sentinel='pre-existing-state'}
        if($present){$global:CashPredictionReleaseSafetyMockState=$expected}
        else{Remove-Variable CashPredictionReleaseSafetyMockState -Scope Global -ErrorAction SilentlyContinue}
        foreach($assignment in $assignments){. ([scriptblock]::Create($assignment.Extent.Text))}
        $global:CashPredictionReleaseSafetyMockState=@{sentinel='replacement-state'}
        . ([scriptblock]::Create($restore[0].Extent.Text))
        $actual=Get-Variable CashPredictionReleaseSafetyMockState -Scope Global -ErrorAction SilentlyContinue
        $passed=if($present){$null -ne $actual -and [object]::ReferenceEquals($expected,$actual.Value)}
            else{$null -eq $actual}
        $results += [pscustomobject]@{previouslyPresent=$present;passed=$passed}
    }
} finally {
    if($hadPrior){$global:CashPredictionReleaseSafetyMockState=$priorValue}
    else{Remove-Variable CashPredictionReleaseSafetyMockState -Scope Global -ErrorAction SilentlyContinue}
}
$receipt=[ordered]@{scope='ACTUAL_SAVE_RESTORE_AST';source=$SafetyPath;
    sourceSha256=(Get-FileHash $SafetyPath).Hash; fixtureSha256=(Get-FileHash $PSCommandPath).Hash;
    tests=2;failures=@($results|Where-Object {-not $_.passed}).Count;results=$results;
    nativeExecuted=$false;networkExecuted=$false;publisherExecuted=$false}
if(Test-Path -LiteralPath $ReceiptPath){throw 'MOCK_SCOPE_RECEIPT_EXISTS'}
$stream=[IO.File]::Open($ReceiptPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
try{$bytes=[Text.UTF8Encoding]::new($false).GetBytes(($receipt|ConvertTo-Json -Depth 8));
    $stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
$receipt|ConvertTo-Json -Depth 8
if($receipt.failures){exit 1}
