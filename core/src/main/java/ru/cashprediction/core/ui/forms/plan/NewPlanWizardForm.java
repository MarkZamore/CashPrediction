package ru.cashprediction.core.ui.forms.plan;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.List;
import java.nio.file.Files;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.ui.form.ButtonRole;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.ButtonView;
import ru.cashprediction.core.ui.form.FieldChecks;
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
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.session.WindowType;

/**
 * §6.1 NEW_PLAN_WIZARD «Новый план» (₽, 600; представление {@code WIZARD}; контекст {@code page} = 0..2).
 *
 * <p>Страница 1: {@code name} (первое свободное «Мой план», «Мой план 2», …; checkPlanName, {@code val.plan.exists}),
 * {@code currency} (editableChoice ₽ $ € ₸ BYN), широкая подсказка. Страница 2: {@code startDate} (сегодня),
 * {@code startBalance} (0,00), горизонт ({@link HorizonFields}), начальный {@code displayPeriod} (M12),
 * {@code cushion} (0,00, неотрицательная), подсказка.
 * Страница 3: разделы «Ежемесячный доход» ({@code quickIncomeTitle} «Зарплата», {@code quickIncomeAmount},
 * {@code quickIncomeDay} 5) и «Ежемесячный расход» ({@code quickExpenseTitle} «Аренда», {@code quickExpenseAmount},
 * {@code quickExpenseDay} 1); проверки только при заполненной сумме.</p>
 *
 * <p>Кнопки: [Открыть пример] (LEFT, только страница 1) · [‹ Назад] (BACK, отключена на странице 1) · [Далее ›] (NEXT,
 * отключена на странице 3 или при ошибке страницы) · [Готово] (FINISH, когда все страницы корректны; иначе строка
 * проблем «✖ Шаг {N}: {ошибка}») · [Отмена]. Enter = «Далее» на страницах 1–2, «Готово» на странице 3.</p>
 *
 * <p>Результат: {@code Close(}{@link Created}{@code )} с планом (ежемесячные правила для заполненных сумм, выходные
 * «Не сдвигать») или {@code Close(}{@link OpenSample}{@code )}; отмена — {@code Close(null)}.</p>
 */
public final class NewPlanWizardForm implements FormLogic {

    /**
     * Мастер создал план.
     *
     * @param plan новый план (контроллер сохраняет его в CashMemory и открывает)
     * @param displayPeriod начальный период показа, независимый от горизонта прогноза
     */
    public record Created(Plan plan, PeriodChoice displayPeriod) {
        /** Проверяет план. */
        public Created {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(displayPeriod, "displayPeriod");
        }

        /** Сохраняет прежний контракт вызывающих сторон с периодом показа M12. */
        public Created(Plan plan) { this(plan, PeriodChoice.M12); }
    }

    /** Нажата «Открыть пример»: мастер закрывается и открывается {@code SamplePlan}. */
    public record OpenSample() {
    }

    /** Создаёт мастер. */
    public NewPlanWizardForm() {
    }

