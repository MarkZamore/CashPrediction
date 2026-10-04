package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.CoreWindowFactory;
import ru.cashprediction.core.app.flow.ToolsFlow;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/** Сравнивает результат обычных команд с теми же действиями восстановленных окон. */
class FreshRestoredEquivalenceTest {
    @TempDir Path home;

    /** Сверка при включённом сценарии записывает настоящий баланс и тот же текст разницы. */
    @Test void reconcileUsesPlainBalanceForPlanAndStatus() {
        compareReconcile(Money.parse("5000"));
    }

    /** Совпадение фактического и настоящего балансов не создаёт историю или статус. */
    @Test void reconcileEqualPlainBalanceHasNoHistory() {
        compareReconcile(Money.ZERO);
    }

    /** Применение меняющего суммы сценария даёт одинаковые план, сброс вида и единственный шаг истории. */
    @Test void applyingWhatIfWithChangesIsEquivalent() {
        CaptureContext fresh = context();
        CaptureContext restored = context();
        Plan before = fresh.document.plan();
        compareWhatIf(fresh, restored);
        assertNotEquals(before, restored.document.plan());
        assertTrue(restored.document.canUndo());
        fresh.document.undo();
        restored.document.undo();
        assertEquals(before, restored.document.plan());
        assertEquivalent(fresh, restored);
        assertFalse(restored.document.canUndo());
    }

    /** У плана без операций коэффициент дохода не меняет данные, но оба пути выключают сценарий. */
    @Test void applyingWhatIfToEqualPlanStillResetsWithoutHistory() {
        CaptureContext fresh = context();
        CaptureContext restored = context();
        Plan empty = fresh.document.plan().withRules(List.of()).withOneTimes(List.of()).withAdjustments(List.of());
        WhatIf scenario = WhatIf.ofPercent(-10, 0, Money.ZERO);
        for (CaptureContext fake : List.of(fresh, restored)) {
            fake.document.replace(empty, null, false, List.of());
            fake.document.setViewState(fake.document.viewState().withWhatIf(scenario));
        }
        compareWhatIf(fresh, restored);
        assertEquals(empty, restored.document.plan());
        assertFalse(restored.document.canUndo());
        assertFalse(restored.document.isDirty());
    }

    /** Внешняя правка после показа формы запрещает оба пути переименования, не уничтожая черновик. */
    @Test void renameExternalConflictKeepsFreshAndRestoredFormsAndDraft() throws java.io.IOException {
        for (boolean restore : List.of(false, true)) {
            CaptureContext fake = new CaptureContext(home.resolve(restore ? "restored" : "fresh"), ClientProfile.swing());
            // Этот сценарий реально пишет managed-файл; общий CaptureContext по умолчанию readonly.
            var managedGuard = new ru.cashprediction.core.app.flow.ExternalChangeGuard(
                    new ru.cashprediction.core.service.storage.FilePlanStorage(fake.environment.cashMemory()));
            ru.cashprediction.core.app.flow.FileFlow[] managedFiles = new ru.cashprediction.core.app.flow.FileFlow[1];
            var managedContext = (ru.cashprediction.core.app.flow.FlowContext) java.lang.reflect.Proxy.newProxyInstance(
                    ru.cashprediction.core.app.flow.FlowContext.class.getClassLoader(),
                    new Class<?>[] { ru.cashprediction.core.app.flow.FlowContext.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "externalChanges" -> managedGuard;
                        case "planStorage" -> managedGuard.storage();
                        case "files" -> managedFiles[0];
                        default -> fake.invoke(proxy, method, args);
                    });
            managedFiles[0] = new ru.cashprediction.core.app.flow.FileFlow(managedContext);
            var repository = new ru.cashprediction.core.io.PlanRepository(fake.environment.cashMemory());
            Path original = repository.pathFor("original");
            Path target = repository.pathFor("renamed");
            Plan saved = fake.document.plan().withName("original");
            repository.save(saved, original);
            managedGuard.remember(original);
            var rememberedTime = java.nio.file.Files.getLastModifiedTime(original);
            Plan draft = saved.withNote("local draft");
            fake.document.replace(draft, original, true, List.of());
            if (restore) {
                new CoreWindowFactory(managedContext).open(new WindowState("rename1", WindowType.TEXT_INPUT, true,
                        "main", null, Map.of("purpose", "rename"), Map.of("value", "renamed")),
                        "main", window -> { }, reason -> fail(reason));
            } else {
                managedFiles[0].rename();
                fake.sessions.getFirst().fieldChanged("value", "renamed", true, 1);
            }
            var session = fake.sessions.getFirst();
            assertEquals(ru.cashprediction.core.ui.form.Problem.NONE, session.view().problem());
            // Изменение приходит после открытия; метка выставлена явно, без зависимости от точности часов диска.
            repository.save(saved.withNote("external edit"), original);
            java.nio.file.Files.setLastModifiedTime(original,
                    java.nio.file.attribute.FileTime.fromMillis(rememberedTime.toMillis() + 60_000));
            String externalContent = java.nio.file.Files.readString(original);
            assertTrue(managedGuard.changedExternally(original));
            session.buttonPressed("rename");
            assertFalse(session.isClosed());
            assertEquals(ru.cashprediction.core.ui.form.Problem.Severity.ERROR, session.view().problem().severity());
            assertEquals(ru.cashprediction.core.ui.text.UiText.get("alert.external.header", "original"),
                    session.view().problem().text());
            assertEquals("renamed", session.captureState().fields().get("value"));
            assertEquals(draft, fake.document.plan());
            assertEquals(original, fake.document.file().orElseThrow());
            assertTrue(fake.document.isDirty());
            assertFalse(fake.document.canUndo());
            assertEquals(externalContent, java.nio.file.Files.readString(original));
            assertFalse(java.nio.file.Files.exists(target));
            assertTrue(managedGuard.changedExternally(original));
            assertTrue(fake.statusKeys.isEmpty());
            assertTrue(fake.port.calls("showAlert").isEmpty());
        }
    }

