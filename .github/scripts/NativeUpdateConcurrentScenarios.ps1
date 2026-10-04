<#
.SYNOPSIS
Отдельные NEXT helpers для настоящих multi-client jpackage клеток.
.DESCRIPTION
Только определения. MAIN импортирует frozen lifecycle/cold/portable функции через AST
и подключает этот файл ПОСЛЕ завершения текущей очереди. Pins и canonical plan не меняются.
Invoke-NativeConcurrentScenario имеет сигнатуру Invoke-NativeCell. Его зависимости
те же, включая script:nativeTarget/nativeProject/nativeProfile и проверенные Life/Cold.
Два клиента: Row.client и следующий fx/swing/web циклически. Три: все три, начиная
с Row.client, плюс четвёртый клиент отдельной реальной копии для root isolation.
Kernel PID reuse не провоцируется: scoped stale lease с PID живой независимой JVM
и старым birth проверяет NATIVE_STALE_LEASE_BIRTH_MISMATCH. Объединённая клетка
остаётся PENDING до MAIN acceptance даже после actual наблюдения. Fixtures не подтверждают native PASS.
#>

# Закрытый список и предел времени проверяются до файлов и запуска процессов.
function Get-NativeConcurrentClients($Row,[int]$Timeout) {
    if ($Timeout -lt 30 -or $Timeout -gt 300 -or $Row.phase -cne 'SESSION' -or
        $Row.path -cnotin @('ascii','cyrillic','unicode') -or
        $Row.scenario -cnotin @('two-clients','three-clients-pid-root-isolation')) {throw 'CONCURRENT_ROW'}
    $order=@('fx','swing','web');$index=[Array]::IndexOf($order,[string]$Row.client)
    if ($index -lt 0) {throw 'CONCURRENT_CLIENT'}
    $count=if ($Row.scenario -ceq 'two-clients') {2} else {3}
    for ($i=0;$i -lt $count;$i++) {$order[($index+$i)%3]}
}

# Настоящий startup ответ проходит alreadyRunning/ordinary(false), не подменяя окно.
function Get-NativeConcurrentArguments([string]$Root,[string]$Client,[string]$Node,[bool]$Secondary) {
    $args=@(Get-NativeArguments $Root $Client $Node)
    if ($Secondary) {$args+=@('--selftest-recovery','already-ok')}
    return $args
}

# Живая личность проверяется удержанным дескриптором, birth/root/exe и дисковой lease.
function Assert-NativeConcurrentAlive($Entry) {
    $native=$Entry.native;$ui=$native.ui
    Assert-ColdUiReceipt $ui $Entry.root $Entry.client
    if ($native.uiProcess.HasExited) {throw 'CONCURRENT_CLIENT_EXITED'}
    $identity=[pscustomobject]@{ProcessId=$ui.pid;StartedAtTicks=$ui.startedAtTicks;
        ExecutablePath=$ui.executablePath;OwnedRoot=$Entry.root}
    Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $native.uiProcess $Entry.root) $Entry.root $ui.executablePath
    $leasePath=Join-Path $Entry.root ('CashMemory/Updates/processes/'+$ui.lease.leaseId+'.json')
    $lease=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $leasePath -Raw -Encoding utf8)
    if (-not (Test-ColdInventoryEqual $lease $ui.lease)) {throw 'CONCURRENT_LEASE_CHANGED'}
    if ($Entry.client -cne 'web') {Assert-NativeMainWindow (Get-NativeMainWindow $native) $ui}
    return [pscustomobject]@{ui=$ui;identity=$identity;lease=$lease;leasePath=$leasePath;observedUtc=[datetime]::UtcNow.ToString('o')}
}

# Все квитанции должны обозначать разные живые JVM, а не parent launcher или повтор PID.
function Assert-NativeConcurrentDistinct($Entries) {
    $pids=[Collections.Generic.HashSet[long]]::new();$leases=[Collections.Generic.HashSet[string]]::new()
    foreach ($entry in @($Entries)) {
        if (-not $pids.Add([long]$entry.native.ui.pid) -or -not $leases.Add([string]$entry.native.ui.lease.leaseId)) {throw 'CONCURRENT_DUPLICATE_IDENTITY'}
        [void](Assert-NativeConcurrentAlive $entry)
    }
}

