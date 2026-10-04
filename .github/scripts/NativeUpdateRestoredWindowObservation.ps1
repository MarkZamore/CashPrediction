<#
.SYNOPSIS
Отдельный armed observer настоящего raw UiDump; dot-source не запускает процессы.
.DESCRIPTION
MAIN импортирует frozen cold/lifecycle guards. Ожидания готовятся ДО launch:
New-NativeRestoredWindowObserver $PhaseContext $ExpectationFile $ExpectationSha256;
Set-NativeRestoredWindowLaunchEpoch $Observer непосредственно ДО native Start;
Read-NativeRestoredWindowObservation $Observer $Native $FreshSessionObservation
  -FreshObservationFile <phase-context>/recovery.json -FreshObservationSha256 <independent pin> ДО cleanup;
Complete-NativeRestoredWindowObserver $Observer ПОСЛЕ реального cleanup.
Expectation создаёт JDK codec ниже из independently pinned seed snapshotJson, не из observed dump.
Desktop producer: SelfTestRunner shot + signal, FxUiDumper/SwingUiDumper читают виджеты.
Web producer: существующий PortableExeProcess.attach/TestApiBridge/CdpTestApi + WebSelfTestBridge.
Для Web нужен настоящий retained browser и существующий browser-owner.json (profile/executable),
не выдуманный CDP receipt. Observer не запускает browser/server и не POST-ит synthetic dump.
Raw UiDump schema 1 НЕ имеет revision/form-spec id/context. DumpRevisionSha256 означает hash
полных raw bytes; FormSpec id - только expected metadata. Context сверяется со свежим persisted
snapshot, не объявляется свойством виджетов. Форма пока ограничена настоящим GOAL_CALCULATOR.
Frozen phase seed: windows=[] и closed markers. Это SEEDED_WINDOW_NOT_PRODUCED, не UI PASS.
Frozen phase args запрещают selftest/test-api (Assert-ColdSafeArgs), RestartRequest их очищает.
Observer НЕ меняет эти guards/runner/collector и не обходит их через environment properties.
CLI additions - proposal для отдельного MAIN review, не уже интегрированная native клетка.
Pure fixture результат WINDOW_CONTRACT_VALIDATED не означает реальный UI. Ни один entry не
выдаёт native PASS, full-cell proofComplete или ordinary exit; absent evidence остаётся PENDING.
#>

# Используются только уже существующие wire guards; JSON timestamps остаются строками.
function Get-RestoredWindowField($Value,[string]$Name) {
    if ($null -ne $Value -and $Value.PSObject.Properties.Name -ccontains $Name) {return $Value.$Name}
    return $null
}

# Отсутствие не маскируется truthiness: ноль/false являются конкретными данными.
function New-RestoredWindowDecision([string]$Status,[string[]]$Missing=@(),[string[]]$Errors=@()) {
    return [pscustomobject]@{status=$Status;scope='RESTORED_WINDOW_ONLY';missing=$Missing;errors=$Errors;
        nativePass=$false;fullCellProofComplete=$false;ordinaryExitProven=$false}
}

# Полные durable bytes читаются ограниченно; path/link/size/hash mismatch - contradiction.
function Read-RestoredWindowBytes([string]$Path,[string]$Evidence,[string]$Sha256='') {
    $file=Resolve-PortableSafetyPath $Path;$scope=Resolve-PortableSafetyPath $Evidence
    if (-not $file.StartsWith($scope+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)) {throw 'RESTORED_ARTIFACT_SCOPE'}
    $bytes=Read-NativePhaseSessionBytes $file
    if ($Sha256 -and ($Sha256 -cnotmatch '^[0-9a-f]{64}$' -or (Get-NativePhaseSessionSha $bytes) -cne $Sha256)) {throw 'RESTORED_ARTIFACT_PIN'}
    return ,$bytes
}

