package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.util.Optional;
import ru.cashprediction.core.ui.view.popup.DayCardModel;

/**
 * Модель графика баланса (спецификация v2, §5.3; архитектура §3.4). Ядро вычисляет все координаты и подписи;
 * клиенты только рисуют сцену: JavaFX {@code Canvas} → Swing {@code Graphics2D} → Web SVG. PNG рисуется из той же
 * сцены размером 1200×700.
 *
 * <p>Модель неизменяема в пределах ревизии; реализация ({@code ChartLayout}) потокобезопасна для чтения.</p>
 */
public interface ChartModel {

    /** @return номер ревизии (web отбрасывает сцены устаревших ревизий) */
    long revision();

    /**
     * Раскладка для размера области рисования.
     *
     * @param width  ширина в пикселях
     * @param height высота в пикселях
     * @return сцена с примитивами в порядке рисования §5.3
     */
    ChartScene layout(double width, double height);

    /**
     * Карточка дня для даты под указателем (§5.6.2).
     *
     * @param date дата
     * @return модель карточки
     */
    DayCardModel dayCard(LocalDate date);

    /**
     * Наведение внутри области построения: пунктирная вертикаль {@code text.muted}, точка {@code accent} r = 4,5 на
     * линии и карточка дня у указателя +16/+16. За пределами области построения всё скрывается.
     *
     * @param x      координата указателя
     * @param y      координата указателя
     * @param width  ширина области рисования
     * @param height высота области рисования
     * @return наведение или пусто, если указатель вне области построения или данных нет
     */
    Optional<ChartHover> hover(double x, double y, double width, double height);
}
