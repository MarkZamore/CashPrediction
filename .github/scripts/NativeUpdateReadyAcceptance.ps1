<#
.SYNOPSIS
Отдельная ограниченная acceptance проверка Ready/prestart native receipts.
.DESCRIPTION
Тело файла объявляет функции; не запускает процессы, GUI, сеть или сборки и не пишет файлы.
MAIN вызывает Import-NativeReadyAcceptanceDependencies, затем Test-NativeReadyAcceptance
с Row, Receipt, Base, Target, ReceiptSha256, IndependentFile, IndependentSha256.
IndependentFile - отдельный SHA-pinned JSON независимого наблюдателя MAIN, не helper return.
Он содержит actual identity/witness records, снятые до cleanup, inventories до/после,
Ready tree до exit, независимые recovery samples и cleanup census после завершения.
PIN фиксирует bytes, но не доказывает происхождение: MAIN обязан собирать observations
настоящими census/retained-handle/filesystem readers, а не копировать helper receipt.
Успех возвращает cellEvidenceValidated=true со status=PENDING; Row не изменяется.
Только MAIN решает cell acceptance после остальных gates; полного native signoff здесь нет.
Caller может явно присвоить Row.status='PASS', когда decision.cellEvidenceValidated -eq $true,
decision.scope -ceq 'READY_CELL_ONLY', все остальные per-cell gates прошли и cleanup успешен.
decision.status нельзя использовать как итог всей матрицы: aggregate требует все 612 строк.
Если frozen adapter уже сделал cleanup без независимых наблюдений, восстановить их нельзя:
helper return и его artifacts сами по себе остаются PENDING.
#>

# Импортирует только pure validators и path safety, никогда не тела существующих runner.
function Import-NativeReadyAcceptanceDependencies([string]$ScriptsRoot=$PSScriptRoot) {
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $ScriptsRoot 'Test-UpdateBootstrap.ps1'),[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'READY_ACCEPTANCE_DEPENDENCY_PARSE'}
    foreach ($name in 'Assert-ColdKeys','Test-ColdInteger','Get-ColdLauncherName','Get-ColdUtcTicks','ConvertFrom-ColdReceiptJson',
        'ConvertFrom-ColdCommandLine','Test-ColdInventoryEqual','Assert-ColdProcessIdentity','Assert-ColdUiReceipt','Assert-ColdSafeArgs',
        'Assert-ColdInventory','Get-ColdTreeHash','Assert-ColdRecoveryEvidence','Assert-ColdControlledChanges','Get-ColdSessionWords',
        'Assert-ColdHelperCommand','Get-ColdObjectHash','Assert-ColdTree','Get-ColdManagedInventory','Get-ColdUserInventory','Import-ColdPortableSafety') {
        $definitions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($definitions.Count -ne 1) {throw ('READY_ACCEPTANCE_DEPENDENCY '+$name)}
        $text=$definitions[0].Extent.Text -replace ('^function '+[regex]::Escape($name)),('function global:'+$name)
        $text=$text.Replace('$PSScriptRoot',("'"+$ScriptsRoot.Replace("'","''")+"'"))
        . ([scriptblock]::Create($text))
    }
    Import-ColdPortableSafety (Join-Path $ScriptsRoot 'Test-Portable.ps1')
}

# Читает закреплённые bytes один раз, с path/link/size guard и сохранением UTC строк.
function Read-NativeReadyAcceptanceJson([string]$Path,[string]$Sha256) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Sha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'READY_ACCEPTANCE_PIN'}
    $resolved=Resolve-PortableSafetyPath $Path
    $item=Get-Item -LiteralPath $resolved -Force
    if ($item.PSIsContainer -or $item.Length -lt 2 -or $item.Length -gt 8388608) {throw 'READY_ACCEPTANCE_FILE'}
    $bytes=[IO.File]::ReadAllBytes($resolved)
    if ($bytes.Length -gt 8388608 -or [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -cne $Sha256) {throw 'READY_ACCEPTANCE_PIN'}
    return (ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString($bytes)))
}

# Полные managed inventories обязаны совпасть с pinned манифестом, не просто OLD-or-NEW.
function Assert-NativeReadyAcceptanceTree($Files,$Manifest) {
    Assert-ColdInventory $Files $Manifest.treeSha256
    if (-not (Test-ColdInventoryEqual @($Files) @($Manifest.files))) {throw 'READY_ACCEPTANCE_TREE'}
}

