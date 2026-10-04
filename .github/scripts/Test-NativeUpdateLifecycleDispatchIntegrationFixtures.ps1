<#
.SYNOPSIS
Узкие AST/mock integration проверки lifecycle runner и frozen dispatcher.
.DESCRIPTION
Тело runner не запускается. Исполняется только его orchestration try/finally с
mock tools, helpers, registry и in-memory persistence. Temp файлы проверяют
настоящие pin guards, не являются Java/native образами или acceptance evidence.
Ранее пройденные lifecycle/dispatcher suites здесь не повторяются.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
$script:integrationChecks=0;$script:integrationCalls=[Collections.Generic.List[object]]::new()
$script:integrationWrites=[Collections.Generic.List[object]]::new();$script:integrationThrow=$false
$source=Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'
$tokens=$null;$parseIssues=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$parseIssues)
if ($parseIssues.Count) {throw 'NATIVE_INTEGRATION_PARSE'}
$definitions=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]})
foreach ($definition in $definitions) {. ([scriptblock]::Create($definition.Extent.Text))}
Import-NativeDependencies $PSScriptRoot
Initialize-NativeLifecycleDispatch $PSScriptRoot
$dispatcherHash=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1')).Hash
$helperHashes=@{}
foreach ($file in Get-NativeDispatchFiles) {$helperHashes[$file]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash}

# Успех означает только выполненную fixture проверку, не native PASS.
function Assert-Integration([bool]$Value,[string]$Code) {
    if (-not $Value) {throw ('NATIVE_INTEGRATION_ASSERT '+$Code)};$script:integrationChecks++
}

