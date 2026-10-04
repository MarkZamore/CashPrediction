<#
.SYNOPSIS
Только unit/mock контракты NEXT multi-client helper: порядок и отказы, не native PASS.
.DESCRIPTION
AST импорт определений без тела frozen runner. Никаких Java, exe, GUI, сети,
реестра, native evidence или изменения canonical plan. Старые fixtures не повторяются.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'CONCURRENT_FIXTURE_POWERSHELL7'}

# Импортируются определения, но никогда не исполняется основной код lifecycle runner.
function Import-ConcurrentFixtureFunctions([string]$Path) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw ('CONCURRENT_FIXTURE_PARSE '+($errors.Message -join '; '))}
    foreach ($definition in @($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))) {
        $text=$definition.Extent.Text -replace '^function ','function script:'
        . ([scriptblock]::Create($text))
    }
    return $ast
}
$null=Import-ConcurrentFixtureFunctions (Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1')
Import-NativeDependencies $PSScriptRoot
$helperAst=Import-ConcurrentFixtureFunctions (Join-Path $PSScriptRoot 'NativeUpdateConcurrentScenarios.ps1')
$script:checks=0;$script:calls=[Collections.Generic.List[string]]::new()

# Проверяется точный код отказа, чтобы ранняя случайная ошибка не скрыла сломанный guard.
function Assert-ConcurrentReject([scriptblock]$Action,[string]$Code) {
    $caught=$null;try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "CONCURRENT_FIXTURE_REJECTION expected=$Code actual=$caught"}
    $script:checks++
}

# Mock положительный control подтверждает только контракт harness, не исполнение exe.
function Assert-ConcurrentMock([bool]$Condition,[string]$Code) {
    if (-not $Condition) {throw "CONCURRENT_FIXTURE_ASSERT $Code"};$script:checks++
}

# Все внешние операции запрещены; узкие mocks ниже возвращают только данные в памяти.
function Start-NativeOwned {throw 'CONCURRENT_FIXTURE_FORBIDDEN_PROCESS'}
function Start-NativeFixture {throw 'CONCURRENT_FIXTURE_FORBIDDEN_JAVA'}
function Open-PortableProcess {throw 'CONCURRENT_FIXTURE_FORBIDDEN_PROCESS'}
function Get-CopyProcesses {throw 'CONCURRENT_FIXTURE_FORBIDDEN_CIM'}
function Stop-ColdCopyProcesses {throw 'CONCURRENT_FIXTURE_FORBIDDEN_KILL'}
function Stop-ColdRetainedProcess {throw 'CONCURRENT_FIXTURE_FORBIDDEN_KILL'}
function Invoke-RestMethod {throw 'CONCURRENT_FIXTURE_FORBIDDEN_NETWORK'}
function Remove-Item {throw 'CONCURRENT_FIXTURE_FORBIDDEN_DELETE'}
function Start-Sleep {throw 'CONCURRENT_FIXTURE_FORBIDDEN_SLEEP'}

