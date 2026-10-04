# UNIT_MOCK: actual AST pinned adapters и independent guards, без Java/native/GUI/Maven.
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadScenarios.ps1')
Initialize-NativePayloadDependencies $PSScriptRoot
. (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1')
$script:checks=0;$script:actualCalls=0
function Check($Condition,$Name) {if (-not $Condition) {throw ('PINNED_FIXTURE:'+ $Name)};$script:checks++}
function Reject([scriptblock]$Action,$Code) {
    $why=$null;try {& $Action | Out-Null} catch {$why=$_.Exception.Message}
    Check ($why -ceq $Code) ($Code+':'+$why)
}
function Clone($Value) {ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64)}
$definitions=Read-NativeDispatchFunctions $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'
Check (@($definitions | Where-Object Name -CEQ 'Invoke-NativeCell').Count -eq 1) 'EXACT9_SOURCE'
Assert-NativeDispatchCommand 'Invoke-NativeCell'
Check $true 'EXACT9_RUNTIME'
$body=Get-NativeLifecycleBodyDefinition (Get-Command Invoke-NativeCell).ScriptBlock.Ast
Check (($body.Name -creplace '^(global:|script:)','') -ceq 'Invoke-NativeCellOwnedContext') 'SINGLE_BODY'
$row=[pscustomobject]@{scenario='unicode-payload';base='B1';client='web';path='unicode';phase='SESSION';status='PENDING'}
$key='unicode-payload/B1/web/unicode/SESSION'
$entry=[pscustomobject]@{key=$key;file=(Join-Path ([IO.Path]::GetTempPath()) 'pinned-fixture-intent.mock');sha256=('a'*64);evidenceKind='UNVERIFIED'}
$context=[pscustomobject]@{enabled=$true;payloadAuthorityByCell=@{$key=$entry};normalIntentByCell=@{}}
Check ([object]::ReferenceEquals((Get-NativeLifecyclePinnedIntent $context $row 'payload'),$entry)) 'KNOWN_MAIN_SCOPE_MAP'
Check ($null -eq (Get-NativeLifecyclePinnedIntent $null $row 'payload')) 'NO_CONTEXT_LEGACY'
Check ($null -eq (Get-NativeLifecyclePinnedIntent $context $row 'phase')) 'DISJOINT_PHASE_NOT_INTERCEPTED'
$bad=Clone $entry;$bad.sha256='oldpin';$context.payloadAuthorityByCell[$key]=$bad
Reject {Get-NativeLifecyclePinnedIntent $context $row 'payload'} 'NATIVE_PINNED_ENTRY'
$context.payloadAuthorityByCell[$key]=$entry
$bad=Clone $entry;$bad.key='delta/B1/web/unicode/SESSION';$context.payloadAuthorityByCell[$key]=$bad
Reject {Get-NativeLifecyclePinnedIntent $context $row 'payload'} 'NATIVE_PINNED_ENTRY'
$context.payloadAuthorityByCell[$key]=$entry
# Любой настоящий native API запрещён даже при ошибке routing.
function Start-NativeOwned {throw 'PINNED_FIXTURE_FORBIDDEN_NATIVE'}
function Start-NativeFixture {throw 'PINNED_FIXTURE_FORBIDDEN_NATIVE'}
function Invoke-NativePayloadAuthorizedScenario {throw 'PINNED_FIXTURE_FORBIDDEN_AUTHORIZED_NATIVE'}
function Assert-NativeDispatchFrozen {}
function Import-NativeDispatchRoute([string]$SourceScriptsRoot,[string]$Route) {if ($Route -cne 'payload') {throw 'PINNED_FIXTURE_IMPORT_ROUTE'}}
# Mock только executor; actual binding ниже проверяется отдельно.
function Invoke-NativePayloadAuthorizedCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,
    [string]$Evidence,[int]$Timeout,[string]$AuthorityFile,[string]$AuthoritySha256) {
    $script:actualCalls++
    $helper=Clone $Row;$helper.status='PASS';$helper | Add-Member executed $true
    return [pscustomobject]@{status='PENDING';helperRow=$helper;proofComplete=$false;missing=@('UNIT_MOCK_NOT_NATIVE')}
}
$result=Invoke-NativeLifecyclePinnedCell $entry $row 'SOURCE' @{} @{} @{} @{} 'JAVA' 'EVIDENCE' 30
Check ($result.acceptanceVerdict -ceq 'PENDING' -and $script:actualCalls -eq 0 -and $row.status -ceq 'PENDING') 'UNVERIFIED_NO_EXECUTION'
$entry.evidenceKind='UNIT_MOCK'
$result=Invoke-NativeLifecyclePinnedCell $entry $row 'SOURCE' @{} @{} @{} @{} 'JAVA' 'EVIDENCE' 30
Check ($result.acceptanceVerdict -ceq 'PENDING' -and $script:actualCalls -eq 0) 'UNIT_MOCK_NO_EXECUTION'
$entry.evidenceKind='NATIVE'
# Маркер NATIVE здесь только маршрутный mock; итог PENDING и не используется как native evidence.
$result=Invoke-NativeLifecyclePinnedCell $entry $row 'SOURCE' @{} @{} @{} @{} 'JAVA' 'EVIDENCE' 30
Check ($result.status -ceq 'PENDING' -and $result.acceptanceVerdict -ceq 'PENDING' -and
    $row.status -ceq 'PENDING' -and $row.executed -eq $true -and $script:actualCalls -eq 1) 'MOCK_HELPER_PASS_NOT_CANONICAL'
