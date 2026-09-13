package ru.cashprediction.core.recurrence;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.util.RuText;

/**
 * Генератор дат регулярных операций.
 *
 * <p>Главные понятия:</p>
 * <ul>
 *   <li><b>Окно правила</b> — пересечение горизонта плана и интервала «С/По» правила:
 *       {@code lo = max(planStart, from ?? planStart)}, {@code hi = min(planEnd, until ?? planEnd)}.
 *       Если {@code lo > hi}, правило в горизонте не действует и дат нет.</li>
 *   <li><b>Опорная дата (фаза)</b> — {@code from ?? planStart}. От неё отсчитываются «каждые N месяцев»,
 *       «каждые N недель» и «каждые N дней». Фаза НЕ зависит от даты начала плана, если у правила
 *       задано «С»: после «Актуализировать на сегодня» чётные месяцы не превращаются в нечётные.</li>
 *   <li><b>Номинальная дата</b> — дата по правилу, до сдвига с выходных. К ней привязываются корректировки.</li>
 * </ul>
 *
 * <p>Правила построения дат:</p>
 * <ul>
 *   <li>{@code Monthly(day, k)}: месяцы {@code YearMonth(опора) + i*k}, день {@code min(day, длина месяца)},
 *       поэтому «31» означает «последний день», а «29» в феврале невисокосного года даёт 28-е;</li>
 *   <li>{@code Weekly(wd, k)}: первый {@code wd} на или после опорной даты, далее шаг {@code 7*k} дней;</li>
 *   <li>{@code EveryNDays(n)}: опорная дата, далее шаг {@code n} дней;</li>
 *   <li>{@code Yearly(md)}: {@code md.atYear(y)} для каждого года окна; 29.02 в невисокосный год — 28.02.</li>
 * </ul>
 *
 * <p>Защита от «бесконечных» правил: больше {@link #MAX_DATES_PER_RULE} дат на одно правило считается ошибкой
 * плана (такое возможно только при горизонте «до даты» в далёком будущем).</p>
 *
 * <p>Класс без состояния, все методы статические и потокобезопасные.</p>
 */
public final class OccurrenceGenerator {

    /** Максимальное число дат одного правила; при превышении генерация прерывается исключением. */
    public static final int MAX_DATES_PER_RULE = 200_000;

    /**
     * Наибольший сдвиг с выходных в днях: воскресенье → пятница или суббота → понедельник.
     * На столько дней за края горизонта движок прогноза ищет события, которые сдвиг возвращает внутрь.
     */
    public static final int MAX_WEEKEND_SHIFT_DAYS = 2;

    /** Насколько далеко вперёд ищет даты предпросмотр «Ближайшие даты» редактора правила. */
    public static final int UPCOMING_YEARS_LIMIT = 20;

    private OccurrenceGenerator() {
    }