# Синтетические квитанции корректны только как вход pure guards; файлов они не создают.
function New-ConcurrentMockEntry([int]$Number,[string]$Client='fx',[string]$Root='C:\native-fixture\A') {
    $birth=[datetime]::UtcNow.AddSeconds(-10);$ticks=$birth.Ticks;$id=5000+$Number
    $node='ru/cashprediction/selftest/11111111-1111-1111-1111-111111111111'
    $arguments=@(Get-NativeConcurrentArguments $Root $Client $node ($Number -gt 1))
    $exe=Join-Path $Root (Get-ColdLauncherName $Client)
    $lease=[pscustomobject]@{schemaVersion=1;leaseId=('00000000-0000-0000-0000-'+$Number.ToString('000000000000'));
        pid=$id;startedAtEpochMillis=([DateTimeOffset]$birth).ToUnixTimeMilliseconds();installationRoot=$Root;client=$Client}
    $witness=if ($Client -ceq 'web') {[pscustomobject]@{kind='owned-http';port=19000+$Number;status=200;bodySha256=('a'*64);owningProcess=$id}} else {
        [pscustomobject]@{kind='native-window';handle=7000+$Number;title='CashPrediction - NativeLifecycle'}}
    $ui=[pscustomobject]@{pid=$id;startedAtTicks=$ticks;executablePath=$exe;modules=@((Join-Path $Root 'runtime/bin/server/jvm.dll'));
        witness=$witness;lease=$lease;commandLine=('"'+$exe+'" '+(($arguments | ForEach-Object {'"'+$_+'"'}) -join ' '));args=$arguments;observedAt=[datetime]::UtcNow.ToString('o')}
    $process=[pscustomobject]@{HasExited=$false;ExitCode=0;ExitTime=[datetime]::UtcNow;ui=$ui;root=$Root}
    $process | Add-Member ScriptMethod CloseMainWindow {
        $script:calls.Add('physical-close')
        if ($script:rejectClose) {return $false}
        if ($script:loseSurvivor) {$script:mockEntries[1].native.uiProcess.HasExited=$true}
        if ($script:exitOnClose) {$this.HasExited=$true};return $true
    }
    return [pscustomobject]@{root=$Root;client=$Client;node=$node;args=$arguments;
        native=[pscustomobject]@{ui=$ui;uiProcess=$process;web=($Client -ceq 'web');webUrl=$null}}
}

