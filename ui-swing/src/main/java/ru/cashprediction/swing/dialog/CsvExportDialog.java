package ru.cashprediction.swing.dialog;

import java.awt.GridLayout;
import java.awt.Window;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;

/**
 * Диалог 14 «Экспорт CSV»: разделитель, BOM и диапазон (выбранный период или весь прогноз). После «Выбрать файл…»
 * фасад показывает окно сохранения файла и пишет CSV через {@code CsvExporter}.
 *
 * <p>Поля снимка: {@code separator} = «;», «,» или «TAB»; {@code bom} = {@code true/false};
 * {@code range} = {@code PERIOD} или {@code ALL}.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Dialog<CsvOptions> + DialogPane → Swing: SwingDialog<Choice> + SwingDialogPane → Web: openDialog(id): Promise<R> поверх <dialog>
public final class CsvExportDialog extends SwingDialog<CsvExportDialog.Choice> {

    /** Кнопка подтверждения: дальше откроется выбор файла. */
    public static final SwingButtonType CHOOSE_FILE = new SwingButtonType("Выбрать файл…", SwingButtonType.Role.OK_DONE);

    /**
     * Выбор пользователя.
     *
     * @param separator     разделитель ячеек
     * @param bom           добавлять ли BOM
     * @param wholeForecast {@code true} — весь прогноз, {@code false} — выбранный период
     */
    public record Choice(char separator, boolean bom, boolean wholeForecast) {
    }

    private final Map<String, JRadioButton> separators = new LinkedHashMap<>();
    private final Map<String, JRadioButton> ranges = new LinkedHashMap<>();
    private final JCheckBox bom = new JCheckBox("Добавить BOM (Excel правильно покажет кириллицу)", true);

    /**
     * Создаёт диалог.
     *
     * @param owner       окно-владелец
     * @param ownerId     идентификатор владельца для снимка
     * @param recorder    рекордер сессии или {@code null}
     * @param start       начало прогноза
     * @param periodEnd   конец выбранного периода
     * @param forecastEnd конец прогноза
     */
    public CsvExportDialog(Window owner, String ownerId, SessionRecorder recorder, LocalDate start, LocalDate periodEnd,
                           LocalDate forecastEnd) {
        super(owner, ownerId, WindowType.CSV_EXPORT, true, "Экспорт в CSV", recorder);
        separators.put(";", new JRadioButton("Точка с запятой ( ; ) — для русского Excel", true));
        separators.put(",", new JRadioButton("Запятая ( , )"));
        separators.put("TAB", new JRadioButton("Табуляция"));
        ranges.put("PERIOD", new JRadioButton("Выбранный период: " + DateFormats.ru(start) + " – " + DateFormats.ru(periodEnd), true));
        ranges.put("ALL", new JRadioButton("Весь прогноз: " + DateFormats.ru(start) + " – " + DateFormats.ru(forecastEnd)));

        FormPanel form = new FormPanel();
        form.addRow("Разделитель", group(separators));
        form.addRow("Кодировка", bom);
        form.addRow("Строки", group(ranges));
        pane().setHeaderText("Таблица прогноза в файл CSV: дата, операция, доход, расход, баланс, отметки.");
        pane().setContent(form);

        binder().bindRadioGroup("separator", separators);
        binder().bindCheckBox("bom", bom);
        binder().bindRadioGroup("range", ranges);

        setResultConverter(button -> button.isDefaultButton() ? new Choice(selectedSeparator(), bom.isSelected(),
                ranges.get("ALL").isSelected()) : null);
        setButtonTypes(CHOOSE_FILE, SwingButtonType.CANCEL);
    }

    private static JPanel group(Map<String, JRadioButton> buttons) {
        ButtonGroup group = new ButtonGroup();
        JPanel panel = new JPanel(new GridLayout(0, 1));
        buttons.values().forEach(button -> {
            group.add(button);
            panel.add(button);
        });
        return panel;
    }

    private char selectedSeparator() {
        if (separators.get("TAB").isSelected()) {
            return '\t';
        }
        return separators.get(",").isSelected() ? ',' : ';';
    }
}
