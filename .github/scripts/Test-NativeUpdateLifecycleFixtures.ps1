<#
.SYNOPSIS
Контракты native lifecycle runner: AST, данные в памяти и изолированные файловые fixtures.
.DESCRIPTION
Не исполняет тело runner, Java, Maven, exe, helper, GUI, сеть или реестр.
Положительные mock receipts проверяют guards, но никогда не пишутся как native PASS.
Файловые fixtures используют собственные Temp UUID: concurrent append и byte-copy для Unicode seed.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'NATIVE_FIXTURE_POWERSHELL7'}
$tokens=$null;$errors=$null
$source=Join-Path $PSScriptRoot 'Test-NativeUpdateLifecycle.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw ('NATIVE_FIXTURE_PARSE '+($errors.Message -join '; '))}
foreach ($function in @($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))) {
    . ([scriptblock]::Create($function.Extent.Text))
}
Import-NativeDependencies $PSScriptRoot
$script:checks=0

# Отказ по другой причине означает сломанную fixture, а не правильную защиту.
function Assert-NativeMockReject([scriptblock]$Action,[string]$Code) {
    $caught=$null
    try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
    if ($caught -cne $Code) {throw "NATIVE_FIXTURE_REJECTION expected=$Code actual=$caught"}
    $script:checks++
}

# Положительные controls исключают тесты, падающие раньше нужного guard.
function Assert-NativeMock([bool]$Condition,[string]$Code) {
    if (-not $Condition) {throw "NATIVE_FIXTURE_ASSERT $Code"};$script:checks++
}

# Независимые копии mutable JSON и сохранение строковых UTC, включая наносекунды.
function Copy-NativeMock($Value) {return (ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64))}

$evidenceSource=Join-Path $PSScriptRoot '../../ui-parity/src/test/java/ru/cashprediction/parity/update/UpdateEvidence.java'
$plan=@(Get-NativeEvidencePlan $evidenceSource)
Assert-NativeMock ($plan.Count -eq 612) 'EXISTING_EVIDENCE_PLAN'
Assert-NativeMock (@($plan | Where-Object {$_.status -cne 'PENDING'}).Count -eq 0) 'NO_FAKE_PASS'
Assert-NativeMock (@($plan | Where-Object {$_.phase -ceq 'SESSION'}).Count -eq 360) 'SESSION_PLAN'
Assert-NativeMock (@($plan | Where-Object {$_.scenario -ceq 'journal-fault'}).Count -eq 126) 'DURABLE_PHASES'
Assert-NativeMock (@($plan | Where-Object {$_.scenario -ceq 'corrupt-full-retain'}).Count -eq 18) 'EXISTING_FULL_RETAIN_CELLS'
# Проверяется реальный AST-импорт зависимого cfg guard, без изменения cold runner.
Assert-NativeMock ((Get-ColdMainModule "[Application]`napp.mainmodule=ru.cashprediction.fx/ru.cashprediction.fx.FxMain`n[JavaOptions]`njava-options=--module-path`njava-options=`$APPDIR") -ceq
    'ru.cashprediction.fx/ru.cashprediction.fx.FxMain') 'EXTERNAL_MODULES_DEPENDENCY_IMPORTED'
Assert-NativeMockReject {Get-ColdMainModule "[Application]`napp.mainmodule=ru.cashprediction.fx/ru.cashprediction.fx.FxMain`n[JavaOptions]`njava-options=--module-path`njava-options=foreign"} 'COLD_CFG_MODULE_PATH'

# Выполнение внешней операции из любого guard немедленно отвергается.
function Start-NativeOwned {throw 'NATIVE_FIXTURE_FORBIDDEN_PROCESS'}
function Start-ColdProcess {throw 'NATIVE_FIXTURE_FORBIDDEN_PROCESS'}
function Invoke-ColdTool {throw 'NATIVE_FIXTURE_FORBIDDEN_JAVA'}
function Stop-Process {throw 'NATIVE_FIXTURE_FORBIDDEN_KILL'}
function Invoke-WebRequest {throw 'NATIVE_FIXTURE_FORBIDDEN_NETWORK'}
function Invoke-RestMethod {throw 'NATIVE_FIXTURE_FORBIDDEN_NETWORK'}
function Remove-Item {throw 'NATIVE_FIXTURE_FORBIDDEN_DELETE'}

$root=Join-Path ([IO.Path]::GetTempPath()) 'native-mock-memory-copy'
$node='ru/cashprediction/selftest/11111111-1111-1111-1111-111111111111'
foreach ($client in 'fx','swing','web') {
    $arguments=@(Get-NativeArguments $root $client $node)
    Assert-NativeMock ($arguments[0] -ceq '--test-api' -and $arguments[1] -ceq '--home' -and $arguments[2] -ceq $root -and
        $arguments[3] -ceq '--registry-node' -and $arguments[4] -ceq $node) 'STRICT_SEAM_ARGS'
    Assert-NativeMock (($arguments.Count -eq 7) -eq ($client -ceq 'web')) 'WEB_FLAGS_ONLY'
}

