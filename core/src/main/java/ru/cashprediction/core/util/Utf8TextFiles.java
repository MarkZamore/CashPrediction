package ru.cashprediction.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Ограниченное чтение текста без зависимости от репозитория и атомарной записи. */
public final class Utf8TextFiles {
    private Utf8TextFiles() { }

    /**
     * Читает обычный файл в строгой UTF-8, удаляя только первый BOM.
     * Проверяет каждый компонент пути и не следует ссылке при открытии.
     * Лимит проверяется по атрибутам и по фактически прочитанным байтам.
     *
     * @param file канонический путь файла
     * @param maxBytes положительный предел байтов, заданный владельцем формата
     * @return текст без первого BOM
     * @throws IOException при ссылке, превышении лимита, изменении файла или неверной UTF-8
     */
    public static String readString(Path file, long maxBytes) throws IOException {
        Objects.requireNonNull(file, "file");
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("TEXT_LIMIT");
        }
        Path absolute = requireNoLinks(file);
        BasicFileAttributes before = attributes(absolute);
        if (!before.isRegularFile()) throw new IOException("TEXT_NOT_REGULAR");
        if (before.size() > maxBytes) throw new IOException("TEXT_LIMIT");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(before.size(), 8192));
        try (SeekableByteChannel input = Files.newByteChannel(absolute,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long count = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                count += read;
                if (count > maxBytes) throw new IOException("TEXT_LIMIT");
                bytes.write(buffer.array(), 0, read);
                buffer.clear();
            }
        }
        requireNoLinks(absolute);
        BasicFileAttributes after = attributes(absolute);
        if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
            throw new IOException("TEXT_CHANGED");
        }
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** Проверяет существующие компоненты по canonical/no-reparse policy без записи. */
    private static Path requireNoLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (!absolute.equals(absolute.normalize())) throw new IOException("PATH_NOT_CANONICAL");
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            BasicFileAttributes attributes = attributes(current);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("REPARSE_PATH");
            }
            if (!current.equals(absolute) && !attributes.isDirectory()) throw new IOException("ROOT_DIRECTORY");
        }
        return absolute;
    }

    /** Не следует ссылке при чтении атрибутов; отсутствие файла остаётся ошибкой чтения. */
    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
}