# Сравнивает точное исключение интеграции, а не любой случайный отказ.
function Assert-IntegrationReject([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    Assert-Integration ($caught -ceq $Code) ("expected=$Code actual=$caught")
}

# Создаёт свежий canonical план со всеми 612 PENDING rows.
function New-IntegrationPlan {
    @(Get-NativeEvidencePlan ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'))))
}

# Любая непредусмотренная попытка native запуска запрещена до ОС.
function Start-NativeOwned {throw 'NATIVE_INTEGRATION_FORBIDDEN_NATIVE'}
function Start-NativeFixture {throw 'NATIVE_INTEGRATION_FORBIDDEN_SERVER'}
function Start-ColdProcess {throw 'NATIVE_INTEGRATION_FORBIDDEN_PROCESS'}
function Start-Process {throw 'NATIVE_INTEGRATION_FORBIDDEN_PROCESS'}

# Проверяет расширенный parameter contract непосредственно по актуальному runner AST.
$scenarioParameter=@($ast.ParamBlock.Parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq 'Scenario'})[0]
$scenarioSet=@($scenarioParameter.Attributes | Where-Object {$_.TypeName.Name -ceq 'ValidateSet'})[0]
$accepted=@($scenarioSet.PositionalArguments | ForEach-Object {$_.SafeGetValue()})
$plan=New-IntegrationPlan;$scenarioOrder=@($plan.scenario | Select-Object -Unique)
Assert-Integration (($accepted -join '/') -ceq ($scenarioOrder -join '/') -and $accepted.Count -eq 22) 'canonical parameter scenarios/order'
$limitParameter=@($ast.ParamBlock.Parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq 'MaxCells'})[0]
$range=@($limitParameter.Attributes | Where-Object {$_.TypeName.Name -ceq 'ValidateRange'})[0]
Assert-Integration ($range.PositionalArguments[0].SafeGetValue() -eq 1 -and $range.PositionalArguments[1].SafeGetValue() -eq 612) 'bound supports 612'
Assert-Integration ($limitParameter.DefaultValue.SafeGetValue() -eq 144) 'bounded default retained'
$defaults=@($scenarioParameter.DefaultValue.SafeGetValue())
Assert-Integration (@(Get-NativeLifecycleSelectedRows $plan $defaults @() 144 180).Count -eq 144) 'previous default still selects 144'
Assert-Integration (@(Get-NativeLifecycleSelectedRows $plan $accepted @() 612 180).Count -eq 612) 'full matrix selectable explicitly'
# Полный exact фильтр, включая все phases, не обрезается ни порядком входа, ни прежним default budget.
$allKeys=@($plan | ForEach-Object {$_.scenario+'/'+$_.base+'/'+$_.client+'/'+$_.path+'/'+$_.phase})
$reversedKeys=@($allKeys);[array]::Reverse($reversedKeys)
$reversedScenarios=@($accepted);[array]::Reverse($reversedScenarios)
$fullSelected=@(Get-NativeLifecycleSelectedRows $plan $reversedScenarios $reversedKeys 612 180)
Assert-Integration ($fullSelected.Count -eq 612 -and @($fullSelected.scenario | Select-Object -Unique).Count -eq 22) 'all accepted exact filters retain 22 scenarios and 612 cells'
for ($index=0;$index -lt $plan.Count;$index++) {
    Assert-Integration ([object]::ReferenceEquals($fullSelected[$index],$plan[$index]) -and
        $fullSelected[$index].status -ceq 'PENDING') ('canonical exact selection pending row '+$index)
}
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan $accepted $allKeys 611 180} 'NATIVE_CELL_BOUND'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan $accepted @() 144 180} 'NATIVE_CELL_BOUND'
foreach ($acceptedScenario in $accepted) {
    $expectedRows=@($plan | Where-Object scenario -CEQ $acceptedScenario)
    $scenarioSelected=@(Get-NativeLifecycleSelectedRows $plan @($acceptedScenario) @() 612 180)
    $expectedCount=if ($acceptedScenario -cin @('journal-fault','per-move-fault')) {126} else {18}
    Assert-Integration ($scenarioSelected.Count -eq $expectedCount -and $scenarioSelected.Count -eq $expectedRows.Count -and
        @($scenarioSelected | Where-Object status -CNE 'PENDING').Count -eq 0) ('untruncated pending scenario '+$acceptedScenario)
}
Assert-Integration (@($plan | Where-Object status -CNE 'PENDING').Count -eq 0) 'selection is never execution or acceptance'
Assert-Integration (@(Get-NativeLifecycleSelectedRows $plan @('journal-fault') @() 126 180).Count -eq 126) 'seven journal phases'
Assert-Integration (@(Get-NativeLifecycleSelectedRows $plan @('per-move-fault') @() 126 180).Count -eq 126) 'seven move phases'
Assert-Integration (@(Get-NativeLifecycleSelectedRows $plan @('delta','journal-fault') @() 144 180).Count -eq 144) 'phase-aware mixed count'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan @('journal-fault') @() 125 180} 'NATIVE_CELL_BOUND'
$keys=@('journal-fault/B2/web/unicode/ROLLING_BACK','delta/B1/fx/ascii/SESSION')
$selected=@(Get-NativeLifecycleSelectedRows $plan @('journal-fault','delta') $keys 2 180)
Assert-Integration ($selected.Count -eq 2 -and $selected[0].scenario -ceq 'delta' -and $selected[1].phase -ceq 'ROLLING_BACK') 'exact selection before bound canonical order'
Assert-Integration ([object]::ReferenceEquals($selected[0],$plan[0])) 'selection uses original row reference'
Assert-Integration (@($plan | Where-Object status -CNE 'PENDING').Count -eq 0) 'selection never promotes unexecuted rows'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan @('delta','delta') @() 612 180} 'NATIVE_TWO_BASES_AND_UNIQUE_SCENARIOS'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan @('Delta') @() 612 180} 'DISPATCH_UNKNOWN_SCENARIO'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan @() @() 612 180} 'NATIVE_CELL_BOUND'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan @('delta') @('journal-fault/B1/fx/ascii/WAITING') 1 180} 'COLD_CELL_SELECTION'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan @('delta') @('delta/B1/fx/ascii/SESSION','delta/B1/fx/ascii/SESSION') 2 180} 'COLD_CELL_SELECTION'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows $plan @('journal-fault') @('journal-fault/B1/fx/ascii/WAITING') 1 181} 'DISPATCH_TIMEOUT'
Assert-Integration (@(Get-NativeLifecycleSelectedRows $plan @('delta','journal-fault') @('delta/B1/fx/ascii/SESSION') 1 300).Count -eq 1) 'unselected phase does not constrain normal timeout'
Assert-IntegrationReject {Get-NativeLifecycleSelectedRows @($plan | Select-Object -First 396) @('delta') @() 18 180} 'DISPATCH_CANONICAL_PLAN'

