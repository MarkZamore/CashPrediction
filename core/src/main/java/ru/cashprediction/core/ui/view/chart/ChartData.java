package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Данные графика, не зависящие от размера области рисования (спецификация v2, §5.3): диапазон дат, точки баланса,
 * шкалы, линии подушки, цели и сегодня, маркеры дней, столбцы месяцев и легенда. {@link PlanChartModel} считает их
 * один раз на ревизию и раскладывает в пиксели для любого размера.
 *
 * <p><b>Диапазон:</b> от начала прогноза до {@code view.periodEnd(plan, forecast.anchor())}, не дальше конца
 * прогноза. <b>Пустая сцена:</b> прогноз не рассчитан - {@code chart.forecastFailed} цветом {@code expense};
 * меньше двух точек - {@code chart.noData} цветом {@code text.muted}.</p>
 *
 * <p><b>Маркеры</b> (флаг {@code chartMarkers}): один на день диапазона, в котором есть события, прошедшие фильтры
 * вида ({@link ViewState#accepts}), непропущенные и не START; вид - только доходы, только расходы или оба
 * (строка «что-если» - доход). Больше {@value PlanChartModel#MAX_MARKERS} дней - маркеров нет, в легенде уведомление.
 * <b>Столбцы</b> (флаг {@code chartBars}): месяцы прогноза, пересекающие диапазон, с итогами
 * {@code ForecastSummary.byMonth}. <b>Легенда:</b> «Баланс», «Ноль» всегда; «Подушка» при подушке &gt; 0; «Цель» при
 * цели; «Сегодня», если сегодня в диапазоне; «Доход», «Расход», «Доход и расход», если нарисован хотя бы один маркер;
 * уведомление вместо них при превышении; «Итог месяца», если есть столбцы.</p>
 *
 * @param from           первый день диапазона
 * @param to             последний день диапазона
 * @param emptyText      текст пустой сцены или пустая строка
 * @param emptyColor     цвет текста пустой сцены
 * @param points         точки баланса ({@link ChartSeries#sample}, до 1500)
 * @param ticks          деления оси Y или {@code null} у пустой сцены
 * @param months         линии сетки оси X
 * @param cushion        подушка в копейках или {@code null}, если подушки нет
 * @param goal           цель или {@code null}
 * @param today          сегодня, если оно в диапазоне, иначе {@code null}
 * @param balanceByDay   баланс конца дня для маркеров и наведения
 * @param markers        маркеры дней по порядку дат
 * @param tooManyMarkers превышен ли предел маркеров
 * @param bars           столбцы месяцев по порядку (пусто при выключенном флаге)
 * @param legend         элементы легенды по порядку
 */
record ChartData(LocalDate from, LocalDate to, String emptyText, ColorToken emptyColor, List<DailyPoint> points,
                 ChartScale.Ticks ticks, List<ChartScale.MonthTick> months, Long cushion, Goal goal, LocalDate today,
                 Forecast balanceByDay, List<DayMarker> markers, boolean tooManyMarkers, List<MonthBar> bars,
                 List<LegendItem> legend) {

    /**
     * Маркер дня.
     *
     * @param date         день
     * @param balanceMinor баланс на конец дня в копейках
     * @param fill         {@code income}, {@code expense} или {@code marker.mixed}
     * @param tooltip      подсказка маркера
     */
    record DayMarker(LocalDate date, long balanceMinor, ColorToken fill, String tooltip) {
        /** Проверяет поля. */
        DayMarker {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(fill, "fill");
            Objects.requireNonNull(tooltip, "tooltip");
        }
    }

    /**
     * Столбец итога месяца.
     *
     * @param month    месяц
     * @param first    первый день месяца внутри диапазона
     * @param last     последний день месяца внутри диапазона
     * @param netMinor итог месяца в копейках (доходы минус расходы)
     * @param tooltip  подсказка столбца
     */
    record MonthBar(YearMonth month, LocalDate first, LocalDate last, long netMinor, String tooltip) {
        /** Проверяет поля. */
        MonthBar {
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(last, "last");
            Objects.requireNonNull(tooltip, "tooltip");
        }
    }

    /** Проверяет поля и копирует списки. */
    ChartData {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        emptyText = Objects.requireNonNullElse(emptyText, "");
        emptyColor = emptyColor == null ? ColorToken.TEXT_MUTED : emptyColor;
        points = List.copyOf(points);
        months = List.copyOf(months);
        markers = List.copyOf(markers);
        bars = List.copyOf(bars);
        legend = List.copyOf(legend);
    }

    /** @return пустая ли сцена (есть текст вместо графика) */
    boolean isEmpty() {
        return !emptyText.isEmpty();
    }

    /** @return число дней диапазона включительно */
    int dayCount() {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    /**
     * Считает данные графика для состояния.
     *
     * @param state состояние приложения
     * @return данные
     */
    static ChartData of(AppState state) {
        DocumentView document = state.document();
        Plan plan = document.plan();
        ViewState view = state.view();
        Forecast forecast = document.forecast();
        if (forecast == null) {
            return empty(plan.startDate(), ChartTexts.forecastFailed(document.forecastError()), ColorToken.EXPENSE);
        }
        LocalDate from = forecast.startDate();
        LocalDate to = view.periodEnd(plan, forecast.anchor());
        if (to.isAfter(forecast.endDate())) {
            to = forecast.endDate();
        }
        if (to.isBefore(from)) {
            to = from;
        }
        List<DailyPoint> points = ChartSeries.sample(forecast, from, to, ChartSeries.DEFAULT_MAX_POINTS);
        if (points.size() < 2) {
            return empty(from, ChartTexts.noData(), ColorToken.TEXT_MUTED);
        }

        String currency = plan.currency();
        Long cushion = plan.cushion().isPositive() ? plan.cushion().minor() : null;
        Goal goal = plan.goal();
        long lo = 0;
        long hi = 0;
        for (DailyPoint point : points) {
            // Выборка сохраняет глобальные минимум и максимум, поэтому шкала покрывает все дни диапазона.
            lo = Math.min(lo, point.balance().minor());
            hi = Math.max(hi, point.balance().minor());
        }
        if (cushion != null) {
            hi = Math.max(hi, cushion);
        }
        if (goal != null) {
            lo = Math.min(lo, goal.target().minor());
            hi = Math.max(hi, goal.target().minor());
        }
        ChartScale.Ticks ticks = ChartScale.yTicks(lo, hi);
        LocalDate today = state.today().isBefore(from) || state.today().isAfter(to) ? null : state.today();

        List<DayMarker> markers = new ArrayList<>();
        boolean tooMany = false;
        if (view.chartMarkers()) {
            tooMany = collectMarkers(forecast, view, from, to, currency, markers);
        }
        List<MonthBar> bars = view.chartBars() ? bars(forecast, from, to, currency) : List.of();

        List<LegendItem> legend = new ArrayList<>();
        legend.add(new LegendItem("balance", ChartTexts.legendBalance(from, to), LegendItem.Swatch.LINE,
                ColorToken.ACCENT, ChartTexts.legendBalanceTip()));
        legend.add(new LegendItem("zero", ChartTexts.legendZero(), LegendItem.Swatch.LINE, ColorToken.LINE_ZERO,
                ChartTexts.legendZeroTip()));
        if (cushion != null) {
            legend.add(new LegendItem("cushion", ChartTexts.legendCushion(), LegendItem.Swatch.DASH,
                    ColorToken.LINE_CUSHION, ChartTexts.legendCushionTip(plan.cushion(), currency)));
        }
        if (goal != null) {
            legend.add(new LegendItem("goal", ChartTexts.legendGoal(), LegendItem.Swatch.DASH, ColorToken.LINE_GOAL,
                    ChartTexts.legendGoalTip(goal, currency, forecast.summary().goalReachDate().orElse(null))));
        }
        if (today != null) {
            legend.add(new LegendItem("today", ChartTexts.legendToday(), LegendItem.Swatch.DASH,
                    ColorToken.LINE_TODAY, ChartTexts.legendTodayTip(today)));
        }
        if (!markers.isEmpty()) {
            legend.add(new LegendItem("income", ChartTexts.legendIncome(), LegendItem.Swatch.DOT, ColorToken.INCOME,
                    ChartTexts.legendIncomeTip()));
            legend.add(new LegendItem("expense", ChartTexts.legendExpense(), LegendItem.Swatch.DOT,
                    ColorToken.EXPENSE, ChartTexts.legendExpenseTip()));
            legend.add(new LegendItem("mixed", ChartTexts.legendMixed(), LegendItem.Swatch.DOT,
                    ColorToken.MARKER_MIXED, ChartTexts.legendMixedTip()));
        }
        if (tooMany) {
            legend.add(new LegendItem("notice", ChartTexts.tooManyMarkers(), LegendItem.Swatch.NONE,
                    ColorToken.TEXT_MUTED, ""));
        }
        if (!bars.isEmpty()) {
            legend.add(new LegendItem("bars", ChartTexts.legendBars(), LegendItem.Swatch.BOX, ColorToken.INCOME,
                    ChartTexts.legendBarsTip()));
        }
        return new ChartData(from, to, "", ColorToken.TEXT_MUTED, points, ticks, ChartScale.monthTicks(from, to),
                cushion, goal, today, forecast, markers, tooMany, bars, legend);
    }

    /**
     * Собирает маркеры дней.
     *
     * @param forecast прогноз
     * @param view     вид (фильтры)
     * @param from     первый день диапазона
     * @param to       последний день диапазона
     * @param currency валюта
     * @param out      куда добавить маркеры
     * @return {@code true}, если дней с событиями больше предела (тогда {@code out} остаётся пустым)
     */
    private static boolean collectMarkers(Forecast forecast, ViewState view, LocalDate from, LocalDate to,
                                          String currency, List<DayMarker> out) {
        Map<LocalDate, List<ForecastRow>> byDay = new LinkedHashMap<>();
        for (ForecastRow row : forecast.rows()) {
            // Строки прогноза упорядочены по дате (ForecastEngine), поэтому после конца диапазона искать нечего.
            if (row.date().isAfter(to)) {
                break;
            }
            if (row.date().isBefore(from) || row.origin() == Origin.START || row.flags().skipped()
                    || !view.accepts(row)) {
                continue;
            }
            List<ForecastRow> dayRows = byDay.get(row.date());
            if (dayRows == null) {
                if (byDay.size() == PlanChartModel.MAX_MARKERS) {
                    // Дальше считать незачем: превышение уже доказано, а подсказки тысяч дней не нужны.
                    return true;
                }
                dayRows = new ArrayList<>(2);
                byDay.put(row.date(), dayRows);
            }
            dayRows.add(row);
        }
        for (Map.Entry<LocalDate, List<ForecastRow>> day : byDay.entrySet()) {
            boolean income = day.getValue().stream().anyMatch(ForecastRow::isIncome);
            boolean expense = day.getValue().stream().anyMatch(ForecastRow::isExpense);
            ColorToken fill = income && expense ? ColorToken.MARKER_MIXED : income ? ColorToken.INCOME : ColorToken.EXPENSE;
            var balance = forecast.balanceAt(day.getKey());
            out.add(new DayMarker(day.getKey(), balance.minor(), fill,
                    ChartTexts.markerTooltip(day.getKey(), day.getValue(), balance, currency)));
        }
        return false;
    }

    /**
     * Столбцы месяцев, пересекающих диапазон.
     *
     * @param forecast прогноз
     * @param from     первый день диапазона
     * @param to       последний день диапазона
     * @param currency валюта
     * @return столбцы по порядку месяцев
     */
    private static List<MonthBar> bars(Forecast forecast, LocalDate from, LocalDate to, String currency) {
        List<MonthBar> bars = new ArrayList<>();
        for (Map.Entry<YearMonth, MonthTotals> entry : forecast.summary().byMonth().entrySet()) {
            YearMonth month = entry.getKey();
            LocalDate first = month.atDay(1).isBefore(from) ? from : month.atDay(1);
            LocalDate last = month.atEndOfMonth().isAfter(to) ? to : month.atEndOfMonth();
            if (last.isBefore(first)) {
                continue;
            }
            bars.add(new MonthBar(month, first, last, entry.getValue().net().minor(),
                    ChartTexts.barTooltip(month, entry.getValue(), currency)));
        }
        return bars;
    }

    /**
     * Пустая сцена.
     *
     * @param from  первый день для преобразования координат
     * @param text  текст по центру
     * @param color цвет текста
     * @return данные без примитивов
     */
    private static ChartData empty(LocalDate from, String text, ColorToken color) {
        return new ChartData(from, from, text, color, List.of(), null, List.of(), null, null, null, null, List.of(),
                false, List.of(), List.of());
    }
}
