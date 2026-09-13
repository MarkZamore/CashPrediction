package ru.cashprediction.core.app;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Запрос выбора папки (спецификация v2, §6.21, строка «Папка»; §6.22 «Открыть планы из другой папки…»):
 * JavaFX {@code DirectoryChooser} → Swing {@code JFileChooser(DIRECTORIES_ONLY)} → Web окно ядра «Выбор файла»
 * в режиме папок. Не восстанавливается после сбоя.
 *
 * @param title         заголовок окна «Папка с планами (сейчас: {path})»
 * @param initialFolder начальная папка — текущая папка планов
 */
public record DirectoryChooserSpec(String title, Path initialFolder) {

    /** Проверяет поля. */
    public DirectoryChooserSpec {
        title = Objects.requireNonNullElse(title, "");
        Objects.requireNonNull(initialFolder, "initialFolder");
    }
}
