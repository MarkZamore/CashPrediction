package ru.cashprediction.fx.dialog;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.export.CsvOptions;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;

import java.time.LocalDate;
import java.util.List;

/**
 * Диалог 14 «Экспорт CSV»: разделитель, BOM и диапазон; после подтверждения фасад откроет выбор файла.
 *
 * <p>Поля словаря: {@code separator} ({@code ;}, {@code ,} или {@code TAB}), {@code bom}, {@code range}
 * ({@code PERIOD} или {@code ALL}). Результат — {@link CsvExportChoice}. Только FX Application Thread.</p>
 */
// JavaFX: Dialog<R> + DialogPane (AppDialogPane) + ButtonType → Swing: SwingDialog<R> (CsvExportDialog) → Web: openDialog('csv')
public final class CsvExportDialog extends FxStatefulDialog<CsvExportChoice> {

    /** Каноническое значение разделителя «табуляция» в снимке. */
    public static final String TAB = "TAB";

    private final ToggleGroup separator = new ToggleGroup();
    private final CheckBox bom = new CheckBox("Добавить BOM (нужно Excel, чтобы не испортить кириллицу)");
    private final ToggleGroup range = new ToggleGroup();

    /**
     * Создаёт диалог.
     *
     * @param periodFrom первый день видимого периода
     * @param periodTo   последний день видимого периода
     * @param planEnd    последний день горизонта плана
     */
    public CsvExportDialog(LocalDate periodFrom, LocalDate periodTo, LocalDate planEnd) {
        super(WindowType.CSV_EXPORT, new AppDialogPane("Экспорт прогноза в CSV", "⇩"));
        CsvOptions defaults = CsvOptions.DEFAULT;
        // Переключатели столбиком: в строку подписи обрезались до «зап…» и «таб…».
        VBox separators = new VBox(6,
                radio(separator, "точка с запятой ( ; ) — для русского Excel", ";", defaults.separator() == ';'),
                radio(separator, "запятая ( , )", ",", defaults.separator() == ','),
                radio(separator, "табуляция", TAB, defaults.separator() == '\t'));
        bom.setSelected(defaults.bom());
        VBox ranges = new VBox(6,
                radio(range, "Видимый период: " + DateFormats.ru(periodFrom) + " — " + DateFormats.ru(periodTo), "PERIOD", true),
                radio(range, "Весь горизонт плана: до " + DateFormats.ru(planEnd), "ALL", false));
        Label hint = new Label("Колонки: Дата, День, Операция, Категория, Доход, Расход, Баланс, Отметки, Заметка.");
        hint.setWrapText(true);
        appPane().setForm(new FormGrid()
                .row("Разделитель", separators)
                .wide(bom)
                .row("Диапазон", ranges)
                .wide(hint));
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        appPane().getButtonTypes().setAll(AppButtonTypes.EXPORT, AppButtonTypes.CANCEL);

        // Порядок привязки = порядок полей словаря WindowType.CSV_EXPORT.
        binder().bindToggle("separator", separator);
        binder().bindCheck("bom", bom);
        binder().bindToggle("range", range);

        setResultConverter(button -> button == AppButtonTypes.EXPORT ? choice() : null);
        activate();
    }

    /** {@inheritDoc} */
    @Override
    protected void validateForm(List<String> errors, List<String> warnings) {
        if (separator.getSelectedToggle() == null) {
            errors.add("Выберите разделитель");
        }
        if (range.getSelectedToggle() == null) {
            errors.add("Выберите диапазон");
        }
    }

    private CsvExportChoice choice() {
        String key = separator.getSelectedToggle() == null ? ";" : separator.getSelectedToggle().getUserData().toString();
        char sep = TAB.equals(key) ? '\t' : key.charAt(0);
        boolean all = range.getSelectedToggle() != null && "ALL".equals(range.getSelectedToggle().getUserData());
        return new CsvExportChoice(sep, bom.isSelected(), all);
    }

    private static RadioButton radio(ToggleGroup group, String text, String key, boolean selected) {
        RadioButton button = new RadioButton(text);
        button.setUserData(key);
        button.setToggleGroup(group);
        button.setSelected(selected);
        return button;
    }
}
