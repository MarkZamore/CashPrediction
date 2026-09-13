package ru.cashprediction.fx.dialog;

import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Типы кнопок диалогов CashPrediction с русскими надписями и ролями {@link ButtonData}.
 *
 * <p>Роль кнопки важнее надписи: по ней {@code ButtonBar} расставляет кнопки в порядке, принятом в
 * Windows, {@code DialogPane} назначает кнопку по умолчанию (Enter) и кнопку отмены (Esc, крестик окна),
 * а {@link AppDialogPane} решает, какую кнопку блокировать, пока форма заполнена с ошибками.</p>
 *
 * <p>Экземпляры {@link ButtonType} неизменяемы, поэтому общие константы безопасно использовать
 * в любом количестве диалогов: каждая {@code DialogPane} создаёт для них свои кнопки.</p>
 */
// JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
public final class AppButtonTypes {

    /** «Сохранить» — подтверждение формы редактора. */
    public static final ButtonType SAVE = new ButtonType("Сохранить", ButtonData.OK_DONE);
    /** «Удалить» — подтверждение удаления (кнопка по умолчанию в Alert удаления). */
    public static final ButtonType DELETE = new ButtonType("Удалить", ButtonData.OK_DONE);
    /** «Далее» — следующая страница мастера. */
    public static final ButtonType NEXT = new ButtonType("Далее", ButtonData.NEXT_FORWARD);
    /** «Назад» — предыдущая страница мастера. */
    public static final ButtonType BACK = new ButtonType("Назад", ButtonData.BACK_PREVIOUS);
    /** «Готово» — завершение мастера. */
    public static final ButtonType FINISH = new ButtonType("Готово", ButtonData.FINISH);
    /** «Отмена». */
    public static final ButtonType CANCEL = new ButtonType("Отмена", ButtonData.CANCEL_CLOSE);
    /** «Закрыть» — для окон без результата. */
    public static final ButtonType CLOSE = new ButtonType("Закрыть", ButtonData.CANCEL_CLOSE);
    /** «OK» для информационных сообщений. */
    public static final ButtonType OK = new ButtonType("OK", ButtonData.OK_DONE);
    /** «Сбросить» — убрать корректировку события (слева, чтобы не нажать случайно вместо «Сохранить»). */
    public static final ButtonType RESET = new ButtonType("Сбросить", ButtonData.LEFT);
    /** «Не сохранять» при выходе или открытии другого плана. */
    public static final ButtonType DONT_SAVE = new ButtonType("Не сохранять", ButtonData.NO);
    /** «Перезаписать» файл, изменённый снаружи. */
    public static final ButtonType OVERWRITE = new ButtonType("Перезаписать", ButtonData.OK_DONE);
    /** «Перечитать» файл, изменённый снаружи (изменения в программе теряются). */
    public static final ButtonType RELOAD = new ButtonType("Перечитать", ButtonData.OTHER);
    /** «Открыть» выбранный план. */
    public static final ButtonType OPEN = new ButtonType("Открыть", ButtonData.OK_DONE);
    /** «Переименовать». */
    public static final ButtonType RENAME = new ButtonType("Переименовать", ButtonData.OK_DONE);
    /** «Сверить» баланс. */
    public static final ButtonType RECONCILE = new ButtonType("Сверить", ButtonData.OK_DONE);
    /** «Выбрать» значение из списка. */
    public static final ButtonType CHOOSE = new ButtonType("Выбрать", ButtonData.OK_DONE);
    /** «Применить». */
    public static final ButtonType APPLY = new ButtonType("Применить", ButtonData.OK_DONE);
    /** «Актуализировать» план на сегодня. */
    public static final ButtonType ACTUALIZE = new ButtonType("Актуализировать", ButtonData.OK_DONE);
    /** «Экспортировать…» — дальше откроется выбор файла. */
    public static final ButtonType EXPORT = new ButtonType("Экспортировать…", ButtonData.OK_DONE);
    /** «Очистить» снимки сессии. */
    public static final ButtonType CLEAR = new ButtonType("Очистить", ButtonData.OK_DONE);
    /** «Записать цель в план» в калькуляторе цели (окно не закрывается). */
    public static final ButtonType GOAL_TO_PLAN = new ButtonType("Записать цель в план", ButtonData.LEFT);
    /** «Включить «что-если»» в калькуляторе цели (окно не закрывается). */
    public static final ButtonType WHAT_IF_ON = new ButtonType("Показать с доп. экономией", ButtonData.LEFT);
    /** «Открыть планы из другой папки…» в окне «Папка CashMemory». */
    public static final ButtonType OTHER_FOLDER = new ButtonType("Открыть планы из другой папки…", ButtonData.OTHER);
    /** «Вернуться к CashMemory» — снова брать планы из штатной папки. */
    public static final ButtonType BACK_TO_CASH_MEMORY = new ButtonType("Вернуться к CashMemory", ButtonData.LEFT);
    /** «Открыть план» из выбранной папки. */
    public static final ButtonType OPEN_FROM_FOLDER = new ButtonType("Открыть план из CashMemory…", ButtonData.LEFT);
    /** «Не восстанавливать» — начать сеанс заново (отмена диалога восстановления). */
    public static final ButtonType NO_RESTORE = new ButtonType("Не восстанавливать", ButtonData.CANCEL_CLOSE);
    /** «Завершить процесс» — симуляция аварии. */
    public static final ButtonType HALT = new ButtonType("Завершить процесс", ButtonData.OK_DONE);
    /** «Открыть без восстановления» — второй экземпляр программы. */
    public static final ButtonType OPEN_WITHOUT_SESSION = new ButtonType("Открыть без восстановления", ButtonData.OK_DONE);
    /** «Выйти». */
    public static final ButtonType EXIT = new ButtonType("Выйти", ButtonData.CANCEL_CLOSE);
    /** «Сохранить план в файл…» — спасти текст плана из снимка. */
    public static final ButtonType SAVE_SNAPSHOT_PLAN = new ButtonType("Сохранить план в файл…", ButtonData.OK_DONE);
    /** «Пропустить» — не сохранять текст плана из снимка. */
    public static final ButtonType SKIP = new ButtonType("Пропустить", ButtonData.CANCEL_CLOSE);

    /** Формат времени снимка на кнопках восстановления: 10:15:30. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private AppButtonTypes() {
    }

    /**
     * Кнопка «Из реестра Windows (сохранено ЧЧ:ММ:СС)». Создаётся для каждого диалога заново, потому что
     * надпись содержит время снимка, а {@link ButtonType} неизменяем.
     *
     * @param savedAt момент снимка в реестре
     * @return тип кнопки
     */
    public static ButtonType fromRegistry(Optional<Instant> savedAt) {
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        return new ButtonType("Из реестра Windows" + savedSuffix(savedAt), ButtonData.OTHER);
    }

    /**
     * Кнопка «Из XML-файла (сохранено ЧЧ:ММ:СС)».
     *
     * @param savedAt момент снимка в XML-файле
     * @return тип кнопки
     */
    public static ButtonType fromXml(Optional<Instant> savedAt) {
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        return new ButtonType("Из XML-файла" + savedSuffix(savedAt), ButtonData.OTHER);
    }

    /**
     * Время в местном часовом поясе для надписей.
     *
     * @param instant момент
     * @return {@code ЧЧ:ММ:СС}
     */
    public static String localTime(Instant instant) {
        return TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    private static String savedSuffix(Optional<Instant> savedAt) {
        return savedAt.map(t -> " (сохранено " + localTime(t) + ")").orElse(" (снимка нет)");
    }
}
