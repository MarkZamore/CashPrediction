<#
.SYNOPSIS
Независимое read-only принятие actual receipts трёх клиентов и stale birth lease.
.DESCRIPTION
Dot-source загружает только определения. Row.status и возврат helper не доказательства.
Missing evidence оставляет PENDING, противоречие даёт FAIL. Никаких запусков, CIM,
GUI, kill, записей, изменения Row, canonical plan или signoff.
SupplementalDirectory содержит ранее снятые actual snapshots, не реконструкцию baseline.
#>

# Импортируется закрытый список read-only/pure guards, но не тела frozen scripts.
function Import-NativeConcurrentAcceptanceGuards {
    $sources=@{
        'Test-Portable.ps1'=@('Resolve-PortableSafetyPath','Assert-PortableTreeHasNoLinks')
        'Test-UpdateBootstrap.ps1'=@('Assert-ColdKeys','Test-ColdInteger','Get-ColdLauncherName',
            'Get-ColdUtcTicks','ConvertFrom-ColdReceiptJson','ConvertFrom-ColdCommandLine',
            'Test-ColdInventoryEqual','Assert-ColdProcessIdentity','Assert-ColdUiReceipt',
            'Get-ColdTreeHash','Assert-ColdInventory','Assert-ColdControlledChanges')
        'Test-NativeUpdateLifecycle.ps1'=@('Assert-NativeMainWindow','Assert-NativeExitReceipt')
        'NativeUpdateConcurrentScenarios.ps1'=@('Assert-NativeConcurrentStaleLease')
    }
    foreach ($file in $sources.Keys) {
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors)
        if ($errors.Count) {throw 'ACCEPTANCE_GUARD_PARSE'}
        foreach ($name in $sources[$file]) {
            $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true) | Where-Object {$_.Name -ceq $name})
            if ($definitions.Count -ne 1) {throw ('ACCEPTANCE_GUARD_MISSING '+$name)}
            . ([scriptblock]::Create(($definitions[0].Extent.Text -replace '^function ','function script:')))
        }
    }
    # Общая грамматика берётся из исходного ресурса, а не из текущего PSScriptRoot AST.
    $script:acceptanceFormat=Join-Path $PSScriptRoot '../../core/src/main/resources/ru/cashprediction/core/format/format.properties'
}

# Контекст грамматики нужен только frozen controlled guard, файлов приложения не пишет.
function Get-ColdSessionWords {
    $words=@{}
    foreach ($line in Get-Content -LiteralPath $script:acceptanceFormat -Encoding utf8) {
        if ($line -match '^(session\.md\.[^=]+)=(.*)$') {$words[$Matches[1]]=$Matches[2]}
    }
    return $words
}

# Frozen receipts читаются ограниченно и эксклюзивно относительно writers, без partial JSON.
function Read-NativeAcceptanceJson([string]$Path,[string]$ExpectedSha256='') {
    if (-not [IO.Path]::IsPathFullyQualified($Path)) {throw 'ACCEPTANCE_ABSOLUTE_PATH'}
    $safe=Resolve-PortableSafetyPath $Path
    if (-not (Test-Path -LiteralPath $safe -PathType Leaf)) {return $null}
    $stream=[IO.FileStream]::new($safe,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        if ($stream.Length -lt 1 -or $stream.Length -gt 33554432) {throw 'ACCEPTANCE_JSON_SIZE'}
        $bytes=[byte[]]::new([int]$stream.Length);$offset=0
        while ($offset -lt $bytes.Length) {
            $count=$stream.Read($bytes,$offset,$bytes.Length-$offset)
            if ($count -eq 0) {throw 'ACCEPTANCE_SHORT_READ'};$offset+=$count
        }
        if ($ExpectedSha256) {
            if ($ExpectedSha256 -cnotmatch '^[0-9a-f]{64}$' -or
                [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $ExpectedSha256) {throw 'ACCEPTANCE_PIN'}
        }
        $text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes).TrimStart([char]0xfeff)
        return ,(ConvertFrom-ColdReceiptJson $text)
    } finally {$stream.Dispose()}
}

# Равенство всех wire полей запрещает подменить PID/birth/root более поздним процессом.
function Assert-NativeAcceptanceSame($Expected,$Actual,[string]$Code) {
    if (-not (Test-ColdInventoryEqual $Expected $Actual)) {throw $Code}
}

# Наблюдение связано с исходным UI и lease, собственным root и временем наблюдения.
function Assert-NativeAcceptanceAlive($Alive,$Ui,[string]$Root) {
    Assert-ColdKeys $Alive @('ui','identity','lease','leasePath','observedUtc')
    Assert-ColdUiReceipt $Alive.ui $Root $Ui.lease.client
    Assert-NativeAcceptanceSame $Ui $Alive.ui 'ACCEPTANCE_UI_SUBSTITUTION'
    Assert-NativeAcceptanceSame $Ui.lease $Alive.lease 'ACCEPTANCE_LEASE_SUBSTITUTION'
    $expected=[pscustomobject]@{ProcessId=$Ui.pid;StartedAtTicks=$Ui.startedAtTicks;ExecutablePath=$Ui.executablePath;OwnedRoot=$Root}
    Assert-ColdProcessIdentity $expected $Alive.identity $Root $Ui.executablePath
    if ($Ui.pid -gt [int]::MaxValue -or $Alive.leasePath -cne (Join-Path $Root ('CashMemory/Updates/processes/'+$Ui.lease.leaseId+'.json')) -or
        (Get-ColdUtcTicks $Alive.observedUtc) -lt (Get-ColdUtcTicks $Ui.observedAt)) {throw 'ACCEPTANCE_ALIVE_BINDING'}
}

# Exact managed inventory сверяется с независимым pinned manifest, включая readonly.
function Assert-NativeAcceptanceTree($Files,$Manifest) {
    Assert-ColdInventory $Files $Manifest.treeSha256
    Assert-NativeAcceptanceSame @($Manifest.files) @($Files) 'ACCEPTANCE_TREE_FILES'
}

