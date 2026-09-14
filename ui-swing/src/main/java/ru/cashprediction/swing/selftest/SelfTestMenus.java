package ru.cashprediction.swing.selftest;

import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.AbstractButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.event.MenuListener;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.swing.MainFrame;
import ru.cashprediction.swing.menu.ForecastPopupMenu;
import ru.cashprediction.swing.menu.SwingMenuButton;
import ru.cashprediction.swing.menu.SwingSliderMenuItem;
import ru.cashprediction.swing.menu.SwingSpinnerMenuItem;
import ru.cashprediction.swing.menu.SwingSplitMenuButton;

/**
 * Команда самотеста {@code menus <путь>}: текстовое дерево всех меню главного окна — строки меню, панели
 * инструментов и трёх контекстных меню (строка таблицы, график, карточка сводки).
 *
 * <p>Снимок экрана с открытым меню без отправки нажатий клавиш сделать нельзя, поэтому проверка «все аналоги
 * классов меню JavaFX на месте и подключены» делается по модели компонентов. Для каждого элемента печатается его
 * класс Swing, текст, ускоритель, наличие подсказки и то, чем он подключён: число {@code ActionListener}, общая
 * модель переключателя ({@code ButtonModel}) или слушатель подменю. Неподключённый активный пункт помечается
 * словом {@code UNWIRED}. В конце — сводка: сколько элементов каждого класса найдено.</p>
 *
 * <p>Средство разработчика; вызывается в потоке EDT.</p>
 */
public final class SelfTestMenus {

    private final StringBuilder out = new StringBuilder();
    private final Map<String, Integer> counts = new TreeMap<>();
    private final List<String> unwired = new ArrayList<>();
    /** Сколько обойдённых элементов меню и панели инструментов имеют подсказку. */
    private int tooltipCount;

    private SelfTestMenus() {
    }

    /**
     * Строит текст дерева меню.
     *
     * @param app главное окно
     * @return многострочный текст (UTF-8)
     */
    public static String dump(MainFrame app) {
        SelfTestMenus walker = new SelfTestMenus();
        walker.run(app);
        return walker.out.toString();
    }

    private void run(MainFrame app) {
        out.append("# Строка меню\n");
        JMenuBar bar = app.frame().getJMenuBar();
        count("JMenuBar");
        out.append("JMenuBar menus=").append(bar.getMenuCount()).append('\n');
        for (int i = 0; i < bar.getMenuCount(); i++) {
            element(bar.getMenu(i), 1);
        }

        out.append("\n# Панель инструментов\n");
        JToolBar toolBar = app.toolBar().toolBar();
        count("JToolBar");
        out.append("JToolBar\n");
        for (Component c : toolBar.getComponents()) {
            toolComponent(c, 1);
        }

        out.append("\n# Контекстное меню строки таблицы\n");
        ForecastRow ruleRow = null;
        try {
            ruleRow = app.document().visibleRows().stream()
                    .filter(r -> r.origin() == Origin.RULE && r.ruleId() != null).findFirst().orElse(null);
        } catch (IllegalStateException e) {
            out.append("  (прогноз не рассчитан: ").append(e.getMessage()).append(")\n");
        }
        if (ruleRow != null) {
            out.append("row ").append(ruleRow.rowId()).append('\n');
            popup(ForecastPopupMenu.forRow(app.actions(), ruleRow), 1);
        } else {
            out.append("  (в таблице нет события правила - откройте пример)\n");
        }

        out.append("\n# Контекстное меню графика\n");
        popup(ForecastPopupMenu.forChart(app.actions(), app.models(), app, LocalDate.now()), 1);

        out.append("\n# Контекстное меню карточки сводки\n");
        popup(ForecastPopupMenu.forCard(app.actions(), app.models(), app, "Сейчас", "150 000,00 ₽", LocalDate.now()), 1);

        // Раздел соответствия строится после обхода: он опирается на уже подсчитанные элементы и подсказки.
        mapping(app);

        out.append("\n# Сводка по классам\n");
        counts.forEach((name, n) -> out.append(name).append(" = ").append(n).append('\n'));
        out.append("\n# Неподключённые пункты: ").append(unwired.size()).append('\n');
        unwired.forEach(u -> out.append("UNWIRED ").append(u).append('\n'));
    }

