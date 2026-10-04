<#
.SYNOPSIS
Ограниченные проверки native port normalization и diagnostic exact selector.
.DESCRIPTION
Использует AST определения и безопасные фрагменты runner, данные и mocks только в памяти.
Не исполняет тела runner, native, GUI, Java, Maven, сеть, реестр или запись evidence.
Fixture PASS означает только контракт harness, не native PASS и не исправление actual13500.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'ENDPOINT_FIXTURE_POWERSHELL7'}
$coldPath=Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'
$nativePath=Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'
$planPath=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'))
$pins=@{}
foreach ($path in $coldPath,$nativePath,$planPath) {$pins[$path]=(Get-FileHash -LiteralPath $path).Hash}
$tokens=$null;$errors=$null
$coldAst=[Management.Automation.Language.Parser]::ParseFile($coldPath,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'ENDPOINT_FIXTURE_COLD_PARSE'}
$nativeAst=[Management.Automation.Language.Parser]::ParseFile($nativePath,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'ENDPOINT_FIXTURE_NATIVE_PARSE'}
foreach ($name in 'Assert-ColdKeys','Test-ColdInteger','Assert-ColdUiReceipt','Get-ColdLauncherName',
    'Get-ColdUtcTicks','ConvertFrom-ColdCommandLine','Test-ColdInventoryEqual') {
    $definitions=@($coldAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
    if ($definitions.Count -ne 1) {throw ('ENDPOINT_FIXTURE_IMPORT '+$name)}
    . ([scriptblock]::Create($definitions[0].Extent.Text))
}
$definition=@($nativeAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Get-NativeEvidencePlan'},$true))
if ($definition.Count -ne 1) {throw 'ENDPOINT_FIXTURE_PLAN_IMPORT'}
. ([scriptblock]::Create($definition[0].Extent.Text))
$script:checks=0

# Положительный control проверяет реальную ветку, без native acceptance.
function Assert-EndpointFixture([bool]$Condition,[string]$Code) {
    if (-not $Condition) {throw ('ENDPOINT_FIXTURE_ASSERT '+$Code)};$script:checks++
}

# Только ожидаемая точная причина считается проверенным отказом.
function Assert-EndpointRejected([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    Assert-EndpointFixture ($caught -ceq $Code) ('expected='+$Code+' actual='+$caught)
}

# Фрагмент берётся из AST однозначно, без запуска окружающего native тела.
function Get-EndpointStatement([string]$Text) {
    $nodes=@($nativeAst.FindAll({param($n) $n -is [Management.Automation.Language.StatementAst] -and
        $n -isnot [Management.Automation.Language.CommandAst] -and $n.Extent.Text -ceq $Text},$true))
    if ($nodes.Count -ne 1) {throw ('ENDPOINT_FIXTURE_STATEMENT '+$Text)}
    return $nodes[0]
}

# Неожиданный внешний вызов сразу останавливает fixture, в том числе из AST фрагмента.
function Start-Process {throw 'ENDPOINT_FIXTURE_FORBIDDEN_PROCESS'}
function Stop-Process {throw 'ENDPOINT_FIXTURE_FORBIDDEN_KILL'}
function Start-ColdProcess {throw 'ENDPOINT_FIXTURE_FORBIDDEN_NATIVE'}
function Start-NativeOwned {throw 'ENDPOINT_FIXTURE_FORBIDDEN_NATIVE'}
function Invoke-ColdTool {throw 'ENDPOINT_FIXTURE_FORBIDDEN_JAVA'}
function Get-NetTCPConnection {throw 'ENDPOINT_FIXTURE_FORBIDDEN_TCP'}
function Get-CimInstance {throw 'ENDPOINT_FIXTURE_FORBIDDEN_CIM'}
function Invoke-WebRequest {throw 'ENDPOINT_FIXTURE_FORBIDDEN_HTTP'}
function New-Item {throw 'ENDPOINT_FIXTURE_FORBIDDEN_WRITE'}
function Copy-Item {throw 'ENDPOINT_FIXTURE_FORBIDDEN_COPY'}
function Remove-Item {throw 'ENDPOINT_FIXTURE_FORBIDDEN_DELETE'}

# Эти две функции намеренно НЕ импортированы выше: только реальный importer обязан их разрешить.
# Исполняется его определение, не тело runner. Native API остаются закрыты script-scope mocks.
$import=@($nativeAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Import-NativeDependencies'},$true))[0]
& {
    . ([scriptblock]::Create($import.Extent.Text))
    Import-NativeDependencies $PSScriptRoot
    $importedSelector=Get-Item -LiteralPath 'Function:Get-ColdSelectedPlan' -ErrorAction Stop
    $importedNormalizer=Get-Item -LiteralPath 'Function:Convert-ColdNativePort' -ErrorAction Stop
    Assert-EndpointFixture ($importedSelector.ScriptBlock.Ast.Name -ceq 'global:Get-ColdSelectedPlan' -and
        $importedNormalizer.ScriptBlock.Ast.Name -ceq 'global:Convert-ColdNativePort') 'ACTUAL_IMPORT_RESOLVES_BOTH_GLOBAL_FUNCTIONS'
    $port=& $importedNormalizer.ScriptBlock ([uint16]8080)
    Assert-EndpointFixture ($port -is [int] -and $port -eq 8080) 'ACTUAL_IMPORTED_NORMALIZER_EXECUTABLE'
    $mockPlan=@([pscustomobject]@{key='delta/B1/web/ascii/SESSION'},[pscustomobject]@{key='delta/B1/web/unicode/SESSION'})
    $result=@(& $importedSelector.ScriptBlock $mockPlan @('delta/B1/web/unicode/SESSION'))
    Assert-EndpointFixture ($result.Count -eq 1 -and [object]::ReferenceEquals($result[0],$mockPlan[1])) 'ACTUAL_IMPORTED_SELECTOR_EXECUTABLE'
}

# Native UInt16 допустим только до wire boundary и возвращается именно Int32.
foreach ($type in [int],[long],[uint16]) {foreach ($port in 1,8080,65535) {
    $value=[Convert]::ChangeType($port,$type)
    $normalized=Convert-ColdNativePort $value
    Assert-EndpointFixture ($normalized -is [int] -and $normalized -eq $port) ('VALID_NATIVE_PORT_'+$type.Name+'_'+$port)
}}
$invalidPorts=@([int]0,[uint16]0,[long]0,[int]-1,[long]-1,[int]65536,[long]65536,[long]::MaxValue,
    '8080','0','65535',$true,$false,[double]8080,[single]8080,[decimal]8080,[double]1.5,$null,[uint32]8080,[int16]8080)
foreach ($value in $invalidPorts) {Assert-EndpointRejected {Convert-ColdNativePort $value} 'COLD_NATIVE_PORT'}
Assert-EndpointFixture (Test-ColdInteger ([int]1) 1) 'WIRE_INT_CONTROL'
Assert-EndpointFixture (Test-ColdInteger ([long]65535) 1) 'WIRE_LONG_CONTROL'
foreach ($value in @([uint16]8080,[uint32]8080,'8080',$true,[double]8080,$null)) {
    Assert-EndpointFixture (-not (Test-ColdInteger $value 1)) 'WIRE_INTEGER_NOT_WIDENED'
}

# UI receipt проверяется настоящим wire guard, но witness/lease только mock в памяти.
$root='C:\mock-native-endpoint';$exe=Join-Path $root 'CashPrediction-Web.exe'
$birth=[datetime]::new(2026,10,4,12,0,0,[DateTimeKind]::Utc)
$ui=[pscustomobject]@{pid=[int]123;startedAtTicks=$birth.Ticks;executablePath=$exe;
    modules=@((Join-Path $root 'runtime/bin/server/jvm.dll'));args=@('--home',$root);
    commandLine=('"'+$exe+'" --home "'+$root+'"');observedAt=$birth.AddSeconds(1).ToString('o');
    lease=[pscustomobject]@{schemaVersion=1;leaseId='11111111-1111-1111-1111-111111111111';pid=123;
        startedAtEpochMillis=([DateTimeOffset]::new($birth)).ToUnixTimeMilliseconds();installationRoot=$root;client='web'};
    witness=[pscustomobject]@{kind='owned-http';port=[int]8080;status=200;owningProcess=[long]123;bodySha256=('a'*64)}}
Assert-ColdUiReceipt $ui $root 'web';Assert-EndpointFixture $true 'MOCK_WIRE_INT_ACCEPTED'
$ui.witness.port=[uint16]8080
Assert-EndpointRejected {Assert-ColdUiReceipt $ui $root 'web'} 'COLD_REPORT_LAUNCH_IDENTITY'
$ui.witness.port=Convert-ColdNativePort $ui.witness.port
Assert-ColdUiReceipt $ui $root 'web';Assert-EndpointFixture ($ui.witness.port -is [int]) 'NORMALIZED_MOCK_WIRE_ACCEPTED'
foreach ($value in @('8080',$true,[double]8080,$null,0,-1,65536)) {
    $ui.witness.port=$value;Assert-EndpointRejected {Assert-ColdUiReceipt $ui $root 'web'} 'COLD_REPORT_LAUNCH_IDENTITY'
}
$getUi=@($coldAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Get-ColdUiReceipt'},$true))[0]
$normalizations=@($getUi.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Convert-ColdNativePort'},$true))
Assert-EndpointFixture ($normalizations.Count -eq 1 -and $normalizations[0].Extent.Text -ceq 'Convert-ColdNativePort $listener.LocalPort') 'ACTUAL_TCP_NORMALIZATION_CALL'
Assert-EndpointFixture ($getUi.Extent.Text.Contains('Assert-ColdUiReceipt $receipt $Root $Client')) 'ACTUAL_UI_WIRE_GUARD_RETAINED'
Assert-EndpointFixture ($import.Extent.Text.Contains("'Convert-ColdNativePort'") -and $import.Extent.Text.Contains("'Get-ColdSelectedPlan'")) 'NATIVE_IMPORTS_NEW_DEFINITIONS'

# Полный production план читается без исполнения runner. Scenario scope остаётся исходным bound.
$rows=@(Get-NativeEvidencePlan $planPath);$Scenario=@('delta','offline');$CellKey=@();$MaxCells=36
Assert-EndpointFixture ($rows.Count -eq 612 -and @($rows | Where-Object {$_.status -cne 'PENDING'}).Count -eq 0) 'FULL_612_PENDING'
$scenarioSelection=Get-EndpointStatement '$selected=@($rows | Where-Object {$_.scenario -cin $Scenario})'
$bound=Get-EndpointStatement "if (`$selected.Count -gt `$MaxCells -or `$selected.Count -ne `$Scenario.Count*18) {throw 'NATIVE_CELL_BOUND'}"
$proxy=@($nativeAst.FindAll({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -ceq '$selectionPlan'},$true))
if ($proxy.Count -ne 1) {throw 'ENDPOINT_FIXTURE_PROXY_AST'}
$selection=Get-EndpointStatement '$selected=@(Get-ColdSelectedPlan $selectionPlan $CellKey | ForEach-Object {$_.row})'
Assert-EndpointFixture ($scenarioSelection.Extent.StartOffset -lt $bound.Extent.StartOffset -and
    $bound.Extent.StartOffset -lt $proxy[0].Extent.StartOffset -and $proxy[0].Extent.StartOffset -lt $selection.Extent.StartOffset) 'FULL_SCOPE_BOUND_BEFORE_SELECTOR'
. ([scriptblock]::Create($scenarioSelection.Extent.Text))
. ([scriptblock]::Create($bound.Extent.Text))
. ([scriptblock]::Create($proxy[0].Extent.Text))
Assert-EndpointFixture ($selectionPlan.Count -eq 36 -and $selectionPlan[0].key -ceq 'delta/B1/fx/ascii/SESSION') 'PRODUCTION_PROXY_FIELDS_AND_ORDER'
Assert-EndpointFixture ([object]::ReferenceEquals($selectionPlan[0].row,$rows[0])) 'PROXY_RETAINS_REAL_ROW'
$canonical=@($selectionPlan.key)
$requests=@('offline/B2/web/unicode/SESSION','delta/B1/web/ascii/SESSION','delta/B1/fx/ascii/SESSION')
$picked=@(Get-ColdSelectedPlan $selectionPlan $requests)
Assert-EndpointFixture (($picked.key -join '|') -ceq 'delta/B1/fx/ascii/SESSION|delta/B1/web/ascii/SESSION|offline/B2/web/unicode/SESSION') 'REVERSE_REQUEST_CANONICAL_ORDER'
Assert-EndpointFixture (($selectionPlan.key -join '|') -ceq ($canonical -join '|')) 'NO_PLAN_MUTATION'
Assert-EndpointFixture (@(Get-ColdSelectedPlan $selectionPlan @()).Count -eq 36) 'EMPTY_ARRAY_DEFAULT_ALL_SCENARIO_ROWS'
$valid='delta/B1/web/ascii/SESSION'
foreach ($key in @('unknown/B1/web/ascii/SESSION','delta/B3/web/ascii/SESSION','delta/B1/unknown/ascii/SESSION',
    'delta/B1/web/unknown/SESSION','delta/B1/web/ascii/PREPARED','delta/B1/web/ascii','delta/B1/web/ascii/SESSION/extra',
    'DELTA/B1/web/ascii/SESSION','delta/b1/web/ascii/SESSION','delta/B1/Web/ascii/SESSION','delta/B1/web/Ascii/SESSION',
    'delta/B1/web/ascii/session','',' ',' delta/B1/web/ascii/SESSION','delta/B1/web/ascii/SESSION ',
    '*','delta/*/web/ascii/SESSION','delta/B?/web/ascii/SESSION','delta/B[12]/web/ascii/SESSION',
    'delta/.*/web/ascii/SESSION','cancel-next-session/B1/web/ascii/SESSION')) {
    Assert-EndpointRejected {Get-ColdSelectedPlan $selectionPlan @($key)} 'COLD_CELL_SELECTION'
}
Assert-EndpointRejected {Get-ColdSelectedPlan $selectionPlan @($valid,$valid)} 'COLD_CELL_SELECTION'
Assert-EndpointRejected {Get-ColdSelectedPlan $selectionPlan @($valid,'delta/B1/WEB/ascii/SESSION')} 'COLD_CELL_SELECTION'
Assert-EndpointRejected {Get-ColdSelectedPlan $selectionPlan @($valid,'')} 'COLD_CELL_SELECTION'
Assert-EndpointRejected {Get-ColdSelectedPlan $selectionPlan @([string]$null)} 'COLD_CELL_SELECTION'
Assert-EndpointRejected {Get-ColdSelectedPlan @($selectionPlan[0],$selectionPlan[0]) @($selectionPlan[0].key)} 'COLD_PLAN_DUPLICATE'
$CellKey=@($valid);$MaxCells=1
Assert-EndpointRejected {. ([scriptblock]::Create($bound.Extent.Text))} 'NATIVE_CELL_BOUND'
$MaxCells=36;$originalScenario=$Scenario;$Scenario=@('delta')
Assert-EndpointRejected {. ([scriptblock]::Create($bound.Extent.Text))} 'NATIVE_CELL_BOUND';$Scenario=$originalScenario

# Исполняется только настоящий выбранный foreach, с mock Invoke-NativeCell и writer в памяти.
$loop=@($nativeAst.FindAll({param($n) $n -is [Management.Automation.Language.ForEachStatementAst] -and
    $n.Variable.Extent.Text -ceq '$row' -and $n.Condition.Extent.Text -ceq '$selected'},$true))
if ($loop.Count -ne 1) {throw 'ENDPOINT_FIXTURE_EXECUTION_LOOP'}
$finalWrites=@($nativeAst.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Write-ColdJson' -and
    $n.Extent.Text.Contains("status=`$(if (`$fatal) {'FAIL'} else {'PENDING'})")},$true))
if ($finalWrites.Count -ne 1) {throw 'ENDPOINT_FIXTURE_FINAL_WRITE'}
Assert-EndpointFixture ($selection.Extent.StartOffset -lt $loop[0].Extent.StartOffset -and $loop[0].Extent.EndOffset -lt $finalWrites[0].Extent.StartOffset) 'SELECTION_EXECUTION_FINAL_ORDER'
Assert-EndpointFixture ($loop[0].Extent.Text.Contains('Invoke-NativeCell $row') -and $finalWrites[0].Extent.Text.Contains('cells=$rows')) 'EXECUTES_SELECTED_WRITES_ALL_ROWS'
Assert-EndpointFixture ($nativeAst.Extent.Text.Contains("if (`$Signoff) {throw 'NATIVE_SIGNOFF_INCOMPLETE_UPDATE_EVIDENCE_MATRIX'}")) 'SIGNOFF_STILL_INCOMPLETE'
& {
    $script:visited=[Collections.Generic.List[object]]::new();$script:mockResult=$null
    function Invoke-NativeCell($Row) {$script:visited.Add($Row)}
    function Write-ColdJson($Path,$Value) {
        Assert-EndpointFixture (@($Value.cells).Count -eq 612) 'MOCK_WRITER_RETAINS_ALL_ROWS'
        Assert-EndpointFixture (@($Value.cells | Where-Object {$_.status -cne 'PENDING'}).Count -eq 0) 'MOCK_NO_NATIVE_PASS'
        $script:mockResult=$Value
    }
    function Write-Host { }
    $bases=@('mock-b1','mock-b2');$baseManifests=@{'mock-b1'=@{};'mock-b2'=@{}}
    $target=@{};$life=@{};$cold=@{};$java='mock-java';$evidence='mock-evidence';$StepTimeoutSeconds=30
    foreach ($keys in @([pscustomobject]@{value=@($valid)},[pscustomobject]@{value=$requests},[pscustomobject]@{value=@()})) {
        $script:visited.Clear();$CellKey=$keys.value;$fatal=$null
        . ([scriptblock]::Create($selection.Extent.Text))
        . ([scriptblock]::Create($loop[0].Extent.Text))
        . ([scriptblock]::Create($finalWrites[0].Extent.Text))
        Assert-EndpointFixture ($script:visited.Count -eq $selected.Count -and $script:mockResult.status -ceq 'PENDING') 'PARTIAL_OR_FULL_SCENARIO_OVERALL_PENDING'
        for ($index=0;$index -lt $selected.Count;$index++) {
            Assert-EndpointFixture ([object]::ReferenceEquals($script:visited[$index],$selected[$index])) 'VISITS_EXACT_CANONICAL_ROW_ONLY'
        }
        $unselected=@($rows | Where-Object {$row=$_; -not @($selected | Where-Object {[object]::ReferenceEquals($_,$row)}).Count})
        Assert-EndpointFixture ($unselected.Count -eq 612-$selected.Count -and @($unselected | Where-Object {$_.status -cne 'PENDING'}).Count -eq 0) 'UNSELECTED_STAY_PENDING'
    }
    $fatal='mock-native-failure';. ([scriptblock]::Create($finalWrites[0].Extent.Text))
    Assert-EndpointFixture ($script:mockResult.status -ceq 'FAIL' -and $script:mockResult.cells.Count -eq 612) 'FAIL_RETAINS_FULL_PLAN'
}
foreach ($path in $pins.Keys) {if ((Get-FileHash -LiteralPath $path).Hash -cne $pins[$path]) {throw 'ENDPOINT_FIXTURE_DEPENDENCY_CHANGED'}}
Write-Host ('Endpoint/selection fixtures PASS: '+$script:checks+' checks; mocks/static only; nativeExecuted=false')
Write-Host ('Fixture SHA256: '+(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant())
Write-Host ('Cold runner SHA256: '+$pins[$coldPath].ToLowerInvariant())
Write-Host ('Native runner SHA256: '+$pins[$nativePath].ToLowerInvariant())