    /**
     * Описывает три страницы мастера: имя и валюту, начальные параметры и быстрые операции.
     * Задаёт кнопки переходов, завершения, открытия примера и отмены; доступность уточняется при оценке.
     *
     * @param context контекст формы
     * @return общая для клиентов спецификация мастера с начальной кнопкой Enter {@code next}
     */
    @Override
    public FormSpec spec(FormContext context) {
        return new FormSpec("newPlanWizard", WindowType.NEW_PLAN_WIZARD, "", Presentation.WIZARD,
                UiText.get("form.newPlan.title"), "₽", 600, true, false, true,
                List.of(new FormPage("page1", List.of(
                                new FormRow.Field(FieldSpecs.focused(FieldSpecs.text("name", UiText.get("form.newPlan.name"), UiText.get("form.newPlan.name.prompt")))),
                                new FormRow.Field(FieldSpecs.editableChoice("currency", UiText.get("form.newPlan.currency"), currencies())),
                                new FormRow.Hint("fileHint", UiText.get("form.newPlan.hint.file")))),
                        new FormPage("page2", List.of(
                                new FormRow.Field(FieldSpecs.date("startDate", UiText.get("form.newPlan.startDate"))),
                                new FormRow.Field(FieldSpecs.money("startBalance", UiText.get("form.newPlan.startBalance"))),
                                HorizonFields.rows().get(0), HorizonFields.rows().get(1),
                                new FormRow.Field(FieldSpecs.choice("displayPeriod", UiText.get("form.newPlan.displayPeriod"), displayPeriods())),
                                new FormRow.Field(FieldSpecs.money("cushion", UiText.get("form.newPlan.cushion"))),
                                new FormRow.Hint("cushionHint", UiText.get("form.newPlan.hint.cushion")))),
                        new FormPage("page3", List.of(
                                new FormRow.Section(UiText.get("form.newPlan.quickIncome")),
                                new FormRow.Field(FieldSpecs.text("quickIncomeTitle", UiText.get("form.newPlan.operationTitle"), "")),
                                new FormRow.Field(FieldSpecs.withPrompt(FieldSpecs.money("quickIncomeAmount", UiText.get("form.newPlan.amount")), UiText.get("form.newPlan.amount.prompt"))),
                                new FormRow.Field(FieldSpecs.spinner("quickIncomeDay", UiText.get("form.newPlan.day"), 1, 31)),
                                new FormRow.Section(UiText.get("form.newPlan.quickExpense")),
                                new FormRow.Field(FieldSpecs.text("quickExpenseTitle", UiText.get("form.newPlan.operationTitle"), "")),
                                new FormRow.Field(FieldSpecs.withPrompt(FieldSpecs.money("quickExpenseAmount", UiText.get("form.newPlan.amount")), UiText.get("form.newPlan.amount.prompt"))),
                                new FormRow.Field(FieldSpecs.spinner("quickExpenseDay", UiText.get("form.newPlan.day"), 1, 31)),
                                new FormRow.Hint("quickHint", UiText.get("form.newPlan.hint.quick"))))),
                List.of(ButtonSpecs.of("sample", UiText.get("button.openSample"), ButtonRole.LEFT),
                        ButtonSpecs.of("back", UiText.get("button.back"), ButtonRole.BACK),
                        ButtonSpecs.of("next", UiText.get("button.next"), ButtonRole.NEXT),
                        ButtonSpecs.of("finish", UiText.get("button.finish"), ButtonRole.FINISH), ButtonSpecs.cancel()), "next");
    }

