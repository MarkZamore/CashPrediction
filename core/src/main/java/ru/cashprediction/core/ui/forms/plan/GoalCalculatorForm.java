package ru.cashprediction.core.ui.forms.plan;

import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.GoalCalculator;
import ru.cashprediction.core.forecast.service.ForecastRequest;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.form.ButtonRole;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.ButtonView;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.ResultLine;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.session.WindowType;

/**
 * §6.6 GOAL_CALCULATOR «Калькулятор цели» (◎, 640; немодальный, один экземпляр; представление {@code DIALOG}).
 *
 * <p>Заголовок «Когда я накоплю нужную сумму?». Поля: {@code target} «Нужная сумма» (по умолчанию сумма цели);
 * {@code byDateEnabled} + {@code byDate} «Срок» (флажок отмечен, если у цели есть дата; дата цели или конец плана);
 * {@code extraSaving} «Откладывать ещё в месяц». Раздел «Результат» — до 5 строк §6.6, пересчитываются при каждом
 * изменении поля и плана ({@link #reevaluateOnDocumentChange()}). Ошибка расчёта перехватывается: «✖ Прогноз не
 * рассчитан: {0}», окно не падает.</p>
 *
 * <p>Кнопки: [Записать цель в план] (LEFT) → {@code Apply(}{@link SaveGoal}{@code )}, окно не закрывается (отмена
 * {@code undo.goal}, статус {@code status.msg.goalSaved}); [Показать с доп. экономией] (LEFT, доступна при
 * {@code extraSaving} &gt; 0) → {@code Apply(}{@link AddWhatIfExtra}{@code , {extraSaving: ""})}; [Закрыть] (CANCEL).
 * Enter ничего не делает.</p>
 */
public final class GoalCalculatorForm implements FormLogic {

    /**
     * Записать цель в план: прежнее название или «Цель», сумма и дата (если отмечено).
     *
     * @param goal новая цель
     */
    public record SaveGoal(Goal goal) {
        /** Проверяет цель. */
        public SaveGoal {
            Objects.requireNonNull(goal, "goal");
        }
    }

