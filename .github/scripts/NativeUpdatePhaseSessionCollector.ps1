<#
.SYNOPSIS
Независимый collector controlled sessions для frozen phase producer; dot-source только определяет функции.
.DESCRIPTION
MAIN импортирует cold/lifecycle guards через AST. New-NativePhaseSessionHookSource получает
SHA-256 frozen NativeUpdatePhaseScenarios.ps1 и возвращает НОВУЮ функцию для in-memory
импорта вместо Invoke-NativePhaseScenario. Файл producer не меняется, canonical plan не копируется.
Hook: после Initialize-NativeDomainSession и ДО user/controlled baseline - seed;
до identity Kill helper - checkpoint; после WAITING removal - новый epoch;
после первой verify и ДО cleanup native - recovery; после finally - cleanup/receipt.
Для запуска нужны существующие Get-ColdVersion, Start-NativeOwned, Wait-NativeCondition,
Save-NativeOutput, Stop-ColdRetainedProcess, Get-ColdProcessReceipt, Assert-ColdProcessIdentity,
Assert-ColdUiReceipt, Assert-ColdControlledChanges, Get-ColdUtcTicks, Test-ColdInventoryEqual,
Get-CopyProcesses, Get-PortableRealRegistrySnapshot, Write-ColdJson, Resolve-PortableSafetyPath,
Test-ColdInteger и ConvertFrom-ColdReceiptJson.
JDK source launcher использует отдельную ASCII evidence-копию pinned core, Unicode root - Base64.
Seed - реальные domain/store writers, но созданный XML НЕ означает наблюдаемое окно.
Collector сохраняет независимые bytes, свежие codec revisions и cleanup census с SHA receipt.
Полное восстановление пользовательского окна frozen recovery CLI не наблюдает: итог PENDING,
не native PASS. Acceptance/dispatch frozen остаются неизменными. MAIN не должен удалять их
PHASE_CONTROLLED_SESSION_INDEPENDENT_RECEIPTS_NOT_PRODUCED guard без отдельной интеграции.
Адаптер MAIN после терминального предыдущего run (не исполнять тело frozen script):
. NativeUpdatePhaseSessionCollector.ps1
$phaseCollectorFunctionSource=New-NativePhaseSessionHookSource $phaseFile $independentlyPinnedPhaseSha256
. ([scriptblock]::Create($phaseCollectorFunctionSource))
Invoke-NativePhaseScenarioWithSessionCollector $Row $Source $Base $Target $Life $Cold $Java $Evidence $Timeout
Row.phaseSessionIndependent содержит Receipt/ReceiptSha256 только после собственного cleanup capture.
Сохранённый supplemental receipt не меняет frozen PhaseAcceptance return или canonical signoff.
#>

# Закрытый набор файлов охватывает все controlled bytes и независимо созданный domain baseline.
function Get-NativePhaseSessionPaths {
    return @('CashMemory/session-fx.xml','CashMemory/session-swing.xml','CashMemory/web-session.md',
        'CashMemory/web-session.plan.md','CashMemory/web-reconnect.md','CashMemory/web-reconnect-lock.md',
        'CashMemory/lock.md','CashMemory/settings.md','CashMemory/PhaseSession.md','CashMemory/NativeLifecycle.md')
}

