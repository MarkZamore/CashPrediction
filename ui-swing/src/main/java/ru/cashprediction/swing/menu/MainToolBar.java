package ru.cashprediction.swing.menu;

import java.awt.Dimension;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.swing.action.SwingActions;

/**
 * Панель инструментов главного окна (раздел 6 плана «Тулбар»): «Добавить доход» со списком, «Период ▾»,
 * «Что-если ▾», переключатель «Таблица/График», поле фильтра и «Сохранить». У каждого элемента есть подсказка.
 *
 * <p>Пункты «Период ▾» и кнопки «Таблица/График» разделяют модели с пунктами меню «Вид» ({@link ViewModels}), как
 * общая {@code ToggleGroup} в JavaFX-клиенте. Класс используется только в потоке EDT.</p>
 */
public final class MainToolBar {

    private final JToolBar toolBar = new JToolBar("Панель инструментов");
    private final JTextField filterField = new JTextField(18);
    /** Текст фильтра меняется программно (синхронизация с видом): не ввод пользователя. */
    private boolean quiet;

    /**
     * Строит панель.
     *
     * @param actions      фасад команд
     * @param models       общие модели переключателей
     * @param onFilterText новый текст фильтра (ввод пользователя)
     */
    public MainToolBar(SwingActions actions, ViewModels models, Consumer<String> onFilterText) {
        Objects.requireNonNull(onFilterText, "onFilterText");
        toolBar.setFloatable(false);
        toolBar.setRollover(true);

        // JavaFX: SplitMenuButton → Swing: SwingSplitMenuButton (JButton + JButton("▾") с JPopupMenu) → Web: две кнопки + меню
        SwingSplitMenuButton add = new SwingSplitMenuButton("Добавить доход", "Новая регулярная операция: доход (Ctrl+I)",
                "Другие операции: расход, разовая, корректировка события", () -> actions.addRule(Kind.INCOME));
        add.popup().add(ForecastPopupMenu.item("Добавить расход…", "Новая регулярная операция: расход (Ctrl+E)",
                () -> actions.addRule(Kind.EXPENSE)));
        add.popup().add(ForecastPopupMenu.item("Разовая операция…", "Доход или расход один раз (Ctrl+T)", actions::addOneTime));
        add.popup().add(ForecastPopupMenu.item("Скорректировать событие…", "Выделенное событие правила (Ctrl+J)",
                actions::adjustSelected));
        toolBar.add(add);
        toolBar.addSeparator();

        // JavaFX: MenuButton → Swing: SwingMenuButton (JButton + JPopupMenu.show(btn, 0, h)) → Web: <button> + <ul role="menu">
        SwingMenuButton period = new SwingMenuButton("Период", "Какой промежуток показывать в таблице и на графике (план не меняется)");
        for (PeriodChoice choice : PeriodChoice.values()) {
            // JavaFX: RadioMenuItem (общая ToggleGroup с меню «Вид») → Swing: JRadioButtonMenuItem с общим ButtonModel → Web: role="menuitemradio"
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(choice.label());
            item.setModel(models.period(choice));
            item.setToolTipText("Показывать " + (choice == PeriodChoice.ALL ? "весь горизонт" : "ближайшие " + choice.label()));
            period.popup().add(item);
        }
        period.popup().addSeparator();
        period.popup().add(ForecastPopupMenu.item("Горизонт плана…", "Сколько месяцев считать прогноз (меняет план)",
                actions::customMonths));
        toolBar.add(period);

        SwingMenuButton whatIf = new SwingMenuButton("Что-если", "Доходы −10 %, расходы +10 %, дополнительная экономия - без изменения плана");
        AppMenuBar.fillWhatIf(whatIf.popup(), actions, models);
        toolBar.add(whatIf);
        toolBar.addSeparator();

        JToggleButton table = new JToggleButton("Таблица");
        table.setModel(models.mode(ViewMode.TABLE));
        table.setFocusable(false);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        table.setToolTipText("Таблица событий с балансом (Ctrl+1)");
        JToggleButton chart = new JToggleButton("График");
        chart.setModel(models.mode(ViewMode.CHART));
        chart.setFocusable(false);
        chart.setToolTipText("График баланса во времени (Ctrl+2)");
        toolBar.add(table);
        toolBar.add(chart);
        toolBar.addSeparator();

        JLabel filterLabel = new JLabel("Фильтр: ");
        filterLabel.setLabelFor(filterField);
        filterField.setMaximumSize(new Dimension(260, filterField.getPreferredSize().height));
        filterField.setToolTipText("Показывать строки, где название, категория или заметка содержит текст (Ctrl+F)");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            /** Символы вставлены в поле: обрабатывается как любое изменение текста. */
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            /** Символы удалены из поля: обрабатывается как любое изменение текста. */
            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            /** Изменились атрибуты текста (у простых полей не приходит): обрабатывается единообразно. */
            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                if (!quiet) {
                    onFilterText.accept(filterField.getText());
                }
            }
        });
        JButton clear = new JButton("✕");
        clear.setFocusable(false);
        clear.setToolTipText("Очистить фильтр");
        clear.addActionListener(e -> filterField.setText(""));
        toolBar.add(filterLabel);
        toolBar.add(filterField);
        toolBar.add(clear);
        toolBar.add(Box.createHorizontalGlue());

        JButton save = new JButton("Сохранить");
        save.setFocusable(false);
        save.setToolTipText("Сохранить план в файл .md (Ctrl+S)");
        save.addActionListener(e -> actions.save());
        toolBar.add(save);
    }

    /**
     * Панель для размещения в окне.
     *
     * @return панель инструментов
     */
    public JToolBar toolBar() {
        return toolBar;
    }

    /**
     * Поле фильтра (Ctrl+F переводит в него фокус).
     *
     * @return поле
     */
    public JTextField filterField() {
        return filterField;
    }

    /**
     * Ставит текст фильтра без уведомления (восстановление или синхронизация с видом).
     *
     * @param text текст
     */
    public void setFilterTextQuietly(String text) {
        String value = text == null ? "" : text;
        if (value.equals(filterField.getText())) {
            return;
        }
        quiet = true;
        try {
            filterField.setText(value);
        } finally {
            quiet = false;
        }
    }
}
