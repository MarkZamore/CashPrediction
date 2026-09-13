package ru.cashprediction.core.io;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * Сведения о файле плана в CashMemory для списка «Открыть план» и меню «Недавние».
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param name         имя плана: имя файла без {@code .md}
 * @param path         путь к файлу
 * @param lastModified время последнего изменения файла (для сортировки и обнаружения внешних правок)
 */
public record PlanFileInfo(String name, Path path, FileTime lastModified) {

    /** Проверяет обязательные поля. */
    public PlanFileInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(lastModified, "lastModified");
    }
}