# Frozen Connect сравнивает только обычные args; здесь сравнивается полный actual argv.
function Connect-NativeConcurrentClient($Entry,[datetime]$Started,[int]$Timeout) {
    $native=$Entry.native
    if ($native.web) {
        $native.lineTask=$native.process.StandardOutput.ReadLineAsync()
        Wait-NativeCondition {
            if ($native.lineTask.IsCompleted) {
                $line=$native.lineTask.GetAwaiter().GetResult()
                if ($null -eq $line) {throw 'CONCURRENT_WEB_EOF'}
                $native.prefix+=$line+"`n";if ($native.prefix.Length -gt 1048576) {throw 'CONCURRENT_OUTPUT_LIMIT'}
                if ($line -cmatch '^PARITY_URL (http://127\.0\.0\.1:[0-9]+/\?t=[A-Za-z0-9_-]+)$') {$native.webUrl=$Matches[1];return $true}
                $native.lineTask=$native.process.StandardOutput.ReadLineAsync()
            }
            return $false
        } $Timeout 'CONCURRENT_WEB_TIMEOUT'
        $native.stdout=$native.process.StandardOutput.ReadToEndAsync()
    }
    Wait-NativeCondition {
        $native.ui=Get-ColdUiReceipt $Entry.root $Entry.client $Started
        return $null -ne $native.ui
    } $Timeout 'CONCURRENT_UI_TIMEOUT'
    if (-not (Test-ColdInventoryEqual @($native.ui.args) @($Entry.args))) {throw 'CONCURRENT_ARGS_CHANGED'}
    $native.uiProcess=Open-PortableProcess ([int]$native.ui.pid)
    [void](Assert-NativeConcurrentAlive $Entry)
    if ($native.web) {
        $url=[uri]$native.webUrl
        if ($url.Port -ne $native.ui.witness.port) {throw 'CONCURRENT_WEB_OWNER'}
        $bootstrap=Invoke-RestMethod -Uri ($url.GetLeftPart([UriPartial]::Authority)+'/api/ui/bootstrap?tab=native-lifecycle&'+$url.Query.TrimStart('?')) -TimeoutSec 5 -MaximumRedirection 0
        if ($bootstrap.client -cne 'web' -or $bootstrap.testApi -ne $true) {throw 'CONCURRENT_WEB_BOOTSTRAP'}
    }
}

# Файловый prepare.lock отдельной копии исключает подготовку её Ready; это реальная блокировка.
function Open-NativeConcurrentPreparationBarrier([string]$Root) {
    Assert-PortableTreeHasNoLinks $Root
    $path=Join-Path $Root 'CashMemory/Updates/prepare.lock'
    $stream=[IO.FileStream]::new($path,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::ReadWrite)
    try {$stream.Lock(0,1);return $stream} catch {$stream.Dispose();throw}
}

# Освобождается только собственная блокировка, без изменения frozen файлов конфигурации.
function Close-NativeConcurrentPreparationBarrier($Barrier) {
    if ($null -ne $Barrier) {try {$Barrier.Unlock(0,1)} finally {$Barrier.Dispose()}}
}