# Нельзя использовать root продукта, старую чужую копию или evidence с junction/Unicode bridge path.
function Assert-NativePhaseSessionScope([string]$Root,[string]$Evidence) {
    $rootPath=Resolve-PortableSafetyPath $Root;$evidencePath=Resolve-PortableSafetyPath $Evidence
    $tempPath=Resolve-PortableSafetyPath ([IO.Path]::GetTempPath())
    $separators=[char[]]@('\','/')
    $prefix=$tempPath.TrimEnd($separators)+[IO.Path]::DirectorySeparatorChar
    if ($Root -cne $rootPath -or $Evidence -cne $evidencePath -or
        -not $rootPath.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or
        -not $evidencePath.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or
        $rootPath.Substring($prefix.Length) -cnotmatch '^run-[0-9a-f-]{36}[\\/]' -or
        $evidencePath.Substring($prefix.Length) -cnotmatch '^cp-native-(phase|lifecycle)-evidence-[0-9a-f-]{36}[\\/]run-[0-9a-f-]{36}$' -or
        $evidencePath -cmatch '[^\x20-\x7e]' -or
        [IO.Path]::GetFileName($evidencePath) -cne ($rootPath.Substring($prefix.Length).Split($separators)[0])) {
        throw 'PHASE_SESSION_SCOPE'
    }
}

# Один ограниченный complete-file read блокирует append/replace; busy writer не становится пустым baseline.
function Read-NativePhaseSessionBytes([string]$Path) {
    [void](Resolve-PortableSafetyPath $Path)
    $stream=[IO.FileStream]::new($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        if ($stream.Length -gt 8388608) {throw 'PHASE_SESSION_SIZE'}
        $bytes=[byte[]]::new([int]$stream.Length);$offset=0
        while ($offset -lt $bytes.Length) {
            $count=$stream.Read($bytes,$offset,$bytes.Length-$offset)
            if ($count -le 0) {throw 'PHASE_SESSION_SHORT_READ'};$offset+=$count
        }
        if ($stream.Length -ne $bytes.Length) {throw 'PHASE_SESSION_CHANGED'}
        return ,$bytes
    } finally {$stream.Dispose()}
}

# Hash считается по наблюдаемым bytes; codec читает их приватную копию, не гоняющийся live root.
function Get-NativePhaseSessionSha([byte[]]$Bytes) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
}

# Два полных прохода отвергают видимое изменение набора/bytes/readonly во время capture.
function Get-NativePhaseSessionInventory([string]$Root) {
    $files=@(foreach ($relative in Get-NativePhaseSessionPaths) {
        $path=Join-Path $Root $relative
        if (Test-Path -LiteralPath $path) {
            $item=Get-Item -LiteralPath $path -Force
            if ($item.PSIsContainer) {throw 'PHASE_SESSION_FILE'}
            $bytes=Read-NativePhaseSessionBytes $path
            [pscustomobject]@{path=$relative;sizeBytes=$bytes.Length;sha256=(Get-NativePhaseSessionSha $bytes);
                contentBase64=[Convert]::ToBase64String($bytes);readOnly=$item.IsReadOnly}
        }
    })
    return ,$files
}

