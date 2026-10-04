<#
.SYNOPSIS
Реальный opt-in наблюдатель двух Ready cells, без автоматического native PASS.
.DESCRIPTION
MAIN импортирует определения этого файла через AST, вызывает Import-NativeReadyCollectorDependencies.
Точная точка интеграции: NativeUpdateScenarioDispatch.ps1, Invoke-NativeScenarioDispatch,
ветка 'ready' в $helperReceipts: вместо Invoke-NativeReadyScenario вызывает
Invoke-NativeReadyAcceptedCell с теми же девятью аргументами. Dispatcher owner должен добавить
collector/acceptor в frozen imports/pins и guard сигнатуры; существующий dispatcher здесь не изменён.
Возвращаются receipt, decision и independentFile; остальные ветки/полный план не меняются.
Row остаётся PENDING. Только caller после остальных gates и успешного cleanup может принять
cellEvidenceValidated=true, scope=READY_CELL_ONLY как разрешение per-cell PASS, не всей матрицы.
Наблюдения снимаются до cleanup: retained handles, свежий UI witness, файловые inventories,
production journal/CIM и update-log. Recovery использует реальные samples общего cold reader,
не клонирует helper return и не доказывает непрерывную невидимость/foreground/kernel состояние.
Дополнительное чтение может не уложиться в существующий 1000 ms gate: отказ остаётся отказом.
Импорт ничего не запускает. Native запуск разрешён только MAIN после завершения его live runner.
#>

# Импортирует только определения собственных helpers и их действительные зависимости.
function Import-NativeReadyCollectorDependencies([string]$ScriptsRoot=$PSScriptRoot) {
    foreach ($file in 'NativeUpdateReadyScenarios.ps1','NativeUpdateReadyAcceptance.ps1') {
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot $file),[ref]$tokens,[ref]$errors)
        if ($errors.Count) {throw 'READY_COLLECTOR_IMPORT_PARSE'}
        foreach ($def in $ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)) {
            $text=$def.Extent.Text -replace ('^function '+[regex]::Escape($def.Name)),('function global:'+$def.Name)
            . ([scriptblock]::Create($text))
        }
    }
    Import-NativeReadyDependencies $ScriptsRoot
    Import-NativeReadyAcceptanceDependencies $ScriptsRoot
}

# Отделяет наблюдённый snapshot от изменяемого объекта caller, сохраняя wire UTC строки.
function Copy-NativeReadyObservation($Value) {
    return (ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64 -Compress))
}