    /**
     * Прибавить сумму к доп. экономии «что-если».
     *
     * @param amount сумма в месяц (&gt; 0)
     */
    public record AddWhatIfExtra(Money amount) {
        /** Проверяет сумму. */
        public AddWhatIfExtra {
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** Создаёт форму. */
    public GoalCalculatorForm() {
    }

    /** {@inheritDoc} */
    @Override
    public FormSpec spec(FormContext context) {
        return new FormSpec("goalCalculator", WindowType.GOAL_CALCULATOR, "", Presentation.DIALOG,
                UiText.get("form.goal.title"), "◎", 640, false, false, true, List.of(new FormPage("main", List.of(
                new FormRow.Field(FieldSpecs.focused(FieldSpecs.withPrompt(FieldSpecs.money("target", UiText.get("form.goal.target")), UiText.get("form.goal.target.prompt")))),
                new FormRow.Inline(UiText.get("form.goal.deadline"), List.of(FieldSpecs.check("byDateEnabled", UiText.get("form.goal.byDate")), FieldSpecs.date("byDate", ""))),
                new FormRow.Field(FieldSpecs.money("extraSaving", UiText.get("form.goal.extra"))),
                new FormRow.Section(UiText.get("form.goal.result")), new FormRow.Results("results", 3)))),
                List.of(ButtonSpecs.of("saveGoal", UiText.get("button.saveGoal"), ButtonRole.LEFT),
                        ButtonSpecs.of("showExtra", UiText.get("button.showWithExtra"), ButtonRole.LEFT), ButtonSpecs.close()), "");
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, String> defaults(FormContext context) {
        Goal goal = context.app().document().plan().goal();
        LocalDate byDate = goal != null && goal.wishDate() != null ? goal.wishDate() : context.app().document().plan().endDate();
        return Map.of("target", goal == null ? "" : goal.target().formatPlain(), "byDateEnabled", goal != null && goal.wishDate() != null ? FieldCodec.TRUE : FieldCodec.FALSE,
                "byDate", FieldCodec.date(byDate), "extraSaving", "");
    }

    /** {@inheritDoc} */
    @Override
    public FormView evaluate(FormState state, FormContext context) {
        var error = check(state);
        Map<String, ru.cashprediction.core.ui.form.FieldView> fields = new LinkedHashMap<>();
        boolean byDate = FieldCodec.TRUE.equals(state.value("byDateEnabled"));
        fields.put("byDateEnabled", ru.cashprediction.core.ui.form.FieldView.of(state.value("byDateEnabled")));
        fields.put("byDate", new ru.cashprediction.core.ui.form.FieldView(state.value("byDate"), true, byDate, false, null, null, null));
        boolean extra = FieldCodec.parseMoney(state.value("extraSaving")).map(Money::isPositive).orElse(false);
        Problem problem = error.map(Problem::error).orElseGet(() -> context.app().document().forecastAvailable() ? warning(state, context).map(Problem::warning).orElse(Problem.NONE) : Problem.error(UiText.get("form.goal.forecast", context.app().document().forecastError())));
        return new FormView(0, 0, "", fields, problem, Map.of("saveGoal", error.isEmpty() ? ButtonView.ENABLED : ButtonView.DISABLED,
                "showExtra", error.isEmpty() && extra ? ButtonView.ENABLED : ButtonView.DISABLED, "close", ButtonView.ENABLED),
                error.isPresent() || !context.app().document().forecastAvailable() ? List.of() : results(state, context), List.of(), "", false);
    }

    /** {@inheritDoc} */
    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        if ("close".equals(buttonId)) return new FormOutcome.Close(null);
        if (check(state).isPresent()) return FormOutcome.stay();
        Money target = FieldCodec.parseMoney(state.value("target")).orElseThrow();
        if ("saveGoal".equals(buttonId)) {
            Goal old = context.app().document().plan().goal();
            String title = old == null || old.title().isBlank() ? UiText.get("form.goal.defaultTitle") : old.title();
            return new FormOutcome.Apply(new SaveGoal(new Goal(title, target, FieldCodec.TRUE.equals(state.value("byDateEnabled")) ? FieldCodec.parseDate(state.value("byDate")).orElseThrow() : null)), Map.of());
        }
        if ("showExtra".equals(buttonId)) return FieldCodec.parseMoney(state.value("extraSaving")).filter(Money::isPositive)
                .<FormOutcome>map(value -> new FormOutcome.Apply(new AddWhatIfExtra(value), Map.of("extraSaving", ""))).orElseGet(FormOutcome::stay);
        return FormOutcome.stay();
    }

    /** @return {@code true}: результаты пересчитываются при изменении плана */
    @Override
    public boolean reevaluateOnDocumentChange() {
        return true;
    }

    /** Проверяет введённые ограничения калькулятора. */
    private static java.util.Optional<String> check(FormState s) { String target=s.value("target"); if(target.isBlank())return java.util.Optional.of(UiText.get("form.goal.target.required")); var amount=FieldCodec.parseMoney(target); if(amount.isEmpty()||!amount.get().isPositive())return java.util.Optional.of(UiText.get("form.goal.target.positive")); String extra=s.value("extraSaving"); if(!extra.isBlank()&&FieldCodec.parseMoney(extra).map(Money::isNegative).orElse(true))return java.util.Optional.of(UiText.get("form.goal.extra.nonNegative")); if(FieldCodec.TRUE.equals(s.value("byDateEnabled"))&&FieldCodec.parseDate(s.value("byDate")).isEmpty())return java.util.Optional.of(UiText.get("form.goal.date.required")); return java.util.Optional.empty(); }
    /** Возвращает предупреждение о сроке за горизонтом. */
    private static java.util.Optional<String> warning(FormState s, FormContext c) { if(!FieldCodec.TRUE.equals(s.value("byDateEnabled")))return java.util.Optional.empty(); return FieldCodec.parseDate(s.value("byDate")).filter(d->d.isAfter(c.app().document().plan().endDate())).isPresent()?java.util.Optional.of(UiText.get("form.goal.late")):java.util.Optional.empty(); }
    /** Строит до пяти строк результата, не позволяя исключению прогноза уйти в клиент. */
    private static List<ResultLine> results(FormState state, FormContext context) {
        try {
            Forecast forecast = context.app().document().forecast();
            Money target = FieldCodec.parseMoney(state.value("target")).orElseThrow();
            String currency = context.app().document().plan().currency();
            List<ResultLine> result = new java.util.ArrayList<>();
            result.add(line("form.goal.now", UiFormats.date(context.app().today()),
                    UiFormats.whole(forecast.balanceAt(context.app().today()), currency)));
            var reached = GoalCalculator.reachDate(forecast, target);
            if (reached.isPresent()) {
                result.add(line("form.goal.reached", UiFormats.date(reached.get()),
                        monthsSuffix(context.app().today(), reached.get())));
            } else {
                result.add(line("form.goal.notReached", UiFormats.date(forecast.endDate()),
                        UiFormats.whole(forecast.endBalance(), currency)));
            }
            if (FieldCodec.TRUE.equals(state.value("byDateEnabled"))) {
                LocalDate date = FieldCodec.parseDate(state.value("byDate")).orElseThrow();
                result.add(line("form.goal.balance", UiFormats.date(date),
                        UiFormats.whole(forecast.balanceAt(date), currency)));
                var extra = GoalCalculator.requiredExtraMonthly(forecast, target, date);
                result.add(new ResultLine(extra.map(amount -> amount.isZero()
                        ? UiText.get("form.goal.noExtra")
                        : UiText.get("form.goal.needExtra", UiFormats.whole(amount, currency)))
                        .orElse(UiText.get("form.goal.noMonthEnd")), ColorToken.TEXT_PRIMARY));
            }
            FieldCodec.parseMoney(state.value("extraSaving")).filter(Money::isPositive).ifPresent(extra -> {
                var whatIf = context.app().view().whatIf();
                Forecast simulated = context.forecastService().calculate(new ForecastRequest(
                        context.app().document().plan(),
                        whatIf.withExtraMonthlySaving(whatIf.extraMonthlySaving().plus(extra)),
                        context.app().today(), false));
                var with = GoalCalculator.reachDate(simulated, target);
                result.add(new ResultLine(with.map(date -> UiText.get("form.goal.withExtra",
                        UiFormats.whole(extra, currency), UiFormats.date(date),
                        monthsSuffix(context.app().today(), date)))
                        .orElse(UiText.get("form.goal.withExtraNotReached", UiFormats.whole(extra, currency))),
                        ColorToken.TEXT_PRIMARY));
            });
            return List.copyOf(result);
        } catch (RuntimeException error) {
            // Только типизированная ошибка прогноза становится строкой результата формы.
            if (!(error instanceof ru.cashprediction.core.forecast.service.ForecastFailure)) throw error;
            return List.of(new ResultLine(UiText.get("form.goal.forecast", safe(error)), ColorToken.EXPENSE));
        }
    }
    /** Формирует строку результата по ключу. */
    private static ResultLine line(String key,Object... args){return new ResultLine(UiText.get(key,args),ColorToken.TEXT_PRIMARY);}
    /** Возвращает необязательную часть строки о числе месяцев. */
    private static String monthsSuffix(LocalDate from, LocalDate to){long months=Math.max(0,ChronoUnit.MONTHS.between(from,to));return months>0?UiText.get("form.goal.months",months):"";}
    /** Превращает исключение в безопасное короткое объяснение. */
    private static String safe(RuntimeException ex){return ex.getMessage()==null||ex.getMessage().isBlank()?ex.getClass().getSimpleName():ex.getMessage();}
}
