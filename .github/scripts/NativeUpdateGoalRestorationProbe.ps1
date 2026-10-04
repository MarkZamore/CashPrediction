<#
.SYNOPSIS
Opt-in controlled GOAL seed и отдельный post-transaction probe; frozen функции не заменяются.
.DESCRIPTION
MAIN импортирует frozen collector/observer/guards. Генераторы возвращают новый in-memory
callsite только по SHA. Debug flags относятся лишь к отдельному новому запуску после cleanup.
Нет automatic restart PASS, env bypass, UI из XML или изменения canonical acceptance.
#>

# Новый bridge сохраняет default source; только выбранный клиент получает controlled окно.
function Get-NativeGoalPhaseBridgeSource {
    $source=Get-NativePhaseSessionBridgeSource
    $anchors=@(
        @{old='store.markDirty(SessionMarker.running(0,stamp,client).closed());';new='store.markDirty(client.equals(args[2])?SessionMarker.running(0,stamp,client):SessionMarker.running(0,stamp,client).closed());'},
        @{old='store.save(SessionSnapshot.of(stamp,client,main,planState,List.of()));';new='store.save(SessionSnapshot.of(stamp,client,main,planState,client.equals(args[2])?List.of(goalWindow()):List.of()));'},
        @{old='} else if (!args[0].equals("read"))';new='''REARM'''},
        @{old='entry.put("client",client);';new='entry.put("client",client); entry.put("needsRestore",CrashDetector.detect(List.of(store),client).status().name()); entry.put("windowExpectation",expected(snapshot));'}
    )
    $rearm=@'
} else if (args[0].equals("rearm")) {
            SessionStore selected=store(memory,args[2]);
            SessionSnapshot snapshot=selected.load().orElseThrow();
            if (snapshot.windows().size()!=1) throw new IllegalArgumentException("GOAL_PROBE_WINDOW_COUNT");
            WindowState window=snapshot.windows().get(0);
            WindowState goal=goalWindow();
            if (window.type()!=goal.type() || window.modal() || !window.ownerId().equals("main") ||
                !window.fields().equals(goal.fields()) || !window.context().equals(goal.context()))
                throw new IllegalArgumentException("GOAL_PROBE_PRESERVED_VALUES");
            // Это явное повторное вооружение отдельного probe, не сохранённый маркер transaction.
            selected.markDirty(SessionMarker.running(0,Instant.now(),args[2]));
            if (selected.lastError().isPresent()) throw new java.io.IOException("GOAL_PROBE_REARM_WRITE");
            if (!selected.load().orElseThrow().equals(snapshot)) throw new IllegalArgumentException("GOAL_PROBE_SNAPSHOT_CHANGED");
        } else if (!args[0].equals("read"))
'@
    foreach ($anchor in $anchors) {
        if ([regex]::Matches($source,[regex]::Escape($anchor.old)).Count -ne 1) {throw 'GOAL_BRIDGE_ANCHOR'}
        $replacement=if ($anchor.new -ceq "'REARM'") {$rearm} else {$anchor.new}
        $source=$source.Replace($anchor.old,$replacement)
    }
    $source=$source.Replace('System.out.println(JsonWriter.write(out));','new java.io.PrintStream(System.out,true,java.nio.charset.StandardCharsets.UTF_8).println(JsonWriter.write(out));')
    $methods=@'
    /** Стабильный порядок JSON обязателен для межпроцессных byte pins и frozen сравнения. */
    private static Map<String,Object> ordered(Object... pairs) {
        var map=new LinkedHashMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],pairs[i+1]);
        return map;
    }
    /** Четыре непустых недефолтных значения, известный владелец и сохраняемый контекст. */
    private static WindowState goalWindow() {
        var fields=new LinkedHashMap<String,String>();
        var values=Map.of("target","450731,00","byDateEnabled","true","byDate","2031-07-31","extraSaving","1731,00");
        for(String id:WindowType.GOAL_CALCULATOR.fieldIds()) fields.put(id,values.get(id));
        return new WindowState("seedGoal731",WindowType.GOAL_CALCULATOR,false,"main",null,
            new TreeMap<>(Map.of("page","0","probeToken","phase-goal-731")),fields);
    }
    /** Ожидания получаются через настоящий FormSpec/FieldCodec, не через мнимую отрисовку. */
    private static Object expected(SessionSnapshot snapshot) throws Exception {
        var windows=new ArrayList<Object>();
        for (WindowState window:snapshot.windows()) {
            if (window.type()!=WindowType.GOAL_CALCULATOR) throw new IllegalArgumentException("GOAL_PROBE_TYPE");
            // JavaFX: Dialog → Swing: JDialog → Web: dialog.
            var spec=new ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm().spec(null);
            var fields=new ArrayList<Object>();
            for (var page:spec.pages()) for(var row:page.rows()) {
                var specs=row instanceof ru.cashprediction.core.ui.form.FormRow.Field f?List.of(f.field()):
                    row instanceof ru.cashprediction.core.ui.form.FormRow.Inline i?i.fields():List.<ru.cashprediction.core.ui.form.FieldSpec>of();
                for(var field:specs) {
                    if(!window.fields().containsKey(field.id())) throw new IllegalArgumentException("GOAL_PROBE_FIELD");
                    fields.add(ordered("id",field.id(),"kind",field.kind().name(),"text",
                        ru.cashprediction.core.ui.form.FieldCodec.display(field.kind(),window.fields().get(field.id()))));
                }
            }
            windows.add(ordered("id",window.id(),"type",window.type().name(),"coreFormSpecId",spec.formId(),
                "purpose",spec.purpose(),"modal",window.modal(),"ownerId",window.ownerId(),"page",0,
                "context",window.context(),"canonicalFields",window.fields(),"fields",fields));
        }
        String json=new JsonSnapshotCodec().encode(snapshot);
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return ordered("client",snapshot.client(),"seedSavedAt",snapshot.savedAt().toString(),"seedRevisionSha256",sha,"windows",windows);
    }