# Используется реальный orchestration AST, но все его эффекты заменены mock функциями.
$orchestration=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.TryStatementAst]})[-1]
$body=$ast.EndBlock.Extent.Text
Assert-Integration ($body.IndexOf('Assert-ColdCommand $cold') -lt $body.IndexOf('Initialize-NativeLifecycleDispatch $PSScriptRoot') -and
    $body.IndexOf('Assert-NativeLifecycleConfig $life') -lt $body.IndexOf('Initialize-NativeLifecycleDispatch $PSScriptRoot') -and
    $body.IndexOf('NATIVE_ASSET_PIN') -lt $body.IndexOf('Initialize-NativeLifecycleDispatch $PSScriptRoot')) 'pins before dispatcher initialization'
Assert-Integration ($body.Contains("throw 'NATIVE_SIGNOFF_INCOMPLETE_UPDATE_EVIDENCE_MATRIX'") -and $body.Contains('$rows.Count -ne 612') -and $body.Contains('NATIVE_SIGNOFF_FROZEN_REQUEST_REQUIRED') -and $body.Contains('NATIVE_SIGNOFF_CURRENT_RESULTS_NOT_SEALED')) 'no default or incomplete signoff'
Assert-Integration ($orchestration.Extent.Text.Contains('Invoke-NativeLifecycleDispatchedCell') -and
    -not $orchestration.Extent.Text.Contains('try {Invoke-NativeCell ')) 'runner routes through dispatcher wrapper'
Assert-Integration ($orchestration.Extent.Text.Contains("finally {Write-ColdJson (Join-Path `$evidence 'results.json')") -and
    $orchestration.Extent.Text.Contains("status=`$(if (`$fatal) {'FAIL'} else {'PENDING'})")) 'exceptions persist full pending plan'

# Mock imports не обходят runtime signature checks frozen dispatcher; native implementations не вызываются.
function Import-NativeDispatchRoute([string]$SourceScriptsRoot,[string]$Route) {
    if ($SourceScriptsRoot -cne $PSScriptRoot) {throw 'NATIVE_INTEGRATION_WRONG_SOURCE_ROOT'}
    $script:integrationCalls.Add([pscustomobject]@{kind='import';route=$Route})
}