# Снимает actual поля в доступные моменты lifecycle; неизвестный/повторный event отвергается.
function Add-NativeReadyCollectedObservation($State,[string]$Event,$Data) {
    if ($Event -cnotin @('BASELINES','READY','PRESTART','LAUNCHER','INSTALLER','SAMPLE','RESTARTED','CELL_OBSERVED')) {throw 'READY_COLLECTOR_EVENT'}
    if ($Event -cne 'SAMPLE' -and $State.events.Contains($Event)) {throw 'READY_COLLECTOR_DUPLICATE'}
    if ($Event -cne 'BASELINES' -and -not $State.events.Contains('BASELINES')) {throw 'READY_COLLECTOR_ORDER'}
    $p=$State.proof
    switch -CaseSensitive ($Event) {
        'BASELINES' {
            $p.installationRoot=$Data.root;$p.client=$Data.client;$p.scenario=$Data.scenario
            $State.targetRoot=$Data.targetRoot
            $p.currentBefore=@(Get-ColdManagedInventory $Data.root)
            $p.targetBefore=@(Get-ColdManagedInventory $Data.targetRoot)
            $p.userBefore=Get-NativeUserObject $Data.root
            $p.controlledBefore=@(Get-ColdControlledInventory $Data.root)
        }
        'READY' {
            $context=$Data.context
            $ui=Get-ColdUiReceipt $p.installationRoot $p.client $context.Native.uiProcess.StartTime.ToUniversalTime()
            Assert-ColdUiReceipt $ui $p.installationRoot $p.client
            Assert-ColdProcessIdentity (Get-ColdProcessReceipt $context.Native.uiProcess $p.installationRoot) (Get-NativeReadyAcceptanceUiIdentity $ui $p.installationRoot) $p.installationRoot $ui.executablePath
            $p.initialClient=Copy-NativeReadyObservation $ui
            $p.readyTree=@(Assert-ColdTree (Join-Path $p.installationRoot 'CashMemory/Updates/Ready/tree') $State.target)
            $p.controlledAtReady=@(Get-ColdControlledInventory $p.installationRoot)
            $p.readyObservedAt=[datetime]::UtcNow.ToString('o')
        }
        'PRESTART' {
            if (-not $State.events.Contains('READY')) {throw 'READY_COLLECTOR_ORDER'}
            $p.controlledAtPrestart=@(Get-ColdControlledInventory $p.installationRoot)
            $p.recovery.controlledSha256=Get-ColdObjectHash $p.controlledAtPrestart
            if ($p.recovery.controlledSha256 -cne $Data.sample.controlledSha256) {throw 'READY_COLLECTOR_PRESTART_CHANGED'}
            $State.samples.Add((Copy-NativeReadyObservation $Data.sample))
        }
        'LAUNCHER' {
            if (-not $State.events.Contains('PRESTART')) {throw 'READY_COLLECTOR_ORDER'}
            $p.launcherIdentity=Get-ColdProcessReceipt $Data.process $p.installationRoot
            $p.launcherArguments=@($Data.process.StartInfo.ArgumentList)
            $p.launcherObservedAt=[datetime]::UtcNow.ToString('o')
        }
        'INSTALLER' {
            if (-not $State.events.Contains('READY')) {throw 'READY_COLLECTOR_ORDER'}
            $p.installerIdentity=Get-ColdProcessReceipt $Data.installer.process $p.installationRoot
            $live=@(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq $p.installerIdentity.ProcessId})
            if ($live.Count -ne 1 -or $Data.installer.process.HasExited) {throw 'READY_COLLECTOR_INSTALLER_NOT_LIVE'}
            $current=Get-ColdCurrentProcess $live[0] $Data.installer.process $p.installerIdentity.ExecutablePath
            $p.installerCommandLine=$current.CommandLine
            $journal=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $p.installationRoot 'CashMemory/Updates/install-journal.json') -Raw -Encoding utf8)
            if ($journal.installationRoot -cne $p.installationRoot -or -not (Test-ColdInventoryEqual $journal.target $State.target) -or
                $journal.transactionId -cne $Data.installer.journal.transactionId) {throw 'READY_COLLECTOR_JOURNAL'}
            $p.recovery.transactionId=$journal.transactionId
            $p.installerObservedAt=[datetime]::UtcNow.ToString('o')
        }
        'SAMPLE' {
            if (-not $State.events.Contains('LAUNCHER')) {throw 'READY_COLLECTOR_ORDER'}
            $State.samples.Add((Copy-NativeReadyObservation $Data))
        }
        'RESTARTED' {
            if (-not $State.events.Contains('INSTALLER') -or -not $State.events.Contains('LAUNCHER')) {throw 'READY_COLLECTOR_ORDER'}
            $ui=Get-ColdUiReceipt $p.installationRoot $p.client $Data.launchAt
            Assert-ColdUiReceipt $ui $p.installationRoot $p.client
            $retained=Open-PortableProcess $ui.pid
            try {Assert-ColdProcessIdentity (Get-ColdProcessReceipt $retained $p.installationRoot) (Get-NativeReadyAcceptanceUiIdentity $ui $p.installationRoot) $p.installationRoot $ui.executablePath}
            finally {$retained.Dispose()}
            $p.restartedClient=Copy-NativeReadyObservation $ui
        }
        'CELL_OBSERVED' {
            if (-not $State.events.Contains('RESTARTED')) {throw 'READY_COLLECTOR_ORDER'}
            $p.currentAfter=@(Get-ColdManagedInventory $p.installationRoot)
            $p.targetAfter=@(Get-ColdManagedInventory $State.targetRoot)
            $p.userAfter=Get-NativeUserObject $p.installationRoot
            $p.controlledAfter=@(Get-ColdControlledInventory $p.installationRoot)
            $p.journalsCleared=$true
            foreach ($name in 'install-journal.json','completed-journal.json') {
                if (Test-Path -LiteralPath (Join-Path $p.installationRoot ('CashMemory/Updates/'+$name))) {$p.journalsCleared=$false}
            }
            # SESSION - проверенная ветка row, остальные phases читаются только из production log.
            $p.phaseLog=@('SESSION')
            foreach ($line in Get-Content -LiteralPath (Join-Path $p.installationRoot 'CashMemory/Updates/update-log.md') -Encoding utf8) {
                if ($line -cmatch ' PHASE_([A-Z_]+)$') {$p.phaseLog+=@($Matches[1])}
            }
        }
    }
    [void]$State.events.Add($Event)
}