# Lifecycle импортирует cleanup MAIN: реальные типы CIM проверяются без процессов и без изменения JSON guard.
function Test-NativeCimCleanupContracts([string]$Root) {
    & {
        param($Root)
        $script:WorkDir=[IO.Path]::GetDirectoryName($Root)
        $started=[datetime]::new(2026,10,4,2,0,0,[DateTimeKind]::Utc)
        $powerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
        $helperPath=Join-Path $Root 'CashMemory/Updates/apply-update.ps1'
        $helperCommand="& '"+$helperPath.Replace("'","''")+"' -InstallationRoot '"+$Root.Replace("'","''")+"'"
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($helperCommand))
        $helperLine='"'+$powerShell+'" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand '+$encoded
        $script:cimFixtureObservation=$null;$script:cimFixtureCurrent=$null;$script:cimFixtureRetained=$null
        $script:cimFixtureStopped=$false;$script:cimFixtureOpens=0;$script:cimFixtureKills=0
        # Возвращается настоящий UInt32 scalar, без JSON-roundtrip и приведения перед guard.
        function Get-CopyProcesses {if (-not $script:cimFixtureStopped) {return $script:cimFixtureObservation}}
        function Get-CimInstance {
            if (-not $script:cimFixtureStopped) {
                $script:cimFixtureCimReads++
                if ($script:cimFixtureKind -ceq 'helper' -and $script:cimFixtureCimReads -eq 1) {return $script:cimFixtureObservation}
                return $script:cimFixtureCurrent
            }
        }
        function Resolve-PortableSafetyPath([string]$Path) {return $Path}
        function Open-PortableProcess([int]$ProcessId) {
            $script:cimFixtureOpens++
            if ($ProcessId -ne [int]$script:cimFixtureObservation.ProcessId) {throw 'CIM_FIXTURE_WRONG_OPEN'}
            return $script:cimFixtureRetained
        }
        function Get-ColdProcessReceipt($Process,[string]$Root) {
            return [pscustomobject]@{ProcessId=$Process.Id;StartedAtTicks=$Process.StartTime.Ticks;
                ExecutablePath=$Process.MainModule.FileName;OwnedRoot=$Root}
        }
        function Stop-ColdRetainedProcess($Process,$Expected,[string]$Root) {
            # Успешный cleanup всё равно обязан сохранить полный существующий identity guard.
            Assert-ColdProcessIdentity $Expected (Get-ColdProcessReceipt $Process $Root) $Root $Expected.ExecutablePath
            $script:cimFixtureKills++;$script:cimFixtureStopped=$true
        }
        function Set-NativeCimFixture($CimPid,[string]$Kind) {
            $exe=if ($Kind -ceq 'copy') {Join-Path $Root 'CashPrediction.exe'} else {$powerShell}
            $line=if ($Kind -ceq 'copy') {'"'+$exe+'" --test-api'} else {$helperLine}
            $script:cimFixtureObservation=[pscustomobject]@{ProcessId=$CimPid;ExecutablePath=$exe;CommandLine=$line;CreationDate=$started}
            $script:cimFixtureCurrent=[pscustomobject]@{ProcessId=$CimPid;ExecutablePath=$exe;CommandLine=$line;CreationDate=$started}
            $script:cimFixtureRetained=[pscustomobject]@{Id=14004;HasExited=$false;MainModule=[pscustomobject]@{FileName=$exe};StartTime=$started}
            $script:cimFixtureRetained | Add-Member ScriptMethod Dispose {}
            $script:cimFixtureStopped=$false;$script:cimFixtureOpens=0;$script:cimFixtureKills=0
            $script:cimFixtureKind=$Kind;$script:cimFixtureCimReads=0
        }
        function Invoke-NativeCimFixture([string]$Kind) {
            if ($Kind -ceq 'copy') {Stop-ColdCopyProcesses $Root}
            else {Stop-ColdRecoveryHelpers $Root $powerShell $started}
        }
        foreach ($kind in 'copy','helper') {
            foreach ($pidValue in @([uint32]14004,[uint32][int]::MaxValue)) {
                Set-NativeCimFixture $pidValue $kind;$script:cimFixtureRetained.Id=[int]$pidValue
                Assert-NativeMock ($script:cimFixtureObservation.ProcessId -is [uint32]) 'CIM_UINT32_TYPE_PRESERVED'
                Invoke-NativeCimFixture $kind
                Assert-NativeMock ($script:cimFixtureOpens -eq 1 -and $script:cimFixtureKills -eq 1) 'CIM_UINT32_VALID_CLEANUP'
            }
            foreach ($pidValue in @([uint32]0,0,-1,([uint32]2147483648),[uint32]::MaxValue,$true,$false,'14004')) {
                Set-NativeCimFixture $pidValue $kind
                Assert-NativeMockReject {Invoke-NativeCimFixture $kind} 'COLD_PROCESS_IDENTITY'
                Assert-NativeMock ($script:cimFixtureOpens -eq 0 -and $script:cimFixtureKills -eq 0) 'INVALID_CIM_PID_REJECTED_BEFORE_OPEN'
            }
            foreach ($field in 'birth','path','command','pid') {
                Set-NativeCimFixture ([uint32]14004) $kind
                switch ($field) {
                    'birth' {$script:cimFixtureCurrent.CreationDate=$started.AddSeconds(1)}
                    'path' {$script:cimFixtureCurrent.ExecutablePath='foreign.exe'}
                    'command' {$script:cimFixtureCurrent.CommandLine+=' foreign'}
                    'pid' {$script:cimFixtureRetained.Id++}
                }
                $code=if ($field -ceq 'birth') {'COLD_HELPER_PID_REUSED'} else {'COLD_PROCESS_IDENTITY'}
                Assert-NativeMockReject {Invoke-NativeCimFixture $kind} $code
                Assert-NativeMock ($script:cimFixtureKills -eq 0) 'CIM_IDENTITY_MISMATCH_NO_KILL'
            }
        }
    } $Root
}
Test-NativeCimCleanupContracts $root
Assert-NativeMock (-not (Test-ColdInteger ([uint32]14004) 1)) 'JSON_WIRE_STILL_REJECTS_UINT32'
Assert-NativeMock ((Test-ColdInteger ([int]14004) 1) -and (Test-ColdInteger ([long]14004) 1)) 'JSON_WIRE_INT_LONG_UNCHANGED'

Assert-NativeMockReject {Get-NativeArguments 'relative' 'fx' $node} 'NATIVE_ABSOLUTE_PATH'
Assert-NativeMockReject {Get-NativeArguments ($root+'\..\other') 'fx' $node} 'NATIVE_ABSOLUTE_PATH'
# Узел проверяется настоящим существующим portable predicate, без доступа к HKCU.
foreach ($bad in 'ru/cashprediction/session/fx','ru/cashprediction/selftest/not-a-uuid') {
    $message=$null;try {Get-NativeArguments $root 'fx' $bad | Out-Null} catch {$message=$_.Exception.Message}
    Assert-NativeMock ($null -ne $message) 'REGISTRY_ISOLATION'
}
Assert-NativeEndpoint 'http://127.0.0.1:12345/update.json'
foreach ($bad in 'http://localhost:12345/update.json','https://127.0.0.1:12345/update.json','http://127.0.0.1:0/update.json',
    'http://127.0.0.1:65536/update.json','http://127.0.0.1:12345/other','http://127.0.0.1:12345/update.json?cache=1',
    'http://user@127.0.0.1:12345/update.json','http://127.0.0.1:12345/update.json#other') {
    Assert-NativeMockReject {Assert-NativeEndpoint $bad} 'NATIVE_ENDPOINT'
}
Assert-NativeMock ((Get-NativeUtcTicks '2026-10-04T02:00:00.123456789Z') -eq (Get-NativeUtcTicks '2026-10-04T02:00:00.1234567Z')) 'JAVA_INSTANT_NANOS'
Assert-NativeMockReject {Get-NativeUtcTicks '2026-10-04T02:00:00+01:00'} 'NATIVE_UTC'