'@
    $position=$source.LastIndexOf('}')
    return $source.Insert($position,$methods)
}

# Генерирует отдельный Start, сохраняя frozen default Start/bridge и порядок seed -> baseline.
function Get-NativeGoalCollectorStartSource([string]$Collector,[string]$Sha256) {
    if ($Sha256 -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $Collector).Hash.ToLowerInvariant() -cne $Sha256) {throw 'GOAL_COLLECTOR_PIN'}
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Collector,[ref]$tokens,[ref]$errors)
    $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Start-NativePhaseSessionCollector'},$true))
    if ($errors.Count -or $nodes.Count -ne 1) {throw 'GOAL_COLLECTOR_AST'}
    $body=$nodes[0].Extent.Text.Replace('function Start-NativePhaseSessionCollector(', 'function Start-NativeGoalPhaseSessionCollector(')
    $body=$body.Replace('(Get-NativePhaseSessionBridgeSource)','(Get-NativeGoalPhaseBridgeSource)')
    $anchor='$context.seed=Invoke-NativePhaseSessionCodec $context ''seed'' $Root ''seed'''
    if ([regex]::Matches($body,[regex]::Escape($anchor)).Count -ne 1) {throw 'GOAL_COLLECTOR_SEED_ANCHOR'}
    $body=$body.Replace($anchor,$anchor+"`n    if (`$context.seed.decoded.sessions.(`$context.client).needsRestore -cne 'CRASHED') {throw 'GOAL_SEED_NOT_RESTORABLE'}`n    Write-ColdJson (Join-Path `$context.directory 'goal-expected.json') `$context.seed.decoded.sessions.(`$context.client).windowExpectation")
    return $body
}

# Отдельная opt-in функция producer; default dispatcher и исходная функция неизменны.
function New-NativeGoalPhaseHookSource([string]$Producer,[string]$ProducerSha256) {
    $body=New-NativePhaseSessionHookSource $Producer $ProducerSha256
    $anchor='$phaseSession=Start-NativePhaseSessionCollector $Row $root $cellEvidence $Java $Cold'
    if ([regex]::Matches($body,[regex]::Escape($anchor)).Count -ne 1) {throw 'GOAL_PRODUCER_ANCHOR'}
    return $body.Replace($anchor,'$phaseSession=Start-NativeGoalPhaseSessionCollector $Row $root $cellEvidence $Java $Cold; $script:nativeGoalPhaseContext=$phaseSession').Replace(
        'function Invoke-NativePhaseScenarioWithSessionCollector(', 'function Invoke-NativeGoalPhaseScenario(')
}

# Проверяет сохранённые значения, не объявляя byte-equality изменённого owned snapshot.
function Assert-NativeGoalTransactionValues($Before,$After,[string]$Client) {
    if ($Before.decoded.settingsCanonical -cne $After.decoded.settingsCanonical -or
        $Before.decoded.planCanonical -cne $After.decoded.planCanonical) {throw 'GOAL_DOMAIN_CHANGED'}
    foreach ($foreign in @('fx','swing','web') | Where-Object {$_ -cne $Client}) {
        if (-not (Test-ColdInventoryEqual $Before.decoded.sessions.$foreign $After.decoded.sessions.$foreign)) {throw 'GOAL_FOREIGN_SESSION_CHANGED'}
    }
    $old=$Before.decoded.sessions.$Client.windowExpectation;$new=$After.decoded.sessions.$Client.windowExpectation
    if (@($old.windows).Count -ne 1 -or @($new.windows).Count -ne 1) {throw 'GOAL_TRANSACTION_WINDOW_LOST'}
    foreach ($key in 'type','coreFormSpecId','purpose','modal','ownerId','page','context','canonicalFields','fields') {
        if (-not (Test-ColdInventoryEqual $old.windows[0].$key $new.windows[0].$key)) {throw ('GOAL_TRANSACTION_CHANGED:'+ $key)}
    }
}