    /** Элемент меню (пункт, подменю, панель-пункт) с вложенными элементами. */
    private void element(Component c, int depth) {
        indent(depth);
        if (c instanceof JMenu menu) {
            count("JMenu");
            MenuListener[] listeners = menu.getMenuListeners();
            out.append("JMenu «").append(menu.getText()).append('»')
                    .append(menu.getMnemonic() != 0 ? " mnemonic=" + (char) menu.getMnemonic() : "")
                    .append(tooltip(menu))
                    .append(listeners.length > 0 ? " menuListeners=" + listeners.length : "")
                    .append('\n');
            for (Component child : menu.getMenuComponents()) {
                element(child, depth + 1);
            }
        } else if (c instanceof JCheckBoxMenuItem check) {
            count("JCheckBoxMenuItem");
            out.append("JCheckBoxMenuItem «").append(check.getText()).append("» selected=").append(check.isSelected())
                    .append(accelerator(check.getAccelerator())).append(tooltip(check))
                    .append(" model=").append(check.getModel().getClass().getSimpleName())
                    .append(wiring(check)).append('\n');
        } else if (c instanceof JRadioButtonMenuItem radio) {
            count("JRadioButtonMenuItem");
            out.append("JRadioButtonMenuItem «").append(radio.getText()).append("» selected=").append(radio.isSelected())
                    .append(accelerator(radio.getAccelerator())).append(tooltip(radio))
                    .append(" group=").append(radio.getModel() instanceof javax.swing.DefaultButtonModel m && m.getGroup() != null)
                    .append(wiring(radio)).append('\n');
        } else if (c instanceof JMenuItem item) {
            count("JMenuItem");
            int listeners = item.getActionListeners().length;
            out.append("JMenuItem «").append(item.getText()).append('»').append(accelerator(item.getAccelerator()))
                    .append(tooltip(item)).append(" actionListeners=").append(listeners)
                    .append(item.isEnabled() ? "" : " disabled").append('\n');
            if (listeners == 0 && item.isEnabled()) {
                unwired.add(item.getText());
            }
        } else if (c instanceof JPopupMenu.Separator) {
            count("JPopupMenu.Separator");
            out.append("JPopupMenu.Separator\n");
        } else if (c instanceof SwingSliderMenuItem slider) {
            count("SwingSliderMenuItem");
            JSlider s = slider.slider();
            out.append("SwingSliderMenuItem (CustomMenuItem) slider=").append(s.getMinimum()).append("..").append(s.getMaximum())
                    .append(" value=").append(s.getValue()).append(" changeListeners=").append(s.getChangeListeners().length)
                    .append(tooltip(s)).append('\n');
        } else if (c instanceof SwingSpinnerMenuItem spinner) {
            count("SwingSpinnerMenuItem");
            JSpinner s = spinner.spinner();
            out.append("SwingSpinnerMenuItem (CustomMenuItem) value=").append(s.getValue())
                    .append(" modelListeners=").append(s.getModel() instanceof javax.swing.AbstractSpinnerModel m
                            ? m.getChangeListeners().length : 0)
                    .append(tooltip(s)).append('\n');
        } else {
            count(c.getClass().getSimpleName());
            out.append(c.getClass().getSimpleName()).append('\n');
        }
    }

    /** Компонент панели инструментов; у кнопок с меню печатается и само меню. */
    private void toolComponent(Component c, int depth) {
        if (c instanceof SwingSplitMenuButton split) {
            count("SwingSplitMenuButton");
            indent(depth);
            out.append("SwingSplitMenuButton (SplitMenuButton) «").append(split.mainButton().getText())
                    .append("» actionListeners=").append(split.mainButton().getActionListeners().length)
                    .append(tooltip(split.mainButton())).append('\n');
            popup(split.popup(), depth + 1);
        } else if (c instanceof SwingMenuButton button) {
            count("SwingMenuButton");
            indent(depth);
            out.append("SwingMenuButton (MenuButton) «").append(button.getText()).append('»').append(tooltip(button)).append('\n');
            popup(button.popup(), depth + 1);
        } else if (c instanceof JToggleButton toggle) {
            count("JToggleButton");
            indent(depth);
            out.append("JToggleButton «").append(toggle.getText()).append("» selected=").append(toggle.isSelected())
                    .append(tooltip(toggle)).append('\n');
        } else if (c instanceof AbstractButton button) {
            count(button.getClass().getSimpleName());
            indent(depth);
            out.append(button.getClass().getSimpleName()).append(" «").append(button.getText()).append("» actionListeners=")
                    .append(button.getActionListeners().length).append(tooltip(button)).append('\n');
        } else if (c instanceof JTextField field) {
            count("JTextField");
            indent(depth);
            out.append("JTextField columns=").append(field.getColumns()).append(tooltip(field)).append('\n');
        } else if (c instanceof JToolBar.Separator) {
            indent(depth);
            out.append("JToolBar.Separator\n");
        } else if (c instanceof Container container && container.getComponentCount() > 0) {
            indent(depth);
            out.append(c.getClass().getSimpleName()).append('\n');
            for (Component child : container.getComponents()) {
                toolComponent(child, depth + 1);
            }
        } else {
            indent(depth);
            out.append(c.getClass().getSimpleName()).append('\n');
        }
    }