# Wire соответствует ProcessLease/HelperFixture: старый birth, но PID настоящей отдельной JVM.
function New-NativeConcurrentStaleLease([string]$Root,$Peer) {
    [void](Assert-NativeAbsolute $Root)
    Assert-ColdUiReceipt $Peer.native.ui $Peer.root $Peer.client
    if ($Root.Equals($Peer.root,[StringComparison]::OrdinalIgnoreCase) -or
        $Root.StartsWith($Peer.root+'\',[StringComparison]::OrdinalIgnoreCase) -or
        $Peer.root.StartsWith($Root+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'CONCURRENT_STALE_LEASE_ROOT'}
    $actual=[long]$Peer.native.ui.lease.startedAtEpochMillis
    if ($actual -le 10000) {throw 'CONCURRENT_STALE_LEASE_BIRTH'}
    return [pscustomobject][ordered]@{schemaVersion=[int]1;leaseId=[guid]::NewGuid().ToString();
        pid=[long]$Peer.native.ui.pid;startedAtEpochMillis=[long]($actual-10000);installationRoot=$Root;client=$Peer.client}
}

# Не принимаются bool/string/uint32 в JSON; filename, чужой actual root и точный старый birth связаны.
function Assert-NativeConcurrentStaleLease($Lease,[string]$Root,$Peer,[string]$Path) {
    Assert-ColdKeys $Lease @('schemaVersion','leaseId','pid','startedAtEpochMillis','installationRoot','client')
    Assert-ColdUiReceipt $Peer.native.ui $Peer.root $Peer.client
    if ($Root.Equals($Peer.root,[StringComparison]::OrdinalIgnoreCase)) {throw 'CONCURRENT_STALE_LEASE_ROOT'}
    if (-not (Test-ColdInteger $Lease.schemaVersion 1) -or $Lease.schemaVersion -ne 1 -or
        $Lease.leaseId -isnot [string] -or $Lease.leaseId -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
        $Lease.leaseId -ceq $Peer.native.ui.lease.leaseId -or
        -not (Test-ColdInteger $Lease.pid 1) -or $Lease.pid -gt [int]::MaxValue -or $Lease.pid -ne $Peer.native.ui.pid -or
        -not (Test-ColdInteger $Lease.startedAtEpochMillis 1) -or
        $Lease.startedAtEpochMillis -ne ([long]$Peer.native.ui.lease.startedAtEpochMillis-10000) -or
        $Lease.installationRoot -cne $Root -or $Lease.client -cne $Peer.client -or
        $Path -cne (Join-Path $Root ('CashMemory/Updates/processes/'+$Lease.leaseId+'.json'))) {throw 'CONCURRENT_STALE_LEASE_WIRE'}
}

# Инъекция атомарна и только внутри собственного run; никакая настоящая lease не перезаписывается.
function Add-NativeConcurrentStaleLease([string]$Run,[string]$Root,$Peer,$Primary,[string]$Evidence) {
    [void](Assert-ColdOwnedRun $Run ([IO.Path]::GetTempPath()))
    foreach ($copy in @($Root,$Peer.root)) {
        [void](Assert-NativeAbsolute $copy)
        if (-not $copy.StartsWith($Run+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'CONCURRENT_STALE_LEASE_SCOPE'}
        Assert-PortableTreeHasNoLinks $copy
    }
    if (@($Primary).Count -ne 3) {throw 'CONCURRENT_STALE_LEASE_PRIMARY_COUNT'}
    foreach ($entry in @($Primary)) {if ($entry.root -cne $Root) {throw 'CONCURRENT_STALE_LEASE_SCOPE'}}
    Assert-NativeConcurrentDistinct @(@($Primary)+@($Peer))
    $peerBefore=Assert-NativeConcurrentAlive $Peer
    $lease=New-NativeConcurrentStaleLease $Root $Peer
    $path=Join-Path $Root ('CashMemory/Updates/processes/'+$lease.leaseId+'.json')
    Assert-NativeConcurrentStaleLease $lease $Root $Peer $path
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $lease -Depth 8 -Compress))
    $temporary=Join-Path $Root ('CashMemory/Updates/stale-'+$lease.leaseId+'.tmp')
    $stream=[IO.FileStream]::new($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
    [IO.File]::Move($temporary,$path)
    $readback=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $path -Raw -Encoding utf8)
    Assert-NativeConcurrentStaleLease $readback $Root $Peer $path
    $receipt=[pscustomobject][ordered]@{schemaVersion=1;scope='NATIVE_STALE_LEASE_BIRTH_MISMATCH';status='INJECTED';
        kernelPidReuseObserved=$false;leasePath=$path;lease=$readback;leaseSha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant();
        realPeerBefore=$peerBefore;realPeerAfter=(Assert-NativeConcurrentAlive $Peer);
        primary=@($Primary | ForEach-Object {$_.native.ui});injectedUtc=[datetime]::UtcNow.ToString('o');
        evidence=(Join-Path $Evidence 'stale-lease-injection.json')}
    Write-ColdJson $receipt.evidence $receipt
    return $receipt
}

# Завершение требует production cleanup stale lease и target install при всё ещё живом чужом root.
function Test-NativeConcurrentStaleInstallComplete([string]$Root,$Target,$Injection) {
    if (@(Get-CopyProcesses $Root).Count) {throw 'NATIVE_UNEXPECTED_RESTART'}
    $path=Join-Path $Root 'CashMemory/Updates/last-install.json'
    if (-not (Test-Path -LiteralPath $path)) {return $false}
    $last=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $path -Raw -Encoding utf8)
    if ($last.outcome -cne 'UPDATED' -or $last.targetCommitSha -cne $Target.commitSha) {throw 'NATIVE_INSTALL_OUTCOME'}
    return -not (Test-Path -LiteralPath $Injection.leasePath) -and
        -not (Test-Path -LiteralPath (Join-Path $Root 'CashMemory/Updates/install-journal.json')) -and
        -not (Test-Path -LiteralPath (Join-Path $Root 'CashMemory/Updates/completed-journal.json'))
}

# Ограниченный observer не вызывает store mock и не удаляет stale lease ради успешной установки.
function Observe-NativeConcurrentStaleInstall([string]$Root,$Base,$Target,$Exit,$Injection,$Peer,[string]$Evidence,[int]$Timeout) {
    $path=Join-Path $Evidence 'stale-lease-observation.json';$clock=[Diagnostics.Stopwatch]::StartNew()
    $samples=[Collections.Generic.List[object]]::new();$limit=[Math]::Min(60,$Timeout)
    $receipt=[ordered]@{schemaVersion=1;scope='NATIVE_STALE_LEASE_BIRTH_MISMATCH';status='PENDING';kernelPidReuseObserved=$false;
        acceptance='MAIN_PENDING';limitSeconds=$limit;injection=$Injection.evidence;finalExit=$Exit;productionHelper=$null;
        helperSha256=$null;peerAfterInstall=$null;elapsedMillis=0;samples=@();reason=$null}
    try {
        Assert-NativeConcurrentStaleLease $Injection.lease $Root $Peer $Injection.leasePath
        $helper=Join-Path $Root 'CashMemory/Updates/apply-update.ps1'
        $receipt.productionHelper=$helper;$receipt.helperSha256=(Get-FileHash -LiteralPath $helper).Hash.ToLowerInvariant()
        Copy-Item -LiteralPath $helper -Destination (Join-Path $Evidence 'production-apply-update.ps1')
        Wait-NativeCondition {
            $alive=Assert-NativeConcurrentAlive $Peer
            Assert-NativeConcurrentOldTree $Peer.root $Base
            if ($samples.Count -eq 0 -or $clock.ElapsedMilliseconds-$samples[-1].elapsedMillis -ge 250) {
                $phase=$null;$journal=Join-Path $Root 'CashMemory/Updates/install-journal.json'
                if (Test-Path -LiteralPath $journal) {$phase=(ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $journal -Raw -Encoding utf8)).phase}
                $samples.Add([pscustomobject]@{elapsedMillis=$clock.ElapsedMilliseconds;observedUtc=[datetime]::UtcNow.ToString('o');
                    peer=$alive;staleLeasePresent=[bool](Test-Path -LiteralPath $Injection.leasePath);phase=$phase})
            }
            return (Test-NativeConcurrentStaleInstallComplete $Root $Target $Injection)
        } $limit 'CONCURRENT_STALE_LEASE_INSTALL_TIMEOUT'
        # Сохранены штатные tree/version/phases/requests/leases/no-restart проверки frozen runner.
        Wait-NativeInstalled $Root $Target $Exit $Timeout
        $receipt.peerAfterInstall=Assert-NativeConcurrentAlive $Peer
        Assert-NativeConcurrentOldTree $Peer.root $Base
        $receipt.status='OBSERVED';$receipt.reason='PRODUCTION_INSTALL_COMPLETED_WITH_FOREIGN_PID_ALIVE_AND_STALE_LEASE_REMOVED'
    } catch {$receipt.status='FAIL';$receipt.reason=$_.Exception.Message;throw}
    finally {$receipt.elapsedMillis=$clock.ElapsedMilliseconds;$receipt.samples=@($samples.ToArray());Write-ColdJson $path $receipt}
    return $path
}