# Новый post-transaction context: требует independent завершённый phase receipt и отсутствие helper.
# Явное rearm меняет только маркер selected store; до/после bytes сохраняются отдельно.
function New-NativeGoalPostTransactionProbe($PhaseContext,[string]$PhaseReceipt,[string]$PhaseReceiptSha256) {
    Assert-NativePhaseSessionScope $PhaseContext.root (Split-Path -Parent $PhaseContext.directory)
    $bytes=Read-RestoredWindowBytes $PhaseReceipt $PhaseContext.directory $PhaseReceiptSha256
    $terminal=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString($bytes))
    if ($terminal.root -cne $PhaseContext.root -or $terminal.client -cne $PhaseContext.client -or -not $terminal.finishedAt -or
        (Get-ColdUtcTicks $terminal.finishedAt) -gt [datetime]::UtcNow.Ticks -or @(Get-CopyProcesses $PhaseContext.root).Count) {throw 'GOAL_TRANSACTION_NOT_CLEANED'}
    if (@($terminal.cleanup.processesBeforeCodec).Count -or @($terminal.cleanup.processes).Count -or
        $terminal.cleanup.registryUnchanged -isnot [bool] -or -not $terminal.cleanup.registryUnchanged -or
        -not (Test-ColdInventoryEqual $terminal.seed $PhaseContext.seed)) {throw 'GOAL_TRANSACTION_RECEIPT'}
    foreach ($name in 'install-journal.json','Ready') {
        if (Test-Path -LiteralPath (Join-Path $PhaseContext.root ('CashMemory/Updates/'+$name))) {throw 'GOAL_TRANSACTION_NOT_TERMINAL'}
    }
    $directory=Join-Path $PhaseContext.directory 'post-transaction-goal'
    if (Test-Path -LiteralPath $directory) {throw 'GOAL_PROBE_EXISTS'}
    [void][IO.Directory]::CreateDirectory($directory)
    foreach ($pin in @(@{path=$PhaseContext.java;sha=$PhaseContext.javaSha},@{path=$PhaseContext.core;sha=$PhaseContext.coreSha},
        @{path=$PhaseContext.source;sha=$PhaseContext.sourceSha})) {
        if ((Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha) {throw 'GOAL_POST_PIN'}
    }
    $core=Join-Path $directory 'phase-core.jar';$source=Join-Path $directory 'NativePhaseSessionBridge.java'
    [IO.File]::Copy($PhaseContext.core,$core,$false);[IO.File]::Copy($PhaseContext.source,$source,$false)
    $context=[pscustomobject]@{root=$PhaseContext.root;client=$PhaseContext.client;directory=$directory;java=$PhaseContext.java;javaSha=$PhaseContext.javaSha;
        core=$core;coreSha=$PhaseContext.coreSha;source=$source;sourceSha=$PhaseContext.sourceSha;installedAt=[datetime]::UtcNow.ToString('o');
        registryBefore=(Get-PortableRealRegistrySnapshot);observations=[Collections.Generic.List[object]]::new();seed=$PhaseContext.seed}
    $pre=Save-NativePhaseSessionObservation $context 'transaction-bytes'
    Assert-NativeGoalTransactionValues $PhaseContext.seed $pre.codec $context.client
    $originalBaseline=@($terminal.observations | Where-Object stage -CEQ 'baseline')
    if ($originalBaseline.Count -ne 1) {throw 'GOAL_TRANSACTION_BASELINE'}
    $mutable=if ($context.client -ceq 'web') {@('CashMemory/web-session.md','CashMemory/web-session.plan.md')} else {@('CashMemory/session-'+$context.client+'.xml')}
    if (-not (Test-ColdInventoryEqual @($originalBaseline[0].files | Where-Object {$_.path -cnotin $mutable}) `
        @($pre.files | Where-Object {$_.path -cnotin $mutable}))) {throw 'GOAL_TRANSACTION_BYTES_CHANGED'}
    $rearmed=Invoke-NativePhaseSessionCodec $context 'rearm' $context.root 'probe-rearm'
    Assert-NativeGoalTransactionValues $pre.codec $rearmed $context.client
    if ($rearmed.decoded.sessions.($context.client).needsRestore -cne 'CRASHED') {throw 'GOAL_PROBE_NOT_RESTORABLE'}
    $context.seed=$rearmed
    $baseline=Save-NativePhaseSessionObservation $context 'baseline'
    if (-not (Test-ColdInventoryEqual $pre.codec.decoded.sessions.($context.client).snapshotJson `
        $baseline.codec.decoded.sessions.($context.client).snapshotJson) -or
        -not (Test-ColdInventoryEqual @($pre.files | Where-Object {$_.path -cnotin $mutable}) `
        @($baseline.files | Where-Object {$_.path -cnotin $mutable}))) {throw 'GOAL_REARM_CONTENT_CHANGED'}
    $expected=Join-Path $directory 'goal-expected.json';Write-ColdJson $expected $rearmed.decoded.sessions.($context.client).windowExpectation
    # Journal предыдущей транзакции не обходится: probe вооружён лишь после его фактического удаления.
    $observer=New-NativeRestoredWindowObserver $context $expected ((Get-FileHash -LiteralPath $expected).Hash.ToLowerInvariant())
    Write-ColdJson (Join-Path $directory 'probe-provenance.json') ([ordered]@{scope='EXPLICIT_POST_TRANSACTION_RESTORATION_PROBE';
        phaseReceipt=$PhaseReceipt;phaseReceiptSha256=$PhaseReceiptSha256;phaseFinishedAt=$terminal.finishedAt;newEpoch=$context.installedAt;
        originalSeed=$PhaseContext.seed;preHelperBaseline=$originalBaseline[0];
        postTransactionBytes=$pre.files;probeBaselineBytes=$baseline.files;markerRearmed=$true;automaticRestartProof=$false;nativePass=$false})
    $node='ru/cashprediction/selftest/'+[guid]::NewGuid().ToString()
    $arguments=@('--home',$context.root,'--registry-node',$node,'--selftest',$observer.script,
        '--selftest-out',$observer.output,'--selftest-recovery','xml','--test-api')
    if ($context.client -ceq 'web') {$arguments+=@('--no-browser','--no-window')}
    return [pscustomobject]@{context=$context;observer=$observer;arguments=$arguments;registryNode=$node;native=$null;
        managedTreeSha256=(Get-ColdTreeHash @(Get-ColdManagedInventory $context.root));
        scope='EXPLICIT_POST_TRANSACTION_RESTORATION_PROBE';nativePass=$false}
}

# MAIN вызывает только после своей очереди. Обычный новый EXE launch, без updater env/test seam.
# Frozen Connect-NativeClient не подходит: его exact args относятся к прежнему lifecycle.
function Start-NativeGoalPostTransactionClient($Probe,[int]$Timeout=60,[scriptblock]$WebBridgeAttach=$null) {
    if ($Timeout -lt 10 -or $Timeout -gt 180 -or @(Get-CopyProcesses $Probe.context.root).Count -or
        (Test-Path -LiteralPath (Join-Path $Probe.context.root 'CashMemory/Updates/install-journal.json'))) {throw 'GOAL_PROBE_LAUNCH_GUARD'}
    if ((Get-ColdTreeHash @(Get-ColdManagedInventory $Probe.context.root)) -cne $Probe.managedTreeSha256) {throw 'GOAL_PROBE_TREE_CHANGED'}
    Set-NativeRestoredWindowLaunchEpoch $Probe.observer
    $native=Start-NativeOwned (Join-Path $Probe.context.root (Get-ColdLauncherName $Probe.context.client)) $Probe.arguments $Probe.context.root -Web:($Probe.context.client -ceq 'web')
    # Сохраняем retained object до любого ожидания: MAIN finally обязан очистить его и при ошибке.
    $Probe | Add-Member native $native -Force
    Write-ColdJson (Join-Path $Probe.context.directory 'explicit-launch.json') ([ordered]@{identity=$native.identity;
        arguments=$Probe.arguments;launchAt=$Probe.observer.launchAt;scope=$Probe.scope;automaticRestartProof=$false})
    if ($native.web) {
        $native.lineTask=$native.process.StandardOutput.ReadLineAsync()
        Wait-NativeCondition {
            if ($native.lineTask.IsCompleted) {
                $line=$native.lineTask.GetAwaiter().GetResult()
                if ($null -eq $line) {throw 'GOAL_WEB_EOF'}
                $native.prefix+=$line+"`n";if ($native.prefix.Length -gt 1048576) {throw 'GOAL_WEB_OUTPUT_LIMIT'}
                if ($line -cmatch '^PARITY_URL (http://127\.0\.0\.1:[0-9]+/\?t=[A-Za-z0-9_-]+)$') {$native.webUrl=$Matches[1];return $true}
                $native.lineTask=$native.process.StandardOutput.ReadLineAsync()
            }
            return $false
        } $Timeout 'GOAL_WEB_URL_TIMEOUT'
        $native.stdout=$native.process.StandardOutput.ReadToEndAsync()
        # Actual DOM attach должен предшествовать UI-ready: server bootstrap может ждать браузер.
        if ($null -ne $WebBridgeAttach) {& $WebBridgeAttach $Probe $native | Out-Null}
    }
    Wait-NativeCondition {
        $native.ui=Get-ColdUiReceipt $Probe.context.root $Probe.context.client ([datetime]$Probe.observer.launchAt)
        return $null -ne $native.ui
    } $Timeout 'GOAL_PROBE_UI_TIMEOUT'
    Assert-ColdUiReceipt $native.ui $Probe.context.root $Probe.context.client
    if (-not (Test-ColdInventoryEqual @($native.ui.args) $Probe.arguments)) {throw 'GOAL_PROBE_ARGS_CHANGED'}
    $native.uiProcess=Open-PortableProcess ([int]$native.ui.pid)
    $identity=[pscustomobject]@{ProcessId=$native.ui.pid;StartedAtTicks=$native.ui.startedAtTicks;ExecutablePath=$native.ui.executablePath;OwnedRoot=$Probe.context.root}
    Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $native.uiProcess $Probe.context.root) $Probe.context.root $identity.ExecutablePath
    if ($native.web -and ([uri]$native.webUrl).Port -ne $native.ui.witness.port) {throw 'GOAL_WEB_OWNER'}
    return $native
}

