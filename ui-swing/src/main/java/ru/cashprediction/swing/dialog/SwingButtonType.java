package ru.cashprediction.swing.dialog;

import java.util.Objects;

/**
 * Тип кнопки диалога: текст и роль. Swing-аналог JavaFX {@code ButtonType} с {@code ButtonBar.ButtonData}.
 *
 * <p>В Swing нет отдельного понятия «тип кнопки»: {@code JOptionPane} оперирует просто массивом объектов-опций.
 * Чтобы диалоги трёх клиентов вели себя одинаково, роль кнопки задаётся явно: по роли {@link SwingDialogPane}
 * расставляет кнопки в панели, выбирает кнопку по умолчанию (Enter) и кнопку отмены (Esc, крестик окна),
 * а {@link SwingDialog} решает, нужна ли для кнопки проверка формы.</p>
 *
 * <p>Record неизменяем и потокобезопасен. Два типа с одинаковыми текстом и ролью равны.</p>
 *
 * @param text надпись на кнопке (по-русски)
 * @param role роль кнопки
 */
// JavaFX: ButtonType → Swing: SwingButtonType → Web: <button value>
public record SwingButtonType(String text, Role role) {

    /**
     * Роль кнопки: аналог {@code ButtonBar.ButtonData} в JavaFX.
     */
    public enum Role {
        /** Подтверждение: «ОК», «Сохранить», «Удалить». Кнопка по умолчанию; требует валидной формы. */
        OK_DONE,
        /** Отмена/закрытие: «Отмена», «Закрыть». Срабатывает на Esc и на крестик окна. */
        CANCEL_CLOSE,
        /** «Далее» в мастере: требует валидной текущей страницы. */
        NEXT_FORWARD,
        /** «Назад» в мастере. */
        BACK_PREVIOUS,
        /** «Готово» в мастере: как {@link #OK_DONE}, требует валидной формы. */
        FINISH,
        /** Прочие действия («Сбросить», «Из файла…»): обрабатываются самим диалогом. */
        OTHER
    }

    /** Кнопка «ОК». */
    public static final SwingButtonType OK = new SwingButtonType("ОК", Role.OK_DONE);
    /** Кнопка «Отмена». */
    public static final SwingButtonType CANCEL = new SwingButtonType("Отмена", Role.CANCEL_CLOSE);
    /** Кнопка «Закрыть». */
    public static final SwingButtonType CLOSE = new SwingButtonType("Закрыть", Role.CANCEL_CLOSE);
    /** Кнопка «Да» (подтверждение). */
    public static final SwingButtonType YES = new SwingButtonType("Да", Role.OK_DONE);
    /** Кнопка «Нет». */
    public static final SwingButtonType NO = new SwingButtonType("Нет", Role.OTHER);

    /**
     * Проверяет поля.
     */
    public SwingButtonType {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(role, "role");
    }

    /**
     * Является ли кнопка подтверждающей (ей нужна валидная форма и она становится кнопкой по умолчанию).
     *
     * @return {@code true} для {@link Role#OK_DONE} и {@link Role#FINISH}
     */
    public boolean isDefaultButton() {
        return role == Role.OK_DONE || role == Role.FINISH;
    }

    /**
     * Является ли кнопка кнопкой отмены (Esc и крестик окна).
     *
     * @return {@code true} для {@link Role#CANCEL_CLOSE}
     */
    public boolean isCancelButton() {
        return role == Role.CANCEL_CLOSE;
    }

    /**
     * Текст кнопки: {@code JOptionPane} подписывает кнопки-опции результатом {@code toString()}.
     *
     * @return текст кнопки
     */
    @Override
    public String toString() {
        // JOptionPane показывает опции через toString(): так на кнопке окажется только текст.
        return text;
    }
}
