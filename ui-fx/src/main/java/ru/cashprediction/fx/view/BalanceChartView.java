package ru.cashprediction.fx.view;

import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.StringConverter;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.fx.ShellContext;
import ru.cashprediction.fx.menu.ForecastContextMenu;
import ru.cashprediction.fx.popup.DayCardPopupWindow;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * График баланса во времени: шаговая линия баланса, горизонтали нуля, подушки и цели, вертикаль «сегодня»,
 * по желанию — маркеры событий и столбцы итогов месяцев.
 *
 * <p>Ось X — номер дня от начала плана (у {@code LineChart} нет оси дат), подписи делений переводятся в даты
 * форматтером. Точек не больше 1500: баланс по дням прореживается {@link ChartSeries#sample} с сохранением
 * минимумов и максимумов, иначе JavaFX-график на десятках тысяч узлов тормозит. Символы точек выключены
 * ({@code setCreateSymbols(false)}), анимация тоже: при каждой правке плана график перестраивается.</p>
 *
 * <p>Порядок серий постоянный (баланс, ноль, подушка, цель, сегодня, события): по номеру серии
 * {@code styles.css} задаёт её цвет, а пустые серии просто не показываются в легенде. Наведение на область
 * построения показывает {@link DayCardPopupWindow} с балансом и событиями дня; правая кнопка —
 * контекстное меню графика.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class BalanceChartView extends StackPane {

    /** Больше маркеров событий не рисуется: тысячи кружков делают график нечитаемым и медленным. */
    private static final int MAX_MARKERS = 400;
    private static final DateTimeFormatter TICK = DateTimeFormatter.ofPattern("dd.MM.yy");

    private final ShellContext shell;
    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final BalanceLineChart chart;
    private final XYChart.Series<Number, Number> balance = series("Баланс");
    private final XYChart.Series<Number, Number> zero = series("Ноль");
    private final XYChart.Series<Number, Number> cushion = series("Подушка");
    private final XYChart.Series<Number, Number> goal = series("Цель");
    private final XYChart.Series<Number, Number> today = series("Сегодня");
    private final XYChart.Series<Number, Number> markers = series("События");
    private final Map<String, String> legendHints = new HashMap<>();
    private final DayCardPopupWindow dayCard = new DayCardPopupWindow();
    private ContextMenu contextMenu;
    private Forecast forecast;
    private LocalDate rangeStart = LocalDate.now();
    private long rangeDays = 1;
    private boolean stale = true;

    /**
     * Создаёт график.
     *
     * @param shell оболочка приложения
     */
    public BalanceChartView(ShellContext shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
        xAxis.setAutoRanging(false);
        xAxis.setMinorTickVisible(false);
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            /**
             * Подпись деления оси X: номер дня от начала периода превращается в дату.
             *
             * @param day номер дня
             * @return дата ДД.ММ.ГГ
             */
            @Override
            public String toString(Number day) {
                return TICK.format(rangeStart.plusDays(Math.round(day.doubleValue())));
            }

            /**
             * Обратное преобразование подписи оси не используется: ось только показывает деления.
             *
             * @param text подпись
             * @return всегда 0
             */
            @Override
            public Number fromString(String text) {
                return 0;
            }
        });
        NumberFormat money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"));
        yAxis.setForceZeroInRange(true);
        yAxis.setTickLabelFormatter(new StringConverter<>() {
            /**
             * Подпись деления оси Y: сумма целыми единицами с разделителем тысяч.
             *
             * @param value сумма
             * @return подпись
             */
            @Override
            public String toString(Number value) {
                return money.format(Math.round(value.doubleValue()));
            }

            /**
             * Обратное преобразование подписи оси не используется: ось только показывает деления.
             *
             * @param text подпись
             * @return всегда 0
             */
            @Override
            public Number fromString(String text) {
                return 0;
            }
        });
        chart = new BalanceLineChart(xAxis, yAxis);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setTitle("Баланс на конец дня");
        chart.getStyleClass().add("balance-chart");
        chart.getData().addAll(List.of(balance, zero, cushion, goal, today, markers));
        getChildren().add(chart);

        chart.setOnMouseMoved(this::hover);
        chart.setOnMouseClicked(this::hover);
        chart.setOnMouseExited(e -> dayCard.hide());
        // JavaFX: ContextMenuEvent → Swing: MouseAdapter.isPopupTrigger() → Web: contextmenu + preventDefault
        chart.setOnContextMenuRequested(this::showContextMenu);

        // Скрытый график не перестраивается при каждой правке; при показе догоняет изменения.
        visibleProperty().addListener((o, was, is) -> {
            if (is && stale) {
                refresh();
            }
            if (!is) {
                dayCard.hide();
            }
        });
    }

    /**
     * Узел графика для сохранения в PNG.
     *
     * @return график
     */
    public Node chartNode() {
        return chart;
    }

    /** Перестраивает график по документу (если график скрыт — при следующем показе). */
    public void refresh() {
        if (!isVisible()) {
            stale = true;
            return;
        }
        stale = false;
        PlanDocument document = shell.document();
        Plan plan = document.plan();
        ViewState view = document.viewState();
        try {
            forecast = document.forecast();
        } catch (IllegalStateException e) {
            forecast = null;
            chart.setTitle("Прогноз не рассчитан: " + e.getMessage());
            for (XYChart.Series<Number, Number> s : List.of(balance, zero, cushion, goal, today, markers)) {
                s.getData().clear();
            }
            chart.setBars(List.of());
            return;
        }
        rangeStart = forecast.startDate();
        LocalDate end = view.periodEnd(plan, forecast.anchor());
        if (end.isBefore(rangeStart)) {
            end = rangeStart;
        }
        rangeDays = Math.max(1, ChronoUnit.DAYS.between(rangeStart, end));
        chart.setTitle("Баланс на конец дня, " + DateFormats.ru(rangeStart) + " — " + DateFormats.ru(end)
                + ", " + plan.currency());
        xAxis.setLowerBound(0);
        xAxis.setUpperBound(rangeDays);
        xAxis.setTickUnit(tickUnit(rangeDays));

        List<DailyPoint> points = ChartSeries.sample(forecast, rangeStart, end, ChartSeries.DEFAULT_MAX_POINTS);
        List<XYChart.Data<Number, Number>> line = new ArrayList<>(points.size() * 2);
        double lo = 0;
        double hi = 0;
        Double previous = null;
        for (DailyPoint p : points) {
            double x = dayIndex(p.date());
            double y = rub(p.balance());
            // Шаговая линия: баланс меняется скачком в день события, а не плавно между точками.
            if (previous != null) {
                line.add(new XYChart.Data<>(x, previous));
            }
            line.add(new XYChart.Data<>(x, y));
            previous = y;
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        balance.getData().setAll(line);
        legendHints.put("Баланс", "Баланс на конец каждого дня (не больше 1500 точек, минимумы и максимумы сохранены)");

        zero.getData().setAll(List.of(new XYChart.Data<>(0, 0), new XYChart.Data<>(rangeDays, 0)));
        legendHints.put("Ноль", "Нулевой баланс: ниже этой линии денег не хватает");

        if (plan.cushion().isPositive()) {
            double c = rub(plan.cushion());
            cushion.getData().setAll(List.of(new XYChart.Data<>(0, c), new XYChart.Data<>(rangeDays, c)));
            hi = Math.max(hi, c);
            legendHints.put("Подушка", "Подушка безопасности: " + plan.cushion().format(plan.currency()));
        } else {
            cushion.getData().clear();
        }

        if (plan.goal() != null) {
            double g = rub(plan.goal().target());
            goal.getData().setAll(List.of(new XYChart.Data<>(0, g), new XYChart.Data<>(rangeDays, g)));
            hi = Math.max(hi, g);
            legendHints.put("Цель", "Цель «" + plan.goal().title() + "»: " + plan.goal().target().format(plan.currency())
                    + forecast.summary().goalReachDate().map(d -> ", достигается " + DateFormats.ru(d)).orElse(", не достигается"));
        } else {
            goal.getData().clear();
        }

        LocalDate now = shell.today();
        if (!now.isBefore(rangeStart) && !now.isAfter(end)) {
            double x = dayIndex(now);
            today.getData().setAll(List.of(new XYChart.Data<>(x, lo), new XYChart.Data<>(x, hi)));
            legendHints.put("Сегодня", "Сегодня, " + DateFormats.ru(now) + ": левее — прошедшие дни");
        } else {
            today.getData().clear();
        }

        markers.getData().setAll(view.chartMarkers() ? markerData(document, end) : List.of());
        legendHints.put("События", "События периода: зелёные — доходы, красные — расходы");

        chart.setBars(view.chartBars() ? bars(end) : List.of());
        // Легенда создаётся при раскладке графика: подсказки ставятся после неё.
        Platform.runLater(this::decorateLegend);
    }

    private List<XYChart.Data<Number, Number>> markerData(PlanDocument document, LocalDate end) {
        List<XYChart.Data<Number, Number>> data = new ArrayList<>();
        for (ForecastRow row : document.visibleRows()) {
            if (data.size() >= MAX_MARKERS) {
                break;
            }
            if (row.origin() == Origin.START || row.flags().skipped() || row.date().isAfter(end)) {
                continue;
            }
            XYChart.Data<Number, Number> point = new XYChart.Data<>(dayIndex(row.date()), rub(row.balanceAfter()));
            // Свой узел точки: при выключенных символах LineChart рисует только заданные узлы.
            Circle dot = new Circle(3.5, row.isIncome() ? Color.web("#1b7f3b") : Color.web("#b3261e"));
            dot.setMouseTransparent(true);
            point.setNode(dot);
            data.add(point);
        }
        return data;
    }

    private List<Bar> bars(LocalDate end) {
        List<Bar> result = new ArrayList<>();
        for (Map.Entry<YearMonth, MonthTotals> entry : forecast.summary().byMonth().entrySet()) {
            YearMonth month = entry.getKey();
            LocalDate from = month.atDay(1).isBefore(rangeStart) ? rangeStart : month.atDay(1);
            LocalDate to = month.atEndOfMonth().isAfter(end) ? end : month.atEndOfMonth();
            if (to.isBefore(from)) {
                continue;
            }
            result.add(new Bar(dayIndex(from), dayIndex(to) + 1, rub(entry.getValue().net())));
        }
        return result;
    }

    private void decorateLegend() {
        Map<String, XYChart.Series<Number, Number>> byName = new HashMap<>();
        for (XYChart.Series<Number, Number> s : chart.getData()) {
            byName.put(s.getName(), s);
        }
        for (Node node : chart.lookupAll(".chart-legend-item")) {
            if (node instanceof Label label) {
                XYChart.Series<Number, Number> s = byName.get(label.getText());
                boolean empty = s == null || s.getData().isEmpty();
                label.setVisible(!empty);
                label.setManaged(!empty);
                String hint = legendHints.get(label.getText());
                if (hint != null) {
                    // JavaFX: Tooltip → Swing: setToolTipText у элемента легенды → Web: title у <g class="legend-item">
                    label.setTooltip(new Tooltip(hint));
                }
            }
        }
    }

    // ------------------------------------------------------------------ наведение и контекстное меню

    private void hover(MouseEvent event) {
        LocalDate date = dateAt(event.getSceneX(), event.getSceneY());
        if (date == null || forecast == null) {
            dayCard.hide();
            return;
        }
        Plan plan = shell.document().plan();
        List<ForecastRow> events = forecast.rowsBetween(date, date);
        dayCard.showDay(getScene().getWindow(), event.getScreenX(), event.getScreenY(), date, forecast.balanceAt(date),
                events, plan.currency(), plan.cushion());
    }

    private LocalDate dateAt(double sceneX, double sceneY) {
        Node background = chart.lookup(".chart-plot-background");
        if (background == null) {
            return null;
        }
        Point2D inPlot = background.sceneToLocal(sceneX, sceneY);
        if (inPlot == null || !background.contains(inPlot)) {
            return null;
        }
        Point2D onAxis = xAxis.sceneToLocal(sceneX, sceneY);
        double value = xAxis.getValueForDisplay(onAxis.getX()).doubleValue();
        long day = Math.clamp(Math.round(value), 0, rangeDays);
        return rangeStart.plusDays(day);
    }

    private void showContextMenu(ContextMenuEvent event) {
        dayCard.hide();
        LocalDate date = dateAt(event.getSceneX(), event.getSceneY());
        if (contextMenu != null) {
            contextMenu.hide();
        }
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: <ul class="context-menu">
        contextMenu = ForecastContextMenu.forChart(shell, date);
        contextMenu.show(chart, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    // ------------------------------------------------------------------ служебное

    private double dayIndex(LocalDate date) {
        return ChronoUnit.DAYS.between(rangeStart, date);
    }

    private static double rub(Money money) {
        return money.minor() / 100.0;
    }

    private static double tickUnit(long days) {
        if (days <= 31) {
            return 7;
        }
        if (days <= 100) {
            return 14;
        }
        if (days <= 400) {
            return 30;
        }
        if (days <= 800) {
            return 61;
        }
        if (days <= 2000) {
            return 182;
        }
        return 365;
    }

    private static XYChart.Series<Number, Number> series(String name) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        return s;
    }

    /**
     * Столбец итога месяца в единицах осей.
     *
     * @param fromDay первый день месяца (номер дня)
     * @param toDay   день после последнего дня месяца
     * @param net     итог месяца в рублях (со знаком)
     */
    record Bar(double fromDay, double toDay, double net) {
    }

    /**
     * Линейный график со слоем столбцов итогов месяцев под линиями.
     *
     * <p>{@code LineChart} не умеет рисовать столбцы, поэтому прямоугольники раскладываются вручную в
     * {@link #layoutPlotChildren()} по экранным координатам осей — так они остаются на месте при изменении
     * размера окна.</p>
     */
    static final class BalanceLineChart extends LineChart<Number, Number> {

        private final Group barLayer = new Group();
        private List<Bar> bars = List.of();

        BalanceLineChart(NumberAxis x, NumberAxis y) {
            super(x, y);
            barLayer.setManaged(false);
            barLayer.setMouseTransparent(true);
            getPlotChildren().addFirst(barLayer);
        }

        void setBars(List<Bar> value) {
            bars = List.copyOf(value);
            requestChartLayout();
        }

        /**
         * Раскладывает серии графика, затем рисует под линией баланса столбцы итогов месяцев
         * (зелёные — месяц в плюсе, красные — в минусе).
         */
        @Override
        protected void layoutPlotChildren() {
            super.layoutPlotChildren();
            barLayer.getChildren().clear();
            NumberAxis x = (NumberAxis) getXAxis();
            NumberAxis y = (NumberAxis) getYAxis();
            double base = y.getDisplayPosition(0);
            for (Bar bar : bars) {
                double x0 = x.getDisplayPosition(bar.fromDay());
                double x1 = x.getDisplayPosition(bar.toDay());
                double top = y.getDisplayPosition(bar.net());
                Rectangle rect = new Rectangle(Math.min(x0, x1) + 1, Math.min(base, top),
                        Math.max(1, Math.abs(x1 - x0) - 2), Math.abs(top - base));
                rect.setFill(bar.net() >= 0 ? Color.web("#1b7f3b", 0.22) : Color.web("#b3261e", 0.22));
                barLayer.getChildren().add(rect);
            }
        }
    }
}
