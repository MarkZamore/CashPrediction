<#
.SYNOPSIS
Actual dispatcher AST с UNIT_MOCK transport callbacks, без native/Java/GUI.
#>
[CmdletBinding()]param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateNormalAcceptanceCollector.ps1')
Import-NativeDispatchFunctions $PSScriptRoot 'Test-UpdateBootstrap.ps1' @('Assert-ColdKeys')
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'),[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'FIXTURE_PARSE'}
$definition=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Get-NativeLifecyclePinnedIntent'})[0]
. ([scriptblock]::Create($definition.Extent.Text))
$checks=0;$script:events=[Collections.Generic.List[string]]::new();$script:lastAuthority=$null;$script:tamper=$false
# Только fixture assertions, не native verdict.
function Check([bool]$Value,[string]$Code) {if (-not $Value) {throw $Code};$script:checks++}
# Моки исключают любые ОС операции; source inventory остаётся actual закрытым списком.
function Assert-NativeDispatchFrozen {}
function Import-NativeDispatchRoute($Root,$Route) {}
function Import-NativeDispatchFunctions($Root,$File) {}
function Assert-NativeDispatchCommand($Name) {Check ($Name -ceq 'Invoke-NativeCell') 'EXACT9_ROUTE_COMMAND'}
function New-NativeNormalCollector($File,$Pin,$Kind) {
    $script:events.Add('BEFORE_NATIVE');$sources=@{}
    foreach ($name in Get-NativeNormalSourceFiles) {$sources[$name]=$script:nativeDispatchState.Pins[$name].ToLowerInvariant()}
    if ($script:tamper) {$sources['NativeUpdateScenarioDispatch.ps1']='0'*64}
    $script:lastAuthority=[pscustomobject]@{origin='UNIT_MOCK';sourcePins=$sources;sealed=$false;indexSha256=$null}
    return $script:lastAuthority
}
function Invoke-NativeNormalCollectedCell($Authority,$Row,$Source,$Base,$Target,$Life,$Cold,$Java,$Evidence,$Timeout) {
    Check ([object]::ReferenceEquals($Authority,$script:lastAuthority)) 'PREBOUND_AUTHORITY'
    Check ($script:events[-1] -ceq 'BEFORE_NATIVE') 'BEFORE_HELPER_ORDER'
    $script:events.Add('UNIT_MOCK_HELPER');$Authority.sealed=$true;$Authority.indexSha256='b'*64
    $helper=[pscustomobject]@{scenario=$Row.scenario;base=$Row.base;client=$Row.client;path=$Row.path;phase=$Row.phase;status='PASS';reason='UNIT_MOCK_NOT_NATIVE'}
    return [pscustomobject]@{status='PENDING';helperRow=$helper}
}
function Invoke-NativeUpdateAcceptanceDispatch($Row,$Envelope,$Base,$Target,$Cold,$Java,$Evidence,$EvidenceKind) {
    if ($null -eq $Evidence) {
        Check ($script:events.Count -eq $script:missingBefore) 'NO_INTENT_NEVER_EXECUTES_HELPER'
        return [pscustomobject]@{status='PENDING';scope='SELECTED_NATIVE_CELL_ACCEPTANCE_ONLY';cellKey=($Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase);
            canonicalRowUnchanged=$true;gaps=@('MAIN_PRE_NATIVE_NORMAL_INTENT_REQUIRED');contradictions=@();evidenceKind='UNIT_MOCK';routeEvidenceValidated=$false}
    }
    Check ([object]::ReferenceEquals($Evidence.Authority,$script:lastAuthority)) 'BRIDGE_SAME_HELD_AUTHORITY'
    Check ($Evidence.Authority.sealed -and $Evidence.Authority.indexSha256 -ceq ('b'*64)) 'POST_CLEANUP_INDEX_HELD'
    Check ($Evidence.SourcePins -is [Collections.IDictionary] -and -not [object]::ReferenceEquals($Evidence.SourcePins,$Evidence.Authority.sourcePins)) 'INDEPENDENT_SOURCE_PINS'
    Check ($Row.status -ceq 'PENDING') 'HELPER_PASS_DEMOTED'
    $script:events.Add('BRIDGE_READ')
    return [pscustomobject]@{status='PENDING';scope='SELECTED_NATIVE_CELL_ACCEPTANCE_ONLY';cellKey=($Row.scenario+'/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/'+$Row.phase);
        canonicalRowUnchanged=$true;gaps=@('UNIT_MOCK_NOT_NATIVE');contradictions=@();evidenceKind='UNIT_MOCK';routeEvidenceValidated=$false}
}
$pins=@{};foreach ($file in Get-NativeDispatchFiles) {$pins[$file]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash}
$script:nativeDispatchState=[pscustomobject]@{SourceScriptsRoot=$PSScriptRoot;Pins=$pins}
$script:nativeAcceptanceContext=[pscustomobject]@{enabled=$true;normalIntentByCell=@{}}
$script:missingBefore=$script:events.Count
$missingRow=[pscustomobject]@{scenario='delta';base='B1';client='web';path='unicode';phase='SESSION';status='PENDING';reason='UNIT_MOCK'}
$missingResult=Invoke-NativeScenarioDispatch $missingRow 'S' $null $null $null $null 'J' 'E' 180
Check ($missingRow.status -ceq 'PENDING' -and $missingResult.acceptanceVerdict.status -ceq 'PENDING') 'MISSING_INTENT_PENDING'
foreach ($scenario in 'delta','corrupt-delta-full','corrupt-full-retain','cancel-next-session','offline','timeout','malformed','once-three-attempts','leases-normal-close') {
    $row=[pscustomobject]@{scenario=$scenario;base='B1';client='web';path='unicode';phase='SESSION';status='PENDING';reason='UNIT_MOCK'}
    $key=$scenario+'/B1/web/unicode/SESSION'
    $script:nativeAcceptanceContext.normalIntentByCell[$key]=[pscustomobject]@{key=$key;file=(Join-Path $PSScriptRoot 'NativeUpdateNormalAcceptanceCollector.ps1');sha256=('a'*64);evidenceKind='NATIVE'}
    $result=Invoke-NativeScenarioDispatch $row 'MOCK_SOURCE' $null $null $null $null 'MOCK_JAVA' 'MOCK_EVIDENCE' 180
    Check ($row.status -ceq 'PENDING' -and $result.acceptanceVerdict.status -ceq 'PENDING' -and $script:events[-1] -ceq 'BRIDGE_READ') 'NO_NATIVE_PASS'
}
$row=[pscustomobject]@{scenario='delta';base='B1';client='web';path='unicode';phase='SESSION';status='PENDING';reason='UNIT_MOCK'}
$script:tamper=$true;$before=$script:events.Count
try {$null=Invoke-NativeScenarioDispatch $row 'S' $null $null $null $null 'J' 'E' 180;throw 'TAMPER_NOT_REJECTED'}
catch {Check ($_.Exception.Message -ceq 'NORMAL_PRE_NATIVE_SOURCE_PIN:NativeUpdateScenarioDispatch.ps1') 'OLD_SOURCE_PIN_REJECTED_BEFORE_NATIVE'}
Check ($script:events.Count -eq $before+1) 'TAMPER_NEVER_EXECUTES_HELPER'
# Нельзя получить native через неверный key или неподтверждённый evidenceKind.
$script:tamper=$false
$entry=$script:nativeAcceptanceContext.normalIntentByCell['delta/B1/web/unicode/SESSION']
$entry.key='offline/B1/web/unicode/SESSION'
$row.status='PENDING'
try {$null=Invoke-NativeScenarioDispatch $row 'S' $null $null $null $null 'J' 'E' 180;throw 'WRONG_KEY_NOT_REJECTED'}
catch {Check ($_.Exception.Message -ceq 'NATIVE_PINNED_ENTRY') ('WRONG_MAIN_KEY:'+ $_.Exception.Message)}
$entry.key='delta/B1/web/unicode/SESSION';$entry.evidenceKind='UNIT_MOCK';$row.status='PENDING'
$script:missingBefore=$script:events.Count
$mockResult=Invoke-NativeScenarioDispatch $row 'S' $null $null $null $null 'J' 'E' 180
Check ($row.status -ceq 'PENDING' -and $mockResult.acceptanceVerdict.status -ceq 'PENDING') 'UNIT_MOCK_INTENT_NEVER_NATIVE'
[pscustomobject]@{checks=$checks;scope='ACTUAL_DISPATCH_AST_UNIT_MOCK_ONLY';nativeExecuted=$false;nativeStatus='PENDING';fullMatrix='PENDING'}
