<#
.SYNOPSIS
Ограниченные PS fixtures новых rollback hooks: safety, fault и call order, не native PASS.
.DESCRIPTION
Не запускает ни lifecycle runner, ни helper, ни Java/native/GUI. Синтетический helper используется
только как AST fixture. Файловая fixture проверяет FileStream sharing на своём Temp UUID.
Существующие cold/portable определения импортируются через AST, без исполнения script body.
#>
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
$script:rollbackChecks=0

# Проверка ограниченного контракта никогда не присваивает PASS строке UpdateEvidence.
function Assert-RollbackFixture([bool]$Condition,[string]$Code) {
    if (-not $Condition) {throw ('ROLLBACK_FIXTURE_'+$Code)}
    $script:rollbackChecks++
}

# Отказ должен содержать точный safety/fault код.
function Assert-RollbackFixtureReject([scriptblock]$Action,[string]$Code) {
    $rejected=$false
    try {& $Action} catch {if ($_.Exception.Message -notlike ('*'+$Code+'*')) {throw};$rejected=$true}
    Assert-RollbackFixture $rejected ('REJECT_'+$Code)
}

# Импортирует только именованное определение, не запускает тело источника.
function Import-RollbackFixtureFunction([string]$File,[string]$Name) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($File,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'ROLLBACK_FIXTURE_DEPENDENCY_PARSE'}
    $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $Name},$true))
    if ($nodes.Count -ne 1) {throw 'ROLLBACK_FIXTURE_DEPENDENCY_FUNCTION'}
    $text=$nodes[0].Extent.Text -replace ('^function '+[regex]::Escape($Name)),('function global:'+$Name)
    . ([scriptblock]::Create($text))
}

$source=Join-Path $PSScriptRoot 'NativeUpdateRollbackScenarios.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
Assert-RollbackFixture ($errors.Count -eq 0) 'SOURCE_PARSE'
# Source top-level содержит только объявления; dot-source не может запустить native очередь.
Assert-RollbackFixture (@($ast.EndBlock.Statements | Where-Object {$_ -isnot [Management.Automation.Language.FunctionDefinitionAst]}).Count -eq 0) 'DEFINITIONS_ONLY'
. $source
$cold=Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'
foreach ($name in 'Get-ColdTreeHash','Assert-ColdOwnedRun','Get-ColdUtcTicks','Test-ColdInteger','Assert-ColdProcessIdentity','ConvertFrom-ColdReceiptJson') {Import-RollbackFixtureFunction $cold $name}
$portable=Join-Path $PSScriptRoot 'Test-Portable.ps1'
foreach ($name in 'Resolve-PortableSafetyPath','Test-PortablePathContains') {Import-RollbackFixtureFunction $portable $name}

# Реальный актуальный closure импортируется в отдельный module scope, не в функции MAIN/ambient.
$plan=Get-NativeRollbackDependencyPlan $PSScriptRoot
Assert-RollbackFixture ($plan.functions.name -ccontains 'Convert-ColdNativePort') 'CURRENT_TRANSITIVE_NATIVE_PORT_DEPENDENCY'
Assert-RollbackFixture ($plan.functions.name -ccontains 'Get-NativeDomainBridgeArguments' -and
    $plan.functions.name -ccontains 'Get-ColdSessionWords') 'REAL_DOMAIN_AND_SESSION_CLOSURE'
$extra=Get-NativeRollbackDependencyPlan $PSScriptRoot @('New-ColdJournal','Start-ColdProcess','Get-ColdCheckpoints')
Assert-RollbackFixture ($extra.functions.name -ccontains 'Read-ColdConfig' -and
    $extra.functions.name -ccontains 'New-ColdJournal' -and $extra.functions.name -ccontains 'Start-ColdProcess' -and
    $extra.functions.name -ccontains 'Get-ColdCheckpoints') 'OPTIONAL_COLD_ROOTS_RESOLVED_FROM_ACTUAL_SOURCE'