# После фактического shot/signal, до cleanup: новый recovery.json принадлежит только probe epoch.
function Read-NativeGoalPostTransactionObservation($Probe,$BrowserProcess=$null,[string]$BrowserOwnerFile='') {
    if ((Get-ColdTreeHash @(Get-ColdManagedInventory $Probe.context.root)) -cne $Probe.managedTreeSha256) {throw 'GOAL_PROBE_TREE_CHANGED'}
    if ($null -eq $Probe.native -or $null -eq $Probe.native.ui) {return New-RestoredWindowDecision 'PENDING' @('ACTUAL_PROBE_UI_MISSING')}
    $signal=Join-Path $Probe.observer.output 'observed'
    if (-not (Test-Path -LiteralPath $signal)) {return New-RestoredWindowDecision 'PENDING' @('ACTUAL_SHOT_SIGNAL_MISSING')}
    $fresh=Save-NativePhaseSessionObservation $Probe.context 'recovery' $Probe.native.ui $Probe.native.uiProcess
    $file=Join-Path $Probe.context.directory 'recovery.json'
    return Read-NativeRestoredWindowObservation $Probe.observer $Probe.native $fresh $BrowserProcess $BrowserOwnerFile $file ((Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant())
}

# Cleanup отдельного probe не является обычным выходом пользователя и не заменяет restart proof.
# Останавливаются только удерживаемые launcher/UI handles; чужой числовой PID не разыскивается.
function Complete-NativeGoalPostTransactionClient($Probe) {
    $native=$Probe.native;$errors=[Collections.Generic.List[string]]::new();$exits=[Collections.Generic.List[object]]::new()
    if ($null -ne $native) {
        $pairs=[Collections.Generic.List[object]]::new()
        if ($null -ne $native.uiProcess -and $null -ne $native.ui) {
            $pairs.Add(@{process=$native.uiProcess;identity=[pscustomobject]@{ProcessId=$native.ui.pid;StartedAtTicks=$native.ui.startedAtTicks;
                ExecutablePath=$native.ui.executablePath;OwnedRoot=$Probe.context.root}})
        }
        $pairs.Add(@{process=$native.process;identity=$native.identity})
        foreach ($pair in $pairs) {
            try {
                if ($pair.process -isnot [Diagnostics.Process]) {throw 'GOAL_CLEANUP_RETAINED_HANDLE_REQUIRED'}
                Assert-ColdProcessIdentity $pair.identity (Get-ColdProcessReceipt $pair.process $Probe.context.root) $Probe.context.root $pair.identity.ExecutablePath
                Stop-ColdRetainedProcess $pair.process $pair.identity $Probe.context.root
                if (-not $pair.process.HasExited) {throw 'GOAL_CLEANUP_RETAINED_ALIVE'}
                $exits.Add([pscustomobject]@{identity=$pair.identity;actualExit=$pair.process.ExitCode})
            } catch {$errors.Add($_.Exception.Message)}
        }
    }
    $remaining=@(Get-CopyProcesses $Probe.context.root)
    if ($remaining.Count) {$errors.Add('GOAL_CLEANUP_ROOT_PROCESSES_ALIVE')}
    # При rejected stop pipes могут оставаться открытыми: нельзя бесконечно ждать stdout в failure cleanup.
    if ($errors.Count -eq 0 -and $null -ne $native) {
        try {Save-NativeOutput $native $Probe.context.directory 'explicit-client'} catch {$errors.Add($_.Exception.Message)}
    }
    if ($errors.Count -eq 0) {
        # Реестр удаляется только после полного root census и только по validated собственному UUID.
        $nodePath=Get-PortableRegistryPath $Probe.registryNode
        if (Test-Path -LiteralPath $nodePath) {Remove-Item -LiteralPath $nodePath -Recurse -Force}
        if ($null -ne $native) {
            if ($null -ne $native.uiProcess) {$native.uiProcess.Dispose()};$native.process.Dispose()
        }
    }
    $receipt=Join-Path $Probe.context.directory 'probe-native-cleanup.json'
    Write-ColdJson $receipt ([ordered]@{finishedAt=[datetime]::UtcNow.ToString('o');exits=@($exits.ToArray());remaining=$remaining;
        errors=@($errors.ToArray());kind='RETAINED_HANDLE_TEARDOWN';nativePass=$false;ordinaryExitProven=$false})
    if ($errors.Count) {throw ('GOAL_NATIVE_CLEANUP:'+($errors -join ';'))}
    return [pscustomobject]@{Receipt=$receipt;ReceiptSha256=(Get-FileHash -LiteralPath $receipt).Hash.ToLowerInvariant();nativePass=$false;ordinaryExitProven=$false}
}

# Один preflight используется перед phase launch и повторно перед отдельным restoration launch.
function Assert-NativeGoalWebObserverPins($WebRuntime,
    [string]$RuntimeReceiptSha256='',[string]$Edge='',[string]$EdgeSha256='') {
        if ($null -eq $WebRuntime -or $RuntimeReceiptSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            (Get-FileHash -LiteralPath $WebRuntime.receipt).Hash.ToLowerInvariant() -cne $RuntimeReceiptSha256 -or
            $WebRuntime.receiptSha256 -cne $RuntimeReceiptSha256) {throw 'GOAL_WEB_EXPLICIT_RUNTIME_PIN'}
        Assert-NativeWebGoalRuntime $WebRuntime
        $stored=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString(
            (Read-RestoredWindowBytes $WebRuntime.receipt $WebRuntime.directory $RuntimeReceiptSha256)))
        foreach ($key in 'directory','java','javaSha256','core','coreSha256','classes','classpath','sourcePins','classPins') {
            if (-not (Test-ColdInventoryEqual $stored.$key $WebRuntime.$key)) {throw 'GOAL_WEB_RUNTIME_RECEIPT_BINDING'}
        }
        # Проверка Edge pin ДО native launch, не только внутри позднего attach callback.
        [void](Resolve-PortableSafetyPath $Edge)
        if ($EdgeSha256 -cnotmatch '^[0-9a-f]{64}$' -or (Get-FileHash -LiteralPath $Edge).Hash.ToLowerInvariant() -cne $EdgeSha256) {throw 'GOAL_WEB_EXPLICIT_EDGE_PIN'}
}

