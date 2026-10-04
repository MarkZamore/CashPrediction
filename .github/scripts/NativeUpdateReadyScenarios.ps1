<#
.SYNOPSIS
Ограниченные ready/recovery шаги для интеграции MAIN после завершения текущего native runner.
.DESCRIPTION
Тело файла только объявляет функции. Caller создаёт независимую run-UUID копию,
проверяет исходные образы и запускает обычный lifecycle download с pinned config.
Context передаёт живой Native/Server и списки Clients/RecoveryLaunchers для cleanup caller.
Invoke-NativeReadyScenario имеет сигнатуру Invoke-NativeCell; использует MAIN nativeTarget,
nativeProject/nativeProfile и общий AST-import. Возвращает receipts, строка остаётся PENDING
до независимой acceptance MAIN; полный UpdateEvidence план не создаётся и не сужается.
Функция не создаёт Ready, журнал, installer или receipt из шаблона и не назначает PASS.
Applying без наблюдаемого живого production installer явно отвергается, без test pause.
При любом отказе caller обязан выполнить bounded cleanup своих списков и сервера.
Sampling наследует cold polling gate: это не непрерывное доказательство отсутствия UI.
#>

# Импортирует только определения, не запускает существующие runner и их native тела.
function Import-NativeReadyDependencies([string]$ScriptsRoot=$PSScriptRoot) {
    foreach ($file in 'Test-NativeUpdateLifecycle.ps1','Test-UpdateBootstrap.ps1') {
        $tokens=$null;$errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot $file),[ref]$tokens,[ref]$errors)
        if ($errors.Count) {throw 'READY_DEPENDENCY_PARSE'}
        $names=if ($file -ceq 'Test-NativeUpdateLifecycle.ps1') {
            @('Import-NativeDependencies','Assert-NativeAbsolute','Assert-NativeLifecycleConfig','Get-NativeArguments',
              'Assert-NativeEndpoint','Assert-NativeServerReceipt','Get-NativeUtcTicks','Wait-NativeCondition',
              'Close-NativeNormally','Get-NativeMainWindow','Assert-NativeMainWindow','Assert-NativeExitReceipt',
              'Start-NativeFixture','Start-NativeOwned','Stop-NativeFixture','Save-NativeOutput','Assert-NativeHttp',
              'Connect-NativeClient','Initialize-NativeDomainSession','Get-NativeDomainSessionSource',
              'Get-NativeDomainBridgeArguments','Copy-NativeDomainCore','Get-NativeUserObject','Assert-NativeSeam')
        } else {@('Assert-ColdSafeArgs','Get-ColdRecoveryObservation','Assert-ColdRecoveryEvidence','Start-ColdProcess','New-ColdHelperCapture')}
        foreach ($name in $names) {
            $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
            if ($definitions.Count -ne 1) {throw 'READY_DEPENDENCY_FUNCTION'}
            $text=$definitions[0].Extent.Text -replace ('^function '+[regex]::Escape($name)),('function global:'+$name)
            if ($name -cne 'Import-NativeDependencies') {$text=$text.Replace('$PSScriptRoot',("'"+$ScriptsRoot.Replace("'","''")+"'"))}
            . ([scriptblock]::Create($text))
        }
        if ($file -ceq 'Test-NativeUpdateLifecycle.ps1') {Import-NativeDependencies $ScriptsRoot}
    }
}

# MAIN обязан явно предоставить frozen scope; отсутствие переменных отвергается до создания копии.
function Get-NativeReadyMainScope {
    $values=@{}
    foreach ($name in 'nativeTarget','nativeProject','nativeProfile') {
        $variable=Get-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue
        if ($null -eq $variable -or $variable.Value -isnot [string] -or -not $variable.Value) {throw 'READY_MAIN_SCOPE'}
        $values[$name]=Assert-NativeAbsolute $variable.Value
    }
    return [pscustomobject]@{TargetRoot=$values.nativeTarget;ProjectRoot=$values.nativeProject;ProfileRoot=$values.nativeProfile}
}

# Opt-in наблюдатель MAIN собирает actual records до cleanup; без hook native поведение прежнее.
function Invoke-NativeReadyObservation([string]$Event,$Data) {
    $hook=Get-Variable -Name nativeReadyObservationHook -Scope Script -ErrorAction SilentlyContinue
    if ($null -eq $hook -or $null -eq $hook.Value) {return}
    if ($hook.Value -isnot [scriptblock]) {throw 'READY_OBSERVER_TYPE'}
    [void](& $hook.Value $Event $Data)
}