$owned=Join-Path ([IO.Path]::GetTempPath()) '11111111-1111-1111-1111-111111111111'
$birth=[datetime]::new(2026,10,4,2,0,0,[DateTimeKind]::Utc)
$identity=[pscustomobject]@{ProcessId=731;StartedAtTicks=$birth.Ticks}
$receipt=[pscustomobject]@{schemaVersion=1;pid=731;startedUtc='2026-10-04T02:00:00.100000001Z';mode='VALID';
    manifestUri='http://127.0.0.1:12345/update.json';stopPath=(Join-Path $owned 'server.stop');cancelPath=(Join-Path $owned 'server.cancel');
    artifactDir=$root;manifestSha256=('a'*64);fixtures=@()}
Assert-NativeServerReceipt $receipt $owned $identity ('a'*64)
foreach ($field in 'pid','manifestSha256','stopPath','cancelPath','manifestUri','startedUtc') {
    $bad=Copy-NativeMock $receipt
    switch ($field) {'pid' {$bad.pid++} 'manifestSha256' {$bad.manifestSha256='b'*64} 'startedUtc' {$bad.startedUtc='2026-10-04T01:59:59Z'} default {$bad.$field='foreign'}}
    Assert-NativeMockReject {Assert-NativeServerReceipt $bad $owned $identity ('a'*64)} 'NATIVE_SERVER_RECEIPT'
}

# START/FINISH mock моделирует точные wire counters; никакого реального listener здесь нет.
$start=[pscustomobject]@{event='START';id=1L;path='/update.json';query='';method='GET';utc='2026-10-04T02:00:00Z';nanos=0L}
$finish=[pscustomobject]@{event='FINISH';id=1L;path='/update.json';query='';method='GET';startedUtc='2026-10-04T02:00:00Z';
    finishedUtc='2026-10-04T02:00:01Z';startedNanos=0L;finishedNanos=1000000000L;status=200;bytes=1L;outcome='COMPLETE'}
$stats=[pscustomobject]@{schemaVersion=1;closed=$true;healthy=$true;cancelled=$false;active=0L;requests=1L;completed=1L;bytes=1L;counts=[pscustomobject]@{'/update.json'=1L}}
Assert-NativeHttp $stats @($start,$finish)
foreach ($field in 'closed','healthy','active','requests','completed') {
    $bad=Copy-NativeMock $stats
    switch ($field) {'closed' {$bad.closed=$false} 'healthy' {$bad.healthy=$false} 'active' {$bad.active=1L} default {$bad.$field=2L}}
    Assert-NativeMockReject {Assert-NativeHttp $bad @($start,$finish)} 'NATIVE_HTTP_STATS'
}
Assert-NativeMockReject {Assert-NativeHttp $stats @($finish,$start)} 'NATIVE_HTTP_FINISH'
Assert-NativeMockReject {Assert-NativeHttp $stats @($start,$start,$finish)} 'NATIVE_HTTP_START'
Assert-NativeMockReject {Assert-NativeHttp $stats @($start,$finish,$finish)} 'NATIVE_HTTP_FINISH'
$bad=Copy-NativeMock $finish;$bad.path='/foreign'
Assert-NativeMockReject {Assert-NativeHttp $stats @($start,$bad)} 'NATIVE_HTTP_FINISH'
$bad=Copy-NativeMock $stats;$bad.bytes=2L
Assert-NativeMockReject {Assert-NativeHttp $bad @($start,$finish)} 'NATIVE_HTTP_COUNTERS'
$bad=Copy-NativeMock $stats;$bad.counts.'/update.json'=2L
Assert-NativeMockReject {Assert-NativeHttp $bad @($start,$finish)} 'NATIVE_HTTP_COUNTERS'
Assert-NativeMockReject {Assert-NativeHttp $stats @($start)} 'NATIVE_HTTP_COUNTERS'
foreach ($field in 'active','requests','completed','bytes') {
    $bad=Copy-NativeMock $stats;$bad.$field=[string]$bad.$field
    Assert-NativeMockReject {Assert-NativeHttp $bad @($start,$finish)} 'NATIVE_HTTP_STATS'
}

$delta='CashPrediction.from-1.cpdelta'
$metadata=Copy-NativeMock $finish;$metadata.bytes=300L
$patch=Copy-NativeMock $finish;$patch.path='/'+$delta;$patch.bytes=400L
$full=Copy-NativeMock $finish;$full.path='/CashPrediction-portable.zip';$full.bytes=500L
$http=[pscustomobject]@{events=@($metadata,$patch)}
Assert-NativeScenarioHttp 'delta' $http $delta 400 500
Assert-NativeScenarioHttp 'corrupt-delta-full' ([pscustomobject]@{events=@($metadata,$patch,$full)}) $delta 400 500
Assert-NativeMockReject {Assert-NativeScenarioHttp 'delta' ([pscustomobject]@{events=@($metadata,$patch,$full)}) $delta 400 500} 'NATIVE_HTTP_UNEXPECTED_FULL'
Assert-NativeMockReject {Assert-NativeScenarioHttp 'corrupt-delta-full' $http $delta 400 500} 'NATIVE_HTTP_FALLBACK'
Assert-NativeMockReject {Assert-NativeScenarioHttp 'delta' ([pscustomobject]@{events=@($metadata)}) $delta 400 500} 'NATIVE_HTTP_POSITIVE'
$retry1=Copy-NativeMock $metadata;$retry1.status=503;$retry1.bytes=0L
Assert-NativeScenarioHttp 'once-three-attempts' ([pscustomobject]@{events=@($retry1,$retry1,$metadata,$patch)}) $delta 400 500
Assert-NativeMockReject {Assert-NativeScenarioHttp 'once-three-attempts' ([pscustomobject]@{events=@($metadata,$metadata,$metadata,$patch)}) $delta 400 500} 'NATIVE_HTTP_RETRY'
Assert-NativeScenarioHttp 'malformed' ([pscustomobject]@{events=@($finish,$finish,$finish)}) $delta 400 500
Assert-NativeMockReject {Assert-NativeScenarioHttp 'malformed' ([pscustomobject]@{events=@($metadata,$metadata,$metadata)}) $delta 400 500} 'NATIVE_HTTP_MALFORMED'
Assert-NativeMockReject {Assert-NativeScenarioHttp 'offline' $http $delta 400 500} 'NATIVE_HTTP_NEGATIVE'

