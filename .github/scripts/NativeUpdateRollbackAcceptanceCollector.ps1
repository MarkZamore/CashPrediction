<#
.SYNOPSIS
Bounded collector actual helper handle и enforced file-write boundaries после frozen runner cleanup.
.DESCRIPTION
Только определения, не native запуск при import. Invoke-NativeRollbackCollectedAcceptance принимает
изолированный Adapter module, девять аргументов Invoke-NativeRollbackScenario и Authority с frozen pins.
Временно заменяет только два определения ВНУТРИ adapter module, затем восстанавливает их.
Renderer hook добавляет Guard/ATTEMPT ledger на фактических FS write sites собственного helper.
START/END producer - сам исполняющийся instrumented helper; identity/exit producer - второй handle,
проверенный по birth/exe сразу после actual Start. Acceptance вызывается после return/finally runner.
Никаких новых пауз, fault инъекций, шаблонов journal или OS-wide monitor обещаний.
Полнота ограничена write sites pinned helper/application code, не скрытыми writes Windows/PowerShell.

Producer/callsite и хронология (все существующие reports читаются, не дорисовываются):
1 Invoke-NativeRollbackScenario: fixture-baseline/currentBefore/userBefore, production-journal-before.
2 New-NativeRollbackHelperText hook: исходный SHA + injection-receipt, дополнительно Guard sinks.
3 Start-NativeRollbackOwned hook: helper-launch identity + второй birth/exe-verified retained handle.
4 UI ordinary-exit.json; actual helper NativeRollbackCapture: fault-observed.json, phase/IOException.
5 NativeAcceptanceBoundary: START/ATTEMPT до guarded I/O; END после helper finally, не после runner cleanup.
6 Runner: currentAfter/userAfter/phaseLog/last-install-observed; Row.finishedAt ПЕРЕД finally.
7 Runner finally: cleanup.json (identity, errors, unresolved startups, node), cell.json.
8 Collector после return/throw: fresh physical inventories + retained exit + ledger acceptance; Dispose последним.
Controlled session files исключены из user inventory: их проверяет существующий Assert-ColdControlledChanges
в runner; отдельного independent controlled receipt сейчас нет. Не выдавать этот scope за whole-app/OS audit.
Authority frozen pins берёт MAIN из уже verified manifests и Cold.helperScript/helperSha256;
sourceRoot/targetRoot - физические frozen portable inputs. run/root/evidenceRoot заполняет collector.
#>