# JDK bridge использует настоящие core codecs/store writers, без registry и без UI.
function Get-NativePhaseSessionBridgeSource {
    return @'
import java.nio.file.*;
import java.time.*;
import java.util.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.codec.*;
import ru.cashprediction.core.session.store.*;
import ru.cashprediction.core.markdown.*;
import ru.cashprediction.core.document.*;
import ru.cashprediction.core.io.*;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.json.JsonWriter;

/** Только domain seed и свежий codec read приватной копии, не свидетельство окна. */
final class NativePhaseSessionBridge {
    /** Проверяет аргументы, создаёт seed либо читает уже захваченные bytes. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !List.of("fx","swing","web").contains(args[2])) throw new IllegalArgumentException("PHASE_SESSION_ARGUMENTS");
        Path root=Path.of(new String(Base64.getDecoder().decode(args[1]), java.nio.charset.StandardCharsets.UTF_8));
        Path memory=root.resolve("CashMemory");
        LocalDate today=LocalDate.of(2026,10,4);
        if (args[0].equals("seed")) {
            for (String name: List.of("session-fx.xml","session-swing.xml","web-session.md","web-session.plan.md","PhaseSession.md"))
                if (Files.exists(memory.resolve(name))) throw new java.io.IOException("PHASE_SESSION_SEED_EXISTS");
            Plan plan=Plan.empty("PhaseSession",today);
            Path planFile=memory.resolve("PhaseSession.md");
            new PlanRepository(memory).save(plan,planFile);
            AppSettings settings=SettingsMarkdown.read(Files.readString(memory.resolve("settings.md"))).withPlanOpened(planFile.toString());
            SettingsMarkdown.save(memory.resolve("settings.md"),settings);
            Instant stamp=Instant.parse("2026-10-04T00:00:00Z");
            for (String client: List.of("fx","swing","web")) {
                SessionStore store=store(memory,client);
                store.markDirty(SessionMarker.running(0,stamp,client).closed());
                var main=new MainWindowState(null,false,"TABLE","PhaseSession.md","ALL",Map.of("showIncome",true),"phase-731","","");
                // Текущий web clean: recorder вправе удалить свой отдельный dirty plan; foreign web сохраняется точно.
                var planState=client.equals("web") && client.equals(args[2])?PlanState.CLEAN:PlanState.dirty(PlanMarkdownWriter.write(plan));
                store.save(SessionSnapshot.of(stamp,client,main,planState,List.of()));
                if (store.lastError().isPresent()) throw new java.io.IOException("PHASE_SESSION_STORE_WRITE");
            }
        } else if (!args[0].equals("read")) throw new IllegalArgumentException("PHASE_SESSION_MODE");
        var out=new LinkedHashMap<String,Object>();
        var settings=SettingsMarkdown.read(Files.readString(memory.resolve("settings.md")));
        out.put("settingsCanonical",SettingsMarkdown.write(settings));
        var loaded=new PlanRepository(memory).load(memory.resolve("PhaseSession.md"),today);
        if (loaded.hasWarnings()) throw new java.io.IOException("PHASE_SESSION_PLAN_WARNINGS");
        out.put("planCanonical",PlanMarkdownWriter.write(loaded.plan()));
        var sessions=new LinkedHashMap<String,Object>();
        for (String client: List.of("fx","swing","web")) {
            SessionStore store=store(memory,client);
            var snapshot=store.load().orElseThrow();
            var marker=store.readMarker().orElseThrow();
            var entry=new LinkedHashMap<String,Object>();
            entry.put("snapshotJson",new JsonSnapshotCodec().encode(snapshot));
            entry.put("savedAt",snapshot.savedAt().toString());
            entry.put("markerPid",marker.pid());entry.put("markerStartedAt",marker.startedAt().toString());
            entry.put("markerState",marker.state());entry.put("client",client);
            sessions.put(client,entry);
        }
        out.put("sessions",sessions);System.out.println(JsonWriter.write(out));
    }
    /** Выбирает только файловое хранилище; запись не обращается в реестр. */
    private static SessionStore store(Path memory,String client) {
        return client.equals("web")?MarkdownSessionStore.inCashMemory(memory):XmlSessionStore.inCashMemory(memory,client);
    }
}
'@
}

# Future real invocation использует retained PID/birth/evidence guards; timeout не считается успехом.
function Invoke-NativePhaseSessionCodec($Context,[string]$Mode,[string]$Root,[string]$Label) {
    foreach ($pin in @(@{path=$Context.java;sha=$Context.javaSha},@{path=$Context.core;sha=$Context.coreSha},
        @{path=$Context.source;sha=$Context.sourceSha})) {
        if ((Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha) {throw 'PHASE_SESSION_PIN'}
    }
    $arguments=@('-XX:-UsePerfData','-cp',$Context.core,$Context.source,$Mode,
        [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Root)),$Context.client)
    $process=Start-NativeOwned $Context.java $arguments $Context.directory
    try {
        if ($process.process -isnot [Diagnostics.Process]) {throw 'PHASE_SESSION_ACTUAL_JDK_REQUIRED'}
        Wait-NativeCondition {$process.process.HasExited} 60 'PHASE_SESSION_JDK_TIMEOUT'
        Save-NativeOutput $process $Context.directory $Label
        if ($process.process.ExitCode -ne 0) {throw 'PHASE_SESSION_CODEC'}
        $text=$process.stdout.GetAwaiter().GetResult()
        if ($text.Length -gt 8388608) {throw 'PHASE_SESSION_SIZE'}
        $decoded=ConvertFrom-ColdReceiptJson $text
        foreach ($client in 'fx','swing','web') {
            $entry=$decoded.sessions.$client
            $entry | Add-Member revisionSha256 (Get-NativePhaseSessionSha ([Text.Encoding]::UTF8.GetBytes($entry.snapshotJson))) -Force
        }
        foreach ($pin in @(@{path=$Context.java;sha=$Context.javaSha},@{path=$Context.core;sha=$Context.coreSha},
            @{path=$Context.source;sha=$Context.sourceSha})) {
            if ((Get-FileHash -LiteralPath $pin.path).Hash.ToLowerInvariant() -cne $pin.sha) {throw 'PHASE_SESSION_PIN'}
        }
        return [pscustomobject]@{process=$process.identity;arguments=$arguments;exitCode=$process.process.ExitCode;
            readAt=[datetime]::UtcNow.ToString('o');decoded=$decoded}
    } finally {
        if (-not $process.process.HasExited) {Stop-ColdRetainedProcess $process.process $process.identity $Context.directory}
        $process.process.Dispose()
    }
}