Assert-RollbackFixture ($plan.functions.name -cnotcontains 'New-ColdJournal') 'NO_TEMPLATE_JOURNAL_IN_NATIVE_CLOSURE'
$ambientBefore=(Get-Command Get-ColdTreeHash).ScriptBlock.ToString()
$adapter=Import-NativeRollbackDependencies $PSScriptRoot
Assert-RollbackFixture ($adapter.PrivateData.scope -ceq 'AST_DEFINITIONS_ONLY_ISOLATED_MODULE' -and
    -not $adapter.PrivateData.nativeExecuted -and $adapter.PrivateData.sources.Count -eq 4) 'ISOLATED_IMPORT_RECEIPT'
Assert-RollbackFixture ((Get-Command Get-ColdTreeHash).ScriptBlock.ToString() -ceq $ambientBefore) 'AMBIENT_FUNCTION_NOT_REPLACED'
Assert-RollbackFixture (@($adapter.ExportedFunctions.Keys).Count -eq 2 -and
    $adapter.ExportedFunctions.ContainsKey('Invoke-NativeRollbackScenario') -and
    $adapter.ExportedFunctions.ContainsKey('Set-NativeRollbackContext')) 'EXACT_PUBLIC_ADAPTER_API'
Assert-RollbackFixture ($adapter.PrivateData.functions -cnotcontains 'Get-ColdCheckpoints') 'UNNEEDED_CHECKPOINT_NOT_AMBIENT_IMPORTED'

# Tripwire тела файлов: importer не выполняет top-level код даже при наличии ambient missing функции.
$importRun=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
$importRoot=Join-Path $importRun 'scripts';$script:fixtureBodyExecuted=$false
function Get-ColdFixtureMissingDependency {throw 'AMBIENT_DEPENDENCY_MUST_NOT_EXECUTE'}
try {
    [void][IO.Directory]::CreateDirectory($importRoot)
    $sourceText="function Invoke-NativeRollbackScenario { Get-ColdFixtureMissingDependency }`nfunction Set-NativeRollbackContext {}`n`$script:fixtureBodyExecuted=`$true; throw 'BODY_MUST_NOT_EXECUTE'"
    foreach ($file in 'NativeUpdateRollbackScenarios.ps1','Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1') {
        $text=if ($file -ceq 'NativeUpdateRollbackScenarios.ps1') {$sourceText} else {"throw 'BODY_MUST_NOT_EXECUTE'"}
        [IO.File]::WriteAllText((Join-Path $importRoot $file),$text,[Text.UTF8Encoding]::new($true))
    }
    Assert-RollbackFixtureReject {Get-NativeRollbackDependencyPlan $importRoot} 'ROLLBACK_DEPENDENCY_MISSING Get-ColdFixtureMissingDependency'
    [IO.File]::WriteAllText((Join-Path $importRoot 'Test-UpdateBootstrap.ps1'),"function Get-ColdFixtureMissingDependency { return 1 }`nthrow 'BODY_MUST_NOT_EXECUTE'")
    $fixtureAdapter=Import-NativeRollbackDependencies $importRoot
    Assert-RollbackFixture (-not $script:fixtureBodyExecuted -and $fixtureAdapter.PrivateData.functions -ccontains 'Get-ColdFixtureMissingDependency') 'BOM_AND_SOURCE_BODIES_NOT_EXECUTED'
    [IO.File]::WriteAllText((Join-Path $importRoot 'Test-NativeUpdateLifecycle.ps1'),'function Get-ColdFixtureMissingDependency {}')
    Assert-RollbackFixtureReject {Get-NativeRollbackDependencyPlan $importRoot} 'ROLLBACK_DEPENDENCY_DUPLICATE'
    [IO.File]::WriteAllText((Join-Path $importRoot 'Test-NativeUpdateLifecycle.ps1'),'function Broken {')
    Assert-RollbackFixtureReject {Get-NativeRollbackDependencyPlan $importRoot} 'ROLLBACK_DEPENDENCY_PARSE'
} finally {
    # Только четыре явных fixture файла и пустые каталоги собственного UUID.
    foreach ($file in 'NativeUpdateRollbackScenarios.ps1','Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1') {
        $path=Join-Path $importRoot $file;if ([IO.File]::Exists($path)) {[IO.File]::Delete($path)}
    }
    [IO.Directory]::Delete($importRoot);[IO.Directory]::Delete($importRun)
}

