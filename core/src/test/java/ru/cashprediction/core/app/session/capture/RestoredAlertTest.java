package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.CoreWindowFactory;
import ru.cashprediction.core.app.flow.SessionBridge;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет действия и асинхронный показ восстановленных подтверждений без повторного вопроса. */
class RestoredAlertTest {
    @TempDir Path home;

    /** Реальный shown, а не видимость ручки, запускает продолжение ровно один раз. */
    @Test void delayedShownPreservesIdentityAndBounds() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.fx("25"));
        fake.delayedShow = true;
        List<StatefulWindow> shown = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        WindowBounds bounds = new WindowBounds(31, 47, 640, 360);
        WindowState state = alert("deleteRule", "r1", bounds);
        new CoreWindowFactory(fake.flow).open(state, "parent", shown::add, failed::add);
        var restored = fake.alerts.getFirst();
        assertTrue(restored.handle().showing());
        assertTrue(shown.isEmpty());
        assertTrue(failed.isEmpty());
        restored.session().shown();
        restored.session().shown();
        assertEquals(List.of(restored.session()), shown);
        assertEquals(1, fake.registrations);
        WindowState captured = restored.session().captureState();
        assertEquals("w9", captured.id());
        assertEquals("parent", captured.ownerId());
        assertEquals(bounds, captured.bounds());
        assertEquals(state.context(), captured.context());
        assertTrue(fake.sessions.isEmpty());
        WindowBounds moved = new WindowBounds(80, 90, 700, 500);
        ((ru.cashprediction.core.app.fake.FakeWindowHandle) restored.handle()).setBounds(moved);
        assertEquals(moved, restored.session().captureState().bounds());
    }

    /** Ранний shown доставляется даже после показа внутри операции контроллера. */
    @Test void earlyShownAndDeleteAreOnce() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        List<StatefulWindow> shown = new ArrayList<>();
        new CoreWindowFactory(fake.flow).open(alert("deleteRule", "r1", null), "main", shown::add, reason -> fail(reason));
        assertEquals(1, shown.size());
        var restored = fake.alerts.getFirst();
        restored.onButton().accept("delete");
        restored.onButton().accept("delete");
        assertTrue(fake.document.plan().findRule(new RuleId("r1")).isEmpty());
        assertTrue(fake.document.canUndo());
        assertEquals(List.of("status.msg.ruleDeleted"), fake.statusKeys);
        fake.document.undo();
        assertTrue(fake.document.plan().findRule(new RuleId("r1")).isPresent());
        assertFalse(fake.document.canUndo());
        assertEquals(1, fake.port.calls("showAlert").size());
        assertEquals(1, fake.unregistrations);
    }

    /** Удаление разовой операции применяет один шаг истории и не задаёт ещё один вопрос. */
    @Test void deleteOneTimeIsApplied() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        new CoreWindowFactory(fake.flow).open(alert("deleteOneTime", "t1", null), "main", window -> { }, reason -> fail(reason));
        fake.alerts.getFirst().onButton().accept("delete");
        assertTrue(fake.document.plan().findOneTime(new TxId("t1")).isEmpty());
        assertEquals(List.of("status.msg.oneTimeDeleted"), fake.statusKeys);
        fake.document.undo();
        assertTrue(fake.document.plan().findOneTime(new TxId("t1")).isPresent());
        assertFalse(fake.document.canUndo());
        assertEquals(1, fake.port.calls("showAlert").size());
    }

    /** Первый ответ Отмена исключает последующий подтверждающий ответ и любые изменения. */
    @Test void cancelWinsOverDuplicateConfirm() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        Plan before = fake.document.plan();
        new CoreWindowFactory(fake.flow).open(alert("deleteRule", "r1", null), "main", window -> { }, reason -> fail(reason));
        fake.alerts.getFirst().onButton().accept("cancel");
        fake.alerts.getFirst().onButton().accept("delete");
        assertEquals(before, fake.document.plan());
        assertFalse(fake.document.canUndo());
        assertTrue(fake.statusKeys.isEmpty());
    }

    /** Отмена каждого назначения не меняет план и сохраняет параметры что-если. */
    @Test void cancelDoesNotApplyAnyPurpose() {
        for (String purpose : List.of("deleteRule", "deleteOneTime", "actualize", "applyWhatIf", "clearSnapshots")) {
            CaptureContext fake = new CaptureContext(home, ClientProfile.web());
            fake.document.replace(fake.document.plan().withStart(CaptureContext.TODAY.minusMonths(1),
                    fake.document.plan().startBalance()), null, true, List.of());
            WhatIf whatIf = WhatIf.ofPercent(-10, 10, Money.parse("5000"));
            fake.document.setViewState(fake.document.viewState().withWhatIf(whatIf));
            Plan before = fake.document.plan();
            String target = "deleteRule".equals(purpose) ? "r1" : "deleteOneTime".equals(purpose) ? "t1" : "";
            new CoreWindowFactory(fake.flow).open(alert(purpose, target, null), "main", window -> { }, reason -> fail(reason));
            fake.alerts.getFirst().onButton().accept("cancel");
            assertEquals(before, fake.document.plan(), purpose);
            assertEquals(whatIf, fake.document.viewState().whatIf(), purpose);
            assertFalse(fake.document.canUndo(), purpose);
            assertTrue(fake.statusKeys.isEmpty(), purpose);
        }
    }

    /** Что-если меняет суммы, корректировки и дополнительную экономию; повтор ответа не умножает их второй раз. */
    @Test void whatIfAppliesAmountsAdjustmentsAndExtraSavingOnce() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        OccurrenceKey key = new OccurrenceKey(new RuleId("r1"), CaptureContext.TODAY.plusDays(4));
        fake.document.replace(fake.document.plan().withAdjustmentPut(new Adjustment(key,
                new Adjustment.ChangeAmount(Money.parse("10000")), "kept note")), null, true, List.of());
        fake.document.setViewState(fake.document.viewState().withWhatIf(WhatIf.ofPercent(-10, 10, Money.parse("5000"))));
        new CoreWindowFactory(fake.flow).open(alert("applyWhatIf", "", null), "main", window -> { }, reason -> fail(reason));
        String content = fake.alerts.getFirst().session().spec().content();
        assertTrue(content.contains(UiText.get("s2.edit.whatIf.income", "0,90")));
        assertTrue(content.contains(UiText.get("s2.edit.whatIf.expense", "1,10")));
        fake.alerts.getFirst().onButton().accept("apply");
        fake.alerts.getFirst().onButton().accept("apply");
        Plan plan = fake.document.plan();
        assertEquals(Money.parse("72000"), plan.findRule(new RuleId("r1")).orElseThrow().amount());
        assertEquals(Money.parse("49500"), plan.findRule(new RuleId("r3")).orElseThrow().amount());
        assertEquals(Money.parse("54000"), plan.findOneTime(new TxId("t1")).orElseThrow().amount());
        Adjustment.ChangeAmount changed = assertInstanceOf(Adjustment.ChangeAmount.class, plan.findAdjustment(key).orElseThrow().action());
        assertEquals(Money.parse("9000"), changed.amount());
        assertEquals("kept note", plan.findAdjustment(key).orElseThrow().note());
        assertEquals(6, plan.rules().size());
        RecurringRule saving = plan.rules().getLast();
        assertEquals(Money.parse("5000"), saving.amount());
        assertEquals(CaptureContext.TODAY, saving.from());
        assertTrue(fake.document.viewState().whatIf().isNone());
        assertEquals(List.of("status.msg.whatIfApplied"), fake.statusKeys);
        assertEquals(1, fake.port.calls("showAlert").size());
        fake.document.undo();
        assertEquals(5, fake.document.plan().rules().size());
        assertEquals(Money.parse("80000"), fake.document.plan().findRule(new RuleId("r1")).orElseThrow().amount());
        assertFalse(fake.document.canUndo());
    }

    /** Прежний снимок без параметров что-если восстанавливает окно, не выдумывая изменение плана. */
    @Test void legacyWhatIfWithoutParametersDoesNotInventChanges() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        Plan before = fake.document.plan();
        new CoreWindowFactory(fake.flow).open(alert("applyWhatIf", "", null), "main", window -> { }, reason -> fail(reason));
        fake.alerts.getFirst().onButton().accept("apply");
        assertEquals(before, fake.document.plan());
        assertFalse(fake.document.canUndo());
    }

    /** Актуализация переносит начало, берёт настоящий баланс и удаляет прошлые разовые операции. */
    @Test void actualizeUsesPlainBalanceAndKeepsPhaseAnchor() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        Plan plan = fake.document.plan().withStart(CaptureContext.TODAY.minusMonths(1), Money.parse("150000"));
        RecurringRule original = plan.findRule(new RuleId("r1")).orElseThrow();
        plan = plan.withRuleReplaced(new RecurringRule(original.id(), original.title(), original.kind(), original.amount(),
                original.category(), new Recurrence.Monthly(5, 2), null, null,
                original.weekendPolicy(), original.enabled(), original.note()));
        plan = plan.withOneTimeAdded(new OneTimeTransaction(new TxId("old"), CaptureContext.TODAY.minusDays(1),
                "past", Kind.INCOME, Money.parse("2000"), "", ""));
        fake.document.replace(plan, null, true, List.of());
        Money expectedBalance = fake.document.forecast().balanceAt(CaptureContext.TODAY.minusDays(1));
        fake.document.setViewState(fake.document.viewState().withWhatIf(WhatIf.ofPercent(-10, 10, Money.parse("5000"))));
        new CoreWindowFactory(fake.flow).open(alert("actualize", "", null), "main", window -> { }, reason -> fail(reason));
        assertTrue(fake.alerts.getFirst().session().spec().content().contains(expectedBalance.format(plan.currency())));
        fake.alerts.getFirst().onButton().accept("actualize");
        fake.alerts.getFirst().onButton().accept("actualize");
        assertEquals(CaptureContext.TODAY, fake.document.plan().startDate());
        assertEquals(expectedBalance, fake.document.plan().startBalance());
        assertTrue(fake.document.plan().findOneTime(new TxId("old")).isEmpty());
        assertEquals(CaptureContext.TODAY.minusMonths(1), fake.document.plan().findRule(new RuleId("r1")).orElseThrow().from());
        assertEquals(List.of("status.msg.actualized"), fake.statusKeys);
        fake.document.undo();
        assertEquals(plan, fake.document.plan());
        assertFalse(fake.document.canUndo());
    }

    /** Очистка идёт через рекордер, сохраняя текущий маркер running, и не запрашивает подтверждение снова. */
    @Test void clearSnapshotsKeepsRecorderMarker() throws SessionStoreException {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        var store = RegistrySessionStore.inMemory("swing", fake.environment.cashMemory());
        Instant instant = Instant.parse("2026-10-01T00:00:00Z");
        SessionBridge bridge = new SessionBridge(fake.flow);
        fake.recorder = new SessionRecorder("swing", List.of(store), UiExecutor.direct(), bridge,
                fake.port.scheduler(), Clock.fixed(instant, ZoneOffset.UTC), 1234);
        fake.recorder.start();
        store.save(SessionSnapshot.of(instant, "swing", bridge.captureMain(), bridge.capturePlan(), List.of()));
        assertTrue(store.load().isPresent());
        new CoreWindowFactory(fake.flow).open(alert("clearSnapshots", "", null), "main", window -> { }, reason -> fail(reason));
        fake.alerts.getFirst().onButton().accept("clear");
        fake.alerts.getFirst().onButton().accept("clear");
        assertTrue(store.load().isEmpty());
        assertTrue(fake.recorder.isStarted());
        assertEquals(SessionMarker.RUNNING, store.readMarker().orElseThrow().state());
        assertEquals(List.of("status.msg.snapshotsCleared"), fake.statusKeys);
        assertEquals(1, fake.port.calls("showAlert").size());
        fake.recorder.shutdownClean();
        store.clear();
    }

    /** Сбой операции открытия сообщает onFailed один раз, не создавая окно. */
    @Test void openingFailureHasSingleFailureCallback() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        fake.failAlertOpen = true;
        List<String> failed = new ArrayList<>();
        new CoreWindowFactory(fake.flow).open(alert("deleteRule", "r1", null), "main", window -> fail(), failed::add);
        assertEquals(List.of("alert open failure"), failed);
        assertTrue(fake.alerts.isEmpty());
    }

    /** Исключение продолжения shown не подменяется вторым противоположным callback. */
    @Test void continuationFailureDoesNotCallOnFailed() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        List<String> failed = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> new CoreWindowFactory(fake.flow).open(alert("deleteRule", "r1", null),
                "main", window -> { throw new IllegalStateException("continuation failure"); }, failed::add));
        assertTrue(failed.isEmpty());
    }

    /** Состояние подтверждения без введённых полей. */
    private static WindowState alert(String purpose, String target, WindowBounds bounds) {
        return new WindowState("w9", WindowType.ALERT, true, "main", bounds,
                Map.of("purpose", purpose, "targetId", target), Map.of());
    }
}
