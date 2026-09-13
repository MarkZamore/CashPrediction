package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.util.Objects;
import ru.cashprediction.core.ui.view.popup.DayCardModel;

/**
 * Результат наведения на график (спецификация v2, §5.3 «Наведение»).
 *
 * @param date       дата под указателем ({@link PlotTransform#dateAt(double)})
 * @param lineX      x пунктирной вертикали
 * @param dot        точка {@code accent} r = 4,5 на линии баланса конца дня
 * @param card       карточка дня
 * @param cardX      x левого верхнего угла карточки (указатель +16)
 * @param cardY      y левого верхнего угла карточки (указатель +16)
 */
public record ChartHover(LocalDate date, double lineX, ChartPoint dot, DayCardModel card, double cardX, double cardY) {

    /** Проверяет поля. */
    public ChartHover {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dot, "dot");
        Objects.requireNonNull(card, "card");
    }
}
