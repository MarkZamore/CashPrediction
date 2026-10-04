<#
.SYNOPSIS
Независимая read-only приёмка rollback evidence, не promotion adapter return.
.DESCRIPTION
Только определения. Test-NativeRollbackAcceptance -Row $Row -Authority $Authority -RetainedHelper $Witness
возвращает status PASS/FAIL/PENDING, nativePass, reasons, missing и treeState, не меняет Row/файлы.
Authority: run, root, evidenceRoot, sourceRoot, targetRoot; originalManifest/targetManifest/originalHelper
в виде {path,sha256}; collector передаёт boundaryRendererInput и boundaryTimeout. run только Temp/run-UUID.
RetainedHelper: {process=Diagnostics.Process, identity={ProcessId,StartedAtTicks,ExecutablePath,OwnedRoot}}.
Требуется удержанный НЕ disposed handle фактически завершённого helper, не восстановленный PID или JSON.
Ledger CashMemory/Updates/native-rollback-write-boundaries.jsonl создают actual START/ATTEMPT/END
callbacks collector renderer. Проверяются identity, последовательность, Guard paths и точный renderer hash.
Это enforced helper-code boundary census, НЕ OS-wide monitor и не доказательство отсутствия Windows writes.
Без collector/retained handle всегда PENDING. Producer/call order описаны в collector.
UNIT_MOCK никогда не native PASS. Симулируется только disk-full IOException у Flush(true), не заполнение диска.
Физическое дерево проверяется повторно; TARGET распознаётся, но не подменяет ORIGINAL при ROLLED_BACK.
Scope PASS ограничен одной клеткой и guarded helper-code writes, не общей приёмкой этапа.
#>

# Возвращает чистый итог; FAIL противоречия имеют приоритет над missing evidence.
function New-RollbackAcceptanceResult($Errors,$Missing,[string]$TreeState='UNKNOWN',[string]$FaultModel='UNKNOWN') {
    $status=if ($Errors.Count) {'FAIL'} elseif ($Missing.Count) {'PENDING'} else {'PASS'}
    return [pscustomobject]@{status=$status;nativePass=($status -ceq 'PASS');scope='SINGLE_CELL_GUARDED_HELPER_CODE_NOT_OS_WIDE_TRACE';
        treeState=$TreeState;faultModel=$FaultModel;physicalDiskFilled=$false;reasons=@($Errors.ToArray());missing=@($Missing.ToArray())}
}

