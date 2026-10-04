<#
.SYNOPSIS
Focused acceptance fixtures: контракты missing/contradiction/UNIT_MOCK, не native PASS.
.DESCRIPTION
Только синтетические небольшие файлы собственных Temp UUID. Процессы, GUI, Java, helper не запускаются.
Даже полностью согласованный набор metadata остаётся PENDING без настоящего retained handle.
#>
param([string]$FrozenHelper='', [string]$FrozenHelperSha256='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
. (Join-Path $PSScriptRoot 'NativeUpdateRollbackAcceptance.ps1')
. (Join-Path $PSScriptRoot 'NativeUpdateRollbackAcceptanceCollector.ps1')
$script:acceptanceChecks=0

# Проверка fixtures никогда не принимает native PASS из synthetic evidence.
function Assert-AcceptanceFixture([bool]$Value,[string]$Name) {
    if (-not $Value) {throw ('ACCEPTANCE_FIXTURE_'+$Name)};$script:acceptanceChecks++
}

# Меняет только synthetic JSON своего нового fixture каталога.
function Write-AcceptanceFixtureJson([string]$Path,$Value) {
    [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 64),[Text.UTF8Encoding]::new($false))
}

# Обязательный отрицательный результат проверяется вместе с отсутствием nativePass.
function Assert-AcceptanceFixtureResult($Result,[string]$Status,[string]$Code) {
    Assert-AcceptanceFixture ($Result.status -ceq $Status -and -not $Result.nativePass) ($Status+'_'+$Code)
    Assert-AcceptanceFixture (@($Result.reasons+$Result.missing | Where-Object {$_ -like ('*'+$Code+'*')}).Count -gt 0) ('REASON_'+$Code)
}

# Fixture-only глубокая копия сохраняет wire даты строками.
function ConvertFrom-ColdAcceptanceFixture($Value) {
    $options=@{Depth=64};if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {$options.DateKind='String'}
    return (ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $Value -Depth 64) @options)
}

$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'NativeUpdateRollbackAcceptance.ps1'),[ref]$tokens,[ref]$parseErrors)
Assert-AcceptanceFixture ($parseErrors.Count -eq 0) 'PARSE'
Assert-AcceptanceFixture (@($ast.EndBlock.Statements | Where-Object {$_ -isnot [Management.Automation.Language.FunctionDefinitionAst]}).Count -eq 0) 'DEFINITIONS_ONLY'
Assert-AcceptanceFixture (-not ($ast.Extent.Text -match '\.Start\(|\.Kill\(|Start-Process|Remove-Item|Set-Acl|icacls|WriteAll|Set-Content')) 'READ_ONLY_ACCEPTANCE'
$rowOnly=[pscustomobject]@{status='PASS';scenario='locked-rollback';phase='SESSION'}
Assert-AcceptanceFixtureResult (Test-NativeRollbackAcceptance $rowOnly ([pscustomobject]@{})) 'PENDING' 'AUTHORITY'
$unit=[pscustomobject]@{status='PASS';evidenceKind='UNIT_MOCK'}
Assert-AcceptanceFixtureResult (Test-NativeRollbackAcceptance $unit ([pscustomobject]@{})) 'PENDING' 'UNIT_MOCK'
$readers=Import-RollbackAcceptanceReaders $PSScriptRoot