# Статический полный census допустимых файловых write sinks, unknown mutator запрещён до native запуска.
function New-NativeRollbackWriteBoundaryHelper([string]$InputText) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($InputText,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'BOUNDARY_HELPER_PARSE'}
    if (@($ast.FindAll({param($n) $n -is [Management.Automation.Language.RedirectionAst]},$true)).Count) {throw 'BOUNDARY_UNHANDLED_REDIRECTION'}
    $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true) | ForEach-Object {$_.Name})
    $cmdlets=@('ConvertFrom-Json','ConvertTo-Json','ForEach-Object','Get-ChildItem','Get-Item','Get-Process','Join-Path','New-Object',
        'Set-StrictMode','Sort-Object','Start-Sleep','Stop-Process','Test-Path','Where-Object')
    foreach ($call in $ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true)) {
        $name=$call.GetCommandName()
        if (-not $name -or ($name -cnotin $definitions -and $name -cnotin $cmdlets)) {throw 'BOUNDARY_UNHANDLED_COMMAND'}
        if ($name -ceq 'New-Object' -and $call.Extent.Text -cnotmatch "^New-Object (System.Text.UTF8Encoding\(|'System.Collections.Generic\.|byte\[\] |Diagnostics.ProcessStartInfo$)") {throw 'BOUNDARY_UNHANDLED_NEW_OBJECT'}
    }
    foreach ($node in $ast.FindAll({param($n) $n -is [Management.Automation.Language.InvokeMemberExpressionAst] -and -not $n.Static},$true)) {
        # stderr уже redirected Start-NativeRollbackOwned в pipe, сохранение output делает runner в own evidence.
        if ($node.Member.Value -ceq 'WriteLine' -and $node.Expression.Extent.Text -ceq '[Console]::Error') {continue}
        $pureOrOwned=@('Add','ComputeHash','Contains','ContainsKey','Dequeue','Dispose','Enqueue','Equals','GetBaseException','GetByteCount','GetBytes',
            'GetString','GetType','LastIndexOf','Lock','Normalize','Read','Replace','Split','StartsWith','Substring','ToLowerInvariant',
            'ToString','ToUnixTimeMilliseconds','ToUniversalTime','ToUpperInvariant','TransformBlock','TransformFinalBlock','TrimEnd','Write','Flush')
        if ($node.Member.Value -cnotin $pureOrOwned) {throw ('BOUNDARY_UNHANDLED_INSTANCE_MEMBER '+$node.Member.Value+' '+$node.Expression.Extent.Text)}
        if ($node.Member.Value -cin @('Write','Flush') -and $node.Expression.Extent.Text -cnotin @('$stream','$output')) {throw 'BOUNDARY_UNHANDLED_INSTANCE_WRITE'}
    }
    $edits=[Collections.Generic.List[object]]::new();$count=0
    foreach ($node in $ast.FindAll({param($n) $n -is [Management.Automation.Language.InvokeMemberExpressionAst] -and $n.Static},$true)) {
        $type=$node.Expression.Extent.Text;$member=[string]$node.Member.Value;$paths=@();$kind=''
        if ($type -cin @('[IO.File]','[System.IO.File]')) {
            if ($member -cin @('Move','Replace')) {
                if ($member -ceq 'Replace' -and ($node.Arguments.Count -ne 3 -or $node.Arguments[2].Extent.Text -cne '[System.Management.Automation.Language.NullString]::Value')) {throw 'BOUNDARY_UNHANDLED_REPLACE_BACKUP'}
                $paths=@(0,1);$kind=$member
            }
            elseif ($member -cin @('Delete','SetAttributes')) {$paths=@(0);$kind=$member}
            elseif ($member -ceq 'Open') {
                if ($node.Extent.Text -notmatch '\[IO.FileAccess\]::Read(?:,|\))') {$paths=@(0);$kind='OpenWrite'}
            } elseif ($member -cnotin @('Exists','OpenRead','ReadAllBytes','GetAttributes')) {throw ('BOUNDARY_UNHANDLED_FILE '+$member)}
        } elseif ($type -cin @('[IO.Directory]','[System.IO.Directory]')) {
            if ($member -ceq 'Move') {$paths=@(0,1);$kind='DirectoryMove'}
            elseif ($member -cin @('CreateDirectory','Delete')) {$paths=@(0);$kind=$member}
            elseif ($member -cne 'Exists') {throw ('BOUNDARY_UNHANDLED_DIRECTORY '+$member)}
        } elseif ($type -cin @('[IO.FileStream]','[System.IO.FileStream]')) {
            if ($member -cne 'new') {throw 'BOUNDARY_UNHANDLED_STREAM'}
            $paths=@(0);$kind='FileStreamWrite'
        } elseif ($type -ceq '[Diagnostics.Process]' -and $member -ceq 'Start') {
            # Ordinary exit сценарий не должен перезапускать клиентов; неизвестные дочерние writers не скрываются.
            $edits.Add([pscustomobject]@{offset=$node.Extent.StartOffset;length=$node.Extent.Text.Length;text="`$(throw 'BOUNDARY_CHILD_PROCESS_UNSUPPORTED')"});continue
        } elseif ($type -cmatch '^\[(System\.)?IO\.' -and $type -cnotin @('[IO.Path]','[IO.IOException]')) {
            throw 'BOUNDARY_UNHANDLED_IO_TYPE'
        } elseif ($type -cnotin @('[Array]','[BitConverter]','[Convert]','[DateTime]','[Diagnostics.Process]','[Diagnostics.Stopwatch]',
            '[guid]','[IO.Path]','[IO.IOException]','[Security.Cryptography.SHA256]','[regex]')) {
            throw 'BOUNDARY_UNHANDLED_STATIC_TYPE'
        } elseif ($type -ceq '[Diagnostics.Process]' -and $member -cne 'GetCurrentProcess') {
            throw 'BOUNDARY_UNHANDLED_PROCESS_MEMBER'
        }
        if (-not $kind) {continue}
        $arguments=@(foreach ($position in $paths) {
            if ($position -ge $node.Arguments.Count) {throw 'BOUNDARY_ARGUMENT_CENSUS'}
            $node.Arguments[$position].Extent.Text
        }) -join ','
        $replacement='$(' + "NativeAcceptanceBoundary '$kind' @($arguments); " + $node.Extent.Text + ')'
        $edits.Add([pscustomobject]@{offset=$node.Extent.StartOffset;length=$node.Extent.Text.Length;text=$replacement});$count++
    }
    $guard=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst] -and $_.Name -ceq 'Guard'})
    $topTry=@($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.TryStatementAst] -and $null -ne $_.Finally})
    if ($guard.Count -ne 1 -or $topTry.Count -ne 1 -or $count -eq 0) {throw 'BOUNDARY_CONTROL_CENSUS'}
    $hook=@'
