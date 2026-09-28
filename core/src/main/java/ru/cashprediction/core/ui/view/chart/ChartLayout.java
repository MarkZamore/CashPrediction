package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.util.function.BiFunction;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.ui.view.popup.DayCardModel;
import ru.cashprediction.core.ui.view.popup.PopupBuilders;

/**
 * Построение модели графика из состояния (спецификация v2, §5.3).
 *
 * <p><b>Что делает реализация:</b> диапазон от начала прогноза до {@code view.periodEnd}; поля 80/24/28/32;
 * полоса столбцов внизу высотой min(110, 22 % высоты) с осью посередине, прямоугольник на 70 % ширины месяца,
 * непрозрачность 0,45; заливка {@code accent} 0,10; ступенчатая линия {@code accent} 2 px, до 1500 точек
 * ({@code ChartSeries.sample}); ноль, подушка, цель, сегодня с подписями {@code chart.*}; маркеры r = 3,5 на дни с
 * видимыми непропущенными событиями (кроме START), заливка {@code income}/{@code expense}/{@code marker.mixed},
 * белая обводка 1 px, не больше 400 (иначе уведомление {@code chart.tooManyMarkers}); легенда и зоны подсказок.
 * Подробности раскладки - {@code PlanChartModel}, данные и легенда - {@code ChartData}.</p>
 *
 * <p><b>Карточка дня</b> ({@link ChartModel#dayCard}, наведение) строится общим построителем
 * {@link PopupBuilders#dayCard(AppState, LocalDate)}, чтобы график и другие места показывали одну и ту же карточку.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class ChartLayout {

    private ChartLayout() {
    }

    /**
     * Модель графика для состояния.
     *
     * @param state    состояние приложения
     * @param revision ревизия модели
     * @return модель, раскладывающая сцену для любого размера
     */
    public static ChartModel model(AppState state, long revision) {
        return model(state, revision, PopupBuilders::dayCard);
    }

    /**
     * Модель графика с заданным построителем карточки дня (для тестов раскладки независимо от всплывающих окон).
     *
     * @param state    состояние приложения
     * @param revision ревизия модели
     * @param dayCards построитель карточки дня
     * @return модель
     */
    static ChartModel model(AppState state, long revision, BiFunction<AppState, LocalDate, DayCardModel> dayCards) {
        return new PlanChartModel(state, revision, dayCards);
    }
}