# Переводит actual UI receipt в identity для независимого сравнения по PID/birth/path/root.
function Get-NativeReadyAcceptanceUiIdentity($Ui,[string]$Root) {
    return [pscustomobject]@{ProcessId=$Ui.pid;StartedAtTicks=$Ui.startedAtTicks;ExecutablePath=$Ui.executablePath;OwnedRoot=$Root}
}

# Сравнивает содержимое сессии, разрешая только новый process marker и времена записи.
function Get-NativeReadyAcceptanceSessionState($Entry,[string]$Client) {
    $text=[Text.UTF8Encoding]::new($false,$true).GetString([Convert]::FromBase64String($Entry.contentBase64))
    if ($Client -ceq 'web') {
        $words=Get-ColdSessionWords
        foreach ($key in 'state','pid','started','saved') {
            $text=[regex]::Replace($text,'(?m)^- '+[regex]::Escape($words['session.md.key.'+$key])+': [^\n]*\n?','')
        }
        return $text
    }
    $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
    $reader=[Xml.XmlReader]::Create([IO.StringReader]::new($text),$settings)
    try {$doc=[Xml.XmlDocument]::new();$doc.XmlResolver=$null;$doc.Load($reader)} finally {$reader.Dispose()}
    foreach ($name in 'state','pid','startedAt','savedAt') {$doc.DocumentElement.RemoveAttribute($name)}
    return $doc.OuterXml
}

