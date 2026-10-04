# UNIT_MOCK: actual AST collector/dispatcher/prefix, только собственные файлы, без native/JDK/GUI.
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadScenarios.ps1')
Initialize-NativePayloadDependencies $PSScriptRoot
. (Join-Path $PSScriptRoot 'NativeUpdatePayloadAcceptance.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateNormalAcceptance.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1')
# Actual dispatcher importer экспортирует функции в script scope; closure сохраняет binder.
Import-NativeDispatchFunctions $PSScriptRoot 'NativeUpdateNormalAcceptanceCollector.ps1'
$script:checks=0
function Check($Condition,$Name) {if (-not $Condition) {throw ('UNIT_MOCK_ASSERT:'+ $Name)};$script:checks++}
function Reject([scriptblock]$Action,$Code) {
    $message=$null;try {& $Action | Out-Null} catch {$message=$_.Exception.Message}
    Check ($message -ceq $Code) ($Code+':'+$message)
}
function Copy-Value($Value) {ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64)}
$null=Read-NativeDispatchFunctions $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'
Assert-NativeDispatchCommand 'Invoke-NativeCell'
Check $true 'REAL_DISPATCH_EXACT9'
# Каждая normal closure dependency входит в frozen inventory и имеет actual AST exports.
Check (@(Get-NativeNormalSourceFiles).Count -eq 13) 'NORMAL_CLOSURE_EXACT13'
foreach ($file in Get-NativeNormalSourceFiles) {
    Check ($file -cin @(Get-NativeDispatchFiles)) ('NORMAL_CLOSURE_SOURCE_PINNED:'+ $file)
    Check (@(Read-NativeDispatchFunctions $PSScriptRoot $file).Count -gt 0) ('NORMAL_CLOSURE_EXPORTS:'+ $file)
}
foreach ($name in 'New-NativeNormalCollector','Bind-NativeNormalCollector','Invoke-NativeNormalCollectedCell','Test-NativeNormalCollectedAcceptance') {
    $command=Get-Command $name -ErrorAction Stop
    Check ($command.CommandType -eq 'Function' -and $command.Name -ceq $name) ('NORMAL_ACTUAL_EXPORT:'+ $name)
}
$t=$null;$e=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'),[ref]$t,[ref]$e)
$body=Get-NativeLifecycleBodyDefinition $ast
Check ($body.Name -ceq 'Invoke-NativeCellOwnedContext' -and $e.Count -eq 0) 'PINNED_SINGLE_REAL_BODY'
$bad=[Management.Automation.Language.Parser]::ParseInput('function Invoke-NativeCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout,$X,$Y) {}',[ref]$t,[ref]$e)
Reject {Assert-NativeDispatchSignature $bad.EndBlock.Statements[0] 'Invoke-NativeCell'} 'DISPATCH_SIGNATURE Invoke-NativeCell'
$bad=[Management.Automation.Language.Parser]::ParseInput('function Invoke-NativeCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {Start-NativeOwned};function Invoke-NativeCellOwnedContext() {}',[ref]$t,[ref]$e)
Reject {Get-NativeLifecycleBodyDefinition $bad} 'NATIVE_BODY_DUPLICATED'

$fixture=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString());$null=New-Item -ItemType Directory $fixture
$source=Join-Path $fixture 'source';$target=Join-Path $fixture 'target';$evidence=Join-Path $fixture 'evidence'
foreach ($p in $source,$target,$evidence) {$null=New-Item -ItemType Directory $p}
Write-ColdJson (Join-Path $source 'managed.json') @{bytes='old'}
Write-ColdJson (Join-Path $target 'managed.json') @{bytes='new'}
$script:inputFiles=@($source,$target);$script:fixture=$fixture
$base=[pscustomobject]@{files=@();releaseNumber=1;commitSha=('a'*40)}
$targetManifest=[pscustomobject]@{files=@();releaseNumber=2;commitSha=('b'*40)}
$cold=[pscustomobject]@{mock='UNIT_MOCK_COMMAND'};$life=[pscustomobject]@{mock='UNIT_MOCK_LIFE';artifactDir=$target}
$intent=[pscustomobject]@{schemaVersion=1;Scenario='delta';Base='B1';Client='web';Path='ascii';Phase='SESSION';
    SourceRoot=$source;TargetRoot=$target;Java=(Join-Path $fixture 'not-executable.mock');InputSnapshotSha256=''}