# Pure window-only сравнение: runtime ids могут переименоваться, но owner mapping должен быть биекцией.
function Test-NativeRestoredWindowContract($Expected,$Dump,$FreshSnapshot) {
    $windows=Get-RestoredWindowField $Expected 'windows'
    if ($null -eq $windows -or @($windows).Count -eq 0) {return New-RestoredWindowDecision 'PENDING' @('SEEDED_WINDOW_NOT_PRODUCED')}
    if ($null -eq $Dump) {return New-RestoredWindowDecision 'PENDING' @('ACTUAL_RAW_DUMP_NOT_PRODUCED')}
    if ($null -eq $FreshSnapshot) {return New-RestoredWindowDecision 'PENDING' @('FRESH_CODEC_CONTEXT_NOT_PRODUCED')}
    $errors=[Collections.Generic.List[string]]::new()
    $dumpKeys=@('schema','client','scenario','step','frame','menuBar','toolbar','summary','table','chart','status','contextMenus',
        'windows','alerts','popups','screens','chooserRequests','classCensus','counters')
    if (@($Dump.PSObject.Properties).Count -ne $dumpKeys.Count -or
        @($Dump.PSObject.Properties.Name | Where-Object {$_ -cnotin $dumpKeys}).Count) {$errors.Add('DUMP_SCHEMA_KEYS')}
    if (-not (Test-ColdInteger (Get-RestoredWindowField $Dump 'schema') 1) -or $Dump.schema -ne 1 -or
        $Dump.client -cne $Expected.client -or $Dump.client -ceq 'model') {$errors.Add('DUMP_IDENTITY')}
    if ($FreshSnapshot.client -cne $Expected.client -or
        (Get-ColdUtcTicks $FreshSnapshot.savedAt) -le (Get-ColdUtcTicks $Expected.seedSavedAt)) {$errors.Add('STALE_SESSION_REVISION')}
    if (@($Dump.windows).Count -ne @($windows).Count -or @($FreshSnapshot.windows).Count -ne @($windows).Count) {$errors.Add('WINDOW_COUNT')}
    $ids=@{main='main'};$persistedIds=@{main='main'}
    if ($errors.Count -eq 0) {
        for ($index=0;$index -lt @($windows).Count;$index++) {
            $old=$windows[$index];$actual=$Dump.windows[$index];$persisted=$FreshSnapshot.windows[$index]
            if ($old.type -cne 'GOAL_CALCULATOR' -or $old.coreFormSpecId -cne 'goalCalculator') {$errors.Add('UNSUPPORTED_EXPECTED_FORM')}
            if ($ids.ContainsKey($old.id) -or $ids.Values -ccontains $actual.id -or $persistedIds.Values -ccontains $persisted.id -or
                -not $actual.id -or -not $persisted.id) {$errors.Add('WINDOW_ID_BIJECTION');continue}
            $ids[$old.id]=$actual.id;$persistedIds[$old.id]=$persisted.id
            if (-not $ids.ContainsKey($old.ownerId) -or -not $persistedIds.ContainsKey($old.ownerId) -or
                $actual.ownerId -cne $ids[$old.ownerId] -or $persisted.ownerId -cne $persistedIds[$old.ownerId]) {$errors.Add('WINDOW_OWNER')}
            if ($actual.type -cne $old.type -or $actual.purpose -cne $old.purpose -or
                $actual.modal -isnot [bool] -or $actual.modal -ne $old.modal -or $actual.page -ne $old.page -or
                $persisted.type -cne $old.type -or $persisted.modal -ne $old.modal) {$errors.Add('FORM_IDENTITY')}
            if ($null -eq $actual.bounds -or $actual.bounds.width -le 0 -or $actual.bounds.height -le 0) {$errors.Add('WINDOW_NOT_VISIBLE')}
            if (-not (Test-ColdInventoryEqual $old.context $persisted.context) -or
                -not (Test-ColdInventoryEqual $old.canonicalFields $persisted.fields)) {$errors.Add('PERSISTED_CONTEXT_FIELDS')}
            $fields=@{};foreach ($field in @($actual.fields)) {
                if ($fields.ContainsKey($field.id)) {$errors.Add('DUPLICATE_FIELD')} else {$fields[$field.id]=$field}
            }
            if ($fields.Count -ne @($old.fields).Count) {$errors.Add('FIELD_COUNT')}
            foreach ($expectedField in @($old.fields)) {
                if (-not $fields.ContainsKey($expectedField.id)) {$errors.Add('FIELD_MISSING');continue}
                $field=$fields[$expectedField.id]
                if ($field.kind -cne $expectedField.kind -or $field.text -cne $expectedField.text -or
                    $field.visible -isnot [bool] -or -not $field.visible -or $field.readOnly -isnot [bool] -or $field.readOnly) {$errors.Add('ACTUAL_FIELD_VALUE')}
            }
        }
    }
    return New-RestoredWindowDecision $(if ($errors.Count) {'FAIL'} else {'WINDOW_CONTRACT_VALIDATED'}) @() @($errors.ToArray())
}

