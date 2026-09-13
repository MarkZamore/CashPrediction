package ru.cashprediction.core.ui.view.chart;

/**
 * Точка сцены графика в пикселях области рисования (начало координат — левый верхний угол).
 *
 * @param x координата по горизонтали
 * @param y координата по вертикали (растёт вниз)
 */
public record ChartPoint(double x, double y) {
}