# Optional production entry: MAIN вызывает явно на готовом новом probe, без изменения 9-cell dispatch.
# Web runtime должен быть подготовлен заранее и независимо закреплён SHA receipt перед native Start.
function Invoke-NativeGoalPostTransactionObserver($Probe,[int]$Timeout=60,$WebRuntime=$null,
    [string]$RuntimeReceiptSha256='',[string]$Edge='',[string]$EdgeSha256='') {
    $isWeb=$Probe.context.client -ceq 'web';$attach=$null
    if ($isWeb) {
        Assert-NativeGoalWebObserverPins $WebRuntime $RuntimeReceiptSha256 $Edge $EdgeSha256
        $attach={param($p,$n) Start-NativeWebGoalRestorationBridge $p $n $WebRuntime $Edge $EdgeSha256 55}.GetNewClosure()
    }
    $failure=$null;$observationUnavailable=$null;$cleanupErrors=[Collections.Generic.List[string]]::new();$window=$null;$browserCleanup=$null;$nativeCleanup=$null
    $decision=New-RestoredWindowDecision 'PENDING' @('ACTUAL_OBSERVATION_NOT_PRODUCED')
    try {
        [void](Start-NativeGoalPostTransactionClient $Probe $Timeout $attach)
        try {
            Wait-NativeCondition {Test-Path -LiteralPath (Join-Path $Probe.observer.output 'observed')} 30 'GOAL_SHOT_TIMEOUT'
        } catch {
            # Только отсутствие actual shot в этом ожидании означает недоказанность, не успех и не identity failure.
            if ($_.Exception.Message -cne 'GOAL_SHOT_TIMEOUT') {throw}
            $observationUnavailable='GOAL_SHOT_TIMEOUT'
            $decision=New-RestoredWindowDecision 'PENDING' @('ACTUAL_SHOT_SIGNAL_MISSING')
        }
        if ($null -eq $observationUnavailable) {
            $decision=if ($isWeb) {Read-NativeWebGoalRestorationObservation $Probe} else {Read-NativeGoalPostTransactionObservation $Probe}
        }
    } catch {$failure=$_.Exception.Message}
    finally {
        # Даже attach failure сохраняет Probe.webBridge/Probe.native до cleanup; первичная ошибка не стирается.
        $bridge=Get-RestoredWindowField $Probe 'webBridge'
        if ($null -ne $bridge) {
            try {$browserCleanup=Stop-NativeWebGoalRestorationBridge $bridge} catch {$cleanupErrors.Add($_.Exception.Message)}
        }
        try {$nativeCleanup=Complete-NativeGoalPostTransactionClient $Probe} catch {$cleanupErrors.Add($_.Exception.Message)}
        try {$window=Complete-NativeRestoredWindowObserver $Probe.observer} catch {$cleanupErrors.Add($_.Exception.Message)}
    }
    # Последний census collector может опровергнуть ранний sampler; seal следует всем cleanup.
    if ((Get-RestoredWindowField (Get-RestoredWindowField $window 'decision') 'status') -ceq 'FAIL') {
        $cleanupErrors.Add('GOAL_WINDOW_FINAL_CLEANUP_FAILED')
    }
    if ($isWeb) {
        try {Assert-NativeGoalWebObserverPins $WebRuntime $RuntimeReceiptSha256 $Edge $EdgeSha256}
        catch {$cleanupErrors.Add($_.Exception.Message)}
    }
    $status=if ($failure -or $cleanupErrors.Count) {'FAIL'} else {$decision.status}
    $receipt=Join-Path $Probe.context.directory 'goal-probe-execution.json'
    Write-ColdJson $receipt ([ordered]@{scope='EXPLICIT_POST_TRANSACTION_RESTORATION_PROBE';client=$Probe.context.client;
        status=$status;failure=$failure;observationUnavailable=$observationUnavailable;cleanupErrors=@($cleanupErrors.ToArray());decision=$decision;window=$window;
        browserCleanup=$browserCleanup;nativeCleanup=$nativeCleanup;runtimeReceiptSha256=$RuntimeReceiptSha256;edgeSha256=$EdgeSha256;
        finishedAt=[datetime]::UtcNow.ToString('o');nativePass=$false;fullCellProofComplete=$false;
        automaticRestartProof=$false;ordinaryExitProven=$false})
    return [pscustomobject]@{status=$status;failure=$failure;observationUnavailable=$observationUnavailable;cleanupErrors=@($cleanupErrors.ToArray());Receipt=$receipt;
        ReceiptSha256=(Get-FileHash -LiteralPath $receipt).Hash.ToLowerInvariant();nativePass=$false;fullCellProofComplete=$false}
}

