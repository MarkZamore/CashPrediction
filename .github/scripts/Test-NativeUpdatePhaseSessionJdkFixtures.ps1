<#
.SYNOPSIS
Реальные isolated JDK domain/store/codec fixtures, без native helper и UI.
.DESCRIPTION
Исполняет frozen Java source bridge с независимо заданными SHA JDK/core/collector.
Built development JAR не выдаётся за release/native image: Get-ColdVersion не подменяется.
Используются настоящие retained process guards и filesystem observations collector.
Malformed/truncated копии seed проверяются настоящими codecs и byte guard, не mock exit.
Evidence сохраняется в собственном Temp; никакого удаления, shared target или registry writes.
#>
[CmdletBinding()]param(
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$JavaSha256,
    [Parameter(Mandatory)][string]$CoreJar,
    [Parameter(Mandatory)][string]$CoreSha256,
    [string]$CollectorSha256='925747a6ebf6b1e14f3ba0be9c112740ca04a71080e6a4d108e5837ea311d662'
)
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'JDK_FIXTURE_WINDOWS_PS7'}
$collector=Join-Path $PSScriptRoot 'NativeUpdatePhaseSessionCollector.ps1'
foreach ($pin in @(@{path=$Java;sha=$JavaSha256},@{path=$CoreJar;sha=$CoreSha256},@{path=$collector;sha=$CollectorSha256})) {
    if (-not [IO.Path]::IsPathFullyQualified($pin.path) -or $pin.sha -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha) {throw 'JDK_FIXTURE_INPUT_PIN'}
}

# Загружаются только нужные definitions, не тела существующих runners и не mock providers.
$imports=@{
    'Test-Portable.ps1'=@('Resolve-PortableSafetyPath','Get-CopyProcesses','Get-PortableRealRegistrySnapshot')
    'Test-UpdateBootstrap.ps1'=@('Test-ColdInteger','Assert-ColdProcessIdentity','Get-ColdProcessReceipt','Stop-ColdRetainedProcess',
        'Get-ColdUtcTicks','Test-ColdInventoryEqual','ConvertFrom-ColdReceiptJson','Write-ColdJson')
    'Test-NativeUpdateLifecycle.ps1'=@('Start-NativeOwned','Wait-NativeCondition','Save-NativeOutput','Get-NativeDomainSessionSource')
}
$sourcePins=[ordered]@{}
foreach ($file in $imports.Keys) {
    $path=Join-Path $PSScriptRoot $file;$sourcePins[$path]=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'JDK_FIXTURE_IMPORT_PARSE'}
    foreach ($name in $imports[$file]) {
        $nodes=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true))
        if ($nodes.Count -ne 1) {throw ('JDK_FIXTURE_IMPORT:'+ $name)}
        . ([scriptblock]::Create($nodes[0].Extent.Text))
    }
}
. $collector
$protected=@($collector,(Join-Path $PSScriptRoot 'NativeUpdatePhaseAcceptance.ps1'),
    (Join-Path $PSScriptRoot 'NativeUpdateAcceptanceDispatch.ps1'),(Join-Path $PSScriptRoot 'NativeUpdatePhaseScenarios.ps1'))