# Регистрирует девять исходных arguments и намеренно изменяет context для проверки finally восстановления.
function Register-IntegrationCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout,[string]$Route) {
    $script:integrationCalls.Add([pscustomobject]@{kind='cell';scenario=$Row.scenario;phase=$Row.phase;route=$Route;source=$Source;
        base=$Base;target=$Target;life=$Life;cold=$Cold;java=$Java;evidence=$Evidence;timeout=$Timeout})
    $script:nativeTarget='CHANGED_BY_MOCK';$script:nativeProject='CHANGED_BY_MOCK';$script:nativeProfile='CHANGED_BY_MOCK'
    $script:WorkDir='CHANGED_BY_MOCK';$script:nativeNode='CHANGED_BY_MOCK';$script:nativeUi='CHANGED_BY_MOCK';$script:nativeQuiet='CHANGED_BY_MOCK'
    if ($script:integrationThrow) {throw 'NATIVE_INTEGRATION_MOCK_FAILURE'}
    if ($Route -cne 'normal') {$Row.status='PASS';$Row.reason='MOCK_ONLY_NOT_NATIVE_ACCEPTANCE'}
    if ($Route -ceq 'ready') {return $script:integrationReadyReceipt}
}
foreach ($pair in @(@('Invoke-NativeCell','normal'),@('Invoke-NativeConcurrentScenario','concurrent'),@('Invoke-NativeReadyScenario','ready'),
    @('Invoke-NativePhaseScenario','phase'),@('Invoke-NativePayloadCell','payload'))) {
    $text='function script:'+ $pair[0]+'($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {'+
        'Register-IntegrationCell $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout '+"'"+$pair[1]+"'}"
    . ([scriptblock]::Create($text))
}
$script:nativeDispatchRollbackModule=New-Module -ScriptBlock {
    # Mock private context остаётся в module scope; caller bindings не меняются.
    function Set-NativeRollbackContext([string]$TargetRoot,[string]$ProjectRoot,[string]$ProfileRoot) {$script:target=$TargetRoot}
    # Mock девятипараметровый export подтверждает только передачу аргументов/демоцию PASS.
    function Invoke-NativeRollbackScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
        $Row.status='PASS';$Row.reason='MOCK_ROLLBACK_NOT_NATIVE_ACCEPTANCE';$Row | Add-Member fixturePrivateTarget $script:target -Force
    }
    Export-ModuleMember -Function Set-NativeRollbackContext,Invoke-NativeRollbackScenario
}
# Mock verify никогда не запускает Java.
function Invoke-ColdTool($Config,[string]$Java,[string[]]$Arguments,[string]$Evidence,[string]$Root) {
    $script:integrationCalls.Add([pscustomobject]@{kind='verify';root=$Root});return [pscustomobject]@{mock=$true}
}
# Mock реестр только в памяти, без Windows registry доступа.
function Get-PortableRealRegistrySnapshot {return 'MOCK_REGISTRY_UNCHANGED'}
# In-memory снимки проверяют настоящее finally persistence, но не создают native evidence файлы.
function Write-ColdJson([string]$Path,$Value) {
    $script:integrationWrites.Add([pscustomobject]@{path=$Path;status=$Value.status;cells=@($Value.cells | ForEach-Object {
        [pscustomobject]@{scenario=$_.scenario;base=$_.base;client=$_.client;path=$_.path;phase=$_.phase;status=$_.status;reason=$_.reason}
    })})
}
$bases=@('MOCK_B1','MOCK_B2');$sources=@($bases)+@('MOCK_TARGET');$script:nativeTarget='MOCK_TARGET'
$script:nativeProject='MOCK_PROJECT';$script:nativeProfile='MOCK_PROFILE';$script:WorkDir='ORIGINAL_WORK'
Remove-Variable -Name nativeNode,nativeUi,nativeQuiet -Scope Script -ErrorAction SilentlyContinue
$baseManifests=@{'MOCK_B1'=[pscustomobject]@{base='B1'};'MOCK_B2'=[pscustomobject]@{base='B2'}}
$cold=[pscustomobject]@{baseManifests=@([pscustomobject]@{portableDir='MOCK_B1';manifest='MOCK_MANIFEST1'},[pscustomobject]@{portableDir='MOCK_B2';manifest='MOCK_MANIFEST2'});targetManifest='MOCK_TARGET_MANIFEST'}
$life=[pscustomobject]@{mock='life'};$target=[pscustomobject]@{mock='target'};$java='MOCK_JAVA';$evidence='MOCK_EVIDENCE';$StepTimeoutSeconds=180
$registryBefore='MOCK_REGISTRY_UNCHANGED';$fatal=$null
$script:integrationReadyReceipt=[pscustomobject]@{status='PENDING';scope='MOCK_RECEIPT_NOT_NATIVE_EVIDENCE';receipt='MOCK_ONLY'}
$rows=New-IntegrationPlan
$keys=@('launch-applying-safe-args/B2/web/unicode/SESSION','journal-fault/B1/fx/ascii/WAITING','delta/B1/fx/ascii/SESSION')
$selected=@(Get-NativeLifecycleSelectedRows $rows @('journal-fault','launch-applying-safe-args','delta') $keys 3 180)
$forwarded=@(. ([scriptblock]::Create($orchestration.Extent.Text)))
Assert-Integration ($null -eq $fatal) 'mock orchestration no exception'
Assert-Integration ($forwarded.Count -eq 1 -and $forwarded[0].status -ceq 'PENDING' -and
    $forwarded[0].scope -ceq 'NATIVE_HELPER_RECEIPTS_ONLY' -and
    $forwarded[0].cellKey -ceq 'launch-applying-safe-args/B2/web/unicode/SESSION') 'receipt envelope not acceptance'
Assert-Integration ($forwarded[0].helperReceipts.Count -eq 1 -and
    [object]::ReferenceEquals($forwarded[0].helperReceipts[0],$script:integrationReadyReceipt) -and
    $script:integrationReadyReceipt.status -ceq 'PENDING') 'ready receipt preserved unchanged for independent acceptor'
$calls=@($script:integrationCalls | Where-Object kind -CEQ 'cell')
Assert-Integration (($calls.scenario -join '/') -ceq 'delta/launch-applying-safe-args/journal-fault') 'canonical execution order'
Assert-Integration ($calls[1].source -ceq 'MOCK_B2' -and [object]::ReferenceEquals($calls[1].base,$baseManifests['MOCK_B2']) -and
    [object]::ReferenceEquals($calls[1].target,$target) -and [object]::ReferenceEquals($calls[1].life,$life) -and
    [object]::ReferenceEquals($calls[1].cold,$cold) -and $calls[1].java -ceq $java -and $calls[1].evidence -ceq $evidence -and $calls[1].timeout -eq 180) 'nine arguments base-specific unchanged'