$script:acceptanceBoundaryStarted=$false
$script:acceptanceBoundaryCount=0
function NativeAcceptanceRecord($record) {
    $path=Guard (Join-Path $updates 'native-rollback-write-boundaries.jsonl')
    $bytes=$utf8.GetBytes((ConvertTo-Json -InputObject $record -Depth 8 -Compress)+[Environment]::NewLine)
    $stream=[IO.FileStream]::new($path,[IO.FileMode]::Append,[IO.FileAccess]::Write,[IO.FileShare]::Read,4096,[IO.FileOptions]::WriteThrough)
    try {if ($stream.Length+$bytes.Length -gt 16777216) {throw 'BOUNDARY_LEDGER_LIMIT'};$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
}
function NativeAcceptanceBoundary([string]$kind,[string[]]$paths) {
    foreach ($path in $paths) {[void](Guard $path)}
    if (-not $script:acceptanceBoundaryStarted) {
        $script:acceptanceBoundaryStarted=$true
        $process=[Diagnostics.Process]::GetCurrentProcess()
        try {NativeAcceptanceRecord ([ordered]@{event='START';schemaVersion=1;basis='ENFORCED_HELPER_FILE_WRITE_BOUNDARIES';
            pid=[long]$PID;startedAtTicks=$process.StartTime.ToUniversalTime().Ticks;executablePath=$process.MainModule.FileName;
            installationRoot=$root;coveredSites=__COUNT__;inputSha256='__INPUT_SHA__';observedUtc=[DateTime]::UtcNow.ToString('o')})} finally {$process.Dispose()}
    }
    $script:acceptanceBoundaryCount++
    if ($script:acceptanceBoundaryCount -gt 50000) {throw 'BOUNDARY_EVENT_LIMIT'}
    NativeAcceptanceRecord ([ordered]@{event='ATTEMPT';sequence=$script:acceptanceBoundaryCount;kind=$kind;paths=$paths;observedUtc=[DateTime]::UtcNow.ToString('o')})
}

'@
    $inputSha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($InputText))).ToLowerInvariant()
    $hook=$hook.Replace('__COUNT__',[string]$count).Replace('__INPUT_SHA__',$inputSha)
    $edits.Add([pscustomobject]@{offset=$guard[0].Extent.StartOffset;length=0;text=$hook})
    $edits.Add([pscustomobject]@{offset=$topTry[0].Finally.Extent.EndOffset-1;length=0;
        text="`n if (`$script:acceptanceBoundaryStarted) { NativeAcceptanceRecord ([ordered]@{event='END';count=`$script:acceptanceBoundaryCount;observedUtc=[DateTime]::UtcNow.ToString('o')}) }`n"})
    foreach ($edit in $edits | Sort-Object offset -Descending) {$InputText=$InputText.Remove($edit.offset,$edit.length).Insert($edit.offset,$edit.text)}
    [void][Management.Automation.Language.Parser]::ParseInput($InputText,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'BOUNDARY_GENERATED_PARSE'}
    return $InputText
}