# Каждый controlled путь проверяется frozen guard своего реального клиента, без общего whitelist.
function Assert-NativeConcurrentControlled($Before,$After,$Entries,[string]$Root) {
    $allPaths=@('CashMemory/session-fx.xml','CashMemory/session-swing.xml','CashMemory/web-session.md',
        'CashMemory/web-session.plan.md','CashMemory/web-reconnect.md','CashMemory/web-reconnect-lock.md','CashMemory/lock.md')
    foreach ($item in @($Before)+@($After)) {if ($item.path -cnotin $allPaths) {throw 'CONCURRENT_CONTROLLED_PATH'}}
    $finished=[datetime]::UtcNow.Ticks
    for ($i=0;$i -lt @($Entries).Count;$i++) {
        $entry=@($Entries)[$i]
        $paths=@(if ($entry.client -ceq 'web') {'CashMemory/web-session.md','CashMemory/web-session.plan.md','CashMemory/web-reconnect.md','CashMemory/web-reconnect-lock.md'} else {'CashMemory/session-'+$entry.client+'.xml'})
        if ($i -eq 0) {$paths+=@('CashMemory/lock.md')}
        Assert-ColdControlledChanges @($Before | Where-Object {$_.path -cin $paths}) @($After | Where-Object {$_.path -cin $paths}) $entry.client $entry.native.ui $Root $finished
    }
    # Путь отсутствующего клиента может остаться только byte-identical.
    $absent=@('fx','swing','web' | Where-Object {$_ -cnotin @($Entries | ForEach-Object {$_.client})})
    foreach ($client in $absent) {
        $paths=if ($client -ceq 'web') {@('CashMemory/web-session.md','CashMemory/web-session.plan.md','CashMemory/web-reconnect.md','CashMemory/web-reconnect-lock.md')} else {@('CashMemory/session-'+$client+'.xml')}
        if (-not (Test-ColdInventoryEqual @($Before | Where-Object {$_.path -cin $paths}) @($After | Where-Object {$_.path -cin $paths}))) {throw 'CONCURRENT_ABSENT_CLIENT_CHANGED'}
    }
}

# Даже неизменный конечный hash не разрешает промежуточные destructive фазы при живой JVM.
function Assert-NativeConcurrentOldTree([string]$Root,$Base) {
    [void](Assert-ColdTree $Root $Base)
    $log=Join-Path $Root 'CashMemory/Updates/update-log.md'
    if ((Test-Path -LiteralPath $log) -and
        @(Get-Content -LiteralPath $log -Encoding utf8 | Where-Object {$_ -cmatch ' PHASE_(BOOTSTRAPPING|BACKING_UP|INSTALLING|VERIFYING|COMMITTED|ROLLING_BACK)$'}).Count) {throw 'CONCURRENT_REPLACEMENT_WHILE_ALIVE'}
    if (Test-Path -LiteralPath (Join-Path $Root 'CashMemory/Updates/last-install.json')) {throw 'CONCURRENT_EARLY_INSTALL'}
}

