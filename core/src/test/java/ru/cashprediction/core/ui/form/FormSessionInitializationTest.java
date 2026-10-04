package ru.cashprediction.core.ui.form;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/** Проверяет повтор инициализации настоящего сеанса после отказа внедрённой логики, без запуска клиентов. */
class FormSessionInitializationTest {
    /** Исключение evaluate даёт полную error-модель; следующее событие повторяет расчёт без повторного старта. */
    @ParameterizedTest @EnumSource(ClientKind.class)
    void throwingEvaluateCanBeRetried(ClientKind client) {
        var logic = new RetryLogic(false);
        logic.defaultsCalls = 1;
        logic.throwingEvaluate = true;
        var session = session(client, logic);
        FormView failed = assertDoesNotThrow(session::view);
        FormSpec spec = session.spec();
        assertNotNull(spec);
        FormState initial = session.state();
        assertNotNull(initial);
        assertEquals("123", initial.value("amount"));
        assertEquals(Problem.Severity.ERROR, failed.problem().severity());
        assertEquals("fixture-evaluate", failed.problem().text());
        assertEquals(1, failed.revision());
        assertSame(failed, session.view());
        assertSame(initial, session.state());
        assertEquals(1, logic.specCalls);
        assertEquals(2, logic.defaultsCalls);
        assertEquals(1, logic.evaluateCalls);
        assertEquals(0, session.lastClientRev());
        assertFalse(session.isClosed());

        FormView recovered = assertDoesNotThrow(() -> session.fieldChanged("amount", "456", true, 1));
        assertEquals(Problem.Severity.NONE, recovered.problem().severity());
        assertEquals("456", session.state().value("amount"));
        assertEquals("123", initial.value("amount"));
        assertEquals(2, recovered.revision());
        assertEquals(2, logic.evaluateCalls);
        assertEquals(1, logic.specCalls);
        assertEquals(2, logic.defaultsCalls);
        assertSame(spec, session.spec());
        assertSame(recovered, session.view());
        assertEquals(1, session.lastClientRev());
        assertFalse(session.isClosed());
    }

    /** Отказ defaults не публикует неполное состояние; повтор строит согласованную модель. */
    @ParameterizedTest @EnumSource(ClientKind.class)
    void throwingDefaultsCanBeRetried(ClientKind client) {
        var logic = new RetryLogic(false);
        var session = session(client, logic);
        assertThrows(IllegalStateException.class, session::view);
        assertNotNull(session.state());
        assertEquals("123", session.state().value("amount"));
        assertNotNull(session.view());
        assertEquals(2, logic.specCalls);
        assertEquals(2, logic.defaultsCalls);
        assertEquals(1, logic.evaluateCalls);
        assertEquals(1, session.view().revision());
        assertEquals(0, session.lastClientRev());
        assertFalse(session.isClosed());
    }

    /** Некорректная карта defaults не оставляет marker старта после отказа defensive copy. */
    @ParameterizedTest @EnumSource(ClientKind.class)
    void invalidDefaultsCanBeRetried(ClientKind client) {
        var logic = new RetryLogic(true);
        var session = session(client, logic);
        assertThrows(NullPointerException.class, session::spec);
        assertNotNull(session.view());
        assertEquals("123", session.state().value("amount"));
        assertEquals(2, logic.specCalls);
        assertEquals(2, logic.defaultsCalls);
        assertEquals(1, logic.evaluateCalls);
    }

    /** Обычный старт остаётся однократным, а состояние владеет копией входной карты. */
    @ParameterizedTest @EnumSource(ClientKind.class)
    void successfulInitializationRunsOnceAndOwnsDefaults(ClientKind client) {
        var logic = new RetryLogic(false);
        logic.defaultsCalls = 1;
        var session = session(client, logic);
        FormSpec spec = session.spec();
        FormState state = session.state();
        FormView view = session.view();
        logic.values.put("amount", "456");
        assertEquals("123", state.value("amount"));
        assertThrows(UnsupportedOperationException.class, () -> state.values().put("amount", "789"));
        assertSame(spec, session.spec());
        assertSame(state, session.state());
        assertSame(view, session.view());
        assertEquals(1, logic.specCalls);
        assertEquals(2, logic.defaultsCalls);
        assertEquals(1, logic.evaluateCalls);
    }

    /** Создаёт сеанс с настоящим профилем ядра; профиль не запускает toolkit или браузер. */
    private static FormSession session(ClientKind client, FormLogic logic) {
        ClientProfile profile = switch (client) {
            case FX -> ClientProfile.fx("25");
            case SWING -> ClientProfile.swing();
            case WEB -> ClientProfile.web();
        };
        var context = new FormContext("w1", "main", Map.of(),
                FakeStates.empty(profile, Path.of("CashMemory")));
        return new FormSession(WindowType.TEXT_INPUT, false, logic, context, new SilentHost());
    }

    /** Управляемый отказ collaborator проверяет атомарность owner, не имитирует UI acceptance. */
    private static final class RetryLogic implements FormLogic {
        private final boolean invalidMap;
        private final Map<String, String> values = new LinkedHashMap<>(Map.of("amount", "123"));
        private int specCalls;
        private int defaultsCalls;
        private int evaluateCalls;
        private boolean throwingEvaluate;
        private RetryLogic(boolean invalidMap) { this.invalidMap = invalidMap; }
        /** Возвращает допустимую минимальную раскладку. */
        @Override public FormSpec spec(FormContext context) {
            specCalls++;
            return new FormSpec("fixture", WindowType.TEXT_INPUT, "", Presentation.DIALOG,
                    "", "", 560, false, false, false, List.of(), List.of(), "");
        }
        /** Первый вызов отказывает, следующий возвращает допустимые значения. */
        @Override public Map<String, String> defaults(FormContext context) {
            if (++defaultsCalls == 1) {
                if (!invalidMap) throw new IllegalStateException("fixture");
                var invalid = new LinkedHashMap<String, String>();
                invalid.put(null, "123");
                return invalid;
            }
            return values;
        }
        /** Возвращает обычную модель и считает фактические вызовы. */
        @Override public FormView evaluate(FormState state, FormContext context) {
            evaluateCalls++;
            if (throwingEvaluate && evaluateCalls == 1) throw new IllegalStateException("fixture-evaluate");
            return new FormView(0, 0, "", Map.of(), Problem.NONE, Map.of(), List.of(), List.of(), "", false);
        }
        /** Кнопки не участвуют в проверке старта. */
        @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
            return new FormOutcome.Stay(Problem.NONE);
        }
    }

    /** Запрещает незапрошенные lifecycle-события при чистой инициализации модели. */
    private static final class SilentHost implements FormSession.Host {
        /** Регистрация не должна происходить до показа. */
        @Override public void registered(FormSession session) { fail("registered"); }
        /** Отмена регистрации не ожидается. */
        @Override public void unregistered(FormSession session) { fail("unregistered"); }
        /** Старт не является пользовательской правкой. */
        @Override public void touched(FormSession session) { fail("touched"); }
        /** Старт не закрывает форму. */
        @Override public void closed(FormSession session, Object result) { fail("closed"); }
        /** Старт не открывает дочернее окно. */
        @Override public void openChild(FormSession parent, WindowState child) { fail("openChild"); }
        /** Старт не применяет предметную операцию. */
        @Override public void applied(FormSession session, Object action) { fail("applied"); }
    }
}
