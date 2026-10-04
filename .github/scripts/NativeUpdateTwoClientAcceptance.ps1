<#
.SYNOPSIS
Read-only принятие two-clients: ожидание живого peer, identity/birth и полные tree receipts.
.DESCRIPTION
Только определения. Helper PASS не доказательство. Missing=PENDING, contradiction=FAIL.
Test-NativeTwoClientAcceptance сохраняет семь параметров concurrent bridge и добавляет
IndependentSha256, EvidenceKind (default UNVERIFIED). Отдельный supplemental файл
two-client-independent.json должен быть закреплён MAIN, не скопирован из helper return.
Pure Test-NativeTwoClientReceiptContract возвращает SATISFIED, не native PASS.
Disk entry даёт PASS только SATISFIED+EvidenceKind=NATIVE, не меняет Row/612/global signoff.
Chandrasekhar integration: NativeUpdateAcceptanceDispatch.ps1, concurrent branch
Invoke-NativeAcceptanceRouteRead: для row.scenario='two-clients' импортировать этот файл,
вызвать Test-NativeTwoClientAcceptance с прежними семью Evidence полями плюс
Evidence.IndependentSha256 и kind. Удалить только TWO_CLIENT_ACCEPTOR_NOT_PRODUCED guard
для этой ветви; expected scope/validated gate='NATIVE_TWO_CLIENT_WAIT_BARRIER', proofComplete=true.
Добавить файл в frozen allowlist/pins; three-client acceptor не менять. Здесь bridge не изменён.
Producer отсутствует в frozen helper: MAIN должен до dispose снять actual baseline/Ready,
два окна observations allAlive/peerAlive вокруг barrier-all/barrier-after-exit-0,
fresh retained identity/UI до И после каждого полного managed tree hash, actual journal/log,
после final ordinary exit - completion census/lastInstall, затем cleanup census/registry.
Sample: startedAt,finishedAt,aliveBefore,aliveAfter,treeFiles,replacementPhases,lastInstallPresent.
Independent schema1: cellKey,root,baselineObservedAt,readyObservedAt,currentBefore,targetBefore,
targetAfter,userBefore,userAfter,readyManifest,readyTree,allAlive,peerAlive,completion,cleanup.
Completion: observedAt,lastInstall,currentAfter,processes,leases,requests,journalPresent,completedJournalPresent.
Cleanup: observedAt,errors,processes,helpers,registryUnchanged,forcedCleanup.
Нельзя восстановить ранние observations после cleanup. JSON pin фиксирует bytes, не native origin;
NATIVE - явное подтверждение MAIN настоящих readers, не self-reported поле внутри JSON.
Это bounded sampling, не непрерывная/kernel/foreground гарантия; никаких пауз или fake receipts.
#>

# Exact AST imports только pure/disk guards, не native тела или collector функций.
function Import-NativeTwoClientAcceptanceGuards([string]$ScriptsRoot=$PSScriptRoot) {
    $sources=[ordered]@{
        'Test-Portable.ps1'=@('Resolve-PortableSafetyPath')
        'Test-UpdateBootstrap.ps1'=@('Assert-ColdKeys','Test-ColdInteger','Get-ColdLauncherName','Get-ColdUtcTicks',
            'ConvertFrom-ColdReceiptJson','ConvertFrom-ColdCommandLine','Test-ColdInventoryEqual',
            'Assert-ColdProcessIdentity','Assert-ColdUiReceipt','Get-ColdTreeHash','Assert-ColdInventory')
        'Test-NativeUpdateLifecycle.ps1'=@('Assert-NativeMainWindow','Assert-NativeExitReceipt')
        'NativeUpdateConcurrentAcceptance.ps1'=@('Read-NativeAcceptanceJson','Assert-NativeAcceptanceSame',
            'Assert-NativeAcceptanceTree','Assert-NativeAcceptanceUser','Assert-NativeAcceptanceBarrier',
            'Assert-NativeAcceptanceAlive','Test-NativeAcceptanceCloseReceipt')
    }
    foreach ($file in $sources.Keys) {
        $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot $file),[ref]$tokens,[ref]$errors)
        if ($errors.Count) {throw 'TWO_ACCEPT_IMPORT_PARSE'}
        foreach ($name in $sources[$file]) {
            $defs=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $name})
            if ($defs.Count -ne 1) {throw ('TWO_ACCEPT_IMPORT '+$name)}
            $text=$defs[0].Extent.Text -replace ('^function '+[regex]::Escape($name)),('function global:'+$name)
            . ([scriptblock]::Create($text))
        }
    }
}

