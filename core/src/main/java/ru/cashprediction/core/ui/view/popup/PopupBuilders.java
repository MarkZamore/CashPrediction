package ru.cashprediction.core.ui.view.popup;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.DesignTokens;
import ru.cashprediction.core.ui.view.chart.ChartPoint;
import ru.cashprediction.core.ui.view.summary.CardModel;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.util.RuText;

/**
 * Построители моделей всплывающих окон (спецификация v2, §5.1, §5.6.2, §5.6.5).
 *
 * <p><b>Карточка дня:</b> заголовок {@code UiFormats.dateWeekday}; баланс на конец дня ({@code expense} при минусе,
 * {@code warn} ниже подушки); до {@value #DAY_CARD_MAX_LINES} событий дня с учётом фильтров вида (без START), затем
 * {@code day.more}; пропущенные - «{@code day.skipped}  {title}» цвета {@code text.muted}; без событий -
 * {@code day.none}. <b>Спарклайн:</b> первая строка и пояснение - как у карточки сводки; диапазон по таблице §5.1
 * (now: anchor → min(anchor+3 мес, end); m1-m12: anchor → anchor+N; за горизонтом и остальные: anchor → end), до
 * {@value #SPARKLINE_MAX_POINTS} точек ({@code ChartSeries.sample}), нормированных в 0..1 (x по дням диапазона, y
 * сверху вниз от максимума к минимуму; ровная линия - посередине); линия нуля - если минимум &lt; 0 &lt; максимум;
 * точка на дате карточки; подписи минимума и максимума по точкам. <b>Календарь:</b> 6 недель с понедельника,
 * выбранный день, сегодня ({@code accent}), дни соседних месяцев ({@code text.past}), сб и вс ({@code expense}).</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class PopupBuilders {

    /** Сколько строк событий показывает карточка дня. */
    public static final int DAY_CARD_MAX_LINES = 8;

    /** Наибольшее число точек спарклайна: по точке на пиксель ширины графика. */
    public static final int SPARKLINE_MAX_POINTS = DesignTokens.SPARK_WIDTH;

    /** Число ячеек календаря: 6 недель. */
    public static final int CALENDAR_CELLS = 42;

    private PopupBuilders() {
    }

    /**
     * Карточка дня.
     *
     * @param state состояние (прогноз, фильтры вида, валюта)
     * @param date  день
     * @return модель карточки
     */
    public static DayCardModel dayCard(AppState state, LocalDate date) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(date, "date");
        String header = UiFormats.dateWeekday(date);
        Forecast forecast = state.document().forecast();
        if (forecast == null) {
            return new DayCardModel(date, header, "", ColorToken.TEXT_PRIMARY, List.of(), "",
                    UiText.get("day.none"));
        }
        String cur = forecast.plan().currency();
        Money cushion = forecast.plan().cushion();
        Money balance = forecast.balanceAt(date);
        ColorToken balanceColor = balance.isNegative() ? ColorToken.EXPENSE
                : cushion.isPositive() && balance.isLessThan(cushion) ? ColorToken.WARN : ColorToken.TEXT_PRIMARY;

        ViewState view = state.view();
        List<ForecastRow> rows = forecast.rows();
        List<DayCardModel.Line> lines = new ArrayList<>(DAY_CARD_MAX_LINES);
        int events = 0;
        for (int i = firstIndexOn(rows, date); i < rows.size() && rows.get(i).date().equals(date); i++) {
            ForecastRow row = rows.get(i);
            if (row.origin() == Origin.START || !view.accepts(row)) {
                continue;
            }
            events++;
            if (lines.size() < DAY_CARD_MAX_LINES) {
                lines.add(line(row));
            }
        }
        String more = events > DAY_CARD_MAX_LINES ? UiText.get("day.more", events - DAY_CARD_MAX_LINES) : "";
        String none = events == 0 ? UiText.get("day.none") : "";
        return new DayCardModel(date, header, UiText.get("day.balance", balance.format(cur)), balanceColor, lines,
                more, none);
    }

    /**
     * Всплывающее окно карточки сводки.
     *
     * @param state  состояние
     * @param cardId id карточки ({@code now}, {@code m1}, …, {@code goal})
     * @return модель спарклайна; если прогноз не рассчитан - без точек, с текстом {@code spark.noData}
     * @throws IllegalArgumentException если такой карточки нет в {@link SummaryBuilder#CARD_IDS}
     */
    public static SparklineModel sparkline(AppState state, String cardId) {
        Objects.requireNonNull(state, "state");
        if (!SummaryBuilder.CARD_IDS.contains(cardId)) {
            // Id карточки приходит из модели сводки, а не от пользователя: сообщение для разработчика.
            throw new IllegalArgumentException("Unknown summary card: " + cardId);
        }
        Forecast forecast = state.document().forecast();
        Optional<CardModel> found = SummaryBuilder.card(state, cardId);
        if (forecast == null || found.isEmpty()) {
            return new SparklineModel(cardId, "", "", List.of(), null, null, "", "", UiText.get("spark.noData"));
        }
        CardModel card = found.get();
        LocalDate from = forecast.anchor();
        LocalDate to = rangeEnd(forecast, card);
        List<DailyPoint> points = from.isAfter(to) ? List.of()
                : ChartSeries.sample(forecast, from, to, SPARKLINE_MAX_POINTS);
        if (points.size() < 2) {
            return new SparklineModel(cardId, card.popupHeader(), card.explanation(), List.of(), null, null, "", "",
                    UiText.get("spark.noData"));
        }
        LocalDate first = points.getFirst().date();
        long span = Math.max(1, ChronoUnit.DAYS.between(first, points.getLast().date()));
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (DailyPoint point : points) {
            min = Math.min(min, point.balance().minor());
            max = Math.max(max, point.balance().minor());
        }
        List<ChartPoint> normalized = new ArrayList<>(points.size());
        for (DailyPoint point : points) {
            normalized.add(new ChartPoint(ChronoUnit.DAYS.between(first, point.date()) / (double) span,
                    normalizedY(point.balance().minor(), min, max)));
        }
        Double zeroY = min < 0 && max > 0 ? normalizedY(0, min, max) : null;
        ChartPoint marker = null;
        LocalDate cardDate = card.date();
        if (cardDate != null && !cardDate.isBefore(first) && !cardDate.isAfter(points.getLast().date())) {
            marker = new ChartPoint(ChronoUnit.DAYS.between(first, cardDate) / (double) span,
                    normalizedY(forecast.balanceAt(cardDate).minor(), min, max));
        }
        String cur = forecast.plan().currency();
        return new SparklineModel(cardId, card.popupHeader(), card.explanation(), normalized, zeroY, marker,
                UiText.get("spark.min", new Money(min).format(cur)), UiText.get("spark.max", new Money(max).format(cur)),
                "");
    }

    /**
     * Календарь поля даты.
     *
     * @param month    месяц
     * @param selected выбранная дата или {@code null}
     * @param today    сегодня
     * @return модель календаря
     */
    public static CalendarModel calendar(YearMonth month, LocalDate selected, LocalDate today) {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(today, "today");
        List<String> weekdays = new ArrayList<>(7);
        for (DayOfWeek day : DayOfWeek.values()) {
            weekdays.add(RuText.weekdayShort(day));
        }
        LocalDate firstOfMonth = month.atDay(1);
        LocalDate cell = firstOfMonth.minusDays(firstOfMonth.getDayOfWeek().getValue() - 1L);
        List<CalendarModel.Day> days = new ArrayList<>(CALENDAR_CELLS);
        for (int i = 0; i < CALENDAR_CELLS; i++, cell = cell.plusDays(1)) {
            boolean inMonth = YearMonth.from(cell).equals(month);
            boolean isToday = cell.equals(today);
            boolean weekend = cell.getDayOfWeek() == DayOfWeek.SATURDAY || cell.getDayOfWeek() == DayOfWeek.SUNDAY;
            // Сегодня важнее выходного: пользователь ищет глазами именно его; дни соседних месяцев приглушены.
            ColorToken color = isToday ? ColorToken.ACCENT
                    : !inMonth ? ColorToken.TEXT_PAST
                    : weekend ? ColorToken.EXPENSE : ColorToken.TEXT_PRIMARY;
            days.add(new CalendarModel.Day(cell, Integer.toString(cell.getDayOfMonth()), inMonth, cell.equals(selected),
                    isToday, color));
        }
        return new CalendarModel(month, UiFormats.monthTitle(month), UiText.get("calendar.prev"),
                UiText.get("calendar.next"), weekdays, days);
    }

    /** @return строка события карточки дня */
    private static DayCardModel.Line line(ForecastRow row) {
        String title = row.origin() == Origin.WHAT_IF ? UiText.get("table.whatIfTitle") : row.title();
        if (row.flags().skipped()) {
            return new DayCardModel.Line(UiText.get("day.line", UiText.get("day.skipped"), title), ColorToken.TEXT_MUTED);
        }
        return new DayCardModel.Line(UiText.get("day.line", row.amount().formatSigned(), title),
                row.isIncome() ? ColorToken.INCOME : ColorToken.EXPENSE);
    }

    /** @return последний день диапазона спарклайна карточки (§5.1) */
    private static LocalDate rangeEnd(Forecast forecast, CardModel card) {
        LocalDate anchor = forecast.anchor();
        LocalDate end = forecast.endDate();
        return switch (card.id()) {
            case "now" -> {
                LocalDate threeMonths = anchor.plusMonths(3);
                yield threeMonths.isBefore(end) ? threeMonths : end;
            }
            // Дата есть только у карточки внутри горизонта; за горизонтом спарклайн идёт до конца прогноза.
            case "m1", "m3", "m6", "m12" -> card.date() != null ? card.date() : end;
            default -> end;
        };
    }

    /** @return y в 0..1 сверху вниз: максимум - 0, минимум - 1, ровная линия - 0,5 */
    private static double normalizedY(long value, long min, long max) {
        if (max == min) {
            return 0.5;
        }
        double y = (max - value) / (double) (max - min);
        return Math.max(0, Math.min(1, y));
    }

    /** @return индекс первой строки прогноза с датой не раньше {@code date} */
    private static int firstIndexOn(List<ForecastRow> rows, LocalDate date) {
        int lo = 0;
        int hi = rows.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (rows.get(mid).date().isBefore(date)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