$row=[pscustomobject]@{scenario='locked-rollback';base='B1';client='fx';path='ascii';phase='SESSION';status='PENDING'}
Assert-NativeRollbackRow $row 30
Assert-RollbackFixture ($row.status -ceq 'PENDING') 'ROW_NOT_NATIVE_PASS'
foreach ($field in 'scenario','base','client','path','phase','status') {
    $bad=ConvertFrom-Json (ConvertTo-Json $row);$bad.$field='foreign'
    Assert-RollbackFixtureReject {Assert-NativeRollbackRow $bad 30} 'ROLLBACK_ROW'
}
foreach ($timeout in 29,301) {Assert-RollbackFixtureReject {Assert-NativeRollbackRow $row $timeout} 'ROLLBACK_ROW'}

$base=[pscustomobject]@{treeSha256='unmodified-source-pin';files=@(
    [pscustomobject]@{path='CashPrediction.exe';sizeBytes=3L;sha256=('a'*64);readOnly=$false},
    [pscustomobject]@{path='runtime/bin/jli.dll';sizeBytes=3L;sha256=('b'*64);readOnly=$false})}
$baseline=Get-NativeRollbackBaseline $base 'readonly-rollback'
Assert-RollbackFixture ($baseline.files[0].readOnly -and -not $base.files[0].readOnly -and $base.treeSha256 -ceq 'unmodified-source-pin') 'FROZEN_BASE_NOT_MUTATED'
Assert-RollbackFixture ($baseline.treeSha256 -ceq (Get-ColdTreeHash $baseline.files)) 'READONLY_ACTUAL_BASELINE_HASH'
$dated=ConvertFrom-ColdReceiptJson (ConvertTo-Json $base -Depth 16);$dated | Add-Member publishedAt '2026-10-04T00:00:00Z'
$datedCopy=Get-NativeRollbackBaseline $dated 'readonly-rollback'
Assert-RollbackFixture ($datedCopy.publishedAt -is [string] -and $datedCopy.publishedAt -ceq $dated.publishedAt) 'FROZEN_WIRE_DATE_NOT_COERCED'
Assert-RollbackFixture ((Get-NativeRollbackFaultPath $base 'locked-rollback') -ceq 'runtime/bin/jli.dll') 'PLAIN_RUNTIME_LOCK'
Assert-RollbackFixture ((Get-NativeRollbackFaultPath $base 'readonly-rollback') -ceq 'CashPrediction.exe') 'READONLY_EXE_LOCK'
$noPlain=Get-NativeRollbackBaseline $base 'locked-rollback';$noPlain.files[1].readOnly=$true
Assert-RollbackFixtureReject {Get-NativeRollbackFaultPath $noPlain 'locked-rollback'} 'ROLLBACK_PLAIN_RUNTIME_REQUIRED'