Assert-Integration (@($script:integrationCalls | Where-Object kind -CEQ 'verify').Count -eq 3) 'verify all pinned inputs before cells'
Assert-Integration (@($rows | Where-Object status -CNE 'PENDING').Count -eq 0) 'mock helpers cannot promote any cell'
Assert-Integration ($script:integrationWrites[-1].cells.Count -eq 612 -and $script:integrationWrites[-1].status -ceq 'PENDING') 'full plan persistence pending'
Assert-Integration ($script:integrationWrites.Count -eq 5) 'initial per-cell final persistence'
Assert-Integration ($script:nativeTarget -ceq 'MOCK_TARGET' -and $script:nativeProject -ceq 'MOCK_PROJECT' -and
    $script:nativeProfile -ceq 'MOCK_PROFILE' -and $script:WorkDir -ceq 'ORIGINAL_WORK') 'caller context restored'
Assert-Integration ($null -eq (Get-Variable nativeNode -Scope Script -ErrorAction SilentlyContinue) -and
    $null -eq (Get-Variable nativeUi -Scope Script -ErrorAction SilentlyContinue) -and
    $null -eq (Get-Variable nativeQuiet -Scope Script -ErrorAction SilentlyContinue)) 'absent bindings removed again'
$rollback=@(New-IntegrationPlan | Where-Object scenario -CEQ 'locked-rollback')[0]
Invoke-NativeLifecycleDispatchedCell $rollback 'MOCK_B1' $baseManifests['MOCK_B1'] $target $life $cold $java $evidence 180
Assert-Integration ($rollback.status -ceq 'PENDING' -and $rollback.fixturePrivateTarget -ceq 'MOCK_TARGET') 'private rollback context not direct ambient call'
$rows=New-IntegrationPlan;$selected=@(Get-NativeLifecycleSelectedRows $rows @('two-clients','delta') @('delta/B1/fx/ascii/SESSION','two-clients/B1/fx/ascii/SESSION') 2 180)
$script:integrationThrow=$true;$fatal=$null;$script:integrationWrites.Clear();$script:integrationCalls.Clear()
. ([scriptblock]::Create($orchestration.Extent.Text))
Assert-Integration ($fatal -ceq 'NATIVE_INTEGRATION_MOCK_FAILURE') 'helper exception preserved'
Assert-Integration (@($script:integrationCalls | Where-Object kind -CEQ 'cell').Count -eq 1) 'no following cells after failure'
Assert-Integration (@($rows | Where-Object status -CEQ 'FAIL').Count -eq 1 -and @($rows | Where-Object status -CEQ 'PENDING').Count -eq 611) 'every nonexecuted row pending'
Assert-Integration ($script:integrationWrites[-1].status -ceq 'FAIL' -and $script:integrationWrites[-1].cells.Count -eq 612) 'failure persistence not signoff'
Assert-Integration ($script:nativeTarget -ceq 'MOCK_TARGET' -and $script:WorkDir -ceq 'ORIGINAL_WORK') 'exception restores context'

