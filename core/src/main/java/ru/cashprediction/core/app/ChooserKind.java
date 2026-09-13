package ru.cashprediction.core.app;

/**
 * Как клиент выбирает файлы и папки (спецификация v2, §6.0 «Выбор файлов», §6.21).
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum ChooserKind {

    /**
     * Нативный диалог ОС: JavaFX {@code FileChooser}/{@code DirectoryChooser}. Нативный диалог сохранения сам
     * спрашивает о замене файла, поэтому ядро не показывает {@code confirm.replaceFile} (§10 №13).
     */
    NATIVE,
    /** Swing {@code JFileChooser} с русскими ключами {@code UIManager}; о замене спрашивает ядро. */
    SWING,
    /**
     * Web: браузер не видит файловую систему, поэтому ядро открывает свою форму «Выбор файла»
     * ({@code FileBrowserForm}, представление {@code FILE_BROWSER}); {@code UiPort.chooseFile} не вызывается.
     */
    SERVER_BROWSER
}
