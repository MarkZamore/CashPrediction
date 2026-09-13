package ru.cashprediction.core.session;

/**
 * Положение и размер окна в экранных координатах (логические пиксели).
 *
 * <p>Координаты хранятся как {@code double}, потому что JavaFX работает с дробными координатами
 * при масштабировании экрана; Swing и браузер округлят их сами. При восстановлении клиент обязан
 * «прижать» окно к доступным экранам: монитор, на котором окно было, мог быть отключён.</p>
 *
 * <p>Запись неизменяема и потокобезопасна.</p>
 *
 * @param x      левая граница
 * @param y      верхняя граница
 * @param width  ширина, не меньше 0
 * @param height высота, не меньше 0
 */
public record WindowBounds(double x, double y, double width, double height) {

    /** Проверяет, что все числа конечны, а размеры неотрицательны. */
    public WindowBounds {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Координаты окна должны быть конечными числами");
        }
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Размер окна не может быть отрицательным: " + width + "×" + height);
        }
    }
}