# Отсутствие nested поля остаётся gap; неверный объект считается contradiction.
function Assert-NativeTwoClientFields($Value,[string[]]$Names) {
    if ($null -eq $Value) {throw 'TWO_ACCEPT_MISSING:object'}
    if ($Value -isnot [pscustomobject]) {throw 'TWO_ACCEPT_OBJECT'}
    foreach ($name in $Names) {if ($null -eq $Value.PSObject.Properties[$name] -or $null -eq $Value.$name) {throw ('TWO_ACCEPT_MISSING:'+ $name)}}
}

# Presence проверяется до strict wire guards: missing witness/birth не становится случайным FAIL.
function Assert-NativeTwoClientUiFields($Ui) {
    Assert-NativeTwoClientFields $Ui @('pid','startedAtTicks','executablePath','modules','witness','lease','commandLine','args','observedAt')
    Assert-NativeTwoClientFields $Ui.lease @('schemaVersion','leaseId','pid','startedAtEpochMillis','installationRoot','client')
    $keys=if ($Ui.lease.client -ceq 'web') {@('kind','port','status','bodySha256','owningProcess')} else {@('kind','handle','title')}
    Assert-NativeTwoClientFields $Ui.witness $keys
}

# Fresh UI имеет новое observedAt; stable identity/lease/argv привязываются к actual launch receipt.
function Assert-NativeTwoClientFreshAlive($Alive,$Launch,[string]$Root,[long]$After,[long]$Before) {
    Assert-NativeTwoClientFields $Alive @('ui','identity','lease','leasePath','observedUtc')
    Assert-ColdKeys $Alive @('ui','identity','lease','leasePath','observedUtc')
    $ui=$Alive.ui;$original=$Launch.ui
    Assert-NativeTwoClientUiFields $ui
    Assert-NativeTwoClientFields $Alive.identity @('ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot')
    Assert-ColdUiReceipt $ui $Root $original.lease.client
    Assert-ColdProcessIdentity $Alive.identity ([pscustomobject]@{ProcessId=$original.pid;StartedAtTicks=$original.startedAtTicks;
        ExecutablePath=$original.executablePath;OwnedRoot=$Root}) $Root $original.executablePath
    foreach ($field in 'pid','startedAtTicks','executablePath','modules','lease','commandLine','args') {
        Assert-NativeAcceptanceSame $original.$field $ui.$field ('TWO_ACCEPT_FRESH_BINDING_'+$field)
    }
    Assert-NativeAcceptanceSame $original.lease $Alive.lease 'TWO_ACCEPT_LEASE'
    $observed=Get-ColdUtcTicks $ui.observedAt;$read=Get-ColdUtcTicks $Alive.observedUtc
    if ($observed -lt $After -or $observed -gt $read -or $read -gt $Before -or
        $Alive.leasePath -cne (Join-Path $Root ('CashMemory/Updates/processes/'+$ui.lease.leaseId+'.json'))) {throw 'TWO_ACCEPT_FRESH_TIME'}
}

