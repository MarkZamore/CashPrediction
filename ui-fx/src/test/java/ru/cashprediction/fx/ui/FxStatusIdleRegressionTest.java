package ru.cashprediction.fx.ui;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.RecorderStatus;
import ru.cashprediction.core.app.StatusMessages;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.view.status.StatusLevel;
import ru.cashprediction.core.ui.token.ColorToken;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет бюджет настоящих сценариев и готовность stores без запуска JavaFX Toolkit. */
class FxStatusIdleRegressionTest {
    @Test void passiveIdleDoesNotWaitForEverySnapshotDebounce() {
        assertEquals(40, FxUiDriver.idleDelayMillis(0, 5000));
        assertEquals(440, FxUiDriver.idleDelayMillis(400, 5000));
        assertEquals(640, FxUiDriver.idleDelayMillis(600, 5000));
        assertEquals(100, FxUiDriver.idleDelayMillis(600, 100));
        assertEquals(0, FxUiDriver.idleDelayMillis(0, -1));
    }
    @Test void actualS08TraceKeepsSuccessLeaseWithExplicitBoundedWorkload() {
        long budget = budget("s08-alerts", "format", false);
        long legacy = budget("s08-alerts", "format", true);
        System.out.println("s08 snapshot-to-format idle current=" + budget + " legacy=" + legacy);
        Instant start = Instant.parse("2026-09-13T00:00:00Z");
        var messages = StatusMessages.EMPTY.show("snapshot requested", StatusLevel.SUCCESS, start);
        // 1000 ms - явная injected стоимость обработки, не измерение CI или native rendering.
        long workload = 1000;
        assertTrue(messages.visible(start.plusMillis(legacy + workload)).isEmpty(), "legacy padding plus explicit workload expires");
        var visible = messages.visible(start.plusMillis(budget + workload)).orElseThrow();
        assertEquals("snapshot requested", visible.text());
        assertEquals(ColorToken.INCOME, visible.level().color());
    }
    @Test void otherReportedScenarioBudgetsDoNotConsumeWholeMessageLease() {
        for (var item : List.of(new String[]{"s03-chart","whole-horizon"},
                new String[]{"s10-filter-empty-states","hidden-events"},
                new String[]{"s11-past-group-reveal","past-context-toggle"})) {
            long millis = budget(item[0], item[1], false);
            System.out.println(item[0] + " idle current=" + millis + " legacy=" + budget(item[0], item[1], true));
            assertTrue(millis < 2500, item[0] + ": only actual gesture settle should consume lease");
            var now = Instant.EPOCH;
            assertTrue(StatusMessages.EMPTY.show("sample opened", StatusLevel.INFO, now)
                    .visible(now.plusMillis(millis)).isPresent());
        }
    }
    @Test void explicitTenSecondExpiryIsNotFrozenOrExtended() {
        Instant now = Instant.EPOCH;
        var messages = StatusMessages.EMPTY.show("sample opened", StatusLevel.INFO, now);
        assertTrue(messages.visible(now.plusMillis(9999)).isPresent());
        assertTrue(messages.visible(now.plusMillis(10000)).isEmpty());
    }
    @Test void storeBarrierRequiresActualUniqueExpectedOutcomes() {
        var ids = List.of("registry","xml");
        var success = new StoreStatus("registry", true, Instant.EPOCH, "");
        assertFalse(FxUiDriver.startupStoresReady(ids, List.of(success)));
        assertFalse(FxUiDriver.startupStoresReady(ids, List.of(success, success)));
        assertFalse(FxUiDriver.startupStoresReady(ids, List.of(success,new StoreStatus("other",false,null,"IO"))));
        assertFalse(FxUiDriver.startupStoresReady(ids, List.of(success,new StoreStatus("xml",true,null,""))));
        assertFalse(FxUiDriver.startupStoresReady(ids, List.of(success,new StoreStatus("xml",true,null,"quarantine notice"))));
        assertFalse(FxUiDriver.startupStoresReady(ids, List.of(success,new StoreStatus("xml",false,null,""))));
        assertTrue(FxUiDriver.startupStoresReady(ids, List.of(success,new StoreStatus("xml",false,null,"IO"))));
        assertTrue(FxUiDriver.startupStoresReady(ids, List.of(success,new StoreStatus("xml",true,Instant.EPOCH,""))));
        assertFalse(FxUiDriver.startupStoresReady(List.of("xml","xml"),List.of(success,success)));
    }
    @Test void restoreQuestionAndDisabledRecorderAreNotStartupDeadlocks() {
        assertTrue(FxUiDriver.snapshotWaitNotRequired(RecorderStatus.PENDING_RESTORE,true));
        assertFalse(FxUiDriver.snapshotWaitNotRequired(RecorderStatus.PENDING_RESTORE,false));
        assertTrue(FxUiDriver.snapshotWaitNotRequired(RecorderStatus.DISABLED_SECOND_INSTANCE,false));
        assertFalse(FxUiDriver.snapshotWaitNotRequired(RecorderStatus.NOT_STARTED,true));
        assertFalse(FxUiDriver.snapshotWaitNotRequired(RecorderStatus.RECORDING,true));
        assertTrue(FxUiDriver.startupDecision("loadDiagnostics"));
        assertTrue(FxUiDriver.startupDecision("alreadyRunning"));
        assertFalse(FxUiDriver.startupDecision("about"));
    }
    @Test void retainedClosedFormsDoNotBlockButLivePendingShowMustSettle() {
        assertTrue(FxUiDriver.formReady(true,false));
        assertTrue(FxUiDriver.formReady(true,true));
        assertTrue(FxUiDriver.formReady(false,true));
        assertFalse(FxUiDriver.formReady(false,false));
    }
    /** Считает только реальные idle-вызовы раннера и заданные FX gesture delays; не изображает GUI-время. */
    private static long budget(String name, String target, boolean legacy) {
        boolean active = false;
        long total = 0;
        for (var line : SelfTestScript.load(name).lines()) {
            var command = line.command();
            if (name.equals("s08-alerts") ? command instanceof SelfTestCommand.Menu m && m.idOrPath().equals("recovery.snapshotNow")
                    : command instanceof SelfTestCommand.Sample) { active = true; total = 0; }
            if (!active) continue;
            long pending = command instanceof SelfTestCommand.Sample ? 400
                    : command instanceof SelfTestCommand.FilterType ? ru.cashprediction.core.ui.token.DesignTokens.FILTER_DEBOUNCE_MS
                    : command instanceof SelfTestCommand.Hover h && h.target().startsWith("card:") ? 350 : 0;
            if (command instanceof SelfTestCommand.Dump) {
                total += delay(0,legacy); // idle перед чтением dump
                if (((SelfTestCommand.Dump) command).step().equals(target)) return total;
            }
            if (command instanceof SelfTestCommand.Wait wait) total += wait.millis();
            total += delay(pending,legacy); // idle после каждой строки
        }
        throw new AssertionError("missing actual target " + name + "/" + target);
    }
    private static long delay(long pending, boolean legacy) {
        return legacy ? pending + ru.cashprediction.core.session.SessionRecorder.DEBOUNCE.toMillis() + 40
                : FxUiDriver.idleDelayMillis(pending,5000);
    }
}
