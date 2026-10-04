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
function Start-NativeGoalPostTransactionClient($Probe,[int]$Timeout=60) {
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
