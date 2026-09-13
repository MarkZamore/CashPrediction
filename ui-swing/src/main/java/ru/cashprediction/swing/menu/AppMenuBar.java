package ru.cashprediction.swing.menu;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import javax.swing.ButtonModel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.swing.action.SwingActions;

/**
 * Строка меню главного окна: «Файл | Правка | Вид | Инструменты | Восстановление | Справка» (раздел 6 плана).
 *
 * <p>Swing-аналог JavaFX {@code AppMenuBar}. Команды с горячими клавишами — {@link JMenuItem} с
 * {@code setAccelerator}; флажки — {@link JCheckBoxMenuItem}; группы выбора — {@link JRadioButtonMenuItem} в
 * {@code ButtonGroup}; разделители — {@code JMenu.addSeparator()}; «Горизонт плана» — панель со слайдером
 * ({@link SwingSliderMenuItem}), «Откладывать доп.» — панель со спиннером ({@link SwingSpinnerMenuItem}).
 * Переключатели используют общие модели {@link ViewModels}, поэтому меню, панель инструментов и контекстные меню
 * всегда показывают одно и то же.</p>
 *
 * <p>Все пункты вызывают фасад команд {@link SwingActions}. Класс используется только в потоке EDT.</p>
 */
// JavaFX: MenuBar → Swing: JMenuBar → Web: <nav role="menubar">
public final class AppMenuBar {

    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int CTRL_SHIFT = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;

    private final SwingActions actions;
    private final ViewModels models;
    private final MainCommands commands;
    private final JMenuBar bar = new JMenuBar();
    private final JMenuItem undoItem = new JMenuItem("Отменить");
    private final JMenuItem redoItem = new JMenuItem("Повторить");
    private final SwingSliderMenuItem horizonSlider;