# Проверка непрерывной bounded серии; отдельные unit sleeps здесь не доказательство.
function Assert-NativeAcceptanceBarrier($Barrier,$Uis,[string]$Root,$Base,[long]$After,[long]$Before) {
    Assert-ColdKeys $Barrier @('scope','windowMillis','status','elapsedMillis','samples')
    if ($Barrier.scope -cne 'LIVE_CLIENT_LEASE_TREE_BARRIER' -or $Barrier.status -cne 'OBSERVED' -or
        -not (Test-ColdInteger $Barrier.windowMillis 3000) -or $Barrier.windowMillis -ne 3000 -or
        -not (Test-ColdInteger $Barrier.elapsedMillis 3000) -or $Barrier.elapsedMillis -gt 60000 -or
        @($Barrier.samples).Count -lt 2 -or @($Barrier.samples).Count -gt 1000) {throw 'ACCEPTANCE_BARRIER_WINDOW'}
    $previous=-1L;$time=$After
    foreach ($sample in $Barrier.samples) {
        Assert-ColdKeys $sample @('elapsedMillis','observedUtc','alive','treeSha256')
        $now=Get-ColdUtcTicks $sample.observedUtc
        if (-not (Test-ColdInteger $sample.elapsedMillis) -or $sample.elapsedMillis -le $previous -or
            $sample.elapsedMillis -gt $Barrier.elapsedMillis -or $now -lt $time -or $now -gt $Before -or
            $sample.treeSha256 -cne $Base.treeSha256 -or @($sample.alive).Count -ne @($Uis).Count) {throw 'ACCEPTANCE_BARRIER_SAMPLE'}
        for ($i=0;$i -lt @($Uis).Count;$i++) {
            Assert-NativeAcceptanceAlive @($sample.alive)[$i] @($Uis)[$i] $Root
            if ((Get-ColdUtcTicks @($sample.alive)[$i].observedUtc) -gt $now) {throw 'ACCEPTANCE_BARRIER_TIME'}
        }
        $previous=$sample.elapsedMillis;$time=$now
    }
    $first=@($Barrier.samples)[0];$last=@($Barrier.samples)[-1]
    # Stopwatch frozen observer включает дорогую первую проверку tree перед первым sample.
    # Нельзя требовать ещё 3000 ms от первого sample: это другой, не produced контракт.
    if ($last.elapsedMillis -lt 3000 -or
        (Get-ColdUtcTicks $last.observedUtc)-(Get-ColdUtcTicks $first.observedUtc) -lt
            ($last.elapsedMillis-$first.elapsedMillis-25)*10000L) {throw 'ACCEPTANCE_BARRIER_DURATION'}
}

# Пользовательский CashMemory и unmanaged root сравниваются целиком, не отдельным hash.
function Assert-NativeAcceptanceUser($Before,$After) {
    # Frozen Get-NativeUserObject пишет map path -> entry, а не array.
    foreach ($map in @($Before,$After)) {
        if ($map -isnot [pscustomobject]) {throw 'ACCEPTANCE_USER_MAP'}
        $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($property in $map.PSObject.Properties) {
            $entry=$property.Value
            Assert-ColdKeys $entry @('path','directory','sizeBytes','sha256','readOnly')
            if ($entry.path -isnot [string] -or $property.Name -cne $entry.path -or $entry.path -match '(^/|\\|:|(^|/)\.\.?(/|$))' -or -not $seen.Add($entry.path) -or
                $entry.directory -isnot [bool] -or $entry.readOnly -isnot [bool] -or -not (Test-ColdInteger $entry.sizeBytes) -or
                ($entry.directory -and ($entry.sizeBytes -ne 0 -or $entry.sha256 -cne 'directory')) -or
                (-not $entry.directory -and $entry.sha256 -cnotmatch '^[0-9a-f]{64}$')) {throw 'ACCEPTANCE_USER_INVENTORY'}
        }
    }
    Assert-NativeAcceptanceSame $Before $After 'ACCEPTANCE_CASHMEMORY_CHANGED'
}

