package ru.cashprediction.core.markdown;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Грамматика значений файла плана: разбор и канонический вывод повторов, типов, сдвигов,
 * логических значений, действий корректировок, горизонта, дат и сумм.
 *
 * <p>Главный принцип — терпимость к ручной правке в Блокноте: при разборе регистр букв,
 * лишние (в том числе неразрывные) пробелы и различие «ё/е» не важны. Вывод, наоборот, всегда
 * канонический, поэтому после первого сохранения файл приходит к единому виду.</p>
 *
 * <p>Грамматика повтора:</p>
 * <ul>
 *   <li>{@code ежемесячно N}; {@code каждые K месяц|месяца|месяцев|мес N};</li>
 *   <li>{@code еженедельно пн..вс} (и полные названия); {@code каждые K недел* <день>};</li>
 *   <li>{@code ежедневно}; {@code каждые K день|дня|дней};</li>
 *   <li>{@code ежегодно ММ-ДД} или {@code ежегодно ДД.ММ}.</li>
 * </ul>
 *
 * <p><b>Слова и сообщения (решение L13).</b> Русские слова грамматики берутся из нелокализуемого ресурса
 * {@link FormatWords}; регулярные выражения собираются в коде из этих слов через {@link Pattern#quote(String)}, поэтому
 * в ресурсе нет обратных косых. Методы разбора бросают {@link IllegalArgumentException} с понятным сообщением из
 * каталога текстов ({@code markdown_ru.properties}); слова формата попадают в сообщение подстановками, а не
 * вписываются в текст ключа. Читатель плана показывает сообщение пользователю в списке диагностики.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class RuFormats {

    /** Буква, которую нормализация заменяет ({@code ё}); объявлена до шаблонов, которые нормализуют слова. */
    private static final int YO = FormatWords.get("common.char.yo").codePointAt(0);
    /** Замена буквы {@link #YO} ({@code е}). */
    private static final int YE = FormatWords.get("common.char.ye").codePointAt(0);

    /** Необязательное указание числа месяца после дня: «5-го», «5 числа». */
    private static final String DAY_SUFFIX = "(?:-?" + word("plan.recurrence.daySuffix.ordinal") + ")?(?: "
            + word("plan.recurrence.daySuffix.word") + ")?";
    /** Начало «каждые/каждый/каждую». */
    private static final String EVERY = word("plan.recurrence.every.stem") + "\\S*";
    /** Необязательный предлог перед днём недели: «по пт», «в сб». */
    private static final String ON_WEEKDAY = "(?:" + word("plan.recurrence.weekdayPreposition.1") + " |"
            + word("plan.recurrence.weekdayPreposition.2") + " )?";

    private static final Pattern DAILY = Pattern.compile(
            word("plan.recurrence.daily") + "|" + word("plan.recurrence.daily.alias"));
    private static final Pattern MONTHLY = Pattern.compile(word("plan.recurrence.monthly") + " (\\d{1,4})" + DAY_SUFFIX);
    private static final Pattern EVERY_MONTHS = Pattern.compile(EVERY + " (?:(\\d{1,4}) ?)?(?:"
            + word("plan.unit.month.stem") + "\\S*|" + word("plan.unit.month.abbr") + "\\.?) (\\d{1,4})" + DAY_SUFFIX);
    private static final Pattern WEEKLY = Pattern.compile(word("plan.recurrence.weekly") + " " + ON_WEEKDAY + "(\\S+)");
    private static final Pattern EVERY_WEEKS = Pattern.compile(EVERY + " (?:(\\d{1,4}) ?)?(?:"
            + word("plan.unit.week.stem") + "\\S*|" + word("plan.unit.week.abbr") + "\\.?) " + ON_WEEKDAY + "(\\S+)");
    private static final Pattern EVERY_DAYS = Pattern.compile(EVERY + " (?:(\\d{1,4}) ?)?(?:"
            + word("plan.unit.day.one") + "|" + word("plan.unit.day.few") + "|" + word("plan.unit.day.many") + "|"
            + word("plan.unit.day.abbr") + "\\.?)");
    private static final Pattern YEARLY_ISO = Pattern.compile(word("plan.recurrence.yearly") + " (\\d{1,2})-(\\d{1,2})");
    private static final Pattern YEARLY_RU = Pattern.compile(
            word("plan.recurrence.yearly") + " (\\d{1,2})\\.(\\d{1,2})\\.?");

    private static final Pattern HORIZON_MONTHS = Pattern.compile("(\\d{1,4}) ?(?:" + word("plan.unit.month.one") + "|"
            + word("plan.unit.month.few") + "|" + word("plan.unit.month.many") + "|" + word("plan.unit.month.abbr")
            + "\\.?)");
    private static final Pattern HORIZON_YEARS = Pattern.compile("(\\d{1,4}) ?(?:" + word("plan.unit.year.one") + "|"
            + word("plan.unit.year.few") + "|" + word("plan.unit.year.many") + ")");
    private static final Pattern HORIZON_UNTIL = Pattern.compile(word("plan.horizon.until") + " (.+)");

    private static final Pattern DATE_ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DATE_RU = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");

    private RuFormats() {
    }

    /**
     * Действие корректировки без параметров: слово из колонки «Действие».
     *
     * <p>Отдельное перечисление нужно потому, что сама {@link Adjustment.Action} несёт сумму и дату,
     * а при разборе строки сначала распознаётся слово, и только затем проверяется, заполнены ли
     * нужные этому действию колонки.</p>
     */
    public enum ActionType {
        /** «пропустить»: событие не произойдёт. */
        SKIP(false, false),
        /** «изменить»: другая сумма, прежняя дата. */
        CHANGE_AMOUNT(true, false),
        /** «перенести»: другая дата, прежняя сумма. */
        MOVE_DATE(false, true),
        /** «заменить»: другая сумма и другая дата. */
        REPLACE(true, true);

        private final boolean requiresAmount;
        private final boolean requiresDate;

        ActionType(boolean requiresAmount, boolean requiresDate) {
            this.requiresAmount = requiresAmount;
            this.requiresDate = requiresDate;
        }

        /** @return слово для файла плана из грамматики формата ({@link FormatWords}) */
        public String label() {
            return switch (this) {
                case SKIP -> FormatWords.get("plan.action.skip");
                case CHANGE_AMOUNT -> FormatWords.get("plan.action.change");
                case MOVE_DATE -> FormatWords.get("plan.action.move");
                case REPLACE -> FormatWords.get("plan.action.replace");
            };
        }

        /** @return начало однокоренных слов, по которому чтение узнаёт действие («пропуск», «перенос») */
        private String stem() {
            return switch (this) {
                case SKIP -> FormatWords.get("plan.action.skip.stem");
                case CHANGE_AMOUNT -> FormatWords.get("plan.action.change.stem");
                case MOVE_DATE -> FormatWords.get("plan.action.move.stem");
                case REPLACE -> FormatWords.get("plan.action.replace.stem");
            };
        }

        /** @return нужна ли действию колонка «Новая сумма» */
        public boolean requiresAmount() {
            return requiresAmount;
        }

        /** @return нужна ли действию колонка «Новая дата» */
        public boolean requiresDate() {
            return requiresDate;
        }
    }

    // ------------------------------------------------------------------ общее

    /**
     * Нормализует текст для сравнения: нижний регистр, «ё» → «е», любые пробельные символы
     * (включая U+00A0 и U+202F) схлопываются в один обычный пробел, края обрезаются.
     *
     * @param text исходный текст, {@code null} считается пустым
     * @return нормализованный текст
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && !sb.isEmpty()) {
                sb.append(' ');
            }
            pendingSpace = false;
            int lower = Character.toLowerCase(cp);
            sb.appendCodePoint(lower == YO ? YE : lower);
        }
        return sb.toString();
    }

    /**
     * Проверяет, означает ли ячейка «значение не задано».
     *
     * @param text текст ячейки
     * @return {@code true} для пустой ячейки и {@code -}
     */
    public static boolean isEmptyValue(String text) {
        String t = normalize(text);
        return t.isEmpty() || t.equals("-");
    }

    // ------------------------------------------------------------------ повтор

    /**
     * Разбирает правило повтора.
     *
     * @param text например «ежемесячно 5», «Каждые 2 Недели пт», «ежегодно 15.03»
     * @return правило повтора
     * @throws IllegalArgumentException если текст не соответствует грамматике или число вне диапазона
     */
    public static Recurrence parseRecurrence(String text) {
        String t = normalize(text);
        if (t.isEmpty()) {
            throw new IllegalArgumentException(Texts.get("markdown.value.recurrenceMissing", recurrenceExamples()));
        }
        try {
            Matcher m;
            if (DAILY.matcher(t).matches()) {
                return new Recurrence.EveryNDays(1);
            }
            if ((m = MONTHLY.matcher(t)).matches()) {
                return new Recurrence.Monthly(Integer.parseInt(m.group(1)), 1);
            }
            if ((m = EVERY_MONTHS.matcher(t)).matches()) {
                return new Recurrence.Monthly(Integer.parseInt(m.group(2)), countOrOne(m.group(1)));
            }
            if ((m = WEEKLY.matcher(t)).matches()) {
                return new Recurrence.Weekly(weekday(m.group(1)), 1);
            }
            if ((m = EVERY_WEEKS.matcher(t)).matches()) {
                return new Recurrence.Weekly(weekday(m.group(2)), countOrOne(m.group(1)));
            }
            if ((m = EVERY_DAYS.matcher(t)).matches()) {
                return new Recurrence.EveryNDays(countOrOne(m.group(1)));
            }
            if ((m = YEARLY_ISO.matcher(t)).matches()) {
                return new Recurrence.Yearly(monthDay(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), text));
            }
            if ((m = YEARLY_RU.matcher(t)).matches()) {
                return new Recurrence.Yearly(monthDay(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), text));
            }
        } catch (IllegalArgumentException e) {
            // Сообщения конструкторов («День месяца должен быть от 1 до 31») дополняем исходным текстом.
            throw new IllegalArgumentException(Texts.get("markdown.value.recurrenceInvalid", text.strip(), e.getMessage()), e);
        }
        throw new IllegalArgumentException(
                Texts.get("markdown.value.recurrenceUnparsed", text.strip(), recurrenceExamples()));
    }

    /**
     * Канонический текст повтора для файла.
     *
     * @param recurrence правило повтора
     * @return например «каждые 2 месяца 10»
     */
    public static String formatRecurrence(Recurrence recurrence) {
        return recurrence.toRussian();
    }

    /**
     * Подсказка с примерами, добавляемая к сообщению о нераспознанном повторе.
     *
     * <p>Примеры — канонические тексты настоящих правил, поэтому они всегда совпадают с грамматикой формата.</p>
     */
    private static String recurrenceExamples() {
        return Texts.get("markdown.value.recurrenceExamples",
                new Recurrence.Monthly(5, 1).toRussian(),
                new Recurrence.Monthly(10, 2).toRussian(),
                new Recurrence.Weekly(DayOfWeek.SATURDAY, 1).toRussian(),
                new Recurrence.Weekly(DayOfWeek.FRIDAY, 2).toRussian(),
                new Recurrence.EveryNDays(1).toRussian(),
                new Recurrence.EveryNDays(3).toRussian(),
                new Recurrence.Yearly(MonthDay.of(3, 15)).toRussian());
    }

    private static int countOrOne(String group) {
        return group == null ? 1 : Integer.parseInt(group);
    }

    private static DayOfWeek weekday(String token) {
        DayOfWeek day = RuText.parseWeekday(token);
        if (day == null) {
            throw new IllegalArgumentException(Texts.get("markdown.value.unknownWeekday", token,
                    FormatWords.weekdayShort(DayOfWeek.MONDAY), FormatWords.weekdayShort(DayOfWeek.TUESDAY),
                    FormatWords.weekdayShort(DayOfWeek.WEDNESDAY), FormatWords.weekdayShort(DayOfWeek.THURSDAY),
                    FormatWords.weekdayShort(DayOfWeek.FRIDAY), FormatWords.weekdayShort(DayOfWeek.SATURDAY),
                    FormatWords.weekdayShort(DayOfWeek.SUNDAY)));
        }
        return day;
    }

    private static MonthDay monthDay(int month, int day, String original) {
        try {
            return MonthDay.of(month, day);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(Texts.get("markdown.value.badMonthDay", original.strip()), e);
        }
    }

    // ------------------------------------------------------------------ тип, сдвиг, да/нет

    /**
     * Разбирает тип операции.
     *
     * @param text «доход», «расход», «+», «-», «income», «expense»
     * @return тип операции
     * @throws IllegalArgumentException если слово не распознано
     */
    public static Kind parseKind(String text) {
        String t = normalize(text).replace('−', '-');
        if (t.equals(normalize(Kind.INCOME.label())) || t.equals("+") || t.equals("income")) {
            return Kind.INCOME;
        }
        if (t.equals(normalize(Kind.EXPENSE.label())) || t.equals("-") || t.equals("expense")) {
            return Kind.EXPENSE;
        }
        throw new IllegalArgumentException(Texts.get("markdown.value.unknownKind", safe(text),
                Kind.INCOME.label(), Kind.EXPENSE.label()));
    }

    /**
     * Канонический текст типа операции.
     *
     * @param kind тип
     * @return «доход» или «расход»
     */
    public static String formatKind(Kind kind) {
        return kind.label();
    }

    /**
     * Разбирает правило сдвига с выходных. Пустое значение означает «не сдвигать».
     *
     * @param text «нет», «раньше», «позже» (также «не сдвигать», «на пятницу», «на понедельник» и английские
     *             none / previous / next)
     * @return правило сдвига
     * @throws IllegalArgumentException если слово не распознано
     */
    public static WeekendPolicy parseWeekendPolicy(String text) {
        if (isEmptyValue(text)) {
            return WeekendPolicy.NONE;
        }
        String t = normalize(text);
        if (t.equals(normalize(WeekendPolicy.NONE.label())) || t.equals("none")
                || t.equals(normalize(FormatWords.get("plan.weekend.none.alias")))) {
            return WeekendPolicy.NONE;
        }
        if (t.equals(normalize(WeekendPolicy.PREVIOUS_BUSINESS_DAY.label())) || t.equals("previous")
                || t.equals(normalize(FormatWords.get("plan.weekend.previous.alias")))) {
            return WeekendPolicy.PREVIOUS_BUSINESS_DAY;
        }
        if (t.equals(normalize(WeekendPolicy.NEXT_BUSINESS_DAY.label())) || t.equals("next")
                || t.equals(normalize(FormatWords.get("plan.weekend.next.alias")))) {
            return WeekendPolicy.NEXT_BUSINESS_DAY;
        }
        throw new IllegalArgumentException(Texts.get("markdown.value.unknownWeekend", MarkdownFormat.COL_WEEKEND,
                safe(text), WeekendPolicy.NONE.label(), WeekendPolicy.PREVIOUS_BUSINESS_DAY.label(),
                WeekendPolicy.NEXT_BUSINESS_DAY.label()));
    }

    /**
     * Канонический текст правила сдвига.
     *
     * @param policy правило
     * @return «нет», «раньше» или «позже»
     */
    public static String formatWeekendPolicy(WeekendPolicy policy) {
        return policy.label();
    }

    /**
     * Разбирает логическое значение.
     *
     * @param text «да», «нет», «yes», «no», «true», «false», «1», «0»
     * @return значение
     * @throws IllegalArgumentException если слово не распознано
     */
    public static boolean parseBoolean(String text) {
        String t = normalize(text);
        if (t.equals(normalize(FormatWords.get("plan.bool.yes"))) || t.equals("yes") || t.equals("true") || t.equals("1")) {
            return true;
        }
        if (t.equals(normalize(FormatWords.get("plan.bool.no"))) || t.equals("no") || t.equals("false") || t.equals("0")) {
            return false;
        }
        throw new IllegalArgumentException(Texts.get("markdown.value.expectedYesNo",
                FormatWords.get("plan.bool.yes"), FormatWords.get("plan.bool.no"), safe(text)));
    }

    /**
     * Канонический текст логического значения.
     *
     * @param value значение
     * @return «да» или «нет»
     */
    public static String formatBoolean(boolean value) {
        return value ? FormatWords.get("plan.bool.yes") : FormatWords.get("plan.bool.no");
    }

    // ------------------------------------------------------------------ действие корректировки

    /**
     * Разбирает слово действия корректировки. Кроме канонических слов понимает однокоренные:
     * «изменение», «перенос», «пропуск», «замена».
     *
     * @param text слово из колонки «Действие»
     * @return вид действия
     * @throws IllegalArgumentException если слово не распознано
     */
    public static ActionType parseActionType(String text) {
        String t = normalize(text);
        if (t.startsWith(normalize(ActionType.SKIP.stem())) || t.equals("skip")) {
            return ActionType.SKIP;
        }
        if (t.startsWith(normalize(ActionType.CHANGE_AMOUNT.stem())) || t.equals("change")) {
            return ActionType.CHANGE_AMOUNT;
        }
        if (t.startsWith(normalize(ActionType.MOVE_DATE.stem())) || t.equals("move")) {
            return ActionType.MOVE_DATE;
        }
        if (t.startsWith(normalize(ActionType.REPLACE.stem())) || t.equals("replace")) {
            return ActionType.REPLACE;
        }
        throw new IllegalArgumentException(Texts.get("markdown.value.unknownAction", safe(text),
                ActionType.CHANGE_AMOUNT.label(), ActionType.MOVE_DATE.label(), ActionType.SKIP.label(),
                ActionType.REPLACE.label()));
    }

    /**
     * Строит действие корректировки из слова и значений колонок.
     *
     * @param type   вид действия
     * @param amount новая сумма или {@code null}
     * @param date   новая дата или {@code null}
     * @return действие; значения, не нужные этому виду, игнорируются
     * @throws IllegalArgumentException если не заполнено значение, обязательное для этого вида
     */
    public static Adjustment.Action buildAction(ActionType type, Money amount, LocalDate date) {
        Objects.requireNonNull(type, "type");
        if (type.requiresAmount() && amount == null) {
            throw new IllegalArgumentException(
                    Texts.get("markdown.value.actionNeedsColumn", type.label(), MarkdownFormat.COL_NEW_AMOUNT));
        }
        if (type.requiresDate() && date == null) {
            throw new IllegalArgumentException(
                    Texts.get("markdown.value.actionNeedsColumn", type.label(), MarkdownFormat.COL_NEW_DATE));
        }
        return switch (type) {
            case SKIP -> new Adjustment.Skip();
            case CHANGE_AMOUNT -> new Adjustment.ChangeAmount(amount);
            case MOVE_DATE -> new Adjustment.MoveDate(date);
            case REPLACE -> new Adjustment.Replace(amount, date);
        };
    }

    /**
     * Вид действия для готовой корректировки.
     *
     * @param action действие
     * @return вид действия
     */
    public static ActionType actionTypeOf(Adjustment.Action action) {
        return switch (action) {
            case Adjustment.Skip s -> ActionType.SKIP;
            case Adjustment.ChangeAmount c -> ActionType.CHANGE_AMOUNT;
            case Adjustment.MoveDate m -> ActionType.MOVE_DATE;
            case Adjustment.Replace r -> ActionType.REPLACE;
        };
    }

    /**
     * Каноническое слово действия.
     *
     * @param action действие
     * @return «пропустить», «изменить», «перенести» или «заменить»
     */
    public static String formatAction(Adjustment.Action action) {
        return action.label();
    }

    // ------------------------------------------------------------------ горизонт

    /**
     * Разбирает горизонт прогноза.
     *
     * @param text «12 месяцев», «3 мес», «2 года», «до 2027-12-31»
     * @return горизонт
     * @throws IllegalArgumentException если текст не распознан или число вне допустимого диапазона
     */
    public static Horizon parseHorizon(String text) {
        String t = normalize(text);
        try {
            Matcher m;
            if ((m = HORIZON_MONTHS.matcher(t)).matches()) {
                return new Horizon.Months(Integer.parseInt(m.group(1)));
            }
            if ((m = HORIZON_YEARS.matcher(t)).matches()) {
                return new Horizon.Years(Integer.parseInt(m.group(1)));
            }
            if ((m = HORIZON_UNTIL.matcher(t)).matches()) {
                return new Horizon.Until(parseDate(m.group(1)));
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(Texts.get("markdown.value.horizonInvalid", safe(text), e.getMessage()), e);
        }
        // Примеры — канонические тексты настоящих горизонтов: они совпадают с тем, что пишет программа.
        throw new IllegalArgumentException(Texts.get("markdown.value.horizonUnparsed", safe(text),
                new Horizon.Months(12).label(), new Horizon.Years(2).label(),
                new Horizon.Until(LocalDate.of(2027, 12, 31)).label()));
    }

    /**
     * Канонический текст горизонта.
     *
     * @param horizon горизонт
     * @return «12 месяцев», «2 года» или «до 2027-12-31»
     */
    public static String formatHorizon(Horizon horizon) {
        return horizon.label();
    }

    // ------------------------------------------------------------------ даты и суммы

    /**
     * Разбирает дату {@code ГГГГ-ММ-ДД} или {@code ДД.ММ.ГГГГ}. Проверка строгая: 31.02 — ошибка,
     * а не «последний день февраля», потому что опечатку в файле лучше показать, чем молча исправить.
     *
     * @param text текст даты; пробелы внутри игнорируются
     * @return дата
     * @throws IllegalArgumentException если текст пуст или не является существующей датой
     */
    public static LocalDate parseDate(String text) {
        if (isEmptyValue(text)) {
            throw new IllegalArgumentException(Texts.get("date.error.empty"));
        }
        String t = normalize(text).replace(" ", "");
        Matcher iso = DATE_ISO.matcher(t);
        Matcher ru = DATE_RU.matcher(t);
        try {
            if (iso.matches()) {
                return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            }
            if (ru.matches()) {
                return LocalDate.of(Integer.parseInt(ru.group(3)), Integer.parseInt(ru.group(2)), Integer.parseInt(ru.group(1)));
            }
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(Texts.get("markdown.value.dateNonexistent", text.strip()), e);
        }
        // Остальное отдаём общему разборщику ради единообразного сообщения об ошибке.
        return DateFormats.parse(t);
    }

    /**
     * Разбирает необязательную дату.
     *
     * @param text текст ячейки
     * @return дата или {@code null}, если значение пустое ({@link #isEmptyValue(String)})
     * @throws IllegalArgumentException если значение задано, но не является датой
     */
    public static LocalDate parseOptionalDate(String text) {
        return isEmptyValue(text) ? null : parseDate(text);
    }

    /**
     * Канонический текст даты для файла.
     *
     * @param date дата или {@code null}
     * @return {@code 2026-09-01} или пустая строка
     */
    public static String formatDate(LocalDate date) {
        return DateFormats.iso(date);
    }

    /**
     * Разбирает сумму (см. {@link Money#parse(String)}): пробелы любых видов, запятая или точка.
     *
     * @param text текст суммы
     * @return сумма со знаком, если он был указан
     * @throws IllegalArgumentException если текст пуст или не является числом
     */
    public static Money parseMoney(String text) {
        if (isEmptyValue(text)) {
            throw new IllegalArgumentException(Texts.get("money.error.empty"));
        }
        return Money.parse(text);
    }

    /**
     * Проверяет, указан ли у суммы явный знак. В таблицах операций знак не нужен (его задаёт «Тип»),
     * поэтому читатель отбрасывает его с предупреждением.
     *
     * @param text текст суммы
     * @return {@code true}, если текст начинается с «+», «-» или «−»
     */
    public static boolean hasExplicitSign(String text) {
        String t = normalize(text);
        return t.startsWith("+") || t.startsWith("-") || t.startsWith("−");
    }

    /**
     * Канонический текст суммы для файла.
     *
     * @param money сумма
     * @return {@code 80 000,00}
     */
    public static String formatMoney(Money money) {
        return money.format();
    }

    private static String safe(String text) {
        return text == null ? "" : text.strip();
    }

    /**
     * Слово формата для регулярного выражения: нормализованное (как текст, с которым сравнивается) и экранированное.
     *
     * @param name имя слова в {@link FormatWords}
     * @return фрагмент шаблона, совпадающий ровно с этим словом
     */
    private static String word(String name) {
        return Pattern.quote(normalize(FormatWords.get(name)));
    }
}
