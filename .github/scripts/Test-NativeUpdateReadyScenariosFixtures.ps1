<#
.SYNOPSIS
Ограниченные AST/mocks-only guards нового ready/recovery helper.
.DESCRIPTION
Не запускает runner, Java, GUI, helper, сеть или реестр, не пишет native evidence.
Mock receipts проверяют порядок и отказ, но никогда не означают native PASS.
#>
[CmdletBinding()]
param([switch]$AdapterAuditOnly)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'READY_FIXTURE_POWERSHELL7'}
$source=Join-Path $PSScriptRoot 'NativeUpdateReadyScenarios.ps1'
$sourceHash=(Get-FileHash -LiteralPath $source).Hash
$dependencyHashes=@{}
foreach ($file in 'Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1') {
    $dependencyHashes[$file]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash
}
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw ('READY_FIXTURE_PARSE '+($errors.Message -join '; '))}
foreach ($definition in @($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
Import-NativeReadyDependencies $PSScriptRoot
$script:checks=0

# Положительный control исключает случайный отказ раньше нужной проверки.
function Assert-ReadyFixture([bool]$Value,[string]$Code) {
    if (-not $Value) {throw ('READY_FIXTURE_ASSERT '+$Code)};$script:checks++
}

# Сравнивает точную причину отказа вместо любого exception.
function Assert-ReadyRejected([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    Assert-ReadyFixture ($caught -ceq $Code) ('expected='+$Code+' actual='+$caught)
}

# Новый audit отдельно от retained 164 checks: реальная import closure и точные source definitions.
function Invoke-ReadyAdapterAuditFixtures {
    $sourceMap=@{}
    foreach ($file in 'NativeUpdateReadyScenarios.ps1','Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1') {
        $parseTokens=$null;$parseErrors=$null
        $parsed=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$parseTokens,[ref]$parseErrors)
        Assert-ReadyFixture ($parseErrors.Count -eq 0) ('AUDIT_PARSE_'+$file)
        foreach ($definition in $parsed.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)) {
            $sourceMap[$definition.Name]=$definition
        }
    }
    # Import-NativeReadyDependencies уже вызван настоящим определением выше, без исполнения runner.
    $queue=[Collections.Generic.Queue[string]]::new();$queue.Enqueue('Invoke-NativeReadyScenario')
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $commandNames=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    while ($queue.Count) {
        $name=$queue.Dequeue();if (-not $seen.Add($name)) {continue}
        $expected=$sourceMap[$name]
        $resolved=Get-Command -Name $name -CommandType Function -ErrorAction Stop
        $resolvedAst=$resolved.ScriptBlock.Ast
        Assert-ReadyFixture ($resolvedAst -is [Management.Automation.Language.FunctionDefinitionAst]) ('SOURCE_FUNCTION_AST_'+$name)
        $expectedBody=$expected.Body.Extent.Text.Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'"))
        Assert-ReadyFixture ($resolvedAst.Body.Extent.Text -ceq $expectedBody -or
            $resolvedAst.Body.Extent.Text -ceq $expected.Body.Extent.Text) ('EXACT_SOURCE_BODY_'+$name)
        foreach ($call in $expected.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true)) {
            $callee=[string]$call.GetCommandName()
            if ($sourceMap.ContainsKey($callee)) {$queue.Enqueue($callee)}
            elseif ($callee -and $commandNames.Add($callee)) {
                Assert-ReadyFixture ($null -ne (Get-Command -Name $callee -ErrorAction SilentlyContinue)) ('NON_SOURCE_COMMAND_RESOLVES_'+$callee)
            }
        }
    }
    Assert-ReadyFixture ($seen.Contains('New-ColdHelperCapture') -and $seen.Contains('Get-NativeDomainSessionSource') -and
        $seen.Contains('Get-NativeDomainBridgeArguments') -and $seen.Contains('Copy-NativeDomainCore')) 'DOMAIN_AND_DIAGNOSTICS_TRANSITIVE_CLOSURE'
    $capture=Get-Command New-ColdHelperCapture -CommandType Function
    Assert-ReadyFixture ($capture.ScriptBlock.Ast.Name -ceq 'global:New-ColdHelperCapture') 'MISSING_CAPTURE_DEPENDENCY_NOW_IMPORTED'
    $domainSource=Get-NativeDomainSessionSource
    Assert-ReadyFixture ($domainSource.Contains('final class NativeDomainSession') -and
        $domainSource.Contains('SettingsMarkdown.save') -and $domainSource.Contains('repository.save')) 'ACTUAL_JDK_SOURCE_FUNCTION_RESOLVED'
    $bridge=@(Get-NativeDomainBridgeArguments $PSScriptRoot $source $source)
    Assert-ReadyFixture ($bridge[0] -ceq '-XX:-UsePerfData' -and
        [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($bridge[-1])) -ceq $PSScriptRoot) 'ACTUAL_BRIDGE_ARGUMENTS_FUNCTION_RESOLVED'

    # Strict scope: явный guard вместо variable-not-set; valid scope читается без изменения caller.
    $saved=@{}
    foreach ($name in 'nativeTarget','nativeProject','nativeProfile','nativeNode','nativeUi','WorkDir') {
        $variable=Get-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue
        $saved[$name]=[pscustomobject]@{exists=($null -ne $variable);value=$(if ($null -ne $variable) {$variable.Value} else {$null})}
    }
    try {
        foreach ($name in 'nativeTarget','nativeProject','nativeProfile') {Set-Variable -Name $name -Scope Script -Value $PSScriptRoot}
        $scope=Get-NativeReadyMainScope
        Assert-ReadyFixture ($scope.TargetRoot -ceq $PSScriptRoot -and $scope.ProjectRoot -ceq $PSScriptRoot -and $scope.ProfileRoot -ceq $PSScriptRoot) 'ACTUAL_STRICT_SCOPE_POSITIVE'
        foreach ($name in 'nativeTarget','nativeProject','nativeProfile') {
            Set-Variable -Name $name -Scope Script -Value $null
            Assert-ReadyRejected {Get-NativeReadyMainScope} 'READY_MAIN_SCOPE'
            Set-Variable -Name $name -Scope Script -Value $PSScriptRoot
            Remove-Variable -Name $name -Scope Script
            Assert-ReadyRejected {Get-NativeReadyMainScope} 'READY_MAIN_SCOPE'
            Set-Variable -Name $name -Scope Script -Value $PSScriptRoot
        }
        # Исполняется настоящий adapter до первого filesystem leaf refusal; native callbacks не достигаются.
        & {
            function Assert-ColdCommand { }
            function Assert-NativeLifecycleConfig { }
            function Assert-ColdTree {return @()}
            function Assert-ColdNativeImage { }
            function Assert-NativeSeam { }
            function Read-ColdPinnedJson($Path,$Hash) {return [pscustomobject]@{commitSha='mock-pin'}}
            function Get-ValidatedPortablePaths {return @()}
            function New-Item {throw 'AUDIT_FIRST_WRITE_DENIED'}
            function Get-CopyProcesses {return @()}
            function Test-Path {return $false}
            function Stop-ColdRecoveryHelpers { }
            function Write-ColdJson {throw 'AUDIT_UNEXPECTED_RECEIPT_WRITE'}
            $script:nativeNode='caller-node';$script:nativeUi='caller-ui';$script:WorkDir='caller-work'
            $row=[pscustomobject]@{scenario='abrupt-ready-restart';base='B1';client='web';path='unicode';phase='SESSION';status='PENDING';reason='pending'}
            $manifest=[pscustomobject]@{commitSha='mock-pin'}
            $cold=[pscustomobject]@{targetManifestSha256=('a'*64);targetManifest='target-pin';baseManifests=@(
                [pscustomobject]@{portableDir=$PSScriptRoot;manifest='base-pin';sha256=('b'*64)},
                [pscustomobject]@{portableDir=$source;manifest='base2-pin';sha256=('c'*64)})}
            $life=[pscustomobject]@{manifestSha256=('a'*64);artifactDir=$PSScriptRoot}
            Assert-ReadyRejected {Invoke-NativeReadyScenario $row $PSScriptRoot $manifest $manifest $life $cold $source $PSScriptRoot 30} 'AUDIT_FIRST_WRITE_DENIED'
            Assert-ReadyFixture ($script:nativeNode -ceq 'caller-node' -and $script:nativeUi -ceq 'caller-ui' -and $script:WorkDir -ceq 'caller-work') 'ADAPTER_RESTORES_CALLER_SCOPE_ON_FAILURE'
            Assert-ReadyFixture ($row.status -ceq 'FAIL' -and $row.evidenceDirectory -ceq (Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($row.workRoot))) 'evidence')) 'FAILED_ROW_POINTS_TO_OWN_RUN_EVIDENCE'
        }
    } finally {
        foreach ($name in $saved.Keys) {
            if ($saved[$name].exists) {Set-Variable -Name $name -Scope Script -Value $saved[$name].value}
            else {Remove-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue}
        }
    }
    $step=$sourceMap['Invoke-NativeReadyContextScenario']
    $warm=@($step.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Get-NetTCPConnection'},$true))
    Assert-ReadyFixture ($warm.Count -eq 1 -and $warm[0].Extent.Text -ceq 'Get-NetTCPConnection -State Listen -ErrorAction Stop') 'EXACT_REAL_TCP_WARMUP_COMMAND'
    Assert-ReadyFixture ($warm[0].Extent.StartOffset -gt $step.Extent.Text.IndexOf('Wait-NativeTargetReady')+$step.Extent.StartOffset -and
        $warm[0].Extent.StartOffset -lt $step.Extent.Text.IndexOf('Close-NativeNormally')+$step.Extent.StartOffset -and
        $warm[0].Extent.StartOffset -lt $step.Extent.Text.IndexOf('Stop-NativeReadyClient')+$step.Extent.StartOffset) 'WARM_BEFORE_CRASH_AND_APPLYING_WINDOW'
    Assert-ReadyFixture ($step.Extent.Text.Contains('pollingLimitMillis=1000') -and
        $sourceMap['Get-ColdRecoveryObservation'].Extent.Text.Contains('Get-NetTCPConnection -State Listen -ErrorAction Stop')) 'FRESH_OBSERVER_AND_1000MS_GATE_RETAINED'
    Assert-ReadyFixture ($step.Extent.Text.Contains('ready-launch-unvalidated.json') -and
        $step.Extent.Text.IndexOf('ready-launch-unvalidated.json') -lt $step.Extent.Text.IndexOf('Assert-ColdSafeArgs')) 'ACTUAL_RECEIPT_PERSISTED_BEFORE_LATER_REJECTION'
    Assert-ReadyFixture ($step.Extent.Text.Contains('ready-recovery-rejected.json') -and
        $step.Extent.Text.Contains('samples=@($samples.ToArray())') -and $step.Extent.Text.Contains('failure=$rejection')) 'OBSERVER_REJECTION_RETAINS_ACTUAL_SAMPLES'
    $adapter=$sourceMap['Invoke-NativeReadyScenario']
    Assert-ReadyFixture (($adapter.Parameters.Name.VariablePath.UserPath -join ',') -ceq 'Row,Source,Base,Target,Life,Cold,Java,Evidence,Timeout') 'NINE_PARAM_SIGNATURE_RETAINED'
    Assert-ReadyFixture ($adapter.Extent.Text.Contains('Stop-ColdRetainedProcess $launcher.process $launcher.identity $root')) 'RETAINED_LAUNCHER_CLEANUP_FALLBACK'
    Assert-ReadyFixture (-not $adapter.Body.Extent.Text.Contains("'PASS'")) 'ADAPTER_NO_ACCEPTANCE_WIDENING'
    Write-Host ('Audit source closure: '+$seen.Count+' exact reachable function definitions')
}