# Не unit sleep proof: каждое наблюдение заново читает lease, process identity и native tree.
function Observe-NativeConcurrentBarrier([string]$Root,$Base,$Survivors,[string]$Path) {
    if (@($Survivors).Count -lt 1) {throw 'CONCURRENT_NO_SURVIVOR'}
    foreach ($entry in @($Survivors)) {if ($entry.root -cne $Root) {throw 'CONCURRENT_FOREIGN_SURVIVOR'}}
    $clock=[Diagnostics.Stopwatch]::StartNew();$samples=[Collections.Generic.List[object]]::new()
    $receipt=[ordered]@{scope='LIVE_CLIENT_LEASE_TREE_BARRIER';windowMillis=3000;status='PENDING';elapsedMillis=0;samples=@()}
    try {
        do {
            $alive=@(foreach ($entry in @($Survivors)) {Assert-NativeConcurrentAlive $entry})
            Assert-NativeConcurrentOldTree $Root $Base
            # Повтор после hash связывает неизменное дерево с всё ещё живыми клиентами.
            foreach ($entry in @($Survivors)) {[void](Assert-NativeConcurrentAlive $entry)}
            $samples.Add([pscustomobject]@{elapsedMillis=$clock.ElapsedMilliseconds;observedUtc=[datetime]::UtcNow.ToString('o');alive=$alive;treeSha256=$Base.treeSha256})
            if ($samples.Count -ge 2 -and $clock.ElapsedMilliseconds -ge 3000) {break}
            Start-Sleep -Milliseconds 250
        } while ($true)
        $receipt.status='OBSERVED'
    } finally {$receipt.elapsedMillis=$clock.ElapsedMilliseconds;$receipt.samples=@($samples.ToArray());Write-ColdJson $Path $receipt}
    return $Path
}

# Промежуточный выход не выдаёт frozen remainingClients=0 и не завершает оставшихся клиентов.
function Close-NativeConcurrentMember($Entry,$Survivors,[int]$Timeout,[string]$Evidence) {
    [void](Assert-NativeConcurrentAlive $Entry)
    if (@($Survivors).Count -lt 1) {throw 'CONCURRENT_USE_FINAL_CLOSE'}
    foreach ($other in @($Survivors)) {
        if ($other.root -cne $Entry.root -or $other.native.ui.pid -eq $Entry.native.ui.pid) {throw 'CONCURRENT_CLOSE_SURVIVOR'}
        [void](Assert-NativeConcurrentAlive $other)
    }
    $native=$Entry.native
    if ($Entry.client -ceq 'web') {
        $url=[uri]$native.webUrl;$origin=$url.GetLeftPart([UriPartial]::Authority)
        $bootstrap=Invoke-RestMethod -Uri ($origin+'/api/ui/bootstrap?tab=native-lifecycle&'+$url.Query.TrimStart('?')) -TimeoutSec 5 -MaximumRedirection 0
        $body=ConvertTo-Json -Compress -InputObject @{tab='native-lifecycle';afterSeq=$bootstrap.seq;intent=@{type='closeMain'}}
        [void](Invoke-RestMethod -Uri ($origin+'/api/ui/intent'+$url.Query) -Method Post -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 5 -MaximumRedirection 0)
    } else {
        $window=Get-NativeMainWindow $native
        Write-ColdJson (Join-Path $Evidence ('physical-close-window-'+$native.ui.pid+'-'+$native.ui.startedAtTicks+'.json')) ([ordered]@{observedUtc=[datetime]::UtcNow.ToString('o');window=$window;ui=$native.ui})
        Assert-NativeMainWindow $window $native.ui
        if (-not $native.uiProcess.CloseMainWindow()) {throw 'NATIVE_NORMAL_CLOSE_REJECTED'}
    }
    Wait-NativeCondition {
        foreach ($other in @($Survivors)) {[void](Assert-NativeConcurrentAlive $other)}
        return $native.uiProcess.HasExited
    } $Timeout 'CONCURRENT_NORMAL_CLOSE_TIMEOUT'
    if ($native.uiProcess.ExitCode -ne 0) {throw 'NATIVE_NORMAL_EXIT_CODE'}
    $receipt=[pscustomobject]@{pid=$native.ui.pid;startedAtTicks=$native.ui.startedAtTicks;exitCode=$native.uiProcess.ExitCode;
        exitedUtc=$native.uiProcess.ExitTime.ToUniversalTime().ToString('o');remainingUi=@($Survivors).Count;
        survivors=@($Survivors | ForEach-Object {$_.native.ui});kind='ordinary-intermediate-no-restart'}
    if ((Get-ColdUtcTicks $receipt.exitedUtc) -lt $receipt.startedAtTicks) {throw 'CONCURRENT_EXIT_TIME'}
    Write-ColdJson (Join-Path $Evidence ('exit-'+$receipt.pid+'.json')) $receipt
    return $receipt
}

