package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.FontToken;
import ru.cashprediction.core.ui.view.popup.DayCardModel;

/**
 * Модель графика одного снимка состояния (спецификация v2, §5.3): раскладывает {@link ChartData} в пиксели для
 * любого размера области рисования. Геометрия чистая и детерминированная: без AWT и JavaFX, одинаковые состояние и
 * размер дают равные сцены.
 *
 * <p><b>Раскладка.</b> Поля слева 80, справа 24, сверху 28 (легенда), снизу 32. Шкала Y занимает всю высоту области
 * построения; полоса столбцов рисуется первой поверх сетки внизу области (высота min(110, 22 % высоты области
 * рисования), ось посередине), а заливка и линии - поверх неё. День занимает {@code plotWidth / dayCount} пикселей:
 * ступень баланса дня начинается у левого края дня, а маркер, вертикаль «сегодня» и вертикаль наведения стоят в
 * середине дня (на горизонтальном участке ступени этого дня).</p>
 *
 * <p><b>Порядок примитивов:</b> горизонтальная и вертикальная сетка, подписи оси Y (справа налево от оси, x = 74) и
 * оси X (от линии месяца, базовая линия на 18 ниже области); ось полосы, столбцы и подпись «итог мес.»; заливка;
 * ступенчатая линия; ноль и «0» справа над линией; подушка и её подпись слева над линией; цель и её подпись; сегодня и
 * подпись сверху внутри области; маркеры. <b>Зоны подсказок:</b> сначала маркеры (квадрат 12×12 вокруг центра), затем
 * столбцы (ширина столбца на всю высоту полосы); клиент берёт первую зону, содержащую указатель.</p>
 *
 * <p>Данные считаются лениво при первом обращении и запоминаются: скрытый график не тратит время на каждую правку.
 * Класс потокобезопасен.</p>
 */
final class PlanChartModel implements ChartModel {

    /** Больше маркеров не рисуется (§5.3). */
    static final int MAX_MARKERS = 400;
    /** Поле слева (подписи оси Y). */
    static final double MARGIN_LEFT = 80;
    /** Поле справа. */
    static final double MARGIN_RIGHT = 24;
    /** Поле сверху (легенда). */
    static final double MARGIN_TOP = 28;
    /** Поле снизу (подписи оси X). */
    static final double MARGIN_BOTTOM = 32;
    /** Наибольшая высота полосы столбцов. */
    static final double BARS_BAND_MAX = 110;
    /** Доля высоты области рисования под полосу столбцов. */
    static final double BARS_BAND_SHARE = 0.22;
    /** Доля ширины месяца под столбец. */
    static final double BAR_WIDTH_SHARE = 0.7;
    /** Непрозрачность столбцов. */
    static final double BAR_OPACITY = 0.45;
    /** Непрозрачность заливки под линией баланса. */
    static final double FILL_OPACITY = 0.10;
    /** Толщина линии баланса. */
    static final double BALANCE_WIDTH = 2;
    /** Радиус маркера. */
    static final double MARKER_RADIUS = 3.5;
    /** Половина стороны зоны подсказки маркера. */
    static final double MARKER_HIT_HALF = 6;
    /** Смещение карточки дня от указателя по обеим осям. */
    static final double CARD_OFFSET = 16;
    /** Зазор подписи оси Y до области построения. */
    static final double Y_LABEL_GAP = 6;
    /** Сдвиг базовой линии подписи вниз, чтобы текст 11 px стоял по центру линии. */
    static final double LABEL_CENTER_SHIFT = 4;
    /** Базовая линия подписей оси X ниже области построения. */
    static final double X_LABEL_BASELINE = 18;
    /** Отступ подписи от линии или края. */
    static final double LABEL_INSET = 4;
    /** Базовая линия подписи внутри области от её верхнего края («сегодня», «итог мес.»). */
    static final double INNER_LABEL_BASELINE = 12;

    private static final Stroke GRID = Stroke.solid(ColorToken.GRID, 1);
    private static final Stroke BAND_AXIS = Stroke.solid(ColorToken.BORDER, 1);
    private static final Stroke BALANCE = Stroke.solid(ColorToken.ACCENT, BALANCE_WIDTH);
    private static final Stroke ZERO = Stroke.solid(ColorToken.LINE_ZERO, 1);
    private static final Stroke CUSHION = new Stroke(ColorToken.LINE_CUSHION, 1.5, List.of(6.0, 4.0), 1);
    private static final Stroke GOAL = new Stroke(ColorToken.LINE_GOAL, 1.5, List.of(8.0, 4.0), 1);
    private static final Stroke TODAY = new Stroke(ColorToken.LINE_TODAY, 1.5, List.of(4.0, 4.0), 1);