if ($AdapterAuditOnly) {
    Invoke-ReadyAdapterAuditFixtures
    if ((Get-FileHash -LiteralPath $source).Hash -cne $sourceHash) {throw 'READY_FIXTURE_SOURCE_CHANGED'}
    foreach ($file in $dependencyHashes.Keys) {
        if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash -cne $dependencyHashes[$file]) {throw 'READY_FIXTURE_DEPENDENCY_CHANGED'}
    }
    Write-Host ('Ready adapter audit fixtures PASS: '+$script:checks+' checks; AST/import/mocks only; nativeExecuted=false; retained164 not run')
    Write-Host ('Helper SHA256: '+$sourceHash.ToLowerInvariant())
    Write-Host ('Fixture SHA256: '+(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant())
    return
}

# Любой неожиданный выход к ОС немедленно останавливает fixtures.
function Start-Process {throw 'READY_FIXTURE_FORBIDDEN_PROCESS'}
function Stop-Process {throw 'READY_FIXTURE_FORBIDDEN_KILL'}
function Start-NativeOwned {throw 'READY_FIXTURE_FORBIDDEN_NATIVE'}
function Start-NativeFixture {throw 'READY_FIXTURE_FORBIDDEN_SERVER'}
function New-Item {throw 'READY_FIXTURE_FORBIDDEN_WRITE'}
function Copy-Item {throw 'READY_FIXTURE_FORBIDDEN_COPY'}
function Remove-Item {throw 'READY_FIXTURE_FORBIDDEN_DELETE'}
function Invoke-RestMethod {throw 'READY_FIXTURE_FORBIDDEN_NETWORK'}
function Get-CimInstance {throw 'READY_FIXTURE_FORBIDDEN_CIM'}
function Invoke-ColdTool {throw 'READY_FIXTURE_FORBIDDEN_JAVA'}
function Stop-ColdCopyProcesses {throw 'READY_FIXTURE_FORBIDDEN_CLEANUP'}
function Stop-ColdRecoveryHelpers {throw 'READY_FIXTURE_FORBIDDEN_CLEANUP'}

