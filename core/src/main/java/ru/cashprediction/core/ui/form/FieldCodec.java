package ru.cashprediction.core.ui.form;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.RecurrenceKind;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.util.RuText;

/**
 * Канонические и показываемые формы значений полей (архитектура §3.5).
 *
 * <table>
 *   <caption>Формы значений</caption>
 *   <tr><th>Вид</th><th>Каноническая форма (FormState, снимок, протокол)</th><th>Показ</th></tr>
 *   <tr><td>MONEY</td><td>{@code 95000,00} ({@code Money.formatPlain})</td><td>«95 000,00»</td></tr>
 *   <tr><td>DATE</td><td>ISO {@code 2026-10-05}</td><td>«05.10.2026»</td></tr>
 *   <tr><td>MONTH_DAY</td><td>{@code 03-15}</td><td>«15.03»</td></tr>
 *   <tr><td>CHECK</td><td>{@code true}/{@code false}</td><td>флажок</td></tr>
 *   <tr><td>SPINNER</td><td>целое без пробелов</td><td>число</td></tr>
 *   <tr><td>CHOICE, RADIO, LIST</td><td>{@code Option.value}</td><td>{@code Option.text}</td></tr>
 *   <tr><td>TEXT, MULTILINE, EDITABLE_CHOICE</td><td>текст как есть</td><td>текст</td></tr>
 * </table>
 *
 * <p><b>Коды вариантов</b> (значения {@code Option.value}, решение S0.5: в состоянии формы и снимке хранятся коды, а не
 * подписи): тип операции {@code INCOME}/{@code EXPENSE} ({@link Kind}); повтор {@code MONTHLY}, {@code WEEKLY},
 * {@code EVERY_N_DAYS}, {@code YEARLY} ({@link RecurrenceKind}); выходные {@code NONE},
 * {@code PREVIOUS_BUSINESS_DAY}, {@code NEXT_BUSINESS_DAY} ({@link WeekendPolicy}); день недели - имя
 * {@link DayOfWeek} ({@code MONDAY}); вид горизонта {@code MONTHS}/{@code YEARS}/{@code UNTIL}; действие корректировки
 * {@code SKIP}/{@code CHANGE_AMOUNT}/{@code MOVE}/{@code REPLACE}; разделитель CSV {@code ;}/{@code ,}/{@code TAB};
 * строки CSV {@code PERIOD}/{@code ALL}.</p>
 *
 * <p><b>Суммы в полях интерфейса строже файла плана</b> (решение L1b): {@link #parseMoney(String)} принимает всё, что
 * принимает {@code Money.parse} («80000», «80 000,5», «80000.50», «-1 200», «1.234,56»), но отклоняет больше двух цифр
 * после десятичного разделителя («1,234», «12.345», «0,005»): в поле такая запись чаще всего означает тысячи, а не
 * округление до копеек. Файлы плана по-прежнему читает {@code Money.parse} с округлением и диагностикой чтения.
 * Текст проблемы строит {@code FieldChecks.money}.</p>
 *
 * <p>Некорректный ввод не теряется: {@link #canonical} возвращает сырой текст, {@link #display} показывает его
 * дословно. {@link #acceptLegacy} переводит значения из снимков прежних клиентов (фикстуры этапа S1) в
 * канонические формы.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FieldCodec {

    /** Значение флажка «отмечен». */
    public static final String TRUE = "true";
    /** Значение флажка «снят». */
    public static final String FALSE = "false";

    /** Каноническая форма даты: ISO, строго. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    /** Дата интерфейса при разборе: день и месяц одной или двумя цифрами, строго (без «30.02» → «28.02»). */
    private static final DateTimeFormatter RU_INPUT = DateTimeFormatter.ofPattern("d.M.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    /** Дата интерфейса при показе: «05.10.2026». */
    private static final DateTimeFormatter RU_DISPLAY = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    /** День года «ДД.ММ». */
    private static final Pattern DAY_MONTH = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})");
    /** День года «ММ-ДД» (как в файле плана), допускается ведущее «--» формата ISO. */
    private static final Pattern MONTH_DAY_ISO = Pattern.compile("(?:--)?(\\d{1,2})-(\\d{1,2})");
    /** Целое со знаком. */
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d{1,18}");
    /** Целая часть суммы, для которой можно предложить запись тысяч: 1-3 цифры без ведущего нуля. */
    private static final Pattern SHORT_INTEGER = Pattern.compile("[1-9]\\d{0,2}");

    private FieldCodec() {
    }

    /**
     * Каноническая форма введённого текста. Принимает «80000», «80 000,5», «80000.50», «-1 200»; даты ISO и
     * ДД.ММ.ГГГГ; день года ДД.ММ и ММ-ДД.
     *
     * @param kind вид поля
     * @param raw  текст виджета
     * @return каноническое значение или сырой текст, если он некорректен; для {@code null} - пустая строка
     */
    public static String canonical(FieldKind kind, String raw) {
        Objects.requireNonNull(kind, "kind");
        if (raw == null) {
            return "";
        }
        return switch (kind) {
            case MONEY -> raw.isBlank() ? "" : parseMoney(raw).map(Money::formatPlain).orElse(raw);
            case DATE -> raw.isBlank() ? "" : parseDate(raw).map(FieldCodec::date).orElse(raw);
            case MONTH_DAY -> raw.isBlank() ? "" : parseMonthDay(raw).map(FieldCodec::monthDay).orElse(raw);
            case SPINNER -> raw.isBlank() ? "" : parseLong(raw).isPresent() ? Long.toString(parseLong(raw).getAsLong()) : raw;
            case CHECK -> raw.strip().equalsIgnoreCase(TRUE) ? TRUE : raw.strip().equalsIgnoreCase(FALSE) ? FALSE : raw;
            // Выбор, текст и прочее хранятся как есть: пробелы в имени плана или заметке - часть ввода.
            case TEXT, MULTILINE, EDITABLE_CHOICE, CHOICE, RADIO, LIST, PREVIEW, RESULT_LINES, BUTTON -> raw;
        };
    }

    /**
     * Текст для виджета по канонической форме.
     *
     * @param kind      вид поля
     * @param canonical каноническое значение (или сохранённый некорректный текст)
     * @return показываемый текст: сумма «95 000,00», дата «05.10.2026», день года «15.03»; некорректное - как есть
     */
    public static String display(FieldKind kind, String canonical) {
        Objects.requireNonNull(kind, "kind");
        if (canonical == null) {
            return "";
        }
        return switch (kind) {
            case MONEY -> parseMoney(canonical).map(Money::format).orElse(canonical);
            case DATE -> parseDate(canonical).map(RU_DISPLAY::format).orElse(canonical);
            case MONTH_DAY -> parseMonthDay(canonical)
                    .map(md -> twoDigits(md.getDayOfMonth()) + "." + twoDigits(md.getMonthValue())).orElse(canonical);
            case TEXT, MULTILINE, EDITABLE_CHOICE, CHOICE, RADIO, LIST, PREVIEW, RESULT_LINES, BUTTON, SPINNER, CHECK ->
                    canonical;
        };
    }

    /**
     * Значение поля из снимка прежнего клиента в канонической форме нового интерфейса.
     *
     * <p>Вид поля определяется по типу окна и id поля (таблица полей спецификации §6). Суммы, даты, дни года, флажки
     * и целые приводятся {@link #canonical}; коды вариантов - к именам констант без учёта регистра, прежние подписи
     * («Доход», «Ежемесячно / каждые N месяцев», «Не сдвигать») и прежние коды ({@code MOVE_DATE} → {@code MOVE},
     * номер дня недели 1-7, табуляция → {@code TAB}) - к кодам нового интерфейса. Незнакомое поле или значение
     * возвращается как есть.</p>
     *
     * @param type    тип окна
     * @param fieldId id поля
     * @param value   значение из снимка
     * @return каноническое значение (некорректное - как есть)
     */
    public static String acceptLegacy(WindowType type, String fieldId, String value) {
        if (value == null) {
            return "";
        }
        if (type == null || fieldId == null) {
            return value;
        }
        String code = value.strip();
        if (type == WindowType.CHOICE && "value".equals(fieldId)) {
            if (code.equals("__other__") || code.equalsIgnoreCase(UiText.get("legacy.choice.currencyCustom"))) {
                return "custom";
            }
            if (code.equals("__file__") || code.equalsIgnoreCase(UiText.get("legacy.choice.fromFile"))) {
                return "fromFile";
            }
            if (code.startsWith("name:")) {
                return code.substring("name:".length());
            }
        }
        switch (fieldId) {
            case "kind" -> {
                if (type == WindowType.RULE_EDITOR || type == WindowType.ONE_TIME_EDITOR) {
                    for (Kind kind : Kind.values()) {
                        if (code.equalsIgnoreCase(kind.name()) || code.equalsIgnoreCase(kind.title())) {
                            return kind.name();
                        }
                    }
                    return value;
                }
            }
            case "recurrenceKind" -> {
                for (RecurrenceKind kind : RecurrenceKind.values()) {
                    if (code.equalsIgnoreCase(kind.name()) || code.equalsIgnoreCase(kind.title())) {
                        return kind.name();
                    }
                }
                return value;
            }
            case "weekendPolicy" -> {
                for (WeekendPolicy policy : WeekendPolicy.values()) {
                    if (code.equalsIgnoreCase(policy.name()) || code.equalsIgnoreCase(policy.title())) {
                        return policy.name();
                    }
                }
                return value;
            }
            case "weekday" -> {
                return legacyWeekday(code).map(DayOfWeek::name).orElse(value);
            }
            case "horizonKind" -> {
                return upperIfOneOf(code, value, "MONTHS", "YEARS", "UNTIL");
            }
            case "action" -> {
                // Прежние клиенты писали код формата файла MOVE_DATE; новый интерфейс - MOVE (AdjustmentForm).
                return code.equalsIgnoreCase("MOVE_DATE") ? "MOVE"
                        : upperIfOneOf(code, value, "SKIP", "CHANGE_AMOUNT", "MOVE", "REPLACE");
            }
            case "separator" -> {
                if (value.equals("\t") || code.equalsIgnoreCase("TAB")) {
                    return "TAB";
                }
                return code.equals(";") || code.equals(",") ? code : value;
            }
            case "range" -> {
                return upperIfOneOf(code, value, "PERIOD", "ALL");
            }
            default -> {
                // Остальные поля - по виду из таблицы ниже.
            }
        }
        FieldKind kind = legacyKind(type, fieldId);
        return kind == null ? value : canonical(kind, value);
    }

    /**
     * Сумма из текста поля интерфейса (решение L1b): как {@code Money.parse}, но больше двух цифр после десятичного
     * разделителя - некорректный ввод, а не округление.
     *
     * @param text текст поля
     * @return сумма или пусто, если текст пустой или некорректный
     */
    public static Optional<Money> parseMoney(String text) {
        MoneyInput input = analyzeMoney(text);
        return input.status() == MoneyStatus.OK ? Optional.of(input.value()) : Optional.empty();
    }

    /**
     * Дата из текста поля: {@code ДД.ММ.ГГГГ} (день и месяц можно одной цифрой) или ISO {@code ГГГГ-ММ-ДД}; строго,
     * «30.02.2026» некорректна.
     *
     * @param text текст поля
     * @return дата или пусто
     */
    public static Optional<LocalDate> parseDate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.strip();
        try {
            return Optional.of(LocalDate.parse(t, t.indexOf('.') >= 0 ? RU_INPUT : ISO));
        } catch (DateTimeException e) {
            // Недопечатанная дата - обычное состояние поля, а не ошибка программы.
            return Optional.empty();
        }
    }

    /**
     * День года из текста поля: «ДД.ММ» (привычно по-русски) или «ММ-ДД» (как в файле плана).
     *
     * @param text текст поля
     * @return день года или пусто («31.02» некорректен, «29.02» допустим)
     */
    public static Optional<MonthDay> parseMonthDay(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.strip();
        try {
            Matcher dayMonth = DAY_MONTH.matcher(t);
            if (dayMonth.matches()) {
                return Optional.of(MonthDay.of(Integer.parseInt(dayMonth.group(2)), Integer.parseInt(dayMonth.group(1))));
            }
            Matcher monthDay = MONTH_DAY_ISO.matcher(t);
            if (monthDay.matches()) {
                return Optional.of(MonthDay.of(Integer.parseInt(monthDay.group(1)), Integer.parseInt(monthDay.group(2))));
            }
        } catch (DateTimeException e) {
            // «31.02»: такой даты в году нет - поле некорректно.
        }
        return Optional.empty();
    }

    /**
     * Целое из текста поля (спиннер, число месяцев).
     *
     * @param text текст поля
     * @return число или пусто
     */
    public static OptionalLong parseLong(String text) {
        if (text == null) {
            return OptionalLong.empty();
        }
        String t = text.strip();
        return INTEGER.matcher(t).matches() ? OptionalLong.of(Long.parseLong(t)) : OptionalLong.empty();
    }

    /**
     * Флажок из канонического значения.
     *
     * @param value значение
     * @return {@code true} только для {@code true} (без учёта регистра и пробелов по краям)
     */
    public static boolean parseBoolean(String value) {
        return value != null && value.strip().equalsIgnoreCase(TRUE);
    }

    /**
     * Каноническая форма суммы.
     *
     * @param money сумма или {@code null}
     * @return {@code 95000,00} или пустая строка
     */
    public static String money(Money money) {
        return money == null ? "" : money.formatPlain();
    }

    /**
     * Каноническая форма даты.
     *
     * @param date дата или {@code null}
     * @return ISO {@code 2026-10-05} или пустая строка
     */
    public static String date(LocalDate date) {
        return date == null ? "" : ISO.format(date);
    }

    /**
     * Каноническая форма дня года.
     *
     * @param monthDay день года или {@code null}
     * @return {@code 03-15} или пустая строка
     */
    public static String monthDay(MonthDay monthDay) {
        return monthDay == null ? "" : twoDigits(monthDay.getMonthValue()) + "-" + twoDigits(monthDay.getDayOfMonth());
    }

    /**
     * Каноническая форма флажка.
     *
     * @param checked отмечен ли
     * @return {@code true} или {@code false}
     */
    public static String bool(boolean checked) {
        return checked ? TRUE : FALSE;
    }

    /** Итог разбора суммы поля интерфейса (для текстов {@code FieldChecks}). */
    enum MoneyStatus {
        /** Поле пустое. */
        EMPTY,
        /** Сумма разобрана. */
        OK,
        /** Текст не является суммой (в том числе неверные группы разрядов, решение L1). */
        INVALID,
        /** Больше двух цифр после десятичного разделителя (решение L1b). */
        FRACTION,
        /** Число не помещается в сумму. */
        TOO_BIG
    }

    /**
     * Разобранная сумма поля.
     *
     * @param status     итог
     * @param value      сумма для {@link MoneyStatus#OK}, иначе {@code null}
     * @param text       введённый текст без пробелов по краям
     * @param suggestion запись тысяч для неоднозначного ввода («1,234» → «1 234») или пустая строка
     */
    record MoneyInput(MoneyStatus status, Money value, String text, String suggestion) {
    }

    /**
     * Разбирает сумму поля интерфейса и объясняет, чем она некорректна.
     *
     * @param raw текст поля
     * @return итог разбора
     */
    static MoneyInput analyzeMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            return new MoneyInput(MoneyStatus.EMPTY, null, "", "");
        }
        String text = raw.strip();
        Money value;
        try {
            value = Money.parse(text);
        } catch (IllegalArgumentException e) {
            // Money.parse сообщает о переполнении исключением с причиной ArithmeticException.
            MoneyStatus status = e.getCause() instanceof ArithmeticException ? MoneyStatus.TOO_BIG : MoneyStatus.INVALID;
            return new MoneyInput(status, null, text, "");
        } catch (ArithmeticException e) {
            return new MoneyInput(MoneyStatus.TOO_BIG, null, text, "");
        }
        String number = withoutCurrency(text);
        int decimal = decimalSeparatorIndex(number);
        if (decimal >= 0) {
            String fraction = number.substring(decimal + 1).strip();
            if (fraction.length() > 2) {
                return new MoneyInput(MoneyStatus.FRACTION, null, text, thousandsSuggestion(number, decimal));
            }
        }
        return new MoneyInput(MoneyStatus.OK, value, text, "");
    }

    /** @return текст без символов валют и пробелов по краям (как их отбрасывает {@code Money.parse}) */
    private static String withoutCurrency(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().filter(cp -> Character.getType(cp) != Character.CURRENCY_SYMBOL).forEach(sb::appendCodePoint);
        return sb.toString().strip();
    }

    /**
     * Позиция десятичного разделителя по правилам {@code Money.parse}: при запятой и точке - последний из них;
     * одиночная запятая или точка - разделитель; повторённая - разделитель разрядов.
     *
     * @return индекс или -1, если дробной части нет
     */
    private static int decimalSeparatorIndex(String number) {
        int lastComma = number.lastIndexOf(',');
        int lastDot = number.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            return Math.max(lastComma, lastDot);
        }
        if (lastComma >= 0) {
            return number.indexOf(',') == lastComma ? lastComma : -1;
        }
        if (lastDot >= 0) {
            return number.indexOf('.') == lastDot ? lastDot : -1;
        }
        return -1;
    }

    /**
     * Запись тысяч для неоднозначной суммы: «1,234» → «1 234», «-12.345» → «-12 345», «1,234567» → «1 234 567».
     *
     * @return подсказка или пустая строка, если ввод не похож на тысячи («0,005», «1234,567», «1,2345»)
     */
    private static String thousandsSuggestion(String number, int decimal) {
        String integer = number.substring(0, decimal).strip();
        String sign = "";
        if (integer.startsWith("-") || integer.startsWith("+") || integer.startsWith("−")) {
            sign = integer.startsWith("+") ? "" : "-";
            integer = integer.substring(1).strip();
        }
        String fraction = number.substring(decimal + 1).strip();
        if (!SHORT_INTEGER.matcher(integer).matches() || fraction.length() % 3 != 0 || !fraction.chars().allMatch(Character::isDigit)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(sign).append(integer);
        for (int i = 0; i < fraction.length(); i += 3) {
            sb.append(' ').append(fraction, i, i + 3);
        }
        return sb.toString();
    }

    /** @return день недели прежнего снимка: имя константы, номер 1-7 или русское название */
    private static Optional<DayOfWeek> legacyWeekday(String code) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (code.equalsIgnoreCase(day.name())) {
                return Optional.of(day);
            }
        }
        OptionalLong number = parseLong(code);
        if (number.isPresent() && number.getAsLong() >= 1 && number.getAsLong() <= 7) {
            return Optional.of(DayOfWeek.of((int) number.getAsLong()));
        }
        return Optional.ofNullable(RuText.parseWeekday(code));
    }

    /** @return код в верхнем регистре, если он один из допустимых, иначе исходное значение */
    private static String upperIfOneOf(String code, String original, String... allowed) {
        String upper = code.toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(upper)) {
                return candidate;
            }
        }
        return original;
    }

    /**
     * Вид поля восстанавливаемого окна по таблицам полей спецификации §6 (для {@link #acceptLegacy}).
     *
     * @return вид или {@code null}, если поле неизвестно
     */
    private static FieldKind legacyKind(WindowType type, String fieldId) {
        return switch (type) {
            case NEW_PLAN_WIZARD, PLAN_SETTINGS -> switch (fieldId) {
                case "name", "quickIncomeTitle", "quickExpenseTitle", "goalTitle" -> FieldKind.TEXT;
                case "currency" -> FieldKind.EDITABLE_CHOICE;
                case "startDate", "horizonUntil", "goalDate" -> FieldKind.DATE;
                case "startBalance", "cushion", "quickIncomeAmount", "quickExpenseAmount", "goalTarget" -> FieldKind.MONEY;
                case "horizonValue", "quickIncomeDay", "quickExpenseDay" -> FieldKind.SPINNER;
                case "note" -> FieldKind.MULTILINE;
                default -> null;
            };
            case RULE_EDITOR -> switch (fieldId) {
                case "title" -> FieldKind.TEXT;
                case "amount" -> FieldKind.MONEY;
                case "category" -> FieldKind.EDITABLE_CHOICE;
                case "dayOfMonth", "everyN" -> FieldKind.SPINNER;
                case "monthDay" -> FieldKind.MONTH_DAY;
                case "fromEnabled", "untilEnabled", "enabled" -> FieldKind.CHECK;
                case "from", "until" -> FieldKind.DATE;
                case "note" -> FieldKind.MULTILINE;
                default -> null;
            };
            case ONE_TIME_EDITOR -> switch (fieldId) {
                case "date" -> FieldKind.DATE;
                case "title" -> FieldKind.TEXT;
                case "amount" -> FieldKind.MONEY;
                case "category" -> FieldKind.EDITABLE_CHOICE;
                case "note" -> FieldKind.MULTILINE;
                default -> null;
            };
            case ADJUSTMENT_EDITOR -> switch (fieldId) {
                case "amount" -> FieldKind.MONEY;
                case "date" -> FieldKind.DATE;
                case "note" -> FieldKind.MULTILINE;
                default -> null;
            };
            case GOAL_CALCULATOR -> switch (fieldId) {
                case "target", "extraSaving" -> FieldKind.MONEY;
                case "byDateEnabled" -> FieldKind.CHECK;
                case "byDate" -> FieldKind.DATE;
                default -> null;
            };
            case CSV_EXPORT -> "bom".equals(fieldId) ? FieldKind.CHECK : null;
            case QUICK_EDIT_POPUP -> "amount".equals(fieldId) ? FieldKind.MONEY : null;
            // Значение TEXT_INPUT/CHOICE зависит от назначения (имя плана, сумма сверки, валюта): хранится как есть,
            // приводит его форма назначения.
            case TEXT_INPUT, CHOICE, ALERT -> null;
        };
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
