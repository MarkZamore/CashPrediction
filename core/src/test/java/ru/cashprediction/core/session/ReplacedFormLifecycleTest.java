package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.ops.OneTimeForm;

/** Проверяет stale close настоящей формы после замены регистрации, без native UI и storage quarantine. */
class ReplacedFormLifecycleTest {
    /** Поздний closed callback старого равного handle не удаляет replacement и его сырой ошибочный ввод. */
    @Test void oldFormClosedAfterReplacementPreservesCapturedInvalidInput() {
        var store = new FakeStore("memory");
        var scheduler = new FakeScheduler();
        var recorder = new SessionRecorder("fx", List.of(store), UiExecutor.direct(), new FakeSource(), scheduler,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        var callerContext = new LinkedHashMap<>(Map.of("mode", "create"));
        var old = new FormHandle("w1", recorder, callerContext);
        var replacement = new FormHandle("w1", recorder, callerContext);
        assertNotSame(old, replacement);
        assertEquals(old, replacement);
        recorder.start();
        old.form.view();
        old.form.shown();
        old.form.fieldChanged("title", "old", true, 1);
        replacement.form.view();
        replacement.form.shown();
        replacement.form.fieldChanged("title", "replacement", true, 1);
        replacement.form.fieldChanged("amount", "1,234", true, 2);
        assertEquals(ru.cashprediction.core.ui.form.Problem.Severity.ERROR,
                replacement.form.view().problem().severity());
        assertFalse(replacement.form.view().buttons().get("ok").enabled());
        assertEquals(1, recorder.registeredWindows().size());
        assertSame(replacement, recorder.registeredWindows().get(0));

        callerContext.put("mode", "edit");
        old.form.closed();
        assertTrue(old.form.isClosed());
        assertEquals(1, old.closedCallbacks);
        assertEquals(1, old.unregisteredCallbacks);
        assertFalse(replacement.form.isClosed());
        assertEquals(0, replacement.closedCallbacks);
        assertEquals(0, replacement.unregisteredCallbacks);
        assertEquals(1, recorder.registeredWindows().size());
        assertSame(replacement, recorder.registeredWindows().get(0));

        scheduler.advance(SessionRecorder.DEBOUNCE);
        assertEquals(1, store.saved.size());
        SessionSnapshot first = store.saved.get(0);
        assertEquals(1, first.windows().size());
        WindowState captured = first.windows().get(0);
        assertEquals("w1", captured.id());
        assertEquals("main", captured.ownerId());
        assertEquals(WindowType.ONE_TIME_EDITOR, captured.type());
        assertEquals("create", captured.contextValue("mode"));
        assertEquals("replacement", captured.field("title"));
        assertEquals("1,234", captured.field("amount"));
        assertThrows(UnsupportedOperationException.class, () -> captured.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> captured.context().clear());

        replacement.form.fieldChanged("title", "later", true, 3);
        recorder.saveNow();
        assertEquals("later", recorder.lastCaptured().orElseThrow().windows().get(0).field("title"));
        assertEquals("1,234", recorder.lastCaptured().orElseThrow().windows().get(0).field("amount"));
        assertEquals("replacement", first.windows().get(0).field("title"));
        assertEquals("1,234", first.windows().get(0).field("amount"));
        old.form.closed();
        assertEquals(1, old.unregisteredCallbacks);
        assertSame(replacement, recorder.registeredWindows().get(0));
        replacement.form.closed();
        assertEquals(1, replacement.unregisteredCallbacks);
        recorder.saveNow();
        assertTrue(recorder.lastCaptured().orElseThrow().windows().isEmpty());
        recorder.shutdownClean();
    }

    /** Допустимый window adapter с равенством по id связывает настоящий lifecycle формы с рекордером. */
    private static final class FormHandle implements StatefulWindow, FormSession.Host {
        private final String id;
        private final SessionRecorder recorder;
        private final FormSession form;
        private int closedCallbacks;
        private int unregisteredCallbacks;
        private FormHandle(String id, SessionRecorder recorder, Map<String, String> callerContext) {
            this.id = id;
            this.recorder = recorder;
            var context = new FormContext(id, "main", callerContext,
                    FakeStates.empty(ClientProfile.fx("25"), Path.of("CashMemory")));
            form = new FormSession(WindowType.ONE_TIME_EDITOR, true, new OneTimeForm(), context, this);
        }
        /** Возвращает id регистрации. */
        @Override public String windowId() { return id; }
        /** Возвращает тип настоящей формы. */
        @Override public WindowType windowType() { return form.windowType(); }
        /** Возвращает модальность настоящей формы. */
        @Override public boolean modal() { return form.modal(); }
        /** Возвращает владельца настоящей формы. */
        @Override public String ownerId() { return form.ownerId(); }
        /** Захватывает настоящий owned state формы. */
        @Override public WindowState captureState() { return form.captureState(); }
        /** Передаёт восстановление полей настоящей форме. */
        @Override public void applyState(WindowState state) { form.applyState(state); }
        /** Показ регистрирует именно живой экземпляр adapter. */
        @Override public void registered(FormSession session) { recorder.register(this); }
        /** Позднее закрытие старого adapter не должно удалять его замену. */
        @Override public void unregistered(FormSession session) {
            unregisteredCallbacks++;
            recorder.unregister(this);
        }
        /** Изменение формы включает debounce. */
        @Override public void touched(FormSession session) { recorder.touch(); }
        /** Принимает lifecycle callback, не применяя пользовательские результаты. */
        @Override public void closed(FormSession session, Object result) {
            assertNull(result);
            closedCallbacks++;
        }
        /** Дочерние окна в этом сценарии не запрашиваются. */
        @Override public void openChild(FormSession session, WindowState child) { fail("openChild"); }
        /** Невалидная форма не должна применяться. */
        @Override public void applied(FormSession session, Object action) { fail("applied"); }
        /** Порт не запрещает равенство adapter по windowId. */
        @Override public boolean equals(Object other) { return other instanceof FormHandle handle && id.equals(handle.id); }
        /** Согласует hashCode с равенством adapter. */
        @Override public int hashCode() { return id.hashCode(); }
    }
}