# Cleanup census только читает процессы; не завершает чужие PID и не считает mocks native PASS.
function Complete-NativeReadyCollectedObservation($State,$Row) {
    foreach ($event in 'BASELINES','READY','PRESTART','LAUNCHER','INSTALLER','SAMPLE','RESTARTED','CELL_OBSERVED') {
        if (-not $State.events.Contains($event)) {throw ('READY_COLLECTOR_MISSING_'+$event)}
    }
    if ($Row.status -cne 'PENDING' -or $Row.reason -cne 'NATIVE_READY_RECEIPTS_OBSERVED_ACCEPTANCE_PENDING') {throw 'READY_COLLECTOR_HELPER_FAILED'}
    $p=$State.proof;$p.recovery.installationRoot=$p.installationRoot;$p.recovery.samples=@($State.samples.ToArray())
    $exe=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $helper=Join-Path $p.installationRoot 'CashMemory/Updates/apply-update.ps1'
    $cmd="& '"+$helper.Replace("'","''")+"' -InstallationRoot '"+$p.installationRoot.Replace("'","''")+"'"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
    # Любой живой helper с точной командой собственной копии мешает acceptance, включая PID reuse.
    $remaining=@(Get-CimInstance Win32_Process | Where-Object {
        if ($_.ExecutablePath -cne $exe) {return $false}
        try {Assert-ColdHelperCommand $_.CommandLine $exe $encoded;return $true} catch {return $false}
    }).Count
    $p.cleanup=[pscustomobject]@{finishedAt=[datetime]::UtcNow.ToString('o');errors=@();
        remainingClients=@(Get-CopyProcesses $p.installationRoot).Count;remainingHelpers=$remaining;
        registryUnchanged=((Get-PortableRealRegistrySnapshot) -ceq $State.registryBefore)}
    return [pscustomobject]$p
}

# Девять параметров Invoke-NativeCell; временный hook всегда восстанавливается, Row не повышается.
function Invoke-NativeReadyAcceptedCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    $old=Get-Variable nativeReadyObservationHook -Scope Script -ErrorAction SilentlyContinue
    if ($null -ne $old -and $null -ne $old.Value) {throw 'READY_COLLECTOR_HOOK_BUSY'}
    $state=[pscustomobject]@{proof=[ordered]@{schemaVersion=1;recovery=[ordered]@{schemaVersion=1;pollingLimitMillis=1000}};
        events=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal);
        samples=[Collections.Generic.List[object]]::new();target=$Target;targetRoot=$null;
        registryBefore=(Get-PortableRealRegistrySnapshot)}
    $hook={param($event,$data) Add-NativeReadyCollectedObservation $state $event $data}.GetNewClosure()
    try {
        $script:nativeReadyObservationHook=$hook
        $receipt=Invoke-NativeReadyScenario $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
        $proof=Complete-NativeReadyCollectedObservation $state $Row
        $file=Join-Path $Row.evidenceDirectory 'main-independent-ready.json'
        Write-ColdJson $file $proof
        $receiptPin=(Get-FileHash -LiteralPath $Row.command).Hash.ToLowerInvariant()
        $proofPin=(Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant()
        $decision=Test-NativeReadyAcceptance $Row $receipt $Base $Target $receiptPin $file $proofPin
        return [pscustomobject]@{receipt=$receipt;decision=$decision;independentFile=$file;independentSha256=$proofPin;receiptSha256=$receiptPin}
    } catch {
        $Row.status='FAIL';$Row.reason='READY_COLLECTOR: '+$_.Exception.Message
        throw
    } finally {
        if ($null -ne $old) {$script:nativeReadyObservationHook=$old.Value}
        else {Remove-Variable nativeReadyObservationHook -Scope Script -ErrorAction SilentlyContinue}
    }
}