# Проверяет полномочия и pins до ожидания, закрытия или завершения любого клиента.
function Assert-NativeReadyContext($Context) {
    if ($PSVersionTable.PSVersion.Major -lt 7 -or -not $IsWindows) {throw 'READY_WINDOWS_POWERSHELL7'}
    Assert-ColdKeys $Context @('Run','Root','Source','Bases','TargetRoot','Evidence','Java','CommandFile','CommandSha256',
        'LifecycleFile','LifecycleSha256','Node','Client','Timeout','Native','Server','Clients','RecoveryLaunchers')
    if ($Context.Client -cnotin @('fx','swing','web') -or -not (Test-ColdInteger $Context.Timeout 30) -or $Context.Timeout -gt 300 -or
        $Context.Clients -isnot [Collections.Generic.List[object]] -or $Context.RecoveryLaunchers -isnot [Collections.Generic.List[object]] -or
        -not $Context.Clients.Contains($Context.Native) -or $Context.RecoveryLaunchers.Count) {throw 'READY_CONTEXT'}
    $run=Assert-ColdOwnedRun $Context.Run ([IO.Path]::GetTempPath().TrimEnd('\','/'))
    foreach ($path in @($Context.Root,$Context.Evidence,$Context.Source,$Context.TargetRoot,$Context.Java)+@($Context.Bases)) {[void](Assert-NativeAbsolute $path)}
    if ($Context.Root -ceq $run -or -not (Test-PortablePathContains $run $Context.Root) -or
        -not (Test-PortablePathContains $run $Context.Evidence) -or
        (Test-PortablePathContains $Context.Root $Context.Evidence) -or (Test-PortablePathContains $Context.Evidence $Context.Root) -or
        @($Context.Bases).Count -ne 2 -or $Context.Source -cnotin $Context.Bases -or
        $Context.Node -cnotmatch '^ru/cashprediction/selftest/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'READY_OWNED_CONTEXT'}
    foreach ($source in @($Context.Bases)+@($Context.TargetRoot)) {
        if ((Test-PortablePathContains $run $source) -or (Test-PortablePathContains $source $run)) {throw 'READY_SOURCE_OVERLAP'}
    }
    [void](Get-PortableRegistryPath $Context.Node)
    $cold=Read-ColdPinnedJson $Context.CommandFile $Context.CommandSha256
    Assert-ColdCommand $cold $Context.Java $Context.Bases $Context.TargetRoot
    $life=Read-ColdPinnedJson $Context.LifecycleFile $Context.LifecycleSha256;Assert-NativeLifecycleConfig $life
    if ($life.manifestSha256 -cne $cold.targetManifestSha256) {throw 'READY_TARGET_PIN'}
    $target=Read-ColdPinnedJson $cold.targetManifest $cold.targetManifestSha256
    $served=Read-ColdPinnedJson (Join-Path $life.artifactDir 'update.json') $life.manifestSha256
    if (-not (Test-ColdInventoryEqual $target $served)) {throw 'READY_TARGET_PIN'}
    $entry=@($cold.baseManifests | Where-Object {$_.portableDir -ceq $Context.Source})
    if ($entry.Count -ne 1) {throw 'READY_BASE_PIN'}
    $base=Read-ColdPinnedJson $entry[0].manifest $entry[0].sha256
    [void](Assert-ColdTree $Context.Source $base);[void](Assert-ColdTree $Context.Root $base)
    [void](Assert-ColdTree $Context.TargetRoot $target)
    if ($Context.Native.uiProcess.HasExited -or $Context.Server.process.HasExited) {throw 'READY_CONTEXT_EXITED'}
    Assert-ColdUiReceipt $Context.Native.ui $Context.Root $Context.Client
    $identity=[pscustomobject]@{ProcessId=$Context.Native.ui.pid;StartedAtTicks=$Context.Native.ui.startedAtTicks;
        ExecutablePath=$Context.Native.ui.executablePath;OwnedRoot=$Context.Root}
    Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $Context.Native.uiProcess $Context.Root) $Context.Root $identity.ExecutablePath
    if ($Context.Native.identity.ExecutablePath -cne $identity.ExecutablePath -or $Context.Native.identity.OwnedRoot -cne $Context.Root) {throw 'READY_LAUNCHER_IDENTITY'}
    if (-not (Test-ColdInventoryEqual @($Context.Native.ui.args) @(Get-NativeArguments $Context.Root $Context.Client $Context.Node))) {throw 'READY_INITIAL_ARGS'}
    Assert-ColdProcessIdentity $Context.Server.identity (Get-ColdProcessReceipt $Context.Server.process $Context.Server.owned) $Context.Server.owned $Context.Java
    Assert-NativeServerReceipt $Context.Server.receipt $Context.Server.owned $Context.Server.identity $life.manifestSha256
    if ($Context.Server.receipt.artifactDir -cne $life.artifactDir -or $Context.Server.receipt.mode -cne 'VALID' -or
        $Context.Native.process.StartInfo.Environment['JAVA_TOOL_OPTIONS'] -cnotlike ('*-Dcashprediction.update.selftest.manifest='+$Context.Server.receipt.manifestUri+' *')) {throw 'READY_SERVER_CONTEXT'}
    if (Test-Path -LiteralPath (Join-Path $Context.Root 'CashMemory/Updates/install-journal.json')) {throw 'READY_ALREADY_APPLYING'}
    return [pscustomobject]@{cold=$cold;life=$life;base=$base;target=$target}
}

# Ready подтверждается реальным манифестом и полным деревом; никаких copy/template действий.
function Wait-NativeTargetReady($Context,$Pins) {
    $path=Join-Path $Context.Root 'CashMemory/Updates/Ready/update.json'
    Wait-NativeCondition {
        if ($Context.Native.uiProcess.HasExited -or $Context.Server.process.HasExited) {throw 'READY_PREPARATION_EXITED'}
        if (-not (Test-Path -LiteralPath $path)) {return $false}
        $ready=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $path -Raw -Encoding utf8)
        if (-not (Test-ColdInventoryEqual $ready $Pins.target)) {throw 'READY_MANIFEST_IDENTITY'}
        [void](Assert-ColdTree (Join-Path $Context.Root 'CashMemory/Updates/Ready/tree') $Pins.target)
        [void](Assert-ColdTree $Context.Root $Pins.base)
        return $true
    } $Context.Timeout 'READY_PREPARATION_TIMEOUT'
    # ReadyStore повторно сериализует UpdateCodec: pin входа не требует равенства форматирования JSON.
    $manifest=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $path -Raw -Encoding utf8)
    if (-not (Test-ColdInventoryEqual $manifest $Pins.target)) {throw 'READY_MANIFEST_IDENTITY'}
    return [pscustomobject]@{observedUtc=[datetime]::UtcNow.ToString('o');manifest=$manifest;
        tree=@(Assert-ColdTree (Join-Path $Context.Root 'CashMemory/Updates/Ready/tree') $Pins.target);ui=$Context.Native.ui}
}

