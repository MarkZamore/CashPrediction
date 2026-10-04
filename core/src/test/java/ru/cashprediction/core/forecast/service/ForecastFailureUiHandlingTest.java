package ru.cashprediction.core.forecast.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppClock;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;

/** Проверяет реальные обработчики контроллера и формы для четырёх ошибок службы и ошибок программирования. */
class ForecastFailureUiHandlingTest {
    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    @TempDir Path folder;

    /** Реальный движок отклоняет планы, а контроллер и форма сохраняют причину в доступном пользователю виде. */
    @Test
    void controllerPresentsAllFourRealEngineFailuresAndRecovers() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController controller = controller(port, AppClock.fixedToday(START));
        GoalCalculatorForm form = new GoalCalculatorForm();
        for (InvalidPlan invalid : invalidPlans()) {
            RuntimeException failure = engineFailure(invalid);
            // replace вызывает штатный слушатель контроллера, который тоже строит state().
            assertDoesNotThrow(() -> controller.document().replace(invalid.plan(), null, false, List.of()));
            var state = assertDoesNotThrow(controller::state);
            assertSame(invalid.plan(), state.document().plan());
            assertFalse(state.document().forecastAvailable());
            assertNull(state.document().forecast());
            assertEquals(failure.getMessage(), state.document().forecastError());
            FormContext context = new FormContext("w1", "main", Map.of(), state,
                    controller.document().forecastService());
            var view = assertDoesNotThrow(() -> form.evaluate(values(), context));
            assertEquals(Problem.Severity.ERROR, view.problem().severity());
            assertEquals(UiText.get("form.goal.forecast", failure.getMessage()), view.problem().text());
            assertTrue(view.results().isEmpty());
        }
        controller.document().replace(Plan.empty("valid", START), null, false, List.of());
        assertTrue(controller.state().document().forecastAvailable());
        assertEquals("", controller.state().document().forecastError());
        assertTrue(port.calls().isEmpty());
        assertFalse(Files.exists(folder.resolve("CashMemory")));
    }

    /** Ошибки дополнительного прогноза становятся единственной строкой результата с прежним текстом и цветом. */
    @Test
    void goalSimulationPresentsAllFourTypedFailures() {
        AppController controller = controller(new FakeUiPort(ClientProfile.swing()), AppClock.fixedToday(START));
        var baseline = controller.state();
        GoalCalculatorForm form = new GoalCalculatorForm();
        for (InvalidPlan invalid : invalidPlans()) {
            RuntimeException failure = engineFailure(invalid);
            FakeForecastService service = new FakeForecastService(request -> { throw failure; });
            FormContext context = new FormContext("w1", "main", Map.of(), baseline, service);
            var view = assertDoesNotThrow(() -> form.evaluate(values(), context));
            assertEquals(Problem.NONE, view.problem());
            assertEquals(1, view.results().size());
            assertEquals(UiText.get("form.goal.forecast", failure.getMessage()), view.results().getFirst().text());
            assertEquals(ColorToken.EXPENSE, view.results().getFirst().color());
            assertEquals(List.of(new ForecastRequest(baseline.document().plan(),
                    WhatIf.NONE.withExtraMonthlySaving(Money.ofMinor(1000)), START, false)), service.requests());
            assertFalse(controller.document().isDirty());
            assertFalse(controller.document().canUndo());
        }
    }

    /** Нетипизированные ошибки служебных часов проходят через state() тем же объектом, включая старые базовые типы. */
    @Test
    void controllerRethrowsUnexpectedRuntimeFailures() {
        FailingClock clock = new FailingClock();
        AppController controller = controller(new FakeUiPort(ClientProfile.swing()), AppClock.of(clock, null));
        for (RuntimeException failure : unexpectedFailures()) {
            clock.failure = failure;
            assertSame(failure, assertThrows(RuntimeException.class, controller::state));
        }
    }

    /** Нетипизированная ошибка службы дополнительного прогноза не маскируется локализованным результатом. */
    @Test
    void goalSimulationRethrowsUnexpectedRuntimeFailures() {
        AppController controller = controller(new FakeUiPort(ClientProfile.swing()), AppClock.fixedToday(START));
        var baseline = controller.state();
        GoalCalculatorForm form = new GoalCalculatorForm();
        for (RuntimeException failure : unexpectedFailures()) {
            FakeForecastService service = new FakeForecastService(request -> { throw failure; });
            FormContext context = new FormContext("w1", "main", Map.of(), baseline, service);
            assertSame(failure, assertThrows(RuntimeException.class, () -> form.evaluate(values(), context)));
            assertEquals(1, service.requests().size());
        }
    }

    /** Создаёт настоящий контроллер без start(), файловых операций, реестра и графического клиента. */
    private AppController controller(FakeUiPort port, AppClock clock) {
        return new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                folder, folder.resolve("CashMemory"), clock));
    }

    /** Задаёт валидный ввод с добавкой, чтобы evaluate действительно вызывал службу симуляции. */
    private static FormState values() {
        return new FormState(0, Map.of("target", "5,00", "extraSaving", "10,00",
                "byDateEnabled", FieldCodec.FALSE, "byDate", ""));
    }

    /** Получает настоящую типизированную ошибку движка и проверяет ожидаемую классификацию плана. */
    private static RuntimeException engineFailure(InvalidPlan invalid) {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> EngineForecastService.DEFAULT.calculate(
                new ForecastRequest(invalid.plan(), WhatIf.NONE, START, false)));
        assertEquals(invalid.kind(), assertInstanceOf(ForecastFailure.class, failure).kind());
        assertNotNull(failure.getMessage());
        assertFalse(failure.getMessage().isBlank());
        return failure;
    }

    /** Создаёт планы для предела дней, денежных пределов и переполнения расширенного календарного окна движка. */
    private static List<InvalidPlan> invalidPlans() {
        Plan day = Plan.empty("amount", START).withHorizon(new Horizon.Until(START));
        OneTimeTransaction income = new OneTimeTransaction(new TxId("t1"), START, "income", Kind.INCOME,
                Money.ofMinor(1), "", "");
        OneTimeTransaction expense = new OneTimeTransaction(new TxId("t1"), START, "expense", Kind.EXPENSE,
                Money.ofMinor(1), "", "");
        return List.of(
                new InvalidPlan(day.withHorizon(new Horizon.Until(START.plusDays(ForecastEngine.MAX_DAYS))),
                        ForecastFailure.Kind.LIMIT_EXCEEDED),
                new InvalidPlan(day.withStart(START, Money.ofMinor(Long.MAX_VALUE)).withOneTimes(List.of(income)),
                        ForecastFailure.Kind.AMOUNT_OVERFLOW),
                new InvalidPlan(day.withStart(START, Money.ofMinor(-Long.MAX_VALUE)).withOneTimes(List.of(expense)),
                        ForecastFailure.Kind.AMOUNT_OUT_OF_RANGE),
                new InvalidPlan(Plan.empty("date", LocalDate.MAX).withHorizon(new Horizon.Until(LocalDate.MAX)),
                        ForecastFailure.Kind.DATE_RANGE_EXCEEDED));
    }

    /** Проверяет те же базовые классы без маркера ForecastFailure, а также типичную ошибку null. */
    private static List<RuntimeException> unexpectedFailures() {
        return List.of(new IllegalArgumentException("unexpected argument"), new ArithmeticException("unexpected arithmetic"),
                new IllegalStateException("unexpected state"), new DateTimeException("unexpected date"),
                new NullPointerException("unexpected null"));
    }

    /** Связывает входные данные с независимым ожиданием машинного вида ошибки. */
    private record InvalidPlan(Plan plan, ForecastFailure.Kind kind) { }

    /** Подставные часы дают ошибку внутри document.forecast(), без изменения полей контроллера рефлексией. */
    private static final class FailingClock extends Clock {
        private RuntimeException failure;

        /** {@inheritDoc} */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        /** {@inheritDoc} */
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }

        /** {@inheritDoc} */
        @Override public Instant instant() {
            if (failure != null) throw failure;
            return START.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }
}