    /**
     * Подбирает первое свободное имя по наличию файлов в CashMemory и заполняет начальные значения:
     * сегодняшнюю дату, нулевые суммы, горизонт 12 месяцев и названия быстрых операций без сумм.
     *
     * @param context контекст с текущей датой и папкой планов
     * @return неизменяемая карта значений полей; файлы при подборе имени не создаются
     */
    @Override
    public Map<String, String> defaults(FormContext context) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", freeName(context)); values.put("currency", Plan.DEFAULT_CURRENCY);
        values.put("startDate", FieldCodec.date(context.app().today())); values.put("startBalance", Money.ZERO.formatPlain());
        values.putAll(HorizonFields.values(new ru.cashprediction.core.model.Horizon.Months(12)));
        values.put("displayPeriod", PeriodChoice.M12.name());
        values.put("cushion", Money.ZERO.formatPlain());
        values.put("quickIncomeTitle", UiText.get("form.newPlan.quickIncome.default")); values.put("quickIncomeAmount", ""); values.put("quickIncomeDay", "5");
        values.put("quickExpenseTitle", UiText.get("form.newPlan.quickExpense.default")); values.put("quickExpenseAmount", ""); values.put("quickExpenseDay", "1");
        return Map.copyOf(values);
    }

    /**
     * Ограничивает отображаемую страницу диапазоном 0..2, проверяет её поля и вычисляет доступность кнопок.
     * Показывает ошибку текущего шага либо предупреждение горизонта; при отсутствии обоих на первых
     * двух страницах ищет ошибку другого шага. Завершение разрешает только при корректности всех шагов.
     *
     * @param state значения полей и текущая страница
     * @param context контекст проверки имени файла
     * @return представление страницы с проблемой, полями горизонта и состояниями кнопок
     */
    @Override
    public FormView evaluate(FormState state, FormContext context) {
        int page = Math.clamp(state.page(), 0, 2);
        Problem current = checkPage(page, state, context).map(Problem::error).orElseGet(() -> HorizonFields.warning(state, start(state)).map(Problem::warning).orElse(Problem.NONE));
        if (current.severity() == Problem.Severity.NONE && page < 2) {
            for (int i = 0; i < 3; i++) if (i != page) {
                var other = checkPage(i, state, context);
                if (other.isPresent()) { current = Problem.error(UiText.get("form.newPlan.step", i + 1, other.get())); break; }
            }
        }
        boolean allValid = checkPage(0, state, context).isEmpty() && checkPage(1, state, context).isEmpty() && checkPage(2, state, context).isEmpty();
        Map<String, ru.cashprediction.core.ui.form.FieldView> fields = new LinkedHashMap<>(HorizonFields.views(state));
        fields.put("displayPeriod", new ru.cashprediction.core.ui.form.FieldView(displayPeriodValue(state), true, true, false, null, null, null));
        return new FormView(0, page, pageTitle(page), fields, current,
                Map.of("sample", page == 0 ? ButtonView.ENABLED : ButtonView.HIDDEN,
                        "back", page == 0 ? ButtonView.DISABLED : ButtonView.ENABLED,
                        "next", page == 2 || checkPage(page, state, context).isPresent() ? ButtonView.DISABLED : ButtonView.ENABLED,
                        "finish", allValid ? ButtonView.ENABLED : ButtonView.DISABLED,
                        "cancel", ButtonView.ENABLED), List.of(), List.of(), "", false);
    }

    /**
     * Обрабатывает переходы мастера: перед переходом вперёд проверяет текущий шаг,
     * перед завершением проверяет все шаги и собирает план с заполненными быстрыми операциями.
     * Возвращает результат контроллеру без сохранения плана; открытие примера возвращает отдельный признак.
     *
     * @param buttonId идентификатор нажатой кнопки
     * @param state значения полей и текущая страница
     * @param context контекст проверки имени файла
     * @return смена страницы, закрытие с {@link Created} или {@link OpenSample},
     *         закрытие с {@code null} при отмене либо сохранение формы при ошибке или неизвестной кнопке
     */
    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        return switch (buttonId) {
            case "cancel" -> new FormOutcome.Close(null);
            case "sample" -> new FormOutcome.Close(new OpenSample());
            case "back" -> new FormOutcome.Page(Math.max(0, state.page() - 1));
            case "next" -> checkPage(state.page(), state, context).isEmpty() ? new FormOutcome.Page(Math.min(2, state.page() + 1)) : FormOutcome.stay();
            case "finish" -> checkPage(0, state, context).isEmpty() && checkPage(1, state, context).isEmpty() && checkPage(2, state, context).isEmpty()
                    ? new FormOutcome.Close(new Created(buildPlan(state), PeriodChoice.valueOf(displayPeriodValue(state)))) : FormOutcome.stay();
            default -> FormOutcome.stay();
        };
    }

    /** Обрабатывает Enter на последней странице как «Готово», а на первых двух как «Далее». */
    @Override
    public Optional<FormOutcome> onFieldSubmitted(String fieldId, FormState state, FormContext context) {
        return Optional.of(onButton(state.page() >= 2 ? "finish" : "next", state, context));
    }

    /** Создаёт список доступных обозначений валют. */
    private static List<Option> currencies() { return List.of(Option.of("₽", "₽"), Option.of("$", "$"), Option.of("€", "€"), Option.of("₸", "₸"), Option.of("BYN", "BYN")); }
    /** Подбирает свободное имя, не создавая файлов. */
    private static String freeName(FormContext context) { for (int i = 1; ; i++) { String n = i == 1 ? UiText.get("form.newPlan.defaultName") : UiText.get("form.newPlan.defaultNameNumber", i); if (!Files.exists(context.app().cashMemory().resolve(n + ".md"))) return n; } }
    /** Разбирает дату начала, не бросая исключение. */
    private static java.time.LocalDate start(FormState state) { return FieldCodec.parseDate(state.value("startDate")).orElse(null); }
    /** Возвращает локализованный заголовок страницы без динамического ключа. */
    private static String pageTitle(int page) { return switch (page) { case 0 -> UiText.get("form.newPlan.page1"); case 1 -> UiText.get("form.newPlan.page2"); default -> UiText.get("form.newPlan.page3"); }; }
    /** Возвращает первую проблему конкретной страницы. */
    private static java.util.Optional<String> checkPage(int page, FormState s, FormContext c) {
        if (page == 0) { var name=PlanValidator.checkPlanName(s.value("name")); if(name.isPresent())return name; if(Files.exists(c.app().cashMemory().resolve(s.value("name").strip()+".md"))) return java.util.Optional.of(UiText.get("val.plan.exists", s.value("name").strip())); if(s.value("currency").isBlank())return java.util.Optional.of(UiText.get("val.currency.required")); if(s.value("currency").codePointCount(0,s.value("currency").length())>10)return java.util.Optional.of(UiText.get("val.currency.long")); return java.util.Optional.empty(); }
        if (page == 1) return FieldChecks.first(FieldChecks.date(UiText.get("form.newPlan.startDate"), s.value("startDate"), true), FieldChecks.money(UiText.get("form.newPlan.startBalance"),s.value("startBalance"),FieldChecks.MoneyRule.ANY), HorizonFields.error(s,start(s)), displayPeriodError(s), FieldChecks.money(UiText.get("form.newPlan.cushion"),s.value("cushion"),FieldChecks.MoneyRule.NON_NEGATIVE));
        return quickError(s,"quickIncome",UiText.get("form.newPlan.income")).or(() -> quickError(s,"quickExpense",UiText.get("form.newPlan.expense")));
    }
    /** Использует M12 для старого снимка без нового поля; явно ошибочное значение не скрывает. */
    private static String displayPeriodValue(FormState state) {
        return state.values().getOrDefault("displayPeriod", PeriodChoice.M12.name());
    }

    /** Возвращает общий закрытый список периодов, не меняющий финансовый горизонт. */
    private static List<Option> displayPeriods() {
        return java.util.Arrays.stream(PeriodChoice.values()).map(period -> Option.of(period.name(), switch (period) {
            case M3 -> UiText.get("form.newPlan.displayPeriod.M3");
            case M6 -> UiText.get("form.newPlan.displayPeriod.M6");
            case M12 -> UiText.get("form.newPlan.displayPeriod.M12");
            case M24 -> UiText.get("form.newPlan.displayPeriod.M24");
            case ALL -> UiText.get("form.newPlan.displayPeriod.ALL");
        })).toList();
    }

    /** Проверяет код выбора периода до разрешения завершения мастера. */
    private static Optional<String> displayPeriodError(FormState state) {
        String value = displayPeriodValue(state);
        return java.util.Arrays.stream(PeriodChoice.values()).anyMatch(period -> period.name().equals(value))
                ? Optional.empty() : Optional.of(UiText.get("form.newPlan.displayPeriod.invalid"));
    }

    /** Проверяет быструю регулярную операцию только при указанной сумме. */
    private static java.util.Optional<String> quickError(FormState s, String prefix, String kind) {
        String raw = s.value(prefix + "Amount");
        if (raw.isBlank()) return java.util.Optional.empty();
        var amount = FieldCodec.parseMoney(raw);
        if (amount.isEmpty() || !amount.get().isPositive()) return java.util.Optional.of(UiText.get("form.newPlan.quick.amount", kind));
        if (amount.get().abs().compareTo(PlanValidator.MAX_AMOUNT) > 0) return java.util.Optional.of(UiText.get("form.newPlan.quick.tooBig", kind));
        if (s.value(prefix + "Title").isBlank()) return java.util.Optional.of(UiText.get("form.newPlan.quick.title", kind));
        var day = FieldCodec.parseLong(s.value(prefix + "Day"));
        if (day.isEmpty() || day.getAsLong() < 1 || day.getAsLong() > Recurrence.MAX_DAY_OF_MONTH) {
            return java.util.Optional.of(UiText.get("recurrence.error.dayOfMonth", Recurrence.MAX_DAY_OF_MONTH));
        }
        return java.util.Optional.empty();
    }
    /** Собирает итоговый план из проверенных значений мастера. */
    private static Plan buildPlan(FormState s) { var rules=new java.util.ArrayList<RecurringRule>(); addQuick(rules,s,"quickIncome",Kind.INCOME,"r1"); addQuick(rules,s,"quickExpense",Kind.EXPENSE,rules.isEmpty()?"r1":"r2"); return new Plan(s.value("name"),"",s.value("currency"),FieldCodec.parseDate(s.value("startDate")).orElseThrow(),FieldCodec.parseMoney(s.value("startBalance")).orElseThrow(),HorizonFields.toHorizon(s),FieldCodec.parseMoney(s.value("cushion")).orElseThrow(),null,rules,List.of(),List.of(),List.of()); }
    /** Добавляет заполненную быструю операцию. */
    private static void addQuick(List<RecurringRule> rules,FormState s,String prefix,Kind kind,String id){ if(s.value(prefix+"Amount").isBlank())return; rules.add(new RecurringRule(new RuleId(id),s.value(prefix+"Title"),kind,FieldCodec.parseMoney(s.value(prefix+"Amount")).orElseThrow(),"",new Recurrence.Monthly(Math.toIntExact(FieldCodec.parseLong(s.value(prefix+"Day")).orElseThrow()),1),null,null,WeekendPolicy.NONE,true,"")); }
}
