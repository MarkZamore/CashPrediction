<#
.SYNOPSIS
Только real JDK/store/FormSpec fixtures нового nonempty seed; без GUI/native/server/Maven.
#>
[CmdletBinding()]param(
    [Parameter(Mandatory)][string]$Java,[Parameter(Mandatory)][string]$JavaSha256,
    [Parameter(Mandatory)][string]$CoreJar,[Parameter(Mandatory)][string]$CoreSha256)
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$collector=Join-Path $PSScriptRoot 'NativeUpdatePhaseSessionCollector.ps1'
$collectorPin='925747a6ebf6b1e14f3ba0be9c112740ca04a71080e6a4d108e5837ea311d662'
foreach ($pin in @(@{path=$Java;sha=$JavaSha256},@{path=$CoreJar;sha=$CoreSha256},@{path=$collector;sha=$collectorPin})) {
    if ($pin.sha -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha) {throw 'GOAL_FIXTURE_PIN'}
}
$imports=@{'Test-Portable.ps1'=@('Resolve-PortableSafetyPath');
    'Test-UpdateBootstrap.ps1'=@('Test-ColdInteger','Get-ColdUtcTicks','Test-ColdInventoryEqual','ConvertFrom-ColdReceiptJson','Write-ColdJson');
    'Test-NativeUpdateLifecycle.ps1'=@('Get-NativeDomainSessionSource')}