Check ($result.canonicalRowUnchanged -eq $true) 'CANONICAL_STATUS_NOT_IMPORTED'
$row.status='PASS'
Reject {Invoke-NativeLifecyclePinnedCell $entry $row 'SOURCE' @{} @{} @{} @{} 'JAVA' 'EVIDENCE' 30} 'NATIVE_PINNED_CANONICAL_NOT_PENDING'
$row.status='PENDING'
$manifest=[pscustomobject]@{releaseNumber=1;commitSha=('b'*40);treeSha256=('c'*64);files=@()}
$target=Clone $manifest;$target.releaseNumber=3;$target.commitSha='d'*40
$life=[pscustomobject]@{artifactDir='ARTIFACT'};$cold=[pscustomobject]@{targetManifestSha256=('f'*64)}
$ticket=[pscustomobject]@{input=[pscustomobject]@{
    context=[pscustomobject]@{Row=(Clone $row);Source='SOURCE';Java='JAVA';Timeout=30;TargetPortableDir='TARGET'};
    bases=@($manifest,$manifest);target=$target;cold=$cold;life=$life}}
Assert-NativePayloadAuthorityCellBinding $ticket $row 'SOURCE' $manifest $target $life $cold 'JAVA' 30 'TARGET'
Check $true 'REQUIRED_GOOD_ACTUAL_BINDING_GUARD'
Reject {Assert-NativePayloadAuthorityCellBinding $ticket $row 'WRONG_SOURCE' $manifest $target $life $cold 'JAVA' 30 'TARGET'} 'PAYLOAD_AUTHORITY_CALL_BINDING'
Reject {Assert-NativePayloadAuthorityCellBinding $ticket $row 'SOURCE' $manifest $target $life $cold 'JAVA' 31 'TARGET'} 'PAYLOAD_AUTHORITY_CALL_BINDING'
Reject {Assert-NativePayloadAuthorityCellBinding $ticket $row 'SOURCE' $manifest $target $life $cold 'JAVA' 30 'DERIVED_WRONG'} 'PAYLOAD_AUTHORITY_CALL_BINDING'
$bad=Clone $row;$bad.client='fx'
Reject {Assert-NativePayloadAuthorityCellBinding $ticket $bad 'SOURCE' $manifest $target $life $cold 'JAVA' 30 'TARGET'} 'PAYLOAD_AUTHORITY_CELL_IDENTITY'
$bad=Clone $target;$bad.commitSha='e'*40
Reject {Assert-NativePayloadAuthorityCellBinding $ticket $row 'SOURCE' $manifest $bad $life $cold 'JAVA' 30 'TARGET'} 'PAYLOAD_AUTHORITY_IMAGE_IDENTITY'
$bad=Clone $target;$bad.files=@([pscustomobject]@{path='tampered'})
Reject {Assert-NativePayloadAuthorityCellBinding $ticket $row 'SOURCE' $manifest $bad $life $cold 'JAVA' 30 'TARGET'} 'PAYLOAD_AUTHORITY_IMAGE_FILES'
$bad=Clone $cold;$bad.targetManifestSha256='0'*64
Reject {Assert-NativePayloadAuthorityCellBinding $ticket $row 'SOURCE' $manifest $target $life $bad 'JAVA' 30 'TARGET'} 'PAYLOAD_AUTHORITY_CONFIG_BINDING'
$bad=Clone $life;$bad.artifactDir='OLD_ARTIFACT'
Reject {Assert-NativePayloadAuthorityCellBinding $ticket $row 'SOURCE' $manifest $target $bad $cold 'JAVA' 30 'TARGET'} 'PAYLOAD_AUTHORITY_CONFIG_BINDING'
# Actual helper AST сохраняет authority-first/read-only-acceptance порядок.
$t=$null;$e=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'NativeUpdatePayloadScenarios.ps1'),[ref]$t,[ref]$e)
$adapter=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Invoke-NativePayloadAuthorizedCell'})[0].Extent.Text
Check ($e.Count -eq 0 -and $adapter.IndexOf('Assert-NativePayloadAuthorityCellBinding') -lt $adapter.IndexOf('Invoke-NativePayloadAuthorizedScenario')) 'BINDING_BEFORE_EXECUTOR'
Check ($adapter.IndexOf('Read-ColdPinnedJson $executed.authority.file') -lt $adapter.IndexOf('Test-NativePayloadAcceptance')) 'SEALED_EXPECTED_BEFORE_ACCEPTANCE'
Check ($adapter.IndexOf('Get-FileHash') -lt $adapter.IndexOf('Test-NativePayloadAcceptance')) 'OBSERVATION_INDEPENDENT_SHA'
[pscustomobject]@{status='UNIT_MOCK';checks=$script:checks;nativeExecuted=$false;nativeStatus='PENDING';fullMatrix='PENDING';releaseProvenance='PENDING'} | ConvertTo-Json