foreach ($pair in @(@('BaseManifest',$base),@('TargetManifest',$targetManifest),@('Command',$cold),@('Lifecycle',$life))) {
    $file=Join-Path $fixture ($pair[0]+'.json');Write-ColdJson $file $pair[1]
    $intent | Add-Member ($pair[0]+'File') $file
    $intent | Add-Member ($pair[0]+'Sha256') ((Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant())
    $script:inputFiles+=@($file)
}
# Только input snapshot mock; SHA/JSON чтение, source immutability и callback guards реальные.
function Get-NormalAcceptanceInput($File,$Pin) {
    $text=Get-NativePayloadSnapshot $script:inputFiles
    [pscustomobject]@{sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()}
}
$intentFile=Join-Path $fixture 'intent.json'
Write-ColdJson $intentFile $intent
$intent.InputSnapshotSha256=(Get-NormalAcceptanceInput '' '').sha256;Write-ColdJson $intentFile $intent
$intentPin=(Get-FileHash -LiteralPath $intentFile).Hash.ToLowerInvariant()
$a=New-NativeNormalCollector $intentFile $intentPin 'UNIT_MOCK'
Check (-not $a.bound -and -not $a.sealed) 'TRUST_BEFORE_NATIVE'
Check ((Test-NativeNormalCollectedAcceptance $a).status -ceq 'PENDING') 'UNBOUND_NOT_PASS'
$script:nativeTarget=$target;$script:nativeProject=$fixture;$script:nativeProfile=$fixture
# Префикс из actual private body останавливается до try/domain CLI/native; guards не заменены.
$prefix=@()
foreach ($s in $body.Body.EndBlock.Statements) {
    if ($s -is [Management.Automation.Language.TryStatementAst]) {break}
    $prefix+=@($s.Extent.Text)
}
# Actual prefix работает при -File, & и dot-source без scope-зависимых Function provider paths.
. ([scriptblock]::Create('function global:Invoke-NativeCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {$OwnedRunRoot="";$OwnedNodeNonce="";'+($prefix -join "`n")+';$Row.status="PASS";$Row | Add-Member reason "UNIT_MOCK_NOT_NATIVE" -Force}'))
function Get-ValidatedPortablePaths {return $null}
$row=[pscustomobject]@{scenario='delta';base='B1';client='web';path='ascii';phase='SESSION';status='PENDING'}
# Подмена удержанного MAIN intent/pins отвергается ещё до actual prefix.
$memory=New-NativeNormalCollector $intentFile $intentPin 'UNIT_MOCK'
$memory.intent.SourceRoot=$target
Reject {Invoke-NativeNormalCollectedCell $memory $row $source $base $targetManifest $life $cold $intent.Java $evidence 30} 'NORMAL_INTENT_MEMORY_CHANGED'
Check (-not $memory.bound -and -not $memory.sealed) 'MEMORY_TAMPER_NEVER_BINDS'
$memory.intent.SourceRoot=$source
$memory.sourcePins['NativeUpdateScenarioDispatch.ps1']='0'*64
Reject {Bind-NativeNormalCollector $memory ([pscustomobject]@{})} 'NORMAL_SOURCE_PIN:NativeUpdateScenarioDispatch.ps1'
$memory.sourcePins['NativeUpdateScenarioDispatch.ps1']=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'NativeUpdateScenarioDispatch.ps1')).Hash.ToLowerInvariant()
$memory.sourcePins['extra.ps1']='a'*64
Reject {Bind-NativeNormalCollector $memory ([pscustomobject]@{})} 'NORMAL_SOURCE_PINS_CLOSURE'
$memory.sourcePins.Remove('extra.ps1')
# Exact9 проверяется и при прямом MAIN вызове, а не только через dispatcher.
Set-Alias -Name Invoke-NativeCell -Value Write-Output -Scope Local
try {Reject {Invoke-NativeNormalCollectedCell $memory $row $source $base $targetManifest $life $cold $intent.Java $evidence 30} 'DISPATCH_COMMAND Invoke-NativeCell'}
finally {Remove-Item -LiteralPath Alias:Invoke-NativeCell -ErrorAction Stop}
$sentinel={throw 'PRIOR_SCOPE_CALLBACK_MUST_BE_RESTORED'};$script:NativeNormalBeforeNativeCollector=$sentinel
$result=Invoke-NativeNormalCollectedCell $a $row $source $base $targetManifest $life $cold $intent.Java $evidence 30
Check ($a.bound -and -not $a.sealed -and $result.status -ceq 'PENDING') ('ACTUAL_PREFIX_BINDS_MAIN_TICKET:'+ (ConvertTo-Json $result -Compress))
Check ($script:NativeNormalBeforeNativeCollector -eq $sentinel) 'CALLBACK_SCOPE_RESTORED'
Check ((Get-Command Invoke-NativeNormalCollectedCell).ScriptBlock.ToString().Contains('& $binder $ticket $context')) 'ACTUAL_AST_IMPORT_RETAINS_BINDER_SCRIPTBLOCK'
Check ($row.status -ceq 'PENDING' -and $result.helperRow.status -ceq 'PASS' -and $result.status -ceq 'PENDING') 'HELPER_PASS_CANNOT_PROMOTE_CANONICAL'
Check (Test-ColdInventoryEqual (Convert-NormalBindingValue ([pscustomobject]@{publishedAtUtc='2026-10-04T00:00:00Z'})) (Convert-NormalBindingValue ([pscustomobject]@{publishedAtUtc=[datetime]::Parse('2026-10-04T00:00:00Z')}))) 'ACTUAL_READER_DATETIME_CANONICAL'
Reject {Bind-NativeNormalCollector $a ([pscustomobject]@{})} 'NORMAL_REBIND'
$wrong=New-NativeNormalCollector $intentFile $intentPin 'UNIT_MOCK'
$context=[pscustomobject]@{scenario='delta';base='B1';client='web';path='ascii';phase='SESSION';source=$target;target=$target;java=$intent.Java}
Reject {Bind-NativeNormalCollector $wrong $context} 'NORMAL_BIND_source'
# Подмена binder alias отвергается до prefix/native, настоящий ticket не связывается.
$wrongBinder=New-NativeNormalCollector $intentFile $intentPin 'UNIT_MOCK'
Set-Alias -Name Bind-NativeNormalCollector -Value Write-Output -Scope Local
try {
    Reject {Invoke-NativeNormalCollectedCell $wrongBinder $row $source $base $targetManifest $life $cold $intent.Java $evidence 30} 'NORMAL_BINDER_KIND'
    Check (-not $wrongBinder.bound -and -not $wrongBinder.sealed -and $row.status -ceq 'PENDING') 'WRONG_BINDER_FAILS_BEFORE_NATIVE'
} finally {Remove-Item -LiteralPath Alias:Bind-NativeNormalCollector -ErrorAction Stop}
$root=$a.expected.InstalledRoot;$start=[datetime]$a.expected.PreparedUtc