foreach ($file in $imports.Keys) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors)
    foreach ($name in $imports[$file]) {
        $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($errors.Count -or $nodes.Count -ne 1) {throw 'GOAL_FIXTURE_IMPORT'};. ([scriptblock]::Create($nodes[0].Extent.Text))
    }
}
. $collector
. (Join-Path $PSScriptRoot 'NativeUpdateGoalRestorationProbe.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateRestoredWindowObservation.ps1')
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('cp-goal-restoration-jdk-'+[guid]::NewGuid())
[void][IO.Directory]::CreateDirectory($temporary)
$core=Join-Path $temporary 'core.jar';[IO.File]::Copy($CoreJar,$core,$false)
$source=Join-Path $temporary 'NativePhaseSessionBridge.java'
[IO.File]::WriteAllText($source,(Get-NativeGoalPhaseBridgeSource),[Text.UTF8Encoding]::new($false))
$domain=Join-Path $temporary 'NativeDomainSession.java'
[IO.File]::WriteAllText($domain,(Get-NativeDomainSessionSource),[Text.UTF8Encoding]::new($false))
$script:checks=0;$script:commands=[Collections.Generic.List[object]]::new()
# Негативные проверки не превращают корректную схему в actual window observation.
function Assert-GoalFixture([bool]$Condition,[string]$Message) {
    if (-not $Condition) {throw ('GOAL_FIXTURE:'+ $Message)};$script:checks++
}
# Только удерживаемый собственный JDK child, timeout 60 s; аргументы сохраняются без shell quoting.
function Invoke-GoalFixtureJdk([string]$Source,[string[]]$Arguments,[string]$Label) {
    if ((Get-FileHash -LiteralPath $Java).Hash.ToLowerInvariant() -cne $JavaSha256 -or
        (Get-FileHash -LiteralPath $core).Hash.ToLowerInvariant() -cne $CoreSha256) {throw 'GOAL_FIXTURE_PIN'}
    $info=[Diagnostics.ProcessStartInfo]::new($Java);$info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.WorkingDirectory=$temporary;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    foreach ($key in 'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS') {[void]$info.Environment.Remove($key)}
    $effective=@('-XX:-UsePerfData','-cp',$core,$Source)+$Arguments
    foreach ($arg in $effective) {$info.ArgumentList.Add($arg)}
    $process=[Diagnostics.Process]::Start($info)
    $identity=[pscustomobject]@{pid=$process.Id;birth=$process.StartTime.ToUniversalTime().Ticks;ownedRoot=$temporary}
    try {
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(60000)) {throw 'GOAL_FIXTURE_TIMEOUT'}
        $out=$stdout.GetAwaiter().GetResult();$err=$stderr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText((Join-Path $temporary ($Label+'.out.txt')),$out)
        [IO.File]::WriteAllText((Join-Path $temporary ($Label+'.err.txt')),$err)
        $receipt=[pscustomobject]@{executable=$Java;arguments=$effective;identity=$identity;exitCode=$process.ExitCode;stdout=$out;stderr=$err}
        $script:commands.Add($receipt);return $receipt
    } finally {
        if (-not $process.HasExited) {$process.Kill();[void]$process.WaitForExit(5000)};$process.Dispose()
    }
}
# Все writes принадлежат собственному Temp; реальные stores проверяются для трёх клиентов.
foreach ($client in 'fx','swing','web') {
    $root=Join-Path $temporary ('unicode-Δ测试/'+$client)
    [void][IO.Directory]::CreateDirectory((Join-Path $root 'CashMemory'))
    $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($root))
    $prepared=Invoke-GoalFixtureJdk $domain @($encoded) ($client+'-domain')
    Assert-GoalFixture ($prepared.exitCode -eq 0) ($client+' domain '+$prepared.stderr)
    $seed=Invoke-GoalFixtureJdk $source @('seed',$encoded,$client) ($client+'-seed')
    Assert-GoalFixture ($seed.exitCode -eq 0) ($client+' seed '+$seed.stderr)
    $decoded=ConvertFrom-ColdReceiptJson $seed.stdout;$entry=$decoded.sessions.$client
    $window=$entry.windowExpectation.windows[0]
    Assert-GoalFixture ($entry.needsRestore -ceq 'CRASHED' -and $entry.markerState -ceq 'running' -and $entry.markerPid -eq 0) ($client+' real CrashDetector')
    Assert-GoalFixture ($window.coreFormSpecId -ceq 'goalCalculator' -and $window.ownerId -ceq 'main' -and
        $window.context.probeToken -ceq 'phase-goal-731' -and $window.context.page -ceq '0' -and -not $window.modal) ($client+' identity/context')
    Assert-GoalFixture (@($window.fields).Count -eq 4 -and
        @($window.fields | Where-Object {$_.id -ceq 'target' -and $_.text -ceq '450 731,00'}).Count -eq 1 -and
        @($window.fields | Where-Object {$_.id -ceq 'byDateEnabled' -and $_.text -ceq 'true'}).Count -eq 1 -and
        @($window.fields | Where-Object {$_.id -ceq 'byDate' -and $_.text -ceq '31.07.2031'}).Count -eq 1 -and
        @($window.fields | Where-Object {$_.id -ceq 'extraSaving' -and $_.text -ceq '1 731,00'}).Count -eq 1) ($client+' real FieldCodec displays')
    Assert-GoalFixture ($entry.windowExpectation.seedRevisionSha256 -ceq
        (Get-NativePhaseSessionSha ([Text.Encoding]::UTF8.GetBytes($entry.snapshotJson)))) ($client+' UTF8 snapshot pin')
    # Только contract fixture: порядок core codec/FormSession и raw widget полей, НЕ actual UI.
    $fresh=ConvertFrom-ColdReceiptJson $entry.snapshotJson;$fresh.savedAt='2026-10-04T23:59:59Z';$fresh.windows[0].id='w11'
    $actualFields=@(foreach ($field in $window.fields) {
        [pscustomobject]@{id=$field.id;kind=$field.kind;text=$field.text;visible=$true;readOnly=$false}
    })
    $dump=[pscustomobject]@{schema=1;client=$client;scenario='phase-restored';step='restored';frame=$null;menuBar=@();toolbar=$null;
        summary=$null;table=$null;chart=$null;status=@();contextMenus=@();alerts=@();popups=@();screens=@();chooserRequests=@();classCensus=@{};counters=@{};
        windows=@([pscustomobject]@{id='w11';type='GOAL_CALCULATOR';purpose='';modal=$false;ownerId='main';page=0;
            bounds=[pscustomobject]@{width=640;height=400};fields=$actualFields})}
    $contract=Test-NativeRestoredWindowContract $entry.windowExpectation $dump $fresh
    Assert-GoalFixture ($contract.status -ceq 'WINDOW_CONTRACT_VALIDATED' -and -not $contract.nativePass) ($client+' expected matches dump contract, not UI origin')
    foreach ($foreign in @('fx','swing','web') | Where-Object {$_ -cne $client}) {
        Assert-GoalFixture ($decoded.sessions.$foreign.markerState -ceq 'closed' -and
            @($decoded.sessions.$foreign.windowExpectation.windows).Count -eq 0) ($client+' foreign default '+$foreign)
    }
    $before=Get-NativePhaseSessionInventory $root
    $read=Invoke-GoalFixtureJdk $source @('read',$encoded,$client) ($client+'-read')
    Assert-GoalFixture ($read.exitCode -eq 0 -and (Test-ColdInventoryEqual $decoded (ConvertFrom-ColdReceiptJson $read.stdout)) -and
        (Test-ColdInventoryEqual $before (Get-NativePhaseSessionInventory $root))) ($client+' fresh codec and bytes')
    $rearm=Invoke-GoalFixtureJdk $source @('rearm',$encoded,$client) ($client+'-rearm')
    Assert-GoalFixture ($rearm.exitCode -eq 0) ($client+' explicit rearm '+$rearm.stderr)
    $rearmed=ConvertFrom-ColdReceiptJson $rearm.stdout
    Assert-GoalFixture ($entry.snapshotJson -ceq $rearmed.sessions.$client.snapshotJson -and $rearmed.sessions.$client.needsRestore -ceq 'CRASHED') ($client+' rearm keeps snapshot')
    $first=[pscustomobject]@{decoded=$decoded};$second=[pscustomobject]@{decoded=$rearmed}
    Assert-NativeGoalTransactionValues $first $second $client
    Assert-GoalFixture $true ($client+' independent content contract')
    $second.decoded.sessions.$client.windowExpectation.windows[0].canonicalFields.target='1,00'
    $rejected=$false;try {Assert-NativeGoalTransactionValues $first $second $client} catch {$rejected=$_.Exception.Message -ceq 'GOAL_TRANSACTION_CHANGED:canonicalFields'}
    Assert-GoalFixture $rejected ($client+' changed target rejected')
    # Только новые nonempty seed negatives; старые 50 seed checks не повторяются.
    if ($client -ceq 'fx') {
        foreach ($mode in 'malformed','truncated') {
            $negative=Join-Path $temporary ('negative-'+$mode);[void][IO.Directory]::CreateDirectory($negative)
            Copy-Item -LiteralPath (Join-Path $root 'CashMemory') -Destination (Join-Path $negative 'CashMemory') -Recurse
            $file=Join-Path $negative 'CashMemory/session-fx.xml';$bytes=[IO.File]::ReadAllBytes($file)
            if ($mode -ceq 'malformed') {[IO.File]::WriteAllText($file,'<session')} else {[IO.File]::WriteAllBytes($file,$bytes[0..([int]($bytes.Length/2))])}
            $argument=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($negative))
            $bad=Invoke-GoalFixtureJdk $source @('read',$argument,'fx') ('negative-'+$mode)
            Assert-GoalFixture ($bad.exitCode -ne 0) ('real nonempty '+$mode)
        }
    }
}
$generated=Get-NativeGoalCollectorStartSource $collector $collectorPin
$tokens=$null;$errors=$null;$null=[Management.Automation.Language.Parser]::ParseInput($generated,[ref]$tokens,[ref]$errors)
Assert-GoalFixture ($errors.Count -eq 0 -and $generated.Contains('Get-NativeGoalPhaseBridgeSource') -and
    $generated.IndexOf('goal-expected.json') -lt $generated.IndexOf("Save-NativePhaseSessionObservation `$context 'baseline'")) 'opt-in AST order'
