<#
.SYNOPSIS
Ограниченные fixtures реального Ready collector: AST/import closure, mocks, никаких native запусков.
.DESCRIPTION
PASS означает только checks этого файла. Evidence не пишется; процессы/сеть/реестр не читаются.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$script:checks=0;$pins=@{};$asts=@{}
foreach ($file in 'NativeUpdateReadyAcceptanceCollector.ps1','NativeUpdateReadyScenarios.ps1','NativeUpdateReadyAcceptance.ps1',
    'Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1','Test-NativeUpdateReadyAcceptanceFixtures.ps1') {
    $path=Join-Path $PSScriptRoot $file;$pins[$file]=(Get-FileHash -LiteralPath $path).Hash
    $tokens=$null;$errors=$null;$asts[$file]=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw ('COLLECTOR_FIXTURE_PARSE '+$file)}
}
# Считает только bounded fixture checks, никогда native cells.
function Assert-CollectorFixture([bool]$Condition,[string]$Code) {
    if (-not $Condition) {throw ('COLLECTOR_FIXTURE_ASSERT '+$Code)};$script:checks++
}
# Проверяет точный отказ, а не произвольную ошибку mock.
function Assert-CollectorRejected([scriptblock]$Action,[string]$Code) {
    $message=$null;try {& $Action | Out-Null} catch {$message=$_.Exception.Message}
    Assert-CollectorFixture ($message -ceq $Code) ('expected='+$Code+' actual='+$message)
}
foreach ($def in $asts['NativeUpdateReadyAcceptanceCollector.ps1'].FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)) {
    . ([scriptblock]::Create($def.Extent.Text))
}
Import-NativeReadyCollectorDependencies $PSScriptRoot

# До mocks проверяется реальное рекурсивное замыкание импортов, включая точный исходный AST.
$queue=[Collections.Generic.Queue[string]]::new();$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($name in 'Invoke-NativeReadyAcceptedCell','Invoke-NativeReadyScenario','Add-NativeReadyCollectedObservation','Complete-NativeReadyCollectedObservation') {$queue.Enqueue($name)}
while ($queue.Count) {
    $name=$queue.Dequeue();if (-not $seen.Add($name)) {continue}
    $command=Get-Command $name -ErrorAction Stop
    if ($command.CommandType -ne 'Function' -or $command.ModuleName) {continue}
    $matches=@(foreach ($file in $asts.Keys | Where-Object {$_ -cne 'Test-NativeUpdateReadyAcceptanceFixtures.ps1'}) {
        $asts[$file].FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true)
    })
    Assert-CollectorFixture ($matches.Count -eq 1) ('ONE_ACTUAL_SOURCE_'+$name)
    $actual=$command.Definition.Trim()
    $original=$matches[0].Extent.Text
    $bound=$original.Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'"))
    $expected=& {param($text,$functionName) . ([scriptblock]::Create($text));(Get-Command $functionName).Definition.Trim()} $bound $name
    $unbound=& {param($text,$functionName) . ([scriptblock]::Create($text));(Get-Command $functionName).Definition.Trim()} $original $name
    Assert-CollectorFixture ($actual -ceq $expected -or $actual -ceq $unbound) ('EXACT_SOURCE_'+$name)
    foreach ($call in $matches[0].FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true)) {
        $callee=$call.GetCommandName();if ($callee) {$queue.Enqueue($callee)}
    }
}
Assert-CollectorFixture ($seen.Contains('Get-ColdCurrentProcess') -and $seen.Contains('Get-PortableRealRegistrySnapshot') -and $seen.Contains('Get-ColdUiReceipt')) 'REAL_IMPORT_CLOSURE'

