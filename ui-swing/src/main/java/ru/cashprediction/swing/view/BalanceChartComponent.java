package ru.cashprediction.swing.view;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.swing.popup.DayCardPopup;

/**
 * График баланса во времени — собственный компонент на {@code Graphics2D} (в JavaFX — {@code LineChart} из
 * {@code BalanceChartView}, в Web — SVG).
 *
 * <p><b>Что рисуется.</b> Шаговая линия баланса по точкам {@code ChartSeries.sample} (не больше 1500 точек — та же
 * децимация с сохранением минимумов и максимумов, что у двух других клиентов), заливка под линией, горизонтали
 * «0», «подушка» и «цель», вертикаль «сегодня», круглые деления оси баланса ({@link ChartScale}), подписи месяцев,
 * по флажкам — маркеры событий (▲ доход, ▼ расход) и столбцы доходов/расходов по месяцам.</p>
 *
 * <p><b>Мышь.</b> Наведение на область графика через 300 мс показывает карточку дня ({@link DayCardPopup},
 * аналог {@code PopupWindow}), щелчок — сразу; уход курсора её скрывает. Над осями и легендой работает обычная
 * подсказка ({@link #getToolTipText(MouseEvent)}). Контекстное меню — по {@code isPopupTrigger()} в
 * {@code mousePressed} и {@code mouseReleased}.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class BalanceChartComponent extends JComponent {

    /** Реакции графика, которые выполняет главное окно. */
    public interface Handler {
        /**
         * Показать контекстное меню графика.
         *
         * @param date      дата под курсором (или начало периода)
         * @param component компонент, относительно которого заданы координаты
         * @param x         координата x
         * @param y         координата y
         */
        void showContextMenu(LocalDate date, Component component, int x, int y);
    }

    /**
     * Данные для отрисовки.
     *
     * @param forecast прогноз (нужен карточке дня)
     * @param points   точки линии баланса из {@code ChartSeries.sample}
     * @param from     начало оси дат
     * @param to       конец оси дат
     * @param rows     видимые события периода (маркеры)
     * @param cushion  подушка безопасности
     * @param goal     цель или {@code null}
     * @param today    сегодня
     * @param markers  рисовать маркеры событий
     * @param bars     рисовать столбцы по месяцам
     * @param currency валюта плана
     */
    public record ChartData(Forecast forecast, List<DailyPoint> points, LocalDate from, LocalDate to, List<ForecastRow> rows,
                            Money cushion, Goal goal, LocalDate today, boolean markers, boolean bars, String currency) {
        /** Копирует списки. */
        public ChartData {
            points = List.copyOf(points);
            rows = List.copyOf(rows);
        }
    }

    private static final int LEFT = 96;
    private static final int RIGHT = 24;
    private static final int TOP = 34;
    private static final int BOTTOM = 42;
    private static final DecimalFormat AXIS_FORMAT;

    static {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru-RU"));
        symbols.setGroupingSeparator(' ');
        AXIS_FORMAT = new DecimalFormat("#,##0", symbols);
    }

    private final Handler handler;
    private final DayCardPopup dayCard = new DayCardPopup();
    private final Timer hoverTimer;
    private ChartData data;
    private String emptyMessage = "Нет данных для графика";
    private Point hoverPoint;
    /** Диапазон оси баланса последней отрисовки (в копейках). */
    private double axisMin;
    private double axisMax;

    /**
     * Создаёт компонент графика.
     *
     * @param handler реакции главного окна
     */
    public BalanceChartComponent(Handler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
        setPreferredSize(new Dimension(900, 480));
        setBackground(Color.WHITE);
        setOpaque(true);
        setFocusable(true);
        hoverTimer = new Timer(300, e -> showDayCardAt(hoverPoint));
        hoverTimer.setRepeats(false);
        // JavaFX: Tooltip → Swing: getToolTipText(MouseEvent) + ToolTipManager → Web: title / <div class="tooltip">
        ToolTipManager.sharedInstance().registerComponent(this);
        // JavaFX: ContextMenuEvent → Swing: MouseAdapter.isPopupTrigger() в mousePressed и mouseReleased → Web: contextmenu + preventDefault
        MouseAdapter mouse = new MouseAdapter() {
            /** Нажатие кнопки мыши: на части платформ именно оно является запросом контекстного меню. */
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.isPopupTrigger()) {
                    popup(e);
                }
            }

            /** Отпускание кнопки мыши: на Windows запрос контекстного меню приходит здесь. */
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup(e);
                }
            }

            /** Щелчок левой кнопкой по области графика: показывается карточка ближайшего дня. */
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && plotArea().contains(e.getPoint())) {
                    hoverTimer.stop();
                    showDayCardAt(e.getPoint());
                }
            }

            /** Движение мыши: обновляет наведение (подсказку или карточку дня). */
            @Override
            public void mouseMoved(MouseEvent e) {
                if (plotArea().contains(e.getPoint()) && data != null) {
                    hoverPoint = e.getPoint();
                    if (dayCard.isShowing()) {
                        showDayCardAt(hoverPoint);
                    } else {
                        hoverTimer.restart();
                    }
                } else {
                    hoverTimer.stop();
                    dayCard.hide();
                }
            }

            /** Курсор покинул компонент: всплывающие элементы скрываются. */
            @Override
            public void mouseExited(MouseEvent e) {
                hoverTimer.stop();
                dayCard.hide();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    private void popup(MouseEvent e) {
        hoverTimer.stop();
        dayCard.hide();
        LocalDate date = data == null ? LocalDate.now() : dateAt(e.getX(), plotArea());
        handler.showContextMenu(date, this, e.getX(), e.getY());
    }

    /**
     * Задаёт данные графика и перерисовывает его.
     *
     * @param chartData данные или {@code null}, если прогноз не рассчитан
     * @param message   текст вместо графика, когда данных нет
     */
    public void setData(ChartData chartData, String message) {
        data = chartData;
        emptyMessage = message == null ? "Нет данных для графика" : message;
        dayCard.hide();
        repaint();
    }

    /** Скрывает карточку дня (например, при переключении на таблицу). */
    public void hidePopups() {
        hoverTimer.stop();
        dayCard.hide();
    }

    /**
     * Дата под координатой x текущей области построения.
     *
     * @param x координата в компоненте
     * @return дата внутри периода графика
     */
    public LocalDate dateAt(int x) {
        return data == null ? LocalDate.now() : dateAt(x, plotArea());
    }

    private LocalDate dateAt(int x, Rectangle plot) {
        long days = Math.max(1, ChronoUnit.DAYS.between(data.from(), data.to()));
        double ratio = Math.max(0, Math.min(1, (x - plot.x) / (double) Math.max(1, plot.width)));
        return data.from().plusDays(Math.round(ratio * days));
    }

    private void showDayCardAt(Point point) {
        if (point == null || data == null || !isShowing()) {
            return;
        }
        LocalDate date = dateAt(point.x, plotArea());
        Money balance = data.forecast().balanceAt(date);
        List<ForecastRow> events = data.forecast().rowsBetween(date, date);
        Point screen = getLocationOnScreen();
        dayCard.show(this, screen.x + point.x, screen.y + point.y, date, balance, events, data.currency());
    }

    private Rectangle plotArea() {
        return new Rectangle(LEFT, TOP, Math.max(10, getWidth() - LEFT - RIGHT), Math.max(10, getHeight() - TOP - BOTTOM));
    }

    // ------------------------------------------------------------------ подсказки

    /**
     * Подсказка под курсором: над областью графика — пояснение цветов линий, на самой области — сведения о точке.
     *
     * @param event событие мыши с координатами курсора
     * @return текст подсказки (HTML) или {@code null}, если данных нет
     */
    // JavaFX: Tooltip → Swing: getToolTipText(MouseEvent) + ToolTipManager → Web: <div class="tooltip">
    @Override
    public String getToolTipText(MouseEvent event) {
        if (data == null) {
            return null;
        }
        Point p = event.getPoint();
        Rectangle plot = plotArea();
        if (p.y < TOP) {
            return "<html>Синяя линия - баланс на конец дня; оранжевая - подушка безопасности; зелёная - цель; "
                    + "фиолетовая - сегодня.<br>Правая кнопка - действия с графиком.</html>";
        }
        if (p.x < plot.x) {
            return "Ось баланса, " + data.currency() + ": деления кратны " + AXIS_FORMAT.format(
                    ChartScale.niceStep((axisMax - axisMin) / 100.0, 6));
        }
        if (p.y > plot.y + plot.height) {
            return "Ось дат: " + DateFormats.ru(data.from()) + " - " + DateFormats.ru(data.to());
        }
        // Внутри графика подсказку заменяет карточка дня.
        return null;
    }

    // ------------------------------------------------------------------ отрисовка

    /**
     * Рисует график в копии контекста (исходный {@code Graphics} Swing не меняется) через общий метод отрисовки,
     * которым пользуется и экспорт PNG.
     *
     * @param graphics контекст рисования Swing
     */
    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            paintChart(g, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    /**
     * Сохраняет график в PNG заданного размера («Файл → Сохранить график PNG…»).
     *
     * @param file   файл
     * @param width  ширина в пикселях
     * @param height высота в пикселях
     * @throws IOException если записать файл не удалось или данных нет
     */
    public void writePng(Path file, int width, int height) throws IOException {
        if (data == null) {
            throw new IOException("График ещё не построен: " + emptyMessage);
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setFont(getFont());
            paintChart(g, width, height);
        } finally {
            g.dispose();
        }
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("Формат PNG недоступен");
        }
    }

    private void paintChart(Graphics2D g, int width, int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        if (g.getFont() == null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        }
        FontMetrics fm = g.getFontMetrics();
        if (data == null || data.points().size() < 2) {
            g.setColor(Palette.PAST);
            String text = data == null ? emptyMessage : "Слишком короткий период для графика";
            g.drawString(text, (width - fm.stringWidth(text)) / 2, height / 2);
            return;
        }
        Rectangle plot = new Rectangle(LEFT, TOP, Math.max(10, width - LEFT - RIGHT), Math.max(10, height - TOP - BOTTOM));

        // Диапазон оси: баланс, ноль, подушка и цель должны быть видны.
        double min = 0;
        double max = 0;
        for (DailyPoint p : data.points()) {
            min = Math.min(min, p.balance().minor());
            max = Math.max(max, p.balance().minor());
        }
        if (data.cushion().isPositive()) {
            max = Math.max(max, data.cushion().minor());
        }
        if (data.goal() != null) {
            max = Math.max(max, data.goal().target().minor());
        }
        List<Double> ticks = ChartScale.ticks(min / 100.0, max / 100.0, 6);
        axisMin = ticks.getFirst() * 100;
        axisMax = ticks.getLast() * 100;

        // Сетка и подписи оси баланса.
        for (double tick : ticks) {
            int y = yOf(tick * 100, plot);
            g.setColor(Palette.GRID);
            g.drawLine(plot.x, y, plot.x + plot.width, y);
            g.setColor(Color.DARK_GRAY);
            String label = AXIS_FORMAT.format(tick);
            g.drawString(label, plot.x - 8 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
        }
        paintMonthAxis(g, plot, fm);
        if (data.bars()) {
            paintMonthBars(g, plot);
        }

        // Заливка под линией и шаговая линия баланса.
        Path2D line = new Path2D.Double();
        Path2D fill = new Path2D.Double();
        int zeroY = yOf(0, plot);
        List<DailyPoint> points = data.points();
        for (int i = 0; i < points.size(); i++) {
            DailyPoint p = points.get(i);
            double x = xOf(p.date(), plot);
            double y = yOf(p.balance().minor(), plot);
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, zeroY);
                fill.lineTo(x, y);
            } else {
                // Баланс меняется скачком в день события: сначала горизонталь до новой даты, затем вертикаль.
                double prevY = yOf(points.get(i - 1).balance().minor(), plot);
                line.lineTo(x, prevY);
                line.lineTo(x, y);
                fill.lineTo(x, prevY);
                fill.lineTo(x, y);
            }
        }
        double endX = xOf(data.to(), plot) + plot.width / (double) Math.max(1, ChronoUnit.DAYS.between(data.from(), data.to()) + 1);
        double lastY = yOf(points.getLast().balance().minor(), plot);
        line.lineTo(Math.min(endX, plot.x + plot.width), lastY);
        fill.lineTo(Math.min(endX, plot.x + plot.width), lastY);
        fill.lineTo(Math.min(endX, plot.x + plot.width), zeroY);
        fill.closePath();
        g.setColor(Palette.BALANCE_FILL);
        g.fill(fill);
        g.setColor(Palette.BALANCE_LINE);
        g.setStroke(new BasicStroke(2f));
        g.draw(line);

        // Горизонтали: ноль, подушка, цель.
        BasicStroke dashed = new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6f, 4f}, 0f);
        horizontal(g, plot, 0, Palette.ZERO_LINE, new BasicStroke(1.2f), "0", fm);
        if (data.cushion().isPositive()) {
            horizontal(g, plot, data.cushion().minor(), Palette.CUSHION_LINE, dashed, "подушка " + data.cushion().format(), fm);
        }
        if (data.goal() != null) {
            horizontal(g, plot, data.goal().target().minor(), Palette.GOAL_LINE, dashed,
                    "цель «" + data.goal().title() + "» " + data.goal().target().format(), fm);
        }

        // Вертикаль «сегодня».
        if (!data.today().isBefore(data.from()) && !data.today().isAfter(data.to())) {
            int x = (int) Math.round(xOf(data.today(), plot));
            g.setColor(Palette.TODAY_LINE);
            g.setStroke(dashed);
            g.drawLine(x, plot.y, x, plot.y + plot.height);
            g.drawString("сегодня", x + 4, plot.y + fm.getAscent());
        }

        if (data.markers()) {
            paintMarkers(g, plot);
        }

        // Рамка и заголовок-легенда.
        g.setStroke(new BasicStroke(1f));
        g.setColor(Palette.BORDER);
        g.drawRect(plot.x, plot.y, plot.width, plot.height);
        g.setColor(Color.DARK_GRAY);
        String legend = "Баланс, " + data.currency() + ": " + DateFormats.ru(data.from()) + " - " + DateFormats.ru(data.to())
                + "   ▲ доход  ▼ расход";
        g.drawString(legend, plot.x, TOP - 12);
    }

    private void paintMonthAxis(Graphics2D g, Rectangle plot, FontMetrics fm) {
        long months = ChronoUnit.MONTHS.between(YearMonth.from(data.from()), YearMonth.from(data.to())) + 1;
        // Подписи не должны налезать друг на друга: при длинном горизонте подписываем каждый 2-й, 3-й … месяц.
        int every = (int) Math.max(1, Math.ceil(months * 70.0 / Math.max(1, plot.width)));
        YearMonth month = YearMonth.from(data.from());
        for (int i = 0; i < months; i++, month = month.plusMonths(1)) {
            LocalDate first = month.atDay(1);
            if (first.isBefore(data.from())) {
                continue;
            }
            int x = (int) Math.round(xOf(first, plot));
            g.setColor(Palette.GRID);
            g.drawLine(x, plot.y, x, plot.y + plot.height);
            if (i % every == 0) {
                g.setColor(Color.DARK_GRAY);
                String label = RuText.monthNominative(month.getMonth()).substring(0, 3).toLowerCase(Locale.ROOT)
                        + " " + String.valueOf(month.getYear()).substring(2);
                g.drawString(label, x + 3, plot.y + plot.height + fm.getAscent() + 4);
            }
        }
    }

    private void paintMonthBars(Graphics2D g, Rectangle plot) {
        Map<YearMonth, MonthTotals> byMonth = data.forecast().summary().byMonth();
        long maxValue = 1;
        for (Map.Entry<YearMonth, MonthTotals> e : byMonth.entrySet()) {
            if (inRange(e.getKey())) {
                maxValue = Math.max(maxValue, Math.max(e.getValue().income().minor(), e.getValue().expense().minor()));
            }
        }
        int band = plot.height / 4;
        Graphics2D bars = (Graphics2D) g.create();
        try {
            bars.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            for (Map.Entry<YearMonth, MonthTotals> e : byMonth.entrySet()) {
                if (!inRange(e.getKey())) {
                    continue;
                }
                LocalDate start = e.getKey().atDay(1).isBefore(data.from()) ? data.from() : e.getKey().atDay(1);
                LocalDate end = e.getKey().atEndOfMonth().isAfter(data.to()) ? data.to() : e.getKey().atEndOfMonth();
                int x0 = (int) Math.round(xOf(start, plot));
                int x1 = (int) Math.round(xOf(end, plot));
                int w = Math.max(2, (x1 - x0) / 2 - 2);
                int hi = (int) (band * e.getValue().income().minor() / (double) maxValue);
                int he = (int) (band * e.getValue().expense().minor() / (double) maxValue);
                int base = plot.y + plot.height;
                bars.setColor(Palette.INCOME);
                bars.fillRect(x0 + 1, base - hi, w, hi);
                bars.setColor(Palette.EXPENSE);
                bars.fillRect(x0 + 2 + w, base - he, w, he);
            }
        } finally {
            bars.dispose();
        }
    }

    private boolean inRange(YearMonth month) {
        return !month.atEndOfMonth().isBefore(data.from()) && !month.atDay(1).isAfter(data.to());
    }

    private void paintMarkers(Graphics2D g, Rectangle plot) {
        g.setStroke(new BasicStroke(1f));
        // При тысячах событий треугольники слились бы в полосу — рисуем точки.
        int size = data.rows().size() > 400 ? 2 : 5;
        for (ForecastRow row : data.rows()) {
            if (row.origin() == Origin.START || row.flags().skipped() || row.amount().isZero()) {
                continue;
            }
            int x = (int) Math.round(xOf(row.date(), plot));
            int y = yOf(row.balanceAfter().minor(), plot);
            g.setColor(row.isIncome() ? Palette.INCOME : Palette.EXPENSE);
            if (size <= 2) {
                g.fillRect(x - 1, y - 1, 3, 3);
            } else if (row.isIncome()) {
                g.fillPolygon(new int[] {x - size, x + size, x}, new int[] {y + size, y + size, y - size}, 3);
            } else {
                g.fillPolygon(new int[] {x - size, x + size, x}, new int[] {y - size, y - size, y + size}, 3);
            }
        }
    }

    private void horizontal(Graphics2D g, Rectangle plot, double valueMinor, Color color, BasicStroke stroke, String label,
                            FontMetrics fm) {
        int y = yOf(valueMinor, plot);
        g.setColor(color);
        g.setStroke(stroke);
        g.drawLine(plot.x, y, plot.x + plot.width, y);
        g.drawString(label, plot.x + plot.width - fm.stringWidth(label) - 4, y - 3);
    }

    private double xOf(LocalDate date, Rectangle plot) {
        long days = Math.max(1, ChronoUnit.DAYS.between(data.from(), data.to()));
        return plot.x + plot.width * ChronoUnit.DAYS.between(data.from(), date) / (double) days;
    }

    private int yOf(double valueMinor, Rectangle plot) {
        double span = Math.max(1, axisMax - axisMin);
        return (int) Math.round(plot.y + plot.height - plot.height * (valueMinor - axisMin) / span);
    }
}