# Минимальный синтетический helper предназначен только для проверки AST вставок, не исполнения.
$original=@'
param([string]$InstallationRoot)
function Guard([string]$path) {return $path}
function AtomicJson([string]$path,$value) {return}
function PortableRollback {return}
function OpenLock([string]$path,[long]$length) {return}
function MoveJournal([string]$kind,[string]$path,[string]$source,[string]$destination) {
    [IO.File]::Move($source,$destination)
}
function FileOperation([string]$kind,[string]$path,[string]$source,[string]$destination) {
    $output.Flush($true)
    [IO.File]::Replace($temporary,$destination,[System.Management.Automation.Language.NullString]::Value)
}
$helper=OpenLock (Join-Path $updates 'helper.lock') 1
'@
foreach ($scenario in 'locked-rollback','readonly-rollback','disk-full-rollback') {
    $instrumented=New-NativeRollbackHelperText $original $scenario 'CashPrediction.exe' 30
    [void][Management.Automation.Language.Parser]::ParseInput($instrumented,[ref]$tokens,[ref]$errors)
    Assert-RollbackFixture ($errors.Count -eq 0) ('INJECTION_PARSE_'+$scenario)
    Assert-RollbackFixture ($instrumented.Contains("NativeRollbackCapture `$_ 'File.Move'") -and
        $instrumented.Contains("NativeRollbackCapture `$_ 'File.Replace'")) 'ACTUAL_IO_CATCHES'
    Assert-RollbackFixture ($instrumented.Contains("[IO.IOException]::new('simulated-disk-full-IO-boundary',-2147024784)") -and
        $instrumented.Contains('$output.Flush($true)') -and $instrumented.Contains("`$script:journal.phase -ceq 'INSTALLING'")) 'DISK_FLUSH_BOUNDARY_NOT_GENERIC_PHASE_THROW'
    Assert-RollbackFixture ($instrumented.Contains('-not $script:nativeRollbackCaptured') -and
        $instrumented.Contains('$script:nativeRollbackCaptured=$true')) 'ONE_SHOT_FAULT'
    Assert-RollbackFixture (-not $instrumented.Contains("status='PASS'") -and $original -cnotmatch 'NativeRollbackCapture') 'HOOK_NO_FAKE_PASS_OR_SOURCE_MUTATION'
    Assert-RollbackFixture ($instrumented.Contains("OpenLock (Join-Path `$updates 'native-rollback-helper.lock') 1") -and
        -not $instrumented.Contains("OpenLock (Join-Path `$updates 'helper.lock') 1")) 'OWN_HELPER_DISTINCT_LOCK_NO_PRODUCTION_RACE'
}
Assert-RollbackFixtureReject {New-NativeRollbackHelperText ($original.Replace('function PortableRollback','function Unknown')) 'locked-rollback' 'runtime/a.dll' 30} 'ROLLBACK_HELPER_CONTRACT'
Assert-RollbackFixtureReject {New-NativeRollbackHelperText ($original.Replace('$output.Flush($true)','$output.Flush()')) 'disk-full-rollback' 'CashPrediction.exe' 30} 'ROLLBACK_HELPER_FLUSH_CONTRACT'
Assert-RollbackFixtureReject {New-NativeRollbackHelperText $original 'unknown' 'runtime/a.dll' 30} 'ROLLBACK_INJECTION_INPUT'
Assert-RollbackFixtureReject {New-NativeRollbackHelperText $original 'locked-rollback' 'runtime/../foreign' 30} 'ROLLBACK_INJECTION_INPUT'
Assert-RollbackFixtureReject {New-NativeRollbackHelperText ($original.Replace("'helper.lock'","'foreign.lock'")) 'locked-rollback' 'runtime/a.dll' 30} 'ROLLBACK_HELPER_LOCK_CONTRACT'
Assert-RollbackFixture (([IO.IOException]::new('fixture',-2147024784)).HResult.ToString('X8') -ceq '80070070') 'DISK_FULL_HRESULT'