# Hooks находятся в настоящем helper до утраты живых identity, не только в mock callback.
$contextStep=@($asts['NativeUpdateReadyScenarios.ps1'].FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativeReadyContextScenario'},$true))[0]
$calls=@($contextStep.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true))
$hooks=@($calls | Where-Object {$_.GetCommandName() -ceq 'Invoke-NativeReadyObservation'})
foreach ($event in 'READY','PRESTART','LAUNCHER','INSTALLER','SAMPLE','RESTARTED') {
    Assert-CollectorFixture (@($hooks | Where-Object {$_.CommandElements[1].Extent.Text -ceq ("'"+$event+"'")}).Count -ge 1) ('ACTUAL_HOOK_'+$event)
}
$ready=@($hooks | Where-Object {$_.CommandElements[1].Extent.Text -ceq "'READY'"})[0]
$crash=@($calls | Where-Object {$_.GetCommandName() -ceq 'Stop-NativeReadyClient'})[0]
$prestart=@($hooks | Where-Object {$_.CommandElements[1].Extent.Text -ceq "'PRESTART'"})[0]
$launch=@($calls | Where-Object {$_.GetCommandName() -ceq 'Start-ColdProcess'})[0]
Assert-CollectorFixture ($ready.Extent.StartOffset -lt $crash.Extent.StartOffset -and $prestart.Extent.StartOffset -lt $launch.Extent.StartOffset) 'ACTUAL_PRE_CRASH_PRE_LAUNCH_HOOKS'
$adapter=@($asts['NativeUpdateReadyScenarios.ps1'].FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativeReadyScenario'},$true))[0]
foreach ($event in 'BASELINES','CELL_OBSERVED') {
    Assert-CollectorFixture (@($adapter.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Invoke-NativeReadyObservation' -and $n.CommandElements[1].Extent.Text -ceq ("'"+$event+"'")},$true)).Count -eq 1) ('ACTUAL_ADAPTER_HOOK_'+$event)
}
$signature=@($asts['NativeUpdateReadyAcceptanceCollector.ps1'].FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativeReadyAcceptedCell'},$true))[0]
Assert-CollectorFixture (($signature.Parameters.Name.VariablePath.UserPath -join ',') -ceq 'Row,Source,Base,Target,Life,Cold,Java,Evidence,Timeout') 'EXACT_NINE_PARAMETER_ADAPTER'

# Только генераторы данных, не тело/прошлая suite. Эти данные явно synthetic.
foreach ($name in 'Copy-AcceptanceFixture','New-AcceptanceMockUi','New-AcceptanceMockSession','New-AcceptanceFixture') {
    $def=@($asts['Test-NativeUpdateReadyAcceptanceFixtures.ps1'].FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))[0]
    . ([scriptblock]::Create($def.Extent.Text))
}
function Start-Process {throw 'COLLECTOR_FIXTURE_FORBIDDEN_PROCESS'}
function Get-NetTCPConnection {throw 'COLLECTOR_FIXTURE_FORBIDDEN_TCP'}
function Invoke-WebRequest {throw 'COLLECTOR_FIXTURE_FORBIDDEN_NETWORK'}

# Отдельная память fake leaf readers; production event handler и hook выполняются реально.
& {
    $f=New-AcceptanceFixture;$script:mock=$f;$script:readerCalls=[Collections.Generic.List[string]]::new()
    $state=[pscustomobject]@{proof=[ordered]@{schemaVersion=1;recovery=[ordered]@{schemaVersion=1;pollingLimitMillis=1000}};
        events=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal);samples=[Collections.Generic.List[object]]::new();
        target=$f.Target;targetRoot=$null;registryBefore='unchanged'}
    function Get-PortableRealRegistrySnapshot {return 'unchanged'}
    function Get-ColdManagedInventory($Root) {$script:readerCalls.Add('inventory');return $script:mock.Target.files}
    function Get-NativeUserObject($Root) {$script:readerCalls.Add('user');return $script:mock.Independent.userBefore}
    function Get-ColdControlledInventory($Root) {$script:readerCalls.Add('controlled');return @()}
    function Get-CopyProcesses($Root) {return @()}
    function Get-CimInstance {return @()}
    function Get-Content {return @('actual-log PHASE_COMMITTED')}
    function Test-Path {return $false}
    function Assert-ColdTree {$script:readerCalls.Add('ready-tree');return $script:mock.Target.files}
    function Get-ColdUiReceipt {$script:readerCalls.Add('fresh-ui');return $script:mock.Independent.initialClient}
    function Get-ColdProcessReceipt {$script:readerCalls.Add('retained');return (Get-NativeReadyAcceptanceUiIdentity $script:mock.Independent.initialClient $script:mock.Row.workRoot)}
    function Open-PortableProcess {return [IO.MemoryStream]::new()}
    $data=[pscustomobject]@{root=$f.Row.workRoot;targetRoot='C:\mock-target';client='fx';scenario=$f.Row.scenario}
    Assert-CollectorRejected {Add-NativeReadyCollectedObservation $state 'READY' $data} 'READY_COLLECTOR_ORDER'
    Assert-CollectorRejected {Add-NativeReadyCollectedObservation $state 'UNKNOWN' $data} 'READY_COLLECTOR_EVENT'
    Add-NativeReadyCollectedObservation $state 'BASELINES' $data
    Assert-CollectorFixture ($script:readerCalls.Count -eq 4) 'BASELINES_ACTUAL_READERS'
    Assert-CollectorRejected {Add-NativeReadyCollectedObservation $state 'BASELINES' $data} 'READY_COLLECTOR_DUPLICATE'
    Assert-CollectorRejected {Complete-NativeReadyCollectedObservation $state $f.Row} 'READY_COLLECTOR_MISSING_READY'
    $context=[pscustomobject]@{Native=[pscustomobject]@{uiProcess=[pscustomobject]@{StartTime=[datetime]'2026-10-04T12:00:01Z'}}}
    Add-NativeReadyCollectedObservation $state 'READY' ([pscustomobject]@{context=$context})
    Assert-CollectorFixture ($script:readerCalls.Contains('fresh-ui') -and $script:readerCalls.Contains('retained') -and $script:readerCalls.Contains('ready-tree')) 'READY_INDEPENDENT_READERS'
    $sample=Copy-AcceptanceFixture $f.Independent.recovery.samples[0];$sample.controlledSha256=Get-ColdObjectHash @()
    Add-NativeReadyCollectedObservation $state 'PRESTART' ([pscustomobject]@{sample=$sample})
    $sample.controlledSha256='d'*64
    Assert-CollectorFixture ($state.samples[0].controlledSha256 -cne $sample.controlledSha256) 'IMMUTABLE_SAMPLE'
    Add-NativeReadyCollectedObservation $state 'LAUNCHER' ([pscustomobject]@{process=[pscustomobject]@{StartInfo=[pscustomobject]@{ArgumentList=@('--home',$f.Row.workRoot)}}})
    Assert-CollectorFixture ($state.proof.launcherArguments[0] -ceq '--home') 'RETAINED_ARGUMENTS_NOT_RECEIPT'
    Assert-CollectorRejected {Add-NativeReadyCollectedObservation $state 'INSTALLER' ([pscustomobject]@{installer=[pscustomobject]@{process=[pscustomobject]@{HasExited=$false}}})} 'READY_COLLECTOR_INSTALLER_NOT_LIVE'
    $script:installerMode=$true;$script:badJournal=$true
    function Get-ColdProcessReceipt {
        $script:readerCalls.Add('retained')
        if ($script:installerMode) {return $script:mock.Independent.installerIdentity}
        return (Get-NativeReadyAcceptanceUiIdentity $script:mock.Independent.initialClient $script:mock.Row.workRoot)
    }
    function Get-CimInstance {
        if (-not $script:installerMode) {return @()}
        return [pscustomobject]@{ProcessId=$script:mock.Independent.installerIdentity.ProcessId;
            ExecutablePath=$script:mock.Independent.installerIdentity.ExecutablePath;
            CreationDate=[datetime]::new($script:mock.Independent.installerIdentity.StartedAtTicks,[DateTimeKind]::Utc);
            CommandLine=$script:mock.Independent.installerCommandLine}
    }
    function Get-Content($LiteralPath,[switch]$Raw,$Encoding) {
        if (-not $Raw) {return @('actual-log PHASE_COMMITTED')}
        return (ConvertTo-Json -Depth 64 -InputObject ([pscustomobject]@{installationRoot=$(if ($script:badJournal) {'wrong'} else {$script:mock.Row.workRoot});
            target=$script:mock.Target;transactionId=$script:mock.Independent.recovery.transactionId}))
    }
    $identity=$f.Independent.installerIdentity
    $process=[pscustomobject]@{HasExited=$false;Id=$identity.ProcessId;MainModule=[pscustomobject]@{FileName=$identity.ExecutablePath};
        StartTime=[datetime]::new($identity.StartedAtTicks,[DateTimeKind]::Utc)}
    $installerData=[pscustomobject]@{installer=[pscustomobject]@{process=$process;journal=[pscustomobject]@{transactionId=$f.Independent.recovery.transactionId}}}
    Assert-CollectorRejected {Add-NativeReadyCollectedObservation $state 'INSTALLER' $installerData} 'READY_COLLECTOR_JOURNAL'
    $script:badJournal=$false
    Add-NativeReadyCollectedObservation $state 'INSTALLER' $installerData
    Assert-CollectorFixture ($state.proof.installerCommandLine -ceq $f.Independent.installerCommandLine -and $state.proof.recovery.transactionId -ceq $f.Independent.recovery.transactionId) 'ACTUAL_CIM_AND_JOURNAL_READERS'
    $script:installerMode=$false
    Add-NativeReadyCollectedObservation $state 'SAMPLE' $sample
    Add-NativeReadyCollectedObservation $state 'RESTARTED' ([pscustomobject]@{launchAt=[datetime]'2026-10-04T12:00:04Z'})
    Add-NativeReadyCollectedObservation $state 'CELL_OBSERVED' $data
    $f.Row | Add-Member reason 'NATIVE_READY_RECEIPTS_OBSERVED_ACCEPTANCE_PENDING' -Force
    $proof=Complete-NativeReadyCollectedObservation $state $f.Row
    Assert-CollectorFixture ($proof.phaseLog -ccontains 'COMMITTED' -and $proof.cleanup.remainingClients -eq 0 -and $proof.cleanup.registryUnchanged -and $f.Row.status -ceq 'PENDING') 'MOCK_COLLECTION_NEVER_NATIVE_PASS'
    $f.Row.status='FAIL'
    Assert-CollectorRejected {Complete-NativeReadyCollectedObservation $state $f.Row} 'READY_COLLECTOR_HELPER_FAILED'
}

# Wrapper действительно передаёт hook в helper; после exception восстанавливает script scope.
& {
    function Get-PortableRealRegistrySnapshot {return 'mock'}
    function Invoke-NativeReadyScenario {
        Invoke-NativeReadyObservation 'UNKNOWN' $null
        throw 'FIXTURE_HOOK_NOT_CALLED'
    }
    $row=[pscustomobject]@{status='PENDING';reason=''}
    Remove-Variable nativeReadyObservationHook -Scope Script -ErrorAction SilentlyContinue
    Assert-CollectorRejected {Invoke-NativeReadyAcceptedCell $row 'source' $null $null $null $null 'java' 'evidence' 30} 'READY_COLLECTOR_EVENT'
    Assert-CollectorFixture ($null -eq (Get-Variable nativeReadyObservationHook -Scope Script -ErrorAction SilentlyContinue) -and $row.status -ceq 'FAIL') 'HOOK_RESTORED_ON_FAILURE'
    $script:nativeReadyObservationHook='invalid'
    Assert-CollectorRejected {Invoke-NativeReadyObservation 'READY' $null} 'READY_OBSERVER_TYPE'
    Assert-CollectorRejected {Invoke-NativeReadyAcceptedCell $row 'source' $null $null $null $null 'java' 'evidence' 30} 'READY_COLLECTOR_HOOK_BUSY'
    Remove-Variable nativeReadyObservationHook -Scope Script
    Invoke-NativeReadyObservation 'READY' $null
    Assert-CollectorFixture $true 'ABSENT_HOOK_NOOP'
}

# Только wrapper plumbing: сохранение return, pins и PENDING decision, без fake native signoff.
& {
    $script:wrappedReceipt=[pscustomobject]@{synthetic=$true}
    $script:writtenProof=$null
    function Get-PortableRealRegistrySnapshot {return 'mock'}
    function Invoke-NativeReadyScenario {return $script:wrappedReceipt}
    function Complete-NativeReadyCollectedObservation {return [pscustomobject]@{synthetic=$true}}
    function Write-ColdJson($Path,$Value) {$script:writtenProof=$Value}
    function Get-FileHash {return [pscustomobject]@{Hash=('a'*64)}}
    function Test-NativeReadyAcceptance($Row,$Receipt,$Base,$Target,$ReceiptSha256,$IndependentFile,$IndependentSha256) {
        if (-not [object]::ReferenceEquals($Receipt,$script:wrappedReceipt) -or $ReceiptSha256 -cne ('a'*64) -or $IndependentSha256 -cne ('a'*64)) {throw 'COLLECTOR_FIXTURE_FORWARDING'}
        return [pscustomobject]@{status='PENDING';cellEvidenceValidated=$true;scope='READY_CELL_ONLY'}
    }
    $row=[pscustomobject]@{status='PENDING';reason='';evidenceDirectory='C:\mock-evidence';command='C:\mock-evidence\receipt.json'}
    $result=Invoke-NativeReadyAcceptedCell $row 'source' $null $null $null $null 'java' 'evidence' 30
    Assert-CollectorFixture ([object]::ReferenceEquals($result.receipt,$script:wrappedReceipt) -and $result.decision.status -ceq 'PENDING' -and $row.status -ceq 'PENDING' -and $script:writtenProof.synthetic) 'WRAPPER_PRESERVES_RETURN_PENDING'
    Assert-CollectorFixture ($null -eq (Get-Variable nativeReadyObservationHook -Scope Script -ErrorAction SilentlyContinue)) 'HOOK_RESTORED_ON_SUCCESS'
}
foreach ($file in 'NativeUpdateReadyAcceptanceCollector.ps1','NativeUpdateReadyScenarios.ps1') {
    Assert-CollectorFixture (@($asts[$file].EndBlock.Statements | Where-Object {$_ -isnot [Management.Automation.Language.FunctionDefinitionAst]}).Count -eq 0) ('IMPORT_ONLY_'+$file)
    Assert-CollectorFixture ($asts[$file].Extent.Text -cnotmatch "\.status\s*=\s*'PASS'") ('NO_NATIVE_PASS_'+$file)
}
foreach ($file in $pins.Keys) {if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash -cne $pins[$file]) {throw 'COLLECTOR_FIXTURE_SOURCE_CHANGED'}}
Write-Host ('Ready collector fixtures PASS: '+$script:checks+' checks; mocks/AST only; nativeExecuted=false; no native PASS')
foreach ($file in 'NativeUpdateReadyAcceptanceCollector.ps1','NativeUpdateReadyScenarios.ps1') {Write-Host ($file+' SHA256: '+$pins[$file].ToLowerInvariant())}