# Настоящие byte pins на собственных dummy files: rebuild drift отвергается до любого tool/native вызова.
$tempParent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
$owned=Join-Path $tempParent ([guid]::NewGuid().ToString())
if ((Test-Path -LiteralPath $owned) -or [IO.Path]::GetDirectoryName($owned) -cne $tempParent) {throw 'NATIVE_INTEGRATION_TEMP_SCOPE'}
try {
    [void][IO.Directory]::CreateDirectory($owned)
    # Все fixture записи только под literal own Temp UUID.
    function Write-IntegrationFile([string]$Relative,[string]$Text) {
        $path=[IO.Path]::GetFullPath((Join-Path $owned $Relative))
        if (-not $path.StartsWith(($owned+[IO.Path]::DirectorySeparatorChar),[StringComparison]::OrdinalIgnoreCase)) {throw 'NATIVE_INTEGRATION_WRITE_ESCAPE'}
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path));[IO.File]::WriteAllText($path,$Text,[Text.UTF8Encoding]::new($false));return $path
    }
    # Pin вычисляется по реальным fixture bytes, без fake repeated hashes.
    function Get-IntegrationPin([string]$Path) {return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
    $runtime=Write-IntegrationFile 'java.exe' 'MOCK_NOT_EXECUTABLE';$helper=Write-IntegrationFile 'helper.ps1' "throw 'MOCK_NOT_EXECUTED'"
    $tool1=Write-IntegrationFile 'core.jar' 'MOCK_NOT_A_JAR';$tool2=Write-IntegrationFile 'update-tool.jar' 'MOCK_NOT_A_JAR'
    $base1=Write-IntegrationFile 'base1.json' '{}';$base2=Write-IntegrationFile 'base2.json' '{}';$manifest=Write-IntegrationFile 'target.json' '{}'
    $command=[pscustomobject]@{schemaVersion=1;runtimeSha256=(Get-IntegrationPin $runtime);toolArguments=@('--module-path',($tool1+';'+$tool2),'-m','ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool');
        toolFiles=@([pscustomobject]@{path=$tool1;sha256=(Get-IntegrationPin $tool1)},[pscustomobject]@{path=$tool2;sha256=(Get-IntegrationPin $tool2)});
        helperScript=$helper;helperSha256=(Get-IntegrationPin $helper);baseManifests=@([pscustomobject]@{portableDir=(Join-Path $owned 'B1');manifest=$base1;sha256=(Get-IntegrationPin $base1)},
            [pscustomobject]@{portableDir=(Join-Path $owned 'B2');manifest=$base2;sha256=(Get-IntegrationPin $base2)});targetManifest=$manifest;targetManifestSha256=(Get-IntegrationPin $manifest)}
    $commandFile=Write-IntegrationFile 'command.json' (ConvertTo-Json -InputObject $command -Depth 8)
    $commandSha=Get-IntegrationPin $commandFile;$commandBases=@($command.baseManifests.portableDir);$targetRoot=Join-Path $owned 'T'
    $loaded=Read-ColdPinnedJson $commandFile $commandSha
    Assert-ColdCommand $loaded $runtime $commandBases $targetRoot;$script:integrationChecks++
    $null=Write-IntegrationFile 'core.jar' 'MOCK_REBUILT_CHANGED_BYTES'
    Assert-IntegrationReject {Assert-ColdCommand $loaded $runtime $commandBases $targetRoot} 'COLD_TOOL_PIN'
    $null=Write-IntegrationFile 'command.json' '{}'
    Assert-IntegrationReject {Read-ColdPinnedJson $commandFile $commandSha} 'COLD_PIN'
    $server=Write-IntegrationFile 'harness/ru/cashprediction/parity/update/NativeUpdateServer.class' 'MOCK_NOT_JAVA_BYTECODE'
    $classRoot=Join-Path $owned 'harness'
    $config=[pscustomobject]@{schemaVersion=1;artifactDir=$owned;manifestSha256=(Get-IntegrationPin $manifest);harnessClasspath=$classRoot;
        harnessFiles=@([pscustomobject]@{path=$server;sha256=(Get-IntegrationPin $server)})}
    Assert-NativeLifecycleConfig $config;$script:integrationChecks++
    $null=Write-IntegrationFile 'harness/ru/cashprediction/parity/update/NativeUpdateServer.class' 'MOCK_REBUILT_CHANGED_CLASS'
    Assert-IntegrationReject {Assert-NativeLifecycleConfig $config} 'NATIVE_HARNESS_PIN'
} finally {
    if (Test-Path -LiteralPath $owned) {
        $resolved=Resolve-PortableSafetyPath $owned
        if ($resolved -cne $owned -or [IO.Path]::GetDirectoryName($resolved) -cne $tempParent -or
            [IO.Path]::GetFileName($resolved) -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'NATIVE_INTEGRATION_CLEANUP_SCOPE'}
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
Assert-Integration ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1')).Hash -ceq $dispatcherHash) 'frozen dispatcher unchanged'
foreach ($file in Get-NativeDispatchFiles) {Assert-Integration ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash -ceq $helperHashes[$file]) ('source unchanged '+$file)}
[pscustomobject]@{scope='LIFECYCLE_DISPATCH_INTEGRATION_AST_MOCK_AND_PIN_GUARDS';checks=$script:integrationChecks;
    nativeExecuted=$false;nativeStatus='PENDING';canonicalCells=612;fullSignoff='PENDING';retainedSuitesRepeated=$false}