    /**
     * Раздел «23 класса JavaFX → аналоги Swing» (план, раздел 7). Для каждого класса печатается аналог, признак того,
     * что класс аналога загружается, и что найдено в живом окне: число элементов меню и панели инструментов, число
     * элементов с подсказкой или компоненты главного окна со слушателем мыши самого клиента (через него приходит
     * запрос контекстного меню). Диалоги и всплывающие окна открываются сценарием проверки через {@code open}, здесь
     * подтверждается только наличие их классов.
     *
     * @param app главное окно
     */
    private void mapping(MainFrame app) {
        out.append("\n# 23 класса JavaFX → аналоги Swing\n");
        Map<String, Integer> contextTargets = new TreeMap<>();
        collectContextTargets(app.frame().getContentPane(), contextTargets);
        // Столбцы: класс JavaFX, аналог Swing, класс для проверки загрузки, чем подтверждается в живом окне.
        String[][] rows = {
                {"MenuBar", "JMenuBar", "javax.swing.JMenuBar", "JMenuBar"},
                {"Menu", "JMenu", "javax.swing.JMenu", "JMenu"},
                {"MenuItem", "JMenuItem + setAccelerator", "javax.swing.JMenuItem", "JMenuItem"},
                {"CheckMenuItem", "JCheckBoxMenuItem", "javax.swing.JCheckBoxMenuItem", "JCheckBoxMenuItem"},
                {"RadioMenuItem", "JRadioButtonMenuItem + ButtonGroup", "javax.swing.JRadioButtonMenuItem", "JRadioButtonMenuItem"},
                {"SeparatorMenuItem", "JPopupMenu.Separator (addSeparator)", "javax.swing.JPopupMenu$Separator", "JPopupMenu.Separator"},
                {"CustomMenuItem", "SwingSliderMenuItem / SwingSpinnerMenuItem", "ru.cashprediction.swing.menu.SwingSliderMenuItem",
                        "SwingSliderMenuItem+SwingSpinnerMenuItem"},
                {"MenuButton", "SwingMenuButton", "ru.cashprediction.swing.menu.SwingMenuButton", "SwingMenuButton"},
                {"SplitMenuButton", "SwingSplitMenuButton", "ru.cashprediction.swing.menu.SwingSplitMenuButton", "SwingSplitMenuButton"},
                {"PopupWindow", "DayCardPopup (JWindow)", "ru.cashprediction.swing.popup.DayCardPopup", null},
                {"Popup", "QuickEditPopup (PopupFactory)", "ru.cashprediction.swing.popup.QuickEditPopup", null},
                {"PopupControl", "SwingPopupControl (JWindow + Border)", "ru.cashprediction.swing.popup.SwingPopupControl", null},
                {"Tooltip", "setToolTipText / getToolTipText(MouseEvent)", "javax.swing.ToolTipManager", "#tooltips"},
                {"ContextMenu", "JPopupMenu", "javax.swing.JPopupMenu", "JPopupMenu"},
                {"ContextMenuEvent", "MouseAdapter.isPopupTrigger()", "java.awt.event.MouseAdapter", "#context"},
                {"Dialog<R>", "SwingDialog<R>", "ru.cashprediction.swing.dialog.SwingDialog", null},
                {"DialogPane", "SwingDialogPane", "ru.cashprediction.swing.dialog.SwingDialogPane", null},
                {"ButtonType", "SwingButtonType", "ru.cashprediction.swing.dialog.SwingButtonType", null},
                {"Alert", "SwingAlert (JOptionPane)", "ru.cashprediction.swing.dialog.SwingAlert", null},
                {"TextInputDialog", "SwingTextInputDialog", "ru.cashprediction.swing.dialog.SwingTextInputDialog", null},
                {"ChoiceDialog<T>", "SwingChoiceDialog<T>", "ru.cashprediction.swing.dialog.SwingChoiceDialog", null},
                {"DirectoryChooser", "JFileChooser(DIRECTORIES_ONLY)", "ru.cashprediction.swing.dialog.SwingFileChoosers", null},
                {"FileChooser", "JFileChooser + FileNameExtensionFilter", "ru.cashprediction.swing.dialog.SwingFileChoosers", null},
        };
        int number = 0;
        for (String[] row : rows) {
            number++;
            out.append(number).append(". ").append(row[0]).append(" → ").append(row[1]).append(": класс ")
                    .append(loads(row[2]) ? "есть" : "НЕ НАЙДЕН");
            String evidence = row[3];
            if ("#tooltips".equals(evidence)) {
                out.append(", элементов меню и панели с подсказкой: ").append(tooltipCount);
            } else if ("#context".equals(evidence)) {
                out.append(", компоненты со слушателем мыши клиента: ").append(contextTargets);
            } else if (evidence != null) {
                int found = 0;
                for (String key : evidence.split("\\+")) {
                    found += counts.getOrDefault(key, 0);
                }
                out.append(", найдено в меню и на панели: ").append(found);
            }
            out.append('\n');
        }
    }