# Full-only отрицательный transport требует настоящий полный ответ и запрещает дельту/повторы.
$fullHttp=[pscustomobject]@{events=@($metadata,$full)}
Assert-NativeScenarioHttp 'corrupt-full-retain' $fullHttp $delta 400 500
foreach ($events in @(@($metadata),@($metadata,$patch,$full),@($metadata,$full,$full),@($metadata,$metadata,$full))) {
    Assert-NativeMockReject {Assert-NativeScenarioHttp 'corrupt-full-retain' ([pscustomobject]@{events=$events}) $delta 400 500} 'NATIVE_HTTP_CORRUPT_FULL'
}
foreach ($field in 'status','bytes') {
    $bad=Copy-NativeMock $full;$bad.$field=0L
    Assert-NativeMockReject {Assert-NativeScenarioHttp 'corrupt-full-retain' ([pscustomobject]@{events=@($metadata,$bad)}) $delta 400 500} 'NATIVE_HTTP_CORRUPT_FULL'
}
$target=[pscustomobject][ordered]@{schemaVersion=2;releaseNumber=3;commitSha=('c'*40);version='1.0';publishedAtUtc='2026-10-04T02:00:00Z';
    assetName='CashPrediction-portable.zip';sizeBytes=500L;sha256=('a'*64);treeSha256=('b'*64);
    files=@([pscustomobject]@{path='app/core.jar';sizeBytes=1L;sha256=('d'*64);readOnly=$false});
    deltaPatches=@([pscustomobject]@{assetName=$delta})}
$fullManifest=New-NativeFullOnlyManifest $target
Assert-NativeMock ($fullManifest.deltaPatches.Count -eq 0 -and $target.deltaPatches.Count -eq 1) 'FULL_ONLY_PRESERVES_FROZEN_INPUT'
Assert-NativeFullOnlyManifest $fullManifest $target
$bad=Copy-NativeMock $fullManifest;$bad.deltaPatches=$target.deltaPatches
Assert-NativeMockReject {Assert-NativeFullOnlyManifest $bad $target} 'NATIVE_FULL_ONLY_DELTAS'
foreach ($field in 'releaseNumber','commitSha','sha256','treeSha256','files','publishedAtUtc') {
    $bad=Copy-NativeMock $fullManifest
    switch ($field) {'releaseNumber' {$bad.releaseNumber++} 'files' {$bad.files[0].sizeBytes++} default {$bad.$field='foreign'}}
    Assert-NativeMockReject {Assert-NativeFullOnlyManifest $bad $target} 'NATIVE_FULL_ONLY_IDENTITY'
}
$fullLife=[pscustomobject]@{artifactDir=$root;manifestSha256=('e'*64)}
$fullServer=[pscustomobject]@{mode='CORRUPTFULL';artifactDir=$root;manifestSha256=('e'*64);
    fixtures=@([pscustomobject]@{path='/update.json';sha256=('e'*64);size=300L},
        [pscustomobject]@{path='/CashPrediction-portable.zip';sha256=$target.sha256;size=$target.sizeBytes})}
Assert-NativeFullServer $fullServer $fullLife $target
foreach ($field in 'mode','artifactDir','manifestSha256','fixtures') {
    $bad=Copy-NativeMock $fullServer
    if ($field -ceq 'fixtures') {$bad.fixtures+=@([pscustomobject]@{path='/'+$delta;sha256=('a'*64);size=400L})} else {$bad.$field='foreign'}
    Assert-NativeMockReject {Assert-NativeFullServer $bad $fullLife $target} 'NATIVE_FULL_SERVER_FIXTURE'
}
foreach ($field in 'sha256','size') {
    $bad=Copy-NativeMock $fullServer;$bad.fixtures[1].$field=$(if ($field -ceq 'size') {499L} else {'f'*64})
    Assert-NativeMockReject {Assert-NativeFullServer $bad $fullLife $target} 'NATIVE_FULL_SERVER_FIXTURE'
}

# Данные guard проверяются без ожидания: mock samples никогда не доказывают native отсутствие polling.
$snapshot=[pscustomobject]@{stats=(Copy-NativeMock $stats);trace='START/FINISH';traceBytes=12L;traceSha256=('a'*64)}
$snapshot.stats.closed=$false
Assert-NativeNonPollingSnapshot $snapshot (Copy-NativeMock $snapshot)
foreach ($field in 'trace','traceBytes','traceSha256','stats') {
    $bad=Copy-NativeMock $snapshot
    switch ($field) {'stats' {$bad.stats.requests++} 'traceBytes' {$bad.traceBytes++} default {$bad.$field='changed'}}
    Assert-NativeMockReject {Assert-NativeNonPollingSnapshot $snapshot $bad} 'NATIVE_NONPOLLING_CHANGED'
}
$observation=[pscustomobject]@{scope='LIVE_HTTP_AFTER_READY';status='PASS';windowMillis=5000;elapsedMillis=5001L;
    samples=@([pscustomobject]@{elapsedMillis=0L;http=$snapshot},[pscustomobject]@{elapsedMillis=5000L;http=(Copy-NativeMock $snapshot)})}
Assert-NativeNonPollingReceipt $observation
foreach ($field in 'scope','status','windowMillis','elapsedMillis','samples') {
    $bad=Copy-NativeMock $observation
    switch ($field) {'samples' {$bad.samples=@()} 'windowMillis' {$bad.windowMillis=1} 'elapsedMillis' {$bad.elapsedMillis=4999L} default {$bad.$field='unit-sleep-only'}}
    Assert-NativeMockReject {Assert-NativeNonPollingReceipt $bad} 'NATIVE_NONPOLLING_WINDOW'
}
$bad=Copy-NativeMock $observation;$bad.samples[1].elapsedMillis=4999L
Assert-NativeMockReject {Assert-NativeNonPollingReceipt $bad} 'NATIVE_NONPOLLING_WINDOW'
$bad=Copy-NativeMock $observation;$bad.samples=@($bad.samples[0],$bad.samples[0],$bad.samples[1])
Assert-NativeMockReject {Assert-NativeNonPollingReceipt $bad} 'NATIVE_NONPOLLING_SAMPLE_ORDER'
$bad=Copy-NativeMock $observation;$bad.samples[1].http.stats.requests++
Assert-NativeMockReject {Assert-NativeNonPollingReceipt $bad} 'NATIVE_NONPOLLING_CHANGED'