# Проверяет только факты. Положительные synthetic fixtures не становятся native PASS.
function Assert-NativeReadyAcceptanceFacts($Row,$Receipt,$Base,$Target,$Independent) {
    if ($null -eq $Independent) {throw 'READY_ACCEPTANCE_INDEPENDENT_REQUIRED'}
    Assert-ColdKeys $Independent @('schemaVersion','scenario','client','installationRoot','readyObservedAt','initialClient',
        'launcherIdentity','launcherArguments','launcherObservedAt','restartedClient','installerIdentity','installerCommandLine','installerObservedAt',
        'readyTree','currentBefore','currentAfter','targetBefore','targetAfter','userBefore','userAfter','controlledBefore','controlledAfter',
        'controlledAtReady','controlledAtPrestart','recovery','journalsCleared','cleanup','phaseLog')
    Assert-ColdKeys $Receipt @('scenario','ready','clientExit','launcher','originalArgs','launchAt','ui','recovery','version','verify',
        'launcherExit','installer','installerExit','lastInstall','server','serverIdentity','observedUtc')
    if ($Row.scenario -cnotin @('abrupt-ready-restart','launch-applying-safe-args') -or $Row.client -cnotin @('fx','swing','web') -or
        $Row.phase -cne 'SESSION' -or $Row.status -cne 'PENDING' -or $Row.executed -isnot [bool] -or -not $Row.executed -or
        $Independent.schemaVersion -isnot [int] -or $Independent.schemaVersion -ne 1 -or
        $Independent.scenario -cne $Row.scenario -or $Receipt.scenario -cne $Row.scenario -or $Independent.client -cne $Row.client -or
        $Independent.installationRoot -cne $Row.workRoot -or $Row.baseCommit -cne $Base.commitSha -or $Row.targetCommit -cne $Target.commitSha -or
        $Row.baseRelease -ne $Base.releaseNumber -or $Row.targetRelease -ne $Target.releaseNumber -or
        $Base.commitSha -cnotmatch '^[0-9a-f]{40}$' -or $Target.commitSha -cnotmatch '^[0-9a-f]{40}$' -or
        -not (Test-ColdInteger $Base.releaseNumber 1) -or -not (Test-ColdInteger $Target.releaseNumber 1) -or
        $Target.releaseNumber -le $Base.releaseNumber) {throw 'READY_ACCEPTANCE_CONTEXT'}
    $root=$Row.workRoot;$client=$Row.client;$exe=Join-Path $root (Get-ColdLauncherName $client)
    if (-not [IO.Path]::IsPathFullyQualified($root) -or $Row.exe -cne $exe) {throw 'READY_ACCEPTANCE_CONTEXT'}
    $begin=Get-ColdUtcTicks $Row.startedAt;$finished=Get-ColdUtcTicks $Row.finishedAt
    $ready=Get-ColdUtcTicks $Receipt.ready.observedUtc;$independentReady=Get-ColdUtcTicks $Independent.readyObservedAt
    $exit=Get-ColdUtcTicks $Receipt.clientExit.exitedUtc;$launch=Get-ColdUtcTicks $Receipt.launchAt
    $observed=Get-ColdUtcTicks $Receipt.observedUtc;$uiObserved=Get-ColdUtcTicks $Independent.restartedClient.observedAt
    if ($begin -gt $independentReady -or $independentReady -gt $exit -or $ready -lt $begin -or $ready -gt $exit -or
        $exit -gt $launch -or $launch -gt $uiObserved -or $uiObserved -gt $finished -or $observed -gt $finished -or
        $finished -lt $begin) {throw 'READY_ACCEPTANCE_TIME'}
    foreach ($ui in @($Receipt.ready.ui,$Independent.initialClient,$Receipt.ui,$Independent.restartedClient)) {Assert-ColdUiReceipt $ui $root $client}
    $initial=Get-NativeReadyAcceptanceUiIdentity $Independent.initialClient $root
    Assert-ColdProcessIdentity (Get-NativeReadyAcceptanceUiIdentity $Receipt.ready.ui $root) $initial $root $exe
    if ((Get-ColdUtcTicks $Independent.initialClient.observedAt) -gt $exit) {throw 'READY_ACCEPTANCE_TIME'}
    $restart=Get-NativeReadyAcceptanceUiIdentity $Independent.restartedClient $root
    Assert-ColdProcessIdentity (Get-NativeReadyAcceptanceUiIdentity $Receipt.ui $root) $restart $root $exe
    # PID может быть повторно выдан ОС; именно новый birth и lease, а не отличающийся PID, доказывают restart.
    if ($restart.StartedAtTicks -lt $launch -or $restart.StartedAtTicks -le $initial.StartedAtTicks -or
        $Independent.initialClient.lease.leaseId -ceq $Independent.restartedClient.lease.leaseId) {throw 'READY_ACCEPTANCE_RESTART_BIRTH'}
    Assert-ColdProcessIdentity $Receipt.launcher $Independent.launcherIdentity $root $exe
    $launcherObserved=Get-ColdUtcTicks $Independent.launcherObservedAt
    if ($Receipt.launcher.StartedAtTicks -lt $launch -or $launcherObserved -lt $Receipt.launcher.StartedAtTicks -or
        $launcherObserved -gt $finished -or $restart.StartedAtTicks -lt $Receipt.launcher.StartedAtTicks) {throw 'READY_ACCEPTANCE_LAUNCHER_BIRTH'}
    Assert-ColdSafeArgs $Receipt.originalArgs $Independent.restartedClient.args $root $client $Target.commitSha
    Assert-ColdSafeArgs $Row.args $Receipt.ui.args $root $client $Target.commitSha
    # Исходный jpackage launcher может уже выйти: actual ArgumentList берётся из его удерживаемого Process,
    # а путь и birth - отдельным Get-ColdProcessReceipt; посмертный CIM command line не требуется.
    if (-not (Test-ColdInventoryEqual @($Independent.launcherArguments) @($Receipt.originalArgs))) {throw 'READY_ACCEPTANCE_LAUNCHER_COMMAND'}
    if (-not (Test-ColdInventoryEqual $Receipt.ui.args $Independent.restartedClient.args)) {throw 'READY_ACCEPTANCE_RESTART_ARGS'}
    $powerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    Assert-ColdProcessIdentity $Receipt.installer $Independent.installerIdentity $root $powerShell
    $installerObserved=Get-ColdUtcTicks $Independent.installerObservedAt
    if ($Receipt.installer.StartedAtTicks -lt $ready -or $installerObserved -lt $Receipt.installer.StartedAtTicks -or $installerObserved -gt $finished) {throw 'READY_ACCEPTANCE_INSTALLER_BIRTH'}
    if ($Row.scenario -ceq 'launch-applying-safe-args' -and $installerObserved -gt $launch) {throw 'READY_ACCEPTANCE_APPLYING_NOT_LIVE'}
    $helper=Join-Path $root 'CashMemory/Updates/apply-update.ps1'
    $command="& '"+$helper.Replace("'","''")+"' -InstallationRoot '"+$root.Replace("'","''")+"'"
    Assert-ColdHelperCommand $Independent.installerCommandLine $powerShell ([Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command)))
    foreach ($code in @($Receipt.launcherExit,$Receipt.installerExit,$Row.exitCode,$Row.skipped,$Row.failures)) {
        if (-not (Test-ColdInteger $code) -or $code -ne 0) {throw 'READY_ACCEPTANCE_EXIT'}
    }
    if ($Row.scenario -ceq 'abrupt-ready-restart') {
        Assert-ColdProcessIdentity $Receipt.clientExit.identity $initial $root $exe
        if ($Receipt.clientExit.kind -cne 'abrupt-retained-client' -or -not (Test-ColdInteger $Receipt.clientExit.exitCode ([long]::MinValue)) -or
            $Receipt.clientExit.exitCode -eq 0) {throw 'READY_ACCEPTANCE_CRASH'}
    } else {
        if ($Receipt.clientExit.kind -cne 'ordinary-no-restart' -or $Receipt.clientExit.pid -ne $initial.ProcessId -or
            $Receipt.clientExit.startedAtTicks -ne $initial.StartedAtTicks -or $Receipt.clientExit.exitCode -ne 0 -or
            -not (Test-ColdInteger $Receipt.clientExit.exitCode) -or -not (Test-ColdInteger $Receipt.clientExit.remainingClients) -or
            $Receipt.clientExit.remainingClients -ne 0) {throw 'READY_ACCEPTANCE_NORMAL_EXIT'}
    }
    if (-not (Test-ColdInventoryEqual $Receipt.ready.manifest $Target) -or $Receipt.version.commitSha -cne $Target.commitSha -or
        $Receipt.version.releaseNumber -ne $Target.releaseNumber -or $Receipt.lastInstall.outcome -cne 'UPDATED' -or
        $Receipt.lastInstall.targetCommitSha -cne $Target.commitSha -or
        $Receipt.lastInstall.transactionId -cne $Independent.recovery.transactionId -or
        $Receipt.recovery.transactionId -cne $Independent.recovery.transactionId) {throw 'READY_ACCEPTANCE_TARGET'}
    Assert-NativeReadyAcceptanceTree $Independent.currentBefore $Base
    foreach ($tree in @(@{files=$Receipt.ready.tree},@{files=$Independent.readyTree},@{files=$Independent.currentAfter},
        @{files=$Independent.targetBefore},@{files=$Independent.targetAfter})) {Assert-NativeReadyAcceptanceTree $tree.files $Target}
    Assert-ColdRecoveryEvidence $Receipt.recovery $root $Independent.recovery.transactionId (Get-ColdUtcTicks $Receipt.recovery.samples[0].startedAt) $launch (Get-ColdUtcTicks $Receipt.ui.observedAt)
    Assert-ColdRecoveryEvidence $Independent.recovery $root $Independent.recovery.transactionId (Get-ColdUtcTicks $Independent.recovery.samples[0].startedAt) $launch $uiObserved
    if ($Row.scenario -ceq 'launch-applying-safe-args' -and -not @($Independent.recovery.samples | Where-Object {
        $_.journalBefore -and $_.journalAfter -and (Get-ColdUtcTicks $_.finishedAt) -le $launch}).Count) {throw 'READY_ACCEPTANCE_APPLYING_NOT_LIVE'}
    if (-not (Test-ColdInventoryEqual $Independent.userBefore $Independent.userAfter)) {throw 'READY_ACCEPTANCE_USER_CHANGED'}
    if ($Independent.userBefore -isnot [Collections.IDictionary] -and $Independent.userBefore -isnot [pscustomobject]) {throw 'READY_ACCEPTANCE_USER_INVENTORY'}
    $userProperties=@(if ($Independent.userBefore -is [Collections.IDictionary]) {$Independent.userBefore.Keys} else {
        $Independent.userBefore.PSObject.Properties | ForEach-Object {$_.Name}
    })
    if (-not $userProperties.Count) {throw 'READY_ACCEPTANCE_USER_INVENTORY'}
    foreach ($path in $userProperties) {
        $entry=$Independent.userBefore.$path
        Assert-ColdKeys $entry @('path','directory','sizeBytes','sha256','readOnly')
        if ($entry.path -cne $path -or $path -match '(^/|\\|:|(^|/)\.\.(/|$))' -or
            $entry.directory -isnot [bool] -or $entry.readOnly -isnot [bool] -or -not (Test-ColdInteger $entry.sizeBytes) -or
            ($entry.directory -and ($entry.sizeBytes -ne 0 -or $entry.sha256 -cne 'directory')) -or
            (-not $entry.directory -and $entry.sha256 -cnotmatch '^[0-9a-f]{64}$')) {throw 'READY_ACCEPTANCE_USER_INVENTORY'}
    }
    foreach ($path in 'CashMemory/settings.md','CashMemory/NativeLifecycle.md') {
        if ($path -cnotin $userProperties -or $Independent.userBefore.$path.directory -or $Independent.userBefore.$path.sizeBytes -le 0) {throw 'READY_ACCEPTANCE_USER_INVENTORY'}
    }
    Assert-ColdControlledChanges $Independent.controlledBefore $Independent.controlledAfter $client $Independent.restartedClient $root $finished
    $session=if ($client -ceq 'web') {'CashMemory/web-session.md'} else {'CashMemory/session-'+$client+'.xml'}
    if (@($Independent.controlledAfter | Where-Object {$_.path -ceq $session}).Count -ne 1 -or
        $Independent.recovery.controlledSha256 -cne (Get-ColdObjectHash @($Independent.controlledAtPrestart))) {throw 'READY_ACCEPTANCE_SESSION_REQUIRED'}
    $oldSession=@($Independent.controlledAtReady | Where-Object {$_.path -ceq $session})
    $newSession=@($Independent.controlledAfter | Where-Object {$_.path -ceq $session})
    if ($oldSession.Count -ne 1) {throw 'READY_ACCEPTANCE_SESSION_REQUIRED'}
    Assert-ColdControlledChanges @() $oldSession $client $Independent.initialClient $root $exit
    Assert-ColdControlledChanges $Independent.controlledAtReady $Independent.controlledAtPrestart $client $Independent.initialClient $root $exit
    if ((Get-NativeReadyAcceptanceSessionState $oldSession[0] $client) -cne
        (Get-NativeReadyAcceptanceSessionState $newSession[0] $client)) {throw 'READY_ACCEPTANCE_SESSION_CHANGED'}
    Assert-ColdKeys $Independent.cleanup @('finishedAt','errors','remainingClients','remainingHelpers','registryUnchanged')
    if ($Independent.journalsCleared -isnot [bool] -or -not $Independent.journalsCleared -or
        @($Independent.cleanup.errors).Count -or -not (Test-ColdInteger $Independent.cleanup.remainingClients) -or $Independent.cleanup.remainingClients -ne 0 -or
        -not (Test-ColdInteger $Independent.cleanup.remainingHelpers) -or $Independent.cleanup.remainingHelpers -ne 0 -or
        $Independent.cleanup.registryUnchanged -isnot [bool] -or -not $Independent.cleanup.registryUnchanged -or
        (Get-ColdUtcTicks $Independent.cleanup.finishedAt) -lt $uiObserved -or $Independent.phaseLog -cnotcontains 'SESSION' -or
        $Independent.phaseLog -cnotcontains 'COMMITTED') {throw 'READY_ACCEPTANCE_CLEANUP'}
}