# Реальные inventories между двумя fresh identity reads; window доказывает только bounded наблюдения.
function Assert-NativeTwoClientWaitWindow($Samples,$Launches,[string]$Root,$Base,[long]$After,[long]$Before) {
    if (@($Samples).Count -lt 2 -or @($Samples).Count -gt 1000) {throw 'TWO_ACCEPT_WAIT_SAMPLES'}
    $previous=$After;$first=$null;$last=$null
    foreach ($sample in $Samples) {
        Assert-NativeTwoClientFields $sample @('startedAt','finishedAt','aliveBefore','aliveAfter','treeFiles','replacementPhases','lastInstallPresent')
        Assert-ColdKeys $sample @('startedAt','finishedAt','aliveBefore','aliveAfter','treeFiles','replacementPhases','lastInstallPresent')
        $begin=Get-ColdUtcTicks $sample.startedAt;$end=Get-ColdUtcTicks $sample.finishedAt
        if ($begin -lt $previous -or $end -lt $begin -or $end -gt $Before -or
            @($sample.aliveBefore).Count -ne @($Launches).Count -or @($sample.aliveAfter).Count -ne @($Launches).Count -or
            @($sample.replacementPhases).Count -or $sample.lastInstallPresent -isnot [bool] -or $sample.lastInstallPresent) {throw 'TWO_ACCEPT_REPLACEMENT_WHILE_PEER_ALIVE'}
        for ($i=0;$i -lt @($Launches).Count;$i++) {
            Assert-NativeTwoClientFreshAlive @($sample.aliveBefore)[$i] @($Launches)[$i] $Root $begin $end
            Assert-NativeTwoClientFreshAlive @($sample.aliveAfter)[$i] @($Launches)[$i] $Root (Get-ColdUtcTicks @($sample.aliveBefore)[$i].observedUtc) $end
        }
        Assert-NativeAcceptanceTree $sample.treeFiles $Base
        if ($null -eq $first) {$first=$begin};$last=$end;$previous=$end
    }
    if ($last-$first -lt 30000000L -or $last-$first -gt 600000000L) {throw 'TWO_ACCEPT_WAIT_DURATION'}
}