# Наблюдение process/lease задаётся явно; реальные PID, CIM и диск недоступны тесту.
function Get-ColdProcessReceipt($Process,[string]$Root) {
    $script:calls.Add('identity-'+$Process.ui.pid)
    return [pscustomobject]@{ProcessId=$Process.ui.pid;StartedAtTicks=($Process.ui.startedAtTicks+$script:birthOffset);
        ExecutablePath=$Process.ui.executablePath;OwnedRoot=$Root}
}
function Get-Content([string]$LiteralPath,[switch]$Raw,[string]$Encoding) {
    if ($LiteralPath.EndsWith('update-log.md')) {return $script:mockLog}
    $script:calls.Add('lease')
    foreach ($entry in $script:mockEntries) {
        if ($LiteralPath -ceq (Join-Path $entry.root ('CashMemory/Updates/processes/'+$entry.native.ui.lease.leaseId+'.json'))) {
            $lease=ConvertFrom-ColdReceiptJson (ConvertTo-Json $entry.native.ui.lease -Depth 16)
            if ($script:badLease) {$lease.installationRoot='C:\foreign'}
            return (ConvertTo-Json $lease -Depth 16)
        }
    }
    throw 'CONCURRENT_FIXTURE_UNKNOWN_READ'
}
function Get-NativeMainWindow($Native) {
    $script:calls.Add('window')
    return [pscustomobject]@{handle=$Native.ui.witness.handle;pid=$Native.ui.pid;exists=$true;visible=$true;
        enabled=(-not $script:disabled);processMainHandle=$Native.ui.witness.handle}
}
function Wait-NativeCondition([scriptblock]$Condition,[int]$Seconds,[string]$Code) {
    $script:calls.Add('wait');if (-not (& $Condition)) {throw $Code}
}
function Write-ColdJson([string]$Path,$Value) {$script:calls.Add('receipt');$script:lastReceipt=$Value}
function Assert-ColdTree([string]$Root,$Base) {$script:calls.Add('tree');if ($script:badTree) {throw 'COLD_MANAGED_TREE'}}
function Test-Path([string]$LiteralPath) {
    if ($LiteralPath.EndsWith('update-log.md')) {return @($script:mockLog).Count -gt 0}
    if ($LiteralPath.EndsWith('last-install.json')) {return $script:earlyInstall}
    # Safety resolver получает несуществующий synthetic корень; файловая операция не исполняется.
    return $false
}
$script:birthOffset=0L;$script:badLease=$false;$script:disabled=$false;$script:rejectClose=$false
$script:exitOnClose=$true;$script:loseSurvivor=$false
$script:badTree=$false;$script:earlyInstall=$false;$script:mockLog=@();$script:lastReceipt=$null
$row=[pscustomobject]@{scenario='two-clients';client='fx';path='unicode';phase='SESSION';status='PENDING';reason=''}
foreach ($client in 'fx','swing','web') {
    $row.client=$client;$list=@(Get-NativeConcurrentClients $row 30)
    Assert-ConcurrentMock ($list.Count -eq 2 -and $list[0] -ceq $client -and $list[1] -cne $client) 'TWO_REAL_CLIENT_SELECTION'
    $row.scenario='three-clients-pid-root-isolation';$list=@(Get-NativeConcurrentClients $row 300)
    Assert-ConcurrentMock ($list.Count -eq 3 -and @($list | Sort-Object -Unique).Count -eq 3 -and $list[0] -ceq $client) 'THREE_CLIENT_SELECTION'
    $row.scenario='two-clients'
}
foreach ($timeout in 0,29,301) {Assert-ConcurrentReject {Get-NativeConcurrentClients $row $timeout} 'CONCURRENT_ROW'}
$row.scenario='delta';Assert-ConcurrentReject {Get-NativeConcurrentClients $row 180} 'CONCURRENT_ROW';$row.scenario='two-clients'
$row.client='FX';Assert-ConcurrentReject {Get-NativeConcurrentClients $row 180} 'CONCURRENT_CLIENT';$row.client='fx'
$first=New-ConcurrentMockEntry 1;$second=New-ConcurrentMockEntry 2 'swing'
$script:mockEntries=@($first,$second)
Assert-ConcurrentMock (@($second.args | Select-Object -Last 2) -join ' ' -ceq '--selftest-recovery already-ok') 'REAL_STARTUP_OPTION'
[void](Assert-NativeConcurrentAlive $first);$script:checks++
Assert-NativeConcurrentDistinct $script:mockEntries;$script:checks++
Assert-ConcurrentReject {Assert-NativeConcurrentDistinct @($first,$first)} 'CONCURRENT_DUPLICATE_IDENTITY'
$script:birthOffset=1L;Assert-ConcurrentReject {Assert-NativeConcurrentAlive $first} 'COLD_PROCESS_IDENTITY';$script:birthOffset=0L
$script:badLease=$true;Assert-ConcurrentReject {Assert-NativeConcurrentAlive $first} 'CONCURRENT_LEASE_CHANGED';$script:badLease=$false
$first.native.uiProcess.HasExited=$true;Assert-ConcurrentReject {Assert-NativeConcurrentAlive $first} 'CONCURRENT_CLIENT_EXITED';$first.native.uiProcess.HasExited=$false
$script:disabled=$true;Assert-ConcurrentReject {Assert-NativeConcurrentAlive $first} 'NATIVE_NORMAL_CLOSE_OWNER_DISABLED';$script:disabled=$false
Assert-ConcurrentReject {Observe-NativeConcurrentBarrier $first.root @{} @() 'unused'} 'CONCURRENT_NO_SURVIVOR'
$foreign=New-ConcurrentMockEntry 3 'web' 'C:\native-fixture\B'
Assert-ConcurrentReject {Observe-NativeConcurrentBarrier $first.root @{} @($foreign) 'unused'} 'CONCURRENT_FOREIGN_SURVIVOR'
Assert-NativeConcurrentOldTree $first.root @{};$script:checks++
foreach ($phase in 'BOOTSTRAPPING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK') {
    $script:mockLog=@('- 2026-10-04T00:00:00Z PHASE_'+$phase)
    Assert-ConcurrentReject {Assert-NativeConcurrentOldTree $first.root @{}} 'CONCURRENT_REPLACEMENT_WHILE_ALIVE'
}
$script:mockLog=@('- 2026-10-04T00:00:00Z PHASE_WAITING');Assert-NativeConcurrentOldTree $first.root @{};$script:checks++
$script:earlyInstall=$true;Assert-ConcurrentReject {Assert-NativeConcurrentOldTree $first.root @{}} 'CONCURRENT_EARLY_INSTALL';$script:earlyInstall=$false
$script:badTree=$true;Assert-ConcurrentReject {Assert-NativeConcurrentOldTree $first.root @{}} 'COLD_MANAGED_TREE';$script:badTree=$false
Assert-ConcurrentReject {Close-NativeConcurrentMember $first @() 30 'unused'} 'CONCURRENT_USE_FINAL_CLOSE'
Assert-ConcurrentReject {Close-NativeConcurrentMember $first @($first) 30 'unused'} 'CONCURRENT_CLOSE_SURVIVOR'
Assert-ConcurrentReject {Close-NativeConcurrentMember $first @($foreign) 30 'unused'} 'CONCURRENT_CLOSE_SURVIVOR'
$script:rejectClose=$true;Assert-ConcurrentReject {Close-NativeConcurrentMember $first @($second) 30 'unused'} 'NATIVE_NORMAL_CLOSE_REJECTED';$script:rejectClose=$false
$script:calls.Clear();$receipt=Close-NativeConcurrentMember $first @($second) 30 'unused'
Assert-ConcurrentMock ($receipt.kind -ceq 'ordinary-intermediate-no-restart' -and $receipt.remainingUi -eq 1 -and $receipt.survivors[0].pid -eq $second.native.ui.pid) 'INTERMEDIATE_NOT_FINAL_RECEIPT'
Assert-ConcurrentMock ($script:calls.IndexOf('physical-close') -lt $script:calls.IndexOf('wait') -and $script:calls.IndexOf('wait') -lt $script:calls.LastIndexOf('receipt')) 'ORDINARY_CLOSE_ORDER'
Assert-ConcurrentMock (-not $second.native.uiProcess.HasExited) 'SURVIVOR_NOT_CLOSED'
$first.native.uiProcess.HasExited=$false;$first.native.uiProcess.ExitCode=7
Assert-ConcurrentReject {Close-NativeConcurrentMember $first @($second) 30 'unused'} 'NATIVE_NORMAL_EXIT_CODE'
$first.native.uiProcess.HasExited=$false;$first.native.uiProcess.ExitCode=0;$script:exitOnClose=$false
Assert-ConcurrentReject {Close-NativeConcurrentMember $first @($second) 30 'unused'} 'CONCURRENT_NORMAL_CLOSE_TIMEOUT'
$script:exitOnClose=$true;$script:loseSurvivor=$true
Assert-ConcurrentReject {Close-NativeConcurrentMember $first @($second) 30 'unused'} 'CONCURRENT_CLIENT_EXITED'
$script:loseSurvivor=$false;$second.native.uiProcess.HasExited=$false

