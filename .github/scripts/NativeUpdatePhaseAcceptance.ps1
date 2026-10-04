<#
.SYNOPSIS
Независимая read-only приёмка сохранённых receipts frozen Phase helper.
.DESCRIPTION
MAIN импортирует чистые cold/phase/lifecycle guards через AST, затем dot-source этот
файл и вызывает Assert-NativePhaseAcceptance $Row $Base $Target $Cold $Java.
Base/Target должны совпадать с закреплёнными manifest bytes Cold. Row - phase-result
с полями UpdateEvidence. Все artifacts читаются только внутри Row.evidence, без links.
Не запускает helper/exe/java, не делает Kill, не записывает и не меняет Row.status.
Результат RECEIPTS_VALIDATED не заменяет whole-matrix/native signoff.
Assert-NativePhaseReceiptSet - чистый контракт для scoped mock fixtures; не native PASS.
Производитель NativeUpdatePhaseScenarios.ps1 (frozen 5648826E...853BAA2):
293-299 checkpoint/journal/fault до Kill; 302-320 identity Kill/death/diagnostics;
306-333 WAITING setup removal и fresh epoch; 355-361 recovery/native launch;
362-395 full tree, verify перед cleanup, native exit, final tree/verify/user objects;
398 finishedAt ДО finally 402-424; 429 phase-result после cleanup. Поэтому finishedAt
не является cleanup timestamp. Два tool receipts не содержат собственных timestamps:
их порядок доказывается frozen producer flow, не независимым clock witness.
User objects содержат immutable CashMemory payload. Controlled session/update files
исключены: Assert-ColdControlledChanges вызывается producer в 384, но отдельные
baseline/final receipts не сохранены. Эта функция не заявляет независимую приёмку
всего CashMemory или ordinary UI exit; cleanup UI намеренно forced и так маркирован.
#>

# Неизвестные, недостающие и противоречивые поля не становятся успехом по truthiness.
function Assert-PhaseAcceptanceFields($Value,[string[]]$Names) {
    if ($Value -isnot [pscustomobject]) {throw 'PHASE_ACCEPT_OBJECT'}
    foreach ($name in $Names) {if ($Value.PSObject.Properties.Name -cnotcontains $name) {throw 'PHASE_ACCEPT_MISSING'}}
}

# Полный путь проверяется вместе со всеми предками; любые reparse points запрещены.
function Resolve-PhaseAcceptancePath([string]$Path) {
    if (-not $Path -or -not [IO.Path]::IsPathFullyQualified($Path)) {throw 'PHASE_ACCEPT_PATH'}
    $full=[IO.Path]::GetFullPath($Path)
    if ($Path -cne $full) {throw 'PHASE_ACCEPT_PATH'}
    $cursor=$full
    while ($cursor) {
        $item=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {throw 'PHASE_ACCEPT_LINK'}
        $parent=[IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) {break};$cursor=$parent
    }
    return $full
}

# Ограниченный reader сохраняет JSON arrays и UTC strings без locale преобразования.
function Read-PhaseAcceptanceArtifact([string]$Evidence,[string]$Path) {
    $directory=Resolve-PhaseAcceptancePath $Evidence
    $file=Resolve-PhaseAcceptancePath $Path
    if (-not $file.StartsWith($directory+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)) {throw 'PHASE_ACCEPT_ARTIFACT_SCOPE'}
    $item=Get-Item -LiteralPath $file -Force
    if ($item.PSIsContainer -or $item.Length -gt 8388608) {throw 'PHASE_ACCEPT_ARTIFACT_SIZE'}
    return ,(ConvertFrom-ColdReceiptJson ([IO.File]::ReadAllText($file,[Text.UTF8Encoding]::new($false,$true))))
}

# PID/birth/path/root binding проверяется по wire integers, а не GetProcessById после cleanup.
function Assert-PhaseAcceptanceIdentity($Identity,[string]$Root,[string]$Exe) {
    Assert-PhaseAcceptanceFields $Identity @('ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot')
    if (-not (Test-ColdInteger $Identity.ProcessId 1) -or $Identity.ProcessId -gt [int]::MaxValue -or
        -not (Test-ColdInteger $Identity.StartedAtTicks 1) -or $Identity.OwnedRoot -cne $Root -or
        $Identity.ExecutablePath -cne $Exe -or -not [IO.Path]::IsPathFullyQualified($Root) -or
        -not [IO.Path]::IsPathFullyQualified($Exe)) {throw 'PHASE_ACCEPT_PROCESS_IDENTITY'}
}

