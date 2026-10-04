<#
.SYNOPSIS
Реальный bounded producer independent two-client receipts, не retrospective helper PASS.
.DESCRIPTION
Только определения. MAIN после terminal root/native run импортирует этот файл через AST,
привязывая genuine PSScriptRoot variables к ScriptsRoot, как frozen bridge importer.
Import-NativeTwoClientCollectorDependencies загружает определения frozen helpers, не runner body.
Invoke-NativeTwoClientCollectedScenario: девять Invoke-NativeCell аргументов + AcceptancePins,
EvidenceKind=UNVERIFIED. Pins: baseManifest/baseSha256/targetManifest/targetSha256/helperSha256,
sourcePins (map шести source filenames -> SHA256, включая acceptor). Ни pins, ни образы не генерируются.
Перед запуском создаются локальные AST-копии concurrent scenario/barrier с exact anchors.
Callback подключён до launcher и ДО обоих существующих wait windows; original guards/250 ms sleep/
3000 ms window не удаляются. Дополнительный hash/UI/CIM cost может сорвать временной gate:
это NOTPROVEN, не право расширить gate или подделать timestamps/paint/UI.
PrimaryBefore и PeerBefore снимаются до первого/второго launcher соответственно. Ready,
allAlive/peerAlive samples, completion до finishedAt, cleanup после штатного finally реально читаются.
Свежий Get-ColdUiReceipt связывается с original retained uiProcess и fresh CIM birth/command.
Original helper работает с частной копией Row: caller Row не меняется, raw helper cell/receipts
возвращаются отдельно. Full matrix/release не принимаются. UNIT_MOCK/UNVERIFIED не native PASS.
Недоступный UI/identity/time gate => observationStatus=NOTPROVEN, decision PENDING.
Частичные observations сохраняются отдельно, не заменяются шаблоном helper PASS.
MAIN callsite: ready-independent bridge не используется; concurrent route 'two-clients' вызывает
этот adapter вместо plain Invoke-NativeConcurrentScenario. Возврат Evidence содержит прежние семь
concurrent fields + IndependentSha256 для Test-NativeTwoClientAcceptance/bridge Chandrasekhar.
Bridge owner отдельно обновляет frozen imports/pins и literal route. Здесь shared файлы не изменены.
Наблюдения bounded, не непрерывная/kernel/foreground гарантия; observer ничего не kill/delete.
#>

# AST-only импорт существующих definitions; реальные native функции не выполняются при импорте.
function Import-NativeTwoClientCollectorDependencies([string]$ScriptsRoot=$PSScriptRoot) {
    foreach ($file in 'Test-NativeUpdateLifecycle.ps1','NativeUpdateConcurrentScenarios.ps1','NativeUpdateTwoClientAcceptance.ps1') {
        $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot $file),[ref]$tokens,[ref]$errors)
        if ($errors.Count) {throw 'TWO_COLLECT_IMPORT_PARSE'}
        foreach ($def in $ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]}) {
            $text=$def.Extent.Text
            foreach ($v in @($def.FindAll({param($n) $n -is [Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -ceq 'PSScriptRoot'},$true) | Sort-Object {$_.Extent.StartOffset} -Descending)) {
                if ($v.Parent -is [Management.Automation.Language.ExpandableStringExpressionAst]) {throw 'TWO_COLLECT_ROOT_INTERPOLATION'}
                $offset=$v.Extent.StartOffset-$def.Extent.StartOffset
                $text=$text.Remove($offset,$v.Extent.Text.Length).Insert($offset,("'"+$ScriptsRoot.Replace("'","''")+"'"))
            }
            . ([scriptblock]::Create(($text -replace ('^function '+[regex]::Escape($def.Name)),('function global:'+$def.Name))))
        }
    }
    Import-NativeDependencies $ScriptsRoot
    Import-NativeTwoClientAcceptanceGuards $ScriptsRoot
}

