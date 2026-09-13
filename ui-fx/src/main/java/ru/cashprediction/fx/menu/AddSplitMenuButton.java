package ru.cashprediction.fx.menu;

import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.Tooltip;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.fx.ShellContext;
import ru.cashprediction.fx.dialog.OpenRequest;

import java.util.Objects;

/**
 * Кнопка панели инструментов «Добавить доход» со списком других вариантов добавления.
 *
 * <p>Основная часть кнопки сразу открывает редактор регулярного дохода (самое частое действие), стрелка —
 * список: расход, разовая операция, корректировка выбранного события. Пункт корректировки доступен, только
 * если в таблице выбрано событие регулярной операции.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: SplitMenuButton → Swing: SwingSplitMenuButton (JPanel из JButton и JButton «▾» с JPopupMenu) → Web: две кнопки + меню
public final class AddSplitMenuButton extends SplitMenuButton {

    /**
     * Создаёт кнопку.
     *
     * @param shell оболочка приложения
     */
    public AddSplitMenuButton(ShellContext shell) {
        Objects.requireNonNull(shell, "shell");
        setText("Добавить доход");
        setOnAction(e -> shell.actions().addRule(Kind.INCOME, OpenRequest.fromMain()));
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        setTooltip(new Tooltip("Новый регулярный доход (Ctrl+I). Стрелка — расход, разовая операция, корректировка события"));

        // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
        MenuItem expense = new MenuItem("Добавить расход…");
        expense.setOnAction(e -> shell.actions().addRule(Kind.EXPENSE, OpenRequest.fromMain()));
        // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
        MenuItem oneTime = new MenuItem("Разовая операция…");
        oneTime.setOnAction(e -> shell.actions().addOneTime(null, Kind.INCOME, OpenRequest.fromMain()));
        // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
        MenuItem adjust = new MenuItem("Скорректировать выбранное событие…");
        adjust.setOnAction(e -> shell.actions().adjustRow(shell.selectedRowId().orElse("")));
        // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.addSeparator() → Web: <li role="separator"><hr>
        getItems().addAll(expense, oneTime, new SeparatorMenuItem(), adjust);

        // Доступность корректировки определяется в момент открытия списка: выделение в таблице меняется постоянно.
        addEventHandler(ON_SHOWING, e -> adjust.setDisable(shell.selectedRowId()
                .filter(id -> id.contains("@") && !id.startsWith("whatif@")).isEmpty()));
    }
}
