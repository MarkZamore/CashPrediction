package ru.cashprediction.core.ui.view.table;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Готовая к отрисовке строка таблицы (архитектура §3.4): тексты всех восьми ячеек и оформление.
 *
 * @param rowId         стабильный идентификатор строки (выделение, снимок, «Показать в таблице»)
 * @param kind          вид строки
 * @param cells         тексты ячеек в порядке {@link TableModel#columns()}; пустые ячейки — пустые строки
 * @param rowStyle      оформление строки
 * @param cellStyles    оформление ячеек по id колонки (только отличающиеся от строки)
 * @param leadingSpan   сколько первых колонок объединены в одну ячейку (4 для PAST_HEADER: «Дата…Категория»),
 *                      иначе 1
 * @param quickEditable доступна ли быстрая правка суммы двойным щелчком по «Доход»/«Расход» (RULE, не пропущено)
 */
public record TableRowView(String rowId, RowKind kind, List<String> cells, RowStyle rowStyle,
                           Map<String, CellStyle> cellStyles, int leadingSpan, boolean quickEditable) {

    /** Проверяет поля и копирует коллекции. */
    public TableRowView {
        Objects.requireNonNull(rowId, "rowId");
        Objects.requireNonNull(kind, "kind");
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        rowStyle = rowStyle == null ? RowStyle.PLAIN : rowStyle;
        cellStyles = cellStyles == null ? Map.of() : Map.copyOf(cellStyles);
        if (leadingSpan < 1) {
            // Сообщение для разработчика (ошибка построения модели), поэтому латиницей и не из каталога.
            throw new IllegalArgumentException("leadingSpan must be >= 1: " + leadingSpan);
        }
    }
}