# Frozen source pins проверяются до импорта/launch; links/изменённые bytes не допускаются.
function Assert-NativeTwoClientCollectorSources([string]$ScriptsRoot,$Pins) {
    foreach ($file in 'Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1','Test-Portable.ps1',
        'NativeUpdateConcurrentScenarios.ps1','NativeUpdateConcurrentAcceptance.ps1','NativeUpdateTwoClientAcceptance.ps1') {
        $pin=$null
        if ($Pins -is [Collections.IDictionary]) {$pin=$Pins[$file]}
        elseif ($null -ne $Pins -and $null -ne $Pins.PSObject.Properties[$file]) {$pin=$Pins.$file}
        if ($pin -isnot [string] -or $pin -cnotmatch '^[0-9a-f]{64}$') {throw ('TWO_COLLECT_SOURCE_PIN:'+ $file)}
        $item=Get-Item -LiteralPath (Join-Path $ScriptsRoot $file) -Force
        if ($item.PSIsContainer) {throw 'TWO_COLLECT_SOURCE_KIND'}
        for ($node=$item;$null -ne $node;$node=if ($node -is [IO.FileInfo]) {$node.Directory} else {$node.Parent}) {
            if ($node.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'TWO_COLLECT_SOURCE_LINK'}
        }
        if ((Get-FileHash -LiteralPath $item.FullName).Hash.ToLowerInvariant() -cne $pin) {throw ('TWO_COLLECT_SOURCE_CHANGED:'+ $file)}
    }
}

# Только локальные definitions: exact anchors повторяют уже существующий frozen AST-hook подход.
function Get-NativeTwoClientCollectedDefinitions([string]$Path,[string]$ExpectedSha256) {
    if ($ExpectedSha256 -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant() -cne $ExpectedSha256) {throw 'TWO_COLLECT_CONCURRENT_PIN'}
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'TWO_COLLECT_AST'}
    $texts=@{}
    foreach ($name in 'Invoke-NativeConcurrentScenario','Observe-NativeConcurrentBarrier') {
        $defs=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq $name})
        if ($defs.Count -ne 1) {throw 'TWO_COLLECT_AST_EXPORT'};$texts[$name]=$defs[0].Extent.Text
    }
    $scenario=$texts['Invoke-NativeConcurrentScenario'] -replace '^function Invoke-NativeConcurrentScenario','function Invoke-NativeTwoClientObservedScenario'
    $common=' -State $twoCollectorState -Data ([pscustomobject]@{root=$root;targetRoot=$script:nativeTarget;evidence=$cellEvidence;entries=@($entries.ToArray());errors=@($cleanup.ToArray())})'
    $hooks=[ordered]@{
        '$before=@(Assert-ColdTree $root $Base);$userBefore=Get-NativeUserObject $root'=('Invoke-NativeTwoClientCollectorObservation -Stage PrimaryBefore'+$common)
        '$client=$clientNames[$i];$node='=('if ($i -eq 1) {Invoke-NativeTwoClientCollectorObservation -Stage PeerBefore'+$common+'}')
        '$proofs=[Collections.Generic.List[string]]::new()'=('Invoke-NativeTwoClientCollectorObservation -Stage Ready'+$common)
        '$http=Stop-NativeFixture $server $cellEvidence ''server-concurrent'''=('Invoke-NativeTwoClientCollectorObservation -Stage Completion'+$common)
        '$Row.status=if ($failure -or $cleanup.Count)'=('Invoke-NativeTwoClientCollectorObservation -Stage Cleanup'+$common)
    }
    foreach ($anchor in $hooks.Keys) {
        if ([regex]::Matches($scenario,[regex]::Escape($anchor)).Count -ne 1) {throw 'TWO_COLLECT_HOOK_ANCHOR'}
        $scenario=$scenario.Replace($anchor,($hooks[$anchor]+"`n        "+$anchor))
    }
    $scenario=$scenario.Replace('Observe-NativeConcurrentBarrier','Observe-NativeTwoClientObservedBarrier')
    $barrier=$texts['Observe-NativeConcurrentBarrier'] -replace '^function Observe-NativeConcurrentBarrier','function Observe-NativeTwoClientObservedBarrier'
    $data=' -State $twoCollectorState -Data ([pscustomobject]@{root=$Root;entries=@($Survivors);path=$Path})'
    $waitHooks=[ordered]@{
        '$alive=@(foreach ($entry in @($Survivors)) {Assert-NativeConcurrentAlive $entry})'=('Invoke-NativeTwoClientCollectorObservation -Stage BeginWait'+$data)
        '# Повтор после hash связывает неизменное дерево с всё ещё живыми клиентами.'=('Invoke-NativeTwoClientCollectorObservation -Stage TreeWait'+$data)
        '$samples.Add([pscustomobject]@{elapsedMillis=$clock.ElapsedMilliseconds;'=('Invoke-NativeTwoClientCollectorObservation -Stage EndWait'+$data)
    }
    foreach ($anchor in $waitHooks.Keys) {
        if ([regex]::Matches($barrier,[regex]::Escape($anchor)).Count -ne 1) {throw 'TWO_COLLECT_WAIT_ANCHOR'}
        $barrier=$barrier.Replace($anchor,($waitHooks[$anchor]+"`n            "+$anchor))
    }
    $text=$barrier+"`n"+$scenario
    $tokens=$null;$errors=$null;[void][Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'TWO_COLLECT_GENERATED_PARSE'}
    return $text
}