# Завершает только удерживаемые launcher/UI этой копии, не чужой PID и не всё дерево по имени.
function Stop-NativeReadyClient($Context) {
    $native=$Context.Native;$root=$Context.Root
    $uiIdentity=[pscustomobject]@{ProcessId=$native.ui.pid;StartedAtTicks=$native.ui.startedAtTicks;
        ExecutablePath=$native.ui.executablePath;OwnedRoot=$root}
    if ($native.uiProcess.HasExited) {throw 'READY_CRASH_CLIENT_EXITED'}
    Assert-ColdProcessIdentity $uiIdentity (Get-ColdProcessReceipt $native.uiProcess $root) $root $uiIdentity.ExecutablePath
    foreach ($entry in @(Get-CopyProcesses $root)) {
        $expected=if ($entry.ProcessId -eq $uiIdentity.ProcessId) {$uiIdentity} elseif ($entry.ProcessId -eq $native.identity.ProcessId) {$native.identity} else {throw 'READY_FOREIGN_CLIENT'}
        if ($entry.ExecutablePath -cne $expected.ExecutablePath -or
            ($entry.CreationDate.ToUniversalTime().Ticks-($entry.CreationDate.ToUniversalTime().Ticks%10)) -ne
            ($expected.StartedAtTicks-($expected.StartedAtTicks%10))) {throw 'READY_CLIENT_PID_REUSED'}
    }
    Stop-ColdRetainedProcess $native.uiProcess $uiIdentity $root
    if ($native.identity.ProcessId -ne $uiIdentity.ProcessId) {Stop-ColdRetainedProcess $native.process $native.identity $root}
    if ($native.uiProcess.ExitCode -eq 0 -or @(Get-CopyProcesses $root).Count) {throw 'READY_CRASH_NOT_PROVED'}
    return [pscustomobject]@{identity=$uiIdentity;exitCode=$native.uiProcess.ExitCode;
        exitedUtc=$native.uiProcess.ExitTime.ToUniversalTime().ToString('o');kind='abrupt-retained-client'}
}