# OS/CIM/retained process seams исключительно UNIT_MOCK, ни OpenProcess, ни GUI.
function Get-ColdProcessReceipt($Process,$Root) {return $Process.receipt}
function Get-CopyProcesses {return @()}
function Get-NormalRecoveryCensus {return @()}
function Get-PortableRegistryPath {return (Join-Path $script:fixture 'absent-registry.mock')}
function Get-ColdManagedInventory {return @()}
function Test-NativePreparationFinished {return $true}
$epoch=[pscustomobject]@{ProcessId=123;StartedAtTicks=$start.Ticks;ExecutablePath='C:\mock.exe';OwnedRoot=$root}
$process=[pscustomobject]@{HasExited=$true;receipt=$epoch}
Save-NormalRetainedExit $a 'launcher' 0 $process $epoch $root
Check ($a.observations.Count -eq 1) 'RETAINED_RECEIPT_BEFORE_DISPOSE'
$process.receipt=Copy-Value $epoch;$process.receipt.StartedAtTicks++
Reject {Save-NormalRetainedExit $a 'launcher' 0 $process $epoch $root} 'NORMAL_RETAINED_EPOCH'
$process.receipt=$epoch;$process.HasExited=$false
Reject {Save-NormalRetainedExit $a 'launcher' 0 $process $epoch $root} 'NORMAL_RETAINED_EPOCH'
$process.HasExited=$true
$native=[pscustomobject]@{ui=$null;uiProcess=$null}
$null=Save-NormalStage $a 'nonReady' 0 $root $base $targetManifest $native
Check ($a.pending.Contains('ACTUAL_UI:nonReady')) 'MISSING_UI_NOT_FABRICATED'
Write-ColdJson (Join-Path $a.expected.CellEvidence 'normal-BEFORE--1.json') @{stage='BEFORE';session=-1;observedUtc=$start.ToString('o');ready=$false;files=@()}
Write-ColdJson (Join-Path $a.expected.CellEvidence 'cell.json') @{scenario='delta';base='B1';client='web';path='ascii';phase='SESSION';
    status='PASS';executed=$true;startedAt=$start.ToString('o');finishedAt=[datetime]::UtcNow.ToString('o')}