# Mock личности проверяет лишь rejection/поля; native сценарий здесь никогда не вызывается.
$script:fixtureLookupThrows=$false
function Get-ColdProcessReceipt($Process,[string]$Root) {
    if ($script:fixtureLookupThrows) {throw 'FIXTURE_IDENTITY_LOOKUP_FAILED'}
    return $script:fixtureIdentity
}
$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) 'fixture-root'
$script:fixtureIdentity=[pscustomobject]@{ProcessId=123L;StartedAtTicks=456L;ExecutablePath='C:\fixture\powershell.exe';OwnedRoot=$fixtureRoot}
$helper=[pscustomobject]@{identity=$script:fixtureIdentity;process=[pscustomobject]@{HasExited=$false}}
$fault=[pscustomobject]@{schemaVersion=1;installationRoot=$fixtureRoot;transactionId='fixture-transaction';scenario='locked-rollback';
    operation=[pscustomobject]@{path='runtime/bin/jli.dll';kind='BACKUP';state='BEFORE'};boundary='File.Move';phase='BACKING_UP';
    exceptionType='System.IO.IOException';hresult='80070020';scope='actual-managed-file-sharing-failure';
    operations=@([pscustomobject]@{kind='REDIRECT';path='app/CashPrediction.cfg';state='AFTER'});
    pid=123L;startedAtTicks=456L;executablePath='C:\fixture\powershell.exe';observedUtc='2026-10-04T00:00:00.0000000Z'}
Assert-NativeRollbackFault $fault $helper $fixtureRoot 'fixture-transaction' 'locked-rollback' 'runtime/bin/jli.dll'
Assert-RollbackFixture ($row.status -ceq 'PENDING') 'MOCK_FAULT_NOT_NATIVE_PASS'
foreach ($field in 'pid','startedAtTicks','executablePath','hresult','boundary','phase','exceptionType','transactionId','scope') {
    $bad=ConvertFrom-Json (ConvertTo-Json $fault -Depth 16)
    $bad.$field=if ($field -cin @('pid','startedAtTicks')) {999L} else {'invalid'}
    Assert-RollbackFixtureReject {Assert-NativeRollbackFault $bad $helper $fixtureRoot 'fixture-transaction' 'locked-rollback' 'runtime/bin/jli.dll'} 'ROLLBACK_FAULT_RECEIPT'
}
$bad=ConvertFrom-Json (ConvertTo-Json $fault -Depth 16);$bad.operations=@()
Assert-RollbackFixtureReject {Assert-NativeRollbackFault $bad $helper $fixtureRoot 'fixture-transaction' 'locked-rollback' 'runtime/bin/jli.dll'} 'ROLLBACK_NO_PARTIAL_MUTATION_OBSERVED'
# Синтетический disk receipt обязан иметь точные boundary/HResult/scope; он не меняет Row.
$disk=ConvertFrom-Json (ConvertTo-Json $fault -Depth 16);$disk.scenario='disk-full-rollback';$disk.operation.path='CashPrediction.exe'
$disk.operation.kind='REPLACE';$disk.phase='INSTALLING';$disk.boundary='FileOperation.Flush(true)';$disk.hresult='80070070'
$disk.scope='simulated-disk-full-IO-boundary';$disk.observedUtc=$fault.observedUtc
Assert-NativeRollbackFault $disk $helper $fixtureRoot 'fixture-transaction' 'disk-full-rollback' 'CashPrediction.exe'
Assert-RollbackFixture ($row.status -ceq 'PENDING') 'SIMULATED_DISK_RECEIPT_NOT_NATIVE_PASS'
$disk.scope='physical-disk-full'
Assert-RollbackFixtureReject {Assert-NativeRollbackFault $disk $helper $fixtureRoot 'fixture-transaction' 'disk-full-rollback' 'CashPrediction.exe'} 'ROLLBACK_FAULT_RECEIPT'
$helper.process.HasExited=$true
Assert-RollbackFixtureReject {Assert-NativeRollbackFault $fault $helper $fixtureRoot 'fixture-transaction' 'locked-rollback' 'runtime/bin/jli.dll'} 'ROLLBACK_HELPER_EXITED_EARLY'

