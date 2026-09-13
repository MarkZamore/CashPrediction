package ru.cashprediction.core.ui.form;

/**
 * Каким классом инструмента показывается форма (архитектура §3.5, §4.1 таблица 23 классов).
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum Presentation {

    /** Обычная форма: JavaFX {@code Dialog<R>} + {@code DialogPane} → Swing {@code SwingDialog} → Web {@code <dialog>}. */
    DIALOG,
    /** Мастер из нескольких страниц (NEW_PLAN_WIZARD): тот же каркас, кнопки BACK/NEXT/FINISH. */
    WIZARD,
    /** Ввод одного значения: JavaFX {@code TextInputDialog} → Swing {@code SwingTextInputDialog} → Web {@code <dialog>}. */
    TEXT_INPUT,
    /** Выбор из списка: JavaFX {@code ChoiceDialog<T>} → Swing {@code SwingChoiceDialog} → Web {@code <dialog>}. */
    CHOICE,
    /** Список с выбором в форме («Открыть план», §6.10): JavaFX {@code Dialog<R>} со списком. */
    LIST_CHOICE,
    /** Подтверждение: JavaFX {@code Alert(CONFIRMATION)} → Swing {@code SwingAlert} → Web {@code <dialog>}. */
    CONFIRM,
    /** Всплывающее окно без кнопок (быстрая правка суммы): JavaFX {@code Popup} → Swing {@code PopupFactory} → Web div. */
    POPUP,
    /** Web: окно ядра «Выбор файла» (§6.21); у FX и Swing не используется. */
    FILE_BROWSER
}