# Разделение controlled baseline не разрешает изменения клиента, которого не запускали.
$script:controlledCalls=[Collections.Generic.List[object]]::new()
function Assert-ColdControlledChanges($Before,$After,[string]$Client,$Ui,[string]$Root,[long]$Finished) {
    $script:controlledCalls.Add([pscustomobject]@{before=@($Before);after=@($After);client=$Client;pid=$Ui.pid;root=$Root})
}
$old=@([pscustomobject]@{path='CashMemory/session-fx.xml';sha256='old'},[pscustomobject]@{path='CashMemory/session-swing.xml';sha256='same'})
$new=@([pscustomobject]@{path='CashMemory/session-fx.xml';sha256='new'},[pscustomobject]@{path='CashMemory/session-swing.xml';sha256='same'})
Assert-NativeConcurrentControlled $old $new @($first,$second) $first.root
Assert-ConcurrentMock ($script:controlledCalls.Count -eq 2 -and $script:controlledCalls[0].client -ceq 'fx' -and
    $script:controlledCalls[1].client -ceq 'swing' -and $script:controlledCalls[0].after[0].path -ceq 'CashMemory/session-fx.xml' -and
    $script:controlledCalls[1].after[0].path -ceq 'CashMemory/session-swing.xml') 'CONTROLLED_PATH_ROUTING'