# Canonical пути проверяются без перехода через reparse предков, включая ещё не существующие элементы.
function Assert-RollbackAcceptancePath([string]$Path,[string]$Within='') {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path -cne [IO.Path]::GetFullPath($Path).TrimEnd('\','/')) {throw 'ACCEPT_PATH_CANONICAL'}
    $cursor=$Path
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if (((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {throw 'ACCEPT_REPARSE'}
        }
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
    if ($Within -and ($Path -ceq $Within -or -not $Path.StartsWith($Within+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase))) {throw 'ACCEPT_PATH_ESCAPE'}
    return $Path
}

# Читает bounded JSON; absent - missing, malformed/hash mismatch - contradiction.
function Read-RollbackAcceptanceJson([string]$Path,[string]$Within,$Missing,[string]$Sha256='') {
    [void](Assert-RollbackAcceptancePath $Path $Within)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {$Missing.Add('ABSENT '+$Path);return $null}
    $bytes=[IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -gt 8388608) {throw 'ACCEPT_JSON_LIMIT'}
    if ($Sha256 -and ($Sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $Sha256)) {throw 'ACCEPT_PIN_CHANGED'}
    $text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
    if ($text.Length -and $text[0] -eq [char]0xfeff) {$text=$text.Substring(1)}
    $options=@{Depth=64;NoEnumerate=$true}
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {$options.DateKind='String'}
    return ,(ConvertFrom-Json -InputObject $text @options)
}

# Изолированное AST closure только read-only inventory/identity функций, без body runner и global overwrite.
function Import-RollbackAcceptanceReaders([string]$ScriptsRoot=$PSScriptRoot) {
    $index=@{}
    foreach ($file in 'Test-UpdateBootstrap.ps1','Test-Portable.ps1') {
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot $file),[ref]$tokens,[ref]$errors)
        if ($errors.Count) {throw 'ACCEPT_READER_PARSE'}
        foreach ($node in $ast.EndBlock.Statements) {
            if ($node -is [Management.Automation.Language.FunctionDefinitionAst]) {
                if ($index.ContainsKey($node.Name)) {throw 'ACCEPT_READER_DUPLICATE'}
                $index[$node.Name]=$node
            }
        }
    }
    $queue=[Collections.Generic.Queue[string]]::new()
    foreach ($name in 'Get-ColdManagedInventory','Get-ColdUserInventory','Get-ColdTreeHash','Get-ColdProcessReceipt','Assert-ColdProcessIdentity','Test-ColdInventoryEqual','Get-ColdUtcTicks','Get-PortableRegistryPath') {$queue.Enqueue($name)}
    $selected=@{}
    while ($queue.Count) {
        $name=$queue.Dequeue();if ($selected.ContainsKey($name)) {continue}
        if (-not $index.ContainsKey($name)) {throw ('ACCEPT_READER_MISSING '+$name)}
        $node=$index[$name];$selected[$name]=$node.Extent.Text
        foreach ($call in $node.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true)) {
            $called=$call.GetCommandName();if (-not $called) {continue}
            if ($index.ContainsKey($called)) {$queue.Enqueue($called)}
            elseif (-not (Get-Command -Name $called -CommandType Cmdlet -ErrorAction SilentlyContinue)) {throw ('ACCEPT_READER_MISSING '+$called)}
        }
    }
    return (New-Module -Name ('RollbackAcceptanceReaders_'+[guid]::NewGuid().ToString('N')) -ArgumentList $selected -ScriptBlock {
        param($Definitions)
        foreach ($text in $Definitions.Values) {. ([scriptblock]::Create($text))}
    })
}

# Сопоставляет сохранённую identity без поиска процесса по PID и без методов завершения.
function Test-RollbackAcceptanceIdentity($Left,$Right) {
    if ($null -eq $Left -or $null -eq $Right) {return $false}
    foreach ($name in 'ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot') {
        if ($null -eq $Left.PSObject.Properties[$name] -or $null -eq $Right.PSObject.Properties[$name] -or $Left.$name -cne $Right.$name) {return $false}
    }
    return $true
}

# Проверяет реально производимый ledger: START перед первым sink, ATTEMPT до I/O, END в finally helper.
function Assert-RollbackAcceptanceBoundaries($Records,$Identity,[string]$Root,[string]$InputSha,[long]$FaultTicks,[long]$ExitTicks) {
    if (@($Records).Count -lt 3) {return 'BOUNDARY_LEDGER_INCOMPLETE'}
    $first=$Records[0];$last=$Records[-1]
    if ($null -ne $first.PSObject.Properties['evidenceKind'] -and $first.evidenceKind -ceq 'UNIT_MOCK') {return 'UNIT_MOCK_BOUNDARY_LEDGER'}
    foreach ($field in 'event','schemaVersion','basis','pid','startedAtTicks','executablePath','installationRoot','coveredSites','inputSha256','observedUtc') {
        if ($null -eq $first.PSObject.Properties[$field]) {return 'BOUNDARY_START_FIELDS_MISSING'}
    }
    if ($last.event -cne 'END') {return 'BOUNDARY_END_ABSENT'}
    if ($first.event -cne 'START' -or $first.schemaVersion -ne 1 -or $first.basis -cne 'ENFORCED_HELPER_FILE_WRITE_BOUNDARIES' -or
        $first.pid -ne $Identity.ProcessId -or $first.startedAtTicks -ne $Identity.StartedAtTicks -or
        $first.executablePath -cne $Identity.ExecutablePath -or $first.installationRoot -cne $Root -or
        $first.inputSha256 -cne $InputSha -or $first.coveredSites -le 0) {throw 'ACCEPT_BOUNDARY_START'}
    $previous=[datetime]::Parse($first.observedUtc).ToUniversalTime().Ticks
    if ($previous -lt $Identity.StartedAtTicks -or $previous -gt $FaultTicks) {throw 'ACCEPT_BOUNDARY_START_TIME'}
    for ($i=1;$i -lt $Records.Count-1;$i++) {
        $record=$Records[$i]
        if ($record.event -cne 'ATTEMPT' -or $record.sequence -ne $i -or
            $record.kind -cnotin @('Move','Replace','Delete','SetAttributes','OpenWrite','DirectoryMove','CreateDirectory','FileStreamWrite') -or
            @($record.paths).Count -lt 1) {throw 'ACCEPT_BOUNDARY_EVENT'}
        foreach ($path in $record.paths) {
            [void](Assert-RollbackAcceptancePath $path)
            if ($path -cne $Root) {[void](Assert-RollbackAcceptancePath $path $Root)}
        }
        $ticks=[datetime]::Parse($record.observedUtc).ToUniversalTime().Ticks
        if ($ticks -lt $previous) {throw 'ACCEPT_BOUNDARY_ORDER'};$previous=$ticks
    }
    $end=[datetime]::Parse($last.observedUtc).ToUniversalTime().Ticks
    if ($last.count -ne $Records.Count-2 -or $end -lt $previous -or $end -lt $FaultTicks -or ($ExitTicks -gt 0 -and $end -gt $ExitTicks)) {throw 'ACCEPT_BOUNDARY_END'}
    return ''
}

