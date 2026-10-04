<#
.SYNOPSIS
Focused execution нового phase -> restoration entry; substitutes только в отдельном PS, без GUI/JDK/native.
.DESCRIPTION
Проверяются exact entry/tuple/pins, независимые receipts и fail-closed до первого phase launch.
Fixture substitute не выдаёт actual phase/helper/UI PASS; весь результат имеет nativeExecuted=false.
#>
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$imports=@{'Test-Portable.ps1'=@('Resolve-PortableSafetyPath');
    'Test-UpdateBootstrap.ps1'=@('Test-ColdInventoryEqual','ConvertFrom-ColdReceiptJson','Write-ColdJson')}
foreach ($file in $imports.Keys) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors)
    foreach ($name in $imports[$file]) {
        $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($errors.Count -or $nodes.Count -ne 1) {throw 'GOAL_PHASE_FIXTURE_IMPORT'};. ([scriptblock]::Create($nodes[0].Extent.Text))
    }
}
. (Join-Path $PSScriptRoot 'NativeUpdatePhaseSessionCollector.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateRestoredWindowObservation.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateGoalRestorationProbe.ps1')
$directory=Join-Path ([IO.Path]::GetTempPath()) ('cp-goal-phase-route-fixtures-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($directory)
$edge=(Get-Process -Id $PID).Path;$edgeSha=(Get-FileHash -LiteralPath $edge).Hash.ToLowerInvariant()
$runtime=[pscustomobject]@{directory=$directory;java=$edge;javaSha256=$edgeSha;core=$edge;coreSha256=$edgeSha;
    classes=$directory;classpath='FIXTURE_ONLY';sourcePins=@();classPins=@()}
$file=Join-Path $directory 'runtime.json';Write-ColdJson $file $runtime
$runtime | Add-Member receipt $file;$runtime | Add-Member receiptSha256 ((Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant())
$script:checks=0;$script:mode='normal';$script:events=[Collections.Generic.List[string]]::new();$script:phaseCalls=0;$script:entryCalls=0
# Substitutes охватывают только transport/physical execution, actual preflight остаётся production.
function Assert-NativeWebGoalRuntime($Runtime) { }
function Assert-GoalPhaseFixture([bool]$Condition,[string]$Label) {
    if (-not $Condition) {throw ('GOAL_PHASE_FIXTURE:'+ $Label)};$script:checks++
}
function Invoke-NativeGoalPhaseScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    $script:phaseCalls++;$script:events.Add('phase-start')
    Assert-GoalPhaseFixture ($Source -ceq 'SOURCE' -and $Base -ceq 'BASE' -and $Target -ceq 'TARGET' -and
        $Life -ceq 'LIFE' -and $Cold -ceq 'COLD' -and $Java -ceq 'JAVA' -and $Evidence -ceq $directory -and $Timeout -eq 60) 'original phase tuple exact'
    if ($script:mode -ceq 'phase-throws') {throw 'FIXTURE_PHASE_PRIMARY_ERROR'}
    if ($script:mode -cne 'no-context') {$script:nativeGoalPhaseContext=[pscustomobject]@{client=$Row.client;directory=$directory;root=$directory}}
    $script:events.Add('phase-cleanup')
    if ($script:mode -cne 'no-receipt') {$Row | Add-Member phaseSessionIndependent ([pscustomobject]@{Receipt='PHASE_FILE';ReceiptSha256=('a'*64);
        status=$(if ($script:mode -ceq 'collector-fails') {'FAIL'} else {'PENDING'})}) -Force}
    if ($script:mode -ceq 'phase-fails') {$Row.status='FAIL'}
    return $Row
}
function New-NativeGoalPostTransactionProbe($Context,[string]$PhaseReceipt,[string]$PhaseReceiptSha256) {
    $script:events.Add('fresh-probe')
    Assert-GoalPhaseFixture ($script:events -ccontains 'phase-cleanup' -and $PhaseReceipt -ceq 'PHASE_FILE' -and $PhaseReceiptSha256 -ceq ('a'*64)) 'phase receipt consumed after cleanup'
    return [pscustomobject]@{context=$Context;nativePass=$false}
}
function Invoke-NativeGoalPostTransactionObserver($Probe,[int]$Timeout,$WebRuntime,[string]$RuntimeReceiptSha256,[string]$Edge,[string]$EdgeSha256) {
    $script:entryCalls++;$script:events.Add('actual-entry-call')
    if ($Probe.context.client -ceq 'web') {
        Assert-GoalPhaseFixture ([object]::ReferenceEquals($WebRuntime,$runtime) -and $RuntimeReceiptSha256 -ceq $runtime.receiptSha256 -and
            $Edge -ceq $edge -and $EdgeSha256 -ceq $edgeSha) 'independent runtime/Edge pins forwarded exact'
    }
    if ($script:mode -ceq 'entry-throws') {throw 'FIXTURE_ENTRY_PRIMARY_ERROR'}
    return [pscustomobject]@{status='PENDING';Receipt='INDEPENDENT_RESTORATION_FILE';ReceiptSha256=('b'*64);nativePass=$false;fullCellProofComplete=$false}
}
$script:nativeGoalPhaseContext=[pscustomobject]@{token='PREVIOUS_CONTEXT_NOT_REUSED'}
$old=$script:nativeGoalPhaseContext
foreach ($mode in 'normal','no-context','no-receipt','phase-fails','collector-fails','phase-throws','entry-throws') {
    $script:mode=$mode;$script:events.Clear();$script:entryCalls=0
    $row=[pscustomobject]@{client='web';status='PENDING';scenario='FIXTURE_ONLY'}
    $result=Invoke-NativeGoalPhaseSessionCell $row 'SOURCE' 'BASE' 'TARGET' 'LIFE' 'COLD' 'JAVA' $directory 60 $runtime $runtime.receiptSha256 $edge $edgeSha
    Assert-GoalPhaseFixture ($result.status -ceq $(if ($mode -ceq 'normal') {'PENDING'} else {'FAIL'}) -and -not $result.nativePass) ($mode+' not helper-return PASS')
    Assert-GoalPhaseFixture ([object]::ReferenceEquals($script:nativeGoalPhaseContext,$old)) ($mode+' caller context restored')
    $wantEntry=$mode -cin @('normal','entry-throws')
    Assert-GoalPhaseFixture ($script:entryCalls -eq $(if ($wantEntry) {1} else {0})) ($mode+' exact entry count')
    if ($mode -ceq 'normal') {
        Assert-GoalPhaseFixture ($row.status -ceq 'PENDING' -and $result.restoration.ReceiptSha256 -ceq ('b'*64) -and
            $result.phaseSessionIndependent.ReceiptSha256 -ceq ('a'*64)) 'separate receipts no Row promotion'
    }
}
# Pins failures must occur before ANY phase/helper/native launch, not just before late probe.
foreach ($case in @(@{name='missing-runtime';runtime=$null;pin='';edge=$edge;edgePin=$edgeSha;error='GOAL_WEB_EXPLICIT_RUNTIME_PIN'},
    @{name='wrong-runtime';runtime=$runtime;pin=('0'*64);edge=$edge;edgePin=$edgeSha;error='GOAL_WEB_EXPLICIT_RUNTIME_PIN'},
    @{name='missing-edge';runtime=$runtime;pin=$runtime.receiptSha256;edge=$edge;edgePin='';error='GOAL_WEB_EXPLICIT_EDGE_PIN'})) {
    $before=$script:phaseCalls;$rejected=$false
    try {Invoke-NativeGoalPhaseSessionCell ([pscustomobject]@{client='web';status='PENDING'}) 'SOURCE' 'BASE' 'TARGET' 'LIFE' 'COLD' 'JAVA' $directory 60 $case.runtime $case.pin $case.edge $case.edgePin | Out-Null}
    catch {$rejected=$_.Exception.Message -ceq $case.error}
    Assert-GoalPhaseFixture ($rejected -and $script:phaseCalls -eq $before) ($case.name+' no launch')
}
$script:mode='normal';$row=[pscustomobject]@{client='fx';status='PENDING'}
$result=Invoke-NativeGoalPhaseSessionCell $row 'SOURCE' 'BASE' 'TARGET' 'LIFE' 'COLD' 'JAVA' $directory 60
Assert-GoalPhaseFixture ($result.status -ceq 'PENDING' -and $row.status -ceq 'PENDING') 'desktop route no Web pins required'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'NativeUpdateGoalRestorationProbe.ps1'),[ref]$tokens,[ref]$errors)
$node=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativeGoalPhaseSessionCell'},$true))[0]
Assert-GoalPhaseFixture ($errors.Count -eq 0 -and $node.Extent.Text.Contains('Invoke-NativeGoalPostTransactionObserver $probe') -and
    -not $node.Extent.Text.Contains('Invoke-RestMethod') -and -not $node.Extent.Text.Contains('SCENARIOS')) 'production route exact entry no model POST/matrix'
$output=Join-Path $directory 'fixture-results.json'
Write-ColdJson $output ([ordered]@{checks=$script:checks;scope='PHASE_ROUTE_ORCHESTRATION_ONLY';nativeExecuted=$false;actualDom='PENDING';
    modelPost=$false;phaseCalls=$script:phaseCalls;diagnostics=$directory})
[pscustomobject]@{checks=$script:checks;nativeExecuted=$false;diagnostics=$directory} | ConvertTo-Json -Compress
