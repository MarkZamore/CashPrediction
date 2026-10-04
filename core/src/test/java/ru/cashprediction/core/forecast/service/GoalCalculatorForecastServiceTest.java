package ru.cashprediction.core.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.GoalCalculator;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;

/** Проверяет рабочую цепочку документ -> контекст формы -> та же подставная служба -> видимый результат. */
class GoalCalculatorForecastServiceTest {
    private static final LocalDate START = LocalDate.of(2024, 1, 1);

    /** Отличный от движка ответ fake определяет строку результата, а не просто фиксирует формальный вызов. */
    @Test
    void documentAndGoalSimulationConsumeTheSameInjectedService() {
        Money existingExtra = Money.ofMinor(25);
        WhatIf whatIf = new WhatIf(new BigDecimal("1.05"), new BigDecimal("0.90"), existingExtra);
        FakeForecastService fake = new FakeForecastService(request -> {
            // Подставной ответ намеренно не прибавляет новую экономию, но сохраняет метаданные запроса.
            Forecast base = EngineForecastService.DEFAULT.calculate(new ForecastRequest(request.plan(),
                    request.whatIf().withExtraMonthlySaving(existingExtra), request.today(), request.includeSkipped()));
            return new Forecast(request.plan(), request.whatIf(), request.today(), base.anchor(), base.rows(),
                    base.dailyBalance(), base.dailyStart(), base.summary(), base.warnings());
        });
        PlanDocument document = new PlanDocument(plan(), null, () -> START, fake);
        document.setViewState(document.viewState().withWhatIf(whatIf).withShowSkipped(true));
        FormContext context = new FormContext("w1", "main", Map.of(), snapshot(document), document.forecastService());
        GoalCalculatorForm form = new GoalCalculatorForm();
        Money target = Money.ofMinor(500);
        Money extra = Money.ofMinor(1000);
        FormView view = form.evaluate(values(form, context, target, extra), context);

        assertSame(fake, document.forecastService());
        assertSame(fake, context.forecastService());
        assertEquals(List.of(
                new ForecastRequest(document.plan(), whatIf, START, true),
                new ForecastRequest(document.plan(), whatIf.withExtraMonthlySaving(existingExtra.plus(extra)), START, false)),
                fake.requests());
        assertEquals(Problem.NONE, view.problem());
        assertEquals(3, view.results().size());
        assertEquals(UiText.get("form.goal.withExtraNotReached", UiFormats.whole(extra, document.plan().currency())),
                view.results().getLast().text());
        assertTrue(GoalCalculator.reachDate(EngineForecastService.DEFAULT.calculate(fake.requests().getLast()), target)
                .isPresent(), "real engine would reach the goal; only the injected answer says otherwise");
        assertFalse(document.isDirty());
        assertFalse(document.canUndo());
        assertEquals(whatIf, document.viewState().whatIf());
    }

    /** Обновление снимка немодальной формы сохраняет службу, но запрос берёт новые план и дату. */
    @Test
    void withAppPreservesServiceAndUsesFreshSnapshotInputs() {
        AtomicReference<LocalDate> today = new AtomicReference<>(START);
        FakeForecastService fake = new FakeForecastService();
        PlanDocument document = new PlanDocument(plan(), null, today::get, fake);
        FormContext context = new FormContext("w1", "main", Map.of("purpose", "test"), snapshot(document), fake);
        GoalCalculatorForm form = new GoalCalculatorForm();
        form.evaluate(values(form, context, Money.ofMinor(500), Money.ofMinor(1000)), context);

        today.set(START.plusDays(10));
        document.edit("note", current -> current.withNote("updated"));
        FormContext updated = context.withApp(snapshot(document));
        form.evaluate(values(form, updated, Money.ofMinor(500), Money.ofMinor(1000)), updated);

        assertSame(fake, updated.forecastService());
        assertEquals(context.windowId(), updated.windowId());
        assertEquals(context.ownerId(), updated.ownerId());
        assertEquals(context.context(), updated.context());
        assertEquals(4, fake.requests().size());
        assertEquals(new ForecastRequest(document.plan(),
                WhatIf.NONE.withExtraMonthlySaving(Money.ofMinor(1000)), today.get(), false), fake.requests().getLast());
        assertSame(document.plan(), fake.requests().getLast().plan());
    }