# Identity startup failure удерживает mock handle; запрещён Kill при несовпадении личности или lookup error.
$pending=[Collections.Generic.List[object]]::new()
$entry=[pscustomobject]@{process=[pscustomobject]@{HasExited=$false};identity=$null;root=$fixtureRoot;expectedExecutable='C:\fixture\powershell.exe'}
Assert-RollbackFixtureReject {Register-NativeRollbackProcess $entry $pending {throw 'FIXTURE_IDENTITY_LOOKUP_FAILED'}} 'FIXTURE_IDENTITY_LOOKUP_FAILED'
Assert-RollbackFixture ($pending.Count -eq 1 -and $pending[0] -eq $entry -and $null -eq $entry.identity) 'HANDLE_RETAINED_BEFORE_IDENTITY_FAILURE'
$script:fixtureKills=0
function Stop-ColdRetainedProcess($Process,$Identity,[string]$Root) {$script:fixtureKills++}
$originalIdentity=$script:fixtureIdentity
$script:fixtureIdentity=[pscustomobject]@{ProcessId=999L;StartedAtTicks=999L;ExecutablePath='C:\foreign\powershell.exe';OwnedRoot=$fixtureRoot}
Assert-RollbackFixtureReject {Stop-NativeRollbackTracked $entry} 'ROLLBACK_CLEANUP_IDENTITY'
Assert-RollbackFixture ($script:fixtureKills -eq 0 -and $pending.Count -eq 1) 'NO_KILL_UNPROVEN_IDENTITY'
$script:fixtureLookupThrows=$true
Assert-RollbackFixtureReject {Stop-NativeRollbackTracked $entry} 'FIXTURE_IDENTITY_LOOKUP_FAILED'
Assert-RollbackFixture ($script:fixtureKills -eq 0 -and $pending.Count -eq 1) 'LOOKUP_FAILURE_KEEPS_HANDLE_NO_KILL_NO_PASS'
$script:fixtureLookupThrows=$false
$script:fixtureIdentity=$originalIdentity;$entry.identity=$originalIdentity
$script:fixtureIdentity=[pscustomobject]@{ProcessId=999L;StartedAtTicks=999L;ExecutablePath='C:\fixture\powershell.exe';OwnedRoot=$fixtureRoot}
Assert-RollbackFixtureReject {Stop-NativeRollbackTracked $entry} 'COLD_PROCESS_IDENTITY'
Assert-RollbackFixture ($script:fixtureKills -eq 0) 'PID_REUSE_REJECTED_BEFORE_KILL'
$script:fixtureIdentity=$originalIdentity
$success=[pscustomobject]@{process=[pscustomobject]@{HasExited=$false};identity=$null;root=$fixtureRoot;expectedExecutable='C:\fixture\powershell.exe'}
$successfulList=[Collections.Generic.List[object]]::new()
[void](Register-NativeRollbackProcess $success $successfulList {return $script:fixtureIdentity})
Assert-RollbackFixture ($successfulList.Count -eq 0 -and $success.identity -eq $originalIdentity) 'SUCCESS_TRANSFERS_HANDLE_TO_CALLER_NOT_DUPLICATE_CLEANUP'