    private final AppState state;
    private final long revision;
    private final BiFunction<AppState, LocalDate, DayCardModel> dayCards;
    private volatile ChartData data;

    /**
     * Создаёт модель.
     *
     * @param state    состояние приложения
     * @param revision ревизия модели
     * @param dayCards построитель карточки дня
     */
    PlanChartModel(AppState state, long revision, BiFunction<AppState, LocalDate, DayCardModel> dayCards) {
        this.state = Objects.requireNonNull(state, "state");
        this.revision = revision;
        this.dayCards = Objects.requireNonNull(dayCards, "dayCards");
    }

    /**
     * Возвращает ревизию снимка, заданную при создании модели графика.
     * @return номер для сопоставления сцены с состоянием приложения
     */
    @Override
    public long revision() {
        return revision;
    }

    /**
     * Лениво получает данные графика и строит сцену для заданного размера в порядке рисования.
     * Размер области построения ограничивает снизу одним пикселем по каждой оси;
     * неконечные и неположительные размеры при расчёте геометрии считает нулевыми.
     * Зоны подсказок маркеров ставит перед зонами столбцов, чтобы маркеры имели приоритет.
     * При отсутствии данных возвращает сцену с текстом пустого состояния без примитивов.
     * @param width ширина области рисования в пикселях
     * @param height высота области рисования в пикселях
     * @return сцена с преобразованием координат, примитивами, легендой и зонами подсказок
     */
    @Override
    public ChartScene layout(double width, double height) {
        ChartData d = data();
        PlotTransform plot = transform(d, width, height);
        if (d.isEmpty()) {
            return new ChartScene(width, height, plot, List.of(), List.of(), List.of(), d.emptyText(), d.emptyColor());
        }
        double left = plot.plotX();
        double top = plot.plotY();
        double right = left + plot.plotWidth();
        double bottom = top + plot.plotHeight();
        double dayWidth = plot.plotWidth() / d.dayCount();
        List<ChartPrimitive> primitives = new ArrayList<>();

        // 0. Сетка и подписи осей.
        for (long value : d.ticks().values()) {
            double y = plot.yOf(value);
            primitives.add(new ChartPrimitive.Line(left, y, right, y, GRID));
        }
        for (ChartScale.MonthTick month : d.months()) {
            double x = plot.xOf(month.date());
            primitives.add(new ChartPrimitive.Line(x, top, x, bottom, GRID));
        }
        for (int i = 0; i < d.ticks().values().size(); i++) {
            primitives.add(new ChartPrimitive.Label(left - Y_LABEL_GAP, plot.yOf(d.ticks().values().get(i))
                    + LABEL_CENTER_SHIFT, d.ticks().labels().get(i), TextAnchor.END, ColorToken.TEXT_MUTED,
                    FontToken.SMALL));
        }
        for (ChartScale.MonthTick month : d.months()) {
            if (month.labeled()) {
                primitives.add(new ChartPrimitive.Label(plot.xOf(month.date()) + LABEL_INSET,
                        bottom + X_LABEL_BASELINE, month.label(), TextAnchor.START, ColorToken.TEXT_MUTED,
                        FontToken.SMALL));
            }
        }

        // 1. Столбцы итогов месяцев.
        List<HitRegion> barHits = new ArrayList<>();
        if (!d.bars().isEmpty()) {
            double bandHeight = bandHeight(height, plot.plotHeight());
            double bandTop = bottom - bandHeight;
            double axis = bandTop + bandHeight / 2;
            primitives.add(new ChartPrimitive.Line(left, axis, right, axis, BAND_AXIS));
            long maxAbs = 0;
            for (ChartData.MonthBar bar : d.bars()) {
                maxAbs = Math.max(maxAbs, Math.abs(bar.netMinor()));
            }
            for (ChartData.MonthBar bar : d.bars()) {
                double monthLeft = plot.xOf(bar.first());
                double monthWidth = plot.xOf(bar.last().plusDays(1)) - monthLeft;
                double barWidth = monthWidth * BAR_WIDTH_SHARE;
                double barX = monthLeft + (monthWidth - barWidth) / 2;
                if (maxAbs > 0 && bar.netMinor() != 0) {
                    double barHeight = bandHeight / 2 * ((double) Math.abs(bar.netMinor()) / maxAbs);
                    boolean positive = bar.netMinor() > 0;
                    primitives.add(new ChartPrimitive.Box(barX, positive ? axis - barHeight : axis, barWidth,
                            barHeight, positive ? ColorToken.INCOME : ColorToken.EXPENSE, BAR_OPACITY));
                }
                // Зона на всю высоту полосы: подсказку месяца с нулевым итогом тоже можно увидеть.
                barHits.add(new HitRegion("bar@" + bar.month(), HitRegion.Kind.BAR, barX, bandTop, barWidth,
                        bandHeight, bar.month().atDay(1), bar.tooltip()));
            }
            primitives.add(new ChartPrimitive.Label(left + LABEL_INSET, bandTop + INNER_LABEL_BASELINE,
                    ChartTexts.barsAxis(), TextAnchor.START, ColorToken.TEXT_MUTED, FontToken.SMALL));
        }

        // 2-3. Заливка и ступенчатая линия баланса.
        double zeroY = plot.yOf(0);
        List<ChartPoint> step = stepPoints(d.points(), plot, right);
        primitives.add(new ChartPrimitive.Area(step, zeroY, ColorToken.ACCENT, FILL_OPACITY));
        primitives.add(new ChartPrimitive.Polyline(step, BALANCE));

        // 4. Ноль.
        primitives.add(new ChartPrimitive.Line(left, zeroY, right, zeroY, ZERO));
        primitives.add(new ChartPrimitive.Label(right - LABEL_INSET, zeroY - LABEL_INSET, "0", TextAnchor.END,
                ColorToken.LINE_ZERO, FontToken.SMALL));

        // 5. Подушка.
        if (d.cushion() != null) {
            double y = plot.yOf(d.cushion());
            primitives.add(new ChartPrimitive.Line(left, y, right, y, CUSHION));
            primitives.add(new ChartPrimitive.Label(left + LABEL_INSET, y - LABEL_INSET,
                    ChartTexts.cushionLabel(state.document().plan().cushion()), TextAnchor.START,
                    ColorToken.LINE_CUSHION, FontToken.SMALL));
        }

        // 6. Цель.
        if (d.goal() != null) {
            double y = plot.yOf(d.goal().target().minor());
            primitives.add(new ChartPrimitive.Line(left, y, right, y, GOAL));
            primitives.add(new ChartPrimitive.Label(left + LABEL_INSET, y - LABEL_INSET, ChartTexts.goalLabel(d.goal()),
                    TextAnchor.START, ColorToken.LINE_GOAL, FontToken.SMALL));
        }

        // 7. Сегодня.
        if (d.today() != null) {
            double x = plot.xOf(d.today()) + dayWidth / 2;
            primitives.add(new ChartPrimitive.Line(x, top, x, bottom, TODAY));
            primitives.add(new ChartPrimitive.Label(x + LABEL_INSET, top + INNER_LABEL_BASELINE,
                    ChartTexts.todayLabel(), TextAnchor.START, ColorToken.LINE_TODAY, FontToken.SMALL));
        }

        // 8. Маркеры.
        List<HitRegion> hits = new ArrayList<>();
        for (ChartData.DayMarker marker : d.markers()) {
            double cx = plot.xOf(marker.date()) + dayWidth / 2;
            double cy = plot.yOf(marker.balanceMinor());
            primitives.add(new ChartPrimitive.Circle(cx, cy, MARKER_RADIUS, marker.fill(), ColorToken.BG_SURFACE, 1));
            hits.add(new HitRegion("marker@" + marker.date(), HitRegion.Kind.MARKER, cx - MARKER_HIT_HALF,
                    cy - MARKER_HIT_HALF, 2 * MARKER_HIT_HALF, 2 * MARKER_HIT_HALF, marker.date(), marker.tooltip()));
        }
        hits.addAll(barHits);
        return new ChartScene(width, height, plot, primitives, d.legend(), hits, "", ColorToken.TEXT_MUTED);
    }

