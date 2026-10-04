package ru.cashprediction.core.ui.menu;

import java.time.LocalDate;
import java.util.Optional;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.ui.view.chart.ChartLayout;
import ru.cashprediction.core.ui.view.chart.PlotTransform;
import ru.cashprediction.core.ui.view.summary.CardModel;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;

/**
 * Даты, которые контекстным меню карточки и графика берут из других моделей ядра: дата карточки (§5.1) и дата под
 * указателем графика (§5.3). Одна точка зрения на дату у меню, двойного щелчка и наведения - поэтому меню не считает
 * её само, а спрашивает {@link SummaryBuilder} и {@link ChartLayout}.
 *
 * <p>Тесты контекстных меню подставляют свою реализацию, чтобы проверять пункты меню независимо от построения сводки
 * и графика; настоящая - {@link #MODELS}.</p>
 */
interface ContextFacts {

    /** Даты из настоящих моделей сводки и графика. */
    ContextFacts MODELS = new Models();

    /**
     * Дата карточки сводки.
     *
     * @param state  состояние приложения
     * @param cardId id карточки
     * @return дата или пусто, если у карточки нет даты, карточки нет или прогноз не рассчитан
     */
    Optional<LocalDate> cardDate(AppState state, String cardId);

    /**
     * Дата под указателем графика.
     *
     * @param state состояние приложения
     * @param chart точка и размер области рисования
     * @return дата или пусто, если указатель вне области построения или прогноз не рассчитан
     */
    Optional<LocalDate> chartDate(AppState state, ContextTarget.Chart chart);

    /** Реализация через {@link SummaryBuilder} и {@link ChartLayout}. Без состояния, потокобезопасна. */
    final class Models implements ContextFacts {

        /**
         * Строит сводку рассчитанного прогноза и берёт дату карточки с указанным идентификатором.
         * @param state состояние приложения
         * @param cardId идентификатор карточки сводки
         * @return дата или пусто, если прогноз недоступен, карточка не найдена либо не имеет даты
         */
        @Override
        public Optional<LocalDate> cardDate(AppState state, String cardId) {
            if (!state.document().forecastAvailable()) {
                return Optional.empty();
            }
            return SummaryBuilder.build(state).cards().stream()
                    .filter(card -> card.id().equals(cardId))
                    .findFirst()
                    .map(CardModel::date);
        }

        /**
         * Раскладывает график текущей ревизии в заданный размер и определяет день под указателем.
         * Координата по горизонтали выбирает день, обе координаты проверяются на попадание
         * в область построения, включая её границу.
         * @param state состояние приложения с прогнозом
         * @param chart координаты указателя и размер области рисования
         * @return дата или пусто при недоступном прогнозе, отсутствующей области либо указателе вне неё
         */
        @Override
        public Optional<LocalDate> chartDate(AppState state, ContextTarget.Chart chart) {
            if (!state.document().forecastAvailable()) {
                return Optional.empty();
            }
            PlotTransform plot = ChartLayout.model(state, state.revision()).layout(chart.width(), chart.height()).plot();
            return plot != null && plot.contains(chart.x(), chart.y())
                    ? Optional.of(plot.dateAt(chart.x())) : Optional.empty();
        }
    }
}