# Чистая приёмка связанного комплекта; mock-вызов проверяет только контракт, не native run.
function Assert-NativePhaseReceiptSet($Row,$Set,$Base,$Target,$Cold,[string]$Java) {
    Assert-PhaseAcceptanceFields $Row @('scenario','phase','base','client','path','status','executed','exitCode','failures','skipped',
        'workRoot','exe','baseCommit','baseRelease','targetCommit','targetRelease','args','startedAt','finishedAt','version')
    if ($Row.status -cne 'PASS' -or $Row.executed -isnot [bool] -or -not $Row.executed -or
        $Row.base -cnotin @('B1','B2') -or $Row.client -cnotin @('fx','swing','web') -or $Row.path -cnotin @('ascii','cyrillic','unicode') -or
        -not (Test-ColdInteger $Row.exitCode) -or $Row.exitCode -ne 0 -or
        -not (Test-ColdInteger $Row.failures) -or $Row.failures -ne 0 -or
        -not (Test-ColdInteger $Row.skipped) -or $Row.skipped -ne 0) {throw 'PHASE_ACCEPT_PENDING_OR_FAILED'}
    foreach ($name in 'reason','cleanupFailure') {
        if ($Row.PSObject.Properties.Name -ccontains $name -and $Row.$name -cne '') {throw 'PHASE_ACCEPT_CONTRADICTION'}
    }
    $mapping=Get-NativePhaseMapping $Row.scenario $Row.phase
    Assert-PhaseAcceptanceFields $Set @('checkpoint','journal','journalSha256','fault','death','epoch','launch','exit','recovery',
        'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','tools','phaseLog','httpTrace')
    $root=$Row.workRoot;$exe=Join-Path $root (Get-ColdLauncherName $Row.client)
    if ($Row.exe -cne $exe -or $Row.baseCommit -cne $Base.commitSha -or $Row.baseRelease -ne $Base.releaseNumber -or
        $Row.targetCommit -cne $Target.commitSha -or $Row.targetRelease -ne $Target.releaseNumber -or
        -not (Test-ColdInteger $Row.baseRelease 1) -or -not (Test-ColdInteger $Row.targetRelease 1) -or
        $Row.targetRelease -le $Row.baseRelease) {throw 'PHASE_ACCEPT_PINS'}
    $start=Get-ColdUtcTicks $Row.startedAt;$finish=Get-ColdUtcTicks $Row.finishedAt
    if ($finish -le $start) {throw 'PHASE_ACCEPT_TIME'}
    Assert-PhaseAcceptanceFields $Set.death @('identity','actualExit','killedAt','checkpoint','actualDiagnostics')
    $identity=$Set.death.identity
    $psExe=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    Assert-PhaseAcceptanceIdentity $identity $root $psExe
    if (($Set.death.actualExit -isnot [int] -and $Set.death.actualExit -isnot [long]) -or $Set.death.actualExit -eq 0) {throw 'PHASE_ACCEPT_INTERRUPTION'}
    $killed=Get-ColdUtcTicks $Set.death.killedAt
    if ($identity.StartedAtTicks -lt $start -or $identity.StartedAtTicks -ge $killed -or $killed -gt $finish) {throw 'PHASE_ACCEPT_TIME'}
    Assert-PhaseAcceptanceFields $Set.journal @('schemaVersion','installationRoot','transactionId','phase','oldFiles','oldTreeSha256','target','bootstrap','operations','outcome')
    if (-not (Test-ColdInteger $Set.journal.schemaVersion 1) -or $Set.journal.schemaVersion -ne 2 -or
        $Set.journal.installationRoot -cne $root -or $Set.journal.transactionId -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or
        $Set.journalSha256 -cnotmatch '^[0-9a-f]{64}$' -or $Set.checkpoint.journalSha256 -cne $Set.journalSha256 -or
        $Set.journal.oldTreeSha256 -cne $Base.treeSha256 -or -not (Test-ColdInventoryEqual $Set.journal.oldFiles $Base.files) -or
        -not (Test-ColdInventoryEqual $Set.journal.target $Target)) {throw 'PHASE_ACCEPT_JOURNAL'}
    if ($Set.journal.operations -isnot [array] -or @($Set.journal.operations).Count -gt 160000) {throw 'PHASE_ACCEPT_JOURNAL'}
    foreach ($operation in @($Set.journal.operations)) {
        Assert-ColdKeys $operation @('kind','path','state')
        if ($operation.kind -cnotmatch '^(BACKUP|INSTALL|UNINSTALL|RESTORE|BOOT_COPY|BACKUP_COPY|REDIRECT|REPLACE|RESTORE_REPLACE|CFG_SWITCH)$' -or
            $operation.state -cnotin @('BEFORE','AFTER')) {throw 'PHASE_ACCEPT_JOURNAL'}
        $entries=if ($operation.kind -cmatch '^(BACKUP|RESTORE|BOOT_COPY|BACKUP_COPY|REDIRECT|RESTORE_REPLACE)$') {$Base.files} else {$Target.files}
        if (@($entries | Where-Object path -CEQ $operation.path).Count -ne 1 -or
            ($operation.kind -ceq 'BOOT_COPY' -and -not (Test-ColdProtectedPayload $operation.path))) {throw 'PHASE_ACCEPT_JOURNAL'}
    }
    Assert-NativePhaseCheckpoint $mapping $Set.checkpoint $identity $root $Set.journal
    if ($Set.journal.bootstrap.state -cne $Set.checkpoint.bootstrapState -or
        $Set.journal.bootstrap.publishState -cne $Set.checkpoint.publishState) {throw 'PHASE_ACCEPT_JOURNAL'}
    if ($Row.scenario -ceq 'helper-runtime-death') {
        Assert-PhaseAcceptanceFields $Set.fault @('scenario','phase','error','checkpoint','identity','actualExit')
        if ($Set.fault.scenario -cne $Row.scenario -or $Set.fault.phase -cne 'SESSION' -or $Set.fault.error -cne $mapping.error -or
            $Set.fault.actualExit -ne $Set.death.actualExit -or -not (Test-ColdInventoryEqual $Set.fault.identity $identity) -or
            -not (Test-ColdInventoryEqual $Set.fault.checkpoint $Set.checkpoint) -or
            $Set.journal.phase -cne 'BOOTSTRAPPING' -or $Set.journal.bootstrap.state -cne 'ACTIVE') {throw 'PHASE_ACCEPT_INTERRUPTION'}
    } else {Assert-NativePhaseFault $Set.fault $mapping $Set.checkpoint $identity $Set.journal}
    Assert-PhaseAcceptanceFields $Set.epoch @('helperKilledAt','setupGoneAt','observationEpoch','freshLaunchAt')
    $epoch=Get-ColdUtcTicks $Set.epoch.observationEpoch;$launched=Get-ColdUtcTicks $Set.epoch.freshLaunchAt
    if ((Get-ColdUtcTicks $Set.epoch.helperKilledAt) -ne $killed -or $epoch -lt $killed -or $launched -lt $epoch -or $launched -gt $finish) {throw 'PHASE_ACCEPT_TIME'}
    if ($Row.phase -ceq 'WAITING') {
        Assert-PhaseAcceptanceFields $Set @('waitingLive','waitingAtCheckpoint','waitingRemoved')
        Assert-PhaseAcceptanceFields $Set.waitingLive @('scope','launcher','ui','args','startedAt')
        Assert-PhaseAcceptanceFields $Set.waitingRemoved @('scope','exit','helperIdentity','helperActualExit','remainingClients','goneAt')
        if ($Set.waitingLive.scope -cne 'ACTUAL_OWNED_CLIENT_LEASE' -or
            $Set.waitingRemoved.scope -cne 'FAULT_SETUP_REMOVAL_NOT_SIGNOFF' -or
            -not (Test-ColdInteger $Set.waitingRemoved.remainingClients) -or $Set.waitingRemoved.remainingClients -ne 0 -or
            $Set.waitingRemoved.helperActualExit -ne $Set.death.actualExit -or
            -not (Test-ColdInventoryEqual $Set.waitingRemoved.helperIdentity $identity)) {throw 'PHASE_ACCEPT_WAITING'}
        Assert-NativePhaseWaitingLease $Set.waitingLive.ui $Set.waitingAtCheckpoint $root $Row.client
        $gone=Get-ColdUtcTicks $Set.waitingRemoved.goneAt;$setupGone=Get-ColdUtcTicks $Set.epoch.setupGoneAt
        if ($gone -lt $killed -or $setupGone -lt $gone -or $epoch -ne $setupGone -or
            $Set.waitingAtCheckpoint.startedAtTicks -ge $killed -or
            (Get-ColdUtcTicks $Set.waitingAtCheckpoint.observedAt) -gt $killed) {throw 'PHASE_ACCEPT_WAITING'}
    } elseif ($null -ne $Set.epoch.setupGoneAt -or $epoch -ne $killed) {throw 'PHASE_ACCEPT_TIME'}
    Assert-PhaseAcceptanceFields $Set.launch @('launcher','ui','args','startedAt')
    Assert-PhaseAcceptanceFields $Set.exit @('launcher','actualExit','ui','uiCleanup')
    Assert-PhaseAcceptanceIdentity $Set.launch.launcher $root $exe
    if (-not (Test-ColdInteger $Set.exit.actualExit) -or $Set.exit.actualExit -ne 0 -or
        -not (Test-ColdInventoryEqual $Set.exit.launcher $Set.launch.launcher) -or
        -not (Test-ColdInventoryEqual $Set.exit.ui $Set.launch.ui) -or
        $Set.exit.uiCleanup -cne 'identity-bound copy cleanup, not ordinary UI exit' -or
        (Get-ColdUtcTicks $Set.launch.startedAt) -ne $launched -or $Set.launch.launcher.StartedAtTicks -lt $launched -or
        -not (Test-ColdInventoryEqual $Set.launch.args $Row.args)) {throw 'PHASE_ACCEPT_NATIVE_LAUNCH'}
    Assert-ColdUiReceipt $Set.launch.ui $root $Row.client
    Assert-ColdSafeArgs $Set.launch.args $Set.launch.ui.args $root $Row.client $Target.commitSha
    $observed=Get-ColdUtcTicks $Set.launch.ui.observedAt
    if ($Set.launch.ui.startedAtTicks -lt $launched -or $Set.launch.ui.startedAtTicks -lt $Set.launch.launcher.StartedAtTicks -or
        ($Set.launch.ui.pid -eq $identity.ProcessId -and $Set.launch.ui.startedAtTicks -eq $identity.StartedAtTicks) -or
        $observed -gt $finish) {throw 'PHASE_ACCEPT_NATIVE_LAUNCH'}
    if ($Row.phase -ceq 'WAITING' -and $Set.launch.ui.pid -eq $Set.waitingAtCheckpoint.pid -and
        $Set.launch.ui.startedAtTicks -eq $Set.waitingAtCheckpoint.startedAtTicks) {throw 'PHASE_ACCEPT_WAITING'}
    if ($Row.client -cne 'web') {Assert-PhaseAcceptanceFields $Set @('physicalWindow');Assert-NativeMainWindow $Set.physicalWindow $Set.launch.ui}
    Assert-ColdRecoveryEvidence $Set.recovery $root $Set.journal.transactionId $epoch $launched $observed
    foreach ($manifest in @($Base,$Target)) {Assert-ColdImageInventory @($manifest.files) $manifest.treeSha256}
    if (-not (Test-ColdInventoryEqual $Set.currentBefore $Base.files) -or
        -not (Test-ColdInventoryEqual $Set.targetBefore $Target.files) -or
        -not (Test-ColdInventoryEqual $Set.targetAfter $Target.files)) {throw 'PHASE_ACCEPT_TREE'}
    $chosen=if (Test-ColdInventoryEqual $Set.currentAfter $Base.files) {$Base}
        elseif (Test-ColdInventoryEqual $Set.currentAfter $Target.files) {$Target} else {throw 'PHASE_ACCEPT_PARTIAL_TREE'}
    Assert-ColdImageInventory @($Set.currentAfter) $chosen.treeSha256
    if ($Row.phase -ceq 'COMMITTED' -and $chosen.commitSha -cne $Target.commitSha) {throw 'PHASE_ACCEPT_TREE'}
    if ($Row.version.commitSha -cne $chosen.commitSha -or $Row.version.releaseNumber -ne $chosen.releaseNumber) {throw 'PHASE_ACCEPT_VERSION'}
    if ($Set.userBefore -isnot [pscustomobject] -or $Set.userAfter -isnot [pscustomobject] -or
        -not (Test-ColdInventoryEqual $Set.userBefore $Set.userAfter)) {throw 'PHASE_ACCEPT_USER_CHANGED'}
    foreach ($entry in $Set.userBefore.PSObject.Properties) {
        Assert-ColdKeys $entry.Value @('path','sizeBytes','sha256','readOnly')
        if ($entry.Name -cne $entry.Value.path -or $entry.Name -cnotlike 'CashMemory/*' -or
            $entry.Name -match '(^|/)\.\.?(/|$)|[\\:]' -or -not (Test-ColdInteger $entry.Value.sizeBytes) -or
            $entry.Value.sha256 -cnotmatch '^[0-9a-f]{64}$' -or $entry.Value.readOnly -isnot [bool]) {throw 'PHASE_ACCEPT_USER_CHANGED'}
    }
    if (@($Set.userBefore.PSObject.Properties).Count -eq 0) {throw 'PHASE_ACCEPT_USER_CHANGED'}
    # Требуются две настоящие verify-квитанции final root: до и после identity cleanup.
    $verified=0;$logs=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    if (@($Set.tools).Count -ne 2) {throw 'PHASE_ACCEPT_TOOL'}
    foreach ($tool in @($Set.tools)) {
        Assert-PhaseAcceptanceFields $tool @('executable','args','effectiveArguments','actualExit','stdout','stderr')
        if ($tool.executable -cne $Java -or -not (Test-ColdInteger $tool.actualExit) -or $tool.actualExit -ne 0 -or
            -not $logs.Add($tool.stdout) -or -not $logs.Add($tool.stderr)) {throw 'PHASE_ACCEPT_TOOL'}
        $args=@($tool.args);$prefix=@($Cold.toolArguments)
        if ($args.Count -ne $prefix.Count+5 -or
            -not (Test-ColdInventoryEqual @($args | Select-Object -First $prefix.Count) $prefix) -or
            $args[$prefix.Count] -cne 'verify' -or $args[$prefix.Count+1] -cne '--root' -or $args[$prefix.Count+3] -cne '--manifest') {throw 'PHASE_ACCEPT_TOOL'}
        $decoded=[Text.UTF8Encoding]::new($false,$true).GetString([Convert]::FromBase64String(@($tool.effectiveArguments)[-1]))
        $payload=ConvertFrom-ColdReceiptJson $decoded
        $effective=@('-XX:-UsePerfData')+$prefix+@('--arguments-base64',@($tool.effectiveArguments)[-1])
        if (-not (Test-ColdInventoryEqual $tool.effectiveArguments $effective) -or
            -not (Test-ColdInventoryEqual $payload @($args | Select-Object -Skip $prefix.Count))) {throw 'PHASE_ACCEPT_TOOL'}
        if ($args[$prefix.Count+2] -ceq $root) {
            $pin=if ($args[$prefix.Count+4] -ceq $Cold.targetManifest) {$Target} else {
                $matches=@($Cold.baseManifests | Where-Object manifest -CEQ $args[$prefix.Count+4])
                if ($matches.Count -ne 1) {throw 'PHASE_ACCEPT_TOOL'};$Base
            }
            if ($pin.commitSha -cne $chosen.commitSha) {throw 'PHASE_ACCEPT_TOOL'};$verified++
        }
    }
    if ($verified -lt 2) {throw 'PHASE_ACCEPT_TOOL'}
    if ($Set.phaseLog -isnot [array] -or $Set.phaseLog -cnotcontains $Row.phase -or $Set.httpTrace -isnot [array]) {throw 'PHASE_ACCEPT_EVIDENCE_ARRAY'}
    return [pscustomobject]@{status='RECEIPT_CONTRACT_VALIDATED';scenario=$Row.scenario;phase=$Row.phase;
        treeOutcome=$(if ($chosen.commitSha -ceq $Base.commitSha) {'ORIGINAL'} else {'NEW'});
        cashMemoryScope='IMMUTABLE_USER_PAYLOAD';controlledChanges='PRODUCER_ASSERTION_NOT_INDEPENDENT_RECEIPTS';
        nativeExecutionPerformed=$false;wholeMatrixSignoff='NOT_ASSESSED'}
}