    /**
     * Номинальные даты правила в пределах горизонта плана, по возрастанию.
     *
     * <p>Признак {@code enabled} правила здесь не учитывается: решать, участвует ли правило в прогнозе,
     * должен вызывающий код (движок прогноза пропускает выключенные правила).</p>
     *
     * @param rule      правило
     * @param planStart дата начала плана
     * @param planEnd   последний день горизонта (включительно)
     * @return неизменяемый список номинальных дат; пустой, если окно правила не пересекается с горизонтом
     * @throws IllegalStateException если правило даёт больше {@link #MAX_DATES_PER_RULE} дат
     */
    public static List<LocalDate> nominalDates(RecurringRule rule, LocalDate planStart, LocalDate planEnd) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(planStart, "planStart");
        Objects.requireNonNull(planEnd, "planEnd");
        LocalDate lo = windowStart(rule, planStart);
        LocalDate hi = windowEnd(rule, planEnd);
        if (lo.isAfter(hi)) {
            return List.of();
        }
        return List.copyOf(generate(rule, anchor(rule, planStart), lo, hi, MAX_DATES_PER_RULE, true));
    }

    /**
     * Номинальные даты правила в произвольном интервале {@code [from, to]}, без прижатия к горизонту плана.
     *
     * <p>Нужен движку прогноза для событий, номинальная дата которых лежит за краем горизонта, а фактическая
     * (после сдвига с выходных или переноса корректировкой) — внутри него. Интервал ограничивается только
     * полями «С/По» самого правила. Фаза повтора та же, что в {@link #nominalDates}: {@code from ?? planStart}.
     * Исключение — правила без «С», которым опорная дата не нужна ({@link Recurrence#needsAnchor()} ложно:
     * «ежемесячно», «еженедельно», «ежегодно»): они действуют всегда, поэтому их даты бывают и раньше начала плана.
     * Правилу «каждые N …» без «С» фазу до начала плана знать неоткуда, и дат раньше {@code planStart} у него нет.</p>
     *
     * @param rule      правило
     * @param planStart дата начала плана (опорная дата правил без «С»)
     * @param from      первый день интервала (включительно)
     * @param to        последний день интервала (включительно)
     * @return неизменяемый список номинальных дат по возрастанию; пустой, если интервал пуст
     * @throws IllegalStateException если правило даёт больше {@link #MAX_DATES_PER_RULE} дат
     */
    public static List<LocalDate> nominalDatesBetween(RecurringRule rule, LocalDate planStart, LocalDate from, LocalDate to) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(planStart, "planStart");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        LocalDate lo = rule.from() != null && rule.from().isAfter(from) ? rule.from() : from;
        LocalDate hi = rule.until() != null && rule.until().isBefore(to) ? rule.until() : to;
        if (lo.isAfter(hi)) {
            return List.of();
        }
        // Для правил без фазы опорой служит само начало интервала: иначе generate отрезал бы даты до planStart.
        LocalDate phase = rule.from() != null || rule.recurrence().needsAnchor() ? anchor(rule, planStart) : lo;
        return List.copyOf(generate(rule, phase, lo, hi, MAX_DATES_PER_RULE, true));
    }

    /**
     * Проверяет, создаёт ли правило событие в указанную номинальную дату (без учёта горизонта плана).
     *
     * @param rule      правило
     * @param planStart дата начала плана
     * @param date      проверяемая дата
     * @return {@code true}, если {@code date} — номинальная дата правила в смысле {@link #nominalDatesBetween}
     */
    public static boolean isNominalDate(RecurringRule rule, LocalDate planStart, LocalDate date) {
        return nominalDatesBetween(rule, planStart, date, date).contains(date);
    }

    /**
     * События правила в пределах горизонта: номинальные даты вместе с фактическими (после сдвига с выходных).
     *
     * @param rule      правило
     * @param planStart дата начала плана
     * @param planEnd   последний день горизонта (включительно)
     * @return неизменяемый список событий по возрастанию номинальной даты
     * @throws IllegalStateException если правило даёт больше {@link #MAX_DATES_PER_RULE} дат
     */
    public static List<Occurrence> occurrences(RecurringRule rule, LocalDate planStart, LocalDate planEnd) {
        List<LocalDate> dates = nominalDates(rule, planStart, planEnd);
        List<Occurrence> result = new ArrayList<>(dates.size());
        for (LocalDate nominal : dates) {
            result.add(new Occurrence(nominal, shiftWithinHorizon(rule, nominal, planStart, planEnd)));
        }
        return List.copyOf(result);
    }

    /**
     * Применяет сдвиг с выходных к номинальной дате с учётом границ горизонта.
     *
     * <p>Если сдвиг выводит дату за горизонт (например, суббота в последний день плана сдвигается на понедельник
     * после его конца), остаётся номинальная дата: иначе событие молча пропало бы из прогноза.</p>
     *
     * @param rule      правило (источник политики выходных)
     * @param nominal   номинальная дата
     * @param planStart дата начала плана
     * @param planEnd   последний день горизонта
     * @return фактическая дата события
     */
    public static LocalDate shiftWithinHorizon(RecurringRule rule, LocalDate nominal, LocalDate planStart, LocalDate planEnd) {
        LocalDate shifted = rule.weekendPolicy().apply(nominal);
        if (shifted.isBefore(planStart) || shifted.isAfter(planEnd)) {
            return nominal;
        }
        return shifted;
    }

    /**
     * Ближайшие события правила для предпросмотра в редакторе («Ближайшие даты: …»).
     *
     * <p>Горизонт плана здесь не ограничивает поиск: пользователь хочет видеть, как поведёт себя правило,
     * даже если план короткий. Учитываются «С/По» правила и опорная дата ({@code from ?? planStart}).
     * Поиск ограничен {@link #UPCOMING_YEARS_LIMIT} годами после {@code fromInclusive}, чтобы правило
     * с датой начала в далёком будущем не заставляло перебирать века. Сдвиг с выходных применяется без
     * ограничений горизонта.</p>
     *
     * @param rule          правило
     * @param planStart     дата начала плана (опорная дата, если у правила нет «С»)
     * @param fromInclusive с какой даты искать события (обычно сегодня или начало плана)
     * @param count         сколько событий нужно; не больше {@link #MAX_DATES_PER_RULE}
     * @return неизменяемый список не более чем из {@code count} событий
     * @throws IllegalArgumentException если {@code count} отрицательный
     */
    public static List<Occurrence> upcoming(RecurringRule rule, LocalDate planStart, LocalDate fromInclusive, int count) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(planStart, "planStart");
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        if (count < 0) {
            // Число дат задаёт код, а не пользователь: сообщение для разработчика.
            throw new IllegalArgumentException("Date count must not be negative");
        }
        if (count == 0) {
            return List.of();
        }
        LocalDate lo = rule.from() != null && rule.from().isAfter(fromInclusive) ? rule.from() : fromInclusive;
        LocalDate hi = fromInclusive.plusYears(UPCOMING_YEARS_LIMIT);
        if (rule.until() != null && rule.until().isBefore(hi)) {
            hi = rule.until();
        }
        if (lo.isAfter(hi)) {
            return List.of();
        }
        List<LocalDate> dates = generate(rule, anchor(rule, planStart), lo, hi, Math.min(count, MAX_DATES_PER_RULE), false);
        List<Occurrence> result = new ArrayList<>(dates.size());
        for (LocalDate nominal : dates) {
            result.add(new Occurrence(nominal, rule.weekendPolicy().apply(nominal)));
        }
        return List.copyOf(result);
    }

    /**
     * Опорная дата правила, от которой отсчитывается фаза повтора.
     *
     * @param rule      правило
     * @param planStart дата начала плана
     * @return {@code rule.from()}, если задана, иначе {@code planStart}
     */
    public static LocalDate anchor(RecurringRule rule, LocalDate planStart) {
        return rule.from() != null ? rule.from() : planStart;
    }

    /**
     * Первый день окна правила внутри горизонта.
     *
     * @param rule      правило
     * @param planStart дата начала плана
     * @return {@code max(planStart, from ?? planStart)}
     */
    public static LocalDate windowStart(RecurringRule rule, LocalDate planStart) {
        return rule.from() != null && rule.from().isAfter(planStart) ? rule.from() : planStart;
    }

    /**
     * Последний день окна правила внутри горизонта.
     *
     * @param rule    правило
     * @param planEnd последний день горизонта
     * @return {@code min(planEnd, until ?? planEnd)}
     */
    public static LocalDate windowEnd(RecurringRule rule, LocalDate planEnd) {
        return rule.until() != null && rule.until().isBefore(planEnd) ? rule.until() : planEnd;
    }

    /**
     * Общий перебор дат по правилу повтора в интервале {@code [lo, hi]}.
     *
     * @param rule          правило (нужно для текста ошибки)
     * @param anchor        опорная дата фазы
     * @param lo            первый допустимый день
     * @param hi            последний допустимый день
     * @param limit         сколько дат собрать не больше
     * @param throwOnLimit  {@code true} — превышение лимита является ошибкой, {@code false} — просто остановка
     * @return изменяемый список дат по возрастанию
     */
    private static List<LocalDate> generate(RecurringRule rule, LocalDate anchor, LocalDate lo, LocalDate hi,
                                            int limit, boolean throwOnLimit) {
        // До опорной даты правило ещё «не началось»: даже если окно шире, раньше опоры дат нет.
        LocalDate start = lo.isBefore(anchor) ? anchor : lo;
        List<LocalDate> out = new ArrayList<>();
        if (start.isAfter(hi)) {
            return out;
        }
        Collector collector = new Collector(out, limit, throwOnLimit, rule);
        switch (rule.recurrence()) {
            case Recurrence.Monthly(int day, int everyMonths) -> monthly(day, everyMonths, anchor, start, hi, collector);
            case Recurrence.Weekly weekly -> {
                LocalDate first = anchor.with(TemporalAdjusters.nextOrSame(weekly.weekday()));
                stepDays(first, 7L * weekly.everyWeeks(), start, hi, collector);
            }
            case Recurrence.EveryNDays(int days) -> stepDays(anchor, days, start, hi, collector);
            case Recurrence.Yearly yearly -> {
                for (int year = start.getYear(); year <= hi.getYear(); year++) {
                    // MonthDay.atYear сам превращает 29.02 в 28.02 невисокосного года.
                    LocalDate date = yearly.monthDay().atYear(year);
                    if (!date.isBefore(start) && !date.isAfter(hi) && !collector.add(date)) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Перебор «раз в k месяцев» с прижатием дня к концу короткого месяца. */
    private static void monthly(int day, int everyMonths, LocalDate anchor, LocalDate start, LocalDate hi, Collector collector) {
        YearMonth anchorMonth = YearMonth.from(anchor);
        // Сразу перескакиваем к месяцу окна: при опоре в 2000 году и плане в 2026 не нужно перебирать 26 лет.
        long monthsToStart = ChronoUnit.MONTHS.between(anchorMonth, YearMonth.from(start));
        for (long i = Math.max(0, monthsToStart / everyMonths); ; i++) {
            YearMonth month = anchorMonth.plusMonths(i * everyMonths);
            LocalDate date = month.atDay(Math.min(day, month.lengthOfMonth()));
            if (date.isAfter(hi)) {
                return;
            }
            if (date.isBefore(start)) {
                // День в первом месяце окна раньше «С»: например, «5-го» при «С 10.09» начинается с октября.
                continue;
            }
            if (!collector.add(date)) {
                return;
            }
        }
    }

    /** Перебор с постоянным шагом в днях от первой даты {@code first}. */
    private static void stepDays(LocalDate first, long step, LocalDate start, LocalDate hi, Collector collector) {
        LocalDate date = first;
        if (date.isBefore(start)) {
            // Округление вверх до ближайшего шага, не раньше начала окна: фаза сохраняется.
            long gap = ChronoUnit.DAYS.between(first, start);
            date = first.plusDays(((gap + step - 1) / step) * step);
        }
        while (!date.isAfter(hi)) {
            if (!collector.add(date)) {
                return;
            }
            date = date.plusDays(step);
        }
    }

    /**
     * Накопитель дат с проверкой лимита. Живёт только внутри одного вызова генерации,
     * поэтому изменяемость здесь безопасна.
     */
    private static final class Collector {
        private final List<LocalDate> out;
        private final int limit;
        private final boolean throwOnLimit;
        private final RecurringRule rule;

        Collector(List<LocalDate> out, int limit, boolean throwOnLimit, RecurringRule rule) {
            this.out = out;
            this.limit = limit;
            this.throwOnLimit = throwOnLimit;
            this.rule = rule;
        }

        /**
         * Добавляет дату.
         *
         * @return {@code false}, если лимит набран и перебор нужно остановить
         */
        boolean add(LocalDate date) {
            if (out.size() >= limit) {
                if (throwOnLimit) {
                    // Тип исключения прежний, но текст видит пользователь (в web — как ошибку прогноза): он из каталога.
                    // Предел — из лимита сборщика (с исключением он всегда MAX_DATES_PER_RULE), с разделением разрядов.
                    throw new IllegalStateException(Texts.get("occurrence.error.tooManyDates", rule.id(), rule.title(),
                            RuText.groupDigits(limit)));
                }
                return false;
            }
            out.add(date);
            return out.size() < limit || throwOnLimit;
        }
    }
}
