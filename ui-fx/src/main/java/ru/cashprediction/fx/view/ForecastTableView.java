package ru.cashprediction.fx.view;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.util.Duration;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.stage.Window;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.ForecastSummary;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.markdown.RuFormats;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.fx.ShellContext;
import ru.cashprediction.fx.menu.ForecastContextMenu;
import ru.cashprediction.fx.popup.QuickEditPopup;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Вертикальная таблица событий прогноза с балансом после каждого события.
 *
 * <p>Колонки: Дата | День | Операция | Категория | Доход | Расход | Баланс | Отметки. Доход зелёный, расход красный,
 * баланс жирный; фон строки светло-красный при отрицательном балансе и светло-жёлтый ниже подушки; прошедшие события
 * серые, пропущенные зачёркнуты (оформление — {@code styles.css}). Отметки: ✎ сумма изменена, → перенесено,
 * ⇄ сдвиг с выходного, ≡ разовая, «пропуск» — событие пропущено, Δ — строка режима «что-если».
 * При «Вид → Итоги по месяцам» после каждого месяца вставляется строка итога.</p>
 *
 * <p>Взаимодействие: двойной щелчок по сумме события правила открывает {@link QuickEditPopup}, двойной щелчок
 * по остальным ячейкам и Enter — «Изменить…», Delete — «Удалить…», правая кнопка — контекстное меню строки
 * ({@link ContextMenuEvent}). Смена выделения сообщается рекордеру: выделенная строка входит в снимок.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class ForecastTableView extends TableView<TableEntry> {

    /** Классы оформления строк; снимаются перед каждым обновлением строки. */
    private static final List<String> ROW_CLASSES = List.of("negative", "below-cushion", "past", "skipped", "total-row", "what-if");
    /** Классы оформления ячеек. */
    private static final List<String> CELL_CLASSES = List.of("income-cell", "expense-cell", "balance-cell", "marks-cell");

    private final ShellContext shell;
    private final TableColumn<TableEntry, TableEntry> dateColumn;
    private final TableColumn<TableEntry, TableEntry> dayColumn;
    private final TableColumn<TableEntry, TableEntry> titleColumn;
    private final TableColumn<TableEntry, TableEntry> categoryColumn;
    private final TableColumn<TableEntry, TableEntry> incomeColumn;
    private final TableColumn<TableEntry, TableEntry> expenseColumn;
    private final TableColumn<TableEntry, TableEntry> balanceColumn;
    private final TableColumn<TableEntry, TableEntry> marksColumn;
    private final Map<String, Integer> indexById = new HashMap<>();
    private final Label placeholder = new Label("Нет строк: измените фильтр или период");
    private ContextMenu contextMenu;
    private QuickEditPopup quickEdit;
    private int eventRows;

    /**
     * Создаёт таблицу.
     *
     * @param shell оболочка приложения (фасад команд, документ, рекордер)
     */
    public ForecastTableView(ShellContext shell) {
        this.shell = Objects.requireNonNull(shell, "shell");
        dateColumn = column("Дата", 92, this::dateText, null, false);
        dayColumn = column("День", 46, e -> e.isTotal() ? "" : RuText.weekdayShort(e.row().date().getDayOfWeek()), null, false);
        titleColumn = column("Операция", 220, this::titleText, null, false);
        categoryColumn = column("Категория", 130, this::categoryText, null, false);
        incomeColumn = column("Доход", 120, this::incomeText, "income-cell", true);
        expenseColumn = column("Расход", 120, this::expenseText, "expense-cell", true);
        balanceColumn = column("Баланс", 130, this::balanceText, "balance-cell", false);
        marksColumn = column("Отметки", 90, this::marksText, "marks-cell", false);
        getColumns().setAll(List.of(dateColumn, dayColumn, titleColumn, categoryColumn, incomeColumn, expenseColumn,
                balanceColumn, marksColumn));
        setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        setPlaceholder(placeholder);
        getStyleClass().add("forecast-table");
        setRowFactory(table -> new EntryRow());

        // Выделенная строка входит в снимок сессии: после сбоя фокус вернётся на неё.
        getSelectionModel().selectedItemProperty().addListener((o, a, b) -> shell.recorder().touch());

        setOnKeyPressed(e -> {
            Optional<String> id = selectedRowId();
            if (e.getCode() == KeyCode.ENTER && id.isPresent()) {
                e.consume();
                shell.actions().editRow(id.get());
            } else if (e.getCode() == KeyCode.DELETE && id.isPresent()) {
                e.consume();
                shell.actions().deleteRow(id.get());
            }
        });

        // JavaFX: ContextMenuEvent → Swing: MouseAdapter.isPopupTrigger() в mousePressed и mouseReleased → Web: contextmenu + preventDefault
        setOnContextMenuRequested(this::showContextMenu);
    }

    // ------------------------------------------------------------------ данные

    /** Перечитывает строки из документа: видимые строки периода и, при необходимости, итоги месяцев. */
    public void refresh() {
        PlanDocument document = shell.document();
        String selected = Optional.ofNullable(getSelectionModel().getSelectedItem()).map(TableEntry::id).orElse(null);
        List<ForecastRow> rows;
        ForecastSummary summary;
        try {
            rows = document.visibleRows();
            summary = document.forecast().summary();
        } catch (IllegalStateException e) {
            // Слишком длинный горизонт или слишком частое правило: движок отказался считать — показываем причину.
            placeholder.setText("Прогноз не рассчитан: " + e.getMessage());
            getItems().clear();
            indexById.clear();
            eventRows = 0;
            return;
        }
        placeholder.setText("Нет строк: измените фильтр или период");
        boolean totals = document.viewState().monthTotals();
        List<TableEntry> entries = new ArrayList<>(rows.size() + 32);
        YearMonth current = null;
        for (ForecastRow row : rows) {
            YearMonth month = YearMonth.from(row.date());
            if (totals && current != null && !month.equals(current)) {
                addTotal(entries, summary, current);
            }
            entries.add(TableEntry.of(row));
            current = month;
        }
        if (totals && current != null) {
            addTotal(entries, summary, current);
        }
        getItems().setAll(entries);
        indexById.clear();
        for (int i = 0; i < entries.size(); i++) {
            indexById.put(entries.get(i).id(), i);
        }
        eventRows = rows.size();
        if (selected != null && indexById.containsKey(selected)) {
            getSelectionModel().clearAndSelect(indexById.get(selected));
        }
    }

    private static void addTotal(List<TableEntry> entries, ForecastSummary summary, YearMonth month) {
        MonthTotals totals = summary.byMonth().get(month);
        if (totals != null) {
            entries.add(TableEntry.total(month, totals));
        }
    }

    /**
     * Число строк событий (без строк итогов).
     *
     * @return число строк
     */
    public int eventRowCount() {
        return eventRows;
    }

    /**
     * Идентификатор выделенной строки события.
     *
     * @return {@code r2@2026-10-01}, {@code t1}, {@code start} или пусто (ничего не выбрано или выбран итог месяца)
     */
    public Optional<String> selectedRowId() {
        TableEntry entry = getSelectionModel().getSelectedItem();
        return entry == null || entry.isTotal() ? Optional.empty() : Optional.of(entry.row().rowId());
    }

    /**
     * Выделяет строку и прокручивает таблицу к ней.
     *
     * @param rowId идентификатор строки
     * @return {@code true}, если строка есть в таблице
     */
    public boolean select(String rowId) {
        Integer index = rowId == null ? null : indexById.get(rowId);
        if (index == null) {
            return false;
        }
        getSelectionModel().clearAndSelect(index);
        getFocusModel().focus(index);
        scrollTo(Math.max(0, index - 3));
        return true;
    }

    /**
     * Выделяет первую строку события не раньше даты.
     *
     * @param date дата
     * @return {@code true}, если такая строка нашлась
     */
    public boolean selectFirstFrom(LocalDate date) {
        List<TableEntry> items = getItems();
        for (TableEntry entry : items) {
            if (!entry.isTotal() && !entry.row().date().isBefore(date)) {
                return select(entry.id());
            }
        }
        return false;
    }

    /** Снимает выделение (открыт другой план). */
    public void clearSelection() {
        getSelectionModel().clearSelection();
    }

    // ------------------------------------------------------------------ быстрая правка суммы

    /**
     * Открывает быструю правку суммы события правила.
     *
     * @param rowId   идентификатор строки ({@code r1@2026-10-05})
     * @param restore состояние из снимка или {@code null}
     * @param onShown кому сообщить о показе; {@code null} — окно регистрируется в рекордере само
     * @return показанное окно
     * @throws IllegalArgumentException если строки нет в таблице или это не событие правила
     */
    public QuickEditPopup openQuickEdit(String rowId, WindowState restore, Consumer<StatefulWindow> onShown) {
        Integer index = indexById.get(rowId);
        if (index == null) {
            throw new IllegalArgumentException("Строки «" + rowId + "» нет в таблице (проверьте период и фильтры)");
        }
        return openQuickEdit(index, restore, onShown);
    }

    /**
     * Открывает быструю правку по сохранённому состоянию окна ({@code QuickEditOpener} для восстановления сессии).
     *
     * @param state    состояние: контекст {@code ruleId} и {@code originalDate}, поле {@code amount}
     * @param onShown  колбэк показа
     * @param onFailed колбэк отказа
     */
    public void openQuickEdit(WindowState state, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        String ruleId = state.contextValue(WindowType.CONTEXT_RULE_ID);
        String date = state.contextValue(WindowType.CONTEXT_ORIGINAL_DATE);
        try {
            OccurrenceKey key = new OccurrenceKey(new ru.cashprediction.core.model.RuleId(ruleId), LocalDate.parse(date));
            int index = -1;
            List<TableEntry> items = getItems();
            for (int i = 0; i < items.size(); i++) {
                TableEntry entry = items.get(i);
                if (!entry.isTotal() && entry.row().occurrenceKey().filter(key::equals).isPresent()) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                onFailed.accept("события " + ruleId + " от " + date + " нет в таблице (проверьте период и фильтры)");
                return;
            }
            openQuickEdit(index, state, onShown);
        } catch (RuntimeException e) {
            onFailed.accept(Objects.requireNonNullElse(e.getMessage(), e.toString()));
        }
    }

    /**
     * Открытая сейчас быстрая правка.
     *
     * @return окно или пусто
     */
    public Optional<QuickEditPopup> quickEditPopup() {
        return quickEdit != null && quickEdit.isShowing() ? Optional.of(quickEdit) : Optional.empty();
    }

    private QuickEditPopup openQuickEdit(int index, WindowState restore, Consumer<StatefulWindow> onShown) {
        TableEntry entry = getItems().get(index);
        if (entry.isTotal() || entry.row().origin() != Origin.RULE || entry.row().occurrenceKey().isEmpty()) {
            throw new IllegalArgumentException("Быстрая правка суммы доступна только для событий регулярных операций");
        }
        if (quickEdit != null && quickEdit.isShowing()) {
            quickEdit.hide();
        }
        ForecastRow row = entry.row();
        select(entry.id());
        // Строка могла быть вне экрана: после прокрутки нужен проход раскладки, чтобы узнать положение ячейки.
        layout();
        double[] position = cellScreenPosition(index);
        Plan plan = shell.document().plan();
        QuickEditPopup popup = new QuickEditPopup(shell.actions(), shell.recorder(), row.occurrenceKey().get(), row.title(),
                row.amount(), plan.currency());
        popup.showAt(shell.ownerWindow(), position[0], position[1], restore, onShown);
        quickEdit = popup;
        return popup;
    }

    private double[] cellScreenPosition(int index) {
        for (Node node : lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row && row.getIndex() == index && row.isVisible()) {
                Bounds bounds = row.localToScreen(row.getBoundsInLocal());
                if (bounds != null) {
                    double offset = dateColumn.getWidth() + dayColumn.getWidth() + titleColumn.getWidth()
                            + categoryColumn.getWidth();
                    return new double[]{bounds.getMinX() + offset, bounds.getMaxY()};
                }
            }
        }
        // Таблица скрыта (открыт график) или строка не отрисована: ставим окно посреди главного окна.
        Window owner = shell.ownerWindow();
        return new double[]{owner.getX() + owner.getWidth() / 2 - 150, owner.getY() + owner.getHeight() / 2 - 50};
    }

    // ------------------------------------------------------------------ контекстное меню

    private void showContextMenu(ContextMenuEvent event) {
        TableRow<?> row = rowOf(event.getPickResult() == null ? null : event.getPickResult().getIntersectedNode());
        TableEntry entry;
        if (row != null && row.getItem() instanceof TableEntry picked) {
            entry = picked;
            getSelectionModel().clearAndSelect(row.getIndex());
        } else {
            entry = getSelectionModel().getSelectedItem();
        }
        if (entry == null) {
            return;
        }
        if (contextMenu != null) {
            contextMenu.hide();
        }
        // JavaFX: ContextMenu → Swing: JPopupMenu (setComponentPopupMenu) → Web: <ul class="context-menu">
        contextMenu = ForecastContextMenu.forRow(shell, entry, () -> {
            try {
                openQuickEdit(entry.id(), null, null);
            } catch (IllegalArgumentException e) {
                shell.actions().showError("Быстрая правка недоступна", e.getMessage());
            }
        });
        contextMenu.show(this, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    private static TableRow<?> rowOf(Node node) {
        Node current = node;
        while (current != null && !(current instanceof TableRow<?>)) {
            current = current.getParent();
        }
        return (TableRow<?>) current;
    }

    // ------------------------------------------------------------------ тексты ячеек

    private String dateText(TableEntry e) {
        return e.isTotal() ? "" : DateFormats.ru(e.row().date());
    }

    private String titleText(TableEntry e) {
        return e.isTotal() ? DateFormats.monthTitle(e.month()) + " - итог" : e.row().title();
    }

    private String categoryText(TableEntry e) {
        return e.isTotal() ? "за месяц " + e.totals().net().formatSigned() : e.row().category();
    }

    private String incomeText(TableEntry e) {
        if (e.isTotal()) {
            return e.totals().income().format();
        }
        ForecastRow row = e.row();
        if (row.origin() == Origin.START || !row.isIncome()) {
            return "";
        }
        return row.flags().skipped() ? "-" : row.amount().abs().format();
    }

    private String expenseText(TableEntry e) {
        if (e.isTotal()) {
            return e.totals().expense().format();
        }
        ForecastRow row = e.row();
        if (row.origin() == Origin.START || !row.isExpense()) {
            return "";
        }
        return row.flags().skipped() ? "-" : row.amount().abs().format();
    }

    private String balanceText(TableEntry e) {
        return e.isTotal() ? e.totals().closingBalance().format() : e.row().balanceAfter().format();
    }

    private String marksText(TableEntry e) {
        if (e.isTotal()) {
            return "";
        }
        ForecastRow row = e.row();
        Flags flags = row.flags();
        StringBuilder marks = new StringBuilder();
        if (flags.amountChanged()) {
            marks.append("✎ ");
        }
        if (flags.moved()) {
            marks.append("→ ");
        }
        if (flags.shifted()) {
            marks.append("⇄ ");
        }
        if (row.origin() == Origin.ONE_TIME) {
            marks.append("≡ ");
        }
        if (flags.whatIf() || row.origin() == Origin.WHAT_IF) {
            marks.append("Δ ");
        }
        if (flags.skipped()) {
            marks.append("пропуск");
        }
        return marks.toString().strip();
    }

    private String tooltipText(TableEntry e) {
        Plan plan = shell.document().plan();
        if (e.isTotal()) {
            MonthTotals t = e.totals();
            return DateFormats.monthTitle(e.month()) + "\nДоходы: " + t.income().format(plan.currency())
                    + "\nРасходы: " + t.expense().format(plan.currency())
                    + "\nИтог месяца: " + t.net().formatSigned() + "\nБаланс на конец месяца: "
                    + t.closingBalance().format(plan.currency());
        }
        ForecastRow row = e.row();
        StringBuilder text = new StringBuilder(row.title());
        switch (row.origin()) {
            case RULE -> plan.findRule(row.ruleId()).ifPresent(rule -> text.append("\nРегулярная операция ")
                    .append(rule.id().value()).append(": ").append(RuFormats.formatRecurrence(rule.recurrence())));
            case ONE_TIME -> text.append("\nРазовая операция ").append(row.txId().value());
            case START -> text.append("\nНачальный баланс плана");
            case WHAT_IF -> text.append("\nГипотетическая экономия режима «что-если»");
        }
        if (row.originalDate() != null && !row.originalDate().equals(row.date())) {
            text.append("\nПо правилу: ").append(DateFormats.ru(row.originalDate()))
                    .append(", фактически: ").append(DateFormats.ru(row.date()));
        }
        Flags flags = row.flags();
        if (flags.amountChanged()) {
            text.append("\n✎ сумма изменена корректировкой");
        }
        if (flags.moved()) {
            text.append("\n→ событие перенесено");
        }
        if (flags.shifted()) {
            text.append("\n⇄ сдвинуто с выходного дня");
        }
        if (flags.skipped()) {
            text.append("\nсобытие пропущено");
        }
        if (flags.past()) {
            text.append("\nсобытие уже в прошлом");
        }
        if (row.origin() != Origin.START) {
            text.append("\nСумма: ").append(row.amount().formatSigned()).append(' ').append(plan.currency());
        }
        text.append("\nБаланс после: ").append(row.balanceAfter().format(plan.currency()));
        if (!row.note().isBlank()) {
            text.append("\nЗаметка: ").append(row.note());
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ фабрики

    private TableColumn<TableEntry, TableEntry> column(String title, double width, Function<TableEntry, String> text,
                                                       String styleClass, boolean amount) {
        TableColumn<TableEntry, TableEntry> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setReorderable(false);
        column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        column.setCellFactory(c -> new EntryCell(text, styleClass, amount));
        return column;
    }

    /** Ячейка: текст по функции, класс оформления, подсказка и двойной щелчок. */
    private final class EntryCell extends TableCell<TableEntry, TableEntry> {

        private final Function<TableEntry, String> text;
        private final String styleClass;
        private final boolean amount;
        // JavaFX: Tooltip → Swing: getToolTipText(MouseEvent) у JTable → Web: title у <td>
        private final Tooltip tooltip = new Tooltip();

        EntryCell(Function<TableEntry, String> text, String styleClass, boolean amount) {
            this.text = text;
            this.styleClass = styleClass;
            this.amount = amount;
            tooltip.setShowDelay(Duration.millis(600));
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(420);
            setOnMouseClicked(e -> {
                TableEntry item = getItem();
                if (item == null || e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2) {
                    return;
                }
                e.consume();
                if (amount && !item.isTotal() && item.row().origin() == Origin.RULE && !getText().isEmpty()) {
                    try {
                        openQuickEdit(item.id(), null, null);
                    } catch (IllegalArgumentException ex) {
                        shell.actions().showError("Быстрая правка недоступна", ex.getMessage());
                    }
                } else if (!item.isTotal()) {
                    shell.actions().editRow(item.row().rowId());
                }
            });
        }

        /**
         * Заполняет ячейку: текст, класс стиля и подсказку; пустая ячейка очищается.
         *
         * @param item  строка таблицы (событие или итог месяца)
         * @param empty пустая ячейка
         */
        @Override
        protected void updateItem(TableEntry item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(CELL_CLASSES);
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(text.apply(item));
            if (styleClass != null) {
                getStyleClass().add(styleClass);
            }
            tooltip.setText(tooltipText(item) + (amount && !item.isTotal() && item.row().origin() == Origin.RULE
                    ? "\n\nДвойной щелчок по сумме - быстрая правка" : ""));
            setTooltip(tooltip);
        }
    }

    /** Строка: фон по балансу и подушке, серый цвет прошедших, зачёркивание пропущенных. */
    private final class EntryRow extends TableRow<TableEntry> {

        /**
         * Назначает строке классы стиля: итог месяца, минус, ниже подушки, прошедшая, пропущенная, что-если.
         *
         * @param item  строка таблицы
         * @param empty пустая строка
         */
        @Override
        protected void updateItem(TableEntry item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(ROW_CLASSES);
            if (empty || item == null) {
                return;
            }
            Money cushion = shell.document().plan().cushion();
            Money balance = item.isTotal() ? item.totals().closingBalance() : item.row().balanceAfter();
            if (item.isTotal()) {
                getStyleClass().add("total-row");
            }
            if (balance.isNegative()) {
                getStyleClass().add("negative");
            } else if (cushion.isPositive() && balance.isLessThan(cushion)) {
                getStyleClass().add("below-cushion");
            }
            if (!item.isTotal()) {
                Flags flags = item.row().flags();
                if (flags.past()) {
                    getStyleClass().add("past");
                }
                if (flags.skipped()) {
                    getStyleClass().add("skipped");
                }
                if (item.row().origin() == Origin.WHAT_IF) {
                    getStyleClass().add("what-if");
                }
            }
        }
    }
}