# Arming сохраняет expected pin/план до любых новых output; actual scripts не исполняются здесь.
function New-NativeRestoredWindowObserver($PhaseContext,[string]$ExpectationFile,[string]$ExpectationSha256) {
    $expectedBytes=Read-RestoredWindowBytes $ExpectationFile $PhaseContext.directory $ExpectationSha256
    $expected=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString($expectedBytes))
    if ($expected.client -cne $PhaseContext.client -or $expected.seedRevisionSha256 -cne
        $PhaseContext.seed.decoded.sessions.($PhaseContext.client).revisionSha256) {throw 'RESTORED_SEED_PIN'}
    if (@(Get-CopyProcesses $PhaseContext.root).Count -ne 0 -or
        (Test-Path -LiteralPath (Join-Path $PhaseContext.root 'CashMemory/Updates/install-journal.json'))) {throw 'RESTORED_ARM_TOO_LATE'}
    $directory=Join-Path $PhaseContext.directory 'restored-window'
    if (Test-Path -LiteralPath $directory) {throw 'RESTORED_OUTPUT_EXISTS'}
    [void][IO.Directory]::CreateDirectory($directory)
    $scriptFile=Join-Path $directory 'phase-restored.cps'
    [IO.File]::WriteAllText($scriptFile,"shot restored`nsignal observed`n",[Text.UTF8Encoding]::new($false))
    $output=Join-Path $directory 'actual-output'
    $observer=[pscustomobject]@{root=$PhaseContext.root;client=$PhaseContext.client;directory=$directory;expected=$expected;
        expectedFile=$ExpectationFile;expectedSha256=$ExpectationSha256;armedAt=[datetime]::UtcNow.ToString('o');
        launchAt=$null;phaseDirectory=$PhaseContext.directory;script=$scriptFile;scriptSha256=(Get-FileHash -LiteralPath $scriptFile).Hash.ToLowerInvariant();
        output=$output;observation=$null;decision=(New-RestoredWindowDecision 'PENDING' @('ACTUAL_RAW_DUMP_NOT_PRODUCED'))}
    Write-ColdJson (Join-Path $directory 'armed.json') ([ordered]@{root=$observer.root;client=$observer.client;armedAt=$observer.armedAt;
        expectedFile=$ExpectationFile;expectedSha256=$ExpectationSha256;script=$scriptFile;scriptSha256=$observer.scriptSha256;
        output=$output;expectedSource='PINNED_SEED_CORE_FIELD_CODEC_NOT_UI';nativePass=$false})
    return $observer
}

# Epoch должен фиксироваться до Start, а не восстанавливаться по Row.finishedAt после cleanup.
function Set-NativeRestoredWindowLaunchEpoch($Observer) {
    if ($null -ne $Observer.launchAt -or (Test-Path -LiteralPath $Observer.output)) {throw 'RESTORED_LAUNCH_EPOCH_LATE'}
    $Observer.launchAt=[datetime]::UtcNow.ToString('o')
    Write-ColdJson (Join-Path $Observer.directory 'launch-epoch.json') ([ordered]@{root=$Observer.root;launchAt=$Observer.launchAt})
}

# Только proposal разрешённых existing hooks; frozen phase safe-args этого пока НЕ допускает.
function Get-NativeRestoredWindowLaunchProposal($Observer) {
    return [pscustomobject]@{status='PENDING';args=@('--selftest',$Observer.script,'--selftest-out',$Observer.output,
        '--selftest-recovery','xml','--test-api');requiresReview=@('PHASE_SAFE_ARGS_EXPLICIT_TEST_SEAM',
        'RESTART_SELFTEST_STRIPPING','NONEMPTY_RUNNING_OWNED_SEED','WEB_REAL_CDP_BRIDGE');nativePass=$false}
}