# Pure contract: отдельно проверяет доступные группы, отсутствие других не скрывает contradiction.
function Test-NativeTwoClientReceiptContract($Bundle,$Base,$Target,$Independent,[string]$ExpectedHelperSha256) {
    $copy=[ordered]@{};foreach ($key in $Bundle.Keys) {$copy[$key]=$Bundle[$key]};$Bundle=$copy
    $missing=[Collections.Generic.List[string]]::new();$bad=[Collections.Generic.List[string]]::new();$checks=[Collections.Generic.List[string]]::new()
    function Check([string]$Name,[string[]]$Keys,[scriptblock]$Body) {
        $absent=$false
        foreach ($key in $Keys) {if (-not $Bundle.Contains($key) -or $null -eq $Bundle[$key]) {$missing.Add($key);$absent=$true}}
        if ($absent) {return}
        try {& $Body | Out-Null;$checks.Add($Name)} catch {
            if ($_.FullyQualifiedErrorId -like '*PropertyNotFound*' -or $_.Exception.Message -clike 'TWO_ACCEPT_MISSING:*') {$missing.Add($Name+':field')}
            else {$bad.Add($Name+':'+$_.Exception.Message)}
        }
    }
    $Bundle['independent']=$Independent
    Check 'manifests' @() {
        foreach ($manifest in @($Base,$Target)) {
            Assert-NativeTwoClientFields $manifest @('releaseNumber','commitSha','files','treeSha256')
            if (-not (Test-ColdInteger $manifest.releaseNumber 1) -or $manifest.commitSha -cnotmatch '^[0-9a-f]{40}$') {throw 'TWO_ACCEPT_MANIFEST'}
            Assert-NativeAcceptanceTree $manifest.files $manifest
        }
    }
    Check 'row' @('cell') {
        $r=$Bundle.cell
        if ($r.scenario -cne 'two-clients' -or $r.phase -cne 'SESSION' -or $r.client -cnotin @('fx','swing','web') -or
            $r.base -cnotin @('B1','B2') -or $r.path -cnotin @('ascii','cyrillic','unicode') -or $r.status -cnotin @('PASS','PENDING') -or
            $r.executed -isnot [bool] -or -not $r.executed -or $r.baseCommit -cne $Base.commitSha -or $r.targetCommit -cne $Target.commitSha -or
            $r.baseRelease -ne $Base.releaseNumber -or $r.targetRelease -ne $Target.releaseNumber -or $Target.releaseNumber -le $Base.releaseNumber) {throw 'TWO_ACCEPT_ROW'}
        foreach ($code in @($r.exitCode,$r.failures,$r.skipped)) {if (-not (Test-ColdInteger $code) -or $code -ne 0) {throw 'TWO_ACCEPT_EXIT_CODE'}}
    }
    Check 'launches' @('cell','launch0','launch1') {
        $clients=@('fx','swing','web');$index=[Array]::IndexOf($clients,[string]$Bundle.cell.client)
        $root=$Bundle.launch0.ui.lease.installationRoot
        $run=$Bundle.cell.runRoot
        if (-not [IO.Path]::IsPathFullyQualified($run) -or [IO.Path]::GetFullPath($run) -cne $run -or
            [IO.Path]::GetFileName($run) -cnotmatch '^run-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
            -not [IO.Path]::GetDirectoryName($run).Equals([IO.Path]::GetTempPath().TrimEnd('\','/'),[StringComparison]::OrdinalIgnoreCase) -or
            -not $root.StartsWith($run+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'TWO_ACCEPT_ROOT'}
        $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
        if ($root -cne (Join-Path (Join-Path $run $variants[$Bundle.cell.path]) 'CashPrediction')) {throw 'TWO_ACCEPT_ROOT_VARIANT'}
        for ($i=0;$i -lt 2;$i++) {
            $l=$Bundle['launch'+$i];$ui=$l.ui
            Assert-NativeTwoClientUiFields $ui
            Assert-NativeTwoClientFields $l.launcher @('ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot')
            Assert-ColdUiReceipt $ui $root $clients[($index+$i)%3]
            Assert-ColdProcessIdentity $l.launcher $l.launcher $root $ui.executablePath
            if ($l.launcher.StartedAtTicks -lt (Get-ColdUtcTicks $l.startedAt) -or $ui.startedAtTicks -lt $l.launcher.StartedAtTicks) {throw 'TWO_ACCEPT_LAUNCH_BIRTH'}
            Assert-NativeAcceptanceSame @($l.args) @($ui.args) 'TWO_ACCEPT_ARGS'
        }
        if ($Bundle.launch0.ui.pid -eq $Bundle.launch1.ui.pid -or $Bundle.launch0.ui.lease.leaseId -ceq $Bundle.launch1.ui.lease.leaseId) {throw 'TWO_ACCEPT_DISTINCT'}
    }
    Check 'ordinary-exits' @('launch0','launch1','exit0','exitFinal') {
        $e=$Bundle.exit0;$ui=$Bundle.launch0.ui
        Assert-NativeTwoClientFields $e @('pid','startedAtTicks','exitCode','exitedUtc','remainingUi','survivors','kind')
        Assert-ColdKeys $e @('pid','startedAtTicks','exitCode','exitedUtc','remainingUi','survivors','kind')
        if (-not (Test-ColdInteger $e.pid 1) -or $e.pid -ne $ui.pid -or -not (Test-ColdInteger $e.startedAtTicks 1) -or $e.startedAtTicks -ne $ui.startedAtTicks -or
            -not (Test-ColdInteger $e.exitCode) -or $e.exitCode -ne 0 -or -not (Test-ColdInteger $e.remainingUi 1) -or $e.remainingUi -ne 1 -or $e.kind -cne 'ordinary-intermediate-no-restart') {throw 'TWO_ACCEPT_FIRST_EXIT'}
        Assert-NativeAcceptanceSame @($Bundle.launch1.ui) @($e.survivors) 'TWO_ACCEPT_SURVIVOR'
        Assert-NativeTwoClientFields $Bundle.exitFinal @('pid','startedAtTicks','exitCode','exitedUtc','remainingClients','kind')
        Assert-NativeTwoClientUiFields $Bundle.launch1.ui
        Assert-NativeExitReceipt $Bundle.exitFinal $Bundle.launch1.ui $Bundle.launch1.ui.lease.installationRoot $Bundle.launch1.ui.lease.client
        if ((Get-ColdUtcTicks $e.exitedUtc) -lt (Get-ColdUtcTicks $ui.observedAt) -or (Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc) -le (Get-ColdUtcTicks $e.exitedUtc)) {throw 'TWO_ACCEPT_EXIT_ORDER'}
    }
    Check 'helper-barriers' @('launch0','launch1','exit0','exitFinal','barrierAll','barrierAfter0') {
        foreach ($name in 'barrierAll','barrierAfter0') {
            Assert-NativeTwoClientFields $Bundle[$name] @('scope','windowMillis','status','elapsedMillis','samples')
            foreach ($sample in $Bundle[$name].samples) {
                Assert-NativeTwoClientFields $sample @('elapsedMillis','observedUtc','alive','treeSha256')
                foreach ($alive in $sample.alive) {
                    Assert-NativeTwoClientFields $alive @('ui','identity','lease','leasePath','observedUtc')
                    Assert-NativeTwoClientFields $alive.identity @('ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot')
                    Assert-NativeTwoClientUiFields $alive.ui
                }
            }
        }
        $root=$Bundle.launch0.ui.lease.installationRoot;$exit=Get-ColdUtcTicks $Bundle.exit0.exitedUtc;$final=Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc
        Assert-NativeAcceptanceBarrier $Bundle.barrierAll @($Bundle.launch0.ui,$Bundle.launch1.ui) $root $Base (Get-ColdUtcTicks $Bundle.launch1.ui.observedAt) $exit
        Assert-NativeAcceptanceBarrier $Bundle.barrierAfter0 @($Bundle.launch1.ui) $root $Base $exit $final
    }
    foreach ($pair in @(@('currentBefore',$Base),@('currentAfter',$Target),@('targetBefore',$Target),@('targetAfter',$Target))) {
        $key=$pair[0];$manifest=$pair[1];Check $key @($key) {Assert-NativeAcceptanceTree $Bundle[$key] $manifest}
    }
    Check 'user' @('userBefore','userAfter') {Assert-NativeAcceptanceUser $Bundle.userBefore $Bundle.userAfter}
    Check 'helper-pin' @('productionHelperHash') {
        if (-not $ExpectedHelperSha256) {$missing.Add('fresh helper pin');return}
        if ($ExpectedHelperSha256 -cnotmatch '^[0-9a-f]{64}$' -or $Bundle.productionHelperHash -cne $ExpectedHelperSha256) {throw 'TWO_ACCEPT_HELPER_PIN'}
    }
    Check 'replacement-log' @('updateLog','exitFinal') {
        $commits=0;$final=Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc
        foreach ($line in $Bundle.updateLog -split '\r?\n') {
            if ($line -cmatch '^- (\S+) PHASE_(BOOTSTRAPPING|BACKING_UP|INSTALLING|VERIFYING|COMMITTED|ROLLING_BACK)$') {
                if ((Get-ColdUtcTicks $Matches[1]) -lt $final -or $Matches[2] -ceq 'ROLLING_BACK') {throw 'TWO_ACCEPT_EARLY_REPLACEMENT'}
                if ($Matches[2] -ceq 'COMMITTED') {$commits++}
            }
        }
        if ($commits -ne 1) {throw 'TWO_ACCEPT_COMMIT'}
    }
    Check 'independent' @('independent','cell','launch0','launch1','exit0','exitFinal') {
        $p=$Independent;$r=$Bundle.cell;$root=$Bundle.launch0.ui.lease.installationRoot
        Assert-NativeTwoClientFields $p @('schemaVersion','cellKey','root','baselineObservedAt','readyObservedAt','currentBefore','targetBefore',
            'targetAfter','userBefore','userAfter','readyManifest','readyTree','allAlive','peerAlive','completion','cleanup')
        if ($p.schemaVersion -isnot [int] -or $p.schemaVersion -ne 1 -or $p.cellKey -cne ('two-clients/'+$r.base+'/'+$r.client+'/'+$r.path+'/SESSION') -or $p.root -cne $root) {throw 'TWO_ACCEPT_INDEPENDENT_CONTEXT'}
        $begin=Get-ColdUtcTicks $r.startedAt;$finished=Get-ColdUtcTicks $r.finishedAt;$baseline=Get-ColdUtcTicks $p.baselineObservedAt;$ready=Get-ColdUtcTicks $p.readyObservedAt
        if ($baseline -lt $begin -or $baseline -gt (Get-ColdUtcTicks $Bundle.launch0.startedAt) -or $ready -lt (Get-ColdUtcTicks $Bundle.launch1.ui.observedAt) -or $ready -gt (Get-ColdUtcTicks $Bundle.exit0.exitedUtc)) {throw 'TWO_ACCEPT_OBSERVATION_ORDER'}
        foreach ($pair in @(@('currentBefore',$Base),@('targetBefore',$Target),@('targetAfter',$Target),@('readyTree',$Target))) {Assert-NativeAcceptanceTree $p.($pair[0]) $pair[1]}
        Assert-NativeAcceptanceSame $Target $p.readyManifest 'TWO_ACCEPT_READY_PIN'
        Assert-NativeAcceptanceUser $p.userBefore $p.userAfter
        if ($null -ne $Bundle.userBefore) {Assert-NativeAcceptanceSame $Bundle.userBefore $p.userBefore 'TWO_ACCEPT_USER_BINDING'}
        if ($null -ne $Bundle.userAfter) {Assert-NativeAcceptanceSame $Bundle.userAfter $p.userAfter 'TWO_ACCEPT_USER_BINDING'}
        Assert-NativeTwoClientWaitWindow $p.allAlive @($Bundle.launch0,$Bundle.launch1) $root $Base $ready (Get-ColdUtcTicks $Bundle.exit0.exitedUtc)
        Assert-NativeTwoClientWaitWindow $p.peerAlive @($Bundle.launch1) $root $Base (Get-ColdUtcTicks $Bundle.exit0.exitedUtc) (Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc)
        $c=$p.completion;Assert-NativeTwoClientFields $c @('observedAt','lastInstall','currentAfter','processes','leases','requests','journalPresent','completedJournalPresent')
        Assert-NativeTwoClientFields $c.lastInstall @('outcome','targetCommitSha')
        Assert-ColdKeys $c @('observedAt','lastInstall','currentAfter','processes','leases','requests','journalPresent','completedJournalPresent')
        $complete=Get-ColdUtcTicks $c.observedAt
        if ($complete -lt (Get-ColdUtcTicks $Bundle.exitFinal.exitedUtc) -or $complete -gt $finished -or
            $c.lastInstall.outcome -cne 'UPDATED' -or $c.lastInstall.targetCommitSha -cne $Target.commitSha -or @($c.processes).Count -or @($c.leases).Count -or @($c.requests).Count -or
            $c.journalPresent -isnot [bool] -or $c.journalPresent -or $c.completedJournalPresent -isnot [bool] -or $c.completedJournalPresent) {throw 'TWO_ACCEPT_COMPLETION'}
        Assert-NativeAcceptanceTree $c.currentAfter $Target
        $cleanup=$p.cleanup;Assert-NativeTwoClientFields $cleanup @('observedAt','errors','processes','helpers','registryUnchanged','forcedCleanup')
        Assert-ColdKeys $cleanup @('observedAt','errors','processes','helpers','registryUnchanged','forcedCleanup')
        if ((Get-ColdUtcTicks $cleanup.observedAt) -lt $complete -or @($cleanup.errors).Count -or @($cleanup.processes).Count -or @($cleanup.helpers).Count -or
            $cleanup.registryUnchanged -isnot [bool] -or -not $cleanup.registryUnchanged -or $cleanup.forcedCleanup -isnot [bool] -or $cleanup.forcedCleanup) {throw 'TWO_ACCEPT_CLEANUP'}
        # Cleanup может быть до row.finishedAt; сравниваем только с completion, не выдуманной хронологией.
    }
    foreach ($i in 0,1) {
        $launchKey='launch'+$i;$closeKey='close'+$i;$exitKey=if ($i -eq 0) {'exit0'} else {'exitFinal'}
        Check ('physical-close-'+$i) @($launchKey,$exitKey) {
            $ui=$Bundle[$launchKey].ui
            if ($ui.lease.client -ceq 'web') {return}
            if (-not $Bundle.Contains($closeKey)) {$missing.Add($closeKey);return}
            foreach ($gap in @(Test-NativeAcceptanceCloseReceipt $Bundle[$closeKey] $ui $Bundle[$exitKey])) {$missing.Add($gap)}
        }
    }
    return [pscustomobject]@{contractStatus=$(if ($bad.Count) {'FAIL'} elseif ($missing.Count) {'PENDING'} else {'SATISFIED'});
        scope='NATIVE_TWO_CLIENT_WAIT_BARRIER';missing=@($missing | Sort-Object -Unique);contradictions=$bad.ToArray();checks=$checks.ToArray()}
}

# Disk bridge entry: independent SHA обязателен для принятия, missing pin/file не становится PASS.
function Test-NativeTwoClientAcceptance([string]$CellEvidence,[string]$BaseManifest,[string]$BaseSha256,
    [string]$TargetManifest,[string]$TargetSha256,[string]$ExpectedHelperSha256,[string]$SupplementalDirectory=$CellEvidence,
    [string]$IndependentSha256='', [ValidateSet('UNVERIFIED','NATIVE','UNIT_MOCK')][string]$EvidenceKind='UNVERIFIED') {
    Import-NativeTwoClientAcceptanceGuards
    try {
        foreach ($pin in @($BaseSha256,$TargetSha256,$ExpectedHelperSha256,$IndependentSha256)) {
            if ($pin -and $pin -cnotmatch '^[0-9a-f]{64}$') {throw 'TWO_ACCEPT_PIN_FORMAT'}
        }
        $missing=@()
        foreach ($pin in @($BaseSha256,$TargetSha256,$ExpectedHelperSha256,$IndependentSha256)) {if (-not $pin) {$missing+=@('fresh independent pins')}}
        $base=if ($BaseSha256) {Read-NativeAcceptanceJson $BaseManifest $BaseSha256} else {$null}
        $target=if ($TargetSha256) {Read-NativeAcceptanceJson $TargetManifest $TargetSha256} else {$null}
        if ($null -eq $base -or $null -eq $target) {return [pscustomobject]@{status='PENDING';scope='NATIVE_TWO_CLIENT_WAIT_BARRIER';proofComplete=$false;missing=@('pinned manifests');contradictions=@()}}
        $bundle=[ordered]@{}
        foreach ($name in 'cell','launch0','launch1','barrierAll','barrierAfter0','exitFinal','currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter') {
            $file=switch ($name) {'launch0' {'launch-0'} 'launch1' {'launch-1'} 'barrierAll' {'barrier-all'} 'barrierAfter0' {'barrier-after-exit-0'} 'exitFinal' {'exit-final'} default {$name}}
            $bundle[$name]=Read-NativeAcceptanceJson (Join-Path $CellEvidence ($file+'.json'))
        }
        $bundle.exit0=if ($null -ne $bundle.launch0) {Read-NativeAcceptanceJson (Join-Path $CellEvidence ('exit-'+$bundle.launch0.ui.pid+'.json'))} else {$null}
        foreach ($i in 0,1) {
            $launch=$bundle['launch'+$i]
            $bundle['close'+$i]=if ($null -ne $launch -and $launch.ui.lease.client -cne 'web') {
                Read-NativeAcceptanceJson (Join-Path $CellEvidence ('physical-close-window-'+$launch.ui.pid+'-'+$launch.ui.startedAtTicks+'.json'))
            } else {$null}
        }
        $bundle.updateLog=$null;$log=Join-Path $CellEvidence 'update-log.md'
        if (Test-Path -LiteralPath $log -PathType Leaf) {
            $log=Resolve-PortableSafetyPath $log;if ((Get-Item -LiteralPath $log).Length -gt 8388608) {throw 'TWO_ACCEPT_LOG_SIZE'}
            $bundle.updateLog=Get-Content -LiteralPath $log -Raw -Encoding utf8
        }
        $bundle.productionHelperHash=$null;$helper=Join-Path $CellEvidence 'production-apply-update.ps1'
        if (Test-Path -LiteralPath $helper -PathType Leaf) {$bundle.productionHelperHash=(Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $helper)).Hash.ToLowerInvariant()}
        $independent=if ($IndependentSha256) {Read-NativeAcceptanceJson (Join-Path $SupplementalDirectory 'two-client-independent.json') $IndependentSha256} else {$null}
        $result=Test-NativeTwoClientReceiptContract $bundle $base $target $independent $ExpectedHelperSha256
        $missing+=@($result.missing)
        if ($EvidenceKind -cne 'NATIVE') {$missing+=@('MAIN_ACTUAL_NATIVE_PROVENANCE_REQUIRED')}
        $status=if ($result.contradictions.Count) {'FAIL'} elseif ($missing.Count) {'PENDING'} else {'PASS'}
        return [pscustomobject]@{status=$status;scope=$result.scope;proofComplete=($status -ceq 'PASS');missing=$missing;
            contradictions=$result.contradictions;checks=$result.checks;cellEvidence=$CellEvidence;independentSha256=$IndependentSha256}
    } catch {
        if ($_.FullyQualifiedErrorId -like '*PropertyNotFound*') {return [pscustomobject]@{status='PENDING';scope='NATIVE_TWO_CLIENT_WAIT_BARRIER';proofComplete=$false;missing=@('nested receipt fields');contradictions=@()}}
        return [pscustomobject]@{status='FAIL';scope='NATIVE_TWO_CLIENT_WAIT_BARRIER';proofComplete=$false;missing=@();contradictions=@($_.Exception.Message)}
    }
}