# Совместимый entry point владеет только собственной Temp/run-UUID копией и сохраняет receipts.
function Invoke-NativeConcurrentScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    $clientNames=@(Get-NativeConcurrentClients $Row $Timeout)
    $run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
    [void](Assert-ColdOwnedRun $run ([IO.Path]::GetTempPath()))
    foreach ($protected in @($Source,$script:nativeTarget,$Life.artifactDir)) {[void](Get-ValidatedPortablePaths $protected $run $script:nativeProject $script:nativeProfile)}
    $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
    $root=Join-Path (Join-Path $run $variants[$Row.path]) 'CashPrediction'
    $cellEvidence=Join-Path $Evidence ([IO.Path]::GetFileName($run))
    $null=New-Item -ItemType Directory -Path $cellEvidence
    $entries=[Collections.Generic.List[object]]::new();$nodes=[Collections.Generic.List[string]]::new()
    $roots=[Collections.Generic.List[string]]::new();$cleanup=[Collections.Generic.List[string]]::new()
    $server=$null;$offline=$null;$servers=[Collections.Generic.List[object]]::new();$peerBarrier=$null;$peer=$null;$stale=$null;$failure=$null;$complete=$false
    $started=[datetime]::UtcNow;$priorWorkDir=Get-Variable WorkDir -Scope Script -ValueOnly -ErrorAction SilentlyContinue;$script:WorkDir=$run
    $Row.status='PENDING';$Row.reason='CONCURRENT_NOT_EXECUTED'
    $Row | Add-Member runRoot $run -Force;$Row | Add-Member concurrentCellEvidence $cellEvidence -Force
    try {
        $roots.Add($root)
        if ($clientNames.Count -eq 3) {$roots.Add((Join-Path (Join-Path $run 'independent-root') 'CashPrediction'))}
        foreach ($copy in $roots) {
            $null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($copy))
            Copy-Item -LiteralPath $Source -Destination $copy -Recurse
            [void](Assert-ColdTree $copy $Base)
            $null=New-Item -ItemType Directory -Path (Join-Path $copy 'CashMemory/Updates') -Force
            $domainEvidence=Join-Path $cellEvidence ('seed-'+$roots.IndexOf($copy));$null=New-Item -ItemType Directory -Path $domainEvidence
            $domain=Initialize-NativeDomainSession $copy $Java $Cold $domainEvidence
            Write-ColdJson (Join-Path $domainEvidence 'receipt.json') $domain
        }
        $before=@(Assert-ColdTree $root $Base);$userBefore=Get-NativeUserObject $root
        $targetBefore=@(Assert-ColdTree $script:nativeTarget $Target);$controlled=@(Get-ColdControlledInventory $root)
        $delta=@($Target.deltaPatches | Where-Object {$_.baseReleaseNumber -eq $Base.releaseNumber -and $_.baseCommitSha -ceq $Base.commitSha -and $_.baseTreeSha256 -ceq $Base.treeSha256})
        if ($delta.Count -ne 1) {throw 'NATIVE_DELTA_BASE'}
        $offline=Start-NativeFixture $Life $Java 'offline-close' 0;$servers.Add($offline)
        # Завершённые реальные offline попытки исключают публикацию Ready до последнего beforeUi.
        for ($i=0;$i -lt $clientNames.Count;$i++) {
            $activeServer=$offline
            if ($i -eq $clientNames.Count-1) {$server=Start-NativeFixture $Life $Java 'valid' 0;$servers.Add($server);$activeServer=$server}
            $client=$clientNames[$i];$node='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString();$nodes.Add($node)
            $arguments=@(Get-NativeConcurrentArguments $root $client $node ($i -gt 0));$launchAt=[datetime]::UtcNow
            $entry=[pscustomobject]@{root=$root;client=$client;node=$node;args=$arguments;native=$null}
            $entry.native=Start-NativeOwned (Join-Path $root (Get-ColdLauncherName $client)) $arguments $root $activeServer.receipt.manifestUri -Web:($client -ceq 'web')
            $entries.Add($entry);$Row | Add-Member executed $true -Force
            Write-ColdJson (Join-Path $cellEvidence ('launch-started-'+$i+'.json')) ([ordered]@{launcher=$entry.native.identity;args=$arguments;startedAt=$launchAt.ToString('o');manifestUri=$activeServer.receipt.manifestUri})
            Connect-NativeConcurrentClient $entry $launchAt $Timeout
            Write-ColdJson (Join-Path $cellEvidence ('launch-'+$i+'.json')) ([ordered]@{launcher=$entry.native.identity;ui=$entry.native.ui;args=$arguments;startedAt=$launchAt.ToString('o');manifestUri=$activeServer.receipt.manifestUri})
            if ($i -lt $clientNames.Count-1) {
                Wait-NativeCondition {
                    $stats=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $offline.owned 'server-stats.json') -Raw -Encoding utf8)
                    return $stats.requests -ge (3*($i+1)) -and $stats.active -eq 0 -and $stats.requests -eq $stats.completed -and (Test-NativePreparationFinished $root)
                } $Timeout 'CONCURRENT_OFFLINE_PREPARATION_TIMEOUT'
                if (Test-Path -LiteralPath (Join-Path $root 'CashMemory/Updates/Ready')) {throw 'CONCURRENT_OFFLINE_READY'}
            }
        }
        Assert-NativeConcurrentDistinct @($entries.ToArray())
        $ready=Join-Path $root 'CashMemory/Updates/Ready/update.json'
        Wait-NativeCondition {Test-Path -LiteralPath $ready} $Timeout 'NATIVE_READY_TIMEOUT'
        $manifest=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $ready -Raw -Encoding utf8)
        if ($manifest.releaseNumber -ne $Target.releaseNumber -or $manifest.commitSha -cne $Target.commitSha -or $manifest.treeSha256 -cne $Target.treeSha256) {throw 'NATIVE_READY_IDENTITY'}
        [void](Assert-ColdTree (Join-Path $root 'CashMemory/Updates/Ready/tree') $Target)
        $proofs=[Collections.Generic.List[string]]::new()
        $proofs.Add((Observe-NativeConcurrentBarrier $root $Base @($entries.ToArray()) (Join-Path $cellEvidence 'barrier-all.json')))
        if ($roots.Count -eq 2) {
            $peerRoot=$roots[1];$peerUser=Get-NativeUserObject $peerRoot;$peerControlled=@(Get-ColdControlledInventory $peerRoot)
            $peerBarrier=Open-NativeConcurrentPreparationBarrier $peerRoot
            $node='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString();$nodes.Add($node)
            $arguments=@(Get-NativeConcurrentArguments $peerRoot $Row.client $node $false);$launchAt=[datetime]::UtcNow
            $peer=[pscustomobject]@{root=$peerRoot;client=$Row.client;node=$node;args=$arguments;native=$null}
            $peer.native=Start-NativeOwned (Join-Path $peerRoot (Get-ColdLauncherName $Row.client)) $arguments $peerRoot $server.receipt.manifestUri -Web:($Row.client -ceq 'web')
            $entries.Add($peer);Connect-NativeConcurrentClient $peer $launchAt $Timeout
            Write-ColdJson (Join-Path $cellEvidence 'launch-independent-root.json') ([ordered]@{root=$peerRoot;ui=$peer.native.ui;launcher=$peer.native.identity;args=$arguments})
            Assert-NativeConcurrentDistinct @($entries.ToArray())
            $stale=Add-NativeConcurrentStaleLease $run $root $peer @($entries | Where-Object {$_.root -ceq $root}) $cellEvidence
            $Row | Add-Member staleLeaseEvidence $stale.evidence -Force
            $proofs.Add((Observe-NativeConcurrentBarrier $root $Base @($entries | Where-Object {$_.root -ceq $root}) (Join-Path $cellEvidence 'barrier-three-with-stale-lease.json')))
            [void](Assert-NativeConcurrentAlive $peer)
        }
        $primary=@($entries | Where-Object {$_.root -ceq $root})
        for ($i=0;$i -lt $primary.Count-1;$i++) {
            $survivors=@($primary | Select-Object -Skip ($i+1))
            [void](Close-NativeConcurrentMember $primary[$i] $survivors $Timeout $cellEvidence)
            $proofs.Add((Observe-NativeConcurrentBarrier $root $Base $survivors (Join-Path $cellEvidence ('barrier-after-exit-'+$i+'.json'))))
        }
        $last=$primary[-1];$exit=Close-NativeNormally $last.native $root $last.client $Timeout $cellEvidence
        Write-ColdJson (Join-Path $cellEvidence 'exit-final.json') $exit
        if ($null -ne $stale) {
            $Row | Add-Member staleLeaseObservation (Join-Path $cellEvidence 'stale-lease-observation.json') -Force
            [void](Observe-NativeConcurrentStaleInstall $root $Base $Target $exit $stale $peer $cellEvidence $Timeout)
        } else {Wait-NativeInstalled $root $Target $exit $Timeout}
        if ($null -ne $peer) {
            [void](Assert-NativeConcurrentAlive $peer);Assert-NativeConcurrentOldTree $peer.root $Base
            Write-ColdJson (Join-Path $cellEvidence 'root-isolation.json') ([ordered]@{scope='LIVE_INDEPENDENT_NATIVE_ROOT';status='OBSERVED';installedRoot=$root;independentRoot=$peer.root;alive=(Assert-NativeConcurrentAlive $peer);current=@(Assert-ColdTree $root $Target);independent=@(Assert-ColdTree $peer.root $Base)})
            $peerExit=Close-NativeNormally $peer.native $peer.root $peer.client $Timeout $cellEvidence
            Write-ColdJson (Join-Path $cellEvidence 'exit-independent-root.json') $peerExit
            Assert-NativeConcurrentOldTree $peer.root $Base
            if (-not (Test-ColdInventoryEqual $peerUser (Get-NativeUserObject $peer.root))) {throw 'CONCURRENT_PEER_USER_CHANGED'}
            Assert-ColdControlledChanges $peerControlled @(Get-ColdControlledInventory $peer.root) $peer.client $peer.native.ui $peer.root ([datetime]::UtcNow.Ticks)
        }
        $http=Stop-NativeFixture $server $cellEvidence 'server-concurrent'
        $offlineHttp=Stop-NativeFixture $offline $cellEvidence 'server-offline'
        Assert-NativeScenarioHttp 'delta' $http $delta[0].assetName $delta[0].sizeBytes $Target.sizeBytes
        Assert-NativeScenarioHttp 'offline' $offlineHttp $delta[0].assetName $delta[0].sizeBytes $Target.sizeBytes
        $userAfter=Get-NativeUserObject $root
        if (-not (Test-ColdInventoryEqual $userBefore $userAfter)) {throw 'NATIVE_USER_CHANGED'}
        Assert-NativeConcurrentControlled $controlled @(Get-ColdControlledInventory $root) $primary $root
        $phases=@('SESSION');$log=Join-Path $root 'CashMemory/Updates/update-log.md'
        Copy-Item -LiteralPath $log -Destination (Join-Path $cellEvidence 'update-log.md')
        foreach ($line in Get-Content -LiteralPath $log -Encoding utf8) {if ($line -cmatch ' PHASE_([A-Z_]+)$') {$phases+=@($Matches[1])}}
        $artifacts=@{currentBefore=$before;currentAfter=@(Assert-ColdTree $root $Target);targetBefore=$targetBefore;targetAfter=@(Assert-ColdTree $script:nativeTarget $Target);
            userBefore=$userBefore;userAfter=$userAfter;httpTrace=@(@($offlineHttp.events)+@($http.events) | Where-Object {$_.event -ceq 'FINISH'});phaseLog=$phases}
        foreach ($key in $artifacts.Keys) {$path=Join-Path $cellEvidence ($key+'.json');Write-ColdJson $path $artifacts[$key];$Row | Add-Member $key $path -Force}
        $fields=@{exe=(Join-Path $root (Get-ColdLauncherName $Row.client));args=$primary[0].args;baseRelease=$Base.releaseNumber;baseCommit=$Base.commitSha;
            targetRelease=$Target.releaseNumber;targetCommit=$Target.commitSha;command=(Join-Path $cellEvidence 'launch-0.json');startedAt=$started.ToString('o');
            finishedAt=[datetime]::UtcNow.ToString('o');exitCode=0;skipped=0;failures=0;concurrentEvidence=@($proofs.ToArray());runRoot=$run;serverDirectories=@($servers | ForEach-Object {$_.owned})}
        foreach ($key in $fields.Keys) {$Row | Add-Member $key $fields[$key] -Force}
        $complete=$clientNames.Count -eq 2
        if (-not $complete) {
            $Row | Add-Member outstanding @('MAIN_NATIVE_STALE_LEASE_ACCEPTANCE_PENDING') -Force
            Write-ColdJson (Join-Path $cellEvidence 'pid-reuse.json') ([ordered]@{scope='NATIVE_STALE_LEASE_BIRTH_MISMATCH';status='PENDING';
                reason='MAIN_NATIVE_STALE_LEASE_ACCEPTANCE_PENDING';kernelPidReuseObserved=$false;guards='retained-handle/pid/birth/root/executable';mockIsNativeEvidence=$false})
        }
    } catch {$failure=$_.Exception.Message}
    finally {
        try {Close-NativeConcurrentPreparationBarrier $peerBarrier} catch {$cleanup.Add($_.Exception.Message)}
        foreach ($copy in $roots) {
            try {
                # Любой cleanup kill означает FAIL, даже если основные receipts уже получены.
                if (@(Get-CopyProcesses $copy).Count) {$cleanup.Add('CONCURRENT_FORCED_CLEANUP_REQUIRED');Stop-ColdCopyProcesses $copy}
                Stop-ColdRecoveryHelpers $copy (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe') $started
            } catch {$cleanup.Add($_.Exception.Message)}
        }
        foreach ($entry in $entries) {
            try {if ($entry.native.process.HasExited) {Save-NativeOutput $entry.native $cellEvidence ('client-'+$entries.IndexOf($entry))}} catch {$cleanup.Add($_.Exception.Message)}
            if ($null -ne $entry.native.uiProcess) {$entry.native.uiProcess.Dispose()};$entry.native.process.Dispose()
        }
        foreach ($ownedServer in $servers) {
            try {if (-not $ownedServer.process.HasExited) {[void](Stop-NativeFixture $ownedServer $cellEvidence ('cleanup-server-'+$servers.IndexOf($ownedServer)))}} catch {$cleanup.Add($_.Exception.Message)}
            try {if (-not $ownedServer.process.HasExited) {Stop-ColdRetainedProcess $ownedServer.process $ownedServer.identity $ownedServer.owned}} catch {$cleanup.Add($_.Exception.Message)}
            foreach ($file in 'server-receipt.json','server-stats.json','server-trace.jsonl') {
                try {
                    $raw=Join-Path $ownedServer.owned $file
                    if (Test-Path -LiteralPath $raw) {Copy-Item -LiteralPath (Resolve-PortableSafetyPath $raw) -Destination (Join-Path $cellEvidence ('raw-server-'+$servers.IndexOf($ownedServer)+'-'+$file))}
                } catch {$cleanup.Add($_.Exception.Message)}
            }
            $ownedServer.process.Dispose()
        }
        $Row | Add-Member serverDirectories @($servers | ForEach-Object {$_.owned}) -Force
        if (-not $cleanup.Count) {
            foreach ($node in $nodes) {try {$path=Get-PortableRegistryPath $node;if (Test-Path -LiteralPath $path) {Remove-Item -LiteralPath $path -Recurse -Force}} catch {$cleanup.Add($_.Exception.Message)}}
        }
        $Row.status=if ($failure -or $cleanup.Count) {'FAIL'} elseif ($complete) {'PASS'} else {'PENDING'}
        $Row.reason=if ($failure) {$failure} elseif ($cleanup.Count) {$cleanup -join '; '} elseif ($complete) {'NATIVE_CONCURRENT_EXECUTED'} else {'MAIN_NATIVE_STALE_LEASE_ACCEPTANCE_PENDING'}
        Write-ColdJson (Join-Path $cellEvidence 'cell.json') $Row
        $script:WorkDir=$priorWorkDir
    }
    if ($failure -or $cleanup.Count) {throw $Row.reason}
}