# Сначала actual stable bytes, затем fresh JDK decode; timestamps не заимствуются из Row/helper.
function Save-NativePhaseSessionObservation($Context,[string]$Stage,$Ui=$null,$UiProcess=$null) {
    $started=[datetime]::UtcNow.ToString('o')
    if ($null -ne $Ui) {
        Assert-ColdUiReceipt $Ui $Context.root $Context.client
        if ($UiProcess -isnot [Diagnostics.Process] -or $UiProcess.HasExited) {throw 'PHASE_SESSION_UI_GONE'}
        $identity=[pscustomobject]@{ProcessId=$Ui.pid;StartedAtTicks=$Ui.startedAtTicks;ExecutablePath=$Ui.executablePath;OwnedRoot=$Context.root}
        Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $UiProcess $Context.root) $Context.root $Ui.executablePath
    }
    $first=Get-NativePhaseSessionInventory $Context.root
    $second=Get-NativePhaseSessionInventory $Context.root
    if (-not (Test-ColdInventoryEqual $first $second)) {throw 'PHASE_SESSION_CHANGED'}
    $capture=Join-Path $Context.directory $Stage
    if (Test-Path -LiteralPath $capture) {throw 'PHASE_SESSION_STAGE_EXISTS'}
    [void][IO.Directory]::CreateDirectory((Join-Path $capture 'CashMemory'))
    foreach ($file in $first) {[IO.File]::WriteAllBytes((Join-Path $capture $file.path),[Convert]::FromBase64String($file.contentBase64))}
    $codec=Invoke-NativePhaseSessionCodec $Context 'read' $capture $Stage
    if (-not (Test-ColdInventoryEqual $first (Get-NativePhaseSessionInventory $Context.root))) {throw 'PHASE_SESSION_CHANGED'}
    if ($null -ne $Ui) {
        Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $UiProcess $Context.root) $Context.root $Ui.executablePath
        $before=if (@($Context.observations | Where-Object stage -CEQ 'epoch').Count) {
            @($Context.observations | Where-Object stage -CEQ 'epoch')[0].files
        } else {$Context.observations[0].files}
        $controlled=@(Get-NativePhaseSessionPaths | Select-Object -First 7)
        Assert-ColdControlledChanges @($before | Where-Object {$_.path -cin $controlled}) @($first | Where-Object {$_.path -cin $controlled}) `
            $Context.client $Ui $Context.root ([datetime]::UtcNow.Ticks)
    }
    $freshRevision=$false
    if ($null -ne $Ui) {
        $current=$codec.decoded.sessions.($Context.client)
        $freshRevision=$current.markerPid -eq $Ui.pid -and
            (Get-ColdUtcTicks $current.markerStartedAt) -ge $Ui.startedAtTicks -and
            (Get-ColdUtcTicks $current.savedAt) -ge $Ui.startedAtTicks -and
            $current.revisionSha256 -cne $Context.seed.decoded.sessions.($Context.client).revisionSha256
    }
    $observation=[pscustomobject]@{stage=$Stage;startedAt=$started;finishedAt=[datetime]::UtcNow.ToString('o');
        files=$first;codec=$codec;ui=$Ui;freshOwnedRevisionObserved=$freshRevision;physicalRestorationObserved=$false}
    Write-ColdJson (Join-Path $Context.directory ($Stage+'.json')) $observation
    $Context.observations.Add($observation)
    return $observation
}

# Hook обязан сработать на свежем owned clone до Ready/journal/helper, не после выполненной фазы.
function Start-NativePhaseSessionCollector($Row,[string]$Root,[string]$Evidence,[string]$Java,$Cold) {
    Assert-NativePhaseSessionScope $Root $Evidence
    if ($Row.client -cnotin @('fx','swing','web') -or @(Get-CopyProcesses $Root).Count -ne 0) {throw 'PHASE_SESSION_SEED_LIVE'}
    foreach ($file in @('CashMemory/Updates/Ready','CashMemory/Updates/install-journal.json')+
        @(Get-NativePhaseSessionPaths | Where-Object {$_ -match 'session|PhaseSession'})) {
        if (Test-Path -LiteralPath (Join-Path $Root $file)) {throw 'PHASE_SESSION_TOO_LATE'}
    }
    $directory=Join-Path $Evidence 'phase-session-independent'
    if (Test-Path -LiteralPath $directory) {throw 'PHASE_SESSION_COLLECTOR_EXISTS'}
    [void][IO.Directory]::CreateDirectory($directory)
    $version=Get-ColdVersion $Root;$original=Join-Path (Join-Path $Root 'app') $version.jar
    [void](Resolve-PortableSafetyPath $original);[void](Resolve-PortableSafetyPath $Java)
    if ((Get-FileHash -LiteralPath $original).Hash.ToLowerInvariant() -cne $version.jarSha256 -or
        (Get-FileHash -LiteralPath $Java).Hash.ToLowerInvariant() -cne $Cold.runtimeSha256) {throw 'PHASE_SESSION_PIN'}
    $core=Join-Path $directory 'phase-core.jar';[IO.File]::Copy($original,$core,$false)
    $source=Join-Path $directory 'NativePhaseSessionBridge.java'
    [IO.File]::WriteAllText($source,(Get-NativePhaseSessionBridgeSource),[Text.UTF8Encoding]::new($false))
    $context=[pscustomobject]@{root=$Root;client=$Row.client;directory=$directory;java=$Java;javaSha=$Cold.runtimeSha256;
        core=$core;coreSha=$version.jarSha256;source=$source;sourceSha=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant();
        installedAt=[datetime]::UtcNow.ToString('o');registryBefore=(Get-PortableRealRegistrySnapshot);
        observations=[Collections.Generic.List[object]]::new();seed=$null}
    $context.seed=Invoke-NativePhaseSessionCodec $context 'seed' $Root 'seed'
    [void](Save-NativePhaseSessionObservation $context 'baseline')
    return $context
}

# Pure контракт: изменённые bytes - FAIL, недостающие наблюдения - PENDING; Row.status не читается.
function Test-NativePhaseSessionObservations($Context) {
    $errors=[Collections.Generic.List[string]]::new();$missing=[Collections.Generic.List[string]]::new()
    if ($null -eq $Context -or $Context.PSObject.Properties.Name -cnotcontains 'observations' -or $null -eq $Context.observations) {
        return [pscustomobject]@{status='PENDING';scope='CONTROLLED_SESSION_BYTES_AND_FRESH_CODEC';errors=@();
            missing=@('INDEPENDENT_OBSERVATIONS_NOT_PRODUCED');nativePass=$false}
    }
    $stages=@{}
    foreach ($observation in @($Context.observations.ToArray())) {
        if ($stages.ContainsKey($observation.stage)) {$errors.Add('DUPLICATE_STAGE');continue}
        $stages[$observation.stage]=$observation
        if ((Get-ColdUtcTicks $observation.finishedAt) -lt (Get-ColdUtcTicks $observation.startedAt)) {$errors.Add('TIME')}
        $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($file in @($observation.files)) {
            try {$bytes=[Convert]::FromBase64String($file.contentBase64)} catch {$errors.Add('BYTES');continue}
            if ($file.path -cnotin @(Get-NativePhaseSessionPaths) -or $file.sizeBytes -ne $bytes.Length -or
                -not (Test-ColdInteger $file.sizeBytes) -or $file.sizeBytes -gt 8388608 -or $file.readOnly -isnot [bool] -or
                -not $seen.Add($file.path) -or $file.sha256 -cne (Get-NativePhaseSessionSha $bytes)) {$errors.Add('BYTES')}
        }
    }
    foreach ($stage in 'baseline','checkpoint','recovery','cleanup') {if (-not $stages.ContainsKey($stage)) {$missing.Add('STAGE:'+ $stage)}}
    $previous=Get-ColdUtcTicks $Context.installedAt
    foreach ($stage in 'baseline','checkpoint','epoch','recovery','cleanup') {
        if ($stages.ContainsKey($stage)) {
            if ((Get-ColdUtcTicks $stages[$stage].startedAt) -lt $previous) {$errors.Add('ORDER')}
            $previous=Get-ColdUtcTicks $stages[$stage].finishedAt
        }
    }
    if ($stages.ContainsKey('baseline')) {
        $baseline=$stages.baseline
        foreach ($observation in @($Context.observations.ToArray())) {
            foreach ($path in 'CashMemory/settings.md','CashMemory/PhaseSession.md','CashMemory/NativeLifecycle.md') {
                $old=@($baseline.files | Where-Object path -CEQ $path);$new=@($observation.files | Where-Object path -CEQ $path)
                if ($old.Count -ne 1 -or $new.Count -ne 1) {$missing.Add('FILE:'+ $path)}
                elseif (-not (Test-ColdInventoryEqual $old $new)) {$errors.Add('DOMAIN_CHANGED:'+ $path)}
            }
            if ($null -eq $observation.codec) {$missing.Add('FRESH_CODEC_NOT_PRODUCED');continue}
            foreach ($client in 'fx','swing','web') {
                $entry=$observation.codec.decoded.sessions.$client
                if ($null -eq $entry) {$missing.Add('CODEC:'+ $client);continue}
                if ($entry.client -cne $client -or $entry.revisionSha256 -cne
                    (Get-NativePhaseSessionSha ([Text.Encoding]::UTF8.GetBytes($entry.snapshotJson))) -or
                    -not (Test-ColdInteger $entry.markerPid) -or $entry.markerPid -gt [int]::MaxValue -or
                    $entry.markerState -cnotin @('running','closed')) {$errors.Add('CODEC_REVISION')}
                if ($client -cne $Context.client) {
                    if (-not (Test-ColdInventoryEqual $baseline.codec.decoded.sessions.$client $entry)) {$errors.Add('FOREIGN_SESSION_CHANGED')}
                    $paths=if ($client -ceq 'web') {@('CashMemory/web-session.md','CashMemory/web-session.plan.md')} else {@('CashMemory/session-'+$client+'.xml')}
                    if (-not (Test-ColdInventoryEqual @($baseline.files | Where-Object {$_.path -cin $paths}) @($observation.files | Where-Object {$_.path -cin $paths}))) {
                        $errors.Add('FOREIGN_SESSION_BYTES_CHANGED')
                    }
                }
            }
            if ($observation.codec.decoded.settingsCanonical -cne $baseline.codec.decoded.settingsCanonical -or
                $observation.codec.decoded.planCanonical -cne $baseline.codec.decoded.planCanonical) {$errors.Add('DOMAIN_CODEC_CHANGED')}
            if ($observation.stage -ceq 'checkpoint' -and -not (Test-ColdInventoryEqual $baseline.files $observation.files) -and
                $null -eq $observation.ui) {$errors.Add('EARLY_CONTROLLED_CHANGED')}
            if ($observation.stage -ceq 'recovery' -and $null -eq $observation.ui) {$missing.Add('ACTUAL_RETAINED_RECOVERY_IDENTITY')}
            if ($observation.stage -ceq 'recovery' -and
                ($observation.PSObject.Properties.Name -cnotcontains 'freshOwnedRevisionObserved' -or
                $observation.freshOwnedRevisionObserved -isnot [bool] -or -not $observation.freshOwnedRevisionObserved)) {
                $missing.Add('FRESH_OWNED_SESSION_REVISION_NOT_OBSERVED')
            }
        }
    }
    # Persisted revision и созданный snapshot не доказывают действительное восстановление окна.
    $missing.Add('ACTUAL_RESTORED_USER_WINDOW_NOT_OBSERVED')
    return [pscustomobject]@{status=$(if ($errors.Count) {'FAIL'} else {'PENDING'});scope='CONTROLLED_SESSION_BYTES_AND_FRESH_CODEC';
        errors=@($errors.ToArray());missing=@($missing.ToArray());nativePass=$false}
}

# При невозможности получить bytes - PENDING; установленное противоречие нельзя скрыть таким статусом.
function Get-NativePhaseSessionFailureDecision([string]$Reason) {
    $contradiction=$Reason -cmatch '^PHASE_SESSION_(PIN|CHANGED|SHORT_READ|SIZE|FILE|SCOPE|UI_GONE|TOO_LATE|SEED_LIVE|STAGE_EXISTS|COLLECTOR_EXISTS)$' -or
        $Reason -ceq 'COLD_REPORT_CONTROLLED' -or $Reason -cmatch '^COLD_.*(IDENTITY|UI_RECEIPT)'
    return [pscustomobject]@{status=$(if ($contradiction) {'FAIL'} else {'PENDING'});reason=$Reason;nativePass=$false}
}

# Завершение после finally: actual census/registry и собственное finishedAt, не Row.finishedAt.
function Complete-NativePhaseSessionCollector($Context) {
    $processesBefore=@(Get-CopyProcesses $Context.root)
    $cleanupObservation=Save-NativePhaseSessionObservation $Context 'cleanup'
    $processes=@(Get-CopyProcesses $Context.root)
    $registrySame=(Get-PortableRealRegistrySnapshot) -ceq $Context.registryBefore
    $cleanupObservedAt=[datetime]::UtcNow.ToString('o')
    $decision=Test-NativePhaseSessionObservations $Context
    if ($processesBefore.Count -ne 0 -or $processes.Count -ne 0 -or -not $registrySame) {
        $decision.status='FAIL';$decision.errors+=@('CLEANUP_CENSUS_OR_REGISTRY')
    }
    $receipt=Join-Path $Context.directory 'phase-session-independent.json'
    Write-ColdJson $receipt ([ordered]@{schemaVersion=1;scope=$decision.scope;root=$Context.root;client=$Context.client;
        installedAt=$Context.installedAt;finishedAt=[datetime]::UtcNow.ToString('o');seed=$Context.seed;
        observations=@($Context.observations.ToArray());cleanup=[ordered]@{observedAt=$cleanupObservedAt;
            processesBeforeCodec=$processesBefore;processes=$processes;registryUnchanged=$registrySame};decision=$decision;nativePass=$false})
    return [pscustomobject]@{status=$decision.status;Receipt=$receipt;ReceiptSha256=(Get-FileHash -LiteralPath $receipt).Hash.ToLowerInvariant();
        scope=$decision.scope;missing=$decision.missing;errors=$decision.errors;nativePass=$false}
}

# Точные pinned anchors дают применимый hook ДО execution; дрейф frozen producer отвергается.
function Publish-NativePhaseSessionCollectorResult($Context,[string]$Failure) {
    if ($null -eq $Context) {
        return Get-NativePhaseSessionFailureDecision $(if ($Failure) {$Failure} else {'COLLECTOR_NOT_INSTALLED'})
    }
    try {$result=Complete-NativePhaseSessionCollector $Context}
    catch {$result=Get-NativePhaseSessionFailureDecision $_.Exception.Message}
    # Источник - конкретное исключение collector, не Row.status или helper-return PASS.
    if ($Failure -cmatch '^PHASE_SESSION_' -or $Failure -ceq 'COLD_REPORT_CONTROLLED') {
        $failureDecision=Get-NativePhaseSessionFailureDecision $Failure
        if ($failureDecision.status -ceq 'FAIL') {
            $result.status='FAIL';$result | Add-Member collectorFailure $Failure -Force
        }
    }
    return $result
}

# Hook компилируется только в памяти; не меняет файл, число клеток или существующий dispatch.
function New-NativePhaseSessionHookSource([string]$Producer,[string]$ExpectedSha256) {
    if ($ExpectedSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $Producer).Hash.ToLowerInvariant() -cne $ExpectedSha256) {throw 'PHASE_SESSION_PRODUCER_PIN'}
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($Producer,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'PHASE_SESSION_PRODUCER_PARSE'}
    $nodes=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Invoke-NativePhaseScenario'},$true))
    if ($nodes.Count -ne 1) {throw 'PHASE_SESSION_PRODUCER_FUNCTION'}
    $body=$nodes[0].Extent.Text
    $hooks=@(
        @{anchor='$Row | Add-Member domainEvidence $domain -Force';code='$phaseSession=Start-NativePhaseSessionCollector $Row $root $cellEvidence $Java $Cold'},
        @{anchor='Stop-ColdRetainedProcess $helper $identity $root';code='if ($null -ne $setupClient) { [void](Save-NativePhaseSessionObservation $phaseSession ''checkpoint'' $setupClient.ui $setupClient.uiProcess) } else { [void](Save-NativePhaseSessionObservation $phaseSession ''checkpoint'') }';first=$true},
        @{anchor='$controlled=@(Get-ColdControlledInventory $root)';code='[void](Save-NativePhaseSessionObservation $phaseSession ''epoch'')';last=$true},
        @{anchor='$tools=@(Invoke-ColdTool $Cold $Java @(''verify'',''--root'',$root,''--manifest'',$manifest) $cellEvidence $root)';
            code='[void](Save-NativePhaseSessionObservation $phaseSession ''recovery'' $native.ui $native.uiProcess)'},
        @{anchor='Write-ColdJson (Join-Path $cellEvidence ''phase-result.json'') $Row';
            code='$Row | Add-Member phaseSessionIndependent (Publish-NativePhaseSessionCollectorResult $phaseSession $failure) -Force';before=$true}
    )
    foreach ($hook in $hooks) {
        $matches=[regex]::Matches($body,[regex]::Escape($hook.anchor))
        $expected=if ($hook.ContainsKey('first') -or $hook.ContainsKey('last')) {2} else {1}
        if ($matches.Count -ne $expected) {throw 'PHASE_SESSION_HOOK_ANCHOR'}
        $match=if ($hook.ContainsKey('last')) {$matches[-1]} else {$matches[0]}
        $index=if ($hook.ContainsKey('before')) {$match.Index} else {$match.Index+$match.Length}
        $code=if ($hook.ContainsKey('before')) {$hook.code+"`n        "} else {"`n        "+$hook.code}
        # checkpoint должен сниматься до Kill, не после него.
        if ($hook.ContainsKey('first')) {$index=$match.Index;$code=$hook.code+"`n        "}
        $body=$body.Insert($index,$code)
    }
    $body=$body.Replace('function Invoke-NativePhaseScenario(', 'function Invoke-NativePhaseScenarioWithSessionCollector(')
    $body=$body.Replace('$helper=$null;$identity=$null;', '$phaseSession=$null;$helper=$null;$identity=$null;')
    $null=[Management.Automation.Language.Parser]::ParseInput($body,[ref]$tokens,[ref]$errors)
    if ($errors.Count) {throw 'PHASE_SESSION_HOOK_PARSE'}
    return $body
}
