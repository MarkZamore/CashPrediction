package ru.cashprediction.swing.menu;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.LocalDate;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.swing.action.SwingActions;

/**
 * Контекстные меню главного окна: строка таблицы, график и карточка сводки (раздел 6 плана «Контекстные меню»).
 *
 * <p>Swing-аналог JavaFX {@code ForecastContextMenu}: каждое меню — {@link JPopupMenu}, собираемый заново при каждом
 * вызове (пункты зависят от строки: у разовой операции нет «Скорректировать событие»). Показывает меню вызывающий —
 * из {@code MouseAdapter} по {@code isPopupTrigger()} или по клавише контекстного меню.</p>
 *
 * <p>Класс без состояния; методы вызываются в потоке EDT.</p>
 */
// JavaFX: ContextMenu → Swing: JPopupMenu → Web: <ul class="context-menu">
public final class ForecastPopupMenu {

    private ForecastPopupMenu() {
    }

    /**
     * Меню строки таблицы прогноза.
     *
     * @param actions фасад команд
     * @param row     событие прогноза
     * @return меню
     */
    public static JPopupMenu forRow(SwingActions actions, ForecastRow row) {
        JPopupMenu menu = new JPopupMenu();
        String rowId = row.rowId();
        boolean rule = row.origin() == Origin.RULE && row.ruleId() != null;
        boolean oneTime = row.origin() == Origin.ONE_TIME && row.txId() != null;
        // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
        menu.add(item("Изменить…", "Открыть редактор операции (Enter)", () -> actions.editRow(rowId)));
        if (rule) {
            row.occurrenceKey().ifPresent(key ->
                    menu.add(item("Быстрая правка суммы…", "Изменить сумму только этого события прямо в таблице",
                            () -> actions.quickEdit(key))));
            menu.add(item("Скорректировать событие…", "Пропустить, изменить сумму, перенести или заменить это событие (Ctrl+J)",
                    () -> actions.adjustRow(rowId)));
            JMenuItem skip = item("Пропустить", "Событие не произойдёт; правило не меняется", () -> actions.skipRow(rowId));
            skip.setEnabled(!row.flags().skipped());
            menu.add(skip);
            JMenuItem reset = item("Вернуть как по правилу", "Удалить корректировку этого события", () -> actions.resetRow(rowId));
            reset.setEnabled(row.flags().adjusted() || row.flags().skipped());
            menu.add(reset);
        }
        // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.addSeparator() (JPopupMenu.Separator) → Web: <li role="separator"><hr>
        menu.addSeparator();
        menu.add(item("Добавить разовую на " + DateFormats.ru(row.date()) + "…", "Новая разовая операция на эту дату",
                () -> actions.addOneTime(row.date(), null)));
        if (rule) {
            menu.add(item("Перейти к правилу…", "Открыть регулярную операцию, к которой относится событие",
                    () -> actions.editRule(row.ruleId())));
            menu.add(item("Отключить правило", "Правило останется в плане, но перестанет влиять на прогноз (Ctrl+Z — отменить)",
                    () -> actions.disableRule(row.ruleId())));
        }
        menu.addSeparator();
        if (rule || oneTime) {
            menu.add(item("Удалить…", "Удалить операцию из плана с подтверждением (Delete)", () -> actions.deleteRow(rowId)));
        }
        menu.add(item("Копировать", "Скопировать строку в буфер обмена", () -> actions.copyRow(rowId)));
        return menu;
    }

    /**
     * Меню графика.
     *
     * @param actions  фасад команд
     * @param models   общие модели флажков вида
     * @param commands команды главного окна
     * @param date     дата под курсором
     * @return меню
     */
    public static JPopupMenu forChart(SwingActions actions, ViewModels models, MainCommands commands, LocalDate date) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Показать в таблице с " + DateFormats.ru(date), "Переключиться на таблицу и перейти к этой дате",
                () -> commands.showTableAt(date)));
        menu.add(item("Добавить разовую на " + DateFormats.ru(date) + "…", "Новая разовая операция на эту дату",
                () -> actions.addOneTime(date, null)));
        menu.add(item("Сохранить график PNG…", "Сохранить изображение графика в файл", actions::saveChartPng));
        menu.addSeparator();
        menu.add(check("Маркеры событий", "Показывать события на линии баланса", models, ViewModels.CHART_MARKERS));
        menu.add(check("Столбцы по месяцам", "Показывать доходы и расходы месяцев столбцами", models, ViewModels.CHART_BARS));
        return menu;
    }

    /**
     * Меню карточки сводки.
     *
     * @param actions  фасад команд
     * @param models   общие модели флажков вида
     * @param commands команды главного окна
     * @param title    заголовок карточки
     * @param value    значение карточки
     * @param date     дата карточки или {@code null}
     * @return меню
     */
    public static JPopupMenu forCard(SwingActions actions, ViewModels models, MainCommands commands, String title, String value,
                                     LocalDate date) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem show = item(date == null ? "Показать в таблице" : "Показать в таблице с " + DateFormats.ru(date),
                "Переключиться на таблицу и перейти к дате карточки", () -> commands.showTableAt(date));
        show.setEnabled(date != null);
        menu.add(show);
        menu.add(item("Копировать значение", "Скопировать «" + title + ": " + value + "» в буфер обмена",
                () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(title + ": " + value), null)));
        menu.add(item("Калькулятор цели…", "Когда накопится нужная сумма и сколько откладывать (Ctrl+G)", actions::goalCalculator));
        menu.addSeparator();
        menu.add(check("Панель сводки", "Показывать карточки сводки над таблицей", models, ViewModels.SUMMARY_PANEL));
        return menu;
    }

    /**
     * Пункт меню с подсказкой.
     *
     * @param text    текст
     * @param tooltip подсказка
     * @param action  команда
     * @return пункт
     */
    static JMenuItem item(String text, String tooltip, Runnable action) {
        // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
        JMenuItem item = new JMenuItem(text);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        item.setToolTipText(tooltip);
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * Флажок, разделяющий модель с пунктом меню «Вид».
     *
     * @param text    текст
     * @param tooltip подсказка
     * @param models  общие модели
     * @param key     ключ флажка
     * @return пункт
     */
    static JCheckBoxMenuItem check(String text, String tooltip, ViewModels models, String key) {
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text);
        item.setModel(models.filter(key));
        item.setToolTipText(tooltip);
        return item;
    }
}
