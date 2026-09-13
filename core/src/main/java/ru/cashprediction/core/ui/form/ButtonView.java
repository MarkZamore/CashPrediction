package ru.cashprediction.core.ui.form;

/**
 * Изменяемое состояние кнопки формы.
 *
 * @param enabled доступна ли (OK/FINISH отключены, пока есть ошибка)
 * @param visible видна ли («Открыть пример» только на странице 1, «Сбросить корректировку» только при корректировке)
 * @param text    текст или {@code null} — как в {@code ButtonSpec}
 * @param tooltip подсказка или {@code null} — как в {@code ButtonSpec}
 */
public record ButtonView(boolean enabled, boolean visible, String text, String tooltip) {

    /** Доступная видимая кнопка с текстом из {@code ButtonSpec}. */
    public static final ButtonView ENABLED = new ButtonView(true, true, null, null);

    /** Отключённая видимая кнопка. */
    public static final ButtonView DISABLED = new ButtonView(false, true, null, null);

    /** Скрытая кнопка. */
    public static final ButtonView HIDDEN = new ButtonView(false, false, null, null);
}