# Изолированные doubles проверяют old identity, отсутствие Ready/установки и следов скачивания.
& {
    $script:retainResidue='';$script:retainPhase=$false;$script:retainWrongVersion=$false;$script:retainWrongTree=$false
    function Assert-ColdTree {if ($script:retainWrongTree) {throw 'COLD_TREE'}}
    function Get-ColdVersion {return [pscustomobject]@{releaseNumber=$(if ($script:retainWrongVersion) {2} else {1});commitSha=('a'*40)}}
    function Test-Path([string]$LiteralPath) {
        $path=$LiteralPath.Replace('\','/')
        return ($script:retainResidue -ne '' -and $path.EndsWith('/'+$script:retainResidue)) -or ($script:retainPhase -and $path.EndsWith('/update-log.md'))
    }
    function Get-Content {return '- 2026-10-04T02:00:00Z PHASE_INSTALLING'}
    $base=[pscustomobject]@{releaseNumber=1;commitSha=('a'*40)}
    Assert-NativeFullRetained $root $base
    foreach ($residue in 'Ready','PreviousReady','Staging','DeltaBase','payload.download','prepare-journal.json','install-journal.json','completed-journal.json','last-install.json') {
        $script:retainResidue=$residue
        Assert-NativeMockReject {Assert-NativeFullRetained $root $base} 'NATIVE_FULL_RETAIN_RESIDUE'
    }
    $script:retainResidue='';$script:retainPhase=$true
    Assert-NativeMockReject {Assert-NativeFullRetained $root $base} 'NATIVE_FULL_RETAIN_INSTALL_PHASE'
    $script:retainPhase=$false;$script:retainWrongVersion=$true
    Assert-NativeMockReject {Assert-NativeFullRetained $root $base} 'NATIVE_FULL_RETAIN_VERSION'
    $script:retainWrongVersion=$false;$script:retainWrongTree=$true
    Assert-NativeMockReject {Assert-NativeFullRetained $root $base} 'COLD_TREE'
}

# Native witness guard остаётся существующим cold guard, а новый exit не принимает CLI-only результат.
$nativeExe=Join-Path $root 'CashPrediction.exe'
$ui=[pscustomobject]@{pid=731;startedAtTicks=$birth.Ticks;executablePath=$nativeExe;modules=@((Join-Path $root 'runtime/bin/server/jvm.dll'));
    witness=[pscustomobject]@{kind='native-window';handle=123L;title='CashPrediction - mock'};
    lease=[pscustomobject]@{schemaVersion=1;leaseId='11111111-1111-1111-1111-111111111111';pid=731;startedAtEpochMillis=([DateTimeOffset]$birth).ToUnixTimeMilliseconds();installationRoot=$root;client='fx'};
    commandLine=('"'+$nativeExe+'" --test-api --home "'+$root+'" --registry-node '+$node);args=@('--test-api','--home',$root,'--registry-node',$node);observedAt=$birth.AddSeconds(1).ToString('o')}
$exit=[pscustomobject]@{pid=731;startedAtTicks=$birth.Ticks;exitCode=0;exitedUtc=$birth.AddSeconds(2).ToString('o');remainingClients=0;kind='ordinary-no-restart'}
Assert-NativeExitReceipt $exit $ui $root 'fx'
$unsignedExit=Copy-NativeMock $exit;$unsignedExit.pid=[uint32]731
Assert-NativeMockReject {Assert-NativeExitReceipt $unsignedExit $ui $root 'fx'} 'NATIVE_EXIT_RECEIPT'
$unsignedUi=Copy-NativeMock $ui;$unsignedUi.lease.pid=[uint32]731
Assert-NativeMockReject {Assert-NativeExitReceipt $exit $unsignedUi $root 'fx'} 'COLD_REPORT_LAUNCH_IDENTITY'
foreach ($field in 'pid','startedAtTicks','exitCode','remainingClients','kind','exitedUtc') {
    $bad=Copy-NativeMock $exit
    switch ($field) {'kind' {$bad.kind='CLI_ONLY'} 'exitedUtc' {$bad.exitedUtc=$birth.AddSeconds(-1).ToString('o')} default {$bad.$field++}}
    Assert-NativeMockReject {Assert-NativeExitReceipt $bad $ui $root 'fx'} 'NATIVE_EXIT_RECEIPT'
}
$bad=Copy-NativeMock $ui;$bad.witness.handle=0L
Assert-NativeMockReject {Assert-NativeExitReceipt $exit $bad $root 'fx'} 'COLD_REPORT_LAUNCH_IDENTITY'

# Закрывается только enabled главное окно того же native PID и witness HWND, без popup обхода.
$window=[pscustomobject]@{handle=123L;pid=731L;exists=$true;visible=$true;enabled=$true;processMainHandle=123L}
Assert-NativeMainWindow $window $ui
foreach ($field in 'handle','pid','exists','visible','processMainHandle') {
    $bad=Copy-NativeMock $window
    if ($field -in @('exists','visible')) {$bad.$field=$false} else {$bad.$field++}
    Assert-NativeMockReject {Assert-NativeMainWindow $bad $ui} 'NATIVE_NORMAL_CLOSE_WINDOW_IDENTITY'
}
foreach ($value in @($false,'true')) {
    $bad=Copy-NativeMock $window;$bad.enabled=$value
    Assert-NativeMockReject {Assert-NativeMainWindow $bad $ui} 'NATIVE_NORMAL_CLOSE_OWNER_DISABLED'
}
# Source bridge тестируется как контракт: здесь он никогда не компилируется/не запускает Java.
$domainSource=Get-NativeDomainSessionSource
foreach ($api in 'Plan.empty','PlanRepository(home)','repository.save(plan, file)','repository.load(file, today)',
    'AppSettings.defaults().withPlanOpened(file.toString())','SettingsMarkdown.save(settingsFile, settings)',
    'SettingsMarkdown.load(settingsFile).equals(settings)','loaded.hasWarnings()') {
    Assert-NativeMock ($domainSource.Contains($api)) 'REAL_FROZEN_DOMAIN_API_REQUIRED'
}
Assert-NativeMock (-not $domainSource.Contains('java.util.prefs') -and -not $domainSource.Contains('ProcessBuilder') -and
    -not $domainSource.Contains('Files.writeString')) 'NO_FAKE_DOMAIN_BYTES_OR_REGISTRY'

# Реальная Unicode файловая fixture проверяет лишь transport и pinned byte-copy, без запуска Java.
function Test-NativeUnicodeSeedFixture {
    $directory=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    $unicodeRoot=Join-Path $directory 'Мои программы Δ 测试'
    $evidence=Join-Path $directory 'evidence';$wrong=Join-Path $directory 'wrong-pin';$corrupt=Join-Path $directory 'corrupt-copy'
    $core=Join-Path $unicodeRoot 'ядро-Δ.jar';$copy=Join-Path $evidence 'domain-core.jar'
    $source=Join-Path $evidence 'NativeDomainSession.java'
    try {
        foreach ($path in @($directory,$unicodeRoot,$evidence,$wrong,$corrupt)) {[void][IO.Directory]::CreateDirectory($path)}
        # Это mock содержимое для проверки копирования, не исполняемый JAR и не native evidence.
        $bytes=[Text.Encoding]::UTF8.GetBytes('unicode-seed-byte-copy-contract')
        [IO.File]::WriteAllBytes($core,$bytes)
        $hash=(Get-FileHash -LiteralPath $core).Hash.ToLowerInvariant()
        $actual=Copy-NativeDomainCore $core $hash $evidence
        Assert-NativeMock ($actual -ceq $copy -and (Get-FileHash -LiteralPath $copy).Hash.ToLowerInvariant() -ceq $hash) 'UNICODE_CORE_COPY_SAME_PIN'
        Assert-NativeMock ([Convert]::ToBase64String([IO.File]::ReadAllBytes($core)) -ceq
            [Convert]::ToBase64String([IO.File]::ReadAllBytes($copy))) 'UNICODE_CORE_COPY_EXACT_BYTES'
        foreach ($rootVariant in @((Join-Path $directory 'plain'),(Join-Path $directory 'Мои программы'),$unicodeRoot)) {
            $arguments=@(Get-NativeDomainBridgeArguments $rootVariant $copy $source)
            Assert-NativeMock ($arguments.Count -eq 5 -and $arguments[1] -ceq '-cp' -and $arguments[2] -ceq $copy -and $arguments[3] -ceq $source) 'ASCII_SEED_CLASSPATH_SOURCE'
            Assert-NativeMock (@($arguments | Where-Object {$_ -cmatch '[^\x20-\x7e]'}).Count -eq 0) 'ALL_SEED_ARGUMENTS_ASCII'
            $decoded=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($arguments[4]))
            Assert-NativeMock ($decoded -ceq $rootVariant) 'ACTUAL_NATIVE_ROOT_BASE64_ROUNDTRIP'
        }
        Assert-NativeMockReject {Get-NativeDomainBridgeArguments $unicodeRoot $core $source} 'NATIVE_DOMAIN_ASCII_PATH_REQUIRED'
        Assert-NativeMockReject {Get-NativeDomainBridgeArguments $unicodeRoot $copy (Join-Path $unicodeRoot 'NativeDomainSession.java')} 'NATIVE_DOMAIN_ASCII_PATH_REQUIRED'
        Assert-NativeMockReject {Copy-NativeDomainCore $core $hash $unicodeRoot} 'NATIVE_DOMAIN_ASCII_PATH_REQUIRED'
        Assert-NativeMockReject {Copy-NativeDomainCore $core $hash $evidence} 'NATIVE_DOMAIN_CORE_COPY_EXISTS'
        Assert-NativeMockReject {Copy-NativeDomainCore $core ('0'*64) $wrong} 'NATIVE_DOMAIN_CORE_PIN'
        Assert-NativeMock (-not [IO.File]::Exists((Join-Path $wrong 'domain-core.jar'))) 'BAD_PIN_NO_COPY'
        # Пост-копирование проверяет destination digest, не только исходный pin.
        & {
            function Get-FileHash([string]$LiteralPath) {
                $result=Microsoft.PowerShell.Utility\Get-FileHash -LiteralPath $LiteralPath
                if ($LiteralPath -ceq (Join-Path $corrupt 'domain-core.jar')) {return [pscustomobject]@{Hash=('f'*64)}}
                return $result
            }
            Assert-NativeMockReject {Copy-NativeDomainCore $core $hash $corrupt} 'NATIVE_DOMAIN_CORE_PIN'
        }
        Assert-NativeMock ((Get-FileHash -LiteralPath $core).Hash.ToLowerInvariant() -ceq $hash) 'FROZEN_UNICODE_CORE_UNCHANGED'
    } finally {
        # Только явные пути собственных файлов и пустых каталогов, не рекурсивная очистка.
        foreach ($path in @($core,$copy,(Join-Path $corrupt 'domain-core.jar'))) {if ([IO.File]::Exists($path)) {[IO.File]::Delete($path)}}
        foreach ($path in @($unicodeRoot,$evidence,$wrong,$corrupt,$directory)) {if ([IO.Directory]::Exists($path)) {[IO.Directory]::Delete($path)}}
    }
}
Test-NativeUnicodeSeedFixture