# MAIN интерфейс: pin двух отдельных artifacts, pure fact guards, никакой автоматической смены Row.status.
function Test-NativeReadyAcceptance($Row,$Receipt,$Base,$Target,[string]$ReceiptSha256,[string]$IndependentFile,[string]$IndependentSha256) {
    if (-not $IndependentFile -or -not $IndependentSha256) {throw 'READY_ACCEPTANCE_INDEPENDENT_REQUIRED'}
    if (-not (Test-PortablePathContains $Row.evidenceDirectory $Row.command) -or
        -not (Test-PortablePathContains $Row.evidenceDirectory $IndependentFile) -or
        $IndependentFile.Equals($Row.command,[StringComparison]::OrdinalIgnoreCase)) {throw 'READY_ACCEPTANCE_ARTIFACT_SCOPE'}
    $stored=Read-NativeReadyAcceptanceJson $Row.command $ReceiptSha256
    # Depth=64 нужен реальному nested inventory; cold comparator Depth=8 здесь не используется.
    if ((ConvertTo-Json -InputObject $stored -Depth 64 -Compress) -cne (ConvertTo-Json -InputObject $Receipt -Depth 64 -Compress)) {throw 'READY_ACCEPTANCE_RECEIPT_MISMATCH'}
    $independent=Read-NativeReadyAcceptanceJson $IndependentFile $IndependentSha256
    Assert-NativeReadyAcceptanceFacts $Row $Receipt $Base $Target $independent
    # Пост-cleanup filesystem доступен независимо от helper; повторно читается действительное дерево и user-data.
    [void](Assert-ColdTree $Row.workRoot $Target)
    $actualUser=[ordered]@{};foreach ($entry in @(Get-ColdUserInventory $Row.workRoot)) {$actualUser[$entry.path]=$entry}
    if (-not (Test-ColdInventoryEqual $actualUser $independent.userAfter)) {throw 'READY_ACCEPTANCE_CURRENT_USER_CHANGED'}
    return [pscustomobject]@{status='PENDING';cellEvidenceValidated=$true;scope='READY_CELL_ONLY';
        scenario=$Row.scenario;client=$Row.client;receiptSha256=$ReceiptSha256;independentSha256=$IndependentSha256;
        reason='READY_FACTS_VALIDATED_MAIN_ACCEPTANCE_REQUIRED'}
}