# Реальный pinned helper только читается/рендерится/парсится, его body не исполняется.
if ($FrozenHelper) {
    if ($FrozenHelperSha256 -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $FrozenHelper).Hash.ToLowerInvariant() -cne $FrozenHelperSha256) {throw 'FIXTURE_FROZEN_HELPER_PIN'}
    $text=[IO.File]::ReadAllText($FrozenHelper)
    foreach ($mode in 'locked-rollback','readonly-rollback','disk-full-rollback') {
        $faultPath=if ($mode -ceq 'locked-rollback') {'runtime/bin/jli.dll'} else {'CashPrediction.exe'}
        $input=Get-RollbackAcceptanceExpectedInput $text $mode $faultPath 60
        $rendered=New-NativeRollbackWriteBoundaryHelper $input
        [void][Management.Automation.Language.Parser]::ParseInput($rendered,[ref]$tokens,[ref]$parseErrors)
        Assert-AcceptanceFixture ($parseErrors.Count -eq 0 -and $rendered.Contains("event='END'") -and $rendered.Contains('NativeAcceptanceBoundary')) ('PINNED_RENDER_'+$mode)
        $rejected=$false
        try {New-NativeRollbackWriteBoundaryHelper ($input+"`n[IO.File]::WriteAllText('C:\outside','x')") | Out-Null} catch {$rejected=$_.Exception.Message -like '*UNHANDLED*'}
        Assert-AcceptanceFixture $rejected ('UNKNOWN_SINK_REJECTED_'+$mode)
    }
    # UNIT_MOCK module проверяет persistence/restore hooks и runner finally, без process Start.
    $state=[pscustomobject]@{original=$input;rendered=$null;cleanup=$false;hookSeen=$false}
    $adapter=New-Module -ArgumentList $state -ScriptBlock {
        param($State)
        $script:fixtureState=$State
        function New-NativeRollbackHelperText {param($Original,$Scenario,$FaultPath,$Timeout) return $script:fixtureState.original}
        function Start-NativeRollbackOwned {param($Executable,$Arguments,$Root,$Endpoint='',[switch]$Web) throw 'FIXTURE_START_FORBIDDEN'}
        function Invoke-NativeRollbackScenario {
            param($Row,$Source,$Base,$Target,$Life,$Cold,$Java,$Evidence,$Timeout)
            try {
                $script:fixtureState.hookSeen=(Get-Command New-NativeRollbackHelperText).ScriptBlock.ToString().Contains('acceptanceOriginalRenderer')
                $script:fixtureState.rendered=New-NativeRollbackHelperText '' '' '' 60
                throw 'FIXTURE_ADAPTER_FAILURE'
            } finally {$script:fixtureState.cleanup=$true}
        }
    }
    $unitResult=Invoke-NativeRollbackCollectedAcceptance $adapter $unit '' $null $null $null $null '' '' 60 ([pscustomobject]@{evidenceKind='UNIT_MOCK'})
    Assert-AcceptanceFixture ($state.hookSeen -and $state.cleanup -and $state.rendered.Contains('NativeAcceptanceBoundary')) 'MODULE_HOOKS_PERSIST_THROUGH_RUNNER_FINALLY'
    Assert-AcceptanceFixture ($unitResult.adapterFailure -ceq 'FIXTURE_ADAPTER_FAILURE' -and $unitResult.acceptance.status -ceq 'PENDING' -and -not $unitResult.acceptance.nativePass) 'COLLECTOR_MOCK_NEVER_NATIVE_PASS'
    $restored=& $adapter {(Get-Command New-NativeRollbackHelperText).ScriptBlock.ToString()}
    Assert-AcceptanceFixture (-not $restored.Contains('acceptanceOriginalRenderer')) 'MODULE_HOOKS_RESTORED'
}