$producer=Join-Path $PSScriptRoot 'NativeUpdatePhaseScenarios.ps1'
$producerPin=(Get-FileHash -LiteralPath $producer).Hash.ToLowerInvariant()
$hook=New-NativeGoalPhaseHookSource $producer $producerPin
$null=[Management.Automation.Language.Parser]::ParseInput($hook,[ref]$tokens,[ref]$errors)
Assert-GoalFixture ($errors.Count -eq 0 -and $hook.Contains('Start-NativeGoalPhaseSessionCollector') -and
    $hook.Contains('Assert-ColdSafeArgs $nativeArgs $native.ui.args') -and
    -not $hook.Contains('--selftest-recovery')) 'phase safeArgs unchanged; supplemental only'
Assert-GoalFixture ((Get-FileHash -LiteralPath $collector).Hash.ToLowerInvariant() -ceq $collectorPin -and
    (Get-FileHash -LiteralPath $CoreJar).Hash.ToLowerInvariant() -ceq $CoreSha256) 'frozen inputs'
$result=[ordered]@{status='ISOLATED_NONEMPTY_SEED_VALIDATED';checks=$script:checks;nativeExecuted=$false;actualWindowObservation='PENDING';
    javaSha256=$JavaSha256;coreSha256=$CoreSha256;bridgeSha256=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant();
    commands=@($script:commands.ToArray());diagnostics=$temporary}
Write-ColdJson (Join-Path $temporary 'fixture-results.json') $result
[pscustomobject]@{checks=$script:checks;status=$result.status;nativeExecuted=$false;diagnostics=$temporary} | ConvertTo-Json -Compress