# Статический контракт не исполняет adapter/native операции и не сужает полный план MAIN.
$functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))
$adapter=@($functions | Where-Object {$_.Name -ceq 'Invoke-NativeReadyScenario'})[0]
Assert-ReadyFixture (($adapter.Parameters.Name.VariablePath.UserPath -join ',') -ceq 'Row,Source,Base,Target,Life,Cold,Java,Evidence,Timeout') 'ADAPTER_SIGNATURE'
$contextStep=@($functions | Where-Object {$_.Name -ceq 'Invoke-NativeReadyContextScenario'})[0]
foreach ($text in @('Assert-NativeReadyContext','Wait-NativeTargetReady','Stop-NativeReadyClient','Close-NativeNormally',
    'Get-NativeReadyInstaller','Get-ColdRecoveryObservation','Start-ColdProcess','Get-ColdUiReceipt','Assert-ColdSafeArgs',
    'Assert-ColdRecoveryEvidence','Assert-ColdTree','Invoke-ColdTool')) {
    Assert-ReadyFixture ($contextStep.Extent.Text.Contains($text)) ('ACTUAL_GUARD_'+$text)
}
foreach ($forbidden in @('New-ColdJournal','New-ColdInstrumentedHelper','PASS','Start-Process','Stop-Process')) {
    Assert-ReadyFixture (-not $adapter.Body.Extent.Text.Contains($forbidden) -and -not $contextStep.Body.Extent.Text.Contains($forbidden)) ('NO_TEMPLATE_OR_PASS_'+$forbidden)
}
Assert-ReadyFixture ($adapter.Extent.Text.Contains("'PENDING'") -and $adapter.Extent.Text.Contains("'FAIL'")) 'NO_AUTOMATIC_ACCEPTANCE'
Assert-ReadyFixture ($adapter.Extent.Text.Contains('Assert-ColdControlledChanges') -and $adapter.Extent.Text.Contains('READY_USER_CHANGED')) 'USER_CONTROLLED_GATE'
Assert-ReadyFixture (@($ast.EndBlock.Statements | Where-Object {$_ -isnot [Management.Automation.Language.FunctionDefinitionAst]}).Count -eq 0) 'DECLARATIONS_ONLY_NO_AUTO_NATIVE'
foreach ($field in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog','command') {
    Assert-ReadyFixture ($adapter.Extent.Text.Contains($field)) ('ROW_ARTIFACT_'+$field)
}

