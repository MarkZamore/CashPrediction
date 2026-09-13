package ru.cashprediction.core.ui.form;

/**
 * Закрытый набор элементов формы (спецификация v2, §6.0 «Закрытый набор элементов формы»). Клиент строит для
 * каждого вида ровно один виджет; других элементов в формах нет.
 *
 * <p>Каноническое значение поля (в {@code FormState}, снимке и web-протоколе) задаёт {@code FieldCodec}: сумма
 * {@code 95000,00}, дата ISO {@code 2026-10-05}, день года {@code MM-dd}, флажок {@code true}/{@code false}, выбор —
 * {@code Option.value}. Некорректный ввод хранится как есть и восстанавливается дословно.</p>
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum FieldKind {

    /** Однострочное поле: JavaFX {@code TextField} → Swing {@code JTextField} → Web {@code input}. */
    TEXT,
    /** Многострочное поле с переносом, {@code FieldSpec.textRows} строк. */
    MULTILINE,
    /**
     * Сумма: выровнена вправо, подсказка «0,00», переформатируется при потере фокуса («80000» → «80 000,00»);
     * принимает «80000», «80 000,5», «80000.50».
     */
    MONEY,
    /**
     * Дата: поле + кнопка «▦» календаря (§5.6.5), подсказка «ДД.ММ.ГГГГ», принимает также «ГГГГ-ММ-ДД», при потере
     * фокуса переформатируется в «ДД.ММ.ГГГГ».
     */
    DATE,
    /**
     * День года правила YEARLY: рисуется как {@link #TEXT}, подсказка «ДД.ММ, например 15.03»; принимает ДД.ММ и
     * ММ-ДД, каноническое значение ММ-ДД.
     */
    MONTH_DAY,
    /** Целое в диапазоне {@code min..max} с шагом {@code step}: JavaFX {@code Spinner} → Swing {@code JSpinner} → Web number. */
    SPINNER,
    /** Выпадающий список: JavaFX {@code ComboBox} → Swing {@code JComboBox} → Web {@code select}. */
    CHOICE,
    /** Список с вводом: редактируемый {@code ComboBox} / {@code JComboBox} → Web {@code input} + {@code datalist}. */
    EDITABLE_CHOICE,
    /** Флажок: {@code CheckBox} → {@code JCheckBox} → {@code input type=checkbox}. */
    CHECK,
    /** Группа радиокнопок, горизонтальная или вертикальная ({@code FieldSpec.orientation}). */
    RADIO,
    /** Список с выбором, {@code FieldSpec.textRows} видимых строк: {@code ListView} → {@code JList} → Web список. */
    LIST,
    /** Только чтение: список ({@code FormView.preview}) или текст (значение поля), например предпросмотр дат. */
    PREVIEW,
    /** Строки результата ({@code FormView.results}), например калькулятор цели. */
    RESULT_LINES,
    /** Кнопка внутри формы (не в панели кнопок), например «Скорректировать выбранную дату…». */
    BUTTON
}
