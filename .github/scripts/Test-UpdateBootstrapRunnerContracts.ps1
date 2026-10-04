<#
.SYNOPSIS
Независимый аудит принятия квитанций cold runner, только AST и изолированные mocks.
.DESCRIPTION
Не запускает тело runner, builder, Java, helper, EXE, GUI, сеть или реестр.
Все артефакты существуют только в памяти. Синтетическая положительная квитанция
нужна для проверки отдельных отказов и никогда не подтверждает native PASS.
Неотклонённые отрицательные данные дают GAP и ненулевой exit всего аудита.
#>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3
if ($PSVersionTable.PSVersion.Major -lt 7) {throw 'RUNNER_CONTRACT_POWERSHELL7_REQUIRED'}
$source=Join-Path $PSScriptRoot 'Test-UpdateBootstrap.ps1'
$tokens=$null; $parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) {throw 'RUNNER_CONTRACT_PARSE'}
$formatPath=Join-Path $PSScriptRoot '../../core/src/main/resources/ru/cashprediction/core/format/format.properties'
$formatText=Get-Content -LiteralPath $formatPath -Raw -Encoding utf8

# Дочерняя область исключает утечку mock-функций в вызывающий сеанс.
& {
    param($RunnerAst,[string]$FormatText,[string]$FormatPath)
    $script:contractChecks=0
    $script:contractGaps=[Collections.Generic.List[string]]::new()
    foreach ($name in 'Assert-ColdKeys','Get-ColdCheckpoints','Assert-ColdCheckpoint','Assert-ColdProcessIdentity',
        'Get-ColdTreeHash','Assert-ColdTree','Get-ColdProcessReceipt','Stop-ColdRetainedProcess',
        'Get-ColdUiReceipt','Stop-ColdRecoveryHelpers','Assert-ColdMatrix','Test-ColdInteger','Get-ColdLauncherName',
        'Assert-ColdInventory','Assert-ColdImageInventory','Test-ColdInventoryEqual','Get-ColdUtcTicks','ConvertFrom-ColdCommandLine',
        'Assert-ColdSafeArgs','Assert-ColdUiReceipt','Get-ColdCurrentProcess','Assert-ColdHelperCommand',
        'Assert-ColdRecoveryEvidence','Assert-ColdControlledChanges','Get-ColdRecoveryObservation','Stop-ColdCopyProcesses','ConvertFrom-ColdReceiptJson','Get-ColdObjectHash','Get-ColdMainModule',
        'Test-ColdExternalModules','Test-ColdProtectedPayload','Assert-ColdProtectedEvidence') {
        $nodes=@($RunnerAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
        if ($nodes.Count -ne 1) {throw "RUNNER_CONTRACT_FUNCTION $name"}
        . ([scriptblock]::Create($nodes[0].Extent.Text))
    }

    # Отказ по иной причине является поломкой fixture, а принятие - дефектом guard.
    function Test-ContractRejection([string]$Name,[scriptblock]$Action,[string]$Code) {
        $caught=$null
        try {& $Action | Out-Null} catch {$caught=$_.Exception.Message}
        $script:contractChecks++
        if ($null -eq $caught) {$script:contractGaps.Add($Name); Write-Output "GAP $Name"}
        elseif ($caught -cne $Code) {throw "CONTRACT_UNEXPECTED_ERROR $Name expected=$Code actual=$caught"}
    }

    # Положительный контроль нужен, чтобы отрицательный тест дошёл до нужного guard.
    function Assert-Contract([bool]$Condition,[string]$Name) {
        if (-not $Condition) {throw "CONTRACT_FIXTURE $Name"}
        $script:contractChecks++
    }

    # Глубокая копия не разделяет mutable записи между независимыми сценариями.
    function Copy-Contract($Value) {ConvertFrom-ColdReceiptJson (ConvertTo-Json -InputObject $Value -Depth 64)}

    # Любая попытка выйти из mock-среды немедленно ломает fixture.
    function Start-Process {throw 'CONTRACT_FORBIDDEN_PROCESS'}
    function Start-ColdProcess {throw 'CONTRACT_FORBIDDEN_PROCESS'}
    function Invoke-ColdTool {throw 'CONTRACT_FORBIDDEN_JAVA'}
    function Stop-Process {throw 'CONTRACT_FORBIDDEN_KILL'}
    function Start-Sleep {throw 'CONTRACT_FORBIDDEN_WAIT'}
    function Invoke-WebRequest {throw 'CONTRACT_FORBIDDEN_NETWORK'}
    function Get-NetTCPConnection {return $script:mockListeners}
    function Get-CimInstance {return $script:mockObserved}
    function Get-CopyProcesses {return $script:mockObserved}
    function Open-PortableProcess {return $script:mockProcess}
    function Resolve-PortableSafetyPath([string]$Path) {
        if (-not [IO.Path]::IsPathFullyQualified($Path)) {throw 'CONTRACT_RELATIVE_ARTIFACT'}
        return $Path
    }

    # Mock containment проверяет корень файла; доступ к файловой системе отсутствует.
    function Test-PortablePathContains([string]$Root,[string]$Path) {
        return $Path.StartsWith($Root.TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)
    }

    # Файлы-квитанции в памяти позволяют изменять bytes и пересчитывать реальные SHA.
    function Set-ContractArtifact([string]$Path,$Value) {
        $script:mockArtifacts[$Path]=ConvertTo-Json -InputObject $Value -Depth 64
    }
    function Test-Path([string]$LiteralPath,[string]$PathType) {
        return ($script:mockArtifacts.ContainsKey($LiteralPath) -or $LiteralPath -ceq $script:mockLeases)
    }
    function Get-Content([string]$LiteralPath,[switch]$Raw) {
        if (-not $script:mockArtifacts.ContainsKey($LiteralPath)) {throw 'CONTRACT_ARTIFACT_MISSING'}
        if ($Raw) {return $script:mockArtifacts[$LiteralPath]}
        return ($script:mockArtifacts[$LiteralPath] -split '\r?\n')
    }
    function Get-FileHash([string]$LiteralPath) {
        if (-not $script:mockArtifacts.ContainsKey($LiteralPath)) {throw 'CONTRACT_ARTIFACT_MISSING'}
        return [pscustomobject]@{Hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
            [Text.Encoding]::UTF8.GetBytes($script:mockArtifacts[$LiteralPath])))}
    }
    function Get-ChildItem([string]$LiteralPath,[switch]$File,[string]$Filter) {
        if ($LiteralPath -cne $script:mockLeases) {throw 'CONTRACT_UNEXPECTED_DIRECTORY'}
        return @([pscustomobject]@{FullName=$script:mockLeasePath})
    }
    function Get-ColdManagedInventory {return $script:mockInventory}

    $root=Join-Path ([IO.Path]::GetTempPath()) 'cp-contract-memory-only'
    $exe=Join-Path $root 'CashPrediction.exe'
    $powerShell=Join-Path $root 'mock-powershell.exe'
    $script:mockArtifacts=@{}
    $script:mockArtifacts[$FormatPath]=$FormatText
    $script:mockLeases=Join-Path $root 'CashMemory/Updates/processes'
    $script:mockLeasePath=Join-Path $script:mockLeases 'mock.json'
    $transaction='11111111-1111-1111-1111-111111111111'
    $birth=[datetime]::UtcNow.AddMinutes(-1)
    $helperIdentity=[pscustomobject]@{ProcessId=730;StartedAtTicks=$birth.AddSeconds(-1).Ticks;ExecutablePath=$powerShell;OwnedRoot=$root}
    $lease=[pscustomobject]@{schemaVersion=1;leaseId=$transaction;pid=731;startedAtEpochMillis=([DateTimeOffset]$birth).ToUnixTimeMilliseconds();installationRoot=$root;client='fx'}
    $ui=[pscustomobject]@{pid=731;startedAtTicks=$birth.Ticks;executablePath=$exe;modules=@((Join-Path $root 'runtime/bin/server/jvm.dll'));
        witness=[pscustomobject]@{kind='native-window';handle=123;title='CashPrediction - mock'};lease=$lease;
        args=@('--home',$root,'--updated-from',('b'*40));commandLine=('"'+$exe+'" --home "'+$root+'" --updated-from '+('b'*40));observedAt=$birth.AddMilliseconds(20).ToString('o')}
    $inventory=@('CashPrediction.exe','CashPrediction-Swing.exe','CashPrediction-Web.exe',
        'app/cashprediction-core-1.0.0.jar','app/cashprediction-ui-fx-1.0.0.jar',
        'app/cashprediction-ui-swing-1.0.0.jar','app/cashprediction-web-1.0.0.jar',
        'app/.jpackage.xml','app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg',
        'runtime/bin/jli.dll','runtime/bin/server/jvm.dll','runtime/lib/modules' | Sort-Object {[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($_))} | ForEach-Object {
        [pscustomobject][ordered]@{path=$_;sizeBytes=1L;sha256=('a'*64);readOnly=$false}
    })
    $tree=Get-ColdTreeHash $inventory
    $newInventory=Copy-Contract $inventory
    foreach ($file in $newInventory) {$file.sha256='b'*64}
    $newTree=Get-ColdTreeHash $newInventory
    $journal=[pscustomobject]@{schemaVersion=2;installationRoot=$root;transactionId=$transaction;
        oldFiles=$inventory;oldTreeSha256=$tree;operations=@();target=[pscustomobject]@{files=$newInventory;treeSha256=$newTree;releaseNumber=2;commitSha=('b'*40)}}
    $protected=@($inventory | Where-Object {Test-ColdProtectedPayload $_.path})
    $checkpoint=[pscustomobject]@{schemaVersion=1;checkpoint='ACTIVE';installationRoot=$root;transactionId=$transaction;
        pid=730;startedAtTicks=$helperIdentity.StartedAtTicks;executablePath=$powerShell;hook='SAVE';phase='BOOTSTRAPPING';
        bootstrapState='ACTIVE';publishState='AFTER';operation=$null;journalSha256=('a'*64);
        bootstrapVerified=$true;bootstrapFiles=$protected;bootstrapTreeSha256=(Get-ColdTreeHash $protected)}
    $journalPath=Join-Path $root 'journal.json'; $checkpointPath=Join-Path $root 'checkpoint.json'
    $inventoryPath=Join-Path $root 'inventory.json'; $launchPath=Join-Path $root 'launch.json'; $userPath=Join-Path $root 'user.json'
    $recoveryPath=Join-Path $root 'recovery.json';$postPath=Join-Path $root 'post.json';$controlledPath=Join-Path $root 'controlled.json'
    Set-ContractArtifact $userPath @([pscustomobject]@{path='CashMemory/plans/user.md';sha256=('c'*64)})
    $userHash=(Get-FileHash $userPath).Hash.ToLowerInvariant()
    $launch=[pscustomobject]@{schemaVersion=1;base='B1';checkpoint='ACTIVE';client='fx';pathVariant='ascii';nativeExe=$exe;
        args=@('--home',$root);startedAt=$birth.AddMilliseconds(-10).ToString('o');
        launcher=[pscustomobject]@{ProcessId=732;StartedAtTicks=$birth.AddMilliseconds(-10).Ticks;ExecutablePath=$exe};uiReceipt=$ui;
        version=[pscustomobject]@{releaseNumber=1;commitSha=('a'*40);jar='cashprediction-core-1.0.0.jar';jarSha256=('a'*64)};treeSha256=$tree;actualExit=0;
        helperKilledAt=$birth.AddMilliseconds(-100).ToString('o');finishedAt=$birth.AddMilliseconds(50).ToString('o');
        recoveryReceipt=$recoveryPath;postCleanupReceipt=$postPath;controlledBeforeReceipt=$controlledPath;controlledAfterReceipt=$controlledPath}
    $row=[pscustomobject]@{base='B1';checkpoint='ACTIVE';client='fx';pathVariant='ascii';status='PASS';nativeExecuted=$true;
        actualExit=0;helperActualExit=-1;treeOutcome='OLD';managedSha256=$tree;baseTreeSha256=$tree;targetTreeSha256=$newTree;
        userBeforeSha256=$userHash;userAfterSha256=$userHash;userBeforeReceipt=$userPath;userAfterReceipt=$userPath;uiReceipt=$ui;
        checkpointReceipt=$checkpointPath;journalReceipt=$journalPath;inventoryReceipt=$inventoryPath;launchReceipt=$launchPath;
        workRoot=$root;helperIdentity=$helperIdentity;transactionId=$transaction;baseReleaseNumber=1;baseCommitSha=('a'*40);
        targetReleaseNumber=2;targetCommitSha=('b'*40);phase='BOOTSTRAPPING';bootstrapState='ACTIVE';publishState='AFTER'}
    $plan=@([pscustomobject]@{key='B1/ACTIVE/fx/ascii'})

    # Каждый запуск восстанавливает исходные квитанции, включая согласованный journal pin.
    function Reset-ContractArtifacts {
        Set-ContractArtifact $journalPath $journal
        $p=Copy-Contract $checkpoint; $p.journalSha256=(Get-FileHash $journalPath).Hash.ToLowerInvariant()
        Set-ContractArtifact $checkpointPath $p
        Set-ContractArtifact $inventoryPath $inventory
        Set-ContractArtifact $launchPath $launch
        Set-ContractArtifact $script:mockLeasePath $lease
        Set-ContractArtifact $controlledPath @()
        Set-ContractArtifact $recoveryPath ([pscustomobject]@{schemaVersion=1;installationRoot=$root;transactionId=$transaction;pollingLimitMillis=1000;controlledSha256=(Get-ColdObjectHash @());samples=@(
            [pscustomobject]@{startedAt=$birth.AddMilliseconds(-90).ToString('o');finishedAt=$birth.AddMilliseconds(-80).ToString('o');journalBefore=$true;journalAfter=$true;visibleCount=0;controlledSha256=(Get-ColdObjectHash @())},
            [pscustomobject]@{startedAt=$birth.AddMilliseconds(5).ToString('o');finishedAt=$birth.AddMilliseconds(10).ToString('o');journalBefore=$false;journalAfter=$false;visibleCount=1;controlledSha256=(Get-ColdObjectHash @())})})
        Set-ContractArtifact $postPath ([pscustomobject]@{schemaVersion=1;installationRoot=$root;transactionId=$transaction;
            observedAt=$birth.AddMilliseconds(30).ToString('o');treeSha256=$tree;files=$inventory;toolExit=0;processesRemaining=0})
    }
    Reset-ContractArtifacts
    Assert-ColdSafeArgs $launch.args $ui.args $root 'fx' ('b'*40)
    Assert-ColdMatrix @($row) $plan
    Assert-Contract $true 'SYNTHETIC_BASELINE_ACCEPTED_NOT_NATIVE_PASS'
    $newRow=Copy-Contract $row; $newRow.treeOutcome='NEW'; $newRow.managedSha256=$newTree
    $newLaunch=Copy-Contract $launch; $newLaunch.treeSha256=$newTree
    $newLaunch.version.releaseNumber=2; $newLaunch.version.commitSha='b'*40;$newLaunch.version.jarSha256='b'*64
    Set-ContractArtifact $inventoryPath $newInventory; Set-ContractArtifact $launchPath $newLaunch
    $newPost=Get-Content $postPath -Raw | ConvertFrom-Json -Depth 64;$newPost.files=$newInventory;$newPost.treeSha256=$newTree;Set-ContractArtifact $postPath $newPost
    Assert-ColdMatrix @($newRow) $plan
    Assert-Contract $true 'SYNTHETIC_NEW_BASELINE_NOT_NATIVE_PASS'
    $badNew=Copy-Contract $newLaunch; $badNew.version.releaseNumber=1
    Set-ContractArtifact $launchPath $badNew
    Test-ContractRejection 'new-tree-old-version' {Assert-ColdMatrix @($newRow) $plan} 'COLD_REPORT_VERSION'

    # Эти мутации покрывают недостающие в старых fixtures связи полей.
    foreach ($case in @(
        @('launch-wrong-exe','COLD_REPORT_LAUNCH_IDENTITY'),@('launch-wrong-home','COLD_REPORT_LAUNCH_IDENTITY'),
        @('launch-wrong-launcher','COLD_REPORT_LAUNCH_IDENTITY'),@('launch-wrong-lease-client','COLD_REPORT_LAUNCH_IDENTITY'),
        @('launch-wrong-version','COLD_REPORT_VERSION'),@('launch-wrong-commit','COLD_REPORT_VERSION'),
        @('launch-no-witness','COLD_REPORT_LAUNCH_IDENTITY'),@('launch-foreign-jvm','COLD_REPORT_LAUNCH_IDENTITY'),
        @('launch-wrong-ui-exe','COLD_REPORT_LAUNCH_IDENTITY'),@('launch-wrong-lease-birth','COLD_REPORT_LAUNCH_IDENTITY'),
        @('launch-wrong-lease-pid','COLD_REPORT_LAUNCH_IDENTITY'),@('launch-ui-before-launch','COLD_REPORT_LAUNCH_IDENTITY'),
        @('launch-extra-unsafe-args','COLD_REPORT_LAUNCH_IDENTITY'),@('launch-invalid-time','COLD_REPORT_LAUNCH_IDENTITY'),
        @('journal-foreign-root','COLD_REPORT_JOURNAL_IDENTITY'),@('journal-wrong-transaction','COLD_REPORT_JOURNAL_IDENTITY'),
        @('journal-incomplete-old-inventory','COLD_REPORT_JOURNAL_IDENTITY'),@('journal-incomplete-new-inventory','COLD_REPORT_JOURNAL_IDENTITY'),
        @('checkpoint-wrong-phase','COLD_REPORT_PHASE_IDENTITY'),@('checkpoint-wrong-hook','COLD_CHECKPOINT_STATE')
    )) {
        Reset-ContractArtifacts
        $l=Copy-Contract $launch; $j=Copy-Contract $journal
        $p=Get-Content $checkpointPath -Raw | ConvertFrom-Json -Depth 64
        switch ($case[0]) {
            'launch-wrong-exe' {$l.nativeExe=$powerShell}
            'launch-wrong-home' {$l.args[1]=$root+'-foreign'}
            'launch-wrong-launcher' {$l.launcher.ExecutablePath=$powerShell}
            'launch-wrong-lease-client' {$l.uiReceipt.lease.client='web'}
            'launch-wrong-version' {$l.version.releaseNumber=2}
            'launch-wrong-commit' {$l.version.commitSha='b'*40}
            'launch-no-witness' {$l.uiReceipt.PSObject.Properties.Remove('witness')}
            'launch-foreign-jvm' {$l.uiReceipt.modules=@('C:\foreign\jvm.dll')}
            'launch-wrong-ui-exe' {$l.uiReceipt.executablePath=$powerShell}
            'launch-wrong-lease-birth' {$l.uiReceipt.lease.startedAtEpochMillis++}
            'launch-wrong-lease-pid' {$l.uiReceipt.lease.pid++}
            'launch-ui-before-launch' {$l.startedAt=$birth.AddDays(1).ToString('o')}
            'launch-extra-unsafe-args' {$l.args+=@('--registry-node','foreign','--test-api')}
            'launch-invalid-time' {$l.startedAt='not-a-time'}
            'journal-foreign-root' {$j.installationRoot=$root+'-foreign'}
            'journal-wrong-transaction' {$j.transactionId='foreign'}
            'journal-incomplete-old-inventory' {$j.oldFiles=@()}
            'journal-incomplete-new-inventory' {$j.target.files=@()}
            'checkpoint-wrong-phase' {$p.phase='INSTALLING'}
            'checkpoint-wrong-hook' {$p.hook='BOUNDARY'}
        }
        Set-ContractArtifact $journalPath $j
        $p.journalSha256=(Get-FileHash $journalPath).Hash.ToLowerInvariant()
        Set-ContractArtifact $checkpointPath $p; Set-ContractArtifact $launchPath $l
        Test-ContractRejection $case[0] {Assert-ColdMatrix @($row) $plan} $case[1]
    }

    # Новые обязательные артефакты нельзя заменить template, ранним UI или неполным postcleanup.
    foreach ($case in 'active-ui','no-active-sample','no-cleared-sample','sample-time-reversed','sample-gap','sample-duration',
        'active-controlled-change','post-partial','post-wrong-root','post-tool-failed','post-process-alive','post-before-ui','controlled-corrupt') {
        Reset-ContractArtifacts
        $r=ConvertFrom-ColdReceiptJson (Get-Content $recoveryPath -Raw)
        $post=ConvertFrom-ColdReceiptJson (Get-Content $postPath -Raw)
        $code='COLD_REPORT_RECOVERY_EVIDENCE'
        switch ($case) {
            'active-ui' {$r.samples[0].visibleCount=1}
            'no-active-sample' {$r.samples[0].journalBefore=$false;$r.samples[0].journalAfter=$false}
            'no-cleared-sample' {$r.samples[1].journalBefore=$true;$r.samples[1].journalAfter=$true;$r.samples[1].visibleCount=0}
            'sample-time-reversed' {$r.samples[0].finishedAt=$birth.AddMilliseconds(-95).ToString('o')}
            'sample-gap' {$r.samples[1].startedAt=$birth.AddSeconds(2).ToString('o');$r.samples[1].finishedAt=$birth.AddSeconds(2).ToString('o')}
            'sample-duration' {$r.samples[0].finishedAt=$birth.AddSeconds(2).ToString('o')}
            'active-controlled-change' {$r.samples[0].controlledSha256='a'*64}
            'post-partial' {$post.files=@($post.files | Select-Object -Skip 1);$code='COLD_REPORT_POST_CLEANUP'}
            'post-wrong-root' {$post.installationRoot=$root+'-foreign';$code='COLD_REPORT_POST_CLEANUP'}
            'post-tool-failed' {$post.toolExit=1;$code='COLD_REPORT_POST_CLEANUP'}
            'post-process-alive' {$post.processesRemaining=1;$code='COLD_REPORT_POST_CLEANUP'}
            'post-before-ui' {$post.observedAt=$birth.AddMilliseconds(-1).ToString('o');$code='COLD_REPORT_POST_CLEANUP'}
            'controlled-corrupt' {Set-ContractArtifact $controlledPath @([pscustomobject]@{path='CashMemory/session-fx.xml';sizeBytes=1;sha256=('a'*64);contentBase64='';readOnly=$false})}
        }
        Set-ContractArtifact $recoveryPath $r;Set-ContractArtifact $postPath $post
        Test-ContractRejection $case {Assert-ColdMatrix @($row) $plan} $code
    }

    # Аргументы Web проверяются как исходный allowlist плюс ровно один ожидаемый update SHA.
    $webOriginal=@('--home',$root,'--no-browser','--no-window')
    Assert-ColdSafeArgs $webOriginal @($webOriginal+@('--updated-from',('b'*40))) $root 'web' ('b'*40)
    Assert-Contract $true 'WEB_FLAGS_PRESERVED'
    foreach ($badArgs in @(
        @{args=@('--home',$root,'--no-browser','--updated-from',('b'*40))},
        @{args=@('--home',$root,'--no-browser','--no-window','--updated-from',('a'*40))},
        @{args=@('--home',$root,'--no-browser','--no-window','--updated-from',('b'*40),'--test-api')},
        @{args=@('--home',$root,'--no-browser','--no-window','--no-window','--updated-from',('b'*40))}
    )) {Test-ContractRejection 'unsafe-web-restart' {Assert-ColdSafeArgs $webOriginal $badArgs.args $root 'web' ('b'*40)} 'COLD_REPORT_LAUNCH_IDENTITY'}

    # Pure argv parser покрывает кавычки, Unicode и backslash без shell/API.
    Assert-Contract (Test-ColdInventoryEqual @(ConvertFrom-ColdCommandLine '"C:\Мои программы\CashPrediction.exe" --home "C:\Δ 测试"') @('C:\Мои программы\CashPrediction.exe','--home','C:\Δ 测试')) 'WINDOWS_ARGV_UNICODE'
    Test-ContractRejection 'argv-unclosed-quote' {ConvertFrom-ColdCommandLine '"unclosed'} 'COLD_COMMAND_LINE'
    foreach ($badInventory in @(
        @{field='path';value='app/../outside'},@{field='path';value='app/CON.txt'},@{field='path';value='app/file:stream'},
        @{field='sizeBytes';value='1'},@{field='readOnly';value='false'},@{field='sha256';value=('A'*64)}
    )) {
        $bad=Copy-Contract $inventory;$bad[0].($badInventory.field)=$badInventory.value
        Test-ContractRejection ('inventory-'+$badInventory.field) {Assert-ColdInventory $bad $tree} 'COLD_INVENTORY'
    }
    Reset-ContractArtifacts
    foreach ($case in 'string-exit','string-helper-exit','string-ui-pid','string-window-handle','wrong-jar-pin','restart-extra-flag','restart-wrong-sha') {
        $bad=Copy-Contract $row;$l=Copy-Contract $launch;$code='COLD_REPORT_LAUNCH_IDENTITY'
        switch ($case) {
            'string-exit' {$bad.actualExit='0';$code='COLD_REPORT_PENDING_OR_FAILED'}
            'string-helper-exit' {$bad.helperActualExit='-1';$code='COLD_REPORT_PENDING_OR_FAILED'}
            'string-ui-pid' {$l.uiReceipt.pid='731'}
            'string-window-handle' {$l.uiReceipt.witness.handle='123'}
            'wrong-jar-pin' {$l.version.jarSha256='c'*64;$code='COLD_REPORT_VERSION'}
            'restart-extra-flag' {$l.uiReceipt.args+=@('--test-api');$l.uiReceipt.commandLine+=' --test-api';$bad.uiReceipt=Copy-Contract $l.uiReceipt}
            'restart-wrong-sha' {$l.uiReceipt.args[-1]='c'*40;$l.uiReceipt.commandLine=$l.uiReceipt.commandLine.Replace(('b'*40),('c'*40));$bad.uiReceipt=Copy-Contract $l.uiReceipt}
        }
        Set-ContractArtifact $launchPath $l
        Test-ContractRejection $case {Assert-ColdMatrix @($bad) $plan} $code
    }

    # Semantics controlled snapshots отделяет текущую сессию от данных других клиентов.
    function New-ContractControlled([string]$Path,[string]$Text) {
        $bytes=[Text.Encoding]::UTF8.GetBytes($Text)
        return [pscustomobject]@{path=$Path;sizeBytes=[long]$bytes.Length;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant();contentBase64=[Convert]::ToBase64String($bytes);readOnly=$false}
    }
    $snapshot='<session schema="1" client="fx" state="running" pid="731" startedAt="'+$birth.ToString('o')+'" savedAt="'+$birth.AddMilliseconds(10).ToString('o')+'"><main/></session>'
    $current=@((New-ContractControlled 'CashMemory/session-fx.xml' $snapshot))
    Assert-ColdControlledChanges @() $current 'fx' $ui $root $birth.AddMilliseconds(50).Ticks
    Assert-Contract $true 'OWN_SESSION_ALLOWED'
    foreach ($case in 'wrong-pid','wrong-client','future-save','dtd','foreign-session','unsaved-plan','lock-data','delete','readonly') {
        $before=@();$after=$current
        switch ($case) {
            'wrong-pid' {$after=@((New-ContractControlled 'CashMemory/session-fx.xml' $snapshot.Replace('pid="731"','pid="999"')))}
            'wrong-client' {$after=@((New-ContractControlled 'CashMemory/session-fx.xml' $snapshot.Replace('client="fx"','client="web"')))}
            'future-save' {$after=@((New-ContractControlled 'CashMemory/session-fx.xml' $snapshot.Replace($birth.AddMilliseconds(10).ToString('o'),$birth.AddDays(1).ToString('o'))))}
            'dtd' {$after=@((New-ContractControlled 'CashMemory/session-fx.xml' ('<!DOCTYPE session SYSTEM "file:///unread">'+$snapshot)))}
            'foreign-session' {$after=@((New-ContractControlled 'CashMemory/session-swing.xml' $snapshot))}
            'unsaved-plan' {$after=@((New-ContractControlled 'CashMemory/web-session.plan.md' 'changed-plan'))}
            'lock-data' {$after=@((New-ContractControlled 'CashMemory/lock.md' 'changed-lock'))}
            'delete' {$before=$current;$after=@()}
            'readonly' {$before=$current;$after=Copy-Contract $current;$after[0].readOnly=$true}
        }
        Test-ContractRejection ('controlled-'+$case) {Assert-ColdControlledChanges $before $after 'fx' $ui $root $birth.AddMilliseconds(50).Ticks} 'COLD_REPORT_CONTROLLED'
    }
    $words=@{}
    foreach ($line in $FormatText -split '\r?\n') {if ($line -match '^(session\.md\.[^=]+)=(.*)$') {$words[$Matches[1]]=$Matches[2]}}
    # Получение grammar resource заменено заранее прочитанным полным ресурсом, parser настоящий.
    function Get-ColdSessionWords {return $words}
    $webSnapshot=$words['session.md.title.prefix']+"web)`n`n"
    foreach ($pair in @(@('state','running'),@('pid','731'),@('started',$birth.ToString('o')),@('saved',$birth.AddMilliseconds(10).ToString('o')),@('schema','1'))) {
        $webSnapshot+='- '+$words['session.md.key.'+$pair[0]]+': '+$pair[1]+"`n"
    }
    Assert-ColdControlledChanges @() @((New-ContractControlled 'CashMemory/web-session.md' $webSnapshot)) 'web' $ui $root $birth.AddMilliseconds(50).Ticks
    Assert-Contract $true 'WEB_SESSION_ALLOWED'
    Test-ContractRejection 'web-session-foreign-pid' {Assert-ColdControlledChanges @() @((New-ContractControlled 'CashMemory/web-session.md' $webSnapshot.Replace(': 731',': 999'))) 'web' $ui $root $birth.AddMilliseconds(50).Ticks} 'COLD_REPORT_CONTROLLED'
    $binding=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes((Join-Path $root 'CashMemory').ToLowerInvariant()))).ToLowerInvariant()
    $credentials="# CashPrediction web reconnect`nVersion: 1`nInstallation: "+('a'*32)+"`nKey: "+('b'*64)+"`nPath-SHA256: "+$binding+"`n"
    Assert-ColdControlledChanges @() @((New-ContractControlled 'CashMemory/web-reconnect.md' $credentials),(New-ContractControlled 'CashMemory/web-reconnect-lock.md' '')) 'web' $ui $root $birth.AddMilliseconds(50).Ticks
    Assert-Contract $true 'WEB_CREDENTIALS_BOUND_TO_HOME'
    Test-ContractRejection 'web-credentials-foreign-home' {Assert-ColdControlledChanges @() @((New-ContractControlled 'CashMemory/web-reconnect.md' $credentials.Replace($binding,('c'*64)))) 'web' $ui $root $birth.AddMilliseconds(50).Ticks} 'COLD_REPORT_CONTROLLED'

    # Исполняем настоящий journal builder, заменяя только два чтения cfg данными в памяти.
    $journalNodes=@($RunnerAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'New-ColdJournal'},$true))
    Assert-Contract ($journalNodes.Count -eq 1) 'JOURNAL_BUILDER_DEFINITION'
    $journalSource=$journalNodes[0].Extent.Text
    Assert-Contract ([regex]::Matches($journalSource,'\bRead-ColdConfig ').Count -eq 2) 'JOURNAL_CFG_READ_SEAM'
    . ([scriptblock]::Create($journalSource.Replace('Read-ColdConfig ','Get-ContractCfg ')))
    $cfgPaths=@('app/CashPrediction.cfg','app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg')
    $canonicalCfgPaths=@('app/CashPrediction-Swing.cfg','app/CashPrediction-Web.cfg','app/CashPrediction.cfg')
    $script:mockCfg=@{}
    foreach ($path in $cfgPaths) {
        $text="[Application]`r`napp.mainmodule=ru.example/ru.example.Main`r`n`r`n[JavaOptions]`r`njava-options=-Xmx512m`r`njava-options=--module-path`r`njava-options=`$APPDIR`r`n"
        $script:mockCfg[(Join-Path $root $path)]=$text
        $script:mockCfg[(Join-Path (Join-Path $root 'CashMemory/Updates/Ready/tree') $path)]=$text
    }
    function Get-ContractCfg([string]$Path) {
        if (-not $script:mockCfg.ContainsKey($Path)) {throw 'CONTRACT_CFG_MISSING'}
        return $script:mockCfg[$Path]
    }
    $cfgFiles=Copy-Contract $inventory
    foreach ($file in $cfgFiles) {$file.readOnly=($file.path -ceq 'app/CashPrediction-Swing.cfg')}
    $cfgBase=[pscustomobject]@{files=$cfgFiles;treeSha256=(Get-ColdTreeHash $cfgFiles)}
    $savedCulture=[Threading.Thread]::CurrentThread.CurrentCulture
    try {
        foreach ($culture in 'en-US','tr-TR') {
            [Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo($culture)
            $built=New-ColdJournal $root $cfgBase $journal.target
            $redirects=@($built.bootstrap.redirectFiles)
            Assert-Contract (Test-ColdInventoryEqual @($redirects.path) $canonicalCfgPaths) ('JOURNAL_REDIRECT_ORDER_'+$culture)
            Assert-ColdInventory $redirects (Get-ColdTreeHash $redirects)
            Assert-Contract ($built.schemaVersion -eq 2 -and $built.bootstrap.state -ceq 'INITIAL' -and
                (Test-ColdInventoryEqual $built.oldFiles $cfgBase.files) -and
                (Test-ColdInventoryEqual @($built.bootstrap.cfgTexts.path) $cfgPaths)) ('JOURNAL_ORDER_ONLY_'+$culture)
            foreach ($entry in $redirects) {
                $cfg=@($built.bootstrap.cfgTexts | Where-Object {$_.path -ceq $entry.path})[0]
                $bytes=[Text.Encoding]::UTF8.GetBytes($cfg.text)
                Assert-Contract ($entry.sizeBytes -eq $bytes.Length -and $entry.sha256 -ceq
                    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -and
                    $entry.readOnly -eq ($entry.path -ceq 'app/CashPrediction-Swing.cfg')) ('JOURNAL_REDIRECT_FIELDS_'+$culture+'_'+$entry.path)
                $sourceText=$script:mockCfg[(Join-Path $root $entry.path)]
                $expectedText=$sourceText.Replace("[Application]`r`n","[Application]`r`napp.runtime=`$ROOTDIR\CashMemory\Updates\Bootstrap\runtime`r`n").Replace(
                    'java-options=$APPDIR','java-options=$ROOTDIR\CashMemory\Updates\Bootstrap\app')
                Assert-Contract ($cfg.text -ceq $expectedText) ('JOURNAL_EXACT_RUNTIME_MODULE_REDIRECT_'+$culture+'_'+$entry.path)
            }
            # Legacy порядок и перестановка двух соседей отвергаются даже с пересчитанным digest.
            foreach ($badOrder in @(@(2,0,1),@(1,0,2))) {
                $unsorted=@($badOrder | ForEach-Object {$redirects[$_]})
                Test-ContractRejection ('journal-unsorted-'+$culture+'-'+($badOrder -join '-')) {
                    Assert-ColdInventory $unsorted (Get-ColdTreeHash $unsorted)
                } 'COLD_INVENTORY'
            }
        }
    } finally {[Threading.Thread]::CurrentThread.CurrentCulture=$savedCulture}

    # Независимые ожидаемые bytes для LF/CRLF и исходного runtime; это не исполнение Java или helper.
    foreach ($newline in @("`n","`r`n")) {
        foreach ($runtimeLine in @('',('app.runtime=$ROOTDIR/runtime'+$newline),('app.runtime=$ROOTDIR\runtime'+$newline))) {
            $original='[Application]'+$newline+$runtimeLine+'app.mainmodule=ru.example/ru.example.Main'+$newline+
                '[JavaOptions]'+$newline+'java-options=--module-path'+$newline+'java-options=$APPDIR'+$newline+'java-options=-Xmx512m'+$newline
            foreach ($path in $cfgPaths) {$script:mockCfg[(Join-Path $root $path)]=$original}
            $built=New-ColdJournal $root $cfgBase $journal.target
            $expected='[Application]'+$newline+'app.runtime=$ROOTDIR\CashMemory\Updates\Bootstrap\runtime'+$newline+
                'app.mainmodule=ru.example/ru.example.Main'+$newline+'[JavaOptions]'+$newline+'java-options=--module-path'+$newline+
                'java-options=$ROOTDIR\CashMemory\Updates\Bootstrap\app'+$newline+'java-options=-Xmx512m'+$newline
            foreach ($cfg in $built.bootstrap.cfgTexts) {Assert-Contract ($cfg.text -ceq $expected) 'JOURNAL_NEWLINES_RUNTIME_AND_MODULES'}
        }
    }
    $validCfg=$script:mockCfg[(Join-Path $root $cfgPaths[0])]
    $pair="java-options=--module-path`r`njava-options=`$APPDIR"
    foreach ($bad in @(
        $validCfg.Replace('java-options=$APPDIR','java-options=C:\foreign'),
        $validCfg.Replace('java-options=$APPDIR','java-options=$APPDIR\mods'),
        $validCfg.Replace('java-options=$APPDIR','java-options=$APPDIR;C:\foreign'),
        $validCfg.Replace('java-options=$APPDIR','java-options=..\foreign'),
        $validCfg.Replace('java-options=$APPDIR','java-options=\\server\share'),
        $validCfg.Replace($pair,$pair+"`r`n"+$pair),
        $validCfg.Replace($pair,'java-options=--module-path=$APPDIR'),
        $validCfg.Replace($pair,"java-options=-p`r`njava-options=`$APPDIR"),
        $validCfg.Replace($pair,"java-options=--module-path`r`n[Other]`r`njava-options=`$APPDIR"),
        $validCfg.Replace($pair,'java-options=--module-path'),
        $validCfg.Replace($pair,'java-options=$APPDIR'))) {
        foreach ($side in 'old','target') {
            $badPath=if ($side -ceq 'old') {(Join-Path $root $cfgPaths[0])} else {(Join-Path (Join-Path $root 'CashMemory/Updates/Ready/tree') $cfgPaths[0])}
            $saved=$script:mockCfg[$badPath]; $script:mockCfg[$badPath]=$bad
            try {Test-ContractRejection ('journal-bad-module-pair-'+$side) {New-ColdJournal $root $cfgBase $journal.target} 'COLD_CFG_MODULE_PATH'}
            finally {$script:mockCfg[$badPath]=$saved}
        }
    }
    Test-ContractRejection 'cfg-byte-limit' {Get-ColdMainModule ($validCfg+('x'*65536))} 'COLD_CFG_ENCODING'
    Test-ContractRejection 'cfg-mixed-cr' {Get-ColdMainModule ($validCfg+"`r#bad`r`n")} 'COLD_CFG_ENCODING'

    # Неполные роли и вложенный app/mods не превращаются в защищённый native образ даже с новым SHA.
    foreach ($variant in 'missing','empty','duplicate-core-missing-web','nested-mods','foreign-jar') {
        $files=Copy-Contract $inventory
        switch ($variant) {
            'missing' {$files=@($files | Where-Object {$_.path -cne 'app/cashprediction-web-1.0.0.jar'})}
            'empty' {($files | Where-Object {$_.path -ceq 'app/cashprediction-web-1.0.0.jar'}).sizeBytes=0L}
            'duplicate-core-missing-web' {($files | Where-Object {$_.path -ceq 'app/cashprediction-web-1.0.0.jar'}).path='app/cashprediction-core-2.0.0.jar'}
            'nested-mods' {($files | Where-Object {$_.path -ceq 'app/cashprediction-web-1.0.0.jar'}).path='app/mods/cashprediction-web-1.0.0.jar'}
            'foreign-jar' {($files | Where-Object {$_.path -ceq 'app/cashprediction-web-1.0.0.jar'}).path='app/foreign.jar'}
        }
        $files=@($files | Sort-Object {[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($_.path))})
        foreach ($side in 'old','target') {
            $badBase=Copy-Contract $cfgBase; $badTarget=Copy-Contract $journal.target
            $badManifest=if ($side -ceq 'old') {$badBase} else {$badTarget}
            $badManifest.files=$files; $badManifest.treeSha256=Get-ColdTreeHash $files
            Test-ContractRejection ('module-layout-'+$variant+'-'+$side) {New-ColdJournal $root $badBase $badTarget} 'COLD_INVENTORY'
        }
    }
    foreach ($file in $protected) {
        Assert-Contract (Test-ColdProtectedPayload $file.path) 'PAYLOAD_RUNTIME_OR_ROOT_ROLE'
        $copyJournal=Copy-Contract $journal
        $copyJournal.operations=@([pscustomobject]@{kind='BOOT_COPY';path=$file.path;state='BEFORE'})
        Assert-ColdProtectedEvidence $checkpoint $copyJournal
        $copyJournal.operations[0].state='AFTER'
        Assert-ColdProtectedEvidence $checkpoint $copyJournal
        Assert-Contract $true 'BOOT_COPY_BEFORE_AFTER_PROTECTED_OLD'
    }
    foreach ($path in 'app/CashPrediction.cfg','app/foreign.jar','app/mods/cashprediction-core-1.0.0.jar','app/cashprediction-core-9.0.jar','CashMemory/user.md') {
        $copyJournal=Copy-Contract $journal; $copyJournal.operations=@([pscustomobject]@{kind='BOOT_COPY';path=$path;state='AFTER'})
        Test-ContractRejection ('boot-copy-path-'+$path) {Assert-ColdProtectedEvidence $checkpoint $copyJournal} 'COLD_BOOT_COPY_PATH'
    }
    foreach ($variant in 'runtime-only','missing-jar','new-jar-hash','attribute','unverified-active') {
        $badReceipt=Copy-Contract $checkpoint
        switch ($variant) {
            'runtime-only' {$badReceipt.bootstrapFiles=@($badReceipt.bootstrapFiles | Where-Object {$_.path.StartsWith('runtime/')})}
            'missing-jar' {$badReceipt.bootstrapFiles=@($badReceipt.bootstrapFiles | Where-Object {$_.path -cne 'app/cashprediction-web-1.0.0.jar'})}
            'new-jar-hash' {($badReceipt.bootstrapFiles | Where-Object {$_.path -ceq 'app/cashprediction-core-1.0.0.jar'}).sha256='b'*64}
            'attribute' {$badReceipt.bootstrapFiles[0].readOnly=$true}
            'unverified-active' {$badReceipt.bootstrapVerified=$false; $badReceipt.bootstrapFiles=@()}
        }
        $badReceipt.bootstrapTreeSha256=Get-ColdTreeHash $badReceipt.bootstrapFiles
        Test-ContractRejection ('bootstrap-observation-'+$variant) {Assert-ColdProtectedEvidence $badReceipt $journal} 'COLD_BOOTSTRAP_PAYLOAD'
    }
    foreach ($cp in 'INITIAL','COPYING','PUBLISH_BEFORE','CLEANED') {
        $emptyReceipt=Copy-Contract $checkpoint; $emptyReceipt.checkpoint=$cp; $emptyReceipt.bootstrapVerified=$false
        $emptyReceipt.bootstrapFiles=@(); $emptyReceipt.bootstrapTreeSha256=Get-ColdTreeHash @()
        Assert-ColdProtectedEvidence $emptyReceipt $journal
        Assert-Contract $true 'UNPUBLISHED_OR_CLEANED_IS_NOT_VERIFIED'
    }

    # Полный OLD/NEW инвентарь: отсутствие файла, атрибут, размер и hash не скрываются.
    $script:mockInventory=$inventory
    $manifest=[pscustomobject]@{files=$inventory;treeSha256=$tree}
    [void](Assert-ColdTree $root $manifest)
    foreach ($field in 'path','sizeBytes','sha256','readOnly','missing','extra') {
        $script:mockInventory=Copy-Contract $inventory
        switch ($field) {
            'path' {$script:mockInventory[0].path='app/foreign'}
            'sizeBytes' {$script:mockInventory[0].sizeBytes++}
            'sha256' {$script:mockInventory[0].sha256='b'*64}
            'readOnly' {$script:mockInventory[0].readOnly=$true}
            'missing' {$script:mockInventory=@($script:mockInventory | Select-Object -Skip 1)}
            'extra' {$script:mockInventory+=@([pscustomobject]@{path='runtime/extra';sizeBytes=1L;sha256=('a'*64);readOnly=$false})}
        }
        Test-ContractRejection ('managed-'+$field) {Assert-ColdTree $root $manifest} 'COLD_MANAGED_TREE'
    }

    # Process содержит только mock-методы, никакой PID не открывается в ОС.
    function New-ContractProcess {
        $p=[pscustomobject]@{Id=731;Handle=[intptr]123;HasExited=$false;StartTime=$birth;
            MainModule=[pscustomobject]@{FileName=$exe};Modules=@([pscustomobject]@{ModuleName='jvm.dll';FileName=(Join-Path $root 'runtime/bin/server/jvm.dll')});
            MainWindowHandle=[intptr]123;MainWindowTitle='CashPrediction - mock';Kills=0;Disposals=0}
        $p | Add-Member ScriptMethod Kill {$this.Kills++;$this.HasExited=$true}
        $p | Add-Member ScriptMethod WaitForExit {param($Milliseconds) return $this.HasExited}
        $p | Add-Member ScriptMethod Dispose {$this.Disposals++}
        return $p
    }
    # Настоящий provider по-прежнему запрещает синтетический Process.
    $productionReceiptProvider=${function:Get-ColdProcessReceipt}
    $script:mockProcess=New-ContractProcess
    Test-ContractRejection 'production-receipt-rejects-mock' {Get-ColdProcessReceipt $script:mockProcess $root} 'COLD_PROCESS_TYPE'
    # Отдельная дочерняя область подменяет только получение квитанции для mock OS.
    # Проверки PID/birth/executable и методы cleanup исполняются из production AST.
    & {
        $mockReceiptRoot=$root
        # Provider принимает только текущий зарегистрированный mock по ссылке.
        # Эти данные никогда не выдаются за квитанцию настоящего retained Process.
        function Get-ColdProcessReceipt($Process,[string]$Root) {
            if (-not [object]::ReferenceEquals($Process,$script:mockProcess) -or $Root -cne $mockReceiptRoot -or
                $Process -is [Diagnostics.Process]) {throw 'CONTRACT_RECEIPT_MOCK_SCOPE'}
            return [pscustomobject]@{ProcessId=$Process.Id;StartedAtTicks=$Process.StartTime.ToUniversalTime().Ticks;
                ExecutablePath=$Process.MainModule.FileName;OwnedRoot=$Root}
        }
        $script:mockProcess=New-ContractProcess
        $unregistered=New-ContractProcess
        Test-ContractRejection 'mock-receipt-rejects-unregistered-process' {Get-ColdProcessReceipt $unregistered $root} 'CONTRACT_RECEIPT_MOCK_SCOPE'
        Test-ContractRejection 'mock-receipt-rejects-foreign-root' {Get-ColdProcessReceipt $script:mockProcess ($root+'-foreign')} 'CONTRACT_RECEIPT_MOCK_SCOPE'
    $script:mockProcess=New-ContractProcess
    $identity=Get-ColdProcessReceipt $script:mockProcess $root
    Stop-ColdRetainedProcess $script:mockProcess $identity $root
    Assert-Contract ($script:mockProcess.Kills -eq 1) 'RETAINED_KILL_POSITIVE'
    foreach ($field in 'Id','StartTime','MainModule') {
        $script:mockProcess=New-ContractProcess
        switch ($field) {'Id' {$script:mockProcess.Id++} 'StartTime' {$script:mockProcess.StartTime=$birth.AddTicks(10)} 'MainModule' {$script:mockProcess.MainModule.FileName=$powerShell}}
        Test-ContractRejection ('retained-kill-'+$field) {Stop-ColdRetainedProcess $script:mockProcess $identity $root} 'COLD_PROCESS_IDENTITY'
        Assert-Contract ($script:mockProcess.Kills -eq 0) ('NO_KILL_'+$field)
    }

    # UI receipt: PID reuse, старый процесс, отсутствие окна/lease/JVM не принимаются.
    foreach ($case in 'baseline','pid-reuse','old-process','no-window','no-lease','no-jvm','foreign-jvm','wrong-client-exe') {
        Reset-ContractArtifacts
        $script:mockProcess=New-ContractProcess
        $script:mockObserved=@([pscustomobject]@{ProcessId=731;CreationDate=$birth;ExecutablePath=$exe;CommandLine=$ui.commandLine})
        switch ($case) {
            'pid-reuse' {$script:mockObserved[0].CreationDate=$birth.AddTicks(10)}
            'old-process' {$script:mockObserved[0].CreationDate=$birth.AddDays(-1)}
            'no-window' {$script:mockProcess.MainWindowHandle=[intptr]::Zero}
            'no-lease' {$script:mockArtifacts.Remove($script:mockLeasePath)}
            'no-jvm' {$script:mockProcess.Modules=@()}
            'foreign-jvm' {$script:mockProcess.Modules[0].FileName='C:\foreign\jvm.dll'}
            'wrong-client-exe' {$script:mockProcess.MainModule.FileName=Join-Path $root 'CashPrediction-Swing.exe'}
        }
        if ($case -eq 'foreign-jvm') {Test-ContractRejection 'ui-foreign-jvm' {Get-ColdUiReceipt $root 'fx' $birth.AddSeconds(-1)} 'COLD_FOREIGN_JVM';continue}
        if ($case -eq 'no-lease') {
            # Mock directory остаётся, но не содержит lease файлов.
            function Get-ChildItem {return @()}
        } else {
            function Get-ChildItem {return @([pscustomobject]@{FullName=$script:mockLeasePath})}
        }
        $result=Get-ColdUiReceipt $root 'fx' $birth.AddSeconds(-1)
        if ($case -eq 'baseline') {Assert-Contract ($null -ne $result) 'UI_MOCK_BASELINE'}
        elseif ($null -ne $result) {$script:contractGaps.Add('ui-'+$case);Write-Output ('GAP ui-'+$case)}
        else {Assert-Contract $true ('UI_REJECT_'+$case)}
        Assert-Contract ($script:mockProcess.Disposals -eq $(if ($case -eq 'old-process') {0} else {1})) ('UI_DISPOSE_'+$case)
    }

    # HTTP - только синтетический ответ; чужой listener или страница не дают witness.
    foreach ($case in 'baseline','foreign-listener','no-listener','wrong-status','wrong-page') {
        Reset-ContractArtifacts
        $script:mockProcess=New-ContractProcess
        $script:mockProcess.MainModule.FileName=Join-Path $root 'CashPrediction-Web.exe'
        $script:mockObserved=@([pscustomobject]@{ProcessId=731;CreationDate=$birth;ExecutablePath=$script:mockProcess.MainModule.FileName;
            CommandLine=('"'+$script:mockProcess.MainModule.FileName+'" --home "'+$root+'" --no-browser --no-window --updated-from '+('b'*40))})
        $webLease=Copy-Contract $lease; $webLease.client='web'; Set-ContractArtifact $script:mockLeasePath $webLease
        $script:mockListeners=@([pscustomobject]@{OwningProcess=731;LocalAddress='127.0.0.1';LocalPort=12345})
        $script:mockResponse=[pscustomobject]@{StatusCode=200;Content='<title>CashPrediction mock</title>'}
        $script:mockHttpCalls=0
        function Invoke-WebRequest {param($Uri,$TimeoutSec,[switch]$UseBasicParsing) $script:mockHttpCalls++;return $script:mockResponse}
        switch ($case) {
            'foreign-listener' {$script:mockListeners[0].OwningProcess=999}
            'no-listener' {$script:mockListeners=@()}
            'wrong-status' {$script:mockResponse.StatusCode=503}
            'wrong-page' {$script:mockResponse.Content='<title>Foreign</title>'}
        }
        $result=Get-ColdUiReceipt $root 'web' $birth.AddSeconds(-1)
        Assert-Contract ($(if ($case -eq 'baseline') {$null -ne $result} else {$null -eq $result})) ('HTTP_'+$case)
        if ($case -in @('foreign-listener','no-listener')) {Assert-Contract ($script:mockHttpCalls -eq 0) ('HTTP_NOT_CALLED_'+$case)}
    }

    # Cleanup helper: наблюдаемый executable меняется после CIM без смены birth time.
    $script:mockProcess=New-ContractProcess
    $command="& '"+(Join-Path $root 'CashMemory/Updates/apply-update.ps1').Replace("'","''")+"' -InstallationRoot '"+$root.Replace("'","''")+"'"
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $script:mockObserved=@([pscustomobject]@{ProcessId=731;CreationDate=$birth;ExecutablePath=$powerShell;CommandLine='powershell -EncodedCommand '+$encoded})
    $script:mockProcess.StartTime=$birth.AddTicks(10)
    Test-ContractRejection 'recovery-helper-pid-reuse' {Stop-ColdRecoveryHelpers $root $powerShell $birth.AddSeconds(-1)} 'COLD_HELPER_PID_REUSED'
    Assert-Contract ($script:mockProcess.Kills -eq 0) 'NO_RECOVERY_PID_REUSE_KILL'
    $script:mockProcess=New-ContractProcess
    # После одной итерации CIM становится пустым; fixture не ждёт и не убивает ОС.
    function Get-CimInstance {$items=$script:mockObserved;$script:mockObserved=@();return $items}
    Test-ContractRejection 'recovery-helper-retained-foreign-exe' {Stop-ColdRecoveryHelpers $root $powerShell $birth.AddSeconds(-1)} 'COLD_PROCESS_IDENTITY'
    Assert-Contract ($script:mockProcess.Kills -eq 0) 'NO_FOREIGN_HELPER_KILL'

    # Origin allowlist отвергает верный encoded suffix с посторонними flags/другим exe.
    $helperArgv=@($powerShell,'-NoProfile','-NonInteractive','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded)
    $helperLine=($helperArgv | ForEach-Object {'"'+$_+'"'}) -join ' '
    Assert-ColdHelperCommand $helperLine $powerShell $encoded
    Assert-Contract $true 'HELPER_ORIGIN_POSITIVE'
    foreach ($line in @($helperLine+' -Command evil',$helperLine.Replace($powerShell,$exe),$helperLine.Replace($encoded,'foreign'))) {
        Test-ContractRejection 'helper-origin-reject' {Assert-ColdHelperCommand $line $powerShell $encoded} 'COLD_PROCESS_IDENTITY'
    }
    function Get-CimInstance {return $script:mockObserved}
    $script:mockProcess=New-ContractProcess
    $observed=[pscustomobject]@{ProcessId=731;CreationDate=$birth;ExecutablePath=$exe;CommandLine=$ui.commandLine}
    $script:mockObserved=@((Copy-Contract $observed))
    $script:mockObserved[0].CreationDate=$birth
    $script:mockObserved[0].CommandLine+=' --foreign'
    Test-ContractRejection 'origin-command-changed' {Get-ColdCurrentProcess $observed $script:mockProcess $exe} 'COLD_PROCESS_IDENTITY'
    Assert-Contract ($script:mockProcess.Kills -eq 0) 'ORIGIN_CHANGE_NO_KILL'
    $actual=Copy-Contract $identity;$actual.OwnedRoot=$root+'-foreign'
    Test-ContractRejection 'process-foreign-owned-root' {Assert-ColdProcessIdentity $identity $actual $root $exe} 'COLD_PROCESS_IDENTITY'

    # Ранний UI проверяется реальным observer с mock OS, ещё без какой-либо lease.
    function Get-ColdControlledInventory {return @()}
    $activeJournal=Join-Path $root 'CashMemory/Updates/install-journal.json'
    foreach ($case in 'none','window','listener') {
        $script:mockProcess=New-ContractProcess;$script:mockProcess.MainWindowHandle=[intptr]::Zero
        $script:mockObserved=@([pscustomobject]@{ProcessId=731;CreationDate=$birth;ExecutablePath=$exe;CommandLine=$ui.commandLine})
        $script:mockListeners=@()
        Set-ContractArtifact $activeJournal ([pscustomobject]@{active=$true})
        if ($case -eq 'window') {$script:mockProcess.MainWindowHandle=[intptr]123}
        if ($case -eq 'listener') {$script:mockListeners=@([pscustomobject]@{OwningProcess=731})}
        if ($case -eq 'none') {
            $sample=Get-ColdRecoveryObservation $root $birth.AddSeconds(-1)
            Assert-Contract ($sample.journalBefore -and $sample.journalAfter -and $sample.visibleCount -eq 0) 'LIVE_ACTIVE_NO_UI'
        } else {Test-ContractRejection ('live-active-'+$case) {Get-ColdRecoveryObservation $root $birth.AddSeconds(-1)} 'COLD_UI_WHILE_RECOVERY_ACTIVE'}
        Assert-Contract ($script:mockProcess.Kills -eq 0 -and $script:mockProcess.Disposals -eq 1) ('EARLY_UI_NO_KILL_'+$case)
    }
    $script:mockArtifacts.Remove($activeJournal)
    # Cleanup собственной копии при подмене retained path запрещён до Kill.
    $WorkDir=[IO.Path]::GetDirectoryName($root)
    $script:mockProcess=New-ContractProcess;$script:mockProcess.MainModule.FileName=$root+'-foreign.exe'
    $script:mockObserved=@([pscustomobject]@{ProcessId=731;CreationDate=$birth;ExecutablePath=$exe;CommandLine=$ui.commandLine})
    Test-ContractRejection 'copy-cleanup-retained-foreign-path' {Stop-ColdCopyProcesses $root} 'COLD_PROCESS_IDENTITY'
    Assert-Contract ($script:mockProcess.Kills -eq 0) 'COPY_CLEANUP_NO_FOREIGN_KILL'
    }
    # Provider не выходит за границу mock-тестов; production guard снова доступен.
    Assert-Contract (${function:Get-ColdProcessReceipt}.ToString() -ceq $productionReceiptProvider.ToString()) 'PRODUCTION_RECEIPT_PROVIDER_RESTORED'
    Test-ContractRejection 'production-receipt-rejects-mock-after-scope' {Get-ColdProcessReceipt $script:mockProcess $root} 'COLD_PROCESS_TYPE'

    # Исполняется только AST участка построения плана, с allowlist команд.
    $text=$RunnerAst.Extent.Text
    $start=$text.IndexOf('$plan=@();');$end=$text.IndexOf('if ($plan.Count -gt $MaxCells)')
    Assert-Contract ($start -ge 0 -and $end -gt $start) 'PLAN_SLICE'
    $planText=$text.Substring($start,$end-$start)
    $localTokens=$null;$localErrors=$null
    $planAst=[Management.Automation.Language.Parser]::ParseInput($planText,[ref]$localTokens,[ref]$localErrors)
    Assert-Contract ($localErrors.Count -eq 0) 'PLAN_PARSE'
    foreach ($commandAst in @($planAst.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst]},$true))) {
        Assert-Contract ($commandAst.GetCommandName() -ceq 'Get-ColdCheckpoints') 'PLAN_COMMAND_ALLOWLIST'
    }
    foreach ($baseCount in 1,2) {
        $bases=@(@('B1','B2') | Select-Object -First $baseCount)
        . ([scriptblock]::Create($planText))
        Assert-Contract ($plan.Count -eq 198*$baseCount -and $rows.Count -eq $plan.Count) 'PLAN_198_PER_BASE'
        Assert-Contract (@($plan.key | Sort-Object -Unique).Count -eq $plan.Count) 'PLAN_UNIQUE'
        $requiredCheckpoints=@('INITIAL','COPYING','PUBLISH_BEFORE','PUBLISH_AFTER','COPIED','ACTIVE',
            'BACKING_UP','JLI_GAP','JVM_GAP','MODULES_GAP','INSTALLING','NATIVE_FX_REPLACE','NATIVE_SWING_REPLACE','NATIVE_WEB_REPLACE',
            'VERIFYING','CFG_FX_SWITCH','CFG_SWING_SWITCH','CFG_WEB_SWITCH','RESTORED','COMMITTED','CLEANED','ROLLING_BACK')
        $requiredKeys=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        for ($b=1;$b -le $baseCount;$b++) {foreach ($cp in $requiredCheckpoints) {foreach ($client in 'fx','swing','web') {foreach ($variant in 'ascii','cyrillic','unicode') {
            [void]$requiredKeys.Add("B$b/$cp/$client/$variant")
        }}}}
        Assert-Contract ($requiredKeys.SetEquals([string[]]$plan.key)) 'PLAN_EXACT_22_3_3'
        Assert-Contract (@($rows | Where-Object {$_.status -cne 'PENDING' -or $_.nativeExecuted}).Count -eq 0) 'PLAN_NO_TEMPLATE_PASS'
        Test-ContractRejection ('pending-full-plan-'+$baseCount) {Assert-ColdMatrix @($rows.ToArray()) $plan} 'COLD_REPORT_PENDING_OR_FAILED'
    }

    # Статический порядок не доказывает отсутствие раннего UI или точные restart args.
    $cellStart=$text.IndexOf('foreach ($cell in $plan)')
    $cell=$text.Substring($cellStart)
    $ordered=@('Assert-ColdCheckpoint $checkpoint','$checkpoint.journalSha256','Stop-ColdRetainedProcess $helperProcess',
        '$helperExit=$helperProcess.ExitCode','$launcher=Start-ColdProcess $nativePath $nativeArgs',
        '$ui=Get-ColdUiReceipt','Assert-ColdTree $root $chosen',"`$row.status='PASS'")
    $previous=-1
    foreach ($needle in $ordered) {$position=$cell.IndexOf($needle);Assert-Contract ($position -gt $previous) ('CELL_ORDER_'+$needle);$previous=$position}
    Assert-Contract ($cell.Contains("'--no-browser','--no-window'")) 'WEB_ORDINARY_ARGS'
    Assert-Contract ($cell.Contains("if (-not (Test-Path -LiteralPath (Join-Path `$updates 'install-journal.json')))")) 'UI_OBSERVED_AFTER_JOURNAL_REMOVAL'
    Write-Output ("RUNNER_CONTRACT_AUDIT checks=$script:contractChecks gaps=$($script:contractGaps.Count) nativeMatrix=PENDING EXE/JVM/GUI/Maven=NOT_EXECUTED")
    if ($script:contractGaps.Count) {throw ('RUNNER_CONTRACT_READINESS_GAPS: '+($script:contractGaps -join ', '))}
} $ast $formatText $formatPath