Assert-ConcurrentReject {Assert-NativeConcurrentControlled @() @([pscustomobject]@{path='CashMemory/unknown.md'}) @($first,$second) $first.root} 'CONCURRENT_CONTROLLED_PATH'
Assert-ConcurrentReject {Assert-NativeConcurrentControlled @() @([pscustomobject]@{path='CashMemory/web-reconnect.md';sha256='changed'}) @($first,$second) $first.root} 'CONCURRENT_ABSENT_CLIENT_CHANGED'

# Раздельный root проверяется receipt guard, а не просто строковым сравнением выбранных exe.
$script:mockEntries+=@($foreign)
[void](Assert-NativeConcurrentAlive $foreign);$script:checks++
$wrong=New-ConcurrentMockEntry 4 'fx'
$wrong.root='C:\native-fixture\B'
Assert-ConcurrentReject {Assert-NativeConcurrentAlive $wrong} 'COLD_REPORT_LAUNCH_IDENTITY'

# Новый threat fixture имеет ровно production wire поля, но не выдаётся за kernel PID reuse.
$stale=New-NativeConcurrentStaleLease $first.root $foreign
$stalePath=Join-Path $first.root ('CashMemory/Updates/processes/'+$stale.leaseId+'.json')
Assert-NativeConcurrentStaleLease $stale $first.root $foreign $stalePath;$script:checks++
Assert-ConcurrentMock ($stale.pid -is [long] -and $stale.startedAtEpochMillis -is [long] -and $stale.schemaVersion -is [int] -and
    $stale.pid -eq $foreign.native.ui.pid -and $stale.startedAtEpochMillis -eq $foreign.native.ui.lease.startedAtEpochMillis-10000 -and
    $stale.installationRoot -ceq $first.root -and $stale.leaseId -cne $foreign.native.ui.lease.leaseId) 'STALE_CANONICAL_TYPED_WIRE'
Assert-ConcurrentReject {New-NativeConcurrentStaleLease $foreign.root $foreign} 'CONCURRENT_STALE_LEASE_ROOT'
Assert-ConcurrentReject {Assert-NativeConcurrentStaleLease $stale $first.root $foreign ($stalePath+'.other')} 'CONCURRENT_STALE_LEASE_WIRE'
foreach ($field in 'pid','startedAtEpochMillis','schemaVersion') {
    foreach ($bad in @($true,'1',0,[uint32]5003,([long][int]::MaxValue+1))) {
        $broken=ConvertFrom-ColdReceiptJson (ConvertTo-Json $stale -Depth 16);$broken.$field=$bad
        Assert-ConcurrentReject {Assert-NativeConcurrentStaleLease $broken $first.root $foreign $stalePath} 'CONCURRENT_STALE_LEASE_WIRE'
    }
}
$broken=ConvertFrom-ColdReceiptJson (ConvertTo-Json $stale -Depth 16)
$broken.startedAtEpochMillis=$foreign.native.ui.lease.startedAtEpochMillis
Assert-ConcurrentReject {Assert-NativeConcurrentStaleLease $broken $first.root $foreign $stalePath} 'CONCURRENT_STALE_LEASE_WIRE'
$broken=ConvertFrom-ColdReceiptJson (ConvertTo-Json $stale -Depth 16);$broken.installationRoot=$foreign.root
Assert-ConcurrentReject {Assert-NativeConcurrentStaleLease $broken $first.root $foreign $stalePath} 'CONCURRENT_STALE_LEASE_WIRE'
$broken=ConvertFrom-ColdReceiptJson (ConvertTo-Json $stale -Depth 16);$broken.client='web-other'
Assert-ConcurrentReject {Assert-NativeConcurrentStaleLease $broken $first.root $foreign $stalePath} 'CONCURRENT_STALE_LEASE_WIRE'