# Сам Ready guard исполняется: отсутствие publication, чужой target, ранний exit и настоящие tree calls.
& {
    $script:published=$true;$script:readyWrong=$false;$script:readyTreeCalls=0
    $target=[pscustomobject]@{commitSha=('3'*40);treeSha256=('a'*64)}
    $context=[pscustomobject]@{Root='C:\mock-root';Timeout=30;Native=[pscustomobject]@{uiProcess=[pscustomobject]@{HasExited=$false};ui=@{}};
        Server=[pscustomobject]@{process=[pscustomobject]@{HasExited=$false}}}
    $pins=[pscustomobject]@{target=$target;base=[pscustomobject]@{commitSha=('1'*40)}}
    function Test-Path {return $script:published}
    function Get-Content {return ('{"commitSha":"'+$(if ($script:readyWrong) {'4'*40} else {'3'*40})+'","treeSha256":"'+('a'*64)+'"}')}
    function Assert-ColdTree {$script:readyTreeCalls++;return @()}
    function Wait-NativeCondition($Condition,$Seconds,$Code) {if (-not (& $Condition)) {throw $Code}}
    $receipt=Wait-NativeTargetReady $context $pins
    Assert-ReadyFixture ($receipt.manifest.commitSha -ceq $target.commitSha -and $script:readyTreeCalls -eq 3) 'READY_TREE_AND_BASE_NOT_TEMPLATE'
    $script:published=$false;Assert-ReadyRejected {Wait-NativeTargetReady $context $pins} 'READY_PREPARATION_TIMEOUT';$script:published=$true
    $script:readyWrong=$true;Assert-ReadyRejected {Wait-NativeTargetReady $context $pins} 'READY_MANIFEST_IDENTITY';$script:readyWrong=$false
    $context.Native.uiProcess.HasExited=$true;Assert-ReadyRejected {Wait-NativeTargetReady $context $pins} 'READY_PREPARATION_EXITED'
}