# MAIN передаёт independently pinned ticket из своей очереди; adapter не создаёт разрешение сам.
# Ticket связывает полный tuple и срок очереди, но не служит доказательством actual execution.
function Assert-NativeGoalPhaseMainTicket([string]$MainTicket,[string]$MainTicketSha256,$Binding) {
    if (-not $MainTicket -or $MainTicketSha256 -cnotmatch '^[0-9a-f]{64}$') {throw 'GOAL_PHASE_MAIN_TICKET_REQUIRED'}
    $ticket=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString(
        (Read-RestoredWindowBytes $MainTicket $Binding.evidence $MainTicketSha256)))
    if ((Get-RestoredWindowField $ticket 'scope') -cne 'MAIN_SERIALIZED_GOAL_PHASE' -or
        (Get-RestoredWindowField $ticket 'schemaVersion') -ne 1 -or
        (Get-RestoredWindowField $ticket 'nonce') -cnotmatch '^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {throw 'GOAL_PHASE_MAIN_TICKET_SCHEMA'}
    $now=[datetime]::UtcNow.Ticks
    if ((Get-ColdUtcTicks $ticket.issuedAt) -gt $now -or (Get-ColdUtcTicks $ticket.expiresAt) -le $now) {throw 'GOAL_PHASE_MAIN_TICKET_EXPIRED'}
    if (-not (Test-ColdInventoryEqual $ticket.binding $Binding)) {throw 'GOAL_PHASE_MAIN_TICKET_BINDING'}
    return $ticket
}