# Только in-memory ordering/errors: production helper не подменяется и не запускается fixtures.
& {
    $script:presentStale=$true;$script:presentLast=$true;$script:presentJournal=$false;$script:restart=$false
    $script:lastOutcome='UPDATED';$script:targetSha='b'*40;$script:lastObservedTimeout=0
    function Get-CopyProcesses {if ($script:restart) {return @([pscustomobject]@{ProcessId=123})};return @()}
    function Test-Path([string]$LiteralPath) {
        if ($LiteralPath.EndsWith('last-install.json')) {return $script:presentLast}
        if ($LiteralPath -ceq $stalePath) {return $script:presentStale}
        if ($LiteralPath.EndsWith('install-journal.json')) {return $script:presentJournal}
        return $false
    }
    function Get-Content([string]$LiteralPath,[switch]$Raw,[string]$Encoding) {
        if ($LiteralPath.EndsWith('last-install.json')) {return (ConvertTo-Json @{outcome=$script:lastOutcome;targetCommitSha=$script:targetSha})}
        if ($LiteralPath.EndsWith('install-journal.json')) {return '{"phase":"WAITING"}'}
        return (ConvertTo-Json $foreign.native.ui.lease -Depth 16)
    }
    function Get-FileHash {return [pscustomobject]@{Hash=('c'*64)}}
    function Copy-Item {$script:calls.Add('save-production-helper')}
    function Wait-NativeCondition([scriptblock]$Condition,[int]$Seconds,[string]$Code) {
        $script:lastObservedTimeout=$Seconds;$script:calls.Add('bounded-observer')
        if (-not (& $Condition)) {throw $Code}
    }
    function Assert-NativeConcurrentOldTree {$script:calls.Add('independent-old-tree')}
    function Wait-NativeInstalled {$script:calls.Add('frozen-target-verification')}
    $injection=[pscustomobject]@{lease=$stale;leasePath=$stalePath;evidence='memory-injection'}
    $target=[pscustomobject]@{commitSha=$script:targetSha}
    Assert-ConcurrentMock (-not (Test-NativeConcurrentStaleInstallComplete $first.root $target $injection)) 'STALE_PRESENT_NOT_SUCCESS'
    $script:presentStale=$false
    Assert-ConcurrentMock (Test-NativeConcurrentStaleInstallComplete $first.root $target $injection) 'STALE_REMOVED_TARGET_COMPLETE_CONTRACT'
    $script:presentLast=$false
    Assert-ConcurrentMock (-not (Test-NativeConcurrentStaleInstallComplete $first.root $target $injection)) 'NO_INSTALL_RECEIPT_NOT_SUCCESS'
    $script:presentLast=$true;$script:presentJournal=$true
    Assert-ConcurrentMock (-not (Test-NativeConcurrentStaleInstallComplete $first.root $target $injection)) 'JOURNAL_NOT_CLEAN_NOT_SUCCESS'
    $script:presentJournal=$false;$script:restart=$true
    Assert-ConcurrentReject {Test-NativeConcurrentStaleInstallComplete $first.root $target $injection} 'NATIVE_UNEXPECTED_RESTART'
    $script:restart=$false;$script:lastOutcome='ROLLED_BACK'
    Assert-ConcurrentReject {Test-NativeConcurrentStaleInstallComplete $first.root $target $injection} 'NATIVE_INSTALL_OUTCOME'
    $script:lastOutcome='UPDATED';$script:presentStale=$true
    Assert-ConcurrentReject {Observe-NativeConcurrentStaleInstall $first.root @{} $target @{} $injection $foreign 'memory-only' 180} 'CONCURRENT_STALE_LEASE_INSTALL_TIMEOUT'
    Assert-ConcurrentMock ($script:lastObservedTimeout -eq 60 -and $script:lastReceipt.status -ceq 'FAIL' -and
        $script:lastReceipt.scope -ceq 'NATIVE_STALE_LEASE_BIRTH_MISMATCH' -and $script:lastReceipt.kernelPidReuseObserved -ceq $false -and
        $script:presentStale -and -not $foreign.native.uiProcess.HasExited) 'BLOCKING_PRODUCTION_SEMANTICS_SAVED_AS_FAILURE'
    $script:presentStale=$false;$script:calls.Clear()
    [void](Observe-NativeConcurrentStaleInstall $first.root @{} $target @{} $injection $foreign 'memory-only' 30)
    Assert-ConcurrentMock ($script:lastObservedTimeout -eq 30 -and $script:lastReceipt.status -ceq 'OBSERVED' -and
        $script:lastReceipt.acceptance -ceq 'MAIN_PENDING' -and $script:lastReceipt.peerAfterInstall.ui.pid -eq $foreign.native.ui.pid) 'OBSERVED_ONLY_NOT_ACCEPTED_PASS'
    Assert-ConcurrentMock ($script:calls.IndexOf('bounded-observer') -lt $script:calls.IndexOf('frozen-target-verification') -and
        $script:calls.IndexOf('frozen-target-verification') -lt $script:calls.LastIndexOf('identity-'+$foreign.native.ui.pid)) 'PEER_ALIVE_AFTER_GENUINE_VERIFY_CONTRACT'
    $foreign.native.uiProcess.HasExited=$true
    Assert-ConcurrentReject {Observe-NativeConcurrentStaleInstall $first.root @{} $target @{} $injection $foreign 'memory-only' 30} 'CONCURRENT_CLIENT_EXITED'
    $foreign.native.uiProcess.HasExited=$false
}

