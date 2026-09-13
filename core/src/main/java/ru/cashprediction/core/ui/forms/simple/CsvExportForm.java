package ru.cashprediction.core.ui.forms.simple;

import java.util.Map;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

/**
 * §6.16 CSV_EXPORT «Экспорт в CSV» (⇩, 560; представление {@code DIALOG}).
 *
 * <p>Заголовок «Экспорт прогноза в CSV». Поля: {@code separator} — вертикальное радио «точка с запятой ( ; ) — для
 * русского Excel» (по умолчанию) / «запятая ( , )» / «табуляция», значения {@code ;} {@code ,} {@code TAB};
 * {@code bom} — широкий флажок «Добавить BOM (нужно Excel, чтобы правильно показать кириллицу)», отмечен;
 * {@code range} — радио «Видимый период: dd.MM.yyyy — dd.MM.yyyy» (по умолчанию) / «Весь горизонт плана: …»,
 * значения {@code PERIOD}/{@code ALL}; широкая подпись «Колонки: Дата, День, Операция, Категория, Доход, Расход,
 * Баланс, Отметки, Заметка.». Кнопки [Экспортировать…] [Отмена].</p>
 *
 * <p>Результат {@code Close(CsvOptions)}; затем контроллер показывает выбор файла «Экспорт прогноза в CSV».</p>
 */
public final class CsvExportForm implements FormLogic {

    /** Создаёт форму. */
    public CsvExportForm() {
    }

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework — CsvExportForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework — CsvExportForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework — CsvExportForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework — CsvExportForm.onButton");
    }
}