# Явный phase-session cell route в собственном adapter, не public Jason lifecycle/dispatch.
# Caller заранее импортирует pinned NEW Invoke-NativeGoalPhaseScenario/Start через AST generators.
# Входов prebuilt observations нет: post probe всегда получает свежий context исполнившейся phase.
function Invoke-NativeGoalPhaseSessionCell($Row,[string]$Source,$Base,$Target,$Life,$Cold,[string]$Java,
    [string]$Evidence,[int]$Timeout=60,$WebRuntime=$null,[string]$RuntimeReceiptSha256='',
    [string]$Edge='',[string]$EdgeSha256='',[string]$MainTicket='',[string]$MainTicketSha256='') {
    if ($Row.client -cnotin @('fx','swing','web')) {throw 'GOAL_PHASE_CLIENT'}
    if ($Timeout -lt 30 -or $Timeout -gt 180) {throw 'GOAL_PHASE_TIMEOUT'}
    if ($Row.client -ceq 'web') {Assert-NativeGoalWebObserverPins $WebRuntime $RuntimeReceiptSha256 $Edge $EdgeSha256}
    $binding=[pscustomobject]@{client=$Row.client;scenario=(Get-RestoredWindowField $Row 'scenario');
        source=$Source;base=$Base;target=$Target;life=$Life;cold=$Cold;java=$Java;evidence=$Evidence;timeout=$Timeout;
        runtimeReceiptSha256=$RuntimeReceiptSha256;edge=$Edge;edgeSha256=$EdgeSha256}
    $ticket=Assert-NativeGoalPhaseMainTicket $MainTicket $MainTicketSha256 $binding
    if (-not (Get-Command Invoke-NativeGoalPhaseScenario -CommandType Function -ErrorAction SilentlyContinue)) {throw 'GOAL_PHASE_PINNED_HOOK_NOT_IMPORTED'}
    $saved=Get-Variable nativeGoalPhaseContext -Scope Script -ErrorAction SilentlyContinue
    $old=if ($null -ne $saved) {$saved.Value} else {$null}
    $script:nativeGoalPhaseContext=$null
    $phaseResult=$null;$restoration=$null;$failure=$null;$context=$null
    try {
        $phaseResult=Invoke-NativeGoalPhaseScenario $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
        $context=$script:nativeGoalPhaseContext
        if ($null -eq $context -or $context.client -cne $Row.client) {throw 'GOAL_PHASE_FRESH_CONTEXT_MISSING'}
        $independent=Get-RestoredWindowField $Row 'phaseSessionIndependent'
        if ($null -eq $independent) {throw 'GOAL_PHASE_INDEPENDENT_RECEIPT_MISSING'}
        # Отрицательное состояние phase не разрешает следующий launch; положительное НЕ доказывает окно.
        if ((Get-RestoredWindowField $Row 'status') -ceq 'FAIL') {throw 'GOAL_PHASE_FAILED_NO_PROBE'}
        if ((Get-RestoredWindowField $independent 'status') -ceq 'FAIL') {throw 'GOAL_PHASE_COLLECTOR_FAILED_NO_PROBE'}
        # Очередь и все runtime pins повторно проверяются до rearm/нового запуска.
        [void](Assert-NativeGoalPhaseMainTicket $MainTicket $MainTicketSha256 $binding)
        if ($Row.client -ceq 'web') {Assert-NativeGoalWebObserverPins $WebRuntime $RuntimeReceiptSha256 $Edge $EdgeSha256}
        $probe=New-NativeGoalPostTransactionProbe $context $independent.Receipt $independent.ReceiptSha256
        $restoration=Invoke-NativeGoalPostTransactionObserver $probe $Timeout $WebRuntime $RuntimeReceiptSha256 $Edge $EdgeSha256
    } catch {$failure=$_.Exception.Message}
    finally {
        if ($null -ne $saved) {$script:nativeGoalPhaseContext=$old} else {Remove-Variable nativeGoalPhaseContext -Scope Script -ErrorAction SilentlyContinue}
    }
    # Возвращается отдельный supplemental результат; Row.status/signoff/counts не переписываются.
    return [pscustomobject]@{scope='PHASE_WITH_EXPLICIT_POST_TRANSACTION_RESTORATION';
        status=$(if ($failure) {'FAIL'} elseif ($null -eq $restoration) {'PENDING'} else {$restoration.status});
        failure=$failure;phaseResult=$phaseResult;phaseSessionIndependent=(Get-RestoredWindowField $Row 'phaseSessionIndependent');
        restoration=$restoration;mainTicket=$MainTicket;mainTicketSha256=$MainTicketSha256;mainNonce=$ticket.nonce;
        nativePass=$false;fullCellProofComplete=$false;automaticRestartProof=$false}
}