# Восстанавливает pure renderer из AST определения frozen helper, без выполнения script body.
function Get-RollbackAcceptanceExpectedInput([string]$Original,[string]$Scenario,[string]$FaultPath,[int]$Timeout) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'NativeUpdateRollbackScenarios.ps1'),[ref]$tokens,[ref]$errors)
    $nodes=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'New-NativeRollbackHelperText'})
    if ($errors.Count -or $nodes.Count -ne 1) {throw 'ACCEPT_RENDERER_DEFINITION'}
    $module=New-Module -ScriptBlock ([scriptblock]::Create($nodes[0].Extent.Text))
    return (& $module {param($O,$S,$P,$T) New-NativeRollbackHelperText $O $S $P $T} $Original $Scenario $FaultPath $Timeout)
}

# Независимая приёмка: status adapter игнорируется как положительное доказательство, negative contradiction сохраняется.
function Test-NativeRollbackAcceptance($Row,$Authority,$RetainedHelper=$null) {
    $errors=[Collections.Generic.List[string]]::new();$missing=[Collections.Generic.List[string]]::new();$treeState='UNKNOWN';$model='UNKNOWN'
    if ($null -eq $Row -or $null -eq $Authority) {$missing.Add('ROW_OR_AUTHORITY_ABSENT');return (New-RollbackAcceptanceResult $errors $missing)}
    if (($null -ne $Row.PSObject.Properties['evidenceKind'] -and $Row.evidenceKind -ceq 'UNIT_MOCK') -or
        ($null -ne $Authority.PSObject.Properties['evidenceKind'] -and $Authority.evidenceKind -ceq 'UNIT_MOCK')) {
        $missing.Add('UNIT_MOCK_NOT_NATIVE_EVIDENCE');return (New-RollbackAcceptanceResult $errors $missing)
    }
    try {
        foreach ($name in 'scenario','phase') {if ($null -eq $Row.PSObject.Properties[$name]) {$missing.Add('ROW_'+$name)}}
        if ($missing.Count) {return (New-RollbackAcceptanceResult $errors $missing)}
        if ($Row.scenario -cnotin @('locked-rollback','readonly-rollback','disk-full-rollback') -or $Row.phase -cne 'SESSION') {throw 'ACCEPT_SCENARIO'}
        $model=if ($Row.scenario -ceq 'disk-full-rollback') {'simulated-disk-full-IO-boundary'} else {'actual-managed-file-sharing-failure'}
        if ($null -ne $Row.PSObject.Properties['status'] -and $Row.status -ceq 'FAIL') {$errors.Add('ADAPTER_REPORTED_FAIL')}
        foreach ($name in 'run','root','evidenceRoot','sourceRoot','targetRoot','originalManifest','targetManifest','originalHelper') {
            if ($null -eq $Authority.PSObject.Properties[$name]) {$missing.Add('AUTHORITY_'+$name)}
        }
        if ($missing.Count) {return (New-RollbackAcceptanceResult $errors $missing $treeState $model)}
        [void](Assert-RollbackAcceptancePath $Authority.run)
        if ([IO.Path]::GetDirectoryName($Authority.run) -cne [IO.Path]::GetTempPath().TrimEnd('\','/') -or
            [IO.Path]::GetFileName($Authority.run) -cnotmatch '^run-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'ACCEPT_OWN_RUN'}
        foreach ($name in 'root','evidenceRoot') {[void](Assert-RollbackAcceptancePath $Authority.$name $Authority.run)}
        foreach ($name in 'sourceRoot','targetRoot') {
            [void](Assert-RollbackAcceptancePath $Authority.$name)
            if ($Authority.$name -ceq $Authority.run -or $Authority.$name.StartsWith($Authority.run+'\',[StringComparison]::OrdinalIgnoreCase) -or
                $Authority.run.StartsWith($Authority.$name+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'ACCEPT_FROZEN_SOURCE_OVERLAP'}
        }
        if ($Authority.root -ceq $Authority.evidenceRoot -or $Authority.root.StartsWith($Authority.evidenceRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or
            $Authority.evidenceRoot.StartsWith($Authority.root+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'ACCEPT_ROOT_EVIDENCE_OVERLAP'}
        $data=@{}
        foreach ($name in 'currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','phaseLog','httpTrace') {
            $path=Join-Path $Authority.evidenceRoot ($name+'.json')
            if ($null -ne $Row.PSObject.Properties[$name] -and $Row.$name -cne $path) {throw 'ACCEPT_ROW_ARTIFACT_PATH'}
            $data[$name]=Read-RollbackAcceptanceJson $path $Authority.evidenceRoot $missing
        }
        foreach ($pair in @(@('fault','fault-observed.json'),@('injection','injection-receipt.json'),@('journal','production-journal-before.json'),
            @('last','last-install-observed.json'),@('cleanup','cleanup.json'),@('launch','helper-launch.json'),@('clientExit','ordinary-exit.json'),@('baseline','fixture-baseline.json'))) {
            $data[$pair[0]]=Read-RollbackAcceptanceJson (Join-Path $Authority.evidenceRoot $pair[1]) $Authority.evidenceRoot $missing
        }
        foreach ($pair in @(@('original','originalManifest'),@('target','targetManifest'))) {
            $pin=$Authority.($pair[1]);$data[$pair[0]]=Read-RollbackAcceptanceJson $pin.path '' $missing $pin.sha256
        }
        if ($missing.Count) {return (New-RollbackAcceptanceResult $errors $missing $treeState $model)}
        $required=@{fault=@('schemaVersion','installationRoot','transactionId','scenario','boundary','phase','operation','hresult','exceptionType','pid','startedAtTicks','executablePath','observedUtc','operations','scope');
            injection=@('originalHelper','originalHelperSha256','portableOriginalHelper','portableOriginalHelperSha256','ownHelper','ownHelperSha256','scenario','faultPath','lockShare','scope','physicalDiskFilled','transactionId');
            journal=@('installationRoot','transactionId','oldTreeSha256','oldFiles','target','phase');last=@('outcome','transactionId','targetCommitSha');
            cleanup=@('root','errors','unresolvedStartups','helperIdentity','uiIdentity','scope','node','nativeCompleted','filesRetained');launch=@('identity','args','helperSha256');
            clientExit=@('pid','startedAtTicks','exitCode','remainingClients','kind','exitedUtc');baseline=@('fixtureTreeSha256','originalTreeSha256');
            original=@('files','treeSha256','releaseNumber','commitSha');target=@('files','treeSha256','releaseNumber','commitSha')}
        foreach ($name in $required.Keys) {
            foreach ($field in $required[$name]) {if ($null -eq $data[$name].PSObject.Properties[$field]) {$missing.Add('FIELD '+$name+'.'+$field)}}
        }
        if ($missing.Count) {return (New-RollbackAcceptanceResult $errors $missing $treeState $model)}
        $readers=Import-RollbackAcceptanceReaders
        $base=$data.original;$target=$data.target;$fault=$data.fault;$injection=$data.injection;$journal=$data.journal;$cleanup=$data.cleanup;$identity=$data.launch.identity
        $originalFiles=$base.files | ConvertTo-Json -Depth 16 | ConvertFrom-Json
        if ($Row.scenario -ceq 'readonly-rollback') {
            $exe=@($originalFiles | Where-Object {$_.path -ceq 'CashPrediction.exe'})
            if ($exe.Count -ne 1) {throw 'ACCEPT_READONLY_BASELINE'};$exe[0].readOnly=$true
        }
        $beforeHash=& $readers {param($Files) Get-ColdTreeHash $Files} $originalFiles
        foreach ($manifest in @($base,$target)) {
            if ((& $readers {param($Files) Get-ColdTreeHash $Files} $manifest.files) -cne $manifest.treeSha256 -or
                $manifest.commitSha -cnotmatch '^[0-9a-f]{40}$' -or $manifest.releaseNumber -le 0) {throw 'ACCEPT_MANIFEST_DIGEST'}
        }
        $equal=& $readers {param($A,$B) Test-ColdInventoryEqual $A $B} $data.currentBefore @($originalFiles)
        if (-not $equal -or $journal.oldTreeSha256 -cne $beforeHash -or $data.baseline.fixtureTreeSha256 -cne $beforeHash -or
            $data.baseline.originalTreeSha256 -cne $base.treeSha256) {throw 'ACCEPT_ORIGINAL_BASELINE'}
        $current=@(& $readers {param($Root) Get-ColdManagedInventory $Root} $Authority.root)
        $treeState=if (& $readers {param($A,$B) Test-ColdInventoryEqual $A $B} $current @($originalFiles)) {'ORIGINAL'}
            elseif (& $readers {param($A,$B) Test-ColdInventoryEqual $A $B} $current @($target.files)) {'TARGET'} else {'PARTIAL'}
        if ($treeState -ceq 'PARTIAL' -or -not (& $readers {param($A,$B) Test-ColdInventoryEqual $A $B} $current $data.currentAfter)) {throw 'ACCEPT_PARTIAL_OR_STALE_TREE'}
        foreach ($name in 'targetBefore','targetAfter') {
            if (-not (& $readers {param($A,$B) Test-ColdInventoryEqual $A $B} $data[$name] @($target.files))) {throw 'ACCEPT_TARGET_CHANGED'}
        }
        foreach ($pair in @(@($Authority.sourceRoot,$base.files),@($Authority.targetRoot,$target.files))) {
            $actual=@(& $readers {param($Root) Get-ColdManagedInventory $Root} $pair[0])
            if (-not (& $readers {param($A,$B) Test-ColdInventoryEqual $A $B} $actual @($pair[1]))) {throw 'ACCEPT_FROZEN_TREE_CHANGED'}
        }
        $users=& $readers {param($Root) $map=[ordered]@{};foreach ($item in @(Get-ColdUserInventory $Root)) {$map[$item.path]=$item};return $map} $Authority.root
        if (-not (& $readers {param($A,$B) Test-ColdInventoryEqual $A $B} $data.userBefore $data.userAfter) -or
            -not (& $readers {param($A,$B) Test-ColdInventoryEqual $A $B} $users $data.userAfter)) {throw 'ACCEPT_USER_CHANGED'}
        $transaction=$journal.transactionId;[void][guid]::Parse($transaction)
        $kind=if ($Row.scenario -ceq 'locked-rollback') {'BACKUP'} else {'REPLACE'}
        $phase=if ($Row.scenario -ceq 'locked-rollback') {'BACKING_UP'} else {'INSTALLING'}
        $boundary=switch -CaseSensitive ($Row.scenario) {'locked-rollback' {'File.Move'} 'readonly-rollback' {'File.Replace'} 'disk-full-rollback' {'FileOperation.Flush(true)'}}
        $codes=if ($Row.scenario -ceq 'disk-full-rollback') {@('80070070')} else {@('80070020','80070021')}
        if ($fault.installationRoot -cne $Authority.root -or $journal.installationRoot -cne $Authority.root -or
            $fault.transactionId -cne $transaction -or $injection.transactionId -cne $transaction -or $fault.scenario -cne $Row.scenario -or $injection.scenario -cne $Row.scenario -or
            $fault.scope -cne $model -or $injection.scope -cne $model -or $injection.physicalDiskFilled -isnot [bool] -or $injection.physicalDiskFilled -or
            $fault.exceptionType -cne 'System.IO.IOException' -or $fault.hresult -cnotin $codes -or $fault.boundary -cne $boundary -or
            $fault.phase -cne $phase -or $fault.operation.kind -cne $kind -or $fault.operation.state -cne 'BEFORE' -or
            $fault.operation.path -cne $injection.faultPath) {throw 'ACCEPT_FAULT_CONTRADICTION'}
        if ($Row.scenario -ceq 'locked-rollback') {
            if ($injection.faultPath -cnotmatch '^runtime/' -or @($originalFiles | Where-Object {$_.path -ceq $injection.faultPath -and -not $_.readOnly}).Count -ne 1) {throw 'ACCEPT_RUNTIME_LOCK'}
        } elseif ($injection.faultPath -cne 'CashPrediction.exe') {throw 'ACCEPT_EXE_FAULT_PATH'}
        if ($Row.scenario -cne 'disk-full-rollback' -and $injection.lockShare -cne 'ReadWrite, no Delete') {throw 'ACCEPT_LOCK_SHARE'}
        if (-not @($fault.operations | Where-Object {$_.state -ceq 'AFTER' -and $_.kind -cin @('BACKUP','REDIRECT','REPLACE')}).Count) {throw 'ACCEPT_NO_ACTUAL_MUTATION'}
        $faultTicks=& $readers {param($Value) Get-ColdUtcTicks $Value} $fault.observedUtc
        $clientExitTicks=& $readers {param($Value) Get-ColdUtcTicks $Value} $data.clientExit.exitedUtc
        if ($data.clientExit.exitCode -ne 0 -or $data.clientExit.remainingClients -ne 0 -or $data.clientExit.kind -cne 'ordinary-no-restart' -or
            $data.clientExit.pid -ne $cleanup.uiIdentity.pid -or $data.clientExit.startedAtTicks -ne $cleanup.uiIdentity.startedAtTicks -or
            $faultTicks -lt $clientExitTicks -or $faultTicks -lt $identity.StartedAtTicks) {throw 'ACCEPT_CLIENT_EXIT_OR_TIMELINE'}
        if ($data.phaseLog -cnotcontains 'SESSION' -or $data.phaseLog -cnotcontains 'ROLLING_BACK' -or $data.phaseLog -ccontains 'COMMITTED' -or
            $data.last.outcome -cne 'ROLLED_BACK' -or $data.last.transactionId -cne $transaction -or $data.last.targetCommitSha -cne $target.commitSha -or
            $treeState -cne 'ORIGINAL') {throw 'ACCEPT_ROLLBACK_OUTCOME'}
        $log=Join-Path $Authority.evidenceRoot 'update-log.md';[void](Assert-RollbackAcceptancePath $log $Authority.evidenceRoot)
        if (-not (Test-Path -LiteralPath $log)) {$missing.Add('PHYSICAL_PHASE_LOG_ABSENT')}
        else {
            $observed=@(Get-Content -LiteralPath $log | ForEach-Object {
                if ($_ -cmatch '^- (\S+) PHASE_([A-Z_]+)$') {
                    $time=$Matches[1];$phaseName=$Matches[2]
                    if ($phaseName -cin @('BOOTSTRAPPING','BACKING_UP','INSTALLING','VERIFYING','COMMITTED','ROLLING_BACK') -and
                        (& $readers {param($T) Get-ColdUtcTicks $T} $time) -lt $clientExitTicks) {throw 'ACCEPT_MUTATION_BEFORE_EXIT'}
                    if ($phaseName -ceq 'ROLLING_BACK' -and (& $readers {param($T) Get-ColdUtcTicks $T} $time) -lt $faultTicks) {throw 'ACCEPT_ROLLBACK_BEFORE_FAULT'}
                    $phaseName
                }
            })
            if ($observed -cnotcontains 'ROLLING_BACK' -or $observed -ccontains 'COMMITTED') {throw 'ACCEPT_PHASE_LOG_CONTRADICTION'}
        }
        if ($cleanup.root -cne $Authority.root -or $cleanup.scope -cne 'OWN_RUN_UUID_AND_SELFTEST_NODE' -or
            $cleanup.nativeCompleted -isnot [bool] -or -not $cleanup.nativeCompleted -or $cleanup.filesRetained -isnot [bool] -or -not $cleanup.filesRetained -or
            @($cleanup.errors).Count -or @($cleanup.unresolvedStartups).Count -or
            -not (Test-RollbackAcceptanceIdentity $cleanup.helperIdentity $identity) -or $identity.OwnedRoot -cne $Authority.root -or
            $fault.pid -ne $identity.ProcessId -or $fault.startedAtTicks -ne $identity.StartedAtTicks -or $fault.executablePath -cne $identity.ExecutablePath) {throw 'ACCEPT_IDENTITY_OR_CLEANUP'}
        $registry=& $readers {param($Node) Get-PortableRegistryPath $Node} $cleanup.node
        if (Test-Path -LiteralPath $registry) {throw 'ACCEPT_REGISTRY_CLEANUP_RESIDUE'}
        foreach ($relative in @('install-journal.json','completed-journal.json','Bootstrap',('Bootstrap.partial-'+$transaction),('Work-'+$transaction),'Backup')) {
            if (Test-Path -LiteralPath (Join-Path $Authority.root ('CashMemory/Updates/'+$relative))) {throw 'ACCEPT_ROLLBACK_RESIDUE'}
        }
        foreach ($relative in 'requests','processes') {
            $directory=Join-Path $Authority.root ('CashMemory/Updates/'+$relative)
            if ((Test-Path -LiteralPath $directory) -and @(Get-ChildItem -LiteralPath $directory -Force).Count) {throw 'ACCEPT_LEASE_OR_REQUEST_RESIDUE'}
        }
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("& '"+$injection.ownHelper.Replace("'","''")+"' -InstallationRoot '"+$Authority.root.Replace("'","''")+"' -Diagnostics"))
        if ($data.launch.args.Count -ne 8 -or $data.launch.args[6] -cne '-EncodedCommand' -or $data.launch.args[7] -cne $encoded) {throw 'ACCEPT_HELPER_COMMAND'}
        foreach ($pin in @($Authority.originalHelper,[pscustomobject]@{path=$injection.ownHelper;sha256=$injection.ownHelperSha256},
            [pscustomobject]@{path=$injection.portableOriginalHelper;sha256=$injection.portableOriginalHelperSha256})) {
            [void](Assert-RollbackAcceptancePath $pin.path)
            if (-not (Test-Path -LiteralPath $pin.path)) {$missing.Add('HELPER_PIN_FILE_ABSENT')}
            elseif ((Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha256) {throw 'ACCEPT_HELPER_PIN_CHANGED'}
        }
        [void](Assert-RollbackAcceptancePath $injection.ownHelper $Authority.evidenceRoot)
        if ($injection.portableOriginalHelper -cne (Join-Path $Authority.root 'CashMemory/Updates/apply-update.ps1') -or
            $injection.originalHelper -cne $Authority.originalHelper.path -or $injection.originalHelperSha256 -cne $Authority.originalHelper.sha256 -or
            $injection.portableOriginalHelperSha256 -cne $Authority.originalHelper.sha256 -or $data.launch.helperSha256 -cne $injection.ownHelperSha256) {throw 'ACCEPT_HELPER_PROVENANCE'}
        $exitTicks=0L
        if ($null -eq $RetainedHelper -or $RetainedHelper.process -isnot [Diagnostics.Process]) {$missing.Add('GENUINE_RETAINED_HELPER_HANDLE_ABSENT')}
        else {
            if (-not (Test-RollbackAcceptanceIdentity $RetainedHelper.identity $identity)) {throw 'ACCEPT_RETAINED_IDENTITY'}
            try {
                $actual=& $readers {param($Process,$Root) Get-ColdProcessReceipt $Process $Root} $RetainedHelper.process $Authority.root
                if (-not (Test-RollbackAcceptanceIdentity $actual $identity)) {throw 'ACCEPT_RETAINED_IDENTITY'}
                if (-not $RetainedHelper.process.HasExited) {$missing.Add('HELPER_NOT_EXITED')}
                elseif ($RetainedHelper.process.ExitCode -ne 0) {throw 'ACCEPT_RETAINED_EXIT_FAILURE'}
                else {$exitTicks=$RetainedHelper.process.ExitTime.ToUniversalTime().Ticks;if ($exitTicks -lt $faultTicks) {throw 'ACCEPT_RETAINED_EXIT_BEFORE_FAULT'}}
            } catch {
                if ($_.Exception.Message -like '*ACCEPT_RETAINED*') {throw}
                $missing.Add('RETAINED_HANDLE_UNREADABLE_OR_DISPOSED')
            }
        }
        $ledger=Join-Path $Authority.root 'CashMemory/Updates/native-rollback-write-boundaries.jsonl'
        [void](Assert-RollbackAcceptancePath $ledger $Authority.root)
        if (-not (Test-Path -LiteralPath $ledger)) {$missing.Add('GUARDED_BOUNDARY_LEDGER_ABSENT')}
        elseif ($null -eq $Authority.PSObject.Properties['boundaryRendererInput'] -or $null -eq $Authority.PSObject.Properties['boundaryTimeout'] -or
            -not (Get-Command New-NativeRollbackWriteBoundaryHelper -ErrorAction SilentlyContinue)) {$missing.Add('COLLECTOR_RENDERER_PROVENANCE_ABSENT')}
        else {
            $originalText=[Text.UTF8Encoding]::new($false,$true).GetString([IO.File]::ReadAllBytes($Authority.originalHelper.path))
            $input=Get-RollbackAcceptanceExpectedInput $originalText $Row.scenario $injection.faultPath $Authority.boundaryTimeout
            if ($input -cne $Authority.boundaryRendererInput) {throw 'ACCEPT_RENDERER_INPUT_CHANGED'}
            $expected=New-NativeRollbackWriteBoundaryHelper $input
            $expectedSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($expected))).ToLowerInvariant()
            if ($expectedSha -cne $injection.ownHelperSha256) {throw 'ACCEPT_RENDERED_HELPER_CHANGED'}
            if ((Get-Item -LiteralPath $ledger).Length -gt 16777216) {throw 'ACCEPT_BOUNDARY_LEDGER_LIMIT'}
            $options=@{Depth=16};if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) {$options.DateKind='String'}
            $records=@(Get-Content -LiteralPath $ledger | ForEach-Object {ConvertFrom-Json -InputObject $_ @options})
            if ($records.Count -gt 50002) {throw 'ACCEPT_BOUNDARY_EVENT_LIMIT'}
            $inputSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($input))).ToLowerInvariant()
            $why=Assert-RollbackAcceptanceBoundaries $records $identity $Authority.root $inputSha $faultTicks $exitTicks
            if ($why) {$missing.Add($why)}
        }
    } catch {$errors.Add($_.Exception.Message)}
    return (New-RollbackAcceptanceResult $errors $missing $treeState $model)
}