# Единственный disk adapter: связывает все filenames, pins, durable bytes и raw logs.
function Assert-NativePhaseAcceptance($Row,$Base,$Target,$Cold,[string]$Java) {
    Assert-PhaseAcceptanceFields $Row @('evidence','command','faultReceipt','currentBefore','currentAfter','targetBefore','targetAfter',
        'userBefore','userAfter','phaseLog','httpTrace')
    foreach ($name in 'ConvertFrom-ColdReceiptJson','Test-ColdInteger','Get-ColdUtcTicks','Get-ColdLauncherName',
        'Assert-ColdKeys','Test-ColdInventoryEqual','Assert-ColdImageInventory','Assert-ColdUiReceipt','Assert-ColdSafeArgs',
        'Assert-ColdRecoveryEvidence','Get-NativePhaseMapping','Assert-NativePhaseCheckpoint','Assert-NativePhaseFault',
        'Assert-NativePhaseWaitingLease','Assert-NativeMainWindow') {
        if (-not (Get-Command $name -CommandType Function -ErrorAction SilentlyContinue)) {throw 'PHASE_ACCEPT_DEPENDENCY'}
    }
    $evidence=Resolve-PhaseAcceptancePath $Row.evidence
    $saved=Read-PhaseAcceptanceArtifact $evidence (Join-Path $evidence 'phase-result.json')
    foreach ($field in 'scenario','phase','base','client','path','status','executed','workRoot','evidence','exe','args',
        'baseCommit','baseRelease','targetCommit','targetRelease','currentBefore','currentAfter','targetBefore','targetAfter',
        'userBefore','userAfter','phaseLog','httpTrace','command','faultReceipt','startedAt','finishedAt','exitCode','failures','skipped','version') {
        Assert-PhaseAcceptanceFields $Row @($field);Assert-PhaseAcceptanceFields $saved @($field)
        if (-not (Test-ColdInventoryEqual $Row.$field $saved.$field)) {throw 'PHASE_ACCEPT_SAVED_RESULT'}
    }
    foreach ($field in 'reason','cleanupFailure') {
        if ($saved.PSObject.Properties.Name -ccontains $field -and $saved.$field -cne '') {throw 'PHASE_ACCEPT_CONTRADICTION'}
    }
    $set=[ordered]@{}
    foreach ($entry in @{checkpoint='checkpoint.json';journal='journal-at-fault.json';fault='phased-fault.json';death='helper-death.json';
        epoch='recovery-epoch.json';launch='native-launch.json';exit='native-exit.json';recovery='recovery-observations.json';tools='tool-receipts.json'}.GetEnumerator()) {
        $set[$entry.Key]=Read-PhaseAcceptanceArtifact $evidence (Join-Path $evidence $entry.Value)
    }
    foreach ($name in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','phaseLog','httpTrace') {
        $path=Join-Path $evidence ($name+'.json')
        if ($Row.$name -cne $path) {throw 'PHASE_ACCEPT_ARTIFACT_BINDING'}
        $set[$name]=Read-PhaseAcceptanceArtifact $evidence $path
    }
    if ($Row.command -cne (Join-Path $evidence 'native-launch.json') -or $Row.faultReceipt -cne (Join-Path $evidence 'phased-fault.json') -or
        $set.death.checkpoint -cne (Join-Path $evidence 'checkpoint.json') -or
        $set.death.actualDiagnostics -cne (Join-Path $evidence 'helper-diagnostics.json')) {throw 'PHASE_ACCEPT_ARTIFACT_BINDING'}
    $set['journalSha256']=(Get-FileHash -LiteralPath (Join-Path $evidence 'journal-at-fault.json')).Hash.ToLowerInvariant()
    $diag=Read-PhaseAcceptanceArtifact $evidence $set.death.actualDiagnostics
    if (-not (Test-ColdInteger $diag.schemaVersion 1) -or $diag.schemaVersion -ne 1 -or
        ($diag.actualExit -isnot [int] -and $diag.actualExit -isnot [long]) -or $diag.actualExit -ne $set.death.actualExit -or
        -not (Test-ColdInventoryEqual $diag.identity $set.death.identity) -or $diag.actualDiagnostics.drained -isnot [bool] -or
        -not $diag.actualDiagnostics.drained -or $null -ne $diag.actualDiagnostics.failure) {throw 'PHASE_ACCEPT_DIAGNOSTICS'}
    foreach ($path in @($diag.actualDiagnostics.stdout,$diag.actualDiagnostics.stderr)+@($set.tools | ForEach-Object {@($_.stdout,$_.stderr)})) {
        $file=Resolve-PhaseAcceptancePath $path
        if (-not $file.StartsWith($evidence+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal) -or
            (Get-Item -LiteralPath $file).PSIsContainer) {throw 'PHASE_ACCEPT_ARTIFACT_SCOPE'}
    }
    foreach ($pin in @($Cold.baseManifests)+@([pscustomobject]@{manifest=$Cold.targetManifest;sha256=$Cold.targetManifestSha256})) {
        $file=Resolve-PhaseAcceptancePath $pin.manifest
        if ((Get-Item -LiteralPath $file).Length -gt 8388608 -or $pin.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            (Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant() -cne $pin.sha256) {throw 'PHASE_ACCEPT_PINS'}
        $manifest=ConvertFrom-ColdReceiptJson ([IO.File]::ReadAllText($file))
        $expected=if ($pin.manifest -ceq $Cold.targetManifest) {$Target} else {$Base}
        if ($expected.commitSha -ceq $manifest.commitSha -and -not (Test-ColdInventoryEqual $expected $manifest)) {throw 'PHASE_ACCEPT_PINS'}
        if ($pin.manifest -ceq $Cold.targetManifest -and -not (Test-ColdInventoryEqual $Target $manifest)) {throw 'PHASE_ACCEPT_PINS'}
    }
    $baseMatches=@($Cold.baseManifests | Where-Object {
        (ConvertFrom-ColdReceiptJson ([IO.File]::ReadAllText($_.manifest))).commitSha -ceq $Base.commitSha
    })
    if ($baseMatches.Count -ne 1) {throw 'PHASE_ACCEPT_PINS'}
    $javaFile=Resolve-PhaseAcceptancePath $Java
    $helperFile=Resolve-PhaseAcceptancePath $Cold.helperScript
    if ((Get-FileHash -LiteralPath $javaFile).Hash.ToLowerInvariant() -cne $Cold.runtimeSha256 -or
        (Get-FileHash -LiteralPath $helperFile).Hash.ToLowerInvariant() -cne $Cold.helperSha256) {throw 'PHASE_ACCEPT_PINS'}
    $toolArgs=@($Cold.toolArguments)
    if ($toolArgs.Count -ne 4 -or $toolArgs[0] -cne '--module-path' -or $toolArgs[2] -cne '-m' -or
        $toolArgs[3] -cne 'ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool') {throw 'PHASE_ACCEPT_PINS'}
    $jars=@($toolArgs[1].Split(';'))
    if (@($Cold.toolFiles).Count -ne $jars.Count -or $jars.Count -lt 2 -or $jars.Count -gt 4) {throw 'PHASE_ACCEPT_PINS'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($jar in $jars) {
        $pins=@($Cold.toolFiles | Where-Object path -CEQ $jar)
        if (-not $seen.Add($jar) -or $pins.Count -ne 1 -or $pins[0].sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            (Get-FileHash -LiteralPath (Resolve-PhaseAcceptancePath $jar)).Hash.ToLowerInvariant() -cne $pins[0].sha256) {throw 'PHASE_ACCEPT_PINS'}
    }
    if ($Row.phase -ceq 'WAITING') {
        $set['waitingLive']=Read-PhaseAcceptanceArtifact $evidence (Join-Path $evidence 'waiting-client-live.json')
        $set['waitingAtCheckpoint']=Read-PhaseAcceptanceArtifact $evidence (Join-Path $evidence 'waiting-lease-at-checkpoint.json')
        $set['waitingRemoved']=Read-PhaseAcceptanceArtifact $evidence (Join-Path $evidence 'waiting-client-removed.json')
    }
    if ($Row.client -cne 'web') {$set['physicalWindow']=Read-PhaseAcceptanceArtifact $evidence (Join-Path $evidence 'physical-main-window.json')}
    return Assert-NativePhaseReceiptSet $Row ([pscustomobject]$set) $Base $Target $Cold $Java
}