# Installer guard проверяется по journal/pin/command/birth: handles закрываются, никаких запусков.
& {
    $root='C:\mock-root';$birth=[datetime]::UtcNow;$script:installerOpens=0;$script:installerDisposes=0
    $target=[pscustomobject]@{commitSha=('3'*40)};$pins=[pscustomobject]@{target=$target;cold=[pscustomobject]@{helperSha256=('a'*64)}}
    $context=[pscustomobject]@{Root=$root}
    $script:journal=[pscustomobject]@{schemaVersion=2;installationRoot=$root;target=$target;phase='INSTALLING';transactionId='11111111-1111-1111-1111-111111111111'}
    $script:journalExists=$true;$script:badPin=$false;$script:badBirth=$false;$script:helperExited=$false;$script:ambiguous=$false
    function Test-Path {return $script:journalExists}
    function Get-Content {return (ConvertTo-Json -InputObject $script:journal -Depth 16)}
    function Resolve-PortableSafetyPath($Path) {return $Path}
    function Get-FileHash {return [pscustomobject]@{Hash=$(if ($script:badPin) {'b'*64} else {'a'*64})}}
    function Get-CimInstance {
        $powerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
        $path=Join-Path $root 'CashMemory/Updates/apply-update.ps1'
        $command="& '"+$path.Replace("'","''")+"' -InstallationRoot '"+$root.Replace("'","''")+"'"
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
        $entry=[pscustomobject]@{ProcessId=111;CreationDate=$birth;ExecutablePath=$powerShell;
            CommandLine=('"'+$powerShell+'" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand '+$encoded)}
        if ($script:ambiguous) {return @($entry,$entry)};return @($entry)
    }
    function Open-PortableProcess {
        $script:installerOpens++;$process=[pscustomobject]@{HasExited=$script:helperExited}
        $process | Add-Member ScriptMethod Dispose {$script:installerDisposes++};return $process
    }
    function Get-ColdProcessReceipt {return [pscustomobject]@{StartedAtTicks=$(if ($script:badBirth) {$birth.AddSeconds(1).Ticks} else {$birth.Ticks})}}
    function Get-ColdCurrentProcess {return [pscustomobject]@{mockOnly=$true}}
    $receipt=Get-NativeReadyInstaller $context $birth.AddSeconds(-1) $pins
    Assert-ReadyFixture ($null -ne $receipt -and $script:installerOpens -eq 1) 'ACTUAL_INSTALLER_GUARD_CONTROL';$receipt.process.Dispose()
    $script:journalExists=$false;Assert-ReadyFixture ($null -eq (Get-NativeReadyInstaller $context $birth $pins)) 'NO_JOURNAL_NO_INSTALLER';$script:journalExists=$true
    $script:journal.phase='COMMITTED';Assert-ReadyRejected {Get-NativeReadyInstaller $context $birth $pins} 'READY_LIVE_JOURNAL';$script:journal.phase='INSTALLING'
    $script:journal.schemaVersion=1;Assert-ReadyRejected {Get-NativeReadyInstaller $context $birth $pins} 'READY_LIVE_JOURNAL';$script:journal.schemaVersion=2
    $script:badPin=$true;Assert-ReadyRejected {Get-NativeReadyInstaller $context $birth $pins} 'READY_PRODUCTION_HELPER_PIN';$script:badPin=$false
    $script:ambiguous=$true;Assert-ReadyRejected {Get-NativeReadyInstaller $context $birth $pins} 'READY_INSTALLER_AMBIGUOUS';$script:ambiguous=$false
    $script:badBirth=$true;Assert-ReadyRejected {Get-NativeReadyInstaller $context $birth $pins} 'READY_INSTALLER_PID_REUSED';$script:badBirth=$false
    $script:helperExited=$true;Assert-ReadyFixture ($null -eq (Get-NativeReadyInstaller $context $birth $pins)) 'DEAD_INSTALLER_NOT_ACTIVE'
    Assert-ReadyFixture ($script:installerOpens -eq $script:installerDisposes) 'NO_HANDLE_LEAK_ON_REJECT'
}