# Настоящий FileStream на маленьком fixture файле; никаких portable exe или updater процессов.
$run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
$root=Join-Path $run 'fixture';$directory=Join-Path $root 'runtime';$updates=Join-Path $root 'CashMemory/Updates'
$file=Join-Path $directory 'probe.bin';$moved=Join-Path $directory 'moved.bin';$lock=$null
try {
    [void][IO.Directory]::CreateDirectory($directory);[void][IO.Directory]::CreateDirectory($updates)
    [IO.File]::WriteAllBytes($file,[byte[]]@(1,2,3))
    Assert-RollbackFixtureReject {Assert-NativeRollbackOwned $run ([IO.Path]::GetTempPath().TrimEnd('\','/'))} 'ROLLBACK_OWNED_PATH'
    Assert-RollbackFixtureReject {Open-NativeRollbackFileLock $run $root 'runtime/../foreign'} 'ROLLBACK_LOCK_PATH'
    Assert-RollbackFixtureReject {Assert-NativeRollbackOwned $run (Join-Path $root 'runtime/../foreign')} 'ROLLBACK_OWNED_PATH'
    $lock=Open-NativeRollbackFileLock $run $root 'runtime/probe.bin'
    $denied=$false
    try {[IO.File]::Move($file,$moved)} catch {$denied=$_.Exception.GetBaseException() -is [IO.IOException]}
    Assert-RollbackFixture $denied 'NO_DELETE_SHARE_GENUINE_FILESYSTEM_FAILURE'
    Release-NativeRollbackFault $lock $run $root;$lock=$null
    [IO.File]::Move($file,$moved)
    Assert-RollbackFixture ([IO.File]::Exists($moved) -and [IO.File]::Exists((Join-Path $updates 'native-rollback-release'))) 'LOCK_DISPOSED_BEFORE_RELEASE'
} finally {
    if ($null -ne $lock) {$lock.Dispose()}
    # Только явные маленькие файлы и пустые каталоги собственной UUID fixture, без recursive delete.
    foreach ($path in @($file,$moved,(Join-Path $updates 'native-rollback-release'))) {if ([IO.File]::Exists($path)) {[IO.File]::Delete($path)}}
    foreach ($path in @($directory,$updates,(Join-Path $root 'CashMemory'),$root,$run)) {if ([IO.Directory]::Exists($path)) {[IO.Directory]::Delete($path)}}
}

$cell=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativeRollbackScenario'},$true))[0].Extent.Text
Assert-RollbackFixture ($cell.IndexOf('Open-NativeRollbackHelperBarrier') -lt $cell.IndexOf('Close-NativeNormally') -and
    $cell.IndexOf('Close-NativeNormally') -lt $cell.IndexOf('New-NativeRollbackHelperText') -and
    $cell.IndexOf('Assert-NativeRollbackFault $fault') -lt $cell.IndexOf('Release-NativeRollbackFault $lock')) 'BARRIER_EXIT_OBSERVATION_RELEASE_CALL_ORDER'
Assert-RollbackFixture ($cell.IndexOf('$barrier.Unlock(0,1)') -gt $cell.IndexOf('Stop-NativeRollbackTracked $entry') -and
    $cell.IndexOf('$barrier.Unlock(0,1)') -gt $cell.IndexOf("`$last.outcome -cne 'ROLLED_BACK'")) 'PRODUCTION_BARRIER_HELD_THROUGH_ACTUAL_ROLLBACK_AND_CLEANUP'
Assert-RollbackFixture ($cell.Contains("`$last.outcome -cne 'ROLLED_BACK'") -and $cell.Contains('Assert-ColdTree $root $baseline') -and
    $cell.Contains('Test-ColdInventoryEqual $userBefore $userAfter') -and $cell.Contains('Assert-ColdControlledChanges') -and
    $cell.Contains('Stop-ColdRetainedProcess $helper.process $helper.identity $root')) 'FULL_TREE_USER_IDENTITY_CLEANUP_GATES'
foreach ($field in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','httpTrace','phaseLog',
    'exe','args','baseRelease','baseCommit','targetRelease','targetCommit','command','startedAt','finishedAt','exitCode','skipped','failures') {
    Assert-RollbackFixture ($cell.Contains($field+'=')) ('UPDATE_EVIDENCE_FIELD_'+$field)
}
Assert-RollbackFixture (-not ($ast.Extent.Text -match 'Set-Acl|icacls|SetLength\(|diskpart|Remove-Item.*\$run')) 'NO_ACL_DISK_FILL_OR_RUN_DELETE'
Write-Output "Rollback fixtures: $script:rollbackChecks contract checks passed; safety/fault/call-order ONLY. Native rollback NOT RUN / PENDING; no Java/native/GUI executed."
