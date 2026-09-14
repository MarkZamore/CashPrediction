package ru.cashprediction.swing.view;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumnModel;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Таблица прогноза главного окна ({@code JTable} + {@link ForecastTableModel}) — Swing-аналог JavaFX {@code TableView}
 * из {@code ForecastTableView}.
 *
 * <p><b>Мышь.</b> Двойной щелчок по сумме события правила открывает быструю правку суммы ({@code Popup}), по другой
 * ячейке — редактор операции. Щелчок по заголовку «Прошедшие события» сворачивает и разворачивает группу.
 * Контекстное меню открывается по {@link MouseEvent#isPopupTrigger()}, который проверяется и в
 * {@code mousePressed}, и в {@code mouseReleased}: на Windows признак приходит при отпускании кнопки, на других
 * системах — при нажатии (аналог JavaFX {@code ContextMenuEvent}).</p>
 *
 * <p><b>Клавиатура.</b> Enter — изменить, Delete — удалить, клавиша контекстного меню и Shift+F10 — меню выбранной
 * строки у её ячейки.</p>
 *
 * <p><b>Подсказки.</b> {@link #getToolTipText(MouseEvent)} даёт подсказку конкретной ячейки: полную дату, заметку,
 * пояснение отметок, сравнение баланса с подушкой.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class ForecastTable extends JTable {

    /** Реакции таблицы, которые выполняет главное окно. */
    public interface Handler {
        /**
         * Открыть редактор операции строки.
         *
         * @param row событие
         */
        void editRow(ForecastRow row);

        /**
         * Открыть быструю правку суммы события правила.
         *
         * @param row событие правила
         */
        void quickEdit(ForecastRow row);

        /** «Удалить…» для выбранной строки (клавиша Delete). */
        void deleteSelected();

        /**
         * Показать контекстное меню строки.
         *
         * @param row       событие
         * @param component компонент, относительно которого заданы координаты
         * @param x         координата x
         * @param y         координата y
         */
        void showContextMenu(ForecastRow row, Component component, int x, int y);

        /** Свернуть или развернуть группу «Прошедшие события». */
        void togglePast();

        /** Изменилось выделение строки. */
        void selectionChanged();
    }

    private final ForecastTableModel model;
    private final Handler handler;

    /**
     * Создаёт таблицу.
     *
     * @param model   модель
     * @param handler реакции главного окна
     */
    public ForecastTable(ForecastTableModel model, Handler handler) {
        super(model);
        this.model = model;
        this.handler = Objects.requireNonNull(handler, "handler");
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setRowHeight(Math.max(22, getFontMetrics(getFont()).getHeight() + 6));
        setFillsViewportHeight(true);
        setShowVerticalLines(false);
        setAutoCreateRowSorter(false);
        getTableHeader().setReorderingAllowed(false);
        setDefaultRenderer(String.class, new ForecastCellRenderer());
        TableColumnModel columns = getColumnModel();
        int[] widths = {90, 40, 220, 120, 110, 110, 120, 80};
        for (int i = 0; i < widths.length; i++) {
            columns.getColumn(i).setPreferredWidth(widths[i]);
        }
        getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                handler.selectionChanged();
            }
        });
        installMouse();
        installKeys();
        // JavaFX: Tooltip на ячейке → Swing: getToolTipText(MouseEvent) + ToolTipManager → Web: title у <td>
        setToolTipText("");
    }

    private void installMouse() {
        // JavaFX: ContextMenuEvent (setOnContextMenuRequested) → Swing: MouseAdapter.isPopupTrigger() в mousePressed и mouseReleased → Web: contextmenu + preventDefault
        addMouseListener(new MouseAdapter() {
            /** Нажатие кнопки мыши: на части платформ именно оно является запросом контекстного меню. */
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popupAt(e);
                }
            }

            /** Отпускание кнопки мыши: на Windows запрос контекстного меню приходит здесь. */
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popupAt(e);
                }
            }

            /** Щелчок мышью: одинарный выбирает, двойной открывает редактирование. */
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int viewRow = rowAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                TableItem item = model.itemAt(convertRowIndexToModel(viewRow));
                if (item.type() == TableItem.Type.PAST_HEADER) {
                    handler.togglePast();
                    return;
                }
                if (e.getClickCount() == 2 && item.isRow()) {
                    int column = convertColumnIndexToModel(columnAtPoint(e.getPoint()));
                    boolean amountColumn = column == ForecastTableModel.COL_INCOME || column == ForecastTableModel.COL_EXPENSE;
                    if (amountColumn && item.row().origin() == Origin.RULE && item.row().ruleId() != null) {
                        handler.quickEdit(item.row());
                    } else {
                        handler.editRow(item.row());
                    }
                }
            }
        });
    }

    private void popupAt(MouseEvent e) {
        int viewRow = rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            return;
        }
        // Правая кнопка сначала выделяет строку под курсором — меню относится именно к ней.
        setRowSelectionInterval(viewRow, viewRow);
        TableItem item = model.itemAt(convertRowIndexToModel(viewRow));
        if (item.isRow()) {
            handler.showContextMenu(item.row(), this, e.getX(), e.getY());
        }
    }

    private void installKeys() {
        // Enter у JTable по умолчанию переходит на следующую строку; здесь это «Изменить…», как в JavaFX-клиенте.
        KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        KeyStroke delete = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0);
        KeyStroke contextKey = KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0);
        KeyStroke shiftF10 = KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK);
        for (int condition : new int[] {JComponent.WHEN_FOCUSED, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT}) {
            getInputMap(condition).put(enter, "cashEditRow");
            getInputMap(condition).put(delete, "cashDeleteRow");
            getInputMap(condition).put(contextKey, "cashContextMenu");
            getInputMap(condition).put(shiftF10, "cashContextMenu");
        }
        getActionMap().put("cashEditRow", new AbstractAction() {
            /** Срабатывание действия (кнопка, Enter, клавиша или таймер): выполняет команду этого слушателя. */
            @Override
            public void actionPerformed(ActionEvent e) {
                selectedItem().ifPresent(item -> {
                    if (item.isRow()) {
                        handler.editRow(item.row());
                    } else if (item.type() == TableItem.Type.PAST_HEADER) {
                        handler.togglePast();
                    }
                });
            }
        });
        getActionMap().put("cashDeleteRow", new AbstractAction() {
            /** Срабатывание действия (кнопка, Enter, клавиша или таймер): выполняет команду этого слушателя. */
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.deleteSelected();
            }
        });
        getActionMap().put("cashContextMenu", new AbstractAction() {
            /** Срабатывание действия (кнопка, Enter, клавиша или таймер): выполняет команду этого слушателя. */
            @Override
            public void actionPerformed(ActionEvent e) {
                int viewRow = getSelectedRow();
                if (viewRow < 0) {
                    return;
                }
                TableItem item = model.itemAt(convertRowIndexToModel(viewRow));
                if (item.isRow()) {
                    Rectangle cell = getCellRect(viewRow, ForecastTableModel.COL_TITLE, true);
                    handler.showContextMenu(item.row(), ForecastTable.this, cell.x + 12, cell.y + cell.height);
                }
            }
        });
    }

    // ------------------------------------------------------------------ данные и выделение

    /**
     * Модель таблицы.
     *
     * @return модель
     */
    public ForecastTableModel forecastModel() {
        return model;
    }

    /**
     * Заменяет строки, сохраняя выделение по идентификатору строки.
     *
     * @param items    строки
     * @param currency валюта плана
     * @param cushion  подушка безопасности
     */
    public void setItems(List<TableItem> items, String currency, Money cushion) {
        String selected = selectedRowId().orElse(null);
        model.setItems(items, currency, cushion);
        if (selected != null) {
            int index = model.indexOf(selected);
            if (index >= 0) {
                int viewRow = convertRowIndexToView(index);
                getSelectionModel().setSelectionInterval(viewRow, viewRow);
            }
        }
    }

    /**
     * Выделенная строка таблицы.
     *
     * @return строка или пусто
     */
    public Optional<TableItem> selectedItem() {
        int viewRow = getSelectedRow();
        return viewRow < 0 ? Optional.empty() : Optional.of(model.itemAt(convertRowIndexToModel(viewRow)));
    }

    /**
     * Идентификатор выделенного события прогноза.
     *
     * @return идентификатор или пусто, если выделена служебная строка или ничего
     */
    public Optional<String> selectedRowId() {
        return selectedItem().filter(TableItem::isRow).map(TableItem::rowId);
    }

    /**
     * Выделяет строку по идентификатору и прокручивает к ней.
     *
     * @param rowId идентификатор строки
     * @return {@code true}, если строка есть в таблице
     */
    public boolean selectRowId(String rowId) {
        int index = model.indexOf(rowId);
        if (index < 0) {
            return false;
        }
        int viewRow = convertRowIndexToView(index);
        getSelectionModel().setSelectionInterval(viewRow, viewRow);
        scrollRectToVisible(getCellRect(viewRow, 0, true));
        return true;
    }

    /**
     * Прямоугольник ячейки суммы события в координатах таблицы.
     *
     * @param rowId идентификатор строки
     * @return прямоугольник или пусто, если строки нет
     */
    public Optional<Rectangle> amountCell(String rowId) {
        int index = model.indexOf(rowId);
        if (index < 0) {
            return Optional.empty();
        }
        TableItem item = model.itemAt(index);
        int column = item.isRow() && item.row().isExpense() ? ForecastTableModel.COL_EXPENSE : ForecastTableModel.COL_INCOME;
        int viewRow = convertRowIndexToView(index);
        Rectangle cell = getCellRect(viewRow, convertColumnIndexToView(column), true);
        scrollRectToVisible(cell);
        return Optional.of(cell);
    }

    // ------------------------------------------------------------------ подсказки

    /**
     * Подсказка для ячейки под курсором (у каждой строки и колонки своя).
     *
     * @param event событие мыши с координатами курсора
     * @return текст подсказки или {@code null} вне строк таблицы
     */
    // JavaFX: Tooltip на ячейке → Swing: JTable.getToolTipText(MouseEvent) → Web: title ячейки
    @Override
    public String getToolTipText(MouseEvent event) {
        Point p = event.getPoint();
        int viewRow = rowAtPoint(p);
        int viewColumn = columnAtPoint(p);
        if (viewRow < 0 || viewColumn < 0) {
            return null;
        }
        TableItem item = model.itemAt(convertRowIndexToModel(viewRow));
        int column = convertColumnIndexToModel(viewColumn);
        String text = switch (item.type()) {
            case PAST_HEADER -> "События раньше сегодняшнего дня. Щёлкните, чтобы " + (item.expanded() ? "свернуть" : "показать") + " их.";
            case MONTH_TOTAL -> DateFormats.monthTitle(item.month()) + ": доходы " + item.totals().income().format(model.currency())
                    + ", расходы " + item.totals().expense().format(model.currency()) + ", итог " + item.totals().net().formatSigned()
                    + "\nБаланс на конец месяца: " + item.totals().closingBalance().format(model.currency());
            case ROW -> rowTooltip(item.row(), column);
        };
        return text == null || text.isBlank() ? null : "<html>" + ru.cashprediction.swing.dialog.SwingText.escape(text).replace("\n", "<br>") + "</html>";
    }

    private String rowTooltip(ForecastRow row, int column) {
        String currency = model.currency();
        return switch (column) {
            case ForecastTableModel.COL_DATE, ForecastTableModel.COL_DAY -> RuText.weekdayFull(row.date().getDayOfWeek()) + ", "
                    + DateFormats.ru(row.date()) + (row.originalDate().equals(row.date()) ? "" : "\nПо правилу: " + DateFormats.ru(row.originalDate()));
            case ForecastTableModel.COL_TITLE, ForecastTableModel.COL_CATEGORY -> row.title() + " - " + row.origin().title()
                    + (row.note().isBlank() ? "" : "\nЗаметка: " + row.note())
                    + (row.origin() == Origin.RULE ? "\nДвойной щелчок - изменить правило, правая кнопка - действия с событием" : "");
            case ForecastTableModel.COL_INCOME, ForecastTableModel.COL_EXPENSE -> row.origin() == Origin.START ? null
                    : row.amount().format(currency) + (row.origin() == Origin.RULE ? "\nДвойной щелчок - быстрая правка суммы этого события" : "");
            case ForecastTableModel.COL_BALANCE -> "Баланс после события: " + row.balanceAfter().format(currency)
                    + (row.balanceAfter().isNegative() ? "\nБаланс ниже нуля!"
                    : model.cushion().isPositive() && row.balanceAfter().compareTo(model.cushion()) < 0
                    ? "\nНиже подушки безопасности " + model.cushion().format(currency) : "");
            case ForecastTableModel.COL_MARKS -> ForecastTableModel.marksExplained(row);
            default -> null;
        };
    }
}