    /**
     * Передаёт построителю карточки дня снимок состояния этой модели и выбранную дату.
     * Сам метод не ограничивает дату горизонтом графика.
     * @param date дата карточки, не {@code null}
     * @return модель карточки, полученная от переданного при создании построителя
     */
    @Override
    public DayCardModel dayCard(LocalDate date) {
        return dayCards.apply(state, Objects.requireNonNull(date, "date"));
    }

    /**
     * Для указателя внутри области построения выбирает день по горизонтальной координате.
     * Ставит вертикаль и точку баланса в середине дня, а карточку смещает от указателя
     * на {@value #CARD_OFFSET} пикселей по обеим осям. Граница области включена в проверку.
     * @param x горизонтальная координата указателя в пикселях
     * @param y вертикальная координата указателя в пикселях
     * @param width ширина области рисования в пикселях
     * @param height высота области рисования в пикселях
     * @return модель наведения или пусто, если данных нет либо указатель вне области построения
     */
    @Override
    public Optional<ChartHover> hover(double x, double y, double width, double height) {
        ChartData d = data();
        if (d.isEmpty()) {
            return Optional.empty();
        }
        PlotTransform plot = transform(d, width, height);
        if (!plot.contains(x, y)) {
            return Optional.empty();
        }
        LocalDate date = plot.dateAt(x);
        double lineX = plot.xOf(date) + plot.plotWidth() / d.dayCount() / 2;
        double dotY = plot.yOf(d.balanceByDay().balanceAt(date).minor());
        return Optional.of(new ChartHover(date, lineX, new ChartPoint(lineX, dotY), dayCard(date), x + CARD_OFFSET,
                y + CARD_OFFSET));
    }