# Реальный writer удерживает write handle и дописывает JSONL частями в другом runspace, без exe/Java.
function Test-NativeTraceFileFixture($Start,$Finish,$Stats) {
    $directory=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    [void][IO.Directory]::CreateDirectory($directory)
    $tracePath=Join-Path $directory 'server-trace.jsonl';$statsPath=Join-Path $directory 'server-stats.json'
    $invalidPath=Join-Path $directory 'invalid.jsonl';$utf8=[Text.UTF8Encoding]::new($false,$true)
    $first=(ConvertTo-Json -InputObject $Start -Compress)+"`n"+(ConvertTo-Json -InputObject $Finish -Compress)+"`n"
    $secondStart=Copy-NativeMock $Start;$secondStart.id=2L;$secondStart.nanos=2000000000L
    $secondFinish=Copy-NativeMock $Finish;$secondFinish.id=2L;$secondFinish.startedNanos=2000000000L;$secondFinish.finishedNanos=3000000000L
    $second=(ConvertTo-Json -InputObject $secondStart -Compress)+"`n"+(ConvertTo-Json -InputObject $secondFinish -Compress)+"`n"
    $split=[int]($second.IndexOf("`n")/2)
    $ready=[Threading.ManualResetEventSlim]::new($false);$append=[Threading.ManualResetEventSlim]::new($false)
    $partial=[Threading.ManualResetEventSlim]::new($false);$complete=[Threading.ManualResetEventSlim]::new($false)
    $done=[Threading.ManualResetEventSlim]::new($false);$release=[Threading.ManualResetEventSlim]::new($false)
    $writer=[powershell]::Create();$invocation=$null
    try {
        $live=Copy-NativeMock $Stats;$live.closed=$false
        [IO.File]::WriteAllText($statsPath,(ConvertTo-Json -InputObject $live -Depth 64),$utf8)
        $null=$writer.AddScript({
            param($Path,$First,$Second,$Split,$Ready,$Append,$Partial,$Complete,$Done,$Release)
            $stream=[IO.FileStream]::new($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,
                ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            try {
                $stream.Write($First,0,$First.Length);$stream.Flush($true);$Ready.Set()
                if (-not $Append.Wait(10000)) {throw 'APPEND_FIXTURE_START_TIMEOUT'}
                $stream.Write($Second,0,$Split);$stream.Flush($true);$Partial.Set()
                if (-not $Complete.Wait(10000)) {throw 'APPEND_FIXTURE_COMPLETE_TIMEOUT'}
                $stream.Write($Second,$Split,$Second.Length-$Split);$stream.Flush($true);$Done.Set()
                if (-not $Release.Wait(10000)) {throw 'APPEND_FIXTURE_RELEASE_TIMEOUT'}
            } finally {$stream.Dispose()}
        }).AddArgument($tracePath).AddArgument($utf8.GetBytes($first)).AddArgument($utf8.GetBytes($second)).AddArgument($split).
            AddArgument($ready).AddArgument($append).AddArgument($partial).AddArgument($complete).AddArgument($done).AddArgument($release)
        $invocation=$writer.BeginInvoke()
        Assert-NativeMock ($ready.Wait(10000)) 'APPEND_WRITER_OPEN'
        # Контроль воспроизводит именно ReadAllBytes sharing violation при открытом writer.
        $sharingFailure=$false
        try {[void][IO.File]::ReadAllBytes($tracePath)} catch {$sharingFailure=$_.Exception.GetBaseException() -is [IO.IOException]}
        Assert-NativeMock $sharingFailure 'ORIGINAL_READALLBYTES_SHARING_FAILURE'
        $server=[pscustomobject]@{owned=$directory}
        $before=Read-NativeLiveHttp $server
        Assert-NativeMock ($before.events.Count -eq 2 -and $before.trace -ceq $first) 'SHARED_WRITER_COMPLETE_SNAPSHOT'
        $append.Set();Assert-NativeMock ($partial.Wait(10000)) 'CONCURRENT_PARTIAL_APPEND'
        Assert-NativeMockReject {Read-NativeTraceSnapshot $tracePath} 'NATIVE_TRACE_INCOMPLETE'
        Assert-NativeMockReject {Read-NativeLiveHttp $server} 'NATIVE_TRACE_INCOMPLETE'
        $complete.Set();Assert-NativeMock ($done.Wait(10000)) 'CONCURRENT_COMPLETE_APPEND'
        # Stale реальные counters нельзя заменять числами, вычисленными из trace.
        Assert-NativeMockReject {Read-NativeLiveHttp $server} 'NATIVE_HTTP_COUNTERS'
        $live.requests=2L;$live.completed=2L;$live.bytes=2L;$live.counts.'/update.json'=2L
        [IO.File]::WriteAllText($statsPath,(ConvertTo-Json -InputObject $live -Depth 64),$utf8)
        $after=Read-NativeLiveHttp $server
        Assert-NativeMock ($after.events.Count -eq 4 -and $after.trace -ceq ($first+$second)) 'COMPLETE_APPEND_EXACT_BYTES'
        Assert-NativeMockReject {Assert-NativeNonPollingSnapshot $before $after} 'NATIVE_NONPOLLING_CHANGED'
        Assert-NativeMock (-not $invocation.IsCompleted) 'WRITER_STILL_OPEN_DURING_READS'
        $release.Set();Assert-NativeMock ($invocation.AsyncWaitHandle.WaitOne(10000)) 'APPEND_WRITER_FINISHED'
        [void]$writer.EndInvoke($invocation);$invocation=$null
        Assert-NativeMock ($writer.Streams.Error.Count -eq 0) 'APPEND_WRITER_NO_ERRORS'
        foreach ($text in @(('{broken}'+"`n"),('{}'+"`n"),('[]'+"`n"),($first+"`n"))) {
            [IO.File]::WriteAllText($invalidPath,$text,$utf8)
            Assert-NativeMockReject {Read-NativeTraceSnapshot $invalidPath} 'NATIVE_TRACE_RECORD'
        }
        [IO.File]::WriteAllText($invalidPath,'{"event":"START"}',$utf8)
        Assert-NativeMockReject {Read-NativeTraceSnapshot $invalidPath} 'NATIVE_TRACE_INCOMPLETE'
        [IO.File]::WriteAllBytes($invalidPath,[byte[]]@(0xff,10))
        Assert-NativeMockReject {Read-NativeTraceSnapshot $invalidPath} 'NATIVE_TRACE_UTF8'
        [IO.File]::WriteAllText($invalidPath,("{}`n"*4097),$utf8)
        Assert-NativeMockReject {Read-NativeTraceSnapshot $invalidPath} 'NATIVE_TRACE_LIMIT'
        $oversize=[IO.FileStream]::new($invalidPath,[IO.FileMode]::Open,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try {$oversize.SetLength(8388609)} finally {$oversize.Dispose()}
        Assert-NativeMockReject {Read-NativeTraceSnapshot $invalidPath} 'NATIVE_TRACE_LIMIT'
    } finally {
        $append.Set();$complete.Set();$release.Set()
        if ($null -ne $invocation) {
            if (-not $invocation.AsyncWaitHandle.WaitOne(10000)) {$writer.Stop()}
            try {[void]$writer.EndInvoke($invocation)} catch {}
        }
        $writer.Dispose()
        foreach ($signal in @($ready,$append,$partial,$complete,$done,$release)) {$signal.Dispose()}
        # Только три явных файла собственного UUID; никаких recursive cleanup или чужих run-каталогов.
        foreach ($path in @($tracePath,$statsPath,$invalidPath)) {if ([IO.File]::Exists($path)) {[IO.File]::Delete($path)}}
        [IO.Directory]::Delete($directory)
    }
}
Test-NativeTraceFileFixture $start $finish $stats

# Виртуальные pin файлы: ни class/native bytes, ни DLL/helper не создаются на диске.
$harness=Join-Path $root 'harness';$class=Join-Path $harness 'ru/cashprediction/parity/update/NativeUpdateServer.class'
$mockHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes('mock-contract-data'))).ToLowerInvariant()
function Assert-PortableTreeHasNoLinks {}
function Resolve-PortableSafetyPath([string]$Path) {return $Path}
function Test-Path([string]$LiteralPath,[string]$PathType) {return $LiteralPath -in @($harness,$class)}
function Get-ChildItem([string]$LiteralPath,[switch]$Recurse,[switch]$File,[switch]$Force) {return [pscustomobject]@{FullName=$class;Length=18L}}
function Get-FileHash([string]$LiteralPath) {return [pscustomobject]@{Hash=$mockHash}}
$config=[pscustomobject]@{schemaVersion=1;artifactDir=$root;manifestSha256=('a'*64);harnessClasspath=$harness;
    harnessFiles=@([pscustomobject]@{path=$class;sha256=$mockHash})}