# Удерживает дескриптор только production encoded helper этой копии и сверяет birth/command/pin.
function Get-NativeReadyInstaller($Context,[datetime]$Started,$Pins) {
    $root=$Context.Root;$path=Join-Path $root 'CashMemory/Updates/apply-update.ps1'
    $journalPath=Join-Path $root 'CashMemory/Updates/install-journal.json'
    if (-not (Test-Path -LiteralPath $journalPath)) {return $null}
    $journal=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath $journalPath -Raw -Encoding utf8)
    if ($journal.schemaVersion -ne 2 -or $journal.installationRoot -cne $root -or -not (Test-ColdInventoryEqual $journal.target $Pins.target) -or
        $journal.phase -cnotin @('PREPARED','WAITING','BOOTSTRAPPING','BACKING_UP','INSTALLING','VERIFYING')) {throw 'READY_LIVE_JOURNAL'}
    if (([guid]::Parse($journal.transactionId)).ToString() -cne $journal.transactionId) {throw 'READY_LIVE_JOURNAL'}
    if ((Get-FileHash -LiteralPath (Resolve-PortableSafetyPath $path)).Hash.ToLowerInvariant() -cne $Pins.cold.helperSha256) {throw 'READY_PRODUCTION_HELPER_PIN'}
    $powerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $command="& '"+$path.Replace("'","''")+"' -InstallationRoot '"+$root.Replace("'","''")+"'"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $matches=@(Get-CimInstance Win32_Process | Where-Object {$_.ExecutablePath -and
        $_.ExecutablePath.Equals($powerShell,[StringComparison]::OrdinalIgnoreCase) -and
        $_.CreationDate.ToUniversalTime() -ge $Started -and $_.CommandLine -match ('(?i)-EncodedCommand\s+"?'+[regex]::Escape($encoded)+'"?\s*$')})
    if (-not $matches.Count) {return $null}
    if ($matches.Count -ne 1) {throw 'READY_INSTALLER_AMBIGUOUS'}
    $process=Open-PortableProcess ([int]$matches[0].ProcessId)
    try {
        if ($process.HasExited) {$process.Dispose();return $null}
        $identity=Get-ColdProcessReceipt $process $root
        if (($identity.StartedAtTicks-($identity.StartedAtTicks%10)) -ne
            ($matches[0].CreationDate.ToUniversalTime().Ticks-($matches[0].CreationDate.ToUniversalTime().Ticks%10))) {throw 'READY_INSTALLER_PID_REUSED'}
        [void](Get-ColdCurrentProcess $matches[0] $process $powerShell)
        Assert-ColdHelperCommand $matches[0].CommandLine $powerShell $encoded
        return [pscustomobject]@{process=$process;identity=$identity;journal=$journal;commandLine=$matches[0].CommandLine;observedUtc=[datetime]::UtcNow.ToString('o')}
    } catch {$process.Dispose();throw}
}