# Свежий UI, удержанный первоначальный дескриптор и два CIM census связывают birth/argv/root.
function Read-NativeTwoClientFreshAlive($Entry,[long]$After) {
    $root=$Entry.root;$retained=$Entry.native.uiProcess;$original=$Entry.native.ui
    if ($retained.HasExited) {throw 'TWO_COLLECT_UI_EXITED'}
    $cim=@(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq $original.pid})
    if ($cim.Count -ne 1) {throw 'TWO_COLLECT_CIM_NOT_OBSERVED'}
    [void](Get-ColdCurrentProcess $cim[0] $retained $original.executablePath)
    $ui=Get-ColdUiReceipt $root $Entry.client ([datetime]::new($original.startedAtTicks,[DateTimeKind]::Utc))
    if ($null -eq $ui) {throw 'TWO_COLLECT_UI_NOT_OBSERVED'}
    $identity=Get-ColdProcessReceipt $retained $root
    $leasePath=Join-Path $root ('CashMemory/Updates/processes/'+$ui.lease.leaseId+'.json')
    $lease=Read-NativeAcceptanceJson $leasePath
    $alive=[pscustomobject]@{ui=$ui;identity=$identity;lease=$lease;leasePath=$leasePath;observedUtc=[datetime]::UtcNow.ToString('o')}
    Assert-NativeTwoClientFreshAlive $alive ([pscustomobject]@{ui=$original}) $root $After (Get-ColdUtcTicks $alive.observedUtc)
    if ($retained.HasExited) {throw 'TWO_COLLECT_UI_EXITED'}
    [void](Get-ColdCurrentProcess $cim[0] $retained $original.executablePath)
    return $alive
}

# Artifact writer только CreateNew в собственной cellEvidence, не перезапись baseline/config.
function Write-NativeTwoClientCollectorArtifact([string]$Directory,[string]$Name,$Value,[switch]$Bytes) {
    if ($Name -cnotin @('two-client-independent.json','two-client-observer.json','production-apply-update.ps1')) {throw 'TWO_COLLECT_ARTIFACT_NAME'}
    $directory=Resolve-PortableSafetyPath $Directory
    $file=Resolve-PortableSafetyPath (Join-Path $directory $Name)
    if (-not (Test-PortablePathContains $directory $file)) {throw 'TWO_COLLECT_ARTIFACT_SCOPE'}
    if ($Bytes) {
        if ($Value -isnot [byte[]]) {throw 'TWO_COLLECT_ARTIFACT_SIZE'}
        [byte[]]$data=$Value
    } else {[byte[]]$data=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -Depth 64 -InputObject $Value))}
    if ($data.Length -gt 33554432) {throw 'TWO_COLLECT_ARTIFACT_SIZE'}
    $stream=[IO.File]::Open($file,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($data,0,$data.Length);$stream.Flush($true)} finally {$stream.Dispose()}
    return [pscustomobject]@{path=$file;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($data)).ToLowerInvariant()}
}