    /**
     * Строит меню.
     *
     * @param actions  фасад команд
     * @param models   общие модели переключателей
     * @param commands команды главного окна
     */
    public AppMenuBar(SwingActions actions, ViewModels models, MainCommands commands) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.models = Objects.requireNonNull(models, "models");
        this.commands = Objects.requireNonNull(commands, "commands");
        horizonSlider = new SwingSliderMenuItem(1, 120, months -> "Горизонт плана: " + months + " мес", actions::setHorizonMonths);
        bar.add(fileMenu());
        bar.add(editMenu());
        bar.add(viewMenu());
        bar.add(toolsMenu());
        bar.add(recoveryMenu());
        bar.add(helpMenu());
    }

    /**
     * Готовая строка меню для {@code JFrame.setJMenuBar}.
     *
     * @return строка меню
     */
    public JMenuBar menuBar() {
        return bar;
    }

    /**
     * Слайдер горизонта (главное окно ставит в него текущий горизонт плана).
     *
     * @return пункт со слайдером
     */
    public SwingSliderMenuItem horizonSlider() {
        return horizonSlider;
    }

    /**
     * Обновляет пункты «Отменить …» и «Повторить …» по истории документа.
     *
     * @param document документ плана
     */
    public void updateUndoRedo(PlanDocument document) {
        undoItem.setEnabled(document.canUndo());
        undoItem.setText(document.undoDescription().map(d -> "Отменить: " + d).orElse("Отменить"));
        redoItem.setEnabled(document.canRedo());
        redoItem.setText(document.redoDescription().map(d -> "Повторить: " + d).orElse("Повторить"));
    }

    // ------------------------------------------------------------------ Файл

    private JMenu fileMenu() {
        // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
        JMenu menu = menu("Файл", KeyEvent.VK_F);
        menu.add(item("Новый план…", KeyStroke.getKeyStroke(KeyEvent.VK_N, CTRL), "Мастер нового плана", actions::newPlan));
        menu.add(item("Открыть…", KeyStroke.getKeyStroke(KeyEvent.VK_O, CTRL), "Выбрать план из папки CashMemory", actions::openPlan));
        menu.add(item("Открыть из файла…", null, "Открыть план .md из любой папки", actions::openFromFile));
        menu.add(item("Открыть пример", null, "Несохранённый план «Пример» с зарплатой, арендой и кредитом", actions::openSample));
        menu.add(recentMenu());
        // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() (JPopupMenu.Separator) → Web: <li role="separator"><hr>
        menu.addSeparator();
        menu.add(item("Сохранить", KeyStroke.getKeyStroke(KeyEvent.VK_S, CTRL), "Сохранить план в его файл .md", actions::save));
        menu.add(item("Сохранить как…", KeyStroke.getKeyStroke(KeyEvent.VK_S, CTRL_SHIFT), "Сохранить план в другой файл .md",
                actions::saveAs));
        menu.add(item("Переименовать…", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "Новое имя плана и его файла", actions::rename));
        menu.addSeparator();
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
        JCheckBoxMenuItem autosave = new JCheckBoxMenuItem("Автосохранение");
        autosave.setModel(models.autosave());
        autosave.setToolTipText("Сохранять план в файл примерно через секунду после каждого изменения");
        menu.add(autosave);
        menu.add(item("Экспорт CSV…", KeyStroke.getKeyStroke(KeyEvent.VK_C, CTRL_SHIFT), "Таблица прогноза в CSV для Excel",
                actions::exportCsv));
        menu.add(item("Сохранить график PNG…", null, "Изображение графика баланса", actions::saveChartPng));
        menu.add(item("Папка CashMemory…", null, "Где хранятся планы; открыть планы из другой папки", actions::chooseCashMemoryFolder));
        menu.addSeparator();
        menu.add(item("Выход", null, "Закрыть программу (с вопросом о несохранённых изменениях)", commands::exit));
        return menu;
    }

    private JMenu recentMenu() {
        JMenu recent = new JMenu("Недавние");
        recent.setToolTipText("Недавно открытые планы");
        // Список строится при каждом открытии подменю: настройки могли измениться.
        recent.addMenuListener(new MenuListener() {
            /** Меню открывается: содержимое пересобирается по текущему состоянию. */
            @Override
            public void menuSelected(MenuEvent e) {
                recent.removeAll();
                List<String> plans = commands.recentPlans();
                if (plans.isEmpty()) {
                    JMenuItem none = new JMenuItem("(пусто)");
                    none.setEnabled(false);
                    recent.add(none);
                }
                for (String name : plans) {
                    recent.add(item(name, null, "Открыть «" + name + "»", () -> actions.openRecent(name)));
                }
            }

            /** Меню закрыто: действий не требуется. */
            @Override
            public void menuDeselected(MenuEvent e) {
                // Ничего: список перестраивается при следующем открытии.
            }

            /** Открытие меню отменено: действий не требуется. */
            @Override
            public void menuCanceled(MenuEvent e) {
                // Ничего.
            }
        });
        return recent;
    }

    // ------------------------------------------------------------------ Правка

    private JMenu editMenu() {
        JMenu menu = menu("Правка", KeyEvent.VK_P);
        menu.add(item("Добавить доход…", KeyStroke.getKeyStroke(KeyEvent.VK_I, CTRL), "Новая регулярная операция: доход",
                () -> actions.addRule(Kind.INCOME)));
        menu.add(item("Добавить расход…", KeyStroke.getKeyStroke(KeyEvent.VK_E, CTRL), "Новая регулярная операция: расход",
                () -> actions.addRule(Kind.EXPENSE)));
        menu.add(item("Разовая операция…", KeyStroke.getKeyStroke(KeyEvent.VK_T, CTRL), "Доход или расход один раз", actions::addOneTime));
        // Enter и Delete обрабатывает сама таблица: как ускорители меню они перехватывали бы клавиши в поле фильтра.
        menu.add(item("Изменить… (Enter)", null, "Изменить операцию выделенной строки", actions::editSelected));
        menu.add(item("Удалить… (Delete)", null, "Удалить операцию выделенной строки", actions::deleteSelected));
        menu.add(item("Скорректировать событие…", KeyStroke.getKeyStroke(KeyEvent.VK_J, CTRL),
                "Пропустить, изменить сумму или перенести выделенное событие правила", actions::adjustSelected));
        menu.add(item("Вернуть как по правилу", null, "Удалить корректировку выделенного события", actions::resetSelected));
        menu.addSeparator();
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, CTRL));
        undoItem.setToolTipText("Отменить последнее изменение плана");
        undoItem.addActionListener(e -> actions.undo());
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, CTRL));
        redoItem.setToolTipText("Повторить отменённое изменение");
        redoItem.addActionListener(e -> actions.redo());
        menu.add(undoItem);
        menu.add(redoItem);
        menu.addSeparator();
        menu.add(item("Параметры плана…", null, "Имя, валюта, начало, баланс, горизонт, подушка, цель", actions::planSettings));
        menu.add(item("Актуализировать на сегодня…", null, "Перенести начало плана на сегодня с прогнозным балансом",
                actions::actualize));
        menu.add(item("Сверить баланс…", null, "Ввести фактический баланс: разница станет разовой операцией", actions::reconcile));
        return menu;
    }

    // ------------------------------------------------------------------ Вид

    private JMenu viewMenu() {
        JMenu menu = menu("Вид", KeyEvent.VK_V);
        menu.add(radio("Таблица", KeyStroke.getKeyStroke(KeyEvent.VK_1, CTRL), "Таблица событий с балансом",
                models.mode(ViewMode.TABLE), models, true));
        menu.add(radio("График", KeyStroke.getKeyStroke(KeyEvent.VK_2, CTRL), "График баланса во времени",
                models.mode(ViewMode.CHART), models, true));
        menu.addSeparator();
        menu.add(ForecastPopupMenu.check("Доходы", "Показывать доходы", models, ViewModels.SHOW_INCOME));
        menu.add(ForecastPopupMenu.check("Расходы", "Показывать расходы", models, ViewModels.SHOW_EXPENSE));
        menu.add(ForecastPopupMenu.check("Разовые", "Показывать разовые операции", models, ViewModels.SHOW_ONE_TIME));
        menu.add(ForecastPopupMenu.check("Пропущенные", "Показывать пропущенные события (зачёркнутыми)", models, ViewModels.SHOW_SKIPPED));
        menu.add(ForecastPopupMenu.check("Итоги по месяцам", "Строки «Итог: Октябрь 2026» в таблице", models, ViewModels.MONTH_TOTALS));
        menu.add(ForecastPopupMenu.check("Маркеры на графике", "События на линии баланса", models, ViewModels.CHART_MARKERS));
        menu.add(ForecastPopupMenu.check("Столбцы по месяцам", "Доходы и расходы месяцев столбцами на графике", models,
                ViewModels.CHART_BARS));
        menu.add(ForecastPopupMenu.check("Панель сводки", "Карточки «Сейчас», «Через N мес.», «Минимум», «Цель»", models,
                ViewModels.SUMMARY_PANEL));
        menu.addSeparator();
        JMenuItem periodCaption = new JMenuItem("Период показа:");
        periodCaption.setEnabled(false);
        menu.add(periodCaption);
        for (PeriodChoice period : PeriodChoice.values()) {
            menu.add(radio(period.label(), null, "Показывать " + (period == PeriodChoice.ALL ? "весь горизонт плана"
                    : "ближайшие " + period.label()) + " (план не меняется)", models.period(period), models, false));
        }
        menu.addSeparator();
        // JavaFX: CustomMenuItem (Slider) → Swing: SwingSliderMenuItem (JPanel(JLabel+JSlider) через JMenu.add(Component)) → Web: <li class="custom"><input type="range">
        menu.add(horizonSlider);
        menu.add(item("Горизонт: другое число месяцев…", null, "Ввести горизонт плана от 1 до 600 месяцев", actions::customMonths));
        menu.addSeparator();
        menu.add(item("Фильтр по названию", KeyStroke.getKeyStroke(KeyEvent.VK_F, CTRL), "Перейти к полю фильтра таблицы",
                commands::focusFilter));
        return menu;
    }

    // ------------------------------------------------------------------ Инструменты

    private JMenu toolsMenu() {
        JMenu menu = menu("Инструменты", KeyEvent.VK_I);
        menu.add(item("Калькулятор цели…", KeyStroke.getKeyStroke(KeyEvent.VK_G, CTRL),
                "Когда накопится сумма и сколько откладывать, чтобы успеть к дате", actions::goalCalculator));
        JMenu whatIf = new JMenu("Что-если");
        whatIf.setToolTipText("Проверить план при других доходах и расходах, не меняя его");
        fillWhatIf(whatIf.getPopupMenu(), actions, models);
        menu.add(whatIf);
        menu.addSeparator();
        menu.add(item("Проверить план", null, "Ошибки и предупреждения плана, файла и прогноза", actions::validatePlan));
        menu.add(item("Очистить неиспользуемые корректировки", null, "Удалить корректировки событий, которых больше нет",
                actions::removeOrphanAdjustments));
        menu.addSeparator();
        menu.add(item("Валюта…", null, "Обозначение валюты плана", actions::currency));
        return menu;
    }

    /**
     * Заполняет меню «Что-если» (общее для меню «Инструменты» и кнопки «Что-если ▾»).
     *
     * @param popup   меню
     * @param actions фасад команд
     * @param models  общие модели
     */
    static void fillWhatIf(javax.swing.JPopupMenu popup, SwingActions actions, ViewModels models) {
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem (общий ButtonModel) → Web: role="menuitemcheckbox"
        JCheckBoxMenuItem income = new JCheckBoxMenuItem("Доходы −10 %");
        income.setModel(models.incomeWhatIf());
        income.setToolTipText("Что будет, если все доходы уменьшатся на 10 %");
        JCheckBoxMenuItem expense = new JCheckBoxMenuItem("Расходы +10 %");
        expense.setModel(models.expenseWhatIf());
        expense.setToolTipText("Что будет, если все расходы вырастут на 10 %");
        popup.add(income);
        popup.add(expense);
        // JavaFX: CustomMenuItem (Spinner) → Swing: SwingSpinnerMenuItem (JPanel(JLabel+JSpinner)) → Web: <li class="custom"><input type="number">
        popup.add(new SwingSpinnerMenuItem("Откладывать доп.:", models.extraSaving(), "в месяц"));
        popup.addSeparator();
        popup.add(item("Применить к плану…", null, "Пересчитать суммы плана по «что-если»", actions::applyWhatIf));
        popup.add(item("Сбросить", null, "Выключить «что-если»", actions::resetWhatIf));
    }

    // ------------------------------------------------------------------ Восстановление

    private JMenu recoveryMenu() {
        JMenu menu = menu("Восстановление", KeyEvent.VK_R);
        menu.add(radio("Хранилище по умолчанию: Реестр Windows", null,
                "Какую кнопку выделить в диалоге восстановления после сбоя", models.store(RecoveryStoreKind.REGISTRY),
                models, false, true));
        menu.add(radio("Хранилище по умолчанию: XML-файл", null,
                "Какую кнопку выделить в диалоге восстановления после сбоя", models.store(RecoveryStoreKind.XML),
                models, false, true));
        menu.addSeparator();
        menu.add(item("Сделать снимок сейчас", null, "Записать снимок сессии в реестр и XML немедленно", actions::snapshotNow));
        menu.add(item("Показать последний снимок…", null, "XML-файл и JSON из реестра", actions::showLastSnapshot));
        menu.add(item("Очистить снимки…", null, "Удалить сохранённые снимки (запись сессии продолжится)", actions::clearSnapshots));
        menu.addSeparator();
        JMenu crash = new JMenu("Симулировать сбой");
        crash.setToolTipText("Проверка восстановления: открытые окна и введённые значения вернутся при следующем запуске");
        crash.add(item("Аварийное завершение процесса…", null, "Runtime.halt без сохранения (с подтверждением)", actions::simulateHalt));
        crash.add(item("Необработанное исключение", null, "Исключение в потоке интерфейса: сообщение и завершение",
                actions::simulateException));
        menu.add(crash);
        return menu;
    }

    // ------------------------------------------------------------------ Справка

    private JMenu helpMenu() {
        JMenu menu = menu("Справка", KeyEvent.VK_S);
        menu.add(item("О программе", KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "Версия и папка данных", actions::about));
        menu.add(item("Горячие клавиши", null, "Список сочетаний клавиш", actions::hotkeys));
        menu.add(item("Формат файла .md", null, "Как устроен файл плана (для правки в Блокноте)", actions::formatHelp));
        return menu;
    }

    // ------------------------------------------------------------------ помощники

    private static JMenu menu(String text, int mnemonic) {
        // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
        JMenu menu = new JMenu(text);
        menu.setMnemonic(mnemonic);
        return menu;
    }

    private static JMenuItem item(String text, KeyStroke accelerator, String tooltip, Runnable action) {
        // JavaFX: MenuItem + KeyCombination → Swing: JMenuItem + setAccelerator → Web: <li role="menuitem"> + keydown
        JMenuItem item = ForecastPopupMenu.item(text, tooltip, action);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        return item;
    }

    private static JRadioButtonMenuItem radio(String text, KeyStroke accelerator, String tooltip, ButtonModel model,
                                              ViewModels models, boolean modeGroup) {
        return radio(text, accelerator, tooltip, model, models, modeGroup, false);
    }

    private static JRadioButtonMenuItem radio(String text, KeyStroke accelerator, String tooltip, ButtonModel model,
                                              ViewModels models, boolean modeGroup, boolean storeGroup) {
        // JavaFX: RadioMenuItem + ToggleGroup → Swing: JRadioButtonMenuItem + ButtonGroup → Web: role="menuitemradio"
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(text);
        item.setModel(model);
        item.setToolTipText(tooltip);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        // В группу попадает ровно этот пункт; кнопки панели инструментов только разделяют его модель.
        (storeGroup ? models.storeGroup() : modeGroup ? models.modeGroup() : models.periodGroup()).add(item);
        return item;
    }
}
