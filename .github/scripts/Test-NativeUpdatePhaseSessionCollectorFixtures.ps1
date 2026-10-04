<#
.SYNOPSIS
Focused pure/IO fixtures нового collector; никаких JDK/helper/native/GUI запусков.
.DESCRIPTION
Проверяет отрицательные bytes/revision/chronology контракты и точные in-memory hooks.
Mock codec receipt не считается fresh actual JDK и никогда не даёт native PASS.
Временные complete-file fixtures сохраняются для диагностики, shared target не меняется.
#>
[CmdletBinding()]param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
$script:checks=0
$cold=Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'
$producer=Join-Path $PSScriptRoot 'NativeUpdatePhaseScenarios.ps1'
$protected=@($cold,$producer,(Join-Path $PSScriptRoot 'NativeUpdatePhaseAcceptance.ps1'),(Join-Path $PSScriptRoot 'NativeUpdateAcceptanceDispatch.ps1'))
$pins=@{};foreach ($path in $protected) {$pins[$path]=(Get-FileHash -LiteralPath $path).Hash}
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($cold,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'FIXTURE_COLD_PARSE'}
foreach ($name in 'Test-ColdInteger','Test-ColdInventoryEqual','Get-ColdUtcTicks') {
    $nodes=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true))
    if ($nodes.Count -ne 1) {throw 'FIXTURE_GUARD'}
    . ([scriptblock]::Create($nodes[0].Extent.Text))
}
. (Join-Path $PSScriptRoot 'NativeUpdatePhaseSessionCollector.ps1')