# Читает реальный состав каталога или наблюдаемое отсутствие, без требования создать пустой requests.
function Read-NativeTwoClientDirectoryEntries([string]$Path) {
    $safe=Resolve-PortableSafetyPath $Path
    # Ordinary no-restart может вовсе не создавать requests; отсутствие наблюдается с диска.
    if (-not (Test-Path -LiteralPath $safe)) {return @()}
    if (-not (Test-Path -LiteralPath $safe -PathType Container)) {throw 'TWO_COLLECT_EXPECTED_DIRECTORY'}
    return @(Get-ChildItem -LiteralPath $safe -Force -ErrorAction Stop | ForEach-Object {$_.Name})
}

# Callback отказ сохраняет NOTPROVEN; никогда не подставляет отсутствующие observations из helper.
function Invoke-NativeTwoClientCollectorObservation($State,[string]$Stage,$Data) {
    try {
        if ($Stage -cnotin @('PrimaryBefore','PeerBefore','Ready','BeginWait','TreeWait','EndWait','Completion','Cleanup')) {throw 'TWO_COLLECT_STAGE'}
        $p=$State.proof
        if ($Stage -cne 'PrimaryBefore' -and -not $State.stages.Contains('PrimaryBefore')) {throw 'TWO_COLLECT_ORDER'}
        if ($Stage -cnotin @('BeginWait','TreeWait','EndWait') -and $State.stages.Contains($Stage)) {throw 'TWO_COLLECT_DUPLICATE'}
        if ($Stage -ceq 'PrimaryBefore') {
            [void](Assert-ColdOwnedRun ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($Data.root))) ([IO.Path]::GetTempPath()))
            if (@(Get-CopyProcesses $Data.root).Count) {throw 'TWO_COLLECT_BASELINE_TOO_LATE'}
            $p.root=$Data.root;$State.evidence=$Data.evidence
            $p.currentBefore=@(Assert-ColdTree $Data.root $State.base)
            $p.targetBefore=@(Assert-ColdTree $Data.targetRoot $State.target)
            $p.userBefore=Get-NativeUserObject $Data.root
            $p.baselineObservedAt=[datetime]::UtcNow.ToString('o')
        } else {
            if ($Data.root -cne $p.root) {throw 'TWO_COLLECT_ROOT'}
            switch -CaseSensitive ($Stage) {
                'PeerBefore' {
                    if (@($Data.entries).Count -ne 1) {throw 'TWO_COLLECT_PEER_ORDER'}
                    $begin=[datetime]::UtcNow.Ticks
                    $State.stages.PeerBefore=[pscustomobject]@{initialAlive=(Read-NativeTwoClientFreshAlive $Data.entries[0] $begin);
                        treeFiles=@(Assert-ColdTree $Data.root $State.base);observedAt=[datetime]::UtcNow.ToString('o')}
                }
                'Ready' {
                    if (-not $State.stages.Contains('PeerBefore') -or @($Data.entries).Count -ne 2) {throw 'TWO_COLLECT_READY_ORDER'}
                    $p.readyManifest=Read-NativeAcceptanceJson (Join-Path $p.root 'CashMemory/Updates/Ready/update.json')
                    Assert-NativeAcceptanceSame $State.target $p.readyManifest 'TWO_COLLECT_READY_PIN'
                    $p.readyTree=@(Assert-ColdTree (Join-Path $p.root 'CashMemory/Updates/Ready/tree') $State.target)
                    $p.readyObservedAt=[datetime]::UtcNow.ToString('o')
                }
                {$_ -cin @('BeginWait','TreeWait','EndWait')} {
                    if (-not $State.stages.Contains('Ready')) {throw 'TWO_COLLECT_WAIT_BEFORE_READY'}
                    $name=switch ([IO.Path]::GetFileName($Data.path)) {'barrier-all.json' {'allAlive'} 'barrier-after-exit-0.json' {'peerAlive'} default {throw 'TWO_COLLECT_WAIT_PATH'}}
                    if (@($Data.entries).Count -ne $(if ($name -ceq 'allAlive') {2} else {1})) {throw 'TWO_COLLECT_WAIT_CLIENTS'}
                    if ($Stage -ceq 'BeginWait') {
                        if ($null -ne $State.pending) {throw 'TWO_COLLECT_WAIT_NESTED'}
                        $begin=[datetime]::UtcNow
                        $State.pending=[ordered]@{startedAt=$begin.ToString('o');aliveBefore=@(foreach ($entry in $Data.entries) {Read-NativeTwoClientFreshAlive $entry $begin.Ticks})}
                        $State.window=$name
                    } else {
                        if ($null -eq $State.pending -or $State.window -cne $name) {throw 'TWO_COLLECT_WAIT_ORDER'}
                        if ($Stage -ceq 'TreeWait') {
                            $State.pending.treeFiles=@(Assert-ColdTree $p.root $State.base)
                            $log=Join-Path $p.root 'CashMemory/Updates/update-log.md'
                            $State.pending.replacementPhases=@(if (Test-Path -LiteralPath $log -PathType Leaf) {
                                foreach ($line in Get-Content -LiteralPath $log -Encoding utf8) {if ($line -cmatch ' PHASE_(BOOTSTRAPPING|BACKING_UP|INSTALLING|VERIFYING|COMMITTED|ROLLING_BACK)$') {$Matches[1]}}
                            })
                            $State.pending.lastInstallPresent=[bool](Test-Path -LiteralPath (Join-Path $p.root 'CashMemory/Updates/last-install.json'))
                        } else {
                            if (-not $State.pending.Contains('treeFiles')) {throw 'TWO_COLLECT_WAIT_NO_TREE'}
                            $after=[datetime]::UtcNow.Ticks
                            $State.pending.aliveAfter=@(foreach ($entry in $Data.entries) {Read-NativeTwoClientFreshAlive $entry $after})
                            $State.pending.finishedAt=[datetime]::UtcNow.ToString('o')
                            $State.samples[$name].Add([pscustomobject]$State.pending);$State.pending=$null
                        }
                    }
                }
                'Completion' {
                    # Production publishes helper при exit/install, не при публикации Ready.
                    $helper=Resolve-PortableSafetyPath (Join-Path $p.root 'CashMemory/Updates/apply-update.ps1')
                    $bytes=[IO.File]::ReadAllBytes($helper)
                    if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $State.helperPin) {throw 'TWO_COLLECT_HELPER_PIN'}
                    [void](Write-NativeTwoClientCollectorArtifact $Data.evidence 'production-apply-update.ps1' $bytes -Bytes)
                    $p.targetAfter=@(Assert-ColdTree $Data.targetRoot $State.target);$p.userAfter=Get-NativeUserObject $p.root
                    $p.completion=[pscustomobject]@{lastInstall=(Read-NativeAcceptanceJson (Join-Path $p.root 'CashMemory/Updates/last-install.json'));
                        currentAfter=@(Assert-ColdTree $p.root $State.target);processes=@(Get-CopyProcesses $p.root);
                        leases=@(Read-NativeTwoClientDirectoryEntries (Join-Path $p.root 'CashMemory/Updates/processes'));
                        requests=@(Read-NativeTwoClientDirectoryEntries (Join-Path $p.root 'CashMemory/Updates/requests'));
                        journalPresent=[bool](Test-Path -LiteralPath (Join-Path $p.root 'CashMemory/Updates/install-journal.json'));
                        completedJournalPresent=[bool](Test-Path -LiteralPath (Join-Path $p.root 'CashMemory/Updates/completed-journal.json'));observedAt=[datetime]::UtcNow.ToString('o')}
                }
                'Cleanup' {
                    $helper=Join-Path $p.root 'CashMemory/Updates/apply-update.ps1'
                    $command="& '"+$helper.Replace("'","''")+"' -InstallationRoot '"+$p.root.Replace("'","''")+"'"
                    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
                    $exe=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
                    $helpers=@(Get-CimInstance Win32_Process | Where-Object {
                        if ($_.ExecutablePath -cne $exe) {return $false}
                        try {Assert-ColdHelperCommand $_.CommandLine $exe $encoded;return $true} catch {return $false}
                    } | ForEach-Object {$_.ProcessId})
                    $p.cleanup=[pscustomobject]@{errors=@($Data.errors);processes=@(Get-CopyProcesses $p.root);helpers=$helpers;
                        registryUnchanged=((Get-PortableRealRegistrySnapshot) -ceq $State.registryBefore);
                        forcedCleanup=(@($Data.errors | Where-Object {$_ -ceq 'CONCURRENT_FORCED_CLEANUP_REQUIRED'}).Count -gt 0);observedAt=[datetime]::UtcNow.ToString('o')}
                }
            }
        }
        if (-not $State.stages.Contains($Stage)) {$State.stages[$Stage]=[pscustomobject]@{observedAt=[datetime]::UtcNow.ToString('o')}}
    } catch {
        $State.gaps.Add($Stage+':'+$_.Exception.Message)
        if ($Stage -cin @('BeginWait','TreeWait','EndWait')) {$State.pending=$null}
    }
}

