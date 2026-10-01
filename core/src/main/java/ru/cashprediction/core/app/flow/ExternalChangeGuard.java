package ru.cashprediction.core.app.flow;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.io.IOException;

/**
 * Проверка изменения файла плана другой программой (спецификация v2, §6.14): по времени изменения файла, запомненному
 * при открытии и после каждой записи.
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class ExternalChangeGuard {

    /** Файл, для которого запомнено состояние. */
    private Path rememberedFile;
    /** Время изменения в момент открытия или успешной записи; {@code null}, если файла тогда не было. */
    private FileTime rememberedTime;

    /** Создаёт проверку без запомненного файла. */
    public ExternalChangeGuard() {
    }

    /**
     * Запоминает время изменения файла (после открытия или записи).
     *
     * @param file файл плана
     */
    public void remember(Path file) {
        rememberedFile = file == null ? null : file.toAbsolutePath().normalize();
        rememberedTime = readTime(rememberedFile);
    }

    /**
     * Изменён ли файл снаружи после {@link #remember(Path)}.
     *
     * @param file файл плана
     * @return {@code true}, если время изменения отличается или файл исчез
     */
    public boolean changedExternally(Path file) {
        Path normalized = file == null ? null : file.toAbsolutePath().normalize();
        if (rememberedFile == null || normalized == null || !rememberedFile.equals(normalized)) {
            return false;
        }
        FileTime current = readTime(normalized);
        return current == null || !current.equals(rememberedTime);
    }

    /** Забывает файл (план без файла). */
    public void forget() {
        rememberedFile = null;
        rememberedTime = null;
    }

    /** Читает метку изменения; ошибка чтения означает, что файл нельзя считать неизменным. */
    private static FileTime readTime(Path file) {
        if (file == null) {
            return null;
        }
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : null;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }
}
