package ru.cashprediction.core.ui.view.table;

import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Оформление отдельной ячейки поверх {@link RowStyle}: «Доход» {@code income}, «Расход» {@code expense},
 * «Баланс» {@code expense} при отрицательном значении, прошедшая строка — {@code text.past}, пропущенное событие —
 * название и сумма зачёркнуты (спецификация v2, §5.2).
 *
 * @param text   цвет текста или {@code null} — как у строки
 * @param bold   жирный
 * @param italic курсив
 * @param strike зачёркнутый
 */
public record CellStyle(ColorToken text, boolean bold, boolean italic, boolean strike) {
}
