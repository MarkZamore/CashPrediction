<#
.SYNOPSIS
Новые bounded fixtures advanced Ready acceptance: AST/pure guards/mocks, без native PASS.
.DESCRIPTION
Не запускает старые suites, Java, GUI, native, сеть, реестр и не пишет evidence.
Положительные synthetic records проверяют validator, не происхождение native observations.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop';Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'READY_ACCEPTANCE_FIXTURE_POWERSHELL7'}
$source=Join-Path $PSScriptRoot 'NativeUpdateReadyAcceptance.ps1'
$pins=@{}
foreach ($file in 'NativeUpdateReadyAcceptance.ps1','NativeUpdateReadyScenarios.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1') {
    $pins[$file]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash
}
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'READY_ACCEPTANCE_FIXTURE_PARSE'}
foreach ($definition in $ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
Import-NativeReadyAcceptanceDependencies $PSScriptRoot
$script:checks=0

# Каждый отказ обязан иметь ожидаемую причину, не случайный exception.
function Assert-AcceptanceRejected([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    Assert-AcceptanceFixture ($caught -ceq $Code) ('expected='+$Code+' actual='+$caught)
}

# Fixture PASS никогда не назначается Row/native matrix.
function Assert-AcceptanceFixture([bool]$Value,[string]$Code) {
    if (-not $Value) {throw ('READY_ACCEPTANCE_FIXTURE_ASSERT '+$Code)};$script:checks++
}

# Новая независимая копия mock record сохраняет UTC строки и wire integer types.
function Copy-AcceptanceFixture($Value) {return (ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64))}

# Полный typed witness только в памяти; никаких окон, TCP запросов или процессов.
function New-AcceptanceMockUi([string]$Root,[string]$Client,[int]$ProcessNumber,[datetime]$Born,[datetime]$Observed,[string]$Lease,[string[]]$Arguments) {
    $exe=Join-Path $Root (Get-ColdLauncherName $Client)
    $witness=if ($Client -ceq 'web') {[pscustomobject]@{kind='owned-http';port=8080;status=200;owningProcess=$ProcessNumber;bodySha256=('a'*64)}}
        else {[pscustomobject]@{kind='native-window';handle=1;title='CashPrediction - fixture'}}
    return [pscustomobject]@{pid=$ProcessNumber;startedAtTicks=$Born.Ticks;executablePath=$exe;modules=@((Join-Path $Root 'runtime/bin/server/jvm.dll'));
        witness=$witness;lease=[pscustomobject]@{schemaVersion=1;leaseId=$Lease;pid=$ProcessNumber;
            startedAtEpochMillis=([DateTimeOffset]::new($Born)).ToUnixTimeMilliseconds();installationRoot=$Root;client=$Client};
        commandLine=(('"'+$exe+'" ')+(($Arguments | ForEach-Object {'"'+$_+'"'}) -join ' '));args=$Arguments;observedAt=$Observed.ToString('o')}
}

# Контрольная сессия различается только process markers; содержимое модели остаётся неизменным.
function New-AcceptanceMockSession($Ui,[string]$State='running',[string]$Payload='stable') {
    $started=[datetime]::new($Ui.startedAtTicks,[DateTimeKind]::Utc).AddMilliseconds(50).ToString('o')
    if ($Ui.lease.client -ceq 'web') {
        $words=Get-ColdSessionWords
        $text=$words['session.md.title.prefix']+'web)'+"`n"
        foreach ($pair in @(@('state',$State),@('pid',[string]$Ui.pid),@('started',$started),@('schema','1'))) {
            $text+='- '+$words['session.md.key.'+$pair[0]]+': '+$pair[1]+"`n"
        }
        $text+='View: '+$Payload+"`n";$path='CashMemory/web-session.md'
    } else {
        $text='<session schema="1" client="'+$Ui.lease.client+'" state="'+$State+'" pid="'+$Ui.pid+'" startedAt="'+$started+'"><main value="'+$Payload+'"/></session>'
        $path='CashMemory/session-'+$Ui.lease.client+'.xml'
    }
    $bytes=[Text.Encoding]::UTF8.GetBytes($text)
    return [pscustomobject][ordered]@{path=$path;sizeBytes=[long]$bytes.Length;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();
        contentBase64=[Convert]::ToBase64String($bytes);readOnly=$false}
}

# Данные из production receipt schema; mock не выдаёт себя за полученный native результат.
function New-AcceptanceFixture([string]$Scenario='abrupt-ready-restart',[string]$Client='fx') {
    $root='C:\mock-ready-acceptance';$start=[datetime]::new(2026,10,4,12,0,0,[DateTimeKind]::Utc)
    $oldFiles=@([pscustomobject][ordered]@{path='CashPrediction.exe';sizeBytes=1L;sha256=('a'*64);readOnly=$false})
    $newFiles=@([pscustomobject][ordered]@{path='CashPrediction.exe';sizeBytes=2L;sha256=('b'*64);readOnly=$false})
    $base=[pscustomobject]@{releaseNumber=1;commitSha=('1'*40);files=$oldFiles;treeSha256=(Get-ColdTreeHash $oldFiles)}
    $target=[pscustomobject]@{releaseNumber=2;commitSha=('3'*40);files=$newFiles;treeSha256=(Get-ColdTreeHash $newFiles)}
    $args=@('--home',$root);if ($Client -ceq 'web') {$args+=@('--no-browser','--no-window')}
    $initial=New-AcceptanceMockUi $root $Client 123 $start.AddSeconds(1) $start.AddSeconds(2) '11111111-1111-1111-1111-111111111111' $args
    $restart=New-AcceptanceMockUi $root $Client 456 $start.AddSeconds(4.8) $start.AddSeconds(5) '22222222-2222-2222-2222-222222222222' @($args+@('--updated-from',$target.commitSha))
    $exe=Join-Path $root (Get-ColdLauncherName $Client)
    $launcher=[pscustomobject]@{ProcessId=321;StartedAtTicks=$start.AddSeconds(4.1).Ticks;ExecutablePath=$exe;OwnedRoot=$root}
    $powerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $installerBorn=if ($Scenario -ceq 'launch-applying-safe-args') {$start.AddSeconds(3.1)} else {$start.AddSeconds(4.2)}
    $installer=[pscustomobject]@{ProcessId=999;StartedAtTicks=$installerBorn.Ticks;ExecutablePath=$powerShell;OwnedRoot=$root}
    $atReady=@(New-AcceptanceMockSession $initial);$prestart=@(New-AcceptanceMockSession $initial 'closed');$after=@(New-AcceptanceMockSession $restart)
    $samples=[Collections.Generic.List[object]]::new()
    foreach ($pair in @(@(3.8,3.9,($Scenario -ceq 'launch-applying-safe-args')),@(4.25,4.35,$true),@(4.9,4.95,$false))) {
        $samples.Add([pscustomobject]@{startedAt=$start.AddSeconds($pair[0]).ToString('o');finishedAt=$start.AddSeconds($pair[1]).ToString('o');
            journalBefore=[bool]$pair[2];journalAfter=[bool]$pair[2];visibleCount=0;controlledSha256=(Get-ColdObjectHash $prestart)})
    }
    $recovery=[pscustomobject]@{schemaVersion=1;installationRoot=$root;transactionId='33333333-3333-3333-3333-333333333333';
        pollingLimitMillis=1000;controlledSha256=(Get-ColdObjectHash $prestart);samples=$samples.ToArray()}
    $exit=if ($Scenario -ceq 'abrupt-ready-restart') {[pscustomobject]@{identity=(Get-NativeReadyAcceptanceUiIdentity $initial $root);exitCode=-1;kind='abrupt-retained-client';exitedUtc=$start.AddSeconds(3).ToString('o')}}
        else {[pscustomobject]@{pid=$initial.pid;startedAtTicks=$initial.startedAtTicks;exitCode=0;remainingClients=0;kind='ordinary-no-restart';exitedUtc=$start.AddSeconds(3).ToString('o')}}
    $receipt=[pscustomobject]@{scenario=$Scenario;ready=[pscustomobject]@{observedUtc=$start.AddSeconds(2.2).ToString('o');manifest=$target;tree=$newFiles;ui=$initial};
        clientExit=$exit;launcher=$launcher;originalArgs=$args;launchAt=$start.AddSeconds(4).ToString('o');ui=$restart;recovery=$recovery;version=$target;verify=@{};
        launcherExit=0;installer=$installer;installerExit=0;lastInstall=[pscustomobject]@{outcome='UPDATED';targetCommitSha=$target.commitSha;transactionId=$recovery.transactionId};
        server=@{};serverIdentity=@{};observedUtc=$start.AddSeconds(5.5).ToString('o')}
    $user=[ordered]@{}
    foreach ($path in 'CashMemory/settings.md','CashMemory/NativeLifecycle.md') {$user[$path]=[pscustomobject]@{path=$path;directory=$false;sizeBytes=1L;sha256=('c'*64);readOnly=$false}}
    $helper=Join-Path $root 'CashMemory/Updates/apply-update.ps1';$command="& '"+$helper.Replace("'","''")+"' -InstallationRoot '"+$root.Replace("'","''")+"'"
    $independent=[pscustomobject]@{schemaVersion=1;scenario=$Scenario;client=$Client;installationRoot=$root;readyObservedAt=$start.AddSeconds(2.1).ToString('o');
        initialClient=(Copy-AcceptanceFixture $initial);launcherIdentity=(Copy-AcceptanceFixture $launcher);launcherArguments=$args;
        launcherObservedAt=$start.AddSeconds(4.15).ToString('o');restartedClient=(Copy-AcceptanceFixture $restart);installerIdentity=(Copy-AcceptanceFixture $installer);
        installerCommandLine=('"'+$powerShell+'" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand '+[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command)));
        installerObservedAt=$installerBorn.AddMilliseconds(10).ToString('o');readyTree=$newFiles;currentBefore=$oldFiles;currentAfter=$newFiles;targetBefore=$newFiles;targetAfter=$newFiles;
        userBefore=$user;userAfter=(Copy-AcceptanceFixture $user);controlledBefore=@();controlledAfter=$after;controlledAtReady=$atReady;controlledAtPrestart=$prestart;
        recovery=(Copy-AcceptanceFixture $recovery);journalsCleared=$true;cleanup=[pscustomobject]@{finishedAt=$start.AddSeconds(7).ToString('o');errors=@();remainingClients=0;remainingHelpers=0;registryUnchanged=$true};
        phaseLog=@('SESSION','COMMITTED')}
    $row=[pscustomobject]@{scenario=$Scenario;client=$Client;phase='SESSION';status='PENDING';executed=$true;workRoot=$root;exe=$exe;args=$args;
        baseCommit=$base.commitSha;targetCommit=$target.commitSha;baseRelease=1;targetRelease=2;startedAt=$start.ToString('o');finishedAt=$start.AddSeconds(6).ToString('o');
        exitCode=0;skipped=0;failures=0;evidenceDirectory='C:\mock-evidence';command='C:\mock-evidence\ready-recovery-observed.json'}
    return [pscustomobject]@{Row=$row;Receipt=$receipt;Base=$base;Target=$target;Independent=$independent}
}