# Base reader mock сохраняет все legacy missing reasons и не объявляет native PASS.
function Read-NormalAcceptance {return [pscustomobject]@{errors=@();checked=@();missing=@('POST_FINALLY_CLIENT_HELPER_SERVER_REGISTRY_CENSUS_NOT_PERSISTED','LIVE_NONPOLLING_OBSERVATION_NOT_PRODUCED:delta')}}
# Ошибка authority после cleanup не должна записывать index или объявлять sealed.
$sealPin=$a.sourcePins['NativeUpdateScenarioDispatch.ps1'];$a.sourcePins['NativeUpdateScenarioDispatch.ps1']='0'*64
Reject {Complete-NormalCollector $a $root $start @()} 'NORMAL_SOURCE_PIN:NativeUpdateScenarioDispatch.ps1'
Check (-not $a.sealed -and -not (Test-Path -LiteralPath (Join-Path $a.expected.CellEvidence 'normal-index.json'))) 'CHANGED_SEAL_AUTHORITY_NEVER_SEALS'
$a.sourcePins['NativeUpdateScenarioDispatch.ps1']=$sealPin
$a.intent.SourceRoot=$target
Reject {Complete-NormalCollector $a $root $start @()} 'NORMAL_INTENT_MEMORY_CHANGED'
$a.intent.SourceRoot=$source
Complete-NormalCollector $a $root $start @()
$index=Read-ColdPinnedJson $a.indexFile $a.indexSha256
$cleanup=Read-ColdPinnedJson (Join-Path $a.expected.CellEvidence 'normal-cleanup.json') (@($index.files | Where-Object path -CEQ 'normal-cleanup.json')[0].sha256)
Check ($a.sealed -and [datetime]$index.sealedUtc -ge [datetime]$cleanup.observedUtc) 'POST_CLEANUP_HELD_SHA_SEAL'
$partial=Test-NativeNormalCollectedAcceptance $a
Check ($partial.status -ceq 'PENDING') ('PARTIAL_UI_CENSUS_PENDING:'+ (ConvertTo-Json @{result=$partial;cleanup=$cleanup;sealed=$index.sealedUtc;cell=(Get-Content (Join-Path $a.expected.CellEvidence 'cell.json') -Raw)} -Depth 8 -Compress))
# Независимый reseal допустим ТОЛЬКО внутри UNIT_MOCK negative fixture.
$savedCreated=$a.createdUtc;$a.createdUtc=[datetime]::UtcNow.AddDays(1).ToString('o')
$late=Test-NativeNormalCollectedAcceptance $a
Check ($late.status -ceq 'FAIL' -and $late.errors -ccontains 'NORMAL_PRE_NATIVE_AUTHORITY') 'LATE_EXPECTED_AUTHORITY_REJECTED'
$a.createdUtc=$savedCreated
$savedPin=$a.sourcePins['NativeUpdateScenarioDispatch.ps1'];$a.sourcePins['NativeUpdateScenarioDispatch.ps1']='0'*64
$changed=Test-NativeNormalCollectedAcceptance $a
Check ($changed.status -ceq 'FAIL' -and $changed.errors -ccontains 'NORMAL_SOURCE_PIN:NativeUpdateScenarioDispatch.ps1') 'DISPATCH_SOURCE_PIN_TAMPER_REJECTED'
$a.sourcePins['NativeUpdateScenarioDispatch.ps1']=$savedPin
function Reseal-UnitIndex($Authority) {
    if ($Authority.kind -cne 'UNIT_MOCK') {throw 'FIXTURE_RESEAL_KIND'}
    $mockIndex=Read-ColdPinnedJson $Authority.indexFile $Authority.indexSha256
    foreach ($entry in $mockIndex.files) {$entry.sha256=(Get-FileHash -LiteralPath (Join-Path $Authority.expected.CellEvidence $entry.path)).Hash.ToLowerInvariant()}
    Write-ColdJson $Authority.indexFile $mockIndex
    $Authority.indexSha256=(Get-FileHash -LiteralPath $Authority.indexFile).Hash.ToLowerInvariant()
}
$cleanupFile=Join-Path $a.expected.CellEvidence 'normal-cleanup.json'
$savedCleanup=Copy-Value $cleanup
$cleanup.observedUtc=$start.AddSeconds(-1).ToString('o');Write-ColdJson $cleanupFile $cleanup;Reseal-UnitIndex $a
$stale=Test-NativeNormalCollectedAcceptance $a
Check ($stale.status -ceq 'FAIL' -and $stale.errors -ccontains 'NORMAL_CLEANUP_TIME') 'STALE_CLEANUP_EVEN_REPINNED_FAIL'
Write-ColdJson $cleanupFile $savedCleanup;Reseal-UnitIndex $a
$cleanup=Copy-Value $savedCleanup;$cleanup.PSObject.Properties.Remove('retained');Write-ColdJson $cleanupFile $cleanup;Reseal-UnitIndex $a
$partialCleanup=Test-NativeNormalCollectedAcceptance $a
Check ($partialCleanup.status -ceq 'PENDING' -and $partialCleanup.missing -ccontains 'COLLECTOR_CLEANUP_BEFORE_FIELDS') 'PARTIAL_CLEANUP_PENDING'
Write-ColdJson $cleanupFile $savedCleanup;Reseal-UnitIndex $a
$index=Read-ColdPinnedJson $a.indexFile $a.indexSha256
$originalKind=$a.kind;$a.kind='NATIVE'
Check ((Test-NativeNormalCollectedAcceptance $a).status -ceq 'FAIL') 'UNIT_MOCK_KIND_PROMOTION_REJECTED'
$a.kind=$originalKind
Write-ColdJson $a.expectedFile ([pscustomobject]@{TargetRoot='wrong-target';schemaVersion=1})
Check ((Test-NativeNormalCollectedAcceptance $a).status -ceq 'FAIL') 'PRE_NATIVE_EXPECTED_TAMPER_HELD_SHA'
Write-ColdJson $a.expectedFile $a.expected
$index.nonce='tampered';Write-ColdJson $a.indexFile $index
Check ((Test-NativeNormalCollectedAcceptance $a).status -ceq 'FAIL') 'SEALED_INDEX_TAMPER'
Write-ColdJson (Join-Path $source 'managed.json') @{bytes='tampered'}
Reject {New-NativeNormalCollector $intentFile $intentPin 'UNIT_MOCK'} 'NORMAL_INTENT_INPUTS'