    /**
     * Высота полосы столбцов.
     *
     * @param sceneHeight высота области рисования
     * @param plotHeight  высота области построения
     * @return min(110, 22 % высоты области рисования), но не больше области построения; NaN и бесконечная высота
     *         считаются нулевой, как в преобразовании координат
     */
    static double bandHeight(double sceneHeight, double plotHeight) {
        return Math.min(plotHeight, Math.min(BARS_BAND_MAX, BARS_BAND_SHARE * finite(sceneHeight)));
    }

    /** @return данные графика, посчитанные один раз */
    private ChartData data() {
        ChartData local = data;
        if (local == null) {
            synchronized (this) {
                local = data;
                if (local == null) {
                    local = ChartData.of(state);
                    data = local;
                }
            }
        }
        return local;
    }

    /**
     * Преобразование координат для размера.
     *
     * @param d      данные
     * @param width  ширина области рисования
     * @param height высота области рисования
     * @return область построения с полями 80/24/28/32 (не меньше 1×1)
     */
    private static PlotTransform transform(ChartData d, double width, double height) {
        double plotWidth = Math.max(1, finite(width) - MARGIN_LEFT - MARGIN_RIGHT);
        double plotHeight = Math.max(1, finite(height) - MARGIN_TOP - MARGIN_BOTTOM);
        long min = d.ticks() == null ? 0 : d.ticks().minMinor();
        long max = d.ticks() == null ? 0 : d.ticks().maxMinor();
        return new PlotTransform(MARGIN_LEFT, MARGIN_TOP, plotWidth, plotHeight, d.from(), d.dayCount(), min, max);
    }

    /**
     * Вершины ступенчатой линии: от точки дня горизонталь до дня следующей точки, затем вертикаль к её балансу;
     * последняя горизонталь доходит до правого края области. Точки без изменения баланса вершин не добавляют.
     *
     * @param points точки баланса
     * @param plot   преобразование
     * @param right  правый край области построения
     * @return вершины ломаной
     */
    private static List<ChartPoint> stepPoints(List<DailyPoint> points, PlotTransform plot, double right) {
        List<ChartPoint> vertices = new ArrayList<>(points.size() * 2 + 1);
        double previousY = 0;
        for (int i = 0; i < points.size(); i++) {
            DailyPoint point = points.get(i);
            double x = plot.xOf(point.date());
            double y = plot.yOf(point.balance().minor());
            if (i == 0) {
                vertices.add(new ChartPoint(x, y));
            } else if (y != previousY) {
                vertices.add(new ChartPoint(x, previousY));
                vertices.add(new ChartPoint(x, y));
            }
            previousY = y;
        }
        vertices.add(new ChartPoint(right, previousY));
        return vertices;
    }

    /** @return неотрицательное конечное значение размера (NaN и бесконечность - 0) */
    private static double finite(double value) {
        return Double.isFinite(value) && value > 0 ? value : 0;
    }
}