Assert-NativeLifecycleConfig $config
$bad=Copy-NativeMock $config;$bad.harnessFiles[0].sha256='0'*64
Assert-NativeMockReject {Assert-NativeLifecycleConfig $bad} 'NATIVE_HARNESS_PIN'
$bad=Copy-NativeMock $config;$bad.harnessFiles=@()
Assert-NativeMockReject {Assert-NativeLifecycleConfig $bad} 'NATIVE_HARNESS_SET'
$bad=Copy-NativeMock $config;$bad.harnessClasspath=$harness+';'+$harness
Assert-NativeMockReject {Assert-NativeLifecycleConfig $bad} 'NATIVE_HARNESS_LIMIT'
$bad=Copy-NativeMock $config;$bad | Add-Member unknown 'foreign'
Assert-NativeMockReject {Assert-NativeLifecycleConfig $bad} 'COLD_FIELDS'

# Структурный контроль: обычный exit не включает Kill; ядро/selftest не запускается через java -m.
$close=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Close-NativeNormally'},$true))[0].Extent.Text
Assert-NativeMock ($close.Contains('CloseMainWindow()') -and $close.Contains("type='closeMain'") -and -not $close.Contains('.Kill(')) 'ORDINARY_CLOSE_ONLY'
$cell=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativeCell'},$true))[0].Extent.Text
Assert-NativeMock ($cell.IndexOf('Close-NativeNormally') -lt $cell.IndexOf('Wait-NativeInstalled')) 'CLIENT_EXIT_BEFORE_INSTALL_OBSERVATION'
Assert-NativeMock ($cell.Contains('Get-ColdLauncherName') -and $cell.Contains('Connect-NativeClient')) 'REAL_NATIVE_WITNESS_REQUIRED'
Assert-NativeMock ($cell.IndexOf('Initialize-NativeDomainSession') -lt $cell.IndexOf('Get-NativeUserObject') -and
    $cell.IndexOf('Initialize-NativeDomainSession') -lt $cell.IndexOf('Start-NativeFixture')) 'DOMAIN_FIXTURE_BEFORE_BASELINE_AND_LAUNCH'
