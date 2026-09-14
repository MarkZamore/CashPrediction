package ru.cashprediction.core.app.flow;

import java.nio.file.Path;

/**
 * Проверка изменения файла плана другой программой (спецификация v2, §6.14): по времени изменения файла, запомненному
 * при открытии и после каждой записи.
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class ExternalChangeGuard {

    /** Создаёт проверку без запомненного файла. */
    public ExternalChangeGuard() {
    }

    /**
     * Запоминает время изменения файла (после открытия или записи).
     *
     * @param file файл плана
     */
    public void remember(Path file) {
        throw new UnsupportedOperationException("S2: core-app-file - ExternalChangeGuard.remember");
    }

    /**
     * Изменён ли файл снаружи после {@link #remember(Path)}.
     *
     * @param file файл плана
     * @return {@code true}, если время изменения отличается или файл исчез
     */
    public boolean changedExternally(Path file) {
        throw new UnsupportedOperationException("S2: core-app-file - ExternalChangeGuard.changedExternally");
    }

    /** Забывает файл (план без файла). */
    public void forget() {
        throw new UnsupportedOperationException("S2: core-app-file - ExternalChangeGuard.forget");
    }
}