$run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
$frozenRun=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
$root=Join-Path $run 'plain/CashPrediction';$evidence=Join-Path $run 'evidence'
$source=Join-Path $frozenRun 'original';$targetRoot=Join-Path $frozenRun 'target'
try {
    foreach ($directory in $root,$source,$targetRoot) {
        [void][IO.Directory]::CreateDirectory((Join-Path $directory 'runtime/bin'))
        [IO.File]::WriteAllBytes((Join-Path $directory 'CashPrediction.exe'),[byte[]]@(1,2,3))
        [IO.File]::WriteAllBytes((Join-Path $directory 'runtime/bin/jli.dll'),[byte[]]@(4,5,6))
    }
    [IO.File]::WriteAllBytes((Join-Path $targetRoot 'CashPrediction.exe'),[byte[]]@(7,8,9))
    [void][IO.Directory]::CreateDirectory($evidence)
    [void][IO.Directory]::CreateDirectory((Join-Path $root 'CashMemory/Updates'))
    [IO.File]::WriteAllText((Join-Path $root 'CashMemory/protected-user.txt'),'fixture-user')
    $original=@(& $readers {param($Root) Get-ColdManagedInventory $Root} $source)
    $targetFiles=@(& $readers {param($Root) Get-ColdManagedInventory $Root} $targetRoot)
    $base=[pscustomobject]@{files=$original;treeSha256=(& $readers {param($Files) Get-ColdTreeHash $Files} $original);releaseNumber=1;commitSha=('a'*40)}
    $target=[pscustomobject]@{files=$targetFiles;treeSha256=(& $readers {param($Files) Get-ColdTreeHash $Files} $targetFiles);releaseNumber=2;commitSha=('b'*40)}
    $basePath=Join-Path $frozenRun 'base.json';$targetPath=Join-Path $frozenRun 'target.json'
    Write-AcceptanceFixtureJson $basePath $base;Write-AcceptanceFixtureJson $targetPath $target
    $originalHelper=Join-Path $frozenRun 'original-helper.ps1';$portableHelper=Join-Path $root 'CashMemory/Updates/apply-update.ps1';$ownHelper=Join-Path $evidence 'rollback-helper.ps1'
    [IO.File]::WriteAllText($originalHelper,'fixture bytes, never executed')
    [IO.File]::Copy($originalHelper,$portableHelper,$false)
    [IO.File]::WriteAllText($ownHelper,'instrumented fixture bytes, never executed')
    $helperSha=(Get-FileHash $originalHelper).Hash.ToLowerInvariant();$ownSha=(Get-FileHash $ownHelper).Hash.ToLowerInvariant()
    $authority=[pscustomobject]@{run=$run;root=$root;evidenceRoot=$evidence;sourceRoot=$source;targetRoot=$targetRoot;
        originalManifest=[pscustomobject]@{path=$basePath;sha256=(Get-FileHash $basePath).Hash.ToLowerInvariant()};
        targetManifest=[pscustomobject]@{path=$targetPath;sha256=(Get-FileHash $targetPath).Hash.ToLowerInvariant()};
        originalHelper=[pscustomobject]@{path=$originalHelper;sha256=$helperSha}}
    $row=[pscustomobject]@{status='PASS';scenario='locked-rollback';phase='SESSION'}
    $transaction=[guid]::NewGuid().ToString()
    $started=[datetime]::Parse('2026-10-04T00:00:02Z').ToUniversalTime().Ticks
    $identity=[pscustomobject]@{ProcessId=12345L;StartedAtTicks=$started;ExecutablePath=(Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe');OwnedRoot=$root}
    $users=& $readers {param($Root) $map=[ordered]@{};foreach ($item in @(Get-ColdUserInventory $Root)) {$map[$item.path]=$item};return $map} $root
    foreach ($name in 'currentBefore','currentAfter') {Write-AcceptanceFixtureJson (Join-Path $evidence ($name+'.json')) $original}
    foreach ($name in 'targetBefore','targetAfter') {Write-AcceptanceFixtureJson (Join-Path $evidence ($name+'.json')) $targetFiles}
    foreach ($name in 'userBefore','userAfter') {Write-AcceptanceFixtureJson (Join-Path $evidence ($name+'.json')) $users}
    Write-AcceptanceFixtureJson (Join-Path $evidence 'phaseLog.json') @('SESSION','BACKING_UP','ROLLING_BACK')
    Write-AcceptanceFixtureJson (Join-Path $evidence 'httpTrace.json') @()
    $fault=[pscustomobject]@{schemaVersion=1;installationRoot=$root;transactionId=$transaction;scenario=$row.scenario;boundary='File.Move';phase='BACKING_UP';
        operation=[pscustomobject]@{path='runtime/bin/jli.dll';kind='BACKUP';state='BEFORE'};hresult='80070020';exceptionType='System.IO.IOException';
        pid=$identity.ProcessId;startedAtTicks=$identity.StartedAtTicks;executablePath=$identity.ExecutablePath;observedUtc='2026-10-04T00:00:04Z';
        operations=@([pscustomobject]@{path='app/CashPrediction.cfg';kind='REDIRECT';state='AFTER'});scope='actual-managed-file-sharing-failure'}
    $injection=[pscustomobject]@{originalHelper=$originalHelper;originalHelperSha256=$helperSha;portableOriginalHelper=$portableHelper;portableOriginalHelperSha256=$helperSha;
        ownHelper=$ownHelper;ownHelperSha256=$ownSha;scenario=$row.scenario;faultPath=$fault.operation.path;lockShare='ReadWrite, no Delete';
        scope=$fault.scope;physicalDiskFilled=$false;transactionId=$transaction}
    $journal=[pscustomobject]@{installationRoot=$root;transactionId=$transaction;oldTreeSha256=$base.treeSha256;oldFiles=$original;target=$target;phase='PREPARED'}
    $last=[pscustomobject]@{outcome='ROLLED_BACK';transactionId=$transaction;targetCommitSha=$target.commitSha}
    $cleanup=[pscustomobject]@{root=$root;scope='OWN_RUN_UUID_AND_SELFTEST_NODE';node=('ru/cashprediction/selftest/'+[guid]::NewGuid());nativeCompleted=$true;filesRetained=$true;
        errors=@();unresolvedStartups=@();helperIdentity=$identity;uiIdentity=[pscustomobject]@{pid=54321L;startedAtTicks=($started-10000000)}}
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("& '"+$ownHelper+"' -InstallationRoot '"+$root+"' -Diagnostics"))
    $launch=[pscustomobject]@{identity=$identity;args=@('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded);helperSha256=$ownSha}
    $clientExit=[pscustomobject]@{pid=54321L;startedAtTicks=($started-10000000);exitCode=0;remainingClients=0;kind='ordinary-no-restart';exitedUtc='2026-10-04T00:00:01Z'}
    $baseline=[pscustomobject]@{fixtureTreeSha256=$base.treeSha256;originalTreeSha256=$base.treeSha256}
    foreach ($pair in @(@('fault-observed.json',$fault),@('injection-receipt.json',$injection),@('production-journal-before.json',$journal),
        @('last-install-observed.json',$last),@('cleanup.json',$cleanup),@('helper-launch.json',$launch),@('ordinary-exit.json',$clientExit),@('fixture-baseline.json',$baseline))) {
        Write-AcceptanceFixtureJson (Join-Path $evidence $pair[0]) $pair[1]
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'update-log.md'),"- 2026-10-04T00:00:03Z PHASE_BACKING_UP`n- 2026-10-04T00:00:05Z PHASE_ROLLING_BACK`n")
    $result=Test-NativeRollbackAcceptance $row $authority
    Assert-AcceptanceFixtureResult $result 'PENDING' 'GENUINE_RETAINED_HELPER_HANDLE_ABSENT'
    Assert-AcceptanceFixtureResult $result 'PENDING' 'GUARDED_BOUNDARY_LEDGER_ABSENT'
    Assert-AcceptanceFixture ($result.treeState -ceq 'ORIGINAL') 'ACTUAL_DISK_TREE_NOT_ADAPTER_STATUS'
    foreach ($field in 'hresult','boundary','phase','transactionId','scope','installationRoot','pid') {
        $bad=ConvertFrom-ColdAcceptanceFixture $fault
        $bad.$field=if ($field -ceq 'pid') {99999L} else {'wrong'}
        Write-AcceptanceFixtureJson (Join-Path $evidence 'fault-observed.json') $bad
        $result=Test-NativeRollbackAcceptance $row $authority
        Assert-AcceptanceFixture ($result.status -ceq 'FAIL' -and -not $result.nativePass) ('CONTRADICTION_'+$field)
    }
    Write-AcceptanceFixtureJson (Join-Path $evidence 'fault-observed.json') $fault
    [IO.File]::WriteAllText((Join-Path $root 'runtime/extra.bin'),'partial')
    Assert-AcceptanceFixtureResult (Test-NativeRollbackAcceptance $row $authority) 'FAIL' 'PARTIAL_OR_STALE_TREE'
    [IO.File]::Delete((Join-Path $root 'runtime/extra.bin'))
    [IO.File]::WriteAllText((Join-Path $root 'CashMemory/protected-user.txt'),'changed')
    Assert-AcceptanceFixtureResult (Test-NativeRollbackAcceptance $row $authority) 'FAIL' 'USER_CHANGED'
    [IO.File]::WriteAllText((Join-Path $root 'CashMemory/protected-user.txt'),'fixture-user')
    Assert-AcceptanceFixtureResult (Test-NativeRollbackAcceptance $row $authority ([pscustomobject]@{process=[pscustomobject]@{};identity=$identity})) 'PENDING' 'GENUINE_RETAINED'
    $cleanup.nativeCompleted=$false;Write-AcceptanceFixtureJson (Join-Path $evidence 'cleanup.json') $cleanup
    Assert-AcceptanceFixtureResult (Test-NativeRollbackAcceptance $row $authority) 'FAIL' 'IDENTITY_OR_CLEANUP'
    $cleanup.nativeCompleted=$true;Write-AcceptanceFixtureJson (Join-Path $evidence 'cleanup.json') $cleanup
    [IO.File]::WriteAllText((Join-Path $root 'CashMemory/Updates/install-journal.json'),'{}')
    Assert-AcceptanceFixtureResult (Test-NativeRollbackAcceptance $row $authority) 'FAIL' 'ROLLBACK_RESIDUE'
    [IO.File]::Delete((Join-Path $root 'CashMemory/Updates/install-journal.json'))
    $rejected=$false
    try {Open-NativeRollbackAcceptanceHandle ([pscustomobject]@{process=[pscustomobject]@{};identity=$identity}) $root {throw 'IDENTITY_READER_MUST_NOT_RUN'} | Out-Null} catch {$rejected=$_.Exception.Message -ceq 'COLLECTOR_REAL_PROCESS_REQUIRED'}
    Assert-AcceptanceFixture $rejected 'COLLECTOR_MOCK_PROCESS_REJECTED_BEFORE_IDENTITY_READER'
    $records=@([pscustomobject]@{event='START';schemaVersion=1;basis='ENFORCED_HELPER_FILE_WRITE_BOUNDARIES';pid=$identity.ProcessId;
        startedAtTicks=$started;executablePath=$identity.ExecutablePath;installationRoot=$root;coveredSites=1;inputSha256=('c'*64);observedUtc='2026-10-04T00:00:03Z'},
        [pscustomobject]@{event='ATTEMPT';sequence=1;kind='Move';paths=@($portableHelper);observedUtc='2026-10-04T00:00:04Z'},
        [pscustomobject]@{event='END';count=1;observedUtc='2026-10-04T00:00:05Z'})
    Assert-AcceptanceFixture ((Assert-RollbackAcceptanceBoundaries $records $identity $root ('c'*64) ($started+20000000) ($started+40000000)) -ceq '') 'LEDGER_CONTRACT_ONLY'
    $records[1].paths=@($originalHelper);$rejected=$false
    try {Assert-RollbackAcceptanceBoundaries $records $identity $root ('c'*64) ($started+20000000) 0 | Out-Null} catch {$rejected=$_.Exception.Message -like '*PATH_ESCAPE*'}
    Assert-AcceptanceFixture $rejected 'LEDGER_EXTERNAL_PATH_REJECTED'
    $records[1].paths=@($portableHelper)
    Assert-AcceptanceFixture ((Assert-RollbackAcceptanceBoundaries @($records[0],$records[1]) $identity $root ('c'*64) ($started+20000000) 0) -ceq 'BOUNDARY_LEDGER_INCOMPLETE') 'LEDGER_TRUNCATED_PENDING'
    $records[0] | Add-Member evidenceKind 'UNIT_MOCK'
    Assert-AcceptanceFixture ((Assert-RollbackAcceptanceBoundaries $records $identity $root ('c'*64) ($started+20000000) 0) -ceq 'UNIT_MOCK_BOUNDARY_LEDGER') 'MOCK_LEDGER_PENDING'
    [IO.File]::Delete((Join-Path $evidence 'fault-observed.json'))
    Assert-AcceptanceFixtureResult (Test-NativeRollbackAcceptance $row $authority) 'PENDING' 'ABSENT'
    Assert-AcceptanceFixture ($row.status -ceq 'PASS') 'ROW_NOT_MUTATED_OR_USED_AS_NATIVE_PROOF'
} finally {
    # Два проверенных UUID fixture root, не workspace или broad Temp; только собственные synthetic данные.
    foreach ($owned in @($run,$frozenRun)) {
        [void](Assert-RollbackAcceptancePath $owned)
        if ([IO.Path]::GetDirectoryName($owned) -cne [IO.Path]::GetTempPath().TrimEnd('\','/') -or
            [IO.Path]::GetFileName($owned) -cnotmatch '^run-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'FIXTURE_CLEANUP_ROOT'}
        if (Test-Path -LiteralPath $owned) {Remove-Item -LiteralPath $owned -Recurse -Force}
    }
}
Write-Output "Acceptance fixtures: $script:acceptanceChecks contract checks passed. Native rollback NOT RUN; no native PASS, helper/Java/GUI execution or external writes."