    /** Ошибка подставной службы отображается существующим локализованным текстом и не уходит в клиент. */
    @Test
    void typedSimulationFailureUsesExistingLocalizedErrorPresentation() {
        ForecastFailure.LimitExceeded failure = new ForecastFailure.LimitExceeded(new IllegalStateException(
                Texts.get("forecast.error.horizonTooLong", 200001, 200000)));
        FakeForecastService fake = new FakeForecastService(request -> {
            if (request.whatIf().extraMonthlySaving().isPositive()) {
                throw failure;
            }
            return EngineForecastService.DEFAULT.calculate(request);
        });
        PlanDocument document = new PlanDocument(plan(), null, () -> START, fake);
        FormContext context = new FormContext("w1", "main", Map.of(), snapshot(document), fake);
        GoalCalculatorForm form = new GoalCalculatorForm();
        FormView view = form.evaluate(values(form, context, Money.ofMinor(500), Money.ofMinor(1000)), context);

        assertEquals(2, fake.requests().size());
        assertEquals(1, view.results().size());
        assertEquals(UiText.get("form.goal.forecast", failure.getMessage()), view.results().getFirst().text());
        assertEquals(ColorToken.EXPENSE, view.results().getFirst().color());
        assertFalse(document.isDirty());
        assertFalse(document.canUndo());
    }

    /** Невалидный ввод, нулевая экономия и недоступный базовый прогноз не вызывают симуляцию. */
    @Test
    void invalidInputAndUnavailableBaselineDoNotCallService() {
        FakeForecastService fake = new FakeForecastService();
        PlanDocument document = new PlanDocument(plan(), null, () -> START, fake);
        FormContext context = new FormContext("w1", "main", Map.of(), snapshot(document), fake);
        GoalCalculatorForm form = new GoalCalculatorForm();
        assertTrue(form.evaluate(values(form, context, Money.ofMinor(500), Money.ZERO), context).results().size() >= 2);

        Map<String, String> invalid = new LinkedHashMap<>(form.defaults(context));
        invalid.put("target", "");
        invalid.put("extraSaving", "10,00");
        assertEquals(Problem.Severity.ERROR, form.evaluate(new FormState(0, invalid), context).problem().severity());
        invalid.put("target", "5,00");
        invalid.put("extraSaving", "-1,00");
        assertTrue(form.evaluate(new FormState(0, invalid), context).results().isEmpty());
        assertEquals(1, fake.requests().size());

        DocumentView unavailable = new DocumentView(document.plan(), null, false, false, "", false, "", null,
                Texts.get("forecast.error.horizonTooLong", 200001, 200000), List.of());
        FormContext failedContext = context.withApp(app(unavailable, document));
        FormView failed = form.evaluate(values(form, failedContext, Money.ofMinor(500), Money.ofMinor(1000)), failedContext);
        assertEquals(Problem.Severity.ERROR, failed.problem().severity());
        assertTrue(failed.results().isEmpty());
        assertEquals(1, fake.requests().size());
    }

    /** Старые конструкторы разделяют безопасную службу по умолчанию; явное внедрение требует ненулевой ссылки. */
    @Test
    void compatibilityConstructorsShareDefaultAndExplicitContextRejectsNull() {
        PlanDocument document = new PlanDocument(plan(), null, () -> START);
        AppState app = snapshot(document);
        FormContext compatible = new FormContext("w1", "main", Map.of(), app);

        assertSame(document.forecastService(), compatible.forecastService());
        assertSame(EngineForecastService.DEFAULT, compatible.withApp(app).forecastService());
        assertThrows(NullPointerException.class, () -> new FormContext("w1", "main", Map.of(), app, null));
    }

    /** Создаёт форму без срока с положительной целью и явно заданной добавкой. */
    private static FormState values(GoalCalculatorForm form, FormContext context, Money target, Money extra) {
        Map<String, String> values = new LinkedHashMap<>(form.defaults(context));
        values.put("target", target.formatPlain());
        values.put("extraSaving", extra.formatPlain());
        return new FormState(0, values);
    }

    /** Снимает состояние именно внедрённого документа, не рассчитывая прогноз в обход службы. */
    private static AppState snapshot(PlanDocument document) {
        DocumentView view = new DocumentView(document.plan(), null, document.isDirty(), document.canUndo(), "",
                document.canRedo(), "", document.forecast(), "", List.of());
        return app(view, document);
    }

    /** Оборачивает снимок документа общим состоянием без файловых или реестровых операций. */
    private static AppState app(DocumentView view, PlanDocument document) {
        return new AppState(1, ClientProfile.swing(), document.today(), Path.of("CashMemory"), null, view,
                document.viewState(), "", false, AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /** Создаёт пустой план, в котором реальная добавка достигает цели в последний день месяца. */
    private static Plan plan() {
        return Plan.empty("goal", START).withHorizon(new Horizon.Months(1));
    }
}