# Настоящий quiet reader guard, synthetic samples явно UNIT_MOCK: stale/partial/tamper/drop-epoch.
$ui=Copy-Value $epoch;$server=Copy-Value $epoch;$server.ProcessId=456;$server.OwnedRoot='C:\mock-server'
$launch=[pscustomobject]@{ui=[pscustomobject]@{pid=$ui.ProcessId;startedAtTicks=$ui.StartedAtTicks;executablePath=$ui.ExecutablePath;
    lease=[pscustomobject]@{installationRoot=$root}}}
$http=[pscustomobject]@{traceSha256=('a'*64);traceBytes=1;trace='x';stats=[pscustomobject]@{count=1}}
$quiet=[pscustomobject]@{windowMillis=5000;elapsedMillis=5000L;cancelStream=$false;samples=@(
    [pscustomobject]@{elapsedMillis=0L;observedUtc=$start.AddSeconds(1).ToString('o');ui=$ui;server=$server;http=$http},
    [pscustomobject]@{elapsedMillis=5000L;observedUtc=$start.AddSeconds(6).ToString('o');ui=$ui;server=$server;http=$http})}
Assert-NormalCollectedQuiet $quiet $launch $server $start $start.AddSeconds(7) $false
Check $true 'REQUIRED_GOOD_5000_WINDOW_MOCK_ONLY'
$bad=Copy-Value $quiet;$bad.windowMillis=4999
Reject {Assert-NormalCollectedQuiet $bad $launch $server $start $start.AddSeconds(7) $false} 'NORMAL_QUIET_WINDOW'
$bad=Copy-Value $quiet;$bad.samples[0].observedUtc=$start.AddSeconds(-1).ToString('o')
Reject {Assert-NormalCollectedQuiet $bad $launch $server $start $start.AddSeconds(7) $false} 'NORMAL_QUIET_EPOCH_TIME'
$bad=Copy-Value $quiet;$bad.samples[1].ui.StartedAtTicks++
Reject {Assert-NormalCollectedQuiet $bad $launch $server $start $start.AddSeconds(7) $false} 'NORMAL_QUIET_EPOCH_TIME'
$bad=Copy-Value $quiet;$bad.samples[1].ui.PSObject.Properties.Remove('StartedAtTicks')
Reject {Assert-NormalCollectedQuiet $bad $launch $server $start $start.AddSeconds(7) $false} 'NORMAL_QUIET_EVIDENCE_PENDING'
$bad=Copy-Value $quiet;$bad.samples[1].http.trace='tampered'
Reject {Assert-NormalCollectedQuiet $bad $launch $server $start $start.AddSeconds(7) $false} 'NATIVE_NONPOLLING_CHANGED'
$bad=Copy-Value $quiet;$bad.samples[1].observedUtc=$start.AddSeconds(5).ToString('o')
Reject {Assert-NormalCollectedQuiet $bad $launch $server $start $start.AddSeconds(7) $false} 'NORMAL_QUIET_WALL_WINDOW'
$cancel=Copy-Value $quiet;$cancel.cancelStream=$true
foreach ($sample in $cancel.samples) {$sample.http=[pscustomobject]@{metadata=@([pscustomobject]@{id=1;event='START';path='/update.json'})}}
Assert-NormalCollectedQuiet $cancel $launch $server $start $start.AddSeconds(7) $true
Check $true 'CANCEL_METADATA_WINDOW_NOT_IDLE'
$cancel.samples[1].http.metadata[0].id=2
Reject {Assert-NormalCollectedQuiet $cancel $launch $server $start $start.AddSeconds(7) $true} 'NORMAL_CANCEL_POLLING'
# AST порядок защищает before-dispose и seal-after-cleanup, без исполнения native body.
$text=$body.Extent.Text
Check ($text.IndexOf('Save-NormalRetainedExit $normalAuthority') -lt $text.IndexOf('$server.process.Dispose()')) 'SERVER_BEFORE_DISPOSE'
Check ($text.IndexOf('Write-ColdJson (Join-Path $cellEvidence ''cell.json'')') -lt $text.IndexOf('Complete-NormalCollector')) 'CELL_THEN_SEAL'
Write-Output ("Normal collector fixtures: "+$script:checks+" checks; UNIT_MOCK_ONLY; native/Java/GUI not executed; S7 PENDING; evidence="+$fixture)