# Любой неожиданный внешний вызов запрещён; acceptance функций это не требуется.
function Start-Process {throw 'READY_ACCEPTANCE_FIXTURE_FORBIDDEN_PROCESS'}
function Get-CimInstance {throw 'READY_ACCEPTANCE_FIXTURE_FORBIDDEN_NATIVE'}
function Get-NetTCPConnection {throw 'READY_ACCEPTANCE_FIXTURE_FORBIDDEN_NATIVE'}
function Invoke-WebRequest {throw 'READY_ACCEPTANCE_FIXTURE_FORBIDDEN_NETWORK'}
function Write-ColdJson {throw 'READY_ACCEPTANCE_FIXTURE_FORBIDDEN_WRITE'}

foreach ($scenario in 'abrupt-ready-restart','launch-applying-safe-args') {foreach ($client in 'fx','swing','web') {
    $f=New-AcceptanceFixture $scenario $client
    Assert-NativeReadyAcceptanceFacts $f.Row $f.Receipt $f.Base $f.Target $f.Independent
    Assert-AcceptanceFixture ($f.Row.status -ceq 'PENDING') 'MOCK_FACTS_NEVER_NATIVE_PASS'
}}
# Сравнивается настоящий frozen return schema, а не самостоятельно придуманная receipt template.
$frozenAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'NativeUpdateReadyScenarios.ps1'),[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw 'READY_ACCEPTANCE_FIXTURE_FROZEN_PARSE'}
$step=@($frozenAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativeReadyContextScenario'},$true))[0]
$returned=@($step.FindAll({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -ceq '$receipt'},$true))
Assert-AcceptanceFixture ($returned.Count -eq 1) 'FROZEN_ACTUAL_RETURN_ASSIGNMENT'
$table=$returned[0].Find({param($n) $n -is [Management.Automation.Language.HashtableAst]},$true)
$actualKeys=@($table.KeyValuePairs | ForEach-Object {$_.Item1.Extent.Text} | Sort-Object)
$f=New-AcceptanceFixture
Assert-AcceptanceFixture (($actualKeys -join '|') -ceq (($f.Receipt.PSObject.Properties.Name | Sort-Object) -join '|')) 'FROZEN_RECEIPT_SCHEMA_MATCHES_ACCEPTANCE'
foreach ($property in 'initialClient','launcherIdentity','restartedClient','readyTree','userBefore','controlledAtReady','recovery','cleanup') {
    $f=New-AcceptanceFixture;$f.Independent.PSObject.Properties.Remove($property)
    Assert-AcceptanceRejected {Assert-NativeReadyAcceptanceFacts $f.Row $f.Receipt $f.Base $f.Target $f.Independent} 'COLD_FIELDS'
}
$f=New-AcceptanceFixture
foreach ($ui in @($f.Receipt.ui,$f.Independent.restartedClient)) {$ui.pid=123;$ui.lease.pid=123}
$f.Independent.controlledAfter=@(New-AcceptanceMockSession $f.Independent.restartedClient)
Assert-NativeReadyAcceptanceFacts $f.Row $f.Receipt $f.Base $f.Target $f.Independent
Assert-AcceptanceFixture ($f.Row.status -ceq 'PENDING') 'REUSED_PID_FRESH_BIRTH_NOT_STALE'
# Cleanup может закончиться до записи finishedAt строки; требуется только after actual UI observation.
$f=New-AcceptanceFixture
$f.Independent.cleanup.finishedAt='2026-10-04T12:00:05.8Z'
Assert-NativeReadyAcceptanceFacts $f.Row $f.Receipt $f.Base $f.Target $f.Independent
Assert-AcceptanceFixture ($f.Row.status -ceq 'PENDING') 'CLEANUP_BEFORE_ROW_FINISHED_VALID'
$f=New-AcceptanceFixture
Assert-AcceptanceRejected {Assert-NativeReadyAcceptanceFacts $f.Row $f.Receipt $f.Base $f.Target $null} 'READY_ACCEPTANCE_INDEPENDENT_REQUIRED'
$cases=@(
    @{code='READY_ACCEPTANCE_CONTEXT';change={param($f) $f.Independent.client='web'}},
    @{code='READY_ACCEPTANCE_CONTEXT';change={param($f) $f.Independent.installationRoot='C:\other'}},
    @{code='READY_ACCEPTANCE_CONTEXT';change={param($f) $f.Row.executed=$false}},
    @{code='READY_ACCEPTANCE_CONTEXT';change={param($f) $f.Row.status='PASS'}},
    @{code='COLD_PROCESS_IDENTITY';change={param($f) $f.Independent.launcherIdentity.StartedAtTicks++}},
    @{code='COLD_PROCESS_IDENTITY';change={param($f) $f.Independent.launcherIdentity.ProcessId++}},
    @{code='COLD_PROCESS_IDENTITY';change={param($f) $f.Independent.installerIdentity.ProcessId++}},
    @{code='COLD_REPORT_LAUNCH_IDENTITY';change={param($f) $f.Independent.restartedClient.lease.client='swing'}},
    @{code='COLD_REPORT_LAUNCH_IDENTITY';change={param($f) $f.Independent.restartedClient.args[-1]='4'*40}},
    @{code='READY_ACCEPTANCE_RESTART_BIRTH';change={param($f) $f.Independent.restartedClient.lease.leaseId=$f.Independent.initialClient.lease.leaseId}},
    @{code='READY_ACCEPTANCE_LAUNCHER_COMMAND';change={param($f) $f.Independent.launcherArguments+=@('--test-api')}},
    @{code='READY_ACCEPTANCE_TIME';change={param($f) $f.Independent.readyObservedAt='2026-10-04T12:00:08Z'}},
    @{code='READY_ACCEPTANCE_TARGET';change={param($f) $f.Receipt.lastInstall.outcome='ROLLED_BACK'}},
    @{code='READY_ACCEPTANCE_TARGET';change={param($f) $f.Receipt.lastInstall.transactionId='44444444-4444-4444-4444-444444444444'}},
    @{code='COLD_INVENTORY';change={param($f) $f.Independent.currentAfter=@()}},
    @{code='COLD_INVENTORY';change={param($f) $f.Independent.readyTree[0].sha256='d'*64}},
    @{code='READY_ACCEPTANCE_EXIT';change={param($f) $f.Receipt.launcherExit=1}},
    @{code='READY_ACCEPTANCE_EXIT';change={param($f) $f.Receipt.installerExit='0'}},
    @{code='READY_ACCEPTANCE_CRASH';change={param($f) $f.Receipt.clientExit.exitCode=0}},
    @{code='READY_ACCEPTANCE_USER_CHANGED';change={param($f) $f.Independent.userAfter.'CashMemory/settings.md'.sha256='e'*64}},
    @{code='READY_ACCEPTANCE_USER_INVENTORY';change={param($f) $f.Independent.userBefore=[pscustomobject]@{};$f.Independent.userAfter=[pscustomobject]@{}}},
    @{code='READY_ACCEPTANCE_USER_INVENTORY';change={param($f) $f.Independent.userBefore.'CashMemory/settings.md'.sizeBytes=0;$f.Independent.userAfter.'CashMemory/settings.md'.sizeBytes=0}},
    @{code='READY_ACCEPTANCE_SESSION_REQUIRED';change={param($f) $f.Independent.controlledAfter=@()}},
    @{code='READY_ACCEPTANCE_SESSION_CHANGED';change={param($f) $f.Independent.controlledAfter=@(New-AcceptanceMockSession $f.Independent.restartedClient 'running' 'changed')}},
    @{code='COLD_REPORT_RECOVERY_EVIDENCE';change={param($f) $f.Independent.recovery.samples[1].visibleCount=1}},
    @{code='COLD_REPORT_RECOVERY_EVIDENCE';change={param($f) $f.Independent.recovery.pollingLimitMillis=2000}},
    @{code='READY_ACCEPTANCE_CLEANUP';change={param($f) $f.Independent.cleanup.errors=@('cleanup-error')}},
    @{code='READY_ACCEPTANCE_CLEANUP';change={param($f) $f.Independent.cleanup.remainingClients=1}},
    @{code='READY_ACCEPTANCE_CLEANUP';change={param($f) $f.Independent.journalsCleared=$false}},
    @{code='READY_ACCEPTANCE_CLEANUP';change={param($f) $f.Independent.cleanup.registryUnchanged=$false}}
)
foreach ($case in $cases) {
    $f=New-AcceptanceFixture;& $case.change $f
    Assert-AcceptanceRejected {Assert-NativeReadyAcceptanceFacts $f.Row $f.Receipt $f.Base $f.Target $f.Independent} $case.code
}
$f=New-AcceptanceFixture 'launch-applying-safe-args'
$f.Independent.installerObservedAt='2026-10-04T12:00:04.5Z'
Assert-AcceptanceRejected {Assert-NativeReadyAcceptanceFacts $f.Row $f.Receipt $f.Base $f.Target $f.Independent} 'READY_ACCEPTANCE_APPLYING_NOT_LIVE'

# Реальный byte reader отвергает relative path и неверный pin; reads только собственный source.
Assert-AcceptanceRejected {Read-NativeReadyAcceptanceJson 'relative.json' ('a'*64)} 'READY_ACCEPTANCE_PIN'
Assert-AcceptanceRejected {Read-NativeReadyAcceptanceJson $source ('a'*64)} 'READY_ACCEPTANCE_PIN'

# MAIN интерфейс выполняет настоящий fact guard; только file readers заменены mocks без записи.
& {
    $script:apiFixture=New-AcceptanceFixture
    function Read-NativeReadyAcceptanceJson($Path,$Sha256) {
        if ($Path -ceq $script:apiFixture.Row.command) {return $script:apiFixture.Receipt};return $script:apiFixture.Independent
    }
    function Test-PortablePathContains($Parent,$Child) {return $Child.StartsWith($Parent+'\',[StringComparison]::OrdinalIgnoreCase)}
    function Assert-ColdTree {return @()}
    $script:apiCurrentUserChanged=$false
    function Get-ColdUserInventory {
        $entries=Copy-AcceptanceFixture $script:apiFixture.Independent.userAfter
        if ($script:apiCurrentUserChanged) {$entries.'CashMemory/settings.md'.sha256='d'*64}
        return $entries.PSObject.Properties.Value
    }
    $f=$script:apiFixture
    $decision=Test-NativeReadyAcceptance $f.Row $f.Receipt $f.Base $f.Target ('a'*64) 'C:\mock-evidence\main-independent.json' ('b'*64)
    Assert-AcceptanceFixture ($decision.cellEvidenceValidated -and $decision.status -ceq 'PENDING' -and $f.Row.status -ceq 'PENDING') 'API_DOES_NOT_PROMOTE_MOCK_OR_MATRIX'
    Assert-AcceptanceRejected {Test-NativeReadyAcceptance $f.Row $f.Receipt $f.Base $f.Target ('a'*64) '' ''} 'READY_ACCEPTANCE_INDEPENDENT_REQUIRED'
    Assert-AcceptanceRejected {Test-NativeReadyAcceptance $f.Row $f.Receipt $f.Base $f.Target ('a'*64) $f.Row.command ('b'*64)} 'READY_ACCEPTANCE_ARTIFACT_SCOPE'
    Assert-AcceptanceRejected {Test-NativeReadyAcceptance $f.Row $f.Receipt $f.Base $f.Target ('a'*64) 'C:\foreign\proof.json' ('b'*64)} 'READY_ACCEPTANCE_ARTIFACT_SCOPE'
    $script:apiCurrentUserChanged=$true
    Assert-AcceptanceRejected {Test-NativeReadyAcceptance $f.Row $f.Receipt $f.Base $f.Target ('a'*64) 'C:\mock-evidence\main-independent.json' ('b'*64)} 'READY_ACCEPTANCE_CURRENT_USER_CHANGED'
    $script:apiCurrentUserChanged=$false
    $otherReceipt=Copy-AcceptanceFixture $f.Receipt;$otherReceipt.launcherExit=99
    Assert-AcceptanceRejected {Test-NativeReadyAcceptance $f.Row $otherReceipt $f.Base $f.Target ('a'*64) 'C:\mock-evidence\main-independent.json' ('b'*64)} 'READY_ACCEPTANCE_RECEIPT_MISMATCH'
}
Assert-AcceptanceFixture (@($ast.EndBlock.Statements | Where-Object {$_ -isnot [Management.Automation.Language.FunctionDefinitionAst]}).Count -eq 0) 'NO_AUTOMATIC_EXECUTION'
foreach ($file in $pins.Keys) {if ((Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $file)).Hash -cne $pins[$file]) {throw 'READY_ACCEPTANCE_FIXTURE_SOURCE_CHANGED'}}
Write-Host ('Ready acceptance fixtures PASS: '+$script:checks+' checks; pure guards/mocks only; nativeExecuted=false; no native PASS')
Write-Host ('Acceptance SHA256: '+$pins['NativeUpdateReadyAcceptance.ps1'].ToLowerInvariant())
Write-Host ('Fixture SHA256: '+(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant())