Assert-NativeMock ($close.Contains('Get-NativeMainWindow') -and $close.Contains('Assert-NativeMainWindow') -and
    $close.Contains('physical-close-window-') -and $close.Contains('NATIVE_NORMAL_CLOSE_REJECTED')) 'PHYSICAL_CLOSE_GATE_NOT_WEAKENED'
$domain=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Initialize-NativeDomainSession'},$true))[0].Extent.Text
Assert-NativeMock ($domain.Contains('Get-ColdVersion $Root') -and $domain.Contains('NATIVE_DOMAIN_INPUT_CHANGED') -and
    $domain.Contains('coreSha256') -and $domain.Contains('sourceSha256') -and $domain.Contains('domain-session.json')) 'FROZEN_CORE_AND_REAL_OUTPUT_PROVENANCE'
Assert-NativeMock ($domain.Contains('Copy-NativeDomainCore $core $coreSha $Evidence') -and
    $domain.Contains('Start-NativeOwned $Java $arguments $Evidence') -and $domain.Contains('bridgeCoreSha256')) 'ASCII_SEED_COPY_WORKDIR_PROVENANCE'
Assert-NativeMock ($cell.Contains('Start-NativeOwned (Join-Path $root (Get-ColdLauncherName $Row.client)) $arguments $root')) 'NATIVE_UNICODE_LAUNCH_ROOT_UNCHANGED'
Assert-NativeMock ($cell.Contains("'corrupt-full-retain' {'corruptfull'}") -and $cell.Contains('retainedEvidence') -and $cell.Contains('fixtureEvidence')) 'DISTINCT_FULL_RETAIN_EVIDENCE'
Assert-NativeMock ($cell.IndexOf('Observe-NativeNonPolling') -lt $cell.IndexOf('Close-NativeNormally')) 'LIVE_OBSERVATION_BEFORE_CLOSE'
$observe=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Observe-NativeNonPolling'},$true))[0].Extent.Text
Assert-NativeMock ($observe.Contains('Read-NativeLiveHttp') -and $observe.Contains('Stopwatch') -and $observe.Contains('HasExited') -and $observe.Contains('5000')) 'ACTUAL_TRACE_WINDOW_REQUIRED'
$maxCellsParameter=@($ast.ParamBlock.Parameters | Where-Object {$_.Name.VariablePath.UserPath -ceq 'MaxCells'})[0]
$maxCellsRange=@($maxCellsParameter.Attributes | Where-Object {$_.TypeName.Name -ceq 'ValidateRange'})[0]
Assert-NativeMock ($maxCellsRange.PositionalArguments[0].SafeGetValue() -eq 1 -and
    $maxCellsRange.PositionalArguments[1].SafeGetValue() -eq 612) 'EXPLICIT_FULL_MATRIX_BOUND_612'
Assert-NativeMock ($maxCellsParameter.DefaultValue.SafeGetValue() -eq 144) 'BOUNDED_DEFAULT_144_RETAINED'
Assert-NativeMock ($ast.Extent.Text.Contains("throw 'NATIVE_SIGNOFF_INCOMPLETE_UPDATE_EVIDENCE_MATRIX'") -and $ast.Extent.Text.Contains('$rows.Count -ne 612') -and $ast.Extent.Text.Contains('NATIVE_SIGNOFF_CURRENT_RESULTS_NOT_SEALED')) 'INCOMPLETE_SIGNOFF_STILL_REJECTED'
Write-Output "Native lifecycle contracts: PASS ($script:checks checks, including concurrent append and Unicode seed file fixtures; native/Java/GUI not executed; full S7 PENDING)."
