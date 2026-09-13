package ru.cashprediction.core.ui.form;

import java.util.Map;

/**
 * Введённые значения формы (архитектура §3.5): вход {@code FormLogic.evaluate} и {@code onButton}.
 *
 * <p>Значения — канонические формы {@code FieldCodec} (сумма {@code 95000,00}, дата ISO, день года ММ-ДД, флажок
 * {@code true}/{@code false}), а для некорректного ввода — сырой текст как есть. Ровно эта карта попадает в
 * {@code WindowState.fields} снимка; номер страницы — в контекст {@code page}.</p>
 *
 * <p><b>Выбор в предпросмотре</b> ({@link #previewIndex()}) — тоже часть состояния, потому что {@code FormLogic} не
 * хранит ничего своего: кнопка «Скорректировать выбранную дату…» редактора правила (§6.3) доступна только при
 * выбранной дате, а {@code onButton("adjustSelected")} берёт индекс отсюда. Индекс задаёт
 * {@code FormSession.previewSelected}; в снимок сеанса он не пишется (правило R3: схема снимка меняется только
 * добавлениями из архитектуры), поэтому после восстановления равен -1.</p>
 *
 * @param page         номер текущей страницы
 * @param values       значения по id поля (порядок — порядок {@code WindowType.fieldIds()})
 * @param previewIndex номер выбранного элемента предпросмотра или -1, если ничего не выбрано
 */
public record FormState(int page, Map<String, String> values, int previewIndex) {

    /** Значение {@link #previewIndex()}, когда в предпросмотре ничего не выбрано. */
    public static final int NO_PREVIEW = -1;

    /** Копирует карту; отрицательный индекс предпросмотра приводит к {@link #NO_PREVIEW}. */
    public FormState {
        values = values == null ? Map.of() : Map.copyOf(values);
        previewIndex = previewIndex < 0 ? NO_PREVIEW : previewIndex;
    }

    /**
     * Состояние без выбора в предпросмотре (новая или восстановленная форма).
     *
     * @param page   номер текущей страницы
     * @param values значения по id поля
     */
    public FormState(int page, Map<String, String> values) {
        this(page, values, NO_PREVIEW);
    }

    /**
     * Значение поля.
     *
     * @param fieldId id поля
     * @return значение или пустая строка
     */
    public String value(String fieldId) {
        return values.getOrDefault(fieldId, "");
    }

    /**
     * Копия с другим выбором в предпросмотре.
     *
     * @param index номер элемента или -1
     * @return новое состояние
     */
    public FormState withPreviewIndex(int index) {
        return new FormState(page, values, index);
    }

    /** @return выбран ли элемент предпросмотра */
    public boolean hasPreviewSelection() {
        return previewIndex != NO_PREVIEW;
    }
}