# Дублирует handle при actual Start, сверяет обе личности; PID lookup не принимается без birth/exe matching.
function Open-NativeRollbackAcceptanceHandle($Native,[string]$Root,[scriptblock]$ReadIdentity) {
    if ($Native.process -isnot [Diagnostics.Process]) {throw 'COLLECTOR_REAL_PROCESS_REQUIRED'}
    $copy=[Diagnostics.Process]::GetProcessById($Native.process.Id)
    try {
        [void]$copy.Handle
        $actual=& $ReadIdentity $copy $Root
        foreach ($name in 'ProcessId','StartedAtTicks','ExecutablePath','OwnedRoot') {
            if ($actual.$name -cne $Native.identity.$name) {throw 'COLLECTOR_RETAINED_IDENTITY'}
        }
        return [pscustomobject]@{process=$copy;identity=$actual}
    } catch {$copy.Dispose();throw}
}

# Запускается MAIN только после очереди: callback capture -> runner finally/cleanup -> независимый acceptance.
function Invoke-NativeRollbackCollectedAcceptance($Adapter,$Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout,$Authority,[scriptblock]$AcceptanceObserver=$null) {
    if ($Adapter -isnot [Management.Automation.PSModuleInfo]) {throw 'COLLECTOR_ISOLATED_ADAPTER_REQUIRED'}
    $renderer=${function:New-NativeRollbackWriteBoundaryHelper}.ToString()
    $handleReader=${function:Open-NativeRollbackAcceptanceHandle}.ToString()
    $state=[pscustomobject]@{witness=$null;root=$null;rendererInput=$null;failure=$null}
    $original=& $Adapter {
        @((Get-Command New-NativeRollbackHelperText).ScriptBlock,(Get-Command Start-NativeRollbackOwned).ScriptBlock)
    }
    $primaryError=$null;$collected=$null
    $cleanupErrors=[Collections.Generic.List[object]]::new()
    try {
        & $Adapter {
            param($Original,$Renderer,$HandleReader,$State)
            $script:acceptanceOriginalRenderer=$Original[0];$script:acceptanceOriginalStart=$Original[1];$script:acceptanceCollectorState=$State
            Set-Item Function:script:New-NativeRollbackWriteBoundaryHelper ([scriptblock]::Create($Renderer))
            Set-Item Function:script:Open-NativeRollbackAcceptanceHandle ([scriptblock]::Create($HandleReader))
            Set-Item Function:script:New-NativeRollbackHelperText {
                param($Original,$Scenario,$FaultPath,$Timeout)
                $text=& $script:acceptanceOriginalRenderer $Original $Scenario $FaultPath $Timeout
                $script:acceptanceCollectorState.rendererInput=$text
                New-NativeRollbackWriteBoundaryHelper $text
            }
            Set-Item Function:script:Start-NativeRollbackOwned {
                param($Executable,$Arguments,$Root,$Endpoint='',[switch]$Web)
                $entry=& $script:acceptanceOriginalStart $Executable $Arguments $Root $Endpoint -Web:$Web
                if ($Executable -ceq (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe') -and $Arguments -ccontains '-EncodedCommand') {
                    $script:acceptanceCollectorState.root=$Root
                    try {$script:acceptanceCollectorState.witness=Open-NativeRollbackAcceptanceHandle $entry $Root {param($Process,$Path) Get-ColdProcessReceipt $Process $Path}}
                    catch {$script:acceptanceCollectorState.failure=$_.Exception.Message}
                }
                return $entry
            }
        } $original $renderer $handleReader $state
        $adapterFailure=$null
        try {& $Adapter {param($R,$S,$B,$T,$L,$C,$J,$E,$N) Invoke-NativeRollbackScenario $R $S $B $T $L $C $J $E $N} $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout | Out-Null}
        catch {$adapterFailure=$_.Exception.Message}
        # Scope/finishedAt берутся после finally, а не из раннего Row.finishedAt frozen runner.
        if ($state.root) {
            $Authority | Add-Member root $state.root -Force
            $Authority | Add-Member evidenceRoot $Row.evidenceRoot -Force
            $Authority | Add-Member run ([IO.Path]::GetDirectoryName($Row.evidenceRoot)) -Force
            $Authority | Add-Member boundaryRendererInput $state.rendererInput -Force
            $Authority | Add-Member boundaryTimeout $Timeout -Force
        }
        $result=Test-NativeRollbackAcceptance $Row $Authority $state.witness
        if ($adapterFailure -and $result.nativePass) {
            $result.status='FAIL';$result.nativePass=$false;$result.reasons+=@('COLLECTOR_ADAPTER_FAILURE: '+$adapterFailure)
        }
        # Callback получает actual witness до finally Dispose; cleanup остаётся в исходном finally.
        $bridgeVerdict=if ($null -ne $AcceptanceObserver) {& $AcceptanceObserver $Row $Authority $state.witness $result} else {$null}
        $collected=[pscustomobject]@{acceptance=$result;bridgeVerdict=$bridgeVerdict;row=$Row;adapterFailure=$adapterFailure;collectorFailure=$state.failure;
            collectedAfterCleanupUtc=[datetime]::UtcNow.ToString('o');producer='Invoke-NativeRollbackCollectedAcceptance';scope='GUARDED_HELPER_CODE_NOT_OS_WIDE_TRACE'}
    } catch {
        # Исходная ошибка observer/acceptance/setup сохраняется, ошибки освобождения её не заменяют.
        $primaryError=$_;throw
    } finally {
        # Каждая операция восстановления независима; Dispose выполняется даже при отказе module restore.
        try {
            & $Adapter {param($Original,$Errors)
                try {
                    try {Set-Item Function:script:New-NativeRollbackHelperText $Original[0] -ErrorAction Stop} catch {$Errors.Add($_)}
                    try {Set-Item Function:script:Start-NativeRollbackOwned $Original[1] -ErrorAction Stop} catch {$Errors.Add($_)}
                    foreach ($name in 'New-NativeRollbackWriteBoundaryHelper','Open-NativeRollbackAcceptanceHandle') {
                        try {Remove-Item ('Function:script:'+$name) -ErrorAction Stop} catch {$Errors.Add($_)}
                    }
                } finally {
                    # Сначала обнуляем ссылки: даже ошибка Remove-Variable не удерживает witness/closures.
                    $script:acceptanceCollectorState=$null
                    $script:acceptanceOriginalRenderer=$null;$script:acceptanceOriginalStart=$null
                    try {Microsoft.PowerShell.Utility\Remove-Variable acceptanceCollectorState,acceptanceOriginalRenderer,acceptanceOriginalStart -Scope Script -ErrorAction Stop}
                    catch {$Errors.Add($_)}
                }
            } $original $cleanupErrors
        } catch {$cleanupErrors.Add($_)}
        finally {
            $witness=$state.witness
            # Отделяем witness от state до единственной попытки Dispose; повторного открытия PID нет.
            $state.witness=$null;$state.rendererInput=$null;$state.root=$null
            try {if ($null -ne $witness) {$witness.process.Dispose()}} catch {$cleanupErrors.Add($_)}
            finally {$witness=$null;$original=$null;$AcceptanceObserver=$null}
        }
        if ($cleanupErrors.Count) {
            if ($null -ne $primaryError) {
                # Secondary diagnostics не маскируют primary ErrorRecord и не удерживают handle/module.
                $primaryError.Exception.Data['NativeRollbackCollectorCleanupErrors']=@($cleanupErrors | ForEach-Object {$_.Exception.Message})
            } else {throw $cleanupErrors[0]}
        }
    }
    # Ошибка cleanup не выпускает успешный envelope в output pipeline.
    return $collected
}
