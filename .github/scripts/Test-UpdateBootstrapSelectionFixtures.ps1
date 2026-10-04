<#
.SYNOPSIS
Проверяет exact selector cold runner через AST, память и mocks без native исполнения.
.DESCRIPTION
Импортирует только Get-ColdSelectedPlan и Get-ColdCheckpoints. Из тела runner
исполняет только построение mock-плана, bound, выбор/index и итоговый mock receipt.
Никогда не исполняет native тело клетки, не создаёт файлы, процессы, GUI или реестр.
PASS относится только к контракту selection/status, не к cold matrix.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'SELECTION_POWERSHELL7_REQUIRED'}
$source=Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'
$runnerBytes=[IO.File]::ReadAllBytes($source)
$runnerHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($runnerBytes)).ToLowerInvariant()
$tokens=$null; $errors=$null
$runnerAst=[Management.Automation.Language.Parser]::ParseInput([Text.Encoding]::UTF8.GetString($runnerBytes),$source,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'SELECTION_RUNNER_PARSE'}

# Дочерняя область не оставляет mocks или импортированные функции вызывающему сеансу.
& {
    param($RunnerAst)
    $script:selectionChecks=0
    foreach ($name in 'Get-ColdSelectedPlan','Get-ColdCheckpoints') {
        $definitions=@($RunnerAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($definitions.Count -ne 1) {throw "SELECTION_FUNCTION $name"}
        . ([scriptblock]::Create($definitions[0].Extent.Text))
    }

    # Каждое условие считается только после успеха; неожиданный отказ останавливает fixture.
    function Assert-Selection([bool]$Condition,[string]$Name) {
        if (-not $Condition) {throw "SELECTION_ASSERT $Name"}
        $script:selectionChecks++
    }

    # Отрицательные случаи обязаны дать точный код guard, не ошибку mock или окружения.
    function Assert-SelectionReject([scriptblock]$Action,[string]$Code,[string]$Name) {
        $actual=$null
        try {& $Action | Out-Null} catch {$actual=$_.Exception.Message}
        Assert-Selection ($actual -ceq $Code) "$Name expected=$Code actual=$actual"
    }

    # Только один AST statement разрешён для каждого узкого фрагмента безопасной логики.
    function Get-SelectionStatement([string]$Text) {
        $matches=@($RunnerAst.FindAll({param($n) $n -is [Management.Automation.Language.StatementAst] -and $n -isnot [Management.Automation.Language.CommandAst] -and $n.Extent.Text -ceq $Text},$true))
        if ($matches.Count -ne 1) {throw "SELECTION_STATEMENT $Text count=$($matches.Count)"}
        return $matches[0]
    }

    # Все реальные внешние операции запрещены даже при ошибке выбора AST-фрагмента.
    function Start-ColdProcess {throw 'SELECTION_FORBIDDEN_NATIVE'}
    function Invoke-ColdTool {throw 'SELECTION_FORBIDDEN_JAVA'}
    function Start-Process {throw 'SELECTION_FORBIDDEN_PROCESS'}
    function Stop-Process {throw 'SELECTION_FORBIDDEN_KILL'}
    function New-Item {throw 'SELECTION_FORBIDDEN_WRITE'}
    function Copy-Item {throw 'SELECTION_FORBIDDEN_WRITE'}
    function Remove-Item {throw 'SELECTION_FORBIDDEN_WRITE'}
    function Invoke-WebRequest {throw 'SELECTION_FORBIDDEN_NETWORK'}
    function Get-CimInstance {throw 'SELECTION_FORBIDDEN_CIM'}

    # Mock writer сохраняет receipt только в памяти, никогда не пишет путь из runner.
    function Write-ColdJson([string]$Path,$Value) {
        Assert-Selection ($Path -ceq 'mock-evidence/results.json') 'receipt mock path'
        $script:selectionReceipt=$Value
    }

    # Join-Path итогового statement не обращается к настоящему provider/диску.
    function Join-Path([string]$Path,[string]$ChildPath) {
        if ($Path -cne 'mock-evidence' -or $ChildPath -cne 'results.json') {throw 'SELECTION_FOREIGN_PATH'}
        return "$Path/$ChildPath"
    }

    $top=@($RunnerAst.EndBlock.Statements)
    $planInit=Get-SelectionStatement '$plan=@()'
    $rowsInit=Get-SelectionStatement '$rows=[Collections.Generic.List[object]]::new()'
    $copyInit=@($top | Where-Object {$_ -is [Management.Automation.Language.AssignmentStatementAst] -and $_.Left.Extent.Text -ceq '$copyPlan'})
    $planLoop=@($top | Where-Object {$_ -is [Management.Automation.Language.ForStatementAst] -and $_.Extent.Text.Contains('$plan+=@(')})
    Assert-Selection ($copyInit.Count -eq 1 -and $planLoop.Count -eq 1) 'canonical full-plan construction'
    $bases=@('mock-base-1','mock-base-2')
    . ([scriptblock]::Create($planInit.Extent.Text))
    . ([scriptblock]::Create($rowsInit.Extent.Text))
    . ([scriptblock]::Create($copyInit[0].Extent.Text))
    . ([scriptblock]::Create($planLoop[0].Extent.Text))
    Assert-Selection ($plan.Count -eq 396 -and $rows.Count -eq 396) 'full 396 plan preserved'
    Assert-Selection (@($rows | Where-Object {$_.status -cne 'PENDING' -or $_.nativeExecuted}).Count -eq 0) 'all initial rows pending/unexecuted'
    $initialKeys=@($plan | ForEach-Object {$_.key})

    # Отсутствующий selector/пустой массив означает полный план; пустой элемент означает ошибку.
    $all=@(Get-ColdSelectedPlan $plan @())
    Assert-Selection (($all.key -join '|') -ceq ($initialKeys -join '|')) 'empty selector array defaults to full canonical plan'
    $one=@(Get-ColdSelectedPlan $plan @('B1/INITIAL/fx/unicode'))
    Assert-Selection ($one.Count -eq 1 -and $one[0].key -ceq 'B1/INITIAL/fx/unicode') 'one exact Unicode cell'
    Assert-Selection ([object]::ReferenceEquals($one[0],$plan[2])) 'selector retains canonical cell object'
    $reverse=@(Get-ColdSelectedPlan $plan @('B2/ROLLING_BACK/web/unicode','B1/INITIAL/fx/unicode','B1/INITIAL/fx/ascii'))
    Assert-Selection (($reverse.key -join '|') -ceq 'B1/INITIAL/fx/ascii|B1/INITIAL/fx/unicode|B2/ROLLING_BACK/web/unicode') 'canonical order independent of request order'
    Assert-Selection (($initialKeys -join '|') -ceq (($plan | ForEach-Object {$_.key}) -join '|')) 'selector does not mutate plan'
    foreach ($bad in @('B3/INITIAL/fx/ascii','B1/UNKNOWN/fx/ascii','B1/INITIAL/other/ascii',
        'B1/INITIAL/fx/absent','',' ',' B1/INITIAL/fx/ascii','B1/INITIAL/fx/ascii ',
        '*','B1/INITIAL/fx/*','B?/INITIAL/fx/ascii','B1/INITIAL/fx/[au]*','B1/INITIAL/fx/(ascii|unicode)',
        'b1/INITIAL/fx/ascii','B1/initial/fx/ascii','B1/INITIAL/FX/ascii','B1/INITIAL/fx/ASCII')) {
        Assert-SelectionReject {Get-ColdSelectedPlan $plan @($bad)} 'COLD_CELL_SELECTION' "invalid exact key [$bad]"
    }
    Assert-SelectionReject {Get-ColdSelectedPlan $plan @('B1/INITIAL/fx/ascii','B1/INITIAL/fx/ascii')} 'COLD_CELL_SELECTION' 'duplicate request key'
    Assert-SelectionReject {Get-ColdSelectedPlan $plan @('B1/INITIAL/fx/ascii','B1/INITIAL/FX/ascii')} 'COLD_CELL_SELECTION' 'valid key plus case variant'
    Assert-SelectionReject {Get-ColdSelectedPlan $plan @('B1/INITIAL/fx/ascii','')} 'COLD_CELL_SELECTION' 'valid key plus empty item'
    Assert-SelectionReject {Get-ColdSelectedPlan $plan @([string]$null)} 'COLD_CELL_SELECTION' 'null string entry is not default selector'
    Assert-SelectionReject {Get-ColdSelectedPlan @($plan[0],$plan[0]) @($plan[0].key)} 'COLD_PLAN_DUPLICATE' 'duplicate canonical plan key under explicit selector'

    $bound=Get-SelectionStatement "if (`$plan.Count -gt `$MaxCells) {throw 'COLD_CELL_BOUND'}"
    $selection=Get-SelectionStatement '$executionPlan=@(Get-ColdSelectedPlan $plan $CellKey)'
    Assert-Selection ($bound.Extent.StartOffset -lt $selection.Extent.StartOffset) 'full bound precedes selection'
    $CellKey=@('B1/INITIAL/fx/unicode'); $MaxCells=3
    Assert-SelectionReject {. ([scriptblock]::Create($bound.Extent.Text))} 'COLD_CELL_BOUND' 'selector cannot bypass full-plan MaxCells'
    $MaxCells=396; . ([scriptblock]::Create($bound.Extent.Text))
    Assert-Selection ($plan.Count -eq 396) 'full bound accepts complete plan at 396'

    $indexInit=Get-SelectionStatement '$rowIndices=[Collections.Generic.Dictionary[string,int]]::new([StringComparer]::Ordinal)'
    $indexLoop=Get-SelectionStatement 'for ($position=0;$position -lt $plan.Count;$position++) {$rowIndices.Add($plan[$position].key,$position)}'
    . ([scriptblock]::Create($indexInit.Extent.Text))
    . ([scriptblock]::Create($indexLoop.Extent.Text))
    $execution=@($RunnerAst.FindAll({param($n) $n -is [Management.Automation.Language.ForEachStatementAst] -and $n.Variable.Extent.Text -ceq '$cell' -and $n.Condition.Extent.Text -ceq '$executionPlan'},$true))
    Assert-Selection ($execution.Count -eq 1) 'native loop enumerates selected executionPlan only'
    $rowLookup=Get-SelectionStatement '$row=$rows[$rowIndices[$cell.key]]'
    Assert-Selection ($rowLookup.Extent.StartOffset -gt $execution[0].Body.Extent.StartOffset -and $rowLookup.Extent.EndOffset -lt $execution[0].Body.Extent.EndOffset) 'selected cell updates original full-plan row by exact key'
    $cellAcceptance=@($execution[0].Body.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Assert-ColdMatrix'},$true))
    Assert-Selection ($cellAcceptance.Count -eq 1 -and $cellAcceptance[0].Extent.Text -ceq 'Assert-ColdMatrix @($candidate) @($cell)') 'per-cell full acceptance still present'
    $passAssignment=Get-SelectionStatement "`$row.status='PASS'"
    Assert-Selection ($cellAcceptance[0].Extent.EndOffset -lt $passAssignment.Extent.StartOffset) 'PASS assignment follows per-cell acceptance'
    $projection=Get-SelectionStatement '$executedRows=@($executionPlan | ForEach-Object {$rows[$rowIndices[$_.key]]})'
    $finalAcceptance=Get-SelectionStatement 'Assert-ColdMatrix $executedRows $executionPlan'
    Assert-Selection ($projection.Extent.StartOffset -gt $execution[0].Extent.EndOffset -and $projection.Extent.EndOffset -lt $finalAcceptance.Extent.StartOffset) 'final acceptance uses exact executed rows and selected plan'
    $finalWrites=@($RunnerAst.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Write-ColdJson' -and $n.Extent.Text.Contains('fullPlanCellCount=$plan.Count')},$true))
    Assert-Selection ($finalWrites.Count -eq 1 -and $finalWrites[0].Extent.StartOffset -gt $finalAcceptance.Extent.EndOffset) 'final result emitted after selected acceptance'
    $finalWrite=$finalWrites[0]
    $guard=Get-SelectionStatement "if ((Get-PortableRealRegistrySnapshot) -cne `$registryBefore) {`$fatal='COLD_REAL_REGISTRY_CHANGED'}"
    Assert-Selection ($guard.Extent.EndOffset -lt $finalWrite.Extent.StartOffset) 'registry hygiene precedes final status'

    # Mock acceptance проверяет лишь scope аргументов: production evidence gate не заменяется.
    function Assert-ColdMatrix($Rows,$Plan) {
        Assert-Selection (@($Rows).Count -eq @($Plan).Count) 'mock acceptance scope cardinality'
        Assert-Selection ((@($Rows | ForEach-Object {$_.base+'/'+$_.checkpoint+'/'+$_.client+'/'+$_.pathVariant}) -join '|') -ceq (@($Plan | ForEach-Object {$_.key}) -join '|')) 'mock acceptance exact keys/order'
        if (@($Rows | Where-Object {$_.status -cne 'PASS' -or -not $_.nativeExecuted}).Count) {throw 'MOCK_EXECUTED_PENDING'}
    }

    # Только row lookup исполняется из native тела; PASS/nativeExecuted ниже являются mock-данными.
    foreach ($mode in 'single','subset','full','full-explicit','failure','hygiene') {
        foreach ($record in $rows) {$record.status='PENDING';$record.nativeExecuted=$false}
        $CellKey=switch ($mode) {
            'full' {@()}
            'full-explicit' {for ($i=$initialKeys.Count-1;$i -ge 0;$i--) {$initialKeys[$i]}}
            'subset' {@('B2/ROLLING_BACK/web/unicode','B1/INITIAL/fx/ascii')}
            default {@('B1/INITIAL/fx/unicode')}
        }
        # Switch с пустым output возвращает null: default all передаётся явным пустым массивом.
        if ($mode -ceq 'full') {$CellKey=@()}
        . ([scriptblock]::Create($selection.Extent.Text))
        foreach ($cell in $executionPlan) {
            . ([scriptblock]::Create($rowLookup.Extent.Text))
            $row.status='PASS';$row.nativeExecuted=$true
        }
        . ([scriptblock]::Create($projection.Extent.Text))
        . ([scriptblock]::Create($finalAcceptance.Extent.Text))
        $fatal=$null
        if ($mode -ceq 'failure') {$fatal='MOCK_NATIVE_FAILURE'}
        if ($mode -ceq 'hygiene') {$fatal='COLD_REAL_REGISTRY_CHANGED'}
        $evidence='mock-evidence'; $script:selectionReceipt=$null
        . ([scriptblock]::Create($finalWrite.Extent.Text))
        $expected=if ($mode -in @('failure','hygiene')) {'FAIL'} elseif ($mode -in @('full','full-explicit')) {'PASS'} else {'PARTIAL_PASS'}
        Assert-Selection ($script:selectionReceipt.status -ceq $expected) "$mode final status"
        Assert-Selection ($script:selectionReceipt.fullPlanCellCount -eq 396 -and @($script:selectionReceipt.cells).Count -eq 396) "$mode retains full plan rows"
        Assert-Selection ((@($script:selectionReceipt.selectedKeys) -join '|') -ceq (@($executionPlan.key) -join '|')) "$mode selected keys reflect canonical execution order"
        $selectedKeys=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($selectedCell in $executionPlan) {[void]$selectedKeys.Add($selectedCell.key)}
        $unselected=@($rows | Where-Object {-not $selectedKeys.Contains($_.base+'/'+$_.checkpoint+'/'+$_.client+'/'+$_.pathVariant)})
        Assert-Selection ($unselected.Count -eq (396-$executionPlan.Count)) "$mode unselected remain PENDING"
        Assert-Selection (@($unselected | Where-Object {$_.status -cne 'PENDING'}).Count -eq 0) "$mode unselected status not promoted"
        Assert-Selection (@($unselected | Where-Object {$_.nativeExecuted}).Count -eq 0) "$mode unselected never executed"
        if ($mode -in @('failure','hygiene')) {Assert-Selection ($script:selectionReceipt.failure -ceq $fatal) "$mode preserves failure reason"}
    }
    # Неисполненная выбранная клетка также должна дойти до итогового guard, а не дать частичный успех.
    foreach ($record in $rows) {$record.status='PENDING';$record.nativeExecuted=$false}
    $CellKey=@('B1/INITIAL/fx/unicode')
    . ([scriptblock]::Create($selection.Extent.Text))
    . ([scriptblock]::Create($projection.Extent.Text))
    Assert-SelectionReject {. ([scriptblock]::Create($finalAcceptance.Extent.Text))} 'MOCK_EXECUTED_PENDING' 'selected pending reaches acceptance guard'
    Write-Output "Selection fixtures PASS: $script:selectionChecks checks; mocks/static only; nativeExecuted by this fixture=false"
} $runnerAst

# Новый срез источника не переносится на результат старого AST без явного повторного запуска fixture.
$afterHash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if ($afterHash -cne $runnerHash) {throw 'SELECTION_RUNNER_CHANGED'}
Write-Output "Runner SHA256: $runnerHash"
Write-Output ('Fixture SHA256: '+(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant())