# Pure контракт не выдаёт native PASS: fixtures получают только SATISFIED/PENDING/FAIL.
function Test-NativeConcurrentReceiptContract($Bundle,$Base,$Target,[string]$ExpectedHelperSha256) {
    $missing=[Collections.Generic.List[string]]::new();$bad=[Collections.Generic.List[string]]::new();$checks=[Collections.Generic.List[string]]::new()
    $incomplete=@{}
    # Missing поля отличаются от противоречащих значений: неполная квитанция не FAIL.
    function Fields([string]$Key,$Value,[string[]]$Names,[string]$Prefix=$Key) {
        if ($null -eq $Value) {$missing.Add($Prefix);$incomplete[$Key]=$true;return}
        if ($Value -isnot [pscustomobject]) {return}
        foreach ($name in $Names) {
            if ($null -eq $Value.PSObject.Properties[$name] -or $null -eq $Value.$name) {
                $missing.Add($Prefix+'.'+$name);$incomplete[$Key]=$true
            }
        }
    }
    $schemas=@{
        cell=@('scenario','phase','executed','status','exitCode','skipped','failures','baseRelease','baseCommit','targetRelease','targetCommit','runRoot','finishedAt')
        injection=@('schemaVersion','scope','status','kernelPidReuseObserved','leasePath','lease','leaseSha256','realPeerBefore','realPeerAfter','primary','injectedUtc','evidence')
        observation=@('schemaVersion','scope','status','kernelPidReuseObserved','limitSeconds','injection','finalExit','productionHelper','helperSha256','peerAfterInstall','elapsedMillis','samples')
        rootIsolation=@('scope','status','installedRoot','independentRoot','alive','current','independent')
        completion=@('scope','observedUtc','lastInstall','primaryProcesses','primaryLeases','staleLeasePresent','installJournalPresent','completedJournalPresent','noRestartSamples')
        readyManifest=@('releaseNumber','commitSha','treeSha256')
    }
    foreach ($key in $schemas.Keys) {if ($Bundle.Contains($key) -and $null -ne $Bundle[$key]) {Fields $key $Bundle[$key] $schemas[$key]}}
    foreach ($key in 'launch0','launch1','launch2','peerLaunch') {
        if (-not $Bundle.Contains($key) -or $null -eq $Bundle[$key]) {continue}
        $launch=$Bundle[$key];Fields $key $launch @('ui','launcher','args')
        if ($key -ceq 'peerLaunch') {Fields $key $launch @('root')} else {Fields $key $launch @('startedAt','manifestUri')}
        if ($null -ne $launch.PSObject.Properties['ui']) {
            Fields $key $launch.ui @('pid','startedAtTicks','executablePath','modules','witness','lease','commandLine','args','observedAt') ($key+'.ui')
            if ($null -ne $launch.ui -and $null -ne $launch.ui.PSObject.Properties['lease']) {
                Fields $key $launch.ui.lease @('schemaVersion','leaseId','pid','startedAtEpochMillis','installationRoot','client') ($key+'.ui.lease')
            }
        }
    }
    foreach ($key in 'exit0','exit1','exitFinal','peerExit') {
        if ($Bundle.Contains($key) -and $null -ne $Bundle[$key]) {
            Fields $key $Bundle[$key] @('pid','startedAtTicks','exitCode','exitedUtc','kind')
            Fields $key $Bundle[$key] $(if ($key -cin @('exit0','exit1')) {@('remainingUi','survivors')} else {@('remainingClients')})
        }
    }
    foreach ($key in 'barrierAll','barrierStale','barrierAfter0','barrierAfter1') {
        if ($Bundle.Contains($key) -and $null -ne $Bundle[$key]) {Fields $key $Bundle[$key] @('scope','windowMillis','status','elapsedMillis','samples')}
    }
    foreach ($key in 'collectorPrimaryBefore','collectorPeerBefore','collectorReady','collectorInstalled','collectorAfter') {
        if ($Bundle.Contains($key) -and $null -ne $Bundle[$key]) {Fields $key $Bundle[$key] @('scope','stage','primaryRoot','peerRoot','observedUtc')}
    }
    foreach ($key in 'cell','injection','observation','rootIsolation','barrierAll','barrierStale','barrierAfter0','barrierAfter1') {
        if (-not $Bundle.Contains($key) -or $null -eq $Bundle[$key]) {continue}
        $r=$Bundle[$key]
        if ($null -ne $r.PSObject.Properties['status']) {
            if ($r.status -ceq 'FAIL') {$bad.Add($key+': ACTUAL_RECORDED_FAILURE')}
            elseif ($key -cne 'cell' -and $r.status -ceq 'PENDING') {$missing.Add($key+'.completed');$incomplete[$key]=$true}
        }
        if ($null -ne $r.PSObject.Properties['kernelPidReuseObserved'] -and $r.kernelPidReuseObserved -ceq $true) {$bad.Add($key+': ACCEPTANCE_STALE_SCOPE')}
    }
    function Check([string]$Name,[string[]]$Required,[scriptblock]$Body) {
        $absent=$false
        foreach ($key in $Required) {if (-not $Bundle.Contains($key) -or $null -eq $Bundle[$key]) {$missing.Add($key);$absent=$true}}
        if ($absent -or @($Required | Where-Object {$incomplete.ContainsKey($_)}).Count) {return}
        try {& $Body | Out-Null;$checks.Add($Name)} catch {
            if ($_.FullyQualifiedErrorId -like '*PropertyNotFound*') {$missing.Add($Name+': '+$_.Exception.Message)}
            else {$bad.Add($Name+': '+$_.Exception.Message)}
        }
    }
    Check 'row' @('cell') {
        $row=$Bundle.cell
        if ($row.scenario -cne 'three-clients-pid-root-isolation' -or $row.phase -cne 'SESSION' -or
            $row.executed -isnot [bool] -or -not $row.executed -or $row.status -ceq 'FAIL' -or
            -not (Test-ColdInteger $row.exitCode) -or $row.exitCode -ne 0 -or $row.skipped -ne 0 -or $row.failures -ne 0 -or
            $row.baseRelease -ne $Base.releaseNumber -or $row.baseCommit -cne $Base.commitSha -or
            $row.targetRelease -ne $Target.releaseNumber -or $row.targetCommit -cne $Target.commitSha) {throw 'ACCEPTANCE_ROW_IDENTITY'}
    }
    Check 'identities' @('cell','launch0','launch1','launch2','peerLaunch') {
        $uis=@($Bundle.launch0.ui,$Bundle.launch1.ui,$Bundle.launch2.ui);$root=$uis[0].lease.installationRoot;$peer=$Bundle.peerLaunch.ui;$peerRoot=$Bundle.peerLaunch.root
        $run=[IO.Path]::GetFullPath($Bundle.cell.runRoot).TrimEnd('\','/')
        if ([IO.Path]::GetFileName($run) -cnotmatch '^run-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
            -not [IO.Path]::GetDirectoryName($run).Equals([IO.Path]::GetTempPath().TrimEnd('\','/'),[StringComparison]::OrdinalIgnoreCase)) {throw 'ACCEPTANCE_OWNED_RUN'}
        foreach ($copy in @($root,$peerRoot)) {
            if (-not [IO.Path]::IsPathFullyQualified($copy) -or -not $copy.StartsWith($run+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'ACCEPTANCE_OWNED_ROOT'}
        }
        if ($root.Equals($peerRoot,[StringComparison]::OrdinalIgnoreCase) -or $root.StartsWith($peerRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or
            $peerRoot.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'ACCEPTANCE_ROOT_ISOLATION'}
        $pids=@();$leases=@();$clients=@()
        foreach ($launch in @($Bundle.launch0,$Bundle.launch1,$Bundle.launch2,$Bundle.peerLaunch)) {
            $ui=$launch.ui;$copy=$ui.lease.installationRoot
            Assert-ColdUiReceipt $ui $copy $ui.lease.client
            Assert-NativeAcceptanceSame @($launch.args) @($ui.args) 'ACCEPTANCE_ARGUMENTS'
            if ($ui.pid -gt [int]::MaxValue -or $pids -contains $ui.pid -or $leases -ccontains $ui.lease.leaseId) {throw 'ACCEPTANCE_DISTINCT_IDENTITY'}
            $pids+=@($ui.pid);$leases+=@($ui.lease.leaseId)
            $parent=$launch.launcher
            Assert-ColdProcessIdentity $parent $parent $copy $ui.executablePath
            if ($parent.ProcessId -gt [int]::MaxValue) {throw 'ACCEPTANCE_LAUNCHER_PID'}
        }
        foreach ($ui in $uis) {if ($ui.lease.installationRoot -cne $root) {throw 'ACCEPTANCE_PRIMARY_ROOT'};$clients+=@($ui.lease.client)}
        if (@($clients | Sort-Object -Unique).Count -ne 3 -or $peer.lease.installationRoot -cne $peerRoot) {throw 'ACCEPTANCE_THREE_CLIENTS'}
    }
    Check 'ordinary-exits' @('launch0','launch1','launch2','peerLaunch','exit0','exit1','exitFinal','peerExit') {
        $uis=@($Bundle.launch0.ui,$Bundle.launch1.ui,$Bundle.launch2.ui);$previous=0L
        for ($i=0;$i -lt 3;$i++) {
            $exit=@($Bundle.exit0,$Bundle.exit1,$Bundle.exitFinal)[$i];$ui=$uis[$i]
            if ($i -lt 2) {
                Assert-ColdKeys $exit @('pid','startedAtTicks','exitCode','exitedUtc','remainingUi','survivors','kind')
                if (-not (Test-ColdInteger $exit.pid 1) -or $exit.pid -ne $ui.pid -or $exit.startedAtTicks -ne $ui.startedAtTicks -or
                    -not (Test-ColdInteger $exit.startedAtTicks 1) -or -not (Test-ColdInteger $exit.exitCode) -or $exit.exitCode -ne 0 -or
                    -not (Test-ColdInteger $exit.remainingUi 1) -or $exit.remainingUi -ne 2-$i -or $exit.kind -cne 'ordinary-intermediate-no-restart') {throw 'ACCEPTANCE_INTERMEDIATE_EXIT'}
                Assert-NativeAcceptanceSame @($uis | Select-Object -Skip ($i+1)) @($exit.survivors) 'ACCEPTANCE_SURVIVORS'
            } else {Assert-NativeExitReceipt $exit $ui $ui.lease.installationRoot $ui.lease.client}
            $time=Get-ColdUtcTicks $exit.exitedUtc
            if ($time -lt $ui.startedAtTicks -or $time -le $previous) {throw 'ACCEPTANCE_EXIT_ORDER'};$previous=$time
        }
        $peer=$Bundle.peerLaunch.ui;Assert-NativeExitReceipt $Bundle.peerExit $peer $peer.lease.installationRoot $peer.lease.client
        if ((Get-ColdUtcTicks $Bundle.peerExit.exitedUtc) -le $previous) {throw 'ACCEPTANCE_PEER_EARLY_EXIT'}
    }
    Check 'blocking' @('launch0','launch1','launch2','barrierAll','barrierStale','barrierAfter0','barrierAfter1','injection','exit0','exit1','exitFinal') {
        $uis=@($Bundle.launch0.ui,$Bundle.launch1.ui,$Bundle.launch2.ui);$root=$uis[0].lease.installationRoot
        $injected=Get-ColdUtcTicks $Bundle.injection.injectedUtc;$e0=Get-ColdUtcTicks $Bundle.exit0.exitedUtc;$e1=Get-ColdUtcTicks $Bundle.exit1.exitedUtc;$ef=Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc
        Assert-NativeAcceptanceBarrier $Bundle.barrierAll $uis $root $Base (Get-ColdUtcTicks $uis[-1].observedAt) $injected
        Assert-NativeAcceptanceBarrier $Bundle.barrierStale $uis $root $Base $injected $e0
        Assert-NativeAcceptanceBarrier $Bundle.barrierAfter0 @($uis | Select-Object -Skip 1) $root $Base $e0 $e1
        Assert-NativeAcceptanceBarrier $Bundle.barrierAfter1 @($uis[-1]) $root $Base $e1 $ef
    }
    Check 'stale-birth' @('launch0','launch1','launch2','peerLaunch','injection','observation','exitFinal','peerExit','rootIsolation','productionHelperHash') {
        $peer=$Bundle.peerLaunch.ui;$peerRoot=$Bundle.peerLaunch.root;$root=$Bundle.launch0.ui.lease.installationRoot
        $injection=$Bundle.injection;$observation=$Bundle.observation;$isolation=$Bundle.rootIsolation
        foreach ($receipt in @($injection,$observation)) {
            if ($receipt.schemaVersion -ne 1 -or -not (Test-ColdInteger $receipt.schemaVersion 1) -or $receipt.scope -cne 'NATIVE_STALE_LEASE_BIRTH_MISMATCH' -or
                $receipt.kernelPidReuseObserved -isnot [bool] -or $receipt.kernelPidReuseObserved) {throw 'ACCEPTANCE_STALE_SCOPE'}
        }
        if ($injection.status -cne 'INJECTED' -or $observation.status -cne 'OBSERVED' -or
            $observation.injection -cne $injection.evidence -or $observation.productionHelper -cne (Join-Path $root 'CashMemory/Updates/apply-update.ps1') -or
            $ExpectedHelperSha256 -cnotmatch '^[0-9a-f]{64}$' -or $observation.helperSha256 -cne $ExpectedHelperSha256 -or
            $Bundle.productionHelperHash -cne $ExpectedHelperSha256) {throw 'ACCEPTANCE_PRODUCTION_HELPER'}
        $entry=[pscustomobject]@{root=$peerRoot;client=$peer.lease.client;native=[pscustomobject]@{ui=$peer}}
        Assert-NativeConcurrentStaleLease $injection.lease $root $entry $injection.leasePath
        Assert-NativeAcceptanceSame @($Bundle.launch0.ui,$Bundle.launch1.ui,$Bundle.launch2.ui) @($injection.primary) 'ACCEPTANCE_INJECTED_PRIMARY'
        if ($injection.lease.leaseId -cin @($injection.primary | ForEach-Object {$_.lease.leaseId})) {throw 'ACCEPTANCE_STALE_LEASE_COLLISION'}
        $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $injection.lease -Depth 8 -Compress))
        if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $injection.leaseSha256) {throw 'ACCEPTANCE_STALE_HASH'}
        Assert-NativeAcceptanceSame $Bundle.exitFinal $observation.finalExit 'ACCEPTANCE_FINAL_EXIT_BINDING'
        foreach ($alive in @($injection.realPeerBefore,$injection.realPeerAfter,$observation.peerAfterInstall,$isolation.alive)) {Assert-NativeAcceptanceAlive $alive $peer $peerRoot}
        $injected=Get-ColdUtcTicks $injection.injectedUtc
        if ((Get-ColdUtcTicks $injection.realPeerBefore.observedUtc) -gt $injected -or
            (Get-ColdUtcTicks $injection.realPeerAfter.observedUtc) -gt $injected) {throw 'ACCEPTANCE_INJECTION_TIME'}
        $final=Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc;$peerExit=Get-ColdUtcTicks $Bundle.peerExit.exitedUtc
        $time=$final;$elapsed=-1L
        if (-not (Test-ColdInteger $observation.limitSeconds 1) -or $observation.limitSeconds -gt 60 -or
            -not (Test-ColdInteger $observation.elapsedMillis) -or @($observation.samples).Count -lt 1 -or @($observation.samples).Count -gt 1000) {throw 'ACCEPTANCE_STALE_OBSERVER'}
        foreach ($sample in $observation.samples) {
            Assert-ColdKeys $sample @('elapsedMillis','observedUtc','peer','staleLeasePresent','phase')
            $now=Get-ColdUtcTicks $sample.observedUtc
            if (-not (Test-ColdInteger $sample.elapsedMillis) -or $sample.elapsedMillis -le $elapsed -or
                $sample.elapsedMillis -gt 1000*$observation.limitSeconds -or $now -lt $time -or $now -ge $peerExit -or
                $sample.staleLeasePresent -isnot [bool]) {throw 'ACCEPTANCE_STALE_SAMPLE'}
            Assert-NativeAcceptanceAlive $sample.peer $peer $peerRoot
            if ((Get-ColdUtcTicks $sample.peer.observedUtc) -gt $now) {throw 'ACCEPTANCE_STALE_SAMPLE_TIME'}
            $time=$now;$elapsed=$sample.elapsedMillis
        }
        if ($isolation.scope -cne 'LIVE_INDEPENDENT_NATIVE_ROOT' -or $isolation.status -cne 'OBSERVED' -or
            $isolation.installedRoot -cne $root -or $isolation.independentRoot -cne $peerRoot -or
            (Get-ColdUtcTicks $observation.peerAfterInstall.observedUtc) -lt $time -or
            (Get-ColdUtcTicks $isolation.alive.observedUtc) -lt (Get-ColdUtcTicks $observation.peerAfterInstall.observedUtc) -or
            (Get-ColdUtcTicks $isolation.alive.observedUtc) -ge $peerExit) {throw 'ACCEPTANCE_PEER_INSTALL_ORDER'}
        Assert-NativeAcceptanceTree $isolation.current $Target;Assert-NativeAcceptanceTree $isolation.independent $Base
    }
    foreach ($pair in @(@('currentBefore',$Base),@('currentAfter',$Target),@('targetBefore',$Target),@('targetAfter',$Target),@('readyTree',$Target))) {
        $key=$pair[0];$manifest=$pair[1];Check $key @($key) {Assert-NativeAcceptanceTree $Bundle[$key] $manifest}
    }
    Check 'ready' @('readyManifest') {
        $ready=$Bundle.readyManifest
        if ($ready.releaseNumber -ne $Target.releaseNumber -or $ready.commitSha -cne $Target.commitSha -or $ready.treeSha256 -cne $Target.treeSha256) {throw 'ACCEPTANCE_READY_TARGET'}
    }
    Check 'primary-user' @('userBefore','userAfter') {Assert-NativeAcceptanceUser $Bundle.userBefore $Bundle.userAfter}
    Check 'peer-user' @('peerUserBefore','peerUserAfter') {Assert-NativeAcceptanceUser $Bundle.peerUserBefore $Bundle.peerUserAfter}
    Check 'controlled' @('primaryControlledBefore','primaryControlledAfter','peerControlledBefore','peerControlledAfter','launch0','launch1','launch2','peerLaunch','peerExit') {
        $finished=Get-ColdUtcTicks $Bundle.peerExit.exitedUtc
        $uis=@($Bundle.launch0.ui,$Bundle.launch1.ui,$Bundle.launch2.ui)
        $known=@('CashMemory/session-fx.xml','CashMemory/session-swing.xml','CashMemory/web-session.md','CashMemory/web-session.plan.md','CashMemory/web-reconnect.md','CashMemory/web-reconnect-lock.md','CashMemory/lock.md')
        foreach ($item in @($Bundle.primaryControlledBefore)+@($Bundle.primaryControlledAfter)) {if ($item.path -cnotin $known) {throw 'ACCEPTANCE_CONTROLLED_PATH'}}
        for ($i=0;$i -lt 3;$i++) {
            $ui=$uis[$i];$paths=if ($ui.lease.client -ceq 'web') {@($known | Where-Object {$_ -clike 'CashMemory/web-*'})} else {@('CashMemory/session-'+$ui.lease.client+'.xml')}
            if ($i -eq 0) {$paths+=@('CashMemory/lock.md')}
            Assert-ColdControlledChanges @($Bundle.primaryControlledBefore | Where-Object {$_.path -cin $paths}) @($Bundle.primaryControlledAfter | Where-Object {$_.path -cin $paths}) $ui.lease.client $ui $ui.lease.installationRoot $finished
        }
        $peer=$Bundle.peerLaunch.ui;Assert-ColdControlledChanges $Bundle.peerControlledBefore $Bundle.peerControlledAfter $peer.lease.client $peer $peer.lease.installationRoot $finished
    }
    Check 'completion' @('completion','exitFinal','peerExit','injection','peerLaunch') {
        # Дополнительная actual квитанция нужна: frozen helper не сохраняет эти assertions.
        $c=$Bundle.completion
        Assert-ColdKeys $c @('scope','observedUtc','lastInstall','primaryProcesses','primaryLeases','staleLeasePresent','installJournalPresent','completedJournalPresent','noRestartSamples')
        if ($c.scope -cne 'ACTUAL_NATIVE_CONCURRENT_COMPLETION' -or $c.lastInstall.outcome -cne 'UPDATED' -or $c.lastInstall.targetCommitSha -cne $Target.commitSha -or
            @($c.primaryProcesses).Count -ne 0 -or @($c.primaryLeases).Count -ne 0) {throw 'ACCEPTANCE_COMPLETION'}
        foreach ($flag in 'staleLeasePresent','installJournalPresent','completedJournalPresent') {if ($c.$flag -isnot [bool] -or $c.$flag) {throw 'ACCEPTANCE_COMPLETION_REMAINS'}}
        $final=Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc;$end=Get-ColdUtcTicks $c.observedUtc;$previous=$final
        if ($end -ge (Get-ColdUtcTicks $Bundle.peerExit.exitedUtc) -or @($c.noRestartSamples).Count -lt 2 -or @($c.noRestartSamples).Count -gt 1000) {throw 'ACCEPTANCE_COMPLETION_TIME'}
        foreach ($sample in $c.noRestartSamples) {
            Assert-ColdKeys $sample @('observedUtc','primaryProcesses','peer')
            $time=Get-ColdUtcTicks $sample.observedUtc
            if ($time -lt $previous -or $time -gt $end -or @($sample.primaryProcesses).Count) {throw 'ACCEPTANCE_RESTART'};$previous=$time
            Assert-NativeAcceptanceAlive $sample.peer $Bundle.peerLaunch.ui $Bundle.peerLaunch.root
            if ((Get-ColdUtcTicks $sample.peer.observedUtc) -gt $time) {throw 'ACCEPTANCE_COMPLETION_PEER_TIME'}
        }
        if ((Get-ColdUtcTicks @($c.noRestartSamples)[-1].observedUtc)-(Get-ColdUtcTicks @($c.noRestartSamples)[0].observedUtc) -lt 3000L*10000) {throw 'ACCEPTANCE_NO_RESTART_DURATION'}
    }
    Check 'collector-chronology' @('collectorPrimaryBefore','collectorPeerBefore','collectorReady','collectorInstalled','collectorAfter','cell','launch0','launch2','peerLaunch','barrierAll','completion','peerExit') {
        $root=$Bundle.launch0.ui.lease.installationRoot;$peerRoot=$Bundle.peerLaunch.root
        $times=@{}
        foreach ($stage in 'PrimaryBefore','PeerBefore','Ready','Installed','After') {
            $r=$Bundle['collector'+$stage]
            Assert-ColdKeys $r @('scope','stage','primaryRoot','peerRoot','observedUtc')
            if ($r.scope -cne 'ACTUAL_NATIVE_CONCURRENT_SNAPSHOT' -or $r.stage -cne $stage -or
                $r.primaryRoot -cne $root -or $r.peerRoot -cne $peerRoot) {throw 'ACCEPTANCE_COLLECTOR_BINDING'}
            $times[$stage]=Get-ColdUtcTicks $r.observedUtc
        }
        if ($times.PrimaryBefore -gt (Get-ColdUtcTicks $Bundle.launch0.startedAt) -or
            $times.PeerBefore -gt $Bundle.peerLaunch.ui.startedAtTicks -or
            $times.PeerBefore -lt $times.PrimaryBefore -or
            $times.Ready -lt (Get-ColdUtcTicks $Bundle.launch2.ui.observedAt) -or
            $times.Ready -gt (Get-ColdUtcTicks @($Bundle.barrierAll.samples)[0].observedUtc) -or
            $times.Installed -ne (Get-ColdUtcTicks $Bundle.completion.observedUtc) -or
            $times.After -lt (Get-ColdUtcTicks $Bundle.peerExit.exitedUtc) -or
            $times.After -gt (Get-ColdUtcTicks $Bundle.cell.finishedAt)) {throw 'ACCEPTANCE_COLLECTOR_CHRONOLOGY'}
    }
    Check 'phases' @('updateLog','exitFinal') {
        $final=Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc;$commits=0
        foreach ($line in $Bundle.updateLog -split '\r?\n') {
            if ($line -cmatch '^- (\S+) PHASE_(BOOTSTRAPPING|BACKING_UP|INSTALLING|VERIFYING|COMMITTED|ROLLING_BACK)$') {
                if ((Get-ColdUtcTicks $Matches[1]) -lt $final -or $Matches[2] -ceq 'ROLLING_BACK') {throw 'ACCEPTANCE_REPLACEMENT_BEFORE_EXIT'}
                if ($Matches[2] -ceq 'COMMITTED') {$commits++}
            }
        }
        if ($commits -ne 1) {throw 'ACCEPTANCE_COMMIT_COUNT'}
    }
    return [pscustomobject]@{contractStatus=$(if ($bad.Count) {'FAIL'} elseif ($missing.Count) {'PENDING'} else {'SATISFIED'});
        scope='NATIVE_STALE_LEASE_BIRTH_MISMATCH';kernelPidReuseObserved=$false;
        missing=@($missing | Sort-Object -Unique);contradictions=@($bad.ToArray());checks=@($checks.ToArray())}
}

# Desktop close имеет физический owner receipt; отсутствие его частей остаётся PENDING.
function Test-NativeAcceptanceCloseReceipt($Receipt,$Ui,$Exit) {
    $missing=@()
    if ($null -eq $Receipt) {return @('physical-close:'+ $Ui.pid)}
    foreach ($name in 'observedUtc','window','ui') {
        if ($null -eq $Receipt.PSObject.Properties[$name] -or $null -eq $Receipt.$name) {$missing+=@('physical-close.'+$name)}
    }
    if ($missing.Count) {return $missing}
    foreach ($name in 'handle','pid','exists','visible','enabled','processMainHandle') {
        if ($null -eq $Receipt.window.PSObject.Properties[$name]) {$missing+=@('physical-close.window.'+$name)}
    }
    if ($missing.Count) {return $missing}
    Assert-ColdKeys $Receipt @('observedUtc','window','ui')
    Assert-NativeAcceptanceSame $Ui $Receipt.ui 'ACCEPTANCE_CLOSE_UI';Assert-NativeMainWindow $Receipt.window $Ui
    if ($null -eq $Exit) {return @('physical-close-exit:'+ $Ui.pid)}
    if ((Get-ColdUtcTicks $Receipt.observedUtc) -lt (Get-ColdUtcTicks $Ui.observedAt) -or
        (Get-ColdUtcTicks $Receipt.observedUtc) -gt (Get-ColdUtcTicks $Exit.exitedUtc)) {throw 'ACCEPTANCE_CLOSE_TIME'}
    return @()
}

# Единственная публичная acceptance entry: только frozen файлы и независимые manifest/helper pins.
function Test-NativeConcurrentAcceptance {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$CellEvidence,
        [Parameter(Mandatory)][string]$BaseManifest,[Parameter(Mandatory)][string]$BaseSha256,
        [Parameter(Mandatory)][string]$TargetManifest,[Parameter(Mandatory)][string]$TargetSha256,
        [Parameter(Mandatory)][string]$ExpectedHelperSha256,[string]$SupplementalDirectory=$CellEvidence)
    Import-NativeConcurrentAcceptanceGuards
    try {
        foreach ($pin in @($BaseSha256,$TargetSha256,$ExpectedHelperSha256)) {if ($pin -cnotmatch '^[0-9a-f]{64}$') {throw 'ACCEPTANCE_PIN_FORMAT'}}
        $base=Read-NativeAcceptanceJson $BaseManifest $BaseSha256;$target=Read-NativeAcceptanceJson $TargetManifest $TargetSha256
        if ($null -eq $base -or $null -eq $target) {return [pscustomobject]@{status='PENDING';missing=@('pinned manifests');contradictions=@();kernelPidReuseObserved=$false}}
        Assert-ColdInventory $base.files $base.treeSha256;Assert-ColdInventory $target.files $target.treeSha256
        $bundle=[ordered]@{}
        $files=@{cell='cell.json';launch0='launch-0.json';launch1='launch-1.json';launch2='launch-2.json';peerLaunch='launch-independent-root.json';
            barrierAll='barrier-all.json';barrierStale='barrier-three-with-stale-lease.json';barrierAfter0='barrier-after-exit-0.json';barrierAfter1='barrier-after-exit-1.json';
            exitFinal='exit-final.json';peerExit='exit-independent-root.json';injection='stale-lease-injection.json';observation='stale-lease-observation.json';rootIsolation='root-isolation.json';
            currentBefore='currentBefore.json';currentAfter='currentAfter.json';targetBefore='targetBefore.json';targetAfter='targetAfter.json';userBefore='userBefore.json';userAfter='userAfter.json'}
        foreach ($key in $files.Keys) {$bundle[$key]=Read-NativeAcceptanceJson (Join-Path $CellEvidence $files[$key])}
        for ($i=0;$i -lt 2;$i++) {
            $launch=$bundle['launch'+$i]
            $bundle['exit'+$i]=if ($null -ne $launch) {Read-NativeAcceptanceJson (Join-Path $CellEvidence ('exit-'+$launch.ui.pid+'.json'))} else {$null}
        }
        foreach ($key in 'primaryControlledBefore','primaryControlledAfter','peerControlledBefore','peerControlledAfter','peerUserBefore','peerUserAfter','readyManifest','readyTree','completion',
            'collectorPrimaryBefore','collectorPeerBefore','collectorReady','collectorInstalled','collectorAfter') {
            $bundle[$key]=Read-NativeAcceptanceJson (Join-Path $SupplementalDirectory ($key+'.json'))
        }
        $log=Join-Path $CellEvidence 'update-log.md';$bundle.updateLog=$null
        if (Test-Path -LiteralPath $log -PathType Leaf) {
            $log=Resolve-PortableSafetyPath $log
            if ((Get-Item -LiteralPath $log).Length -gt 8388608) {throw 'ACCEPTANCE_LOG_SIZE'}
            $bundle.updateLog=Get-Content -LiteralPath $log -Raw -Encoding utf8
        }
        $helper=Join-Path $CellEvidence 'production-apply-update.ps1';$bundle.productionHelperHash=$null
        if (Test-Path -LiteralPath $helper -PathType Leaf) {$bundle.productionHelperHash=(Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $helper)).Hash.ToLowerInvariant()}
        $result=Test-NativeConcurrentReceiptContract $bundle $base $target $ExpectedHelperSha256
        # Physical close receipts desktop проверяются отдельно; web uses genuine closeMain API.
        foreach ($key in 'launch0','launch1','launch2','peerLaunch') {
            if ($null -eq $bundle[$key]) {continue};$ui=$bundle[$key].ui
            if ($null -eq $ui -or $null -eq $ui.PSObject.Properties['lease'] -or $null -eq $ui.lease.PSObject.Properties['client'] -or
                $null -eq $ui.PSObject.Properties['pid'] -or $null -eq $ui.PSObject.Properties['startedAtTicks']) {continue}
            if ($ui.lease.client -ceq 'web') {continue}
            $receipt=Read-NativeAcceptanceJson (Join-Path $CellEvidence ('physical-close-window-'+$ui.pid+'-'+$ui.startedAtTicks+'.json'))
            $exit=if ($key -ceq 'peerLaunch') {$bundle.peerExit} elseif ($key -ceq 'launch2') {$bundle.exitFinal} else {$bundle['exit'+$key.Substring(6)]}
            $result.missing+=@(Test-NativeAcceptanceCloseReceipt $receipt $ui $exit)
        }
        $status=if ($result.contradictions.Count) {'FAIL'} elseif ($result.missing.Count) {'PENDING'} else {'PASS'}
        return [pscustomobject]@{status=$status;scope=$result.scope;kernelPidReuseObserved=$false;
            missing=$result.missing;contradictions=$result.contradictions;checks=$result.checks;cellEvidence=$CellEvidence;
            baseManifestSha256=$BaseSha256;targetManifestSha256=$TargetSha256;helperSha256=$ExpectedHelperSha256}
    } catch {
        return [pscustomobject]@{status='FAIL';scope='NATIVE_STALE_LEASE_BIRTH_MISMATCH';kernelPidReuseObserved=$false;
            missing=@();contradictions=@($_.Exception.Message);cellEvidence=$CellEvidence}
    }
}

# Новый collector подключается MAIN только после terminal LIVE run. Он не запускает клиентов.
# Call sites frozen Invoke-NativeConcurrentScenario:
# PrimaryBefore: после seed loop, непосредственно перед $before/$userBefore/$controlled.
# PeerBefore: после $peerRoot=..., перед $peerUser и Open-NativeConcurrentPreparationBarrier.
# Ready: после Assert-ColdTree Ready/tree, до Observe-NativeConcurrentBarrier barrier-all.
# Installed: после root-isolation.json, до Close-NativeNormally peer.
# After: после peer ordinary exit и controlled assertion, до Stop-NativeFixture/fields.finishedAt.
# PeerRoot всегда заранее вычислен как run/independent-root/CashPrediction; остальные параметры
# берутся из тех же local $Base/$Target/$peer/$stale/$exit, не из mock или helper return.
function Save-NativeConcurrentAcceptanceSnapshot {
    [CmdletBinding()]
    param([Parameter(Mandatory)][ValidateSet('PrimaryBefore','PeerBefore','Ready','Installed','After')][string]$Stage,
        [Parameter(Mandatory)][string]$CellEvidence,[Parameter(Mandatory)][string]$PrimaryRoot,
        [Parameter(Mandatory)][string]$PeerRoot,[Parameter(Mandatory)]$Base,[Parameter(Mandatory)]$Target,
        $Peer,$Injection,$FinalExit)
    # Common functions должны быть AST imported MAIN из frozen runner/helper, не mocks.
    $ownedRuns=@()
    foreach ($root in @($PrimaryRoot,$PeerRoot)) {
        $root=Resolve-PortableSafetyPath $root
        if (-not [IO.Path]::IsPathFullyQualified($root)) {throw 'ACCEPTANCE_COLLECTOR_ROOT'}
        $ancestor=[IO.Path]::GetDirectoryName($root);$run=[IO.Path]::GetDirectoryName($ancestor)
        [void](Assert-ColdOwnedRun $run ([IO.Path]::GetTempPath()))
        $ownedRuns+=@($run)
        Assert-PortableTreeHasNoLinks $root
    }
    if ($PrimaryRoot.Equals($PeerRoot,[StringComparison]::OrdinalIgnoreCase) -or
        -not $ownedRuns[0].Equals($ownedRuns[1],[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($CellEvidence) -cne [IO.Path]::GetFileName($ownedRuns[0])) {throw 'ACCEPTANCE_COLLECTOR_ISOLATION'}
    $receipt=[ordered]@{scope='ACTUAL_NATIVE_CONCURRENT_SNAPSHOT';stage=$Stage;primaryRoot=$PrimaryRoot;peerRoot=$PeerRoot;observedUtc=$null}
    switch ($Stage) {
        'PrimaryBefore' {
            if (@(Get-CopyProcesses $PrimaryRoot).Count) {throw 'ACCEPTANCE_BASELINE_TOO_LATE'}
            [void](Assert-ColdTree $PrimaryRoot $Base)
            Write-NativeAcceptanceArtifact $CellEvidence 'primaryControlledBefore' @(Get-ColdControlledInventory $PrimaryRoot)
        }
        'PeerBefore' {
            if (@(Get-CopyProcesses $PeerRoot).Count) {throw 'ACCEPTANCE_BASELINE_TOO_LATE'}
            [void](Assert-ColdTree $PeerRoot $Base)
            Write-NativeAcceptanceArtifact $CellEvidence 'peerUserBefore' (Get-NativeUserObject $PeerRoot)
            Write-NativeAcceptanceArtifact $CellEvidence 'peerControlledBefore' @(Get-ColdControlledInventory $PeerRoot)
        }
        'Ready' {
            $manifest=Read-NativeAcceptanceJson (Join-Path $PrimaryRoot 'CashMemory/Updates/Ready/update.json')
            if ($null -eq $manifest -or $manifest.treeSha256 -cne $Target.treeSha256 -or $manifest.commitSha -cne $Target.commitSha -or $manifest.releaseNumber -ne $Target.releaseNumber) {throw 'ACCEPTANCE_COLLECTOR_READY'}
            Write-NativeAcceptanceArtifact $CellEvidence 'readyManifest' $manifest
            Write-NativeAcceptanceArtifact $CellEvidence 'readyTree' @(Assert-ColdTree (Join-Path $PrimaryRoot 'CashMemory/Updates/Ready/tree') $Target)
        }
        'Installed' {
            if ($null -eq $Peer -or $Peer.root -cne $PeerRoot -or $null -eq $Injection -or $null -eq $FinalExit) {throw 'ACCEPTANCE_COLLECTOR_CONTEXT'}
            $launch=Read-NativeAcceptanceJson (Join-Path $CellEvidence 'launch-2.json')
            if ($null -eq $launch) {throw 'ACCEPTANCE_COLLECTOR_FINAL_UI'}
            Assert-NativeExitReceipt $FinalExit $launch.ui $PrimaryRoot $launch.ui.lease.client
            Assert-NativeConcurrentStaleLease $Injection.lease $PrimaryRoot $Peer $Injection.leasePath
            $samples=[Collections.Generic.List[object]]::new();$clock=[Diagnostics.Stopwatch]::StartNew()
            do {
                $alive=Assert-NativeConcurrentAlive $Peer
                [void](Assert-ColdTree $PrimaryRoot $Target);[void](Assert-ColdTree $PeerRoot $Base)
                $processes=@(Get-CopyProcesses $PrimaryRoot)
                if ($processes.Count) {throw 'ACCEPTANCE_COLLECTOR_RESTART'}
                $samples.Add([pscustomobject]@{observedUtc=[datetime]::UtcNow.ToString('o');primaryProcesses=$processes;peer=$alive})
                [void](Assert-NativeConcurrentAlive $Peer)
                if ($clock.Elapsed.TotalSeconds -gt 30) {throw 'ACCEPTANCE_COLLECTOR_TIMEOUT'}
                if ($samples.Count -ge 2 -and (Get-ColdUtcTicks $samples[-1].observedUtc)-(Get-ColdUtcTicks $samples[0].observedUtc) -ge 30000000L) {break}
                Start-Sleep -Milliseconds 250
            } while ($true)
            $last=Read-NativeAcceptanceJson (Join-Path $PrimaryRoot 'CashMemory/Updates/last-install.json')
            if ($null -eq $last -or $last.outcome -cne 'UPDATED' -or $last.targetCommitSha -cne $Target.commitSha) {throw 'ACCEPTANCE_COLLECTOR_INSTALL_OUTCOME'}
            $leases=@(Get-ChildItem -LiteralPath (Join-Path $PrimaryRoot 'CashMemory/Updates/processes') -File -ErrorAction Stop)
            $requests=@(Get-ChildItem -LiteralPath (Join-Path $PrimaryRoot 'CashMemory/Updates/requests') -File -ErrorAction Stop)
            $stale=[bool](Test-Path -LiteralPath $Injection.leasePath)
            $journal=[bool](Test-Path -LiteralPath (Join-Path $PrimaryRoot 'CashMemory/Updates/install-journal.json'))
            $completed=[bool](Test-Path -LiteralPath (Join-Path $PrimaryRoot 'CashMemory/Updates/completed-journal.json'))
            if ($leases.Count -or $requests.Count -or $stale -or $journal -or $completed) {throw 'ACCEPTANCE_COLLECTOR_REMAINS'}
            $receipt.observedUtc=[datetime]::UtcNow.ToString('o')
            Write-NativeAcceptanceArtifact $CellEvidence 'completion' ([ordered]@{scope='ACTUAL_NATIVE_CONCURRENT_COMPLETION';
                observedUtc=$receipt.observedUtc;lastInstall=$last;primaryProcesses=@();primaryLeases=@();
                staleLeasePresent=$stale;installJournalPresent=$journal;completedJournalPresent=$completed;noRestartSamples=@($samples.ToArray())})
        }
        'After' {
            foreach ($root in @($PrimaryRoot,$PeerRoot)) {if (@(Get-CopyProcesses $root).Count) {throw 'ACCEPTANCE_AFTER_STILL_ALIVE'}}
            [void](Assert-ColdTree $PrimaryRoot $Target);[void](Assert-ColdTree $PeerRoot $Base)
            Write-NativeAcceptanceArtifact $CellEvidence 'primaryControlledAfter' @(Get-ColdControlledInventory $PrimaryRoot)
            Write-NativeAcceptanceArtifact $CellEvidence 'peerControlledAfter' @(Get-ColdControlledInventory $PeerRoot)
            Write-NativeAcceptanceArtifact $CellEvidence 'peerUserAfter' (Get-NativeUserObject $PeerRoot)
        }
    }
    if ($null -eq $receipt.observedUtc) {$receipt.observedUtc=[datetime]::UtcNow.ToString('o')}
    Write-NativeAcceptanceArtifact $CellEvidence ('collector'+$Stage) $receipt
}

# Каждый collector artifact CreateNew: поздний повтор не может переписать baseline.
function Write-NativeAcceptanceArtifact([string]$Directory,[string]$Name,$Value) {
    if ($Name -cnotmatch '^[A-Za-z]+$' -or -not [IO.Path]::IsPathFullyQualified($Directory)) {throw 'ACCEPTANCE_ARTIFACT_PATH'}
    $safe=Resolve-PortableSafetyPath (Join-Path $Directory ($Name+'.json'))
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $Value -Depth 64))
    if ($bytes.Length -gt 33554432) {throw 'ACCEPTANCE_ARTIFACT_SIZE'}
    $stream=[IO.FileStream]::new($safe,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
}

# Проверенная локальная AST копия добавляет только collector hooks; frozen файл не меняется.
# Закрытые anchors и pin исключают молчаливую адаптацию к другой версии runner.
function Get-NativeConcurrentCollectedDefinition([string]$ExpectedConcurrentSha256) {
    $path=Resolve-PortableSafetyPath (Join-Path $PSScriptRoot 'NativeUpdateConcurrentScenarios.ps1')
    if ($ExpectedConcurrentSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $ExpectedConcurrentSha256) {throw 'ACCEPTANCE_CONCURRENT_PIN'}
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true) | Where-Object {$_.Name -ceq 'Invoke-NativeConcurrentScenario'})
    if ($errors.Count -or $definitions.Count -ne 1) {throw 'ACCEPTANCE_CONCURRENT_AST'}
    $text=$definitions[0].Extent.Text -replace '^function Invoke-NativeConcurrentScenario','function Invoke-NativeConcurrentCollectedScenario'
    $common=' -CellEvidence $cellEvidence -PrimaryRoot $root -PeerRoot $roots[1] -Base $Base -Target $Target'
    $hooks=[ordered]@{
        '$before=@(Assert-ColdTree $root $Base);$userBefore=Get-NativeUserObject $root'=('if ($clientNames.Count -eq 3) {Save-NativeConcurrentAcceptanceSnapshot -Stage PrimaryBefore'+$common+'}')
        '$peerRoot=$roots[1];$peerUser=Get-NativeUserObject $peerRoot;'=('Save-NativeConcurrentAcceptanceSnapshot -Stage PeerBefore'+$common)
        '$proofs=[Collections.Generic.List[string]]::new()'=('if ($clientNames.Count -eq 3) {Save-NativeConcurrentAcceptanceSnapshot -Stage Ready'+$common+'}')
        '$peerExit=Close-NativeNormally $peer.native $peer.root $peer.client $Timeout $cellEvidence'=('Save-NativeConcurrentAcceptanceSnapshot -Stage Installed'+$common+' -Peer $peer -Injection $stale -FinalExit $exit')
        '$http=Stop-NativeFixture $server $cellEvidence ''server-concurrent'''=('if ($null -ne $peer) {Save-NativeConcurrentAcceptanceSnapshot -Stage After'+$common+'}')
    }
    foreach ($anchor in $hooks.Keys) {
        if ([regex]::Matches($text,[regex]::Escape($anchor)).Count -ne 1) {throw 'ACCEPTANCE_CONCURRENT_HOOK_ANCHOR'}
        $text=$text.Replace($anchor,($hooks[$anchor]+"`n        "+$anchor))
    }
    $tokens=$null;$errors=$null
    [void][Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'ACCEPTANCE_COLLECTED_PARSE'}
    return $text
}

