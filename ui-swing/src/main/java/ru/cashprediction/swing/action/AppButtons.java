package ru.cashprediction.swing.action;

import ru.cashprediction.swing.dialog.SwingButtonType;

/**
 * Типы кнопок, общие для команд Swing-клиента: «Сохранить», «Не сохранять», «Удалить», «Перезаписать»…
 *
 * <p>Аналог {@code AppButtonTypes} JavaFX-клиента. Роль кнопки определяет её место в панели, реакцию на Enter
 * и Esc. Класс только с константами.</p>
 */
// JavaFX: ButtonType → Swing: SwingButtonType → Web: <button value>
public final class AppButtons {

    /** «Сохранить» — подтверждение. */
    public static final SwingButtonType SAVE = new SwingButtonType("Сохранить", SwingButtonType.Role.OK_DONE);
    /** «Не сохранять». */
    public static final SwingButtonType DONT_SAVE = new SwingButtonType("Не сохранять", SwingButtonType.Role.OTHER);
    /** «Отмена». */
    public static final SwingButtonType CANCEL = SwingButtonType.CANCEL;
    /** «Закрыть». */
    public static final SwingButtonType CLOSE = SwingButtonType.CLOSE;
    /** «Удалить» — подтверждение удаления. */
    public static final SwingButtonType DELETE = new SwingButtonType("Удалить", SwingButtonType.Role.OK_DONE);
    /** «Перезаписать» — сохранить поверх файла. */
    public static final SwingButtonType OVERWRITE = new SwingButtonType("Перезаписать", SwingButtonType.Role.OK_DONE);
    /** «Перечитать» — открыть файл с диска заново. */
    public static final SwingButtonType RELOAD = new SwingButtonType("Перечитать", SwingButtonType.Role.OTHER);
    /** «Открыть» — открыть без восстановления (второй экземпляр). */
    public static final SwingButtonType OPEN = new SwingButtonType("Открыть", SwingButtonType.Role.OK_DONE);
    /** «Выход». */
    public static final SwingButtonType EXIT = new SwingButtonType("Выход", SwingButtonType.Role.CANCEL_CLOSE);
    /** «Применить». */
    public static final SwingButtonType APPLY = new SwingButtonType("Применить", SwingButtonType.Role.OK_DONE);
    /** «Другая папка…» в окне «Папка CashMemory». */
    public static final SwingButtonType OTHER_FOLDER = new SwingButtonType("Другая папка…", SwingButtonType.Role.OTHER);
    /** «Вернуться к CashMemory». */
    public static final SwingButtonType BACK_TO_CASH_MEMORY = new SwingButtonType("Вернуться к CashMemory", SwingButtonType.Role.OTHER);
    /** «Сохранить план в файл…» после восстановления, когда запись сессии не начата. */
    public static final SwingButtonType SAVE_PLAN_FILE = new SwingButtonType("Сохранить план в файл…", SwingButtonType.Role.OK_DONE);
    /** «Продолжить». */
    public static final SwingButtonType CONTINUE = new SwingButtonType("Продолжить", SwingButtonType.Role.CANCEL_CLOSE);
    /** «Завершить аварийно» — симуляция сбоя. */
    public static final SwingButtonType HALT = new SwingButtonType("Завершить аварийно", SwingButtonType.Role.OK_DONE);
    /** «Очистить» — удалить снимки сессии. */
    public static final SwingButtonType CLEAR = new SwingButtonType("Очистить", SwingButtonType.Role.OK_DONE);
    /** «Не открывать» — выйти, если программа уже запущена. */
    public static final SwingButtonType DO_NOT_OPEN = new SwingButtonType("Не открывать", SwingButtonType.Role.CANCEL_CLOSE);
    /** «Актуализировать». */
    public static final SwingButtonType ACTUALIZE = new SwingButtonType("Актуализировать", SwingButtonType.Role.OK_DONE);

    private AppButtons() {
    }
}
