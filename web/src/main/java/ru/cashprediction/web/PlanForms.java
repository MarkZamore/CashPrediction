package ru.cashprediction.web;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.markdown.RuFormats;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Построение объектов плана из полей диалогов в канонической форме (тех же, что хранятся в
 * {@code WindowState.fields}): деньги {@code "95000,00"}, даты ISO, флажки {@code "true"/"false"},
 * выбор — имя константы.
 *
 * <p>Браузер отправляет на сервер ровно те поля, которые видит пользователь и которые попадают в снимок сессии;
 * разбор, проверка и русские сообщения об ошибках делаются здесь один раз. Все методы бросают
 * {@link IllegalArgumentException} с сообщением вида «Поле «Сумма»: …», которое API отдаёт как ответ 400.</p>
 *
 * <p>Здесь же — пример плана для пункта «Файл → Открыть пример» ({@link #samplePlan(LocalDate)}), одинаковый
 * во всех трёх клиентах.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class PlanForms {

    /** Имя пустого плана, который открывается, если мастер нового плана отменён. */
    public static final String DEFAULT_PLAN_NAME = "Мой план";

    /** Имя плана-примера. */
    public static final String SAMPLE_PLAN_NAME = "Пример";

    private PlanForms() {
    }

    // ================================================================== пример

    /**
     * План-пример «Пример» для пункта «Открыть пример» (одинаковый в JavaFX, Swing и Web).
     *
     * @param today сегодняшняя дата: план начинается с первого дня текущего месяца
     * @return план с пятью регулярными операциями, премией и целью «Отпуск»
     */
    public static Plan samplePlan(LocalDate today) {
        LocalDate start = today.withDayOfMonth(1);
        List<RecurringRule> rules = List.of(
                new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.ofMajor(80_000), "",
                        new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, ""),
                new RecurringRule(new RuleId("r2"), "Аванс", Kind.INCOME, Money.ofMajor(40_000), "",
                        new Recurrence.Monthly(20, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, ""),
                new RecurringRule(new RuleId("r3"), "Аренда", Kind.EXPENSE, Money.ofMajor(45_000), "",
                        new Recurrence.Monthly(1, 1), null, null, WeekendPolicy.NONE, true, ""),
                new RecurringRule(new RuleId("r4"), "Продукты", Kind.EXPENSE, Money.ofMajor(4_000), "",
                        new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), null, null, WeekendPolicy.NONE, true, ""),
                // 31 — «последний день месяца»: в коротких месяцах платёж приходится на 30-е или 28/29-е.
                new RecurringRule(new RuleId("r5"), "Кредит", Kind.EXPENSE, Money.ofMinor(1_234_567), "",
                        new Recurrence.Monthly(31, 1), null, null, WeekendPolicy.NONE, true, ""));
        List<OneTimeTransaction> oneTimes = List.of(
                new OneTimeTransaction(new TxId("t1"), start.plusMonths(3).withDayOfMonth(20), "Премия", Kind.INCOME,
                        Money.ofMajor(60_000), "", ""));
        return new Plan(SAMPLE_PLAN_NAME, "", Plan.DEFAULT_CURRENCY, start, Money.ofMajor(150_000), new Horizon.Months(12),
                Money.ofMajor(50_000), new Goal("Отпуск", Money.ofMajor(300_000), null), rules, oneTimes, List.of(), List.of());
    }

    // ================================================================== мастер и параметры

    /**
     * План из полей мастера «Новый план» ({@code WindowType.NEW_PLAN_WIZARD}).
     *
     * <p>Необязательные поля получают значения по умолчанию: валюта ₽, начало сегодня, баланс 0, горизонт 12 месяцев.
     * Быстрый доход и расход добавляются правилами {@code r1}, {@code r2}, только если указана сумма.</p>
     *
     * @param f     поля мастера
     * @param today сегодняшняя дата
     * @return новый план
     * @throws IllegalArgumentException если имя недопустимо или значение не разбирается
     */
    public static Plan fromWizard(Map<String, String> f, LocalDate today) {
        String name = text(f, "name");
        PlanValidator.checkPlanName(name).ifPresent(message -> {
            throw new IllegalArgumentException("Поле «Название»: " + message);
        });
        LocalDate start = optionalDate(f, "startDate", "Дата начала");
        Plan plan = new Plan(name, "", text(f, "currency"), start == null ? today : start,
                optionalMoney(f, "startBalance", "Начальный баланс", Money.ZERO),
                horizon(text(f, "horizonKind"), text(f, "horizonValue"), text(f, "horizonUntil"), new Horizon.Months(12)),
                optionalMoney(f, "cushion", "Подушка безопасности", Money.ZERO),
                null, List.of(), List.of(), List.of(), List.of());
        plan = addQuickRule(plan, f, "quickIncome", Kind.INCOME, "Доход", 5);
        plan = addQuickRule(plan, f, "quickExpense", Kind.EXPENSE, "Расход", 1);
        return plan;
    }

    /**
     * Применяет поля диалога «Параметры плана» ({@code WindowType.PLAN_SETTINGS}). Изменяются только переданные поля:
     * так диалог «Валюта» или слайдер горизонта в меню могут прислать одно-два поля. Имя плана здесь не меняется —
     * переименование затрагивает файл и выполняется {@link ServerState}.
     *
     * @param plan текущий план
     * @param f    переданные поля
     * @return изменённый план
     * @throws IllegalArgumentException если значение не разбирается
     */
    public static Plan applySettings(Plan plan, Map<String, String> f) {
        Plan result = plan;
        if (f.containsKey("currency")) {
            String currency = text(f, "currency");
            if (currency.isEmpty()) {
                throw new IllegalArgumentException("Поле «Валюта»: укажите обозначение валюты");
            }
            if (currency.codePointCount(0, currency.length()) > 10) {
                throw new IllegalArgumentException("Поле «Валюта»: не больше 10 символов");
            }
            result = result.withCurrency(currency);
        }
        if (f.containsKey("startDate") || f.containsKey("startBalance")) {
            LocalDate start = f.containsKey("startDate") ? requireDate(f, "startDate", "Дата начала") : result.startDate();
            Money balance = f.containsKey("startBalance")
                    ? optionalMoney(f, "startBalance", "Начальный баланс", Money.ZERO) : result.startBalance();
            result = result.withStart(start, balance);
        }
        if (f.containsKey("horizonKind") || f.containsKey("horizonValue") || f.containsKey("horizonUntil")) {
            Map<String, Object> current = ru.cashprediction.core.json.PlanJson.horizon(result.horizon());
            String kind = f.containsKey("horizonKind") ? text(f, "horizonKind") : String.valueOf(current.get("kind"));
            String value = f.containsKey("horizonValue") ? text(f, "horizonValue")
                    : current.get("count") == null ? "" : String.valueOf(current.get("count"));
            String until = f.containsKey("horizonUntil") ? text(f, "horizonUntil")
                    : current.get("until") == null ? "" : String.valueOf(current.get("until"));
            result = result.withHorizon(horizon(kind, value, until, result.horizon()));
        }
        if (f.containsKey("cushion")) {
            result = result.withCushion(optionalMoney(f, "cushion", "Подушка безопасности", Money.ZERO));
        }
        if (f.containsKey("note")) {
            result = result.withNote(f.get("note"));
        }
        if (f.containsKey("goalTarget") || f.containsKey("goalTitle") || f.containsKey("goalDate")) {
            Goal old = result.goal();
            String targetText = f.containsKey("goalTarget") ? text(f, "goalTarget") : old == null ? "" : old.target().formatPlain();
            if (targetText.isEmpty()) {
                result = result.withGoal(null);
            } else {
                Money target = parseMoney(targetText, "Цель");
                String title = f.containsKey("goalTitle") ? text(f, "goalTitle") : old == null ? "" : old.title();
                LocalDate wish = f.containsKey("goalDate") ? optionalDate(f, "goalDate", "Цель к дате") : old == null ? null : old.wishDate();
                result = result.withGoal(new Goal(title, target, wish));
            }
        }
        return result;
    }

    // ================================================================== операции

    /**
     * Регулярная операция из полей редактора ({@code WindowType.RULE_EDITOR}).
     *
     * @param f  поля редактора
     * @param id идентификатор правила
     * @return правило
     * @throws IllegalArgumentException если значение не разбирается или сумма не больше нуля
     */
    public static RecurringRule ruleFromFields(Map<String, String> f, RuleId id) {
        Kind kind = enumValue(Kind.class, text(f, "kind"), "Тип", null);
        Money amount = positiveMoney(f, "amount", "Сумма");
        Recurrence recurrence = recurrenceFromFields(f);
        LocalDate from = enabledDate(f, "fromEnabled", "from", "С");
        LocalDate until = enabledDate(f, "untilEnabled", "until", "По");
        if (from != null && until != null && until.isBefore(from)) {
            throw new IllegalArgumentException("Дата «По» не может быть раньше даты «С»");
        }
        WeekendPolicy policy = enumValue(WeekendPolicy.class, text(f, "weekendPolicy"), "Выходные", WeekendPolicy.NONE);
        boolean enabled = !f.containsKey("enabled") || bool(f, "enabled", "Активна");
        return new RecurringRule(id, f.get("title"), kind, amount, f.get("category"), recurrence, from, until, policy,
                enabled, f.get("note"));
    }

    /**
     * Повтор из полей редактора правила.
     *
     * @param f поля {@code recurrenceKind, dayOfMonth, everyN, weekday, monthDay}
     * @return повтор
     * @throws IllegalArgumentException если вид неизвестен или значение вне диапазона
     */
    public static Recurrence recurrenceFromFields(Map<String, String> f) {
        String kind = text(f, "recurrenceKind").toUpperCase(Locale.ROOT);
        try {
            return switch (kind) {
                case "MONTHLY" -> new Recurrence.Monthly(integer(f, "dayOfMonth", "День месяца", null),
                        integer(f, "everyN", "Каждые N", 1));
                case "WEEKLY" -> new Recurrence.Weekly(weekday(text(f, "weekday")), integer(f, "everyN", "Каждые N", 1));
                case "EVERY_N_DAYS" -> new Recurrence.EveryNDays(integer(f, "everyN", "Каждые N дней", null));
                case "YEARLY" -> new Recurrence.Yearly(monthDay(text(f, "monthDay")));
                case "" -> throw new IllegalArgumentException("Поле «Повтор»: выберите вид повтора");
                default -> throw new IllegalArgumentException("Поле «Повтор»: неизвестный вид «" + kind + "»");
            };
        } catch (IllegalArgumentException e) {
            throw e.getMessage() != null && e.getMessage().startsWith("Поле ")
                    ? e : new IllegalArgumentException("Поле «Повтор»: " + e.getMessage(), e);
        }
    }

    /**
     * Разовая операция из полей редактора ({@code WindowType.ONE_TIME_EDITOR}).
     *
     * @param f  поля {@code date, title, kind, amount, category, note}
     * @param id идентификатор операции
     * @return операция
     * @throws IllegalArgumentException если значение не разбирается
     */
    public static OneTimeTransaction oneTimeFromFields(Map<String, String> f, TxId id) {
        return new OneTimeTransaction(id, requireDate(f, "date", "Дата"), f.get("title"),
                enumValue(Kind.class, text(f, "kind"), "Тип", null), positiveMoney(f, "amount", "Сумма"),
                f.get("category"), f.get("note"));
    }

    /**
     * Корректировка из полей редактора ({@code WindowType.ADJUSTMENT_EDITOR}) и контекста события.
     *
     * @param f поля {@code ruleId, originalDate, action, amount, date, note}
     * @return корректировка
     * @throws IllegalArgumentException если нет ключа события, действия или нужной действию суммы/даты
     */
    public static Adjustment adjustmentFromFields(Map<String, String> f) {
        String ruleId = text(f, "ruleId");
        if (ruleId.isEmpty()) {
            throw new IllegalArgumentException("Не указано правило корректируемого события");
        }
        OccurrenceKey key = new OccurrenceKey(new RuleId(ruleId), requireDate(f, "originalDate", "Исходная дата"));
        RuFormats.ActionType action = enumValue(RuFormats.ActionType.class, text(f, "action"), "Действие", null);
        Money amount = action.requiresAmount() ? positiveMoney(f, "amount", "Новая сумма") : null;
        LocalDate date = action.requiresDate() ? requireDate(f, "date", "Новая дата") : null;
        return new Adjustment(key, RuFormats.buildAction(action, amount, date), f.get("note"));
    }

    // ================================================================== разбор значений

    /**
     * Горизонт из трёх полей формы.
     *
     * @param kind         {@code MONTHS}, {@code YEARS}, {@code UNTIL}; пусто — {@code defaultValue}
     * @param value        число месяцев или лет
     * @param until        дата окончания (ISO) для {@code UNTIL}
     * @param defaultValue горизонт, если вид не указан
     * @return горизонт
     */
    public static Horizon horizon(String kind, String value, String until, Horizon defaultValue) {
        String k = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        try {
            return switch (k) {
                case "" -> defaultValue;
                case "MONTHS" -> new Horizon.Months(parseInt(value, "Горизонт"));
                case "YEARS" -> new Horizon.Years(parseInt(value, "Горизонт"));
                case "UNTIL" -> new Horizon.Until(parseDate(until, "Горизонт до"));
                default -> throw new IllegalArgumentException("неизвестный вид горизонта «" + kind + "»");
            };
        } catch (IllegalArgumentException e) {
            throw e.getMessage() != null && e.getMessage().startsWith("Поле ")
                    ? e : new IllegalArgumentException("Поле «Горизонт»: " + e.getMessage(), e);
        }
    }

    /**
     * Сумма из текста формы.
     *
     * @param text  текст («95000,00», «95 000»)
     * @param label название поля для сообщения
     * @return сумма
     */
    public static Money parseMoney(String text, String label) {
        try {
            return Money.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Поле «" + label + "»: " + e.getMessage(), e);
        }
    }

    /**
     * Дата из текста формы (ISO или ДД.ММ.ГГГГ).
     *
     * @param text  текст
     * @param label название поля для сообщения
     * @return дата
     */
    public static LocalDate parseDate(String text, String label) {
        try {
            return DateFormats.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Поле «" + label + "»: " + e.getMessage(), e);
        }
    }

    private static Plan addQuickRule(Plan plan, Map<String, String> f, String prefix, Kind kind, String defaultTitle, int defaultDay) {
        String amountText = text(f, prefix + "Amount");
        if (amountText.isEmpty()) {
            return plan;
        }
        String label = kind == Kind.INCOME ? "Доход" : "Расход";
        Money amount = positiveMoney(f, prefix + "Amount", label + ": сумма");
        int day = integer(f, prefix + "Day", label + ": день месяца", defaultDay);
        String title = text(f, prefix + "Title");
        try {
            return plan.withRuleAdded(new RecurringRule(plan.nextRuleId(), title.isEmpty() ? defaultTitle : title, kind, amount,
                    "", new Recurrence.Monthly(day, 1), null, null, WeekendPolicy.NONE, true, ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Поле «" + label + ": день месяца»: " + e.getMessage(), e);
        }
    }

    /** Значение поля без пробелов по краям; отсутствующее поле — пустая строка. */
    private static String text(Map<String, String> f, String key) {
        String value = f.get(key);
        return value == null ? "" : value.strip();
    }

    private static Money optionalMoney(Map<String, String> f, String key, String label, Money defaultValue) {
        String value = text(f, key);
        return value.isEmpty() ? defaultValue : parseMoney(value, label);
    }

    private static Money positiveMoney(Map<String, String> f, String key, String label) {
        String value = text(f, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Поле «" + label + "»: укажите сумму");
        }
        Money money = parseMoney(value, label);
        if (!money.isPositive()) {
            throw new IllegalArgumentException("Поле «" + label + "»: сумма должна быть больше нуля");
        }
        return money;
    }

    private static LocalDate optionalDate(Map<String, String> f, String key, String label) {
        String value = text(f, key);
        return value.isEmpty() ? null : parseDate(value, label);
    }

    private static LocalDate requireDate(Map<String, String> f, String key, String label) {
        String value = text(f, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Поле «" + label + "»: укажите дату");
        }
        return parseDate(value, label);
    }

    /**
     * Дата с флажком «задана»: флажок {@code false} отключает дату, даже если в поле что-то осталось;
     * без флажка дата берётся, если поле непустое.
     */
    private static LocalDate enabledDate(Map<String, String> f, String flagKey, String key, String label) {
        if (f.containsKey(flagKey) && !bool(f, flagKey, label)) {
            return null;
        }
        return optionalDate(f, key, label);
    }

    private static boolean bool(Map<String, String> f, String key, String label) {
        String value = text(f, key);
        try {
            return RuFormats.parseBoolean(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Поле «" + label + "»: ожидается true или false", e);
        }
    }

    private static int integer(Map<String, String> f, String key, String label, Integer defaultValue) {
        String value = text(f, key);
        if (value.isEmpty()) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("Поле «" + label + "»: укажите число");
            }
            return defaultValue;
        }
        return parseInt(value, label);
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value == null ? "" : value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Поле «" + label + "»: ожидается целое число, получено «" + value + "»", e);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String text, String label, E defaultValue) {
        if (text.isEmpty()) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("Поле «" + label + "»: выберите значение");
            }
            return defaultValue;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(text)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Поле «" + label + "»: неизвестное значение «" + text + "»");
    }

    private static DayOfWeek weekday(String text) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().equalsIgnoreCase(text)) {
                return day;
            }
        }
        DayOfWeek day = RuText.parseWeekday(text);
        if (day == null) {
            throw new IllegalArgumentException("Поле «День недели»: неизвестное значение «" + text + "»");
        }
        return day;
    }

    private static MonthDay monthDay(String text) {
        try {
            return MonthDay.parse(text.startsWith("--") ? text : "--" + text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Поле «День года»: ожидается ММ-ДД, получено «" + text + "»", e);
        }
    }
}
