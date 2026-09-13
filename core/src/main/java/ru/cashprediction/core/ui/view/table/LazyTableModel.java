package ru.cashprediction.core.ui.view.table;

import java.util.List;
import ru.cashprediction.core.app.AppState;

/**
 * Реализация {@link TableModel} над прогнозом (спецификация v2, §5.2; архитектура §3.4).
 *
 * <p><b>Устройство:</b> при построении — массив индексов видимых строк прогноза (фильтры, период, флажки, свёрнутая
 * группа прошедших, итоги месяцев после последней видимой строки месяца; итоги, все строки которых скрыты в
 * свёрнутой группе, не показываются) и LRU-кэш на 2000 готовых {@link TableRowView}. Порядок отметок:
 * ✎ → ⇄ ≡ ✕ Δ. Бюджет: индекс 200 000 строк строится быстрее 300 мс, строка — в среднем быстрее 1 мс.</p>
 *
 * <p>Класс неизменяем снаружи (кэш синхронизирован), потокобезопасен.</p>
 */
public final class LazyTableModel implements TableModel {

    private LazyTableModel() {
    }

    /**
     * Строит модель для текущего состояния.
     *
     * @param state    состояние приложения (план, прогноз, вид, выделение, группа прошедших, сегодня)
     * @param revision номер ревизии модели
     * @return модель таблицы
     */
    public static TableModel build(AppState state, long revision) {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.build");
    }

    @Override
    public long revision() {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.revision");
    }

    @Override
    public List<ColumnSpec> columns() {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.columns");
    }

    @Override
    public int rowCount() {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.rowCount");
    }

    @Override
    public TableRowView row(int index) {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.row");
    }

    @Override
    public String tooltip(int index, String columnId) {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.tooltip");
    }

    @Override
    public int indexOf(String rowId) {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.indexOf");
    }

    @Override
    public String selectedRowId() {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.selectedRowId");
    }

    @Override
    public String scrollToRowId() {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.scrollToRowId");
    }

    @Override
    public Placeholder placeholder() {
        throw new UnsupportedOperationException("S1: core-views — LazyTableModel.placeholder");
    }
}