# Reader вызывается при живой retained JVM; никакого helper-return PASS или HTTP-подделки dump.
function Read-NativeRestoredWindowObservation($Observer,$Native,$FreshObservation,$BrowserProcess=$null,[string]$BrowserOwnerFile='',
    [string]$FreshObservationFile='',[string]$FreshObservationSha256='') {
    if ($null -eq $Observer.launchAt -or $null -eq $Native -or $null -eq $Native.ui -or
        $Native.uiProcess -isnot [Diagnostics.Process]) {return New-RestoredWindowDecision 'PENDING' @('ACTUAL_RETAINED_UI_NOT_PRODUCED')}
    if (@($Observer.expected.windows).Count -eq 0) {return New-RestoredWindowDecision 'PENDING' @('SEEDED_WINDOW_NOT_PRODUCED')}
    [void](Read-RestoredWindowBytes $Observer.expectedFile $Observer.phaseDirectory $Observer.expectedSha256)
    if ((Get-FileHash -LiteralPath $Observer.script).Hash.ToLowerInvariant() -cne $Observer.scriptSha256) {throw 'RESTORED_SCRIPT_CHANGED'}
    if (-not $FreshObservationFile -or -not $FreshObservationSha256 -or -not (Test-Path -LiteralPath $FreshObservationFile -PathType Leaf)) {
        return New-RestoredWindowDecision 'PENDING' @('INDEPENDENT_FRESH_CODEC_RECEIPT_NOT_PINNED')
    }
    $saved=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString(
        (Read-RestoredWindowBytes $FreshObservationFile $Observer.phaseDirectory $FreshObservationSha256)))
    if (-not (Test-ColdInventoryEqual $saved $FreshObservation) -or $saved.stage -cne 'recovery' -or
        $saved.codec.process.OwnedRoot -cne $Observer.phaseDirectory -or
        $saved.codec.process.StartedAtTicks -lt (Get-ColdUtcTicks $Observer.launchAt)) {throw 'RESTORED_FRESH_CODEC_BINDING'}
    Assert-ColdUiReceipt $Native.ui $Observer.root $Observer.client
    $identity=[pscustomobject]@{ProcessId=$Native.ui.pid;StartedAtTicks=$Native.ui.startedAtTicks;
        ExecutablePath=$Native.ui.executablePath;OwnedRoot=$Observer.root}
    if ($Native.uiProcess.HasExited -or $identity.StartedAtTicks -lt (Get-ColdUtcTicks $Observer.launchAt)) {throw 'RESTORED_UI_NOT_FRESH'}
    Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $Native.uiProcess $Observer.root) $Observer.root $identity.ExecutablePath
    foreach ($pair in @(@{key='--selftest';value=$Observer.script},@{key='--selftest-out';value=$Observer.output})) {
        $args=@($Native.ui.args);$indices=@(0..($args.Count-1) | Where-Object {$args[$_] -ceq $pair.key})
        if ($indices.Count -ne 1 -or $indices[0]+1 -ge $args.Count -or $args[$indices[0]+1] -cne $pair.value) {
            return New-RestoredWindowDecision 'PENDING' @('ACTUAL_SELFTEST_LAUNCH_NOT_PRODUCED')
        }
    }
    $browserIdentity=$null;$owner=$null
    if ($Observer.client -ceq 'web') {
        if ($BrowserProcess -isnot [Diagnostics.Process] -or -not $BrowserOwnerFile) {
            return New-RestoredWindowDecision 'PENDING' @('ACTUAL_DOM_BROWSER_NOT_ATTACHED')
        }
        $owner=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString((Read-RestoredWindowBytes $BrowserOwnerFile $Observer.output)))
        if ($owner.profile -cne (Join-Path $Observer.output 'browser-profile') -or $BrowserProcess.HasExited) {throw 'RESTORED_BROWSER_OWNER'}
        [void](Resolve-PortableSafetyPath $owner.profile)
        $browserIdentity=Get-ColdProcessReceipt $BrowserProcess $owner.profile
        if ($browserIdentity.ExecutablePath -cne $owner.executable -or $browserIdentity.StartedAtTicks -lt (Get-ColdUtcTicks $Observer.launchAt)) {throw 'RESTORED_BROWSER_OWNER'}
        $cim=@(Get-CimInstance Win32_Process | Where-Object {$_.ProcessId -eq $BrowserProcess.Id})
        if ($cim.Count -ne 1 -or -not $cim[0].CommandLine.Contains($owner.profile)) {throw 'RESTORED_BROWSER_PROFILE'}
    }
    $raw=Join-Path $Observer.output 'phase-restored/restored.raw.json';$png=Join-Path $Observer.output 'phase-restored/restored.png'
    $log=Join-Path $Observer.output 'selftest.log';$signal=Join-Path $Observer.output 'observed'
    foreach ($file in @($raw,$png,$log,$signal)) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {return New-RestoredWindowDecision 'PENDING' @('ACTUAL_ARTIFACT_NOT_PRODUCED:'+ $file)}
        if ((Get-Item -LiteralPath $file).LastWriteTimeUtc.Ticks -lt (Get-ColdUtcTicks $Observer.launchAt)) {throw 'RESTORED_STALE_ARTIFACT'}
    }
    $bytes=Read-RestoredWindowBytes $raw $Observer.output
    $dump=ConvertFrom-ColdReceiptJson ([Text.UTF8Encoding]::new($false,$true).GetString($bytes))
    $journal=[Text.UTF8Encoding]::new($false,$true).GetString((Read-RestoredWindowBytes $log $Observer.output)).Replace("`r`n","`n")
    if ($journal -cne "SELFTEST 1 OK shot restored`n" -or (Read-RestoredWindowBytes $signal $Observer.output).Length -ne 0) {throw 'RESTORED_SELFTEST_ORDER'}
    $image=Read-RestoredWindowBytes $png $Observer.output
    if ($image.Length -lt 24 -or [Convert]::ToHexString($image[0..7]) -cne '89504E470D0A1A0A') {throw 'RESTORED_PNG'}
    if ($dump.scenario -cne 'phase-restored' -or $dump.step -cne 'restored') {throw 'RESTORED_DUMP_LABEL'}
    if ($null -eq $FreshObservation -or $null -eq $FreshObservation.ui -or
        $FreshObservation.freshOwnedRevisionObserved -isnot [bool] -or -not $FreshObservation.freshOwnedRevisionObserved -or
        $FreshObservation.ui.pid -ne $identity.ProcessId -or $FreshObservation.ui.startedAtTicks -ne $identity.StartedAtTicks) {
        return New-RestoredWindowDecision 'PENDING' @('FRESH_OWNED_CODEC_REVISION_NOT_PRODUCED')
    }
    $fresh=ConvertFrom-ColdReceiptJson $FreshObservation.codec.decoded.sessions.($Observer.client).snapshotJson
    $decision=Test-NativeRestoredWindowContract $Observer.expected $dump $fresh
    Assert-ColdProcessIdentity $identity (Get-ColdProcessReceipt $Native.uiProcess $Observer.root) $Observer.root $identity.ExecutablePath
    if ($null -ne $browserIdentity) {Assert-ColdProcessIdentity $browserIdentity (Get-ColdProcessReceipt $BrowserProcess $owner.profile) $owner.profile $owner.executable}
    if ((Get-NativePhaseSessionSha (Read-RestoredWindowBytes $raw $Observer.output)) -cne (Get-NativePhaseSessionSha $bytes)) {throw 'RESTORED_DUMP_CHANGED'}
    $Observer.observation=[ordered]@{observedAt=[datetime]::UtcNow.ToString('o');identity=$identity;browserIdentity=$browserIdentity;
        rawDump=$raw;dumpRevisionSha256=(Get-NativePhaseSessionSha $bytes);png=$png;pngSha256=(Get-NativePhaseSessionSha $image);
        selftestLog=$log;signal=$signal;freshSnapshotRevisionSha256=$FreshObservation.codec.decoded.sessions.($Observer.client).revisionSha256;
        expectedSha256=$Observer.expectedSha256;freshObservationFile=$FreshObservationFile;freshObservationSha256=$FreshObservationSha256;
        decision=$decision;nativePass=$false}
    $Observer.decision=$decision
    Write-ColdJson (Join-Path $Observer.directory 'observation.json') $Observer.observation
    return $decision
}

