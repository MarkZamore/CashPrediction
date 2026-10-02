package ru.cashprediction.swing.ui;

import javax.swing.table.AbstractTableModel;
import ru.cashprediction.core.ui.view.table.TableModel;

/** Ленивая обёртка модели ядра: тексты запрашиваются только для нужной JTable ячейки. */
public final class SwingTableAdapter extends AbstractTableModel {
    private TableModel model;
    /** Создаёт адаптер неизменяемой ревизии. */
    public SwingTableAdapter(TableModel model) { this.model = model; }
    /** Возвращает исходную ленивую модель без материализации строк. */
    public TableModel source() { return model; }
    /** Применяет новую ревизию без копирования строк. */
    public void update(TableModel next) { model = next; fireTableDataChanged(); }
    /** Возвращает число виртуальных строк. */
    @Override public int getRowCount() { return model == null ? 0 : model.rowCount(); }
    /** Возвращает число колонок ядра. */
    @Override public int getColumnCount() { return model == null ? 0 : model.columns().size(); }
    /** Возвращает локализованное имя колонки. */
    @Override public String getColumnName(int column) { return model.columns().get(column).title(); }
    /** Запрашивает только конкретную ячейку нужной строки. */
    @Override public Object getValueAt(int row, int column) { return model.row(row).cells().get(column); }
    /** Таблица не редактирует модель напрямую: редактирование выполняют формы ядра. */
    @Override public boolean isCellEditable(int row, int column) { return false; }
}
