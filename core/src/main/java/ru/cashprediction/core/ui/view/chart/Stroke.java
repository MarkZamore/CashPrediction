package ru.cashprediction.core.ui.view.chart;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Линия примитива графика (спецификация v2, §1.1, §5.3): ноль 1 px сплошная, подушка 1,5 px штрих 6/4,
 * цель 1,5 px штрих 8/4, сегодня 1,5 px штрих 4/4, баланс {@code accent} 2 px.
 *
 * @param color   цвет
 * @param width   толщина в пикселях
 * @param dash    длины штрихов и промежутков; пустой список — сплошная
 * @param opacity непрозрачность 0..1
 */
public record Stroke(ColorToken color, double width, List<Double> dash, double opacity) {

    /** Проверяет поля и копирует список. */
    public Stroke {
        Objects.requireNonNull(color, "color");
        dash = dash == null ? List.of() : List.copyOf(dash);
    }

    /**
     * Сплошная непрозрачная линия.
     *
     * @param color цвет
     * @param width толщина
     * @return линия
     */
    public static Stroke solid(ColorToken color, double width) {
        return new Stroke(color, width, List.of(), 1.0);
    }
}
