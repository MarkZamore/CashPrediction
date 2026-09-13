package ru.cashprediction.core.ui.view.chart;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Сцена графика для конкретного размера (архитектура §3.4): фон {@code bg.surface}, примитивы в порядке рисования,
 * легенда, зоны подсказок и преобразование координат.
 *
 * <p><b>Порядок примитивов</b> (§5.3): сетка и подписи осей; столбцы итогов месяцев и подпись «итог мес.»; заливка
 * под линией; ступенчатая линия баланса; ноль; подушка; цель; сегодня; маркеры. <b>Легенда</b> — строка над областью
 * построения (поле сверху 28 px, шрифт {@code font.legend} 12 px): клиент выкладывает элементы слева направо с
 * образцом цвета, промежуток 12 px. <b>Пустая сцена:</b> если {@link #emptyText()} не пуст, примитивов нет, текст
 * рисуется по центру цветом {@link #emptyColor()}.</p>
 *
 * @param width      ширина области рисования
 * @param height     высота области рисования
 * @param plot       преобразование координат области построения
 * @param primitives примитивы по порядку рисования
 * @param legend     элементы легенды по порядку
 * @param hits       зоны подсказок маркеров и столбцов
 * @param emptyText  «Прогноз не рассчитан: {0}» или {@code chart.noData}; пустая строка — данные есть
 * @param emptyColor цвет пустого текста ({@code expense} для ошибки, иначе {@code text.muted})
 */
public record ChartScene(double width, double height, PlotTransform plot, List<ChartPrimitive> primitives,
                         List<LegendItem> legend, List<HitRegion> hits, String emptyText, ColorToken emptyColor) {

    /** Проверяет поля и копирует списки. */
    public ChartScene {
        Objects.requireNonNull(plot, "plot");
        primitives = List.copyOf(Objects.requireNonNull(primitives, "primitives"));
        legend = List.copyOf(Objects.requireNonNull(legend, "legend"));
        hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
        emptyText = Objects.requireNonNullElse(emptyText, "");
        emptyColor = emptyColor == null ? ColorToken.TEXT_MUTED : emptyColor;
    }
}