# Узкие context guards выполняются с pins только в памяти; native identities - отдельные mocks.
& {
    function Assert-ColdOwnedRun($Run,$TempRoot) {return $Run}
    function Assert-NativeAbsolute($Path) {return $Path}
    function Test-PortablePathContains($Parent,$Child) {return $Child.StartsWith($Parent+'\',[StringComparison]::OrdinalIgnoreCase)}
    function Get-PortableRegistryPath($Node) {return 'mock-registry'}
    function Assert-ColdCommand { $script:checks++ }
    function Assert-NativeLifecycleConfig { $script:checks++ }
    function Assert-ColdTree {return @()}
    function Assert-ColdUiReceipt { $script:checks++ }
    function Assert-ColdProcessIdentity { $script:checks++ }
    function Get-ColdProcessReceipt($Process,$Root) {return $Process.identity}
    function Assert-NativeServerReceipt { $script:checks++ }
    function Test-Path {return $script:contextHasJournal}
    function Read-ColdPinnedJson($Path,$Hash) {
        switch ($Path) {'cmd' {return $script:mockCold} 'life' {return $script:mockLife}
            'base' {return $script:mockBase} 'target' {return $script:mockTarget} default {return $script:mockTarget}}
    }
    $script:mockBase=[pscustomobject]@{commitSha=('1'*40)};$script:mockTarget=[pscustomobject]@{commitSha=('3'*40)}
    $script:mockCold=[pscustomobject]@{targetManifest='target';targetManifestSha256=('a'*64);
        baseManifests=@([pscustomobject]@{portableDir='C:\mock-b1';manifest='base';sha256=('b'*64)})}
    $script:mockLife=[pscustomobject]@{manifestSha256=('a'*64);artifactDir='C:\mock-artifacts'}
    $script:contextHasJournal=$false
    $root='C:\mock-run\plain\CashPrediction';$node='ru/cashprediction/selftest/11111111-1111-1111-1111-111111111111'
    $native=[pscustomobject]@{identity=[pscustomobject]@{ExecutablePath=(Join-Path $root 'CashPrediction.exe');OwnedRoot=$root};uiProcess=[pscustomobject]@{HasExited=$false;identity=@{}};
        ui=[pscustomobject]@{pid=100;startedAtTicks=1;executablePath=(Join-Path $root 'CashPrediction.exe');args=@(Get-NativeArguments $root 'fx' $node)};
        process=[pscustomobject]@{StartInfo=[pscustomobject]@{Environment=@{JAVA_TOOL_OPTIONS='-XX:-UsePerfData -Dcashprediction.update.selftest.manifest=http://127.0.0.1:1234/update.json -Djava.net.useSystemProxies=false'}}}}
    $server=[pscustomobject]@{owned='C:\mock-server';identity=@{};process=[pscustomobject]@{HasExited=$false;identity=@{}};
        receipt=[pscustomobject]@{artifactDir='C:\mock-artifacts';mode='VALID';manifestUri='http://127.0.0.1:1234/update.json'}}
    $clients=[Collections.Generic.List[object]]::new();$clients.Add($native)
    $context=[pscustomobject]@{Run='C:\mock-run';Root=$root;Source='C:\mock-b1';Bases=@('C:\mock-b1','C:\mock-b2');TargetRoot='C:\mock-target';
        Evidence='C:\mock-run\evidence';Java='C:\mock-java\java.exe';CommandFile='cmd';CommandSha256=('a'*64);LifecycleFile='life';LifecycleSha256=('b'*64);
        Node=$node;Client='fx';Timeout=30;Native=$native;Server=$server;Clients=$clients;RecoveryLaunchers=[Collections.Generic.List[object]]::new()}
    Assert-ReadyFixture ((Assert-NativeReadyContext $context).target.commitSha -ceq ('3'*40)) 'VALID_CONTEXT_CONTROL'
    $context.Client='FX';Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_CONTEXT';$context.Client='fx'
    $context.Timeout=301;Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_CONTEXT';$context.Timeout=30
    $context.Root=$context.Run;Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_OWNED_CONTEXT';$context.Root=$root
    $context.Evidence=$root+'\evidence';Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_OWNED_CONTEXT';$context.Evidence='C:\mock-run\evidence'
    $context.Node='ru/cashprediction/session/fx';Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_OWNED_CONTEXT';$context.Node=$node
    $script:mockLife.manifestSha256=('c'*64);Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_TARGET_PIN';$script:mockLife.manifestSha256=('a'*64)
    $native.uiProcess.HasExited=$true;Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_CONTEXT_EXITED';$native.uiProcess.HasExited=$false
    $native.identity.OwnedRoot='C:\foreign';Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_LAUNCHER_IDENTITY';$native.identity.OwnedRoot=$root
    $native.ui.args=@('--home',$root);Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_INITIAL_ARGS';$native.ui.args=@(Get-NativeArguments $root 'fx' $node)
    $server.receipt.mode='CORRUPTDELTA';Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_SERVER_CONTEXT';$server.receipt.mode='VALID'
    $script:contextHasJournal=$true;Assert-ReadyRejected {Assert-NativeReadyContext $context} 'READY_ALREADY_APPLYING'
}

# Сценарные mocks сохраняют реальный cold safe-args/recovery validator, без native acceptance.
& {
    $script:events=[Collections.Generic.List[string]]::new()
    $root='C:\mock-run\plain\CashPrediction';$sha='3'*40;$hash='a'*64
    $script:pins=[pscustomobject]@{target=[pscustomobject]@{releaseNumber=3;commitSha=$sha};cold=[pscustomobject]@{targetManifest='mock-target'}}
    function Assert-NativeReadyContext { $script:events.Add('context');return $script:pins }
    function Wait-NativeTargetReady { $script:events.Add('ready');return [pscustomobject]@{mockOnly=$true} }
    function Write-ColdJson($Path,$Value) {Assert-ReadyFixture (-not ($Value.PSObject.Properties.Name -ccontains 'status')) 'NO_NATIVE_STATUS_WRITE'}
    function Stop-NativeReadyClient { $script:events.Add('crash');return [pscustomobject]@{exitCode=-1;mockOnly=$true} }
    function Close-NativeNormally { $script:events.Add('close');return [pscustomobject]@{exitCode=0;mockOnly=$true} }
    function Get-NativeReadyInstaller {
        $script:events.Add('installer')
        if ($script:missInstaller) {return $null}
        return $script:installer
    }
    function Wait-NativeCondition($Condition,$Seconds,$Code) {if (-not (& $Condition)) {throw $Code}}
    function Get-ColdRecoveryObservation($Root,$Started) {
        $script:sampleCount++
        $active=if ($script:scenario -ceq 'abrupt-ready-restart') {$script:sampleCount -eq 2} else {$script:sampleCount -eq 1}
        $time=[datetime]::UtcNow.ToString('o')
        return [pscustomobject]@{startedAt=$time;finishedAt=$time;journalBefore=$active;journalAfter=$active;
            visibleCount=$(if ($script:earlyUi -and $active) {1} else {0});controlledSha256=$hash}
    }
    function Start-ColdProcess($Exe,$Arguments,$Root,$Node) {
        $script:events.Add('launch');$script:actualLaunchArgs=@($Arguments)
        $launcher=[pscustomobject]@{mockOnly=$true;ExitCode=0};$launcher | Add-Member ScriptMethod WaitForExit {param($Millis) return $true}
        return $launcher
    }
    function Get-ColdProcessReceipt {return [pscustomobject]@{mockOnly=$true}}
    function Get-ColdUiReceipt {
        return [pscustomobject]@{args=@($script:actualLaunchArgs+@('--updated-from',$(if ($script:wrongSha) {'4'*40} else {$sha})));
            observedAt=[datetime]::UtcNow.ToString('o');mockOnly=$true}
    }
    function Assert-ColdUiReceipt {$script:events.Add('ui-guard')}
    function Assert-ColdTree {$script:events.Add('tree');return @()}
    function Get-ColdVersion {return $script:pins.target}
    function Invoke-ColdTool {$script:events.Add('verify');return [pscustomobject]@{mockOnly=$true}}
    function Get-Content {return ('{"outcome":"UPDATED","transactionId":"11111111-1111-1111-1111-111111111111","targetCommitSha":"'+$sha+'"}')}
    function Test-Path {return $false}
    function Start-Sleep { }
    function Get-NetTCPConnection {$script:events.Add('warm');return @()}
    $process=[pscustomobject]@{HasExited=$false;ExitCode=0}
    $process | Add-Member ScriptMethod WaitForExit {param($Millis) return $true}
    $process | Add-Member ScriptMethod Dispose { }
    $script:installer=[pscustomobject]@{process=$process;identity=[pscustomobject]@{mockOnly=$true};
        journal=[pscustomobject]@{transactionId='11111111-1111-1111-1111-111111111111'};commandLine='mock';observedUtc=[datetime]::UtcNow.ToString('o')}
    foreach ($scenario in 'abrupt-ready-restart','launch-applying-safe-args') {foreach ($client in 'fx','swing','web') {
        $script:scenario=$scenario;$script:sampleCount=0;$script:missInstaller=$false;$script:wrongSha=$false;$script:earlyUi=$false
        $script:events.Clear()
        $context=[pscustomobject]@{Root=$root;Evidence='C:\mock-evidence';Node='mock-node';Client=$client;Timeout=30;Native=@{};Java='mock-java';
            Server=[pscustomobject]@{receipt=[pscustomobject]@{mockOnly=$true};identity=[pscustomobject]@{mockOnly=$true}};RecoveryLaunchers=[Collections.Generic.List[object]]::new()}
        $receipt=Invoke-NativeReadyContextScenario $context $scenario
        Assert-ReadyFixture ($receipt.scenario -ceq $scenario -and $context.RecoveryLaunchers.Count -eq 1) 'ACTUAL_CONTEXT_FLOW'
        Assert-ReadyFixture (-not ($receipt.PSObject.Properties.Name -ccontains 'status')) 'MOCK_NEVER_NATIVE_PASS'
        $exitKind=if ($scenario -ceq 'abrupt-ready-restart') {'crash'} else {'close'}
        Assert-ReadyFixture ($script:events.IndexOf('context') -lt $script:events.IndexOf('ready') -and
            $script:events.IndexOf('ready') -lt $script:events.IndexOf($exitKind) -and
            $script:events.IndexOf($exitKind) -lt $script:events.IndexOf('launch') -and
            $script:events.IndexOf('ui-guard') -lt $script:events.IndexOf('verify')) 'SAFETY_ORDER'
        Assert-ReadyFixture (($script:actualLaunchArgs -join '|') -ceq $(if ($client -ceq 'web') {'--home|'+$root+'|--no-browser|--no-window'} else {'--home|'+$root})) 'SAFE_ORIGINAL_ARGS'
        if ($scenario -ceq 'launch-applying-safe-args') {Assert-ReadyFixture ($script:events.IndexOf('installer') -lt $script:events.IndexOf('launch')) 'LIVE_INSTALLER_BEFORE_LAUNCH'}
    }}
    $script:scenario='launch-applying-safe-args';$script:sampleCount=0;$script:missInstaller=$true;$context.RecoveryLaunchers.Clear();$script:events.Clear()
    Assert-ReadyRejected {Invoke-NativeReadyContextScenario $context $script:scenario} 'READY_LIVE_INSTALLER_NOT_OBSERVED'
    Assert-ReadyFixture (-not $script:events.Contains('launch')) 'NO_LAUNCH_WITHOUT_INSTALLER'
    $script:missInstaller=$false;$process.HasExited=$true;$script:sampleCount=0
    Assert-ReadyRejected {Invoke-NativeReadyContextScenario $context $script:scenario} 'READY_APPLYING_WINDOW_MISSED';$process.HasExited=$false
    $script:wrongSha=$true;$script:sampleCount=0;$context.RecoveryLaunchers.Clear()
    Assert-ReadyRejected {Invoke-NativeReadyContextScenario $context $script:scenario} 'COLD_REPORT_LAUNCH_IDENTITY';$script:wrongSha=$false
    $script:earlyUi=$true;$script:sampleCount=0;$context.RecoveryLaunchers.Clear()
    Assert-ReadyRejected {Invoke-NativeReadyContextScenario $context $script:scenario} 'COLD_REPORT_RECOVERY_EVIDENCE';$script:earlyUi=$false
    Assert-ReadyRejected {Invoke-NativeReadyContextScenario $context 'ABRUPT-READY-RESTART'} 'READY_SCENARIO'
}

# Crash safety проверяется отдельно: чужой клиент и stale birth запрещают любой Kill.
& {
    $script:kills=0;$root='C:\mock-root';$birth=[datetime]::UtcNow
    $identity=[pscustomobject]@{ProcessId=101;StartedAtTicks=$birth.Ticks;ExecutablePath=(Join-Path $root 'CashPrediction.exe');OwnedRoot=$root}
    $process=[pscustomobject]@{HasExited=$false;ExitCode=-1;ExitTime=$birth}
    $script:foreign=$true;$script:stale=$false
    function Get-ColdProcessReceipt {return $identity}
    function Assert-ColdProcessIdentity { }
    function Get-CopyProcesses {
        if ($script:kills) {return @()}
        return @([pscustomobject]@{ProcessId=$(if ($script:foreign) {999} else {101});ExecutablePath=$identity.ExecutablePath;
            CreationDate=$(if ($script:stale) {$birth.AddSeconds(1)} else {$birth})})
    }
    function Stop-ColdRetainedProcess {$script:kills++;$process.HasExited=$true}
    $context=[pscustomobject]@{Root=$root;Native=[pscustomobject]@{identity=$identity;process=$process;uiProcess=$process;
        ui=[pscustomobject]@{pid=101;startedAtTicks=$birth.Ticks;executablePath=$identity.ExecutablePath}}}
    Assert-ReadyRejected {Stop-NativeReadyClient $context} 'READY_FOREIGN_CLIENT'
    Assert-ReadyFixture ($script:kills -eq 0) 'FOREIGN_NO_KILL'
    $script:foreign=$false;$script:stale=$true
    Assert-ReadyRejected {Stop-NativeReadyClient $context} 'READY_CLIENT_PID_REUSED'
    Assert-ReadyFixture ($script:kills -eq 0) 'STALE_NO_KILL'
    $script:stale=$false;$receipt=Stop-NativeReadyClient $context
    Assert-ReadyFixture ($script:kills -eq 1 -and $receipt.exitCode -ne 0) 'RETAINED_CRASH_CONTROL'
    Assert-ReadyRejected {Stop-NativeReadyClient $context} 'READY_CRASH_CLIENT_EXITED'
}
if ((Get-FileHash -LiteralPath $source).Hash -cne $sourceHash) {throw 'READY_FIXTURE_SOURCE_CHANGED'}
foreach ($file in $dependencyHashes.Keys) {
    if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash -cne $dependencyHashes[$file]) {throw 'READY_FIXTURE_DEPENDENCY_CHANGED'}
}
Write-Host ('Ready scenarios fixtures PASS: '+$script:checks+' checks; mocks/static only; nativeExecuted=false')
Write-Host ('Helper SHA256: '+$sourceHash.ToLowerInvariant())
Write-Host ('Fixture SHA256: '+(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant())