    /** Реальные формы свежего и восстановленного пути подтверждаются одинаковым значением. */
    private void compareReconcile(Money difference) {
        CaptureContext fresh = context();
        CaptureContext restored = context();
        Plan before = fresh.document.plan();
        Money plain = new PlanDocument(before, null, () -> CaptureContext.TODAY)
                .forecast().balanceAt(CaptureContext.TODAY);
        assertNotEquals(plain, fresh.document.forecast().balanceAt(CaptureContext.TODAY));
        Money actual = plain.plus(difference);
        fresh.edits.reconcile();
        fresh.sessions.getFirst().fieldChanged("value", actual.formatPlain(), true, 1);
        fresh.sessions.getFirst().buttonPressed("reconcile");
        new CoreWindowFactory(restored.flow).open(new WindowState("reconcile1", WindowType.TEXT_INPUT, true,
                "main", null, Map.of("purpose", "reconcile"), Map.of("value", actual.formatPlain())),
                "main", window -> { }, reason -> fail(reason));
        restored.sessions.getFirst().buttonPressed("reconcile");
        assertTrue(fresh.sessions.getFirst().isClosed());
        assertTrue(restored.sessions.getFirst().isClosed());
        assertEquivalent(fresh, restored);
        assertEquals(difference.isZero() ? List.of() : List.of("status.msg.reconciled"), restored.statusKeys);
        if (difference.isZero()) {
            assertEquals(before, restored.document.plan());
            assertFalse(restored.document.canUndo());
        } else {
            assertEquals(List.of(List.of(difference.formatSigned() + " " + before.currency())), restored.statusArguments);
            PlanDocument plainAfter = new PlanDocument(restored.document.plan(), null, () -> CaptureContext.TODAY);
            assertEquals(actual, plainAfter.forecast().balanceAt(CaptureContext.TODAY));
            fresh.document.undo();
            restored.document.undo();
            assertEquals(before, restored.document.plan());
            assertEquivalent(fresh, restored);
            assertFalse(restored.document.canUndo());
        }
    }

    /** Свежий путь использует ToolsFlow, восстановленный - фабрику и семантическую кнопку alert. */
    private void compareWhatIf(CaptureContext fresh, CaptureContext restored) {
        new ToolsFlow(fresh.flow).applyWhatIf();
        fresh.port.pendingAlerts().getFirst().press("apply");
        new CoreWindowFactory(restored.flow).open(new WindowState("apply1", WindowType.ALERT, true,
                "main", null, Map.of("purpose", "applyWhatIf", "targetId", ""), Map.of()),
                "main", window -> { }, reason -> fail(reason));
        restored.alerts.getFirst().onButton().accept("apply");
        restored.alerts.getFirst().onButton().accept("apply");
        assertEquivalent(fresh, restored);
        assertTrue(restored.document.viewState().whatIf().isNone());
        assertEquals(List.of("status.msg.whatIfApplied"), restored.statusKeys);
        assertEquals(1, fresh.port.calls("showAlert").size());
        assertEquals(1, restored.port.calls("showAlert").size());
    }

    /** Сравнивает данные, состояние сценария, историю и все параметры статуса. */
    private static void assertEquivalent(CaptureContext fresh, CaptureContext restored) {
        assertEquals(fresh.document.plan(), restored.document.plan());
        assertEquals(fresh.document.viewState(), restored.document.viewState());
        assertEquals(fresh.document.canUndo(), restored.document.canUndo());
        assertEquals(fresh.document.isDirty(), restored.document.isDirty());
        assertEquals(fresh.statusKeys, restored.statusKeys);
        assertEquals(fresh.statusArguments, restored.statusArguments);
    }

    /** Создаёт одинаковый план с включённым сценарием расходов и доходов. */
    private CaptureContext context() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        fake.document.setViewState(fake.document.viewState().withWhatIf(WhatIf.ofPercent(-10, 10, Money.ZERO)));
        return fake;
    }
}