    /**
     * Собирает компоненты, на которых висит слушатель мыши самого клиента (анонимные классы пакета
     * {@code ru.cashprediction.swing}), а не стандартные слушатели Swing.
     *
     * @param component корень обхода
     * @param targets   имя класса компонента → количество
     */
    private static void collectContextTargets(Component component, Map<String, Integer> targets) {
        for (java.awt.event.MouseListener listener : component.getMouseListeners()) {
            if (listener.getClass().getName().startsWith("ru.cashprediction.swing.")) {
                targets.merge(component.getClass().getSimpleName(), 1, Integer::sum);
                break;
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectContextTargets(child, targets);
            }
        }
    }

    /**
     * Проверяет, что класс аналога доступен (без инициализации).
     *
     * @param className двоичное имя класса
     * @return {@code true}, если класс загружается
     */
    private static boolean loads(String className) {
        try {
            Class.forName(className, false, SelfTestMenus.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void popup(JPopupMenu menu, int depth) {
        count("JPopupMenu");
        indent(depth);
        out.append("JPopupMenu items=").append(menu.getSubElements().length).append('\n');
        // getComponents, а не getSubElements: панели-пункты (слайдер, спиннер) и разделители не являются MenuElement.
        for (Component child : menu.getComponents()) {
            element(child, depth + 1);
        }
    }

    private String wiring(AbstractButton button) {
        // Переключатели подключены через общую модель: слушатели висят на ButtonModel (ViewModels), не на пункте.
        int modelListeners = button.getModel() instanceof javax.swing.DefaultButtonModel m ? m.getItemListeners().length
                + m.getChangeListeners().length + m.getActionListeners().length : 0;
        String text = " modelListeners=" + modelListeners + " actionListeners=" + button.getActionListeners().length;
        if (modelListeners == 0 && button.getActionListeners().length == 0 && button.getItemListeners().length == 0) {
            unwired.add(button.getText());
        }
        return text;
    }

    private static String accelerator(KeyStroke stroke) {
        return stroke == null ? "" : " accel=" + stroke.toString().replace("pressed ", "");
    }

    private String tooltip(JComponent component) {
        boolean present = component.getToolTipText() != null && !component.getToolTipText().isBlank();
        if (present) {
            // Счётчик нужен разделу соответствия: аналог Tooltip подтверждается числом элементов с подсказкой.
            tooltipCount++;
        }
        return present ? " tooltip=yes" : " tooltip=NO";
    }

    private void indent(int depth) {
        out.append("  ".repeat(depth));
    }

    private void count(String name) {
        counts.merge(name, 1, Integer::sum);
    }
}