# Cleanup - отдельный поздний receipt; отсутствие observation не меняется на успех по census=0.
function Complete-NativeRestoredWindowObserver($Observer) {
    $remaining=@(Get-CopyProcesses $Observer.root)
    $decision=if ($null -eq $Observer.observation) {New-RestoredWindowDecision 'PENDING' @('ACTUAL_OBSERVATION_NOT_PRODUCED')} else {$Observer.decision}
    if ($remaining.Count) {$decision=New-RestoredWindowDecision 'FAIL' @() @('CLEANUP_PROCESSES_ALIVE')}
    $receipt=Join-Path $Observer.directory 'restored-window-receipt.json'
    Write-ColdJson $receipt ([ordered]@{root=$Observer.root;client=$Observer.client;armedAt=$Observer.armedAt;launchAt=$Observer.launchAt;
        finishedAt=[datetime]::UtcNow.ToString('o');observation=$Observer.observation;remaining=$remaining;decision=$decision;nativePass=$false})
    return [pscustomobject]@{Receipt=$receipt;ReceiptSha256=(Get-FileHash -LiteralPath $receipt).Hash.ToLowerInvariant();decision=$decision}
}

# JDK expectation/strict-schema adapter; DumpTrees компилируется только в private Temp, не target.
function Get-NativeRestoredWindowCodecSource {
    return @'
import java.nio.file.*;
import java.util.*;
import ru.cashprediction.core.session.codec.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.parity.pipeline.DumpTrees;

/** Pure core codec/schema bridge; не открывает UI и не создаёт sessions в CashMemory. */
final class NativeRestoredWindowCodec {
    /** Читает snapshot/dump; actual origin не выводится из корректной схемы JSON. */
    public static void main(String[] args) throws Exception {
        if (args.length!=2) throw new IllegalArgumentException("RESTORED_CODEC_ARGS");
        String text=Files.readString(Path.of(args[1]));
        if (args[0].equals("dump")) { DumpTrees.read(text);System.out.println("UI_DUMP_SCHEMA_VALIDATED_NOT_UI_ORIGIN");return; }
        if (!args[0].equals("expected")) throw new IllegalArgumentException("RESTORED_CODEC_MODE");
        var seed=new JsonSnapshotCodec().decode(text);var windows=new ArrayList<Object>();
        for (var window: seed.windows()) {
            if (window.type()!=WindowType.GOAL_CALCULATOR) throw new IllegalArgumentException("RESTORED_UNSUPPORTED_FORM");
            // JavaFX: Dialog → Swing: JDialog → Web: dialog.
            var spec=new GoalCalculatorForm().spec(null);
            var fields=new ArrayList<Object>();
            for (var page: spec.pages()) for (var row:page.rows()) {
                List<FieldSpec> specs=row instanceof FormRow.Field f?List.of(f.field()):row instanceof FormRow.Inline i?i.fields():List.of();
                for (var field: specs) {
                    if (!window.fields().containsKey(field.id())) throw new IllegalArgumentException("RESTORED_SEED_FIELD_MISSING");
                    fields.add(Map.of("id",field.id(),"kind",field.kind().name(),"text",FieldCodec.display(field.kind(),window.fields().get(field.id()))));
                }
            }
            var result=new LinkedHashMap<String,Object>();result.put("id",window.id());result.put("type",window.type().name());
            result.put("coreFormSpecId",spec.formId());result.put("purpose",spec.purpose());result.put("modal",window.modal());
            result.put("ownerId",window.ownerId());result.put("page",Integer.parseInt(window.context().getOrDefault("page","0")));
            result.put("context",window.context());result.put("canonicalFields",window.fields());result.put("fields",fields);windows.add(result);
        }
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        System.out.println(JsonWriter.write(Map.of("client",seed.client(),"seedSavedAt",seed.savedAt().toString(),"seedRevisionSha256",sha,"windows",windows)));
    }
}
'@
}
