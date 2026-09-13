package ru.cashprediction.core.ui.view.summary;

import java.util.List;
import java.util.Objects;

/**
 * Панель сводки (спецификация v2, §2, §5.1): девять карточек равной ширины (минимум 118 px) с переносом на вторую
 * строку при нехватке ширины — никогда не обрезаются (решение L7).
 *
 * @param visible         видна ли панель (флажок «Панель сводки»)
 * @param cards           карточки по порядку; пустой список, если прогноз не рассчитан
 * @param unavailableText «Сводка недоступна: {0}» (цвет {@code expense}) или пустая строка
 */
public record SummaryModel(boolean visible, List<CardModel> cards, String unavailableText) {

    /** Копирует список и заменяет {@code null}. */
    public SummaryModel {
        cards = List.copyOf(Objects.requireNonNull(cards, "cards"));
        unavailableText = Objects.requireNonNullElse(unavailableText, "");
    }
}