# Первый набор наблюдений не восстанавливается из завершённого helper; raw receipts читаются как bytes.
function Read-NativeTwoClientCollectorRaw([string]$Directory) {
    $raw=[ordered]@{}
    if (-not $Directory) {return $raw}
    foreach ($file in 'cell.json','launch-0.json','launch-1.json','barrier-all.json','barrier-after-exit-0.json','exit-final.json') {
        $raw[$file]=Read-NativeAcceptanceJson (Join-Path $Directory $file)
    }
    $launch=$raw['launch-0.json']
    if ($null -ne $launch) {$raw['exit-first']=Read-NativeAcceptanceJson (Join-Path $Directory ('exit-'+$launch.ui.pid+'.json'))}
    return $raw
}

# Реальный adapter; caller Row неизменён, schema/pins и fresh observations доступны bridge напрямую.
function Invoke-NativeTwoClientCollectedScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout,
    $AcceptancePins,[ValidateSet('UNVERIFIED','NATIVE','UNIT_MOCK')][string]$EvidenceKind='UNVERIFIED') {
    if ($Row.scenario -cne 'two-clients' -or $Row.phase -cne 'SESSION' -or $Row.status -cne 'PENDING' -or $Timeout -lt 30 -or $Timeout -gt 300) {throw 'TWO_COLLECT_ROW'}
    foreach ($key in 'baseManifest','baseSha256','targetManifest','targetSha256','helperSha256','sourcePins') {
        if ($null -eq $AcceptancePins -or $null -eq $AcceptancePins.PSObject.Properties[$key]) {throw 'TWO_COLLECT_PINS'}
    }
    $scriptsRoot=$PSScriptRoot
    Assert-NativeTwoClientCollectorSources $scriptsRoot $AcceptancePins.sourcePins
    Import-NativeTwoClientCollectorDependencies $scriptsRoot
    foreach ($pair in @(@($Base,$AcceptancePins.baseManifest,$AcceptancePins.baseSha256),@($Target,$AcceptancePins.targetManifest,$AcceptancePins.targetSha256))) {
        $pinned=Read-NativeAcceptanceJson $pair[1] $pair[2]
        if ($null -eq $pinned) {throw 'TWO_COLLECT_MANIFEST_MISSING'}
        Assert-NativeAcceptanceSame $pair[0] $pinned 'TWO_COLLECT_MANIFEST_PIN'
    }
    if ($Cold.helperSha256 -cne $AcceptancePins.helperSha256) {throw 'TWO_COLLECT_HELPER_CONTEXT'}
    $definition=Get-NativeTwoClientCollectedDefinitions (Join-Path $scriptsRoot 'NativeUpdateConcurrentScenarios.ps1') $AcceptancePins.sourcePins.'NativeUpdateConcurrentScenarios.ps1'
    $copy=ConvertFrom-ColdReceiptJson (ConvertTo-Json -Depth 64 -InputObject $Row)
    $twoCollectorState=[pscustomobject]@{proof=[ordered]@{schemaVersion=1;cellKey=('two-clients/'+$Row.base+'/'+$Row.client+'/'+$Row.path+'/SESSION')};
        stages=[ordered]@{};samples=@{allAlive=[Collections.Generic.List[object]]::new();peerAlive=[Collections.Generic.List[object]]::new()};
        pending=$null;window=$null;base=$Base;target=$Target;helperPin=$AcceptancePins.helperSha256;evidence=$null;
        registryBefore=(Get-PortableRealRegistrySnapshot);gaps=[Collections.Generic.List[string]]::new()}
    # Обе definitions установлены до самого первого запуска helper; globals не переопределяются.
    . ([scriptblock]::Create($definition))
    Assert-NativeTwoClientCollectorSources $scriptsRoot $AcceptancePins.sourcePins
    $helperError=$null
    try {$helperOutputs=@(Invoke-NativeTwoClientObservedScenario $copy $Source $Base $Target $Life $Cold $Java $Evidence $Timeout)}
    catch {$helperError=$_.Exception.Message;$helperOutputs=@()}
    # Путь raw artifacts можно взять из actual helper metadata, но baseline/window facts - нельзя.
    if (-not $twoCollectorState.evidence -and $null -ne $copy.PSObject.Properties['concurrentCellEvidence']) {
        $twoCollectorState.evidence=$copy.concurrentCellEvidence
    }
    try {$raw=Read-NativeTwoClientCollectorRaw $twoCollectorState.evidence}
    catch {$raw=[ordered]@{};$twoCollectorState.gaps.Add('RAW:'+ $_.Exception.Message)}
    foreach ($stage in 'PrimaryBefore','PeerBefore','Ready','Completion','Cleanup') {
        if (-not $twoCollectorState.stages.Contains($stage)) {$twoCollectorState.gaps.Add('MISSING:'+ $stage)}
    }
    foreach ($name in 'allAlive','peerAlive') {
        $samples=@($twoCollectorState.samples[$name].ToArray());$twoCollectorState.proof[$name]=$samples
        if ($samples.Count -lt 2 -or (Get-ColdUtcTicks $samples[-1].finishedAt)-(Get-ColdUtcTicks $samples[0].startedAt) -lt 30000000L -or
            (Get-ColdUtcTicks $samples[-1].finishedAt)-(Get-ColdUtcTicks $samples[0].startedAt) -gt 600000000L) {$twoCollectorState.gaps.Add('NOTPROVEN_WINDOW:'+ $name)}
    }
    if ($helperError) {$twoCollectorState.gaps.Add('HELPER:'+ $helperError)}
    $file=$null;$pin=$null;$decision=[pscustomobject]@{status='PENDING';scope='NATIVE_TWO_CLIENT_WAIT_BARRIER';proofComplete=$false;
        missing=$twoCollectorState.gaps.ToArray();contradictions=@()}
    if ($twoCollectorState.evidence) {
        $artifact=Write-NativeTwoClientCollectorArtifact $twoCollectorState.evidence 'two-client-independent.json' ([pscustomobject]$twoCollectorState.proof)
        $file=$artifact.path;$pin=$artifact.sha256
        [void](Write-NativeTwoClientCollectorArtifact $twoCollectorState.evidence 'two-client-observer.json' ([pscustomobject]@{
            stages=$twoCollectorState.stages;gaps=$twoCollectorState.gaps.ToArray();helperError=$helperError}))
        if (-not $twoCollectorState.gaps.Count) {
            $decision=Test-NativeTwoClientAcceptance -CellEvidence $twoCollectorState.evidence -BaseManifest $AcceptancePins.baseManifest -BaseSha256 $AcceptancePins.baseSha256 `
                -TargetManifest $AcceptancePins.targetManifest -TargetSha256 $AcceptancePins.targetSha256 -ExpectedHelperSha256 $AcceptancePins.helperSha256 `
                -SupplementalDirectory $twoCollectorState.evidence -IndependentSha256 $pin -EvidenceKind $EvidenceKind
        }
    }
    return [pscustomobject]@{status=$decision.status;scope='TWO_CLIENT_COLLECTOR_ONLY';observationStatus=$(if ($twoCollectorState.gaps.Count) {'NOTPROVEN'} else {'OBSERVED'});
        canonicalRowUnchanged=$true;helperRow=$copy;helperOutputs=$helperOutputs;rawReceipts=$raw;decision=$decision;helperError=$helperError;
        independentFile=$file;independentSha256=$pin;evidenceKind=$EvidenceKind;
        Evidence=[pscustomobject]@{CellEvidence=$twoCollectorState.evidence;BaseManifest=$AcceptancePins.baseManifest;BaseSha256=$AcceptancePins.baseSha256;
            TargetManifest=$AcceptancePins.targetManifest;TargetSha256=$AcceptancePins.targetSha256;ExpectedHelperSha256=$AcceptancePins.helperSha256;
            SupplementalDirectory=$twoCollectorState.evidence;IndependentSha256=$pin}}
}