foreach ($path in $protected) {$sourcePins[$path]=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}
[void](Resolve-PortableSafetyPath $Java);[void](Resolve-PortableSafetyPath $CoreJar)
$evidenceRoot=Join-Path ([IO.Path]::GetTempPath()) ('cp-native-phase-evidence-'+[guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($evidenceRoot)
$script:checks=0;$script:commands=[Collections.Generic.List[object]]::new();$cells=[Collections.Generic.List[object]]::new()
$started=[datetime]::UtcNow.ToString('o')

# Любая ошибка теста оставляет диагностические bytes, но не native PASS.
function Assert-PhaseJdkFixture([bool]$Condition,[string]$Message) {
    if (-not $Condition) {throw ('JDK_FIXTURE:'+ $Message)};$script:checks++
}

# Writer negatives ограничены собственными каталогами; links запрещены на всех предках.
function Assert-PhaseJdkOwned([string]$Path,[string]$Parent) {
    $safe=Resolve-PortableSafetyPath $Path;$base=Resolve-PortableSafetyPath $Parent
    if (-not $safe.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)) {throw 'JDK_FIXTURE_WRITE_SCOPE'}
    return $safe
}

# Настоящий JDK child с ограничением 60 s, retained identity и raw stdout/stderr для negative exit.
function Invoke-PhaseJdkRaw($Context,[string]$Source,[string[]]$Arguments,[string]$Label) {
    if ((Get-FileHash -LiteralPath $Java).Hash.ToLowerInvariant() -cne $JavaSha256 -or
        (Get-FileHash -LiteralPath $Context.core).Hash.ToLowerInvariant() -cne $CoreSha256) {throw 'JDK_FIXTURE_INPUT_PIN'}
    [void](Assert-PhaseJdkOwned $Source $evidenceRoot)
    $effective=@('-XX:-UsePerfData','-cp',$Context.core,$Source)+$Arguments
    $child=Start-NativeOwned $Java $effective $Context.directory
    try {
        Wait-NativeCondition {$child.process.HasExited} 60 'JDK_FIXTURE_TIMEOUT'
        Save-NativeOutput $child $Context.directory $Label
        $receipt=[pscustomobject]@{executable=$Java;arguments=$effective;identity=$child.identity;exitCode=$child.process.ExitCode;
            stdout=(Join-Path $Context.directory ($Label+'.out.txt'));stderr=(Join-Path $Context.directory ($Label+'.err.txt'));
            observedAt=[datetime]::UtcNow.ToString('o')}
        $script:commands.Add($receipt);return $receipt
    } finally {
        if (-not $child.process.HasExited) {Stop-ColdRetainedProcess $child.process $child.identity $Context.directory}
        $child.process.Dispose()
    }
}

# Collector-return identity/arguments связываются с сохранёнными logs, а не с предполагаемым exit.
function Add-PhaseJdkCodecCommand($Context,$Codec,[string]$Label) {
    $script:commands.Add([pscustomobject]@{executable=$Java;arguments=$Codec.arguments;identity=$Codec.process;exitCode=$Codec.exitCode;
        stdout=(Join-Path $Context.directory ($Label+'.out.txt'));stderr=(Join-Path $Context.directory ($Label+'.err.txt'));
        observedAt=$Codec.readAt})
}

# Независимый negative root создаётся из настоящего полного seed, никогда не из synthetic XML.
function New-PhaseJdkNegativeRoot($Context,[string]$Name) {
    $negative=Join-Path $Context.directory ('negative-'+$Name)
    [void](Assert-PhaseJdkOwned $negative $evidenceRoot)
    if (Test-Path -LiteralPath $negative) {throw 'JDK_FIXTURE_NEGATIVE_EXISTS'}
    [void][IO.Directory]::CreateDirectory($negative)
    Copy-Item -LiteralPath (Join-Path $Context.root 'CashMemory') -Destination (Join-Path $negative 'CashMemory') -Recurse
    return $negative
}

$failure=$null
try {
    foreach ($client in 'fx','swing','web') {
        $runName='run-'+[guid]::NewGuid().ToString()
        $runRoot=Join-Path ([IO.Path]::GetTempPath()) $runName
        $root=Join-Path (Join-Path $runRoot $(if ($client -ceq 'fx') {'plain'} else {'Δ 测试'})) 'CashPrediction'
        $cell=Join-Path $evidenceRoot $runName
        [void][IO.Directory]::CreateDirectory((Join-Path $root 'CashMemory'))
        [void][IO.Directory]::CreateDirectory($cell)
        Assert-NativePhaseSessionScope $root $cell
        $directory=Join-Path $cell 'phase-session-independent';[void][IO.Directory]::CreateDirectory($directory)
        $core=Join-Path $directory 'phase-core.jar';[IO.File]::Copy($CoreJar,$core,$false)
        $source=Join-Path $directory 'NativePhaseSessionBridge.java'
        [IO.File]::WriteAllText($source,(Get-NativePhaseSessionBridgeSource),[Text.UTF8Encoding]::new($false))
        $context=[pscustomobject]@{root=$root;client=$client;directory=$directory;java=$Java;javaSha=$JavaSha256;
            core=$core;coreSha=$CoreSha256;source=$source;sourceSha=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant();
            installedAt=[datetime]::UtcNow.ToString('o');registryBefore=(Get-PortableRealRegistrySnapshot);
            observations=[Collections.Generic.List[object]]::new();seed=$null}
        $domain=Join-Path $directory 'NativeDomainSession.java'
        [IO.File]::WriteAllText($domain,(Get-NativeDomainSessionSource),[Text.UTF8Encoding]::new($false))
        $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($root))
        $domainReceipt=Invoke-PhaseJdkRaw $context $domain @($encoded) 'domain'
        Assert-PhaseJdkFixture ($domainReceipt.exitCode -eq 0 -and
            [IO.File]::ReadAllText($domainReceipt.stdout).Trim() -ceq 'NATIVE_DOMAIN_SESSION_PREPARED') ($client+' actual domain seed')
        $context.seed=Invoke-NativePhaseSessionCodec $context 'seed' $root 'seed'
        Add-PhaseJdkCodecCommand $context $context.seed 'seed'
        $before=Save-NativePhaseSessionObservation $context 'baseline'
        Add-PhaseJdkCodecCommand $context $before.codec 'baseline'
        $after=Save-NativePhaseSessionObservation $context 'jdk-after'
        Add-PhaseJdkCodecCommand $context $after.codec 'jdk-after'
        Assert-PhaseJdkFixture (Test-ColdInventoryEqual $before.files $after.files) ($client+' actual bytes preserved')
        Assert-PhaseJdkFixture (Test-ColdInventoryEqual $context.seed.decoded $before.codec.decoded) ($client+' writer expected baseline')
        Assert-PhaseJdkFixture (Test-ColdInventoryEqual $before.codec.decoded $after.codec.decoded) ($client+' fresh JDK revisions preserved')
        Assert-PhaseJdkFixture ($before.codec.process.ProcessId -ne $after.codec.process.ProcessId -or
            $before.codec.process.StartedAtTicks -ne $after.codec.process.StartedAtTicks) ($client+' distinct fresh JDK process')
        Assert-PhaseJdkFixture (-not $before.physicalRestorationObserved -and -not $after.physicalRestorationObserved) ($client+' not window restoration')
        $complete=Complete-NativePhaseSessionCollector $context
        $cleanup=$context.observations[-1];Add-PhaseJdkCodecCommand $context $cleanup.codec 'cleanup'
        Assert-PhaseJdkFixture ($complete.status -ceq 'PENDING' -and -not $complete.nativePass -and $complete.errors.Count -eq 0) ($client+' no native phase PASS')
        Assert-PhaseJdkFixture ((Get-FileHash -LiteralPath $complete.Receipt).Hash.ToLowerInvariant() -ceq $complete.ReceiptSha256) ($client+' independent receipt SHA')
        Assert-PhaseJdkFixture (Test-ColdInventoryEqual $before.files $cleanup.files) ($client+' after collector bytes unchanged')
        $cells.Add([pscustomobject]@{client=$client;root=$root;core=$core;source=$source;sourceSha256=$context.sourceSha;
            domainSource=$domain;domainSourceSha256=(Get-FileHash -LiteralPath $domain).Hash.ToLowerInvariant();completion=$complete})

        # Одного fx seed достаточно для реальных corrupt/truncated XML/Markdown/plan negatives.
        if ($client -ceq 'fx') {
            $negatives=@(
                @{name='xml-malformed';path='CashMemory/session-fx.xml';mode='malformed';text='<session';error='SessionStoreException'},
                @{name='xml-truncated';path='CashMemory/session-swing.xml';mode='truncated';error='SessionStoreException'},
                @{name='web-malformed';path='CashMemory/web-session.md';mode='malformed';text='not a session';error='Exception'},
                @{name='web-truncated';path='CashMemory/web-session.md';mode='truncated';error='Exception'},
                @{name='plan-malformed';path='CashMemory/PhaseSession.md';mode='malformed';text='not a plan';error='MarkdownParseException'}
            )
            foreach ($negative in $negatives) {
                $copy=New-PhaseJdkNegativeRoot $context $negative.name
                $path=Assert-PhaseJdkOwned (Join-Path $copy $negative.path) $evidenceRoot
                if ($negative.mode -ceq 'truncated') {
                    $bytes=[IO.File]::ReadAllBytes($path);[IO.File]::WriteAllBytes($path,$bytes[0..([int]($bytes.Length/2))])
                } else {[IO.File]::WriteAllText($path,$negative.text,[Text.UTF8Encoding]::new($false))}
                $inventory=Get-NativePhaseSessionInventory $copy
                $payload=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($copy))
                $receipt=Invoke-PhaseJdkRaw $context $source @('read',$payload,$client) $negative.name
                $stderr=[IO.File]::ReadAllText($receipt.stderr)
                Assert-PhaseJdkFixture ($receipt.exitCode -ne 0 -and $stderr.Contains($negative.error)) ($negative.name+' actual codec rejection')
                Assert-PhaseJdkFixture (Test-ColdInventoryEqual $inventory (Get-NativePhaseSessionInventory $copy)) ($negative.name+' read never repaired seed')
                if ($negative.name -cin @('xml-malformed','xml-truncated')) {
                    $retry=Invoke-PhaseJdkRaw $context $source @('seed',$payload,$client) ($negative.name+'-seed')
                    Assert-PhaseJdkFixture ($retry.exitCode -ne 0 -and [IO.File]::ReadAllText($retry.stderr).Contains('PHASE_SESSION_SEED_EXISTS')) ($negative.name+' cannot overwrite partial seed')
                }
            }
            # Settings parser намеренно tolerant: реальный byte guard, не codec exit, обязан отклонить потерю.
            $copy=New-PhaseJdkNegativeRoot $context 'settings-truncated'
            $settingsPath=Assert-PhaseJdkOwned (Join-Path $copy 'CashMemory/settings.md') $evidenceRoot
            [IO.File]::WriteAllBytes($settingsPath,[byte[]]@())
            $payload=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($copy))
            $receipt=Invoke-PhaseJdkRaw $context $source @('read',$payload,$client) 'settings-truncated'
            Assert-PhaseJdkFixture ($receipt.exitCode -eq 0) 'actual tolerant settings decoder (not strict proof)'
            $badObservation=[pscustomobject]@{stage='jdk-corrupt-settings';startedAt=[datetime]::UtcNow.ToString('o');
                finishedAt=[datetime]::UtcNow.ToString('o');files=(Get-NativePhaseSessionInventory $copy);
                codec=[pscustomobject]@{decoded=(ConvertFrom-ColdReceiptJson ([IO.File]::ReadAllText($receipt.stdout)))};
                ui=$null;physicalRestorationObserved=$false;freshOwnedRevisionObserved=$false}
            foreach ($entryClient in 'fx','swing','web') {
                $entry=$badObservation.codec.decoded.sessions.$entryClient
                $entry | Add-Member revisionSha256 (Get-NativePhaseSessionSha ([Text.Encoding]::UTF8.GetBytes($entry.snapshotJson))) -Force
            }
            $context.observations.Add($badObservation);$decision=Test-NativePhaseSessionObservations $context
            Assert-PhaseJdkFixture ($decision.status -ceq 'FAIL' -and $decision.errors -ccontains 'DOMAIN_CHANGED:CashMemory/settings.md') 'actual truncated settings bytes rejected'
            [void]$context.observations.Remove($badObservation)
        }
    }
    foreach ($path in $sourcePins.Keys) {
        Assert-PhaseJdkFixture ((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -ceq $sourcePins[$path]) ('frozen '+$path)
    }
    Assert-PhaseJdkFixture ((Get-FileHash -LiteralPath $CoreJar).Hash.ToLowerInvariant() -ceq $CoreSha256) 'shared built core unchanged'
    Assert-PhaseJdkFixture ((Get-FileHash -LiteralPath $Java).Hash.ToLowerInvariant() -ceq $JavaSha256) 'JDK pin unchanged'
} catch {$failure=$_.Exception.Message}
$result=[ordered]@{schemaVersion=1;status=$(if ($failure) {'JDK_FIXTURE_FAIL'} else {'JDK_FIXTURES_VALIDATED'});
    startedAt=$started;finishedAt=[datetime]::UtcNow.ToString('o');checks=$script:checks;failure=$failure;
    java=$Java;javaSha256=$JavaSha256;coreJar=$CoreJar;coreSha256=$CoreSha256;collectorSha256=$CollectorSha256;
    sourcePins=$sourcePins;commands=@($script:commands.ToArray());cells=@($cells.ToArray());
    jdkExecuted=$true;nativeExecuted=$false;physicalRestorationObserved=$false;nativeAcceptance='PENDING'}
$resultFile=Join-Path $evidenceRoot 'jdk-fixture-results.json';Write-ColdJson $resultFile $result
[pscustomobject]@{status=$result.status;checks=$script:checks;failure=$failure;evidence=$resultFile;
    evidenceSha256=(Get-FileHash -LiteralPath $resultFile).Hash.ToLowerInvariant();nativeAcceptance='PENDING'} | ConvertTo-Json -Compress
if ($failure) {throw $failure}
