package ru.cashprediction.core.ui.view.popup;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.view.chart.ChartPoint;

/**
 * Всплывающее окно карточки сводки со спарклайном (спецификация v2, §5.1): JavaFX {@code PopupControl} + Skin →
 * Swing {@code SwingPopupControl} → Web div-popover. Появляется через 350 мс под карточкой с зазором 4 px.
 *
 * <p>Координаты точек нормированы в 0..1 (x — слева направо, y — сверху вниз), клиент масштабирует их на
 * 240×60: так рисунок одинаков при любом масштабе экрана.</p>
 *
 * @param cardId      id карточки
 * @param header      первая жирная строка «{Заголовок}: {format(cur)}» или «{Заголовок}: за горизонтом»
 * @param explanation пояснение ({@code text.muted}, 11 px, перенос по ширине 240)
 * @param points      точки линии {@code accent} 1,5 px; меньше 2 точек — рисуется {@link #noDataText()}
 * @param zeroY       нормированная y линии нуля (пунктир 3/3 {@code expense}), если баланс пересекает ноль; иначе {@code null}
 * @param marker      нормированная точка {@code line.today} на дате карточки или {@code null}
 * @param minText     {@code spark.min} «мин. {format(cur)}» слева внизу (10 px)
 * @param maxText     {@code spark.max} «макс. {format(cur)}» справа внизу
 * @param noDataText  {@code spark.noData} «нет данных», если точек меньше 2, иначе пустая строка
 */
public record SparklineModel(String cardId, String header, String explanation, List<ChartPoint> points, Double zeroY,
                             ChartPoint marker, String minText, String maxText, String noDataText) {

    /** Проверяет поля и копирует список. */
    public SparklineModel {
        Objects.requireNonNull(cardId, "cardId");
        header = Objects.requireNonNullElse(header, "");
        explanation = Objects.requireNonNullElse(explanation, "");
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        minText = Objects.requireNonNullElse(minText, "");
        maxText = Objects.requireNonNullElse(maxText, "");
        noDataText = Objects.requireNonNullElse(noDataText, "");
    }
}
