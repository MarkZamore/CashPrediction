package ru.cashprediction.core.ui.view.popup;

import java.time.LocalDate;
import java.time.YearMonth;
import ru.cashprediction.core.app.AppState;

/**
 * Построители моделей всплывающих окон (спецификация v2, §5.1, §5.6.2, §5.6.5).
 *
 * <p><b>Карточка дня:</b> заголовок {@code UiFormats.dateWeekday}; баланс на конец дня; до 8 событий дня с учётом
 * фильтров вида (без START), затем {@code day.more}; пропущенные — «{@code day.skipped}  {title}».
 * <b>Спарклайн:</b> диапазон по таблице §5.1 (now: anchor → min(anchor+3 мес, end); m1–m12: anchor → anchor+N; за
 * горизонтом и остальные: anchor → end), до 240 точек, нормированных в 0..1. <b>Календарь:</b> 6 недель с
 * понедельника, выбранный день, сегодня, выходные {@code expense}.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class PopupBuilders {

    private PopupBuilders() {
    }

    /**
     * Карточка дня.
     *
     * @param state состояние (прогноз, фильтры вида, валюта)
     * @param date  день
     * @return модель карточки
     */
    public static DayCardModel dayCard(AppState state, LocalDate date) {
        throw new UnsupportedOperationException("S1: core-views - PopupBuilders.dayCard");
    }

    /**
     * Всплывающее окно карточки сводки.
     *
     * @param state  состояние
     * @param cardId id карточки ({@code now}, {@code m1}, …, {@code goal})
     * @return модель спарклайна
     */
    public static SparklineModel sparkline(AppState state, String cardId) {
        throw new UnsupportedOperationException("S1: core-views - PopupBuilders.sparkline");
    }

    /**
     * Календарь поля даты.
     *
     * @param month    месяц
     * @param selected выбранная дата или {@code null}
     * @param today    сегодня
     * @return модель календаря
     */
    public static CalendarModel calendar(YearMonth month, LocalDate selected, LocalDate today) {
        throw new UnsupportedOperationException("S1: core-views - PopupBuilders.calendar");
    }
}
