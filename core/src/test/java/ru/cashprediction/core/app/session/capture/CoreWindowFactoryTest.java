package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.CoreWindowFactory;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.form.FormSession;

/** Проверяет реальные действия восстановленных форм, а не только их успешное создание. */
class CoreWindowFactoryTest {
    @TempDir Path home;

    /** Видимая ручка не завершает цепочку; только shown разрешает следующее окно, ровно один раз. */
    @Test void asynchronousShownIsTheOnlyHandshake() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        fake.delayedShow = true;
        List<StatefulWindow> shown = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        WindowState state = new WindowState("w1", WindowType.PLAN_SETTINGS, true, "main", null, Map.of(), Map.of());
        new CoreWindowFactory(fake.flow).open(state, "main", shown::add, failed::add);
        FormSession session = fake.sessions.getFirst();
        assertTrue(session.handle().showing());
        assertTrue(shown.isEmpty());
        assertTrue(failed.isEmpty());
        session.shown();
        session.shown();
        assertEquals(List.of(session), shown);
        assertEquals(1, fake.registrations);
    }

    /** Показ внутри openForm до подписки фабрики также подтверждается ровно один раз. */
    @Test void earlyShownIsDelivered() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        List<StatefulWindow> shown = new ArrayList<>();
        new CoreWindowFactory(fake.flow).open(new WindowState("w1", WindowType.PLAN_SETTINGS, true,
                "main", null, Map.of(), Map.of()), "main", shown::add, reason -> fail(reason));
        assertEquals(fake.sessions, shown);
        fake.sessions.getFirst().shown();
        assertEquals(1, shown.size());
    }

    /** Ошибка открытия после раннего shown не подтверждает фабрике несуществующее окно. */
    @Test void failedOpeningAfterEarlyShownReportsOnlyFailure() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        fake.failFormOpenAfterShown = true;
        List<StatefulWindow> shown = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        new CoreWindowFactory(fake.flow).open(new WindowState("failed1", WindowType.PLAN_SETTINGS, true,
                "main", null, Map.of(), Map.of()), "main", shown::add, failed::add);
        assertTrue(shown.isEmpty());
        assertEquals(List.of("form open failure after shown"), failed);
        var session = fake.sessions.getFirst();
        assertTrue(session.isClosed());
        session.shown();
        assertTrue(shown.isEmpty());
        assertEquals(1, failed.size());
        assertTrue(fake.statusKeys.isEmpty());
        assertFalse(fake.document.canUndo());
    }

    /** Восстановленный редактор меняет правило и создаёт шаг отмены. */
    @Test void restoredRuleResultIsApplied() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        WindowState state = new WindowState("w2", WindowType.RULE_EDITOR, true, "w1", new WindowBounds(2, 3, 600, 400),
                Map.of("mode", "edit", "ruleId", "r1"), Map.of("amount", "95000,00"));
        new CoreWindowFactory(fake.flow).open(state, "parent", shown -> { }, failed -> { });
        FormSession session = fake.sessions.getFirst();
        assertEquals("parent", session.ownerId());
        assertEquals(state.bounds(), fake.placement.bounds());
        assertEquals("95000,00", session.state().value("amount"));
        session.buttonPressed("ok");
        assertEquals("95000,00", fake.document.plan().rules().getFirst().amount().formatPlain());
        assertTrue(fake.document.canUndo());
    }

    /** Исчезнувшая после открытия цель оставляет форму открытой и не воскрешает правило. */
    @Test void missingTargetAtCommitKeepsEditorOpen() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        WindowState state = new WindowState("w2", WindowType.RULE_EDITOR, true, "main", null,
                Map.of("mode", "edit", "ruleId", "r1"), Map.of("amount", "95000,00"));
        new CoreWindowFactory(fake.flow).open(state, "main", shown -> { }, reason -> fail(reason));
        FormSession session = fake.sessions.getFirst();
        fake.document.edit("remove", plan -> plan.withRuleRemoved(new ru.cashprediction.core.model.RuleId("r1")));
        session.buttonPressed("ok");
        assertFalse(session.isClosed());
        assertTrue(fake.document.plan().findRule(new ru.cashprediction.core.model.RuleId("r1")).isEmpty());
        assertEquals(ru.cashprediction.core.ui.form.Problem.Severity.ERROR, session.view().problem().severity());
    }

    /** Дочерняя корректировка использует номинальную дату и не становится пассивной формой. */
    @Test void childAdjustmentResultIsApplied() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        WindowState state = new WindowState("w3", WindowType.ADJUSTMENT_EDITOR, true, "w2", null,
                Map.of("ruleId", "r1", "originalDate", "2026-10-05"), Map.of("action", "CHANGE_AMOUNT", "amount", "91000,00"));
        new CoreWindowFactory(fake.flow).open(state, "w2", shown -> { }, failed -> { });
        FormSession session = fake.sessions.getFirst();
        session.buttonPressed("ok");
        assertEquals(1, fake.document.plan().adjustments().size());
        assertEquals("w2", session.ownerId());
    }

    /** Коллизия имени проверяется до результата: форма остаётся открытой, чужой файл не перезаписывается. */
    @Test void restoredRenameRejectsFileCollision() throws java.io.IOException {
        CaptureContext fake = new CaptureContext(home, ClientProfile.swing());
        var repository = new ru.cashprediction.core.io.PlanRepository(fake.environment.cashMemory());
        Path original = repository.pathFor("original");
        Path occupied = repository.pathFor("occupied");
        repository.save(fake.document.plan().withName("original"), original);
        repository.save(fake.document.plan().withName("occupied"), occupied);
        String occupiedBefore = java.nio.file.Files.readString(occupied);
        fake.document.replace(fake.document.plan().withName("original"), original, false, List.of());
        new CoreWindowFactory(fake.flow).open(new WindowState("rename1", WindowType.TEXT_INPUT, true, "main", null,
                Map.of("purpose", "rename"), Map.of("value", "occupied")), "main", shown -> { }, reason -> fail(reason));
        FormSession session = fake.sessions.getFirst();
        assertEquals(ru.cashprediction.core.ui.form.Problem.Severity.ERROR, session.view().problem().severity());
        assertEquals(ru.cashprediction.core.ui.form.ButtonView.DISABLED, session.view().buttons().get("rename"));
        session.buttonPressed("rename");
        assertFalse(session.isClosed());
        assertEquals("original", fake.document.plan().name());
        assertEquals(original, fake.document.file().orElseThrow());
        assertEquals(occupiedBefore, java.nio.file.Files.readString(occupied));
        assertFalse(fake.document.canUndo());
    }

    /** Ошибка callback не вызывает противоположный ответ фабрики повторно. */
    @Test void failureCallbackIsNotCaught() {
        CaptureContext fake = new CaptureContext(home, ClientProfile.web());
        List<String> failures = new ArrayList<>();
        WindowState unknown = new WindowState("w1", WindowType.TEXT_INPUT, true, "main", null, Map.of("purpose", "future"), Map.of());
        assertThrows(IllegalStateException.class, () -> new CoreWindowFactory(fake.flow).open(unknown, "main",
                shown -> fail(), reason -> { failures.add(reason); throw new IllegalStateException("consumer failure"); }));
        assertEquals(1, failures.size());
        assertTrue(fake.sessions.isEmpty());
    }
}