# Запускает прежний exe и возвращает только фактически наблюдённые recovery/target receipts.
function Invoke-NativeReadyContextScenario($Context,[string]$Scenario) {
    if ($Scenario -cnotin @('abrupt-ready-restart','launch-applying-safe-args')) {throw 'READY_SCENARIO'}
    $pins=Assert-NativeReadyContext $Context
    $ready=Wait-NativeTargetReady $Context $pins
    Write-ColdJson (Join-Path $Context.Evidence 'ready-observed.json') $ready
    Invoke-NativeReadyObservation 'READY' ([pscustomobject]@{context=$Context;target=$pins.target})
    # Прогрев выполняется пока Ready client жив: не добавляет задержку в Applying race и не является sample.
    # Каждый настоящий observer по-прежнему читает свежий TCP snapshot; 1000 ms gate не ослабляется.
    [void](Get-NetTCPConnection -State Listen -ErrorAction Stop)
    $started=[datetime]::UtcNow;$installer=$null;$retained=$null;$launchAt=$null;$installerAnnounced=$false
    $samples=[Collections.Generic.List[object]]::new()
    $args=@('--home',$Context.Root)
    if ($Context.Client -ceq 'web') {$args+=@('--no-browser','--no-window')}
    try {
        $exit=if ($Scenario -ceq 'abrupt-ready-restart') {Stop-NativeReadyClient $Context} else {
            Close-NativeNormally $Context.Native $Context.Root $Context.Client $Context.Timeout $Context.Evidence
        }
        Write-ColdJson (Join-Path $Context.Evidence 'ready-client-exit.json') $exit
        if ($Scenario -ceq 'launch-applying-safe-args') {
            # Окно гонки не расширяется фальшивой lease, паузой helper или написанным вручную журналом.
            $box=[pscustomobject]@{installer=$null}
            Wait-NativeCondition {
                $box.installer=Get-NativeReadyInstaller $Context $started $pins
                return $null -ne $box.installer
            } $Context.Timeout 'READY_LIVE_INSTALLER_NOT_OBSERVED'
            $installer=$box.installer
            Invoke-NativeReadyObservation 'INSTALLER' ([pscustomobject]@{root=$Context.Root;installer=$installer;target=$pins.target})
            $installerAnnounced=$true
            Write-ColdJson (Join-Path $Context.Evidence 'applying-installer.json') ([ordered]@{identity=$installer.identity;
                commandLine=$installer.commandLine;journal=$installer.journal;observedUtc=$installer.observedUtc})
        }
        $first=Get-ColdRecoveryObservation $Context.Root $started;$samples.Add($first)
        Invoke-NativeReadyObservation 'PRESTART' ([pscustomobject]@{root=$Context.Root;sample=$first})
        $controlled=$first.controlledSha256
        if ($Scenario -ceq 'launch-applying-safe-args' -and (-not $first.journalBefore -or -not $first.journalAfter -or $installer.process.HasExited)) {throw 'READY_APPLYING_WINDOW_MISSED'}
        $launchAt=[datetime]::UtcNow
        $launcher=Start-ColdProcess (Join-Path $Context.Root (Get-ColdLauncherName $Context.Client)) $args $Context.Root $Context.Node
        # Сначала регистрация дескриптора для caller cleanup, затем получение identity, которое тоже может отказать.
        $retained=[pscustomobject]@{process=$launcher;identity=$null;root=$Context.Root}
        $Context.RecoveryLaunchers.Add($retained)
        $retained.identity=Get-ColdProcessReceipt $launcher $Context.Root
        Invoke-NativeReadyObservation 'LAUNCHER' ([pscustomobject]@{root=$Context.Root;process=$launcher})
        $clock=[Diagnostics.Stopwatch]::StartNew();$ui=$null
        do {
            $sample=Get-ColdRecoveryObservation $Context.Root $launchAt;$samples.Add($sample)
            Invoke-NativeReadyObservation 'SAMPLE' $sample
            if ($sample.journalBefore -and $sample.journalAfter -and $sample.controlledSha256 -cne $controlled) {throw 'READY_CONTROLLED_CHANGED_EARLY'}
            if ($null -eq $installer) {$installer=Get-NativeReadyInstaller $Context $started $pins}
            if ($null -ne $installer -and -not $installerAnnounced) {
                Invoke-NativeReadyObservation 'INSTALLER' ([pscustomobject]@{root=$Context.Root;installer=$installer;target=$pins.target})
                $installerAnnounced=$true
            }
            if (-not $sample.journalAfter) {$ui=Get-ColdUiReceipt $Context.Root $Context.Client $launchAt}
            if ($null -ne $ui) {break}
            Start-Sleep -Milliseconds 100
        } while ($clock.Elapsed.TotalSeconds -lt $Context.Timeout)
        $observations=[pscustomobject]@{schemaVersion=1;installationRoot=$Context.Root;
            transactionId=$(if ($null -ne $installer) {$installer.journal.transactionId} else {''});
            pollingLimitMillis=1000;controlledSha256=$controlled;samples=@($samples.ToArray())}
        Write-ColdJson (Join-Path $Context.Evidence 'ready-recovery-unvalidated.json') $observations
        if ($null -eq $installer -or $null -eq $ui) {throw 'READY_NATIVE_RECOVERY_NOT_OBSERVED'}
        Invoke-NativeReadyObservation 'RESTARTED' ([pscustomobject]@{root=$Context.Root;client=$Context.Client;launchAt=$launchAt})
        # Actual receipts сохраняются также при последующем отказе safe args/tree/exit, без acceptance.
        Write-ColdJson (Join-Path $Context.Evidence 'ready-launch-unvalidated.json') ([ordered]@{launcher=$retained.identity;
            originalArgs=$args;launchAt=$launchAt.ToString('o');ui=$ui;installer=$installer.identity})
        Assert-ColdUiReceipt $ui $Context.Root $Context.Client
        Assert-ColdSafeArgs $args $ui.args $Context.Root $Context.Client $pins.target.commitSha
        Assert-ColdRecoveryEvidence $observations $Context.Root $installer.journal.transactionId (Get-ColdUtcTicks $first.startedAt) $launchAt.Ticks (Get-ColdUtcTicks $ui.observedAt)
        [void](Assert-ColdTree $Context.Root $pins.target)
        $version=Get-ColdVersion $Context.Root
        if ($version.releaseNumber -ne $pins.target.releaseNumber -or $version.commitSha -cne $pins.target.commitSha) {throw 'READY_RECOVERED_VERSION'}
        $verify=Invoke-ColdTool $pins.cold $Context.Java @('verify','--root',$Context.Root,'--manifest',$pins.cold.targetManifest) $Context.Evidence $Context.Root
        if (-not $installer.process.WaitForExit(5000) -or $installer.process.ExitCode -ne 0) {throw 'READY_INSTALLER_EXIT'}
        if (-not $launcher.WaitForExit(5000) -or $launcher.ExitCode -ne 0) {throw 'READY_HANDOFF_EXIT'}
        $last=ConvertFrom-ColdReceiptJson (Get-Content -LiteralPath (Join-Path $Context.Root 'CashMemory/Updates/last-install.json') -Raw -Encoding utf8)
        if ($last.outcome -cne 'UPDATED' -or $last.targetCommitSha -cne $pins.target.commitSha -or
            $last.transactionId -cne $installer.journal.transactionId) {throw 'READY_INSTALL_OUTCOME'}
        foreach ($name in 'install-journal.json','completed-journal.json') {
            if (Test-Path -LiteralPath (Join-Path $Context.Root ('CashMemory/Updates/'+$name))) {throw 'READY_JOURNAL_NOT_CLEARED'}
        }
        $receipt=[pscustomobject]@{scenario=$Scenario;ready=$ready;clientExit=$exit;launcher=$retained.identity;
            originalArgs=$args;launchAt=$launchAt.ToString('o');ui=$ui;recovery=$observations;version=$version;verify=$verify;
            launcherExit=$launcher.ExitCode;installer=$installer.identity;installerExit=$installer.process.ExitCode;lastInstall=$last;
            server=$Context.Server.receipt;serverIdentity=$Context.Server.identity;observedUtc=[datetime]::UtcNow.ToString('o')}
        Write-ColdJson (Join-Path $Context.Evidence 'ready-recovery-observed.json') $receipt
        return $receipt
    } catch {
        # Сохраняются только уже наблюдённые данные; пустые samples явно показывают пропущенную границу.
        # Такой файл не заменяет Assert-ColdRecoveryEvidence и никогда не является acceptance.
        $rejection=$_.Exception.Message
        try {
            Write-ColdJson (Join-Path $Context.Evidence 'ready-recovery-rejected.json') ([ordered]@{failure=$rejection;
                samples=@($samples.ToArray());launcher=$(if ($null -ne $retained) {$retained.identity} else {$null});
                launchAt=$(if ($null -ne $launchAt) {$launchAt.ToString('o')} else {$null});
                installer=$(if ($null -ne $installer) {$installer.identity} else {$null})})
        } catch {throw ('READY_REJECTION_RECEIPT_FAILED original='+$rejection+' receipt='+$_.Exception.Message)}
        throw
    } finally {if ($null -ne $installer) {$installer.process.Dispose()}}
}

