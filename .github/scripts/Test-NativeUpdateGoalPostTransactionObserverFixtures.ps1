<#
.SYNOPSIS
Focused PS integration execution новых entry/guards; actual host handle read-only, без GUI/native/JDK.
.DESCRIPTION
Production orchestration исполняется с transport/start/cleanup substitutes только в отдельном PS.
Отдельно actual Read/Stop/native-cleanup проверяют stale identity реального удерживаемого host handle.
Это не actual DOM, не native execution/PASS и не повторение 47 seed fixtures.
#>
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$imports=@{'Test-Portable.ps1'=@('Resolve-PortableSafetyPath','Get-PortableRegistryPath');
    'Test-UpdateBootstrap.ps1'=@('Test-ColdInteger','Get-ColdUtcTicks','Test-ColdInventoryEqual','ConvertFrom-ColdReceiptJson',
        'Write-ColdJson','Get-ColdProcessReceipt','Assert-ColdProcessIdentity')}
foreach ($file in $imports.Keys) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors)
    foreach ($name in $imports[$file]) {
        $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($errors.Count -or $nodes.Count -ne 1) {throw 'GOAL_INTEGRATION_IMPORT'};. ([scriptblock]::Create($nodes[0].Extent.Text))
    }
}
. (Join-Path $PSScriptRoot 'NativeUpdatePhaseSessionCollector.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateRestoredWindowObservation.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateGoalRestorationProbe.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateWebGoalRestorationBridge.ps1')
# Сохраняем actual functions ДО substitutes. Только один отдельный fixture PS process.
$actualRead=${function:Read-NativeWebGoalRestorationObservation}
$actualStop=${function:Stop-NativeWebGoalRestorationBridge}
$actualNativeCleanup=${function:Complete-NativeGoalPostTransactionClient}
$directory=Join-Path ([IO.Path]::GetTempPath()) ('cp-goal-observer-integration-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($directory)
$hostHandle=[Diagnostics.Process]::GetCurrentProcess()
$hostIdentity=Get-ColdProcessReceipt $hostHandle $directory
$edge=$hostIdentity.ExecutablePath;$edgePin=(Get-FileHash -LiteralPath $edge).Hash.ToLowerInvariant()
$runtime=[pscustomobject]@{directory=$directory;java=$edge;javaSha256=$edgePin;core=$edge;coreSha256=$edgePin;
    classes=$directory;classpath='FIXTURE_ONLY_NOT_EXECUTABLE';sourcePins=@();classPins=@()}
$runtimeFile=Join-Path $directory 'runtime.json';Write-ColdJson $runtimeFile $runtime
$runtime | Add-Member receipt $runtimeFile
$runtime | Add-Member receiptSha256 ((Get-FileHash -LiteralPath $runtimeFile).Hash.ToLowerInvariant())
$script:events=[Collections.Generic.List[string]]::new();$script:checks=0;$script:mode='ok';$script:stopCalls=0
# Проверки явно имеют fixture-only scope, synthetic decisions никогда не native PASS.
function Assert-GoalIntegration([bool]$Condition,[string]$Label) {
    if (-not $Condition) {throw ('GOAL_INTEGRATION:'+ $Label)};$script:checks++
}
# Transport substitute; никакой проверки actual class pins не заявляется.
function Assert-NativeWebGoalRuntime($Runtime) {$script:events.Add('runtime-pin')}
# Start substitute сохраняет actual host handle/identity до ошибки; не запускает клиент.
function Start-NativeGoalPostTransactionClient($Probe,[int]$Timeout,[scriptblock]$Attach) {
    $script:events.Add('native-start')
    $Probe.native=[pscustomobject]@{process=$hostHandle;identity=$hostIdentity;ui=$null;uiProcess=$null}
    if ($script:mode -ceq 'start-fail') {throw 'INTEGRATION_START_ERROR'}
    if ($null -ne $Attach) {& $Attach $Probe $Probe.native | Out-Null}
    $script:events.Add('ui-ready')
}
# Callback substitute проверяет доставку независимо pinned runtime/Edge args.
function Start-NativeWebGoalRestorationBridge($Probe,$Native,$Runtime,[string]$Edge,[string]$EdgeSha256,[int]$Timeout) {
    $script:events.Add('bridge-attach')
    Assert-GoalIntegration ($Runtime.receiptSha256 -ceq $runtime.receiptSha256 -and $EdgeSha256 -ceq $edgePin -and $Timeout -eq 55) 'explicit callback pins'
    $Probe | Add-Member webBridge ([pscustomobject]@{worker=$Native;browserProcess=$hostHandle;browserIdentity=$hostIdentity}) -Force
    if ($script:mode -ceq 'attach-fail') {throw 'INTEGRATION_ATTACH_ERROR'}
}
# Wait substitute: утверждение chronology, не actual signal receipt.
function Wait-NativeCondition([scriptblock]$Condition,[int]$Seconds,[string]$Code) {
    $script:events.Add('shot-wait')
    if ($script:mode -cin @('shot-timeout','shot-timeout-cleanup-fail')) {throw $Code}
    if ($script:mode -ceq 'wait-identity-fail') {throw 'COLD_PROCESS_IDENTITY'}
}
# Sampler substitute проверяет тот же retained handle; не создаёт raw UI или model POST.
function Read-NativeWebGoalRestorationObservation($Probe) {
    $script:events.Add('observe')
    Assert-GoalIntegration ([object]::ReferenceEquals($Probe.native.process,$hostHandle)) 'retained handle observed'
    Assert-ColdProcessIdentity $Probe.native.identity (Get-ColdProcessReceipt $Probe.native.process $directory) $directory $edge
    if ($script:mode -cin @('observe-fail','observe-and-cleanup-fail')) {throw 'INTEGRATION_OBSERVE_ERROR'}
    if ($script:mode -ceq 'missing') {return New-RestoredWindowDecision 'PENDING' @('ACTUAL_DUMP_MISSING')}
    return New-RestoredWindowDecision 'WINDOW_CONTRACT_VALIDATED'
}
function Read-NativeGoalPostTransactionObservation($Probe) {$script:events.Add('desktop-observe');return New-RestoredWindowDecision 'PENDING' @('FIXTURE_NO_UI')}
# Cleanup substitutes не останавливают host; actual identity проверяется до возврата fixture receipt.
function Stop-NativeWebGoalRestorationBridge($State) {
    $script:events.Add('browser-cleanup')
    Assert-GoalIntegration ([object]::ReferenceEquals($State.worker.process,$hostHandle)) 'retained handle at cleanup'
    if ($script:mode -cin @('browser-cleanup-fail','observe-and-cleanup-fail')) {throw 'INTEGRATION_BROWSER_CLEANUP_ERROR'}
    return [pscustomobject]@{nativePass=$false;ordinaryExitProven=$false;fixtureOnly=$true}
}
function Complete-NativeGoalPostTransactionClient($Probe) {
    $script:events.Add('native-cleanup')
    if ($script:mode -cin @('native-cleanup-fail','shot-timeout-cleanup-fail')) {throw 'INTEGRATION_NATIVE_CLEANUP_ERROR'}
    return [pscustomobject]@{nativePass=$false;ordinaryExitProven=$false;fixtureOnly=$true}
}
function Complete-NativeRestoredWindowObserver($Observer) {
    $script:events.Add('window-receipt')
    if ($script:mode -ceq 'window-cleanup-fail') {throw 'INTEGRATION_WINDOW_CLEANUP_ERROR'}
    return [pscustomobject]@{fixtureOnly=$true;nativePass=$false}
}
function New-GoalIntegrationProbe([string]$Name,[string]$Client='web') {
    $cell=Join-Path $directory $Name;[void][IO.Directory]::CreateDirectory($cell)
    return [pscustomobject]@{context=[pscustomobject]@{directory=$cell;root=$directory;client=$Client};
        observer=[pscustomobject]@{output=(Join-Path $cell 'actual-output')};native=$null}
}
foreach ($mode in 'ok','start-fail','attach-fail','observe-fail','observe-and-cleanup-fail','browser-cleanup-fail','native-cleanup-fail','window-cleanup-fail','missing','shot-timeout','shot-timeout-cleanup-fail','wait-identity-fail') {
    $script:mode=$mode;$script:events.Clear();$probe=New-GoalIntegrationProbe $mode
    $result=Invoke-NativeGoalPostTransactionObserver $probe 60 $runtime $runtime.receiptSha256 $edge $edgePin
    $wanted=if ($mode -ceq 'ok') {'WINDOW_CONTRACT_VALIDATED'} elseif ($mode -cin @('missing','shot-timeout')) {'PENDING'} else {'FAIL'}
    Assert-GoalIntegration ($result.status -ceq $wanted -and -not $result.nativePass -and -not $result.fullCellProofComplete) ($mode+' decision not native PASS')
    Assert-GoalIntegration ($script:events.IndexOf('native-cleanup') -ge 0 -and $script:events.IndexOf('window-receipt') -gt $script:events.IndexOf('native-cleanup')) ($mode+' cleanup always attempted')
    if ($mode -cne 'start-fail') {
        Assert-GoalIntegration ($script:events.IndexOf('browser-cleanup') -lt $script:events.IndexOf('native-cleanup')) ($mode+' browser cleanup first')
    }
    if ($mode -ceq 'attach-fail') {Assert-GoalIntegration ($result.failure -ceq 'INTEGRATION_ATTACH_ERROR' -and $script:events -cnotcontains 'observe') 'attach failure retains primary error'}
    if ($mode -ceq 'observe-fail') {Assert-GoalIntegration ($result.failure -ceq 'INTEGRATION_OBSERVE_ERROR') 'observe primary preserved'}
    if ($mode -ceq 'observe-and-cleanup-fail') {
        Assert-GoalIntegration ($result.failure -ceq 'INTEGRATION_OBSERVE_ERROR' -and
            $result.cleanupErrors -ccontains 'INTEGRATION_BROWSER_CLEANUP_ERROR') 'cleanup error never masks primary error'
    }
    if ($mode -ceq 'ok') {
        Assert-GoalIntegration (($script:events.IndexOf('bridge-attach') -lt $script:events.IndexOf('ui-ready')) -and
            ($script:events.IndexOf('observe') -lt $script:events.IndexOf('browser-cleanup'))) 'attach-ready-observe-cleanup ordering'
    }
    if ($mode -cin @('shot-timeout','shot-timeout-cleanup-fail')) {
        $stored=ConvertFrom-ColdReceiptJson ([IO.File]::ReadAllText($result.Receipt))
        Assert-GoalIntegration ($script:events -cnotcontains 'observe' -and $null -eq $result.failure -and
            $stored.observationUnavailable -ceq 'GOAL_SHOT_TIMEOUT' -and $stored.decision.status -ceq 'PENDING') 'missing shot persisted without invented DOM'
        if ($mode -ceq 'shot-timeout-cleanup-fail') {
            Assert-GoalIntegration ($result.cleanupErrors -ccontains 'INTEGRATION_NATIVE_CLEANUP_ERROR') 'missing DOM never hides cleanup failure'
        }
    }
    if ($mode -ceq 'wait-identity-fail') {
        Assert-GoalIntegration ($result.failure -ceq 'COLD_PROCESS_IDENTITY' -and $null -eq $result.observationUnavailable) 'wait identity rejection remains FAIL'
    }
}
$probe=New-GoalIntegrationProbe 'desktop' 'fx';$script:mode='ok';$script:events.Clear()
$result=Invoke-NativeGoalPostTransactionObserver $probe
Assert-GoalIntegration ($result.status -ceq 'PENDING' -and $script:events -cnotcontains 'bridge-attach' -and $script:events -ccontains 'desktop-observe') 'desktop default unchanged'
$script:events.Clear();$probe=New-GoalIntegrationProbe 'bad-pin';$rejected=$false
try {Invoke-NativeGoalPostTransactionObserver $probe 60 $runtime ('0'*64) $edge $edgePin | Out-Null} catch {$rejected=$_.Exception.Message -ceq 'GOAL_WEB_EXPLICIT_RUNTIME_PIN'}
Assert-GoalIntegration ($rejected -and $script:events -cnotcontains 'native-start') 'bad runtime pin before launch'
$runtime.classpath='UNAPPROVED_CLASSPATH';$rejected=$false
try {Invoke-NativeGoalPostTransactionObserver $probe 60 $runtime $runtime.receiptSha256 $edge $edgePin | Out-Null} catch {$rejected=$_.Exception.Message -ceq 'GOAL_WEB_RUNTIME_RECEIPT_BINDING'}
Assert-GoalIntegration ($rejected -and $script:events -cnotcontains 'native-start') 'runtime object cannot escape pinned receipt'
$runtime.classpath='FIXTURE_ONLY_NOT_EXECUTABLE'
$script:events.Clear();$rejected=$false
try {Invoke-NativeGoalPostTransactionObserver $probe 60 $runtime $runtime.receiptSha256 $edge ('0'*64) | Out-Null} catch {$rejected=$_.Exception.Message -ceq 'GOAL_WEB_EXPLICIT_EDGE_PIN'}
Assert-GoalIntegration ($rejected -and $script:events -cnotcontains 'native-start') 'bad Edge pin before launch'
# Actual own bridge Read/Stop и actual native cleanup: stale birth реального host PID отклоняется ДО Kill/marker.
$wrong=ConvertFrom-ColdReceiptJson ($hostIdentity | ConvertTo-Json);$wrong.StartedAtTicks--
$state=[pscustomobject]@{worker=[pscustomobject]@{process=$hostHandle;identity=$wrong};workerDirectory=$directory;runtime=[pscustomobject]@{directory=$directory;java=$edge};
    browserProcess=$hostHandle;browserIdentity=$wrong;profile=$directory;edge=$edge;output=$directory}
foreach ($action in @({& $actualRead ([pscustomobject]@{webBridge=$state})},{& $actualStop $state})) {
    $rejected=$false;try {& $action | Out-Null} catch {$rejected=$_.Exception.Message -ceq 'COLD_PROCESS_IDENTITY'}
    Assert-GoalIntegration $rejected 'actual bridge rejects stale retained host birth'
}
function Stop-ColdRetainedProcess($Process,$Identity,[string]$Root) {$script:stopCalls++;throw 'MUST_NOT_STOP_HOST'}
function Get-CopyProcesses([string]$Root) {return @()}
function Save-NativeOutput($Native,[string]$Root,[string]$Label) {throw 'MUST_NOT_DRAIN_LIVE_PIPES'}
$probe=New-GoalIntegrationProbe 'native-stale';$probe | Add-Member registryNode ('ru/cashprediction/selftest/'+[guid]::NewGuid())
$probe.native=[pscustomobject]@{process=$hostHandle;identity=$wrong;ui=$null;uiProcess=$null}
$rejected=$false;try {& $actualNativeCleanup $probe | Out-Null} catch {$rejected=$_.Exception.Message -cmatch '^GOAL_NATIVE_CLEANUP:COLD_PROCESS_IDENTITY'}
Assert-GoalIntegration ($rejected -and $script:stopCalls -eq 0 -and -not (Test-Path -LiteralPath (Join-Path $directory 'web-bridge.stop'))) 'actual cleanup never acts on mismatched identity'
Assert-GoalIntegration (-not $hostHandle.HasExited) 'host remains alive, no process stopped'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'NativeUpdateWebGoalRestorationBridge.ps1'),[ref]$tokens,[ref]$errors)
$start=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Start-NativeWebGoalRestorationBridge'},$true))[0].Extent.Text
Assert-GoalIntegration ($errors.Count -eq 0 -and $start.Contains('Start-NativeOwned $Runtime.java $arguments $workerDirectory') -and
    $start.Contains('Join-Path $workerDirectory ''bridge-launch.json''')) 'shared runtime never overwrites per-cell worker evidence'
$output=Join-Path $directory 'fixture-results.json'
Write-ColdJson $output ([ordered]@{checks=$script:checks;scope='PS_ORCHESTRATION_AND_RETAINED_HOST_GUARDS_ONLY';
    nativeExecuted=$false;actualDom='PENDING';modelPost=$false;hostIdentity=$hostIdentity;diagnostics=$directory})
[pscustomobject]@{checks=$script:checks;nativeExecuted=$false;diagnostics=$directory} | ConvertTo-Json -Compress
$hostHandle.Dispose()