# Фикстура не может случайно превратиться в настоящий запуск JDK либо native.
function Start-NativeOwned {throw 'FIXTURE_PROCESS_FORBIDDEN'}
function Resolve-PortableSafetyPath([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path);$cursor=$full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if (((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {throw 'FIXTURE_LINK'}
        }
        $parent=[IO.Path]::GetDirectoryName($cursor);if ($parent -eq $cursor) {break};$cursor=$parent
    }
    return $full
}

# Точное условие/код: посторонняя exception не считается положительным отрицательным тестом.
function Assert-PhaseSessionFixture([bool]$Condition,[string]$Message) {
    if (-not $Condition) {throw ('FIXTURE:'+ $Message)};$script:checks++
}
function Assert-PhaseSessionReject([scriptblock]$Action,[string]$Code) {
    $caught='';try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    Assert-PhaseSessionFixture ($caught -ceq $Code) ('reject '+$Code+' actual '+$caught)
}

# Канонические mock bytes и codec content нужны только для validator regression.
function New-PhaseSessionFixture {
    $files=@(foreach ($path in @('CashMemory/settings.md','CashMemory/PhaseSession.md','CashMemory/NativeLifecycle.md',
        'CashMemory/session-fx.xml','CashMemory/session-swing.xml','CashMemory/web-session.md','CashMemory/web-session.plan.md')) {
        $bytes=[Text.Encoding]::UTF8.GetBytes('mock:'+ $path)
        [pscustomobject]@{path=$path;sizeBytes=$bytes.Length;sha256=(Get-NativePhaseSessionSha $bytes);
            contentBase64=[Convert]::ToBase64String($bytes);readOnly=$false}
    })
    $sessions=[pscustomobject]@{}
    foreach ($client in 'fx','swing','web') {
        $json='{"fixtureOnly":"'+$client+'"}'
        $sessions | Add-Member $client ([pscustomobject]@{client=$client;snapshotJson=$json;
            revisionSha256=(Get-NativePhaseSessionSha ([Text.Encoding]::UTF8.GetBytes($json)));
            savedAt='2026-10-04T00:00:00Z';markerPid=0;markerStartedAt='2026-10-04T00:00:00Z';markerState='closed'})
    }
    $context=[pscustomobject]@{client='fx';installedAt='2026-10-04T01:00:00Z';observations=[Collections.Generic.List[object]]::new()}
    $index=1
    foreach ($stage in 'baseline','checkpoint','recovery','cleanup') {
        $context.observations.Add([pscustomobject]@{stage=$stage;
            startedAt=('2026-10-04T01:00:0'+$index+'Z');finishedAt=('2026-10-04T01:00:0'+$index+'Z');
            files=($files | ConvertTo-Json -Depth 20 | ConvertFrom-Json);
            codec=[pscustomobject]@{decoded=[pscustomobject]@{settingsCanonical='mock settings';planCanonical='mock plan';
                sessions=($sessions | ConvertTo-Json -Depth 20 | ConvertFrom-Json)}};
            ui=$(if ($stage -ceq 'recovery') {[pscustomobject]@{fixtureOnly=$true}} else {$null});physicalRestorationObserved=$false})
        $index++
    }
    return $context
}

$fixture=New-PhaseSessionFixture;$decision=Test-NativePhaseSessionObservations $fixture
Assert-PhaseSessionFixture ($decision.status -ceq 'PENDING' -and -not $decision.nativePass -and $decision.errors.Count -eq 0) 'mock cannot pass'
Assert-PhaseSessionFixture ($decision.missing -ccontains 'ACTUAL_RESTORED_USER_WINDOW_NOT_OBSERVED') 'window missing'
Assert-PhaseSessionFixture ($decision.missing -ccontains 'FRESH_OWNED_SESSION_REVISION_NOT_OBSERVED') 'fresh revision absent'
Assert-PhaseSessionFixture ((Test-NativePhaseSessionObservations $null).status -ceq 'PENDING') 'no producer'
Assert-PhaseSessionFixture ((Get-NativePhaseSessionFailureDecision 'PHASE_SESSION_PIN').status -ceq 'FAIL') 'pin contradiction cannot pending'
Assert-PhaseSessionFixture ((Get-NativePhaseSessionFailureDecision 'PHASE_SESSION_CHANGED').status -ceq 'FAIL') 'byte contradiction cannot pending'
Assert-PhaseSessionFixture ((Get-NativePhaseSessionFailureDecision 'COLD_REPORT_CONTROLLED').status -ceq 'FAIL') 'controlled contradiction cannot pending'
Assert-PhaseSessionFixture ((Get-NativePhaseSessionFailureDecision 'sharing violation').status -ceq 'PENDING') 'busy unavailable not fake proof'
Assert-PhaseSessionFixture ((Publish-NativePhaseSessionCollectorResult $null '').status -ceq 'PENDING') 'failed install missing receipt'
Assert-PhaseSessionFixture ((Publish-NativePhaseSessionCollectorResult $null 'PHASE_SESSION_PIN').status -ceq 'FAIL') 'failed install pin contradiction'
$fixture | Add-Member status 'PASS';$fixture | Add-Member helperStatus 'PASS'
Assert-PhaseSessionFixture ((Test-NativePhaseSessionObservations $fixture).status -ceq 'PENDING') 'helper return ignored'
foreach ($stage in 'baseline','checkpoint','recovery','cleanup') {
    $fixture=New-PhaseSessionFixture
    $entry=@($fixture.observations | Where-Object stage -CEQ $stage)[0];[void]$fixture.observations.Remove($entry)
    $decision=Test-NativePhaseSessionObservations $fixture
    Assert-PhaseSessionFixture ($decision.status -ceq 'PENDING' -and $decision.missing -ccontains ('STAGE:'+ $stage)) ('missing '+$stage)
}
$cases=@(
    @{code='BYTES';edit={param($f) $f.observations[2].files[0].sha256='0'*64}},
    @{code='BYTES';edit={param($f) $f.observations[2].files[0].contentBase64='!'}},
    @{code='BYTES';edit={param($f) $f.observations[2].files[0].sizeBytes='10'}},
    @{code='BYTES';edit={param($f) $f.observations[2].files[0].readOnly=1}},
    @{code='BYTES';edit={param($f) $f.observations[2].files+=@($f.observations[2].files[0])}},
    @{code='DOMAIN_CHANGED:CashMemory/settings.md';edit={param($f) $f.observations[2].files[0].readOnly=$true}},
    @{code='DOMAIN_CODEC_CHANGED';edit={param($f) $f.observations[2].codec.decoded.settingsCanonical='different'}},
    @{code='CODEC_REVISION';edit={param($f) $f.observations[2].codec.decoded.sessions.fx.revisionSha256='a'*64}},
    @{code='CODEC_REVISION';edit={param($f) $f.observations[2].codec.decoded.sessions.fx.markerPid=$true}},
    @{code='CODEC_REVISION';edit={param($f) $f.observations[2].codec.decoded.sessions.fx.markerPid=2147483648L}},
    @{code='FOREIGN_SESSION_CHANGED';edit={param($f) $f.observations[2].codec.decoded.sessions.swing.markerState='running'}},
    @{code='FOREIGN_SESSION_BYTES_CHANGED';edit={param($f) $f.observations[2].files[4].readOnly=$true}},
    @{code='TIME';edit={param($f) $f.observations[2].finishedAt='2026-10-04T00:59:00Z'}},
    @{code='ORDER';edit={param($f) $f.observations[2].startedAt='2026-10-04T00:59:00Z'}},
    @{code='DUPLICATE_STAGE';edit={param($f) $f.observations.Add($f.observations[0])}}
)
foreach ($case in $cases) {
    $fixture=New-PhaseSessionFixture;& $case.edit $fixture;$decision=Test-NativePhaseSessionObservations $fixture
    Assert-PhaseSessionFixture ($decision.status -ceq 'FAIL' -and $decision.errors -ccontains $case.code -and -not $decision.nativePass) $case.code
}
$fixture=New-PhaseSessionFixture;$fixture.observations[2].ui=$null
Assert-PhaseSessionFixture ((Test-NativePhaseSessionObservations $fixture).missing -ccontains 'ACTUAL_RETAINED_RECOVERY_IDENTITY') 'no retained witness'
$fixture=New-PhaseSessionFixture;$fixture.observations[2].physicalRestorationObserved=$true
Assert-PhaseSessionFixture ((Test-NativePhaseSessionObservations $fixture).status -ceq 'PENDING') 'self-reported window ignored'
$fixture.observations[2] | Add-Member freshOwnedRevisionObserved $true
Assert-PhaseSessionFixture ((Test-NativePhaseSessionObservations $fixture).status -ceq 'PENDING') 'revision not physical restoration'
$fixture=New-PhaseSessionFixture;$fixture.observations[2].codec=$null
Assert-PhaseSessionFixture ((Test-NativePhaseSessionObservations $fixture).missing -ccontains 'FRESH_CODEC_NOT_PRODUCED') 'codec absent'

$sha=(Get-FileHash -LiteralPath $producer).Hash.ToLowerInvariant()
$hook=New-NativePhaseSessionHookSource $producer $sha
$null=[Management.Automation.Language.Parser]::ParseInput($hook,[ref]$tokens,[ref]$errors)
Assert-PhaseSessionFixture ($errors.Count -eq 0 -and $hook.Contains('function Invoke-NativePhaseScenarioWithSessionCollector(')) 'hook parse'
Assert-PhaseSessionFixture ($hook.IndexOf('Start-NativePhaseSessionCollector') -lt $hook.IndexOf('$userBefore=Get-NativeUserObject')) 'seed before baseline'
Assert-PhaseSessionFixture ($hook.IndexOf('Start-NativePhaseSessionCollector') -lt $hook.IndexOf('$helper=Start-ColdProcess')) 'seed before helper'
Assert-PhaseSessionFixture ($hook.IndexOf("'checkpoint'") -lt $hook.IndexOf('Stop-ColdRetainedProcess $helper $identity $root')) 'checkpoint before kill'
Assert-PhaseSessionFixture ($hook.IndexOf("'recovery' `$native.ui") -lt $hook.IndexOf('Stop-ColdCopyProcesses $root')) 'recovery before cleanup'
Assert-PhaseSessionFixture ($hook.IndexOf('Publish-NativePhaseSessionCollectorResult') -gt $hook.IndexOf('finally {')) 'cleanup after finally'
Assert-PhaseSessionReject {New-NativePhaseSessionHookSource $producer ('0'*64)} 'PHASE_SESSION_PRODUCER_PIN'

$temp=Join-Path ([IO.Path]::GetTempPath()) ('cp-phase-session-fixtures-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($temp)
$file=Join-Path $temp 'complete.bin';$bytes=[Text.Encoding]::UTF8.GetBytes('полный bounded файл')
[IO.File]::WriteAllBytes($file,$bytes)
Assert-PhaseSessionFixture ((Get-NativePhaseSessionSha (Read-NativePhaseSessionBytes $file)) -ceq (Get-NativePhaseSessionSha $bytes)) 'actual complete file'
$writer=[IO.FileStream]::new($file,[IO.FileMode]::Open,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite)
try {
    $busy=$false;try {$null=Read-NativePhaseSessionBytes $file} catch {$busy=$_.Exception.InnerException -is [IO.IOException] -or $_.Exception -is [IO.IOException]}
    Assert-PhaseSessionFixture $busy 'busy writer rejected not empty baseline'
} finally {$writer.Dispose()}
$run='run-'+[guid]::NewGuid().ToString();$nativeRoot=Join-Path (Join-Path (Join-Path ([IO.Path]::GetTempPath()) $run) 'Δ 测试') 'CashPrediction'
$evidence=Join-Path (Join-Path ([IO.Path]::GetTempPath()) ('cp-native-phase-evidence-'+[guid]::NewGuid().ToString())) $run
Assert-NativePhaseSessionScope $nativeRoot $evidence
Assert-PhaseSessionFixture $true 'unicode target and ascii evidence scope'
Assert-PhaseSessionReject {Assert-NativePhaseSessionScope 'C:/Users/Oscar/Documents/CashPrediction' $evidence} 'PHASE_SESSION_SCOPE'
Assert-PhaseSessionReject {Assert-NativePhaseSessionScope $nativeRoot ($evidence+'-foreign')} 'PHASE_SESSION_SCOPE'
Assert-PhaseSessionFixture ((Get-NativePhaseSessionPaths).Count -eq 10) 'closed file allowlist'
$bridge=Get-NativePhaseSessionBridgeSource
Assert-PhaseSessionFixture ($bridge.Contains('new PlanRepository') -and $bridge.Contains('SettingsMarkdown.save') -and
    $bridge.Contains('XmlSessionStore.inCashMemory') -and $bridge.Contains('MarkdownSessionStore.inCashMemory')) 'actual writers'
Assert-PhaseSessionFixture (-not $bridge.Contains('ProcessBuilder') -and -not $bridge.Contains('RegistrySessionStore')) 'no native or registry bridge'
foreach ($path in $protected) {Assert-PhaseSessionFixture ((Get-FileHash -LiteralPath $path).Hash -ceq $pins[$path]) ('frozen '+$path)}
[pscustomobject]@{status='FIXTURE_CONTRACTS_VALIDATED';checks=$script:checks;nativeExecuted=$false;jdkExecuted=$false;
    phaseAcceptance='PENDING';diagnostics=$temp} | ConvertTo-Json -Compress