# Адаптер к Invoke-NativeCell: собственная копия, реальные baselines и cleanup, без изменения полного плана.
function Invoke-NativeReadyScenario($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,[string]$Evidence,[int]$Timeout) {
    if ($Row.scenario -cnotin @('abrupt-ready-restart','launch-applying-safe-args') -or $Row.client -cnotin @('fx','swing','web') -or
        $Row.path -cnotin @('ascii','cyrillic','unicode') -or $Row.base -cnotin @('B1','B2') -or $Row.phase -cne 'SESSION' -or
        $Timeout -lt 30 -or $Timeout -gt 300) {throw 'READY_ROW_CONTEXT'}
    $bases=@($Cold.baseManifests | ForEach-Object {$_.portableDir})
    if ($bases.Count -ne 2 -or $Source -cne $bases[$(if ($Row.base -ceq 'B1') {0} else {1})]) {throw 'READY_ROW_BASE'}
    $mainScope=Get-NativeReadyMainScope
    Assert-ColdCommand $Cold $Java $bases $mainScope.TargetRoot;Assert-NativeLifecycleConfig $Life
    if ($Life.manifestSha256 -cne $Cold.targetManifestSha256) {throw 'READY_TARGET_PIN'}
    [void](Assert-ColdTree $Source $Base);[void](Assert-ColdTree $mainScope.TargetRoot $Target)
    Assert-ColdNativeImage $Source;Assert-NativeSeam $Source
    $entry=@($Cold.baseManifests | Where-Object {$_.portableDir -ceq $Source})[0]
    if (-not (Test-ColdInventoryEqual $Base (Read-ColdPinnedJson $entry.manifest $entry.sha256)) -or
        -not (Test-ColdInventoryEqual $Target (Read-ColdPinnedJson $Cold.targetManifest $Cold.targetManifestSha256))) {throw 'READY_ROW_MANIFEST'}
    $run=Join-Path ([IO.Path]::GetTempPath()) ('run-'+[guid]::NewGuid().ToString())
    [void](Assert-ColdOwnedRun $run ([IO.Path]::GetTempPath()))
    foreach ($protected in @($Source,$mainScope.TargetRoot,$Life.artifactDir)) {
        [void](Get-ValidatedPortablePaths $protected $run $mainScope.ProjectRoot $mainScope.ProfileRoot)
    }
    [void](Assert-NativeAbsolute $Evidence)
    $variants=@{ascii='plain';cyrillic='Мои программы';unicode='Δ 测试'}
    $root=Join-Path (Join-Path $run $variants[$Row.path]) 'CashPrediction'
    $cellEvidence=Join-Path $run 'evidence'
    $Row | Add-Member workRoot $root -Force
    $Row | Add-Member evidenceDirectory $cellEvidence -Force
    $clients=[Collections.Generic.List[object]]::new();$launchers=[Collections.Generic.List[object]]::new()
    $server=$null;$receipt=$null;$failure=$null;$cleanup=[Collections.Generic.List[string]]::new()
    $savedScope=@{}
    foreach ($name in 'WorkDir','nativeNode','nativeUi') {
        $variable=Get-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue
        $savedScope[$name]=[pscustomobject]@{exists=($null -ne $variable);value=$(if ($null -ne $variable) {$variable.Value} else {$null})}
    }
    try {
    $started=[datetime]::UtcNow;$script:WorkDir=$run;$script:nativeNode='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString()
    try {
        $null=New-Item -ItemType Directory -Path $cellEvidence
        $null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($root))
        Copy-Item -LiteralPath $Source -Destination $root -Recurse
        $null=New-Item -ItemType Directory -Path (Join-Path $root 'CashMemory/Updates')
        Write-ColdJson (Join-Path $root 'CashMemory/protected-user.json') ([ordered]@{sentinel='ready-user'})
        Write-ColdJson (Join-Path $root 'protected-root.json') ([ordered]@{sentinel='ready-root'})
        $domain=Initialize-NativeDomainSession $root $Java $Cold $cellEvidence
        $Row | Add-Member domainEvidence $domain -Force
        $before=@(Assert-ColdTree $root $Base);$targetBefore=@(Assert-ColdTree $mainScope.TargetRoot $Target)
        $userBefore=Get-NativeUserObject $root;$controlled=@(Get-ColdControlledInventory $root)
        Invoke-NativeReadyObservation 'BASELINES' ([pscustomobject]@{root=$root;targetRoot=$mainScope.TargetRoot;client=$Row.client;scenario=$Row.scenario})
        foreach ($name in 'Ready','install-journal.json','completed-journal.json') {
            if (Test-Path -LiteralPath (Join-Path $root ('CashMemory/Updates/'+$name))) {throw 'READY_PREEXISTING_PUBLICATION'}
        }
        # Копии уже проверенных config закрепляются в собственном evidence, не меняя исходные pins/classpath.
        $commandPath=Join-Path $cellEvidence 'command-config.json';Write-ColdJson $commandPath $Cold
        $lifePath=Join-Path $cellEvidence 'lifecycle-config.json';Write-ColdJson $lifePath $Life
        $server=Start-NativeFixture $Life $Java 'valid' 0
        $args=@(Get-NativeArguments $root $Row.client $script:nativeNode);$launchAt=[datetime]::UtcNow
        $native=Start-NativeOwned (Join-Path $root (Get-ColdLauncherName $Row.client)) $args $root $server.receipt.manifestUri -Web:($Row.client -ceq 'web')
        $clients.Add($native)
        Connect-NativeClient $native $root $Row.client $launchAt $Timeout
        $context=[pscustomobject]@{Run=$run;Root=$root;Source=$Source;Bases=$bases;TargetRoot=$mainScope.TargetRoot;Evidence=$cellEvidence;
            Java=$Java;CommandFile=$commandPath;CommandSha256=(Get-FileHash -LiteralPath $commandPath).Hash.ToLowerInvariant();
            LifecycleFile=$lifePath;LifecycleSha256=(Get-FileHash -LiteralPath $lifePath).Hash.ToLowerInvariant();
            Node=$script:nativeNode;Client=$Row.client;Timeout=$Timeout;Native=$native;Server=$server;Clients=$clients;RecoveryLaunchers=$launchers}
        $receipt=Invoke-NativeReadyContextScenario $context $Row.scenario
        $http=Stop-NativeFixture $server $cellEvidence 'ready-server'
        $userAfter=Get-NativeUserObject $root
        if (-not (Test-ColdInventoryEqual $userBefore $userAfter)) {throw 'READY_USER_CHANGED'}
        Assert-ColdControlledChanges $controlled @(Get-ColdControlledInventory $root) $Row.client $receipt.ui $root ([datetime]::UtcNow.Ticks)
        $nativeLog=Join-Path $root 'CashMemory/Updates/update-log.md';$phases=@('SESSION')
        foreach ($line in Get-Content -LiteralPath $nativeLog -Encoding utf8) {if ($line -cmatch ' PHASE_([A-Z_]+)$') {$phases+=@($Matches[1])}}
        if ($phases -cnotcontains 'COMMITTED') {throw 'READY_COMMIT_LOG_MISSING'}
        Copy-Item -LiteralPath $nativeLog -Destination (Join-Path $cellEvidence 'update-log.md')
        Invoke-NativeReadyObservation 'CELL_OBSERVED' ([pscustomobject]@{root=$root;targetRoot=$mainScope.TargetRoot})
        $values=@{currentBefore=$before;currentAfter=@(Assert-ColdTree $root $Target);targetBefore=$targetBefore;
            targetAfter=@(Assert-ColdTree $mainScope.TargetRoot $Target);userBefore=$userBefore;userAfter=$userAfter;
            httpTrace=@($http.events | Where-Object {$_.event -ceq 'FINISH'});phaseLog=$phases}
        foreach ($name in $values.Keys) {
            $path=Join-Path $cellEvidence ($name+'.json');Write-ColdJson $path $values[$name];$Row | Add-Member $name $path -Force
        }
        $fields=@{exe=(Join-Path $root (Get-ColdLauncherName $Row.client));args=$receipt.originalArgs;executed=$true;
            baseRelease=$Base.releaseNumber;baseCommit=$Base.commitSha;targetRelease=$Target.releaseNumber;targetCommit=$Target.commitSha;
            command=(Join-Path $cellEvidence 'ready-recovery-observed.json');startedAt=$started.ToString('o');
            finishedAt=[datetime]::UtcNow.ToString('o');exitCode=$receipt.installerExit;skipped=0;failures=0;workRoot=$root}
        foreach ($name in $fields.Keys) {$Row | Add-Member $name $fields[$name] -Force}
    } catch {$failure=$_.Exception.Message}
    finally {
        # Ничего не удаляется: evidence/копия сохраняются; cleanup ошибки не превращаются в успех.
        if ($null -ne $server) {
            $Row | Add-Member serverDirectories @($server.owned) -Force
            try {if (-not $server.process.HasExited) {[void](Stop-NativeFixture $server $cellEvidence 'cleanup-server')}} catch {$cleanup.Add($_.Exception.Message)}
            try {if (-not $server.process.HasExited) {Stop-ColdRetainedProcess $server.process $server.identity $server.owned}} catch {$cleanup.Add($_.Exception.Message)}
            $server.process.Dispose()
        }
        try {Stop-ColdRecoveryHelpers $root (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe') $started} catch {$cleanup.Add($_.Exception.Message)}
        try {Stop-ColdCopyProcesses $root} catch {$cleanup.Add($_.Exception.Message)}
        foreach ($launcher in $launchers) {
            # Root cleanup мог отказать; удерживаемый launcher всё равно получает отдельную identity-проверку.
            try {
                if (-not $launcher.process.HasExited) {
                    if ($null -eq $launcher.identity) {$launcher.identity=Get-ColdProcessReceipt $launcher.process $root}
                    Stop-ColdRetainedProcess $launcher.process $launcher.identity $root
                }
            } catch {$cleanup.Add($_.Exception.Message)}
            $launcher.process.Dispose()
        }
        $clientIndex=0
        foreach ($native in $clients) {
            try {if ($native.process.HasExited) {Save-NativeOutput $native $cellEvidence ('ready-client-'+$clientIndex)}} catch {$cleanup.Add($_.Exception.Message)}
            if ($null -ne $native.uiProcess) {$native.uiProcess.Dispose()};$native.process.Dispose();$clientIndex++
        }
        if (-not $cleanup.Count) {
            try {$nodePath=Get-PortableRegistryPath $script:nativeNode;if (Test-Path -LiteralPath $nodePath) {Remove-Item -LiteralPath $nodePath -Recurse -Force}} catch {$cleanup.Add($_.Exception.Message)}
        }
        $Row.status=if ($failure -or $cleanup.Count) {'FAIL'} else {'PENDING'}
        $Row.reason=if ($failure) {$failure} elseif ($cleanup.Count) {$cleanup -join '; '} else {'NATIVE_READY_RECEIPTS_OBSERVED_ACCEPTANCE_PENDING'}
        if (Test-Path -LiteralPath $cellEvidence) {Write-ColdJson (Join-Path $cellEvidence 'cell.json') $Row}
    }
    if ($failure -or $cleanup.Count) {throw $Row.reason}
    return $receipt
    } finally {
        foreach ($name in $savedScope.Keys) {
            if ($savedScope[$name].exists) {Set-Variable -Name $name -Scope Script -Value $savedScope[$name].value}
            else {Remove-Variable -Name $name -Scope Script -ErrorAction SilentlyContinue}
        }
    }
}
