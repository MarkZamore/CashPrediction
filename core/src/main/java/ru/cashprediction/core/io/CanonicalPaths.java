package ru.cashprediction.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Общая проверка data paths по canonical/no-reparse policy установщика, без записи. */
public final class CanonicalPaths {
    private CanonicalPaths() { }

    /**
     * Проверяет существующие компоненты пути, не следуя ссылкам при чтении атрибутов.
     * Отсутствующая папка допустима; прочие ошибки чтения не скрываются.
     *
     * @param path путь будущего или существующего файла/каталога
     * @return абсолютный canonical lexical путь
     * @throws IOException если путь некорректен, содержит ссылку или недоступен
     */
    public static Path requireNoLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (!absolute.equals(absolute.normalize())) throw new IOException("PATH_NOT_CANONICAL");
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            final BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                continue;
            }
            // Та же physical policy, что SafeTree/UpdateFiles; junction не становится обычной папкой.
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("REPARSE_PATH");
            }
            if (!current.equals(absolute) && !attributes.isDirectory()) throw new IOException("ROOT_DIRECTORY");
        }
        return absolute;
    }

    /**
     * Проверяет неизменяемое соответствие runtime home/CashMemory, не создавая папок.
     *
     * @param home домашняя папка приложения
     * @param memory папка CashMemory данного окружения
     * @throws IOException при несовпадении root, ссылке или не-каталоге
     */
    public static void requireCashMemory(Path home, Path memory) throws IOException {
        Path root = requireNoLinks(home);
        Path expected = root.resolve(AppPaths.CASH_MEMORY_DIR);
        if (!memory.toAbsolutePath().equals(expected)) throw new IOException("CASHMEMORY_ROOT");
        requireNoLinks(expected);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ROOT_DIRECTORY");
        }
        if (Files.exists(expected, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ROOT_DIRECTORY");
        }
    }
}