# MAIN entry совместим с первыми девятью параметрами Invoke-NativeCell.
# AcceptancePins: baseManifest,baseSha256,targetManifest,targetSha256,helperSha256,
# concurrentSha256 - независимые уже frozen конфигурационные pins MAIN.
# После LIVE terminal MAIN импортирует обычные frozen функции и dot-sources этот файл.
# Вызов adapter сам снимает actual evidence в нужном порядке и сохраняет acceptance.json.
# Даже его PASS не меняет Row.status/counts/signoff: окончательное принятие принадлежит MAIN.
function Invoke-NativeConcurrentScenarioWithAcceptance {
    [CmdletBinding()]
    param($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout,
        [Parameter(Mandatory)]$AcceptancePins)
    if ($Row.scenario -cne 'three-clients-pid-root-isolation') {throw 'ACCEPTANCE_ADAPTER_SCENARIO'}
    foreach ($key in 'baseManifest','baseSha256','targetManifest','targetSha256','helperSha256','concurrentSha256') {
        if ($null -eq $AcceptancePins.PSObject.Properties[$key]) {throw 'ACCEPTANCE_ADAPTER_PINS'}
    }
    Import-NativeConcurrentAcceptanceGuards
    # Параметры actual запуска должны совпасть с independently pinned manifests до launch.
    $pinnedBase=Read-NativeAcceptanceJson $AcceptancePins.baseManifest $AcceptancePins.baseSha256
    $pinnedTarget=Read-NativeAcceptanceJson $AcceptancePins.targetManifest $AcceptancePins.targetSha256
    if ($null -eq $pinnedBase -or $null -eq $pinnedTarget -or $Cold.helperSha256 -cne $AcceptancePins.helperSha256) {throw 'ACCEPTANCE_ADAPTER_CONTEXT'}
    foreach ($pair in @(@($Base,$pinnedBase),@($Target,$pinnedTarget))) {
        if ($pair[0].releaseNumber -ne $pair[1].releaseNumber -or $pair[0].commitSha -cne $pair[1].commitSha -or $pair[0].treeSha256 -cne $pair[1].treeSha256) {throw 'ACCEPTANCE_ADAPTER_MANIFEST'}
        Assert-NativeAcceptanceTree $pair[0].files $pair[1]
    }
    $definition=Get-NativeConcurrentCollectedDefinition $AcceptancePins.concurrentSha256
    . ([scriptblock]::Create($definition))
    Invoke-NativeConcurrentCollectedScenario $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
    # Original finally уже сохранил окончательный cell.json и cleanup outcome.
    $result=Test-NativeConcurrentAcceptance -CellEvidence $Row.concurrentCellEvidence -BaseManifest $AcceptancePins.baseManifest -BaseSha256 $AcceptancePins.baseSha256 `
        -TargetManifest $AcceptancePins.targetManifest -TargetSha256 $AcceptancePins.targetSha256 -ExpectedHelperSha256 $AcceptancePins.helperSha256
    Write-NativeAcceptanceArtifact $Row.concurrentCellEvidence 'acceptance' $result
    return $result
}