# Статические контракты integration: фиксированная сигнатура, actual witnesses и отсутствие kill в close.
$top=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativeConcurrentScenario'},$true))[0]
$parameters=@($top.Parameters | ForEach-Object {$_.Name.VariablePath.UserPath})
Assert-ConcurrentMock (($parameters -join ',') -ceq 'Row,Source,Base,Target,Life,Cold,Java,Evidence,Timeout') 'FROZEN_ENTRY_SIGNATURE'
$close=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Close-NativeConcurrentMember'},$true))[0].Extent.Text
Assert-ConcurrentMock ($close -notmatch 'Kill\(|Stop-Process|Stop-Cold' -and $close -match 'CloseMainWindow' -and $close -match 'closeMain') 'NO_KILL_ORDINARY_SUCCESS'
Assert-ConcurrentMock ($top.Extent.Text.Contains('MAIN_NATIVE_STALE_LEASE_ACCEPTANCE_PENDING') -and $top.Extent.Text.Contains("else {'PENDING'}")) 'STALE_LEASE_NOT_FAKE_ACCEPTED'
Assert-ConcurrentMock ($top.Extent.Text.IndexOf('Add-NativeConcurrentStaleLease',[StringComparison]::Ordinal) -lt
    $top.Extent.Text.IndexOf('Close-NativeConcurrentMember',[StringComparison]::Ordinal)) 'INJECTION_BEFORE_ALL_PRIMARY_CLOSES'
$observer=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Observe-NativeConcurrentStaleInstall'},$true))[0].Extent.Text
Assert-ConcurrentMock ($observer -notmatch 'Stop-Cold|Stop-Process|Kill\(|Remove-Item|File\]::Delete' -and $observer.Contains('Wait-NativeInstalled')) 'PRODUCTION_REJECTION_NOT_TEST_DELETION_OR_FOREIGN_KILL'
foreach ($field in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog','command','startedAt','finishedAt') {
    Assert-ConcurrentMock ($top.Extent.Text.Contains($field)) ('CANONICAL_ARTIFACT_'+$field)
}
Assert-ConcurrentMock ($top.Extent.Text.Contains('CONCURRENT_FORCED_CLEANUP_REQUIRED') -and $top.Extent.Text.Contains("if (`$failure -or `$cleanup.Count) {'FAIL'}")) 'CLEANUP_CANNOT_PASS'
Assert-ConcurrentMock ($top.Extent.Text.IndexOf("'offline-close'",[StringComparison]::Ordinal) -lt $top.Extent.Text.IndexOf("'valid'",[StringComparison]::Ordinal) -and
    $top.Extent.Text.Contains('CONCURRENT_OFFLINE_PREPARATION_TIMEOUT')) 'NO_READY_BEFORE_LAST_LAUNCH'
Assert-ConcurrentMock ($row.status -ceq 'PENDING') 'MOCKS_NEVER_NATIVE_PASS'
Write-Output ("CONCURRENT_FIXTURES_PASS checks="+$script:checks+' scope=UNIT_MOCK_ONLY nativeExecuted=false')
