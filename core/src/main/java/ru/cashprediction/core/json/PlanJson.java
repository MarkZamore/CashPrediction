package ru.cashprediction.core.json;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.ForecastSummary;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.forecast.Warning;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.markdown.RuFormats;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RawBlock;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurrenceKind;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Отображение доменных объектов в JSON-объекты ({@code Map<String, Object>}) и обратно — для web-API.
 *
 * <p><b>Канонические значения</b> (те же, что в полях снимка сессии): деньги — строка без разделителей тысяч
 * {@code "95000,00"} ({@link Money#formatPlain()}), даты — ISO {@code "2026-10-05"}, перечисления — имя константы
 * ({@code "INCOME"}), целые — {@link Long}, коэффициенты «что-если» — строка {@code "1.10"}. Строки, а не числа,
 * выбраны для денег и коэффициентов, потому что JavaScript хранит числа в {@code double} и копейки могли бы «поплыть».</p>
 *
 * <p><b>Поля для показа.</b> Рядом с каноническими значениями пишутся готовые к выводу тексты
 * ({@code amountText: "95 000,00"}, {@code dateText: "05.10.2026"}, {@code text} у повтора и т. п.):
 * форматирование денег и дат делается на сервере тем же кодом, что в desktop-клиентах, и браузеру не нужно
 * его дублировать. Методы {@code ...From} такие поля игнорируют.</p>
 *
 * <p><b>Точность.</b> Для любого плана {@code planFrom(plan(p)).equals(p)}, в том числе после
 * {@link JsonWriter#write} и {@link JsonParser#parse}: все целые в картах имеют тип {@link Long},
 * а разобранный JSON совпадает с исходной картой.</p>
 *
 * <p><b>Ошибки.</b> Методы {@code ...From} терпимы к отсутствующим необязательным полям (подставляются значения
 * по умолчанию), к датам в форме {@code ДД.ММ.ГГГГ}, к суммам с пробелами и к числам вместо строк, но на неверные
 * значения бросают {@link JsonException} с русским сообщением, пригодным для ответа 400.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class PlanJson {

    private PlanJson() {
    }

    // ================================================================== план

    /**
     * План в JSON-объект.
     *
     * @param plan план
     * @return объект с полями {@code name, note, currency, startDate, startBalance, horizon, cushion, goal, rules,
     *         oneTimes, adjustments, rawBlocks} и полями для показа ({@code startBalanceText, endDate, cushionText, categories})
     */
    public static Map<String, Object> plan(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", plan.name());
        m.put("note", plan.note());
        m.put("currency", plan.currency());
        m.put("startDate", iso(plan.startDate()));
        putMoney(m, "startBalance", plan.startBalance());
        m.put("horizon", horizon(plan.horizon()));
        m.put("endDate", iso(plan.endDate()));
        putMoney(m, "cushion", plan.cushion());
        m.put("goal", plan.goal() == null ? null : goal(plan.goal()));
        m.put("rules", mapList(plan.rules(), PlanJson::rule));
        m.put("oneTimes", mapList(plan.oneTimes(), PlanJson::oneTime));
        m.put("adjustments", mapList(plan.adjustments(), PlanJson::adjustment));
        m.put("rawBlocks", mapList(plan.rawBlocks(), PlanJson::rawBlock));
        m.put("categories", new ArrayList<Object>(plan.categories()));
        return m;
    }

    /**
     * План из JSON-объекта.
     *
     * @param map объект, созданный {@link #plan(Plan)} или браузером
     * @return план
     * @throws JsonException если нет обязательных полей ({@code name, startDate, horizon}) или значение некорректно
     */
    public static Plan planFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        String name = Json.requireString(map, "name");
        LocalDate startDate = requireDate(map, "startDate");
        Horizon horizon = horizonFrom(Json.optionalObject(map, "horizon")
                .orElseThrow(() -> new JsonException("Отсутствует обязательное поле «horizon»")));
        Goal goal = Json.optionalObject(map, "goal").map(PlanJson::goalFrom).orElse(null);
        return new Plan(name,
                Json.string(map, "note", ""),
                Json.string(map, "currency", Plan.DEFAULT_CURRENCY),
                startDate,
                money(map, "startBalance", Money.ZERO),
                horizon,
                money(map, "cushion", Money.ZERO),
                goal,
                objects(map, "rules", PlanJson::ruleFrom),
                objects(map, "oneTimes", PlanJson::oneTimeFrom),
                objects(map, "adjustments", PlanJson::adjustmentFrom),
                objects(map, "rawBlocks", PlanJson::rawBlockFrom));
    }

    /**
     * Горизонт в JSON-объект.
     *
     * @param horizon горизонт
     * @return {@code {"kind":"MONTHS","count":12,"until":null,"label":"12 месяцев"}};
     *         {@code kind} — {@code MONTHS}, {@code YEARS} или {@code UNTIL}
     */
    public static Map<String, Object> horizon(Horizon horizon) {
        Objects.requireNonNull(horizon, "horizon");
        Map<String, Object> m = new LinkedHashMap<>();
        switch (horizon) {
            case Horizon.Months(int count) -> {
                m.put("kind", "MONTHS");
                m.put("count", (long) count);
                m.put("until", null);
            }
            case Horizon.Years(int count) -> {
                m.put("kind", "YEARS");
                m.put("count", (long) count);
                m.put("until", null);
            }
            case Horizon.Until(LocalDate end) -> {
                m.put("kind", "UNTIL");
                m.put("count", null);
                m.put("until", iso(end));
            }
        }
        m.put("label", horizon.label());
        return m;
    }

    /**
     * Горизонт из JSON-объекта.
     *
     * @param map объект {@code {"kind", "count" | "until"}}
     * @return горизонт
     * @throws JsonException если вид неизвестен или значение вне диапазона
     */
    public static Horizon horizonFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        String kind = Json.requireString(map, "kind").strip().toUpperCase(Locale.ROOT);
        try {
            return switch (kind) {
                case "MONTHS" -> new Horizon.Months(requireInt(map, "count"));
                case "YEARS" -> new Horizon.Years(requireInt(map, "count"));
                case "UNTIL" -> new Horizon.Until(requireDate(map, "until"));
                default -> throw new JsonException("Поле «kind»: неизвестный вид горизонта «" + kind
                        + "» (ожидается MONTHS, YEARS или UNTIL)");
            };
        } catch (IllegalArgumentException e) {
            throw new JsonException("Горизонт: " + e.getMessage());
        }
    }

    /**
     * Цель в JSON-объект.
     *
     * @param goal цель
     * @return {@code {"title", "target", "targetText", "wishDate"}}; {@code wishDate} может быть {@code null}
     */
    public static Map<String, Object> goal(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", goal.title());
        putMoney(m, "target", goal.target());
        m.put("wishDate", isoOrNull(goal.wishDate()));
        return m;
    }

    /**
     * Цель из JSON-объекта.
     *
     * @param map объект цели
     * @return цель
     * @throws JsonException если нет суммы или значение некорректно
     */
    public static Goal goalFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        return new Goal(Json.string(map, "title", ""), requireMoney(map, "target"), optionalDate(map, "wishDate"));
    }

    // ================================================================== операции

    /**
     * Регулярная операция в JSON-объект.
     *
     * @param rule правило
     * @return объект {@code id, title, kind, amount, category, recurrence, from, until, weekendPolicy, enabled, note}
     *         и поля для показа {@code kindText, amountText, weekendPolicyText}
     */
    public static Map<String, Object> rule(RecurringRule rule) {
        Objects.requireNonNull(rule, "rule");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rule.id().value());
        m.put("title", rule.title());
        m.put("kind", rule.kind().name());
        m.put("kindText", rule.kind().title());
        putMoney(m, "amount", rule.amount());
        m.put("category", rule.category());
        m.put("recurrence", recurrence(rule.recurrence()));
        m.put("from", isoOrNull(rule.from()));
        m.put("until", isoOrNull(rule.until()));
        m.put("weekendPolicy", rule.weekendPolicy().name());
        m.put("weekendPolicyText", rule.weekendPolicy().title());
        m.put("enabled", rule.enabled());
        m.put("note", rule.note());
        return m;
    }

    /**
     * Регулярная операция из JSON-объекта; поле {@code id} обязательно.
     *
     * @param map объект правила
     * @return правило
     * @throws JsonException если нет обязательных полей или значение некорректно
     */
    public static RecurringRule ruleFrom(Map<String, Object> map) {
        return ruleFrom(map, null);
    }

    /**
     * Регулярная операция из JSON-объекта с идентификатором по умолчанию — для создания нового правила,
     * когда браузер ещё не знает его идентификатор.
     *
     * @param map       объект правила
     * @param defaultId идентификатор, если в объекте его нет или он пустой; {@code null} — идентификатор обязателен
     * @return правило
     * @throws JsonException если нет обязательных полей ({@code kind, amount, recurrence}) или значение некорректно
     */
    public static RecurringRule ruleFrom(Map<String, Object> map, RuleId defaultId) {
        Objects.requireNonNull(map, "map");
        try {
            String idText = Json.string(map, "id", "");
            RuleId id = idText.isBlank() ? requireDefault(defaultId, "id") : new RuleId(idText);
            Recurrence recurrence = recurrenceFrom(Json.optionalObject(map, "recurrence")
                    .orElseThrow(() -> new JsonException("Отсутствует обязательное поле «recurrence»")));
            return new RecurringRule(id,
                    Json.string(map, "title", ""),
                    requireEnum(Kind.class, map, "kind"),
                    requireMoney(map, "amount"),
                    Json.string(map, "category", ""),
                    recurrence,
                    optionalDate(map, "from"),
                    optionalDate(map, "until"),
                    optionalEnum(WeekendPolicy.class, map, "weekendPolicy", WeekendPolicy.NONE),
                    Json.bool(map, "enabled", true),
                    Json.string(map, "note", ""));
        } catch (IllegalArgumentException e) {
            throw new JsonException("Регулярная операция: " + e.getMessage());
        }
    }

    /**
     * Правило повтора в JSON-объект. Поля, не относящиеся к виду повтора, равны {@code null}.
     *
     * @param recurrence повтор
     * @return например {@code {"kind":"MONTHLY","dayOfMonth":5,"everyN":1,"weekday":null,"monthDay":null,"text":"ежемесячно 5"}};
     *         {@code everyN} — период в месяцах, неделях или днях; {@code weekday} — имя {@link DayOfWeek};
     *         {@code monthDay} — {@code "MM-dd"}
     */
    public static Map<String, Object> recurrence(Recurrence recurrence) {
        Objects.requireNonNull(recurrence, "recurrence");
        Long dayOfMonth = null;
        Long everyN = null;
        String weekday = null;
        String monthDay = null;
        switch (recurrence) {
            case Recurrence.Monthly(int day, int everyMonths) -> {
                dayOfMonth = (long) day;
                everyN = (long) everyMonths;
            }
            case Recurrence.Weekly(DayOfWeek day, int everyWeeks) -> {
                weekday = day.name();
                everyN = (long) everyWeeks;
            }
            case Recurrence.EveryNDays(int days) -> everyN = (long) days;
            case Recurrence.Yearly(MonthDay md) -> monthDay = Recurrence.MONTH_DAY.format(md);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", recurrence.kind().name());
        m.put("dayOfMonth", dayOfMonth);
        m.put("everyN", everyN);
        m.put("weekday", weekday);
        m.put("monthDay", monthDay);
        m.put("text", recurrence.toRussian());
        return m;
    }

    /**
     * Правило повтора из JSON-объекта.
     *
     * @param map объект повтора; {@code everyN} для месяцев и недель по умолчанию 1; {@code weekday} принимает
     *            имя константы или русское название («сб», «суббота»); {@code monthDay} — {@code "MM-dd"} или {@code "--MM-dd"}
     * @return повтор
     * @throws JsonException если вид неизвестен, нужного поля нет или значение вне диапазона
     */
    public static Recurrence recurrenceFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        RecurrenceKind kind = requireEnum(RecurrenceKind.class, map, "kind");
        try {
            return switch (kind) {
                case MONTHLY -> new Recurrence.Monthly(requireInt(map, "dayOfMonth"), optionalInt(map, "everyN", 1));
                case WEEKLY -> new Recurrence.Weekly(weekday(map, "weekday"), optionalInt(map, "everyN", 1));
                case EVERY_N_DAYS -> new Recurrence.EveryNDays(requireInt(map, "everyN"));
                case YEARLY -> new Recurrence.Yearly(monthDay(map, "monthDay"));
            };
        } catch (IllegalArgumentException e) {
            throw new JsonException("Повтор: " + e.getMessage());
        }
    }

    /**
     * Разовая операция в JSON-объект.
     *
     * @param tx операция
     * @return объект {@code id, date, title, kind, amount, category, note} и поля для показа {@code dateText, kindText, amountText}
     */
    public static Map<String, Object> oneTime(OneTimeTransaction tx) {
        Objects.requireNonNull(tx, "tx");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.id().value());
        m.put("date", iso(tx.date()));
        m.put("dateText", DateFormats.ru(tx.date()));
        m.put("title", tx.title());
        m.put("kind", tx.kind().name());
        m.put("kindText", tx.kind().title());
        putMoney(m, "amount", tx.amount());
        m.put("category", tx.category());
        m.put("note", tx.note());
        return m;
    }

    /**
     * Разовая операция из JSON-объекта; поле {@code id} обязательно.
     *
     * @param map объект операции
     * @return операция
     * @throws JsonException если нет обязательных полей или значение некорректно
     */
    public static OneTimeTransaction oneTimeFrom(Map<String, Object> map) {
        return oneTimeFrom(map, null);
    }

    /**
     * Разовая операция из JSON-объекта с идентификатором по умолчанию (для создания новой операции).
     *
     * @param map       объект операции
     * @param defaultId идентификатор, если в объекте его нет или он пустой; {@code null} — идентификатор обязателен
     * @return операция
     * @throws JsonException если нет обязательных полей ({@code date, kind, amount}) или значение некорректно
     */
    public static OneTimeTransaction oneTimeFrom(Map<String, Object> map, TxId defaultId) {
        Objects.requireNonNull(map, "map");
        try {
            String idText = Json.string(map, "id", "");
            TxId id = idText.isBlank() ? requireDefault(defaultId, "id") : new TxId(idText);
            return new OneTimeTransaction(id,
                    requireDate(map, "date"),
                    Json.string(map, "title", ""),
                    requireEnum(Kind.class, map, "kind"),
                    requireMoney(map, "amount"),
                    Json.string(map, "category", ""),
                    Json.string(map, "note", ""));
        } catch (IllegalArgumentException e) {
            throw new JsonException("Разовая операция: " + e.getMessage());
        }
    }

    /**
     * Корректировка в JSON-объект.
     *
     * @param adjustment корректировка
     * @return объект {@code ruleId, originalDate, action, amount, date, note} (сумма и дата — {@code null}, если действие
     *         их не задаёт; {@code action} — {@code SKIP, CHANGE_AMOUNT, MOVE_DATE, REPLACE}) и поля для показа
     *         {@code rowId, actionText, amountText}
     */
    public static Map<String, Object> adjustment(Adjustment adjustment) {
        Objects.requireNonNull(adjustment, "adjustment");
        Adjustment.Action action = adjustment.action();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", adjustment.key().ruleId().value());
        m.put("originalDate", iso(adjustment.key().originalDate()));
        m.put("rowId", adjustment.key().asRowId());
        m.put("action", RuFormats.actionTypeOf(action).name());
        m.put("actionText", action.label());
        Optional<Money> amount = action.newAmount();
        m.put("amount", amount.map(Money::formatPlain).orElse(null));
        m.put("amountText", amount.map(Money::format).orElse(null));
        m.put("date", action.newDate().map(DateFormats::iso).orElse(null));
        m.put("note", adjustment.note());
        return m;
    }

    /**
     * Корректировка из JSON-объекта.
     *
     * @param map объект корректировки
     * @return корректировка
     * @throws JsonException если нет ключа события, действия или нужных действию суммы/даты
     */
    public static Adjustment adjustmentFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        try {
            OccurrenceKey key = new OccurrenceKey(new RuleId(Json.requireString(map, "ruleId")), requireDate(map, "originalDate"));
            RuFormats.ActionType type = requireEnum(RuFormats.ActionType.class, map, "action");
            Adjustment.Action action = RuFormats.buildAction(type, optionalMoney(map, "amount"), optionalDate(map, "date"));
            return new Adjustment(key, action, Json.string(map, "note", ""));
        } catch (IllegalArgumentException e) {
            throw new JsonException("Корректировка: " + e.getMessage());
        }
    }

    /**
     * Нераспознанный фрагмент файла в JSON-объект.
     *
     * @param block фрагмент
     * @return {@code {"afterSection": "...", "lines": ["...", ...]}}
     */
    public static Map<String, Object> rawBlock(RawBlock block) {
        Objects.requireNonNull(block, "block");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("afterSection", block.afterSection());
        m.put("lines", new ArrayList<Object>(block.lines()));
        return m;
    }

    /**
     * Нераспознанный фрагмент файла из JSON-объекта. {@code afterSection} переносится без изменений:
     * это часть формата файла (см. {@code MarkdownFormat.PARAMETER_EXTRAS_ANCHOR}).
     *
     * @param map объект фрагмента
     * @return фрагмент
     * @throws JsonException если строки заданы не массивом строк
     */
    public static RawBlock rawBlockFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        List<String> lines = new ArrayList<>();
        List<Object> items = Json.list(map, "lines");
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof String line)) {
                throw new JsonException("Элемент lines[" + i + "] должен быть строкой, получено: " + Json.typeName(items.get(i)));
            }
            lines.add(line);
        }
        return new RawBlock(Json.string(map, "afterSection", ""), lines);
    }

    // ================================================================== прогноз

    /**
     * Прогноз в JSON-объект для таблицы, графика, сводки и списка предупреждений.
     *
     * <p>Строки и точки графика передаются отдельно, потому что web-клиенту нужна только видимая часть:
     * обычно это {@code PlanDocument.visibleRows()} и {@code ChartSeries.sample(...)}.</p>
     *
     * @param forecast прогноз
     * @param rows     строки для вывода ({@code null} — пустой список)
     * @param chart    точки графика ({@code null} — пустой список)
     * @return объект {@code planName, currency, today, anchor, startDate, endDate, startBalance(Text), endBalance(Text),
     *         cushion(Text), goal, whatIf, rows, summary, warnings, chart}
     */
    public static Map<String, Object> forecast(Forecast forecast, List<ForecastRow> rows, List<DailyPoint> chart) {
        Objects.requireNonNull(forecast, "forecast");
        Plan plan = forecast.plan();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planName", plan.name());
        m.put("currency", plan.currency());
        m.put("today", iso(forecast.today()));
        m.put("anchor", iso(forecast.anchor()));
        m.put("startDate", iso(forecast.startDate()));
        m.put("endDate", iso(forecast.endDate()));
        putMoney(m, "startBalance", forecast.startBalance());
        putMoney(m, "endBalance", forecast.endBalance());
        putMoney(m, "cushion", plan.cushion());
        m.put("goal", plan.goal() == null ? null : goal(plan.goal()));
        m.put("whatIf", whatIf(forecast.whatIf()));
        m.put("rows", mapList(rows == null ? List.of() : rows, row -> row(row, plan.cushion())));
        m.put("summary", summary(forecast.summary()));
        m.put("warnings", warnings(forecast.warnings()));
        m.put("chart", mapList(chart == null ? List.of() : chart, PlanJson::point));
        return m;
    }

    /**
     * Строка прогноза в JSON-объект.
     *
     * @param row     строка
     * @param cushion подушка безопасности плана (для признака {@code belowCushion})
     * @return объект {@code rowId, date, dateText, weekday, originalDate, title, kind, category, amount (со знаком),
     *         amountText (без знака), amountSignedText, balanceAfter, balanceAfterText, negative, belowCushion, origin,
     *         originTitle, ruleId, txId, flags, note}
     */
    public static Map<String, Object> row(ForecastRow row, Money cushion) {
        Objects.requireNonNull(row, "row");
        Money limit = cushion == null ? Money.ZERO : cushion;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowId", row.rowId());
        m.put("date", iso(row.date()));
        m.put("dateText", DateFormats.ru(row.date()));
        m.put("weekday", RuText.weekdayShort(row.date().getDayOfWeek()));
        m.put("originalDate", iso(row.originalDate()));
        m.put("title", row.title());
        m.put("kind", row.kind().name());
        m.put("category", row.category());
        m.put("amount", row.amount().formatPlain());
        m.put("amountText", row.amount().abs().format());
        m.put("amountSignedText", row.amount().formatSigned());
        putMoney(m, "balanceAfter", row.balanceAfter());
        m.put("negative", row.balanceAfter().isNegative());
        m.put("belowCushion", limit.isPositive() && row.balanceAfter().isLessThan(limit));
        m.put("origin", row.origin().name());
        m.put("originTitle", row.origin().title());
        m.put("ruleId", row.ruleId() == null ? null : row.ruleId().value());
        m.put("txId", row.txId() == null ? null : row.txId().value());
        m.put("flags", flags(row.flags()));
        m.put("note", row.note());
        return m;
    }

    /**
     * Отметки строки в JSON-объект.
     *
     * @param flags отметки
     * @return {@code {"shifted", "amountChanged", "moved", "skipped", "past", "whatIf"}}
     */
    public static Map<String, Object> flags(Flags flags) {
        Objects.requireNonNull(flags, "flags");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shifted", flags.shifted());
        m.put("amountChanged", flags.amountChanged());
        m.put("moved", flags.moved());
        m.put("skipped", flags.skipped());
        m.put("past", flags.past());
        m.put("whatIf", flags.whatIf());
        return m;
    }

    /**
     * Сводка прогноза в JSON-объект. Словари сводки передаются массивами: ключи JSON-объекта могут быть только
     * строками, а порядок элементов массива гарантирован.
     *
     * @param summary сводка
     * @return объект с деньгами (и {@code ...Text}), датами (ISO или {@code null}), массивами
     *         {@code balanceAfterMonths: [{months, date, balance, balanceText}]} и
     *         {@code byMonth: [{month, title, income, expense, net, closingBalance (и ...Text)}]}
     */
    public static Map<String, Object> summary(ForecastSummary summary) {
        Objects.requireNonNull(summary, "summary");
        Map<String, Object> m = new LinkedHashMap<>();
        putMoney(m, "startBalance", summary.startBalance());
        putMoney(m, "endBalance", summary.endBalance());
        m.put("anchor", iso(summary.anchor()));
        List<Object> marks = new ArrayList<>();
        summary.balanceAfterMonths().forEach((months, balance) -> {
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("months", (long) months);
            mark.put("date", iso(summary.anchor().plusMonths(months)));
            putMoney(mark, "balance", balance);
            marks.add(mark);
        });
        m.put("balanceAfterMonths", marks);
        putMoney(m, "totalIncome", summary.totalIncome());
        putMoney(m, "totalExpense", summary.totalExpense());
        putMoney(m, "averageMonthlyNet", summary.averageMonthlyNet());
        putMoney(m, "minBalance", summary.minBalance());
        m.put("minBalanceDate", iso(summary.minBalanceDate()));
        m.put("firstNegativeDate", summary.firstNegativeDate().map(DateFormats::iso).orElse(null));
        m.put("firstBelowCushionDate", summary.firstBelowCushionDate().map(DateFormats::iso).orElse(null));
        m.put("goalReachDate", summary.goalReachDate().map(DateFormats::iso).orElse(null));
        List<Object> months = new ArrayList<>();
        for (Map.Entry<YearMonth, MonthTotals> entry : summary.byMonth().entrySet()) {
            MonthTotals totals = entry.getValue();
            Map<String, Object> month = new LinkedHashMap<>();
            month.put("month", entry.getKey().toString());
            month.put("title", DateFormats.monthTitle(entry.getKey()));
            putMoney(month, "income", totals.income());
            putMoney(month, "expense", totals.expense());
            putMoney(month, "net", totals.net());
            putMoney(month, "closingBalance", totals.closingBalance());
            months.add(month);
        }
        m.put("byMonth", months);
        return m;
    }

    /**
     * Предупреждения прогноза в JSON-массив.
     *
     * @param warnings предупреждения
     * @return массив объектов {@code {severity, severityTitle, date, type, typeTitle, message, text}}
     */
    public static List<Object> warnings(List<Warning> warnings) {
        Objects.requireNonNull(warnings, "warnings");
        return mapList(warnings, w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", w.severity().name());
            m.put("severityTitle", w.severity().title());
            m.put("date", isoOrNull(w.date()));
            m.put("type", w.type().name());
            m.put("typeTitle", w.type().title());
            m.put("message", w.message());
            m.put("text", w.format());
            return m;
        });
    }

    /** Точка графика: дата, баланс строкой и в копейках (числом — для вычисления координат на SVG). */
    private static Map<String, Object> point(DailyPoint point) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", iso(point.date()));
        putMoney(m, "balance", point.balance());
        m.put("balanceMinor", point.balance().minor());
        return m;
    }

    // ================================================================== вид и «что-если»

    /**
     * Параметры отображения в JSON-объект.
     *
     * @param state вид
     * @return объект {@code mode, period, showIncome, showExpense, showOneTime, showSkipped, monthTotals, chartMarkers,
     *         chartBars, summaryPanel, filterText, whatIf} и поля для показа {@code modeText, periodText, periodMonths}
     */
    public static Map<String, Object> viewState(ViewState state) {
        Objects.requireNonNull(state, "state");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", state.mode().name());
        m.put("modeText", state.mode().label());
        m.put("period", state.period().name());
        m.put("periodText", state.period().label());
        m.put("periodMonths", (long) state.period().months());
        m.put("showIncome", state.showIncome());
        m.put("showExpense", state.showExpense());
        m.put("showOneTime", state.showOneTime());
        m.put("showSkipped", state.showSkipped());
        m.put("monthTotals", state.monthTotals());
        m.put("chartMarkers", state.chartMarkers());
        m.put("chartBars", state.chartBars());
        m.put("summaryPanel", state.summaryPanel());
        m.put("filterText", state.filterText());
        m.put("whatIf", whatIf(state.whatIf()));
        return m;
    }

    /**
     * Параметры отображения из JSON-объекта; отсутствующие поля берутся из {@link ViewState#defaults()}.
     *
     * @param map объект вида; {@code mode} и {@code period} принимают имя константы или русскую подпись
     * @return вид
     * @throws JsonException если значение некорректно
     */
    public static ViewState viewStateFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        ViewState d = ViewState.defaults();
        return new ViewState(
                parsed(map, "mode", ViewMode::parse, d.mode()),
                parsed(map, "period", PeriodChoice::parse, d.period()),
                Json.bool(map, "showIncome", d.showIncome()),
                Json.bool(map, "showExpense", d.showExpense()),
                Json.bool(map, "showOneTime", d.showOneTime()),
                Json.bool(map, "showSkipped", d.showSkipped()),
                Json.bool(map, "monthTotals", d.monthTotals()),
                Json.bool(map, "chartMarkers", d.chartMarkers()),
                Json.bool(map, "chartBars", d.chartBars()),
                Json.bool(map, "summaryPanel", d.summaryPanel()),
                Json.string(map, "filterText", d.filterText()),
                Json.optionalObject(map, "whatIf").map(PlanJson::whatIfFrom).orElse(d.whatIf()));
    }

    /**
     * Параметры «что-если» в JSON-объект.
     *
     * @param whatIf параметры
     * @return {@code {"incomeFactor":"1.10","expenseFactor":"0.90","extraMonthlySaving":"5000,00",
     *         "extraMonthlySavingText":"5 000,00","active":true}}
     */
    public static Map<String, Object> whatIf(WhatIf whatIf) {
        Objects.requireNonNull(whatIf, "whatIf");
        Map<String, Object> m = new LinkedHashMap<>();
        // toPlainString сохраняет масштаб («1.10»), поэтому обратное чтение даёт равный BigDecimal.
        m.put("incomeFactor", whatIf.incomeFactor().toPlainString());
        m.put("expenseFactor", whatIf.expenseFactor().toPlainString());
        putMoney(m, "extraMonthlySaving", whatIf.extraMonthlySaving());
        m.put("active", !whatIf.isNone());
        return m;
    }

    /**
     * Параметры «что-если» из JSON-объекта; коэффициенты — строка («1.10», «1,10») или число, по умолчанию 1.
     *
     * @param map объект параметров
     * @return параметры
     * @throws JsonException если коэффициент не число или значение отрицательное
     */
    public static WhatIf whatIfFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        try {
            return new WhatIf(decimal(map, "incomeFactor"), decimal(map, "expenseFactor"),
                    money(map, "extraMonthlySaving", Money.ZERO));
        } catch (IllegalArgumentException e) {
            throw new JsonException("Что-если: " + e.getMessage());
        }
    }

    // ================================================================== диагностика и настройки

    /**
     * Диагностика в JSON-массив.
     *
     * @param diagnostics сообщения
     * @return массив объектов {@code {severity, severityTitle, line, message, text}}; {@code line} 0 — без строки
     */
    public static List<Object> diagnostics(List<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        return mapList(diagnostics, d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", d.severity().name());
            m.put("severityTitle", d.severity().title());
            m.put("line", (long) d.line());
            m.put("message", d.message());
            m.put("text", d.format());
            return m;
        });
    }

    /**
     * Настройки приложения в JSON-объект.
     *
     * @param settings настройки
     * @return объект с полями, названными как компоненты {@link AppSettings}; перечисления — именем константы
     */
    public static Map<String, Object> settings(AppSettings settings) {
        Objects.requireNonNull(settings, "settings");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastPlan", settings.lastPlan());
        m.put("recentPlans", new ArrayList<Object>(settings.recentPlans()));
        m.put("recoveryStore", settings.recoveryStore().name());
        m.put("autosave", settings.autosave());
        m.put("view", settings.view().name());
        m.put("period", settings.period().name());
        m.put("showIncome", settings.showIncome());
        m.put("showExpense", settings.showExpense());
        m.put("showOneTime", settings.showOneTime());
        m.put("showSkipped", settings.showSkipped());
        m.put("monthTotals", settings.monthTotals());
        m.put("chartMarkers", settings.chartMarkers());
        m.put("chartBars", settings.chartBars());
        m.put("summaryPanel", settings.summaryPanel());
        return m;
    }

    /**
     * Настройки приложения из JSON-объекта; отсутствующие поля берутся из {@link AppSettings#defaults()}.
     *
     * @param map объект настроек
     * @return настройки (нормализованные конструктором {@link AppSettings})
     * @throws JsonException если значение некорректно
     */
    public static AppSettings settingsFrom(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        AppSettings d = AppSettings.defaults();
        List<String> recent = new ArrayList<>();
        List<Object> items = Json.list(map, "recentPlans");
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof String entry)) {
                throw new JsonException("Элемент recentPlans[" + i + "] должен быть строкой, получено: " + Json.typeName(items.get(i)));
            }
            recent.add(entry);
        }
        return new AppSettings(
                Json.string(map, "lastPlan", d.lastPlan()),
                map.containsKey("recentPlans") ? recent : d.recentPlans(),
                parsed(map, "recoveryStore", RecoveryStoreKind::parse, d.recoveryStore()),
                Json.bool(map, "autosave", d.autosave()),
                parsed(map, "view", ViewMode::parse, d.view()),
                parsed(map, "period", PeriodChoice::parse, d.period()),
                Json.bool(map, "showIncome", d.showIncome()),
                Json.bool(map, "showExpense", d.showExpense()),
                Json.bool(map, "showOneTime", d.showOneTime()),
                Json.bool(map, "showSkipped", d.showSkipped()),
                Json.bool(map, "monthTotals", d.monthTotals()),
                Json.bool(map, "chartMarkers", d.chartMarkers()),
                Json.bool(map, "chartBars", d.chartBars()),
                Json.bool(map, "summaryPanel", d.summaryPanel()));
    }

    // ================================================================== вспомогательное

    /** Кладёт каноническую сумму под ключом {@code key} и текст для показа под ключом {@code key + "Text"}. */
    private static void putMoney(Map<String, Object> map, String key, Money money) {
        map.put(key, money.formatPlain());
        map.put(key + "Text", money.format());
    }

    private static String iso(LocalDate date) {
        return DateFormats.iso(date);
    }

    private static String isoOrNull(LocalDate date) {
        return date == null ? null : DateFormats.iso(date);
    }

    /** Преобразует список в изменяемый {@code ArrayList<Object>}: такой же тип возвращает {@link JsonParser}. */
    private static <T> List<Object> mapList(List<T> items, Function<T, ?> mapper) {
        List<Object> result = new ArrayList<>(items.size());
        for (T item : items) {
            result.add(mapper.apply(item));
        }
        return result;
    }

    /** Читает массив объектов, добавляя к сообщению об ошибке номер элемента. */
    private static <T> List<T> objects(Map<String, Object> map, String key, Function<Map<String, Object>, T> reader) {
        List<Object> items = Json.list(map, key);
        List<T> result = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String where = key + "[" + i + "]";
            try {
                result.add(reader.apply(Json.asObject(items.get(i), where)));
            } catch (JsonException e) {
                throw new JsonException("Элемент " + where + ": " + e.getMessage());
            }
        }
        return result;
    }

    private static <T> T requireDefault(T value, String key) {
        if (value == null) {
            throw new JsonException("Отсутствует обязательное поле «" + key + "»");
        }
        return value;
    }

    /** Необязательная дата: отсутствие, {@code null} и пустая строка дают {@code null}. */
    private static LocalDate optionalDate(Map<String, Object> map, String key) {
        String text = Json.string(map, key, null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return DateFormats.parse(text);
        } catch (IllegalArgumentException e) {
            throw new JsonException("Поле «" + key + "»: " + e.getMessage());
        }
    }

    private static LocalDate requireDate(Map<String, Object> map, String key) {
        return requireDefault(optionalDate(map, key), key);
    }

    /**
     * Необязательная сумма: строка ({@code "95000,00"}, {@code "95 000,00"}) или число в целых единицах валюты;
     * отсутствие, {@code null} и пустая строка дают {@code null}.
     */
    private static Money optionalMoney(Map<String, Object> map, String key) {
        Object value = map.get(key);
        try {
            return switch (value) {
                case null -> null;
                case String s when s.isBlank() -> null;
                case String s -> Money.parse(s);
                case Long l -> Money.ofMajor(l);
                case BigDecimal bd -> Money.parse(bd.toPlainString());
                default -> throw new JsonException("Поле «" + key + "» должно быть суммой (строкой), получено: " + Json.typeName(value));
            };
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new JsonException("Поле «" + key + "»: " + e.getMessage());
        }
    }

    private static Money money(Map<String, Object> map, String key, Money defaultValue) {
        Money value = optionalMoney(map, key);
        return value == null ? defaultValue : value;
    }

    private static Money requireMoney(Map<String, Object> map, String key) {
        return requireDefault(optionalMoney(map, key), key);
    }

    /** Коэффициент: строка с точкой или запятой либо число; по умолчанию 1. */
    private static BigDecimal decimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        try {
            return switch (value) {
                case null -> BigDecimal.ONE;
                case String s -> new BigDecimal(s.strip().replace(',', '.'));
                case Long l -> BigDecimal.valueOf(l);
                case BigDecimal bd -> bd;
                default -> throw new JsonException("Поле «" + key + "» должно быть числом, получено: " + Json.typeName(value));
            };
        } catch (NumberFormatException e) {
            throw new JsonException("Поле «" + key + "»: некорректное число «" + value + "»");
        }
    }

    /** Целое: число JSON или строка из цифр (так удобнее значениям полей ввода браузера). */
    private static Integer intOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || value instanceof String s && s.isBlank()) {
            return null;
        }
        long number;
        if (value instanceof String s) {
            try {
                number = Long.parseLong(s.strip());
            } catch (NumberFormatException e) {
                throw new JsonException("Поле «" + key + "» должно быть целым числом, получено: «" + s + "»");
            }
        } else {
            number = Json.longValue(map, key, 0);
        }
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new JsonException("Поле «" + key + "»: число вне допустимого диапазона");
        }
        return (int) number;
    }

    private static int optionalInt(Map<String, Object> map, String key, int defaultValue) {
        Integer value = intOrNull(map, key);
        return value == null ? defaultValue : value;
    }

    private static int requireInt(Map<String, Object> map, String key) {
        return requireDefault(intOrNull(map, key), key);
    }

    /** Константа перечисления по имени без учёта регистра; неизвестное имя — {@link JsonException} со списком допустимых. */
    private static <E extends Enum<E>> E optionalEnum(Class<E> type, Map<String, Object> map, String key, E defaultValue) {
        String text = Json.string(map, key, null);
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        String name = text.strip().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        throw new JsonException("Поле «" + key + "»: неизвестное значение «" + text + "» (ожидается одно из: "
                + String.join(", ", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()) + ")");
    }

    private static <E extends Enum<E>> E requireEnum(Class<E> type, Map<String, Object> map, String key) {
        return requireDefault(optionalEnum(type, map, key, null), key);
    }

    /** Значение через разборщик перечисления вида ({@code ViewMode.parse} и т. п.), принимающий имя и подпись. */
    private static <E> E parsed(Map<String, Object> map, String key, Function<String, Optional<E>> parser, E defaultValue) {
        String text = Json.string(map, key, null);
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        return parser.apply(text).orElseThrow(() -> new JsonException("Поле «" + key + "»: неизвестное значение «" + text + "»"));
    }

    /** День недели: имя константы ({@code SATURDAY}) или русское название («сб», «суббота»). */
    private static DayOfWeek weekday(Map<String, Object> map, String key) {
        String text = Json.requireString(map, key).strip();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().equalsIgnoreCase(text)) {
                return day;
            }
        }
        // RuText.parseWeekday не бросает, а возвращает null для незнакомого текста: без проверки дальше
        // вылетел бы NullPointerException из конструктора повтора вместо понятного ответа 400.
        DayOfWeek day = RuText.parseWeekday(text);
        if (day == null) {
            throw new JsonException("Поле «" + key + "»: неизвестный день недели «" + text + "»");
        }
        return day;
    }

    /** День года: {@code "MM-dd"} или ISO {@code "--MM-dd"}. */
    private static MonthDay monthDay(Map<String, Object> map, String key) {
        String text = Json.requireString(map, key).strip();
        try {
            return MonthDay.parse(text.startsWith("--") ? text : "--" + text);
        } catch (DateTimeParseException e) {
            throw new JsonException("Поле «" + key + "»: некорректный день года «" + text + "» (ожидается ММ-ДД)");
        }
    }
}
