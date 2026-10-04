package ru.cashprediction.core.ui.selftest.paint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;

/**
 * Сохраняет доказательство захвата в новом каталоге; commit записывается последним.
 * Частичная запись остаётся диагностикой, но никогда не становится успешным набором.
 * Работает только в явно выделенной папке самотеста, не управляет пользовательскими планами.
 */
public final class PaintCaptureFiles {
    private PaintCaptureFiles() { }

    /**
     * Создаёт уникальную попытку и синхронно сохраняет четыре связанных файла.
     * Повторная идентичность отклоняется даже после аварийной частичной записи.
     *
     * @param ownedRoot существующий настоящий каталог конкретного тестового запуска
     * @param request идентичность и намерения захвата
     * @param capture согласованные наблюдения настоящего клиента
     * @return каталог набора, принятого повторной проверкой прочитанных байтов
     * @throws IOException при конфликте, ссылке или ошибке записи
     */
    public static Path write(Path ownedRoot, PaintCaptureRequest request, WidgetCapture capture) throws IOException {
        Objects.requireNonNull(request); Objects.requireNonNull(capture);
        capture.requireRequest(request);
        Path root = realDirectory(ownedRoot);
        Path directory = root.resolve(request.captureId().toString());
        Files.createDirectory(directory);
        byte[] raw = capture.rawJson();
        byte[] png = capture.png();
        byte[] paint = PaintObservationCodec.write(capture.observation());
        byte[] commit = PaintObservationCodec.writeCommit(capture.commit(request, raw, paint));
        durableWrite(directory.resolve("raw.json"), raw);
        durableWrite(directory.resolve("capture.png"), png);
        durableWrite(directory.resolve("paint.json"), paint);
        durableWrite(directory.resolve("commit.json"), commit);
        verify(directory, request, capture);
        return directory;
    }

    /**
     * Проверяет точные байты полного набора, не принимая один лишь файл commit.
     *
     * @param directory каталог конкретной попытки
     * @param request исходный запрос тестового оркестратора
     * @param capture исходные согласованные наблюдения
     * @throws IOException при неполном наборе, подмене или ошибке чтения
     */
    public static void verify(Path directory, PaintCaptureRequest request, WidgetCapture capture) throws IOException {
        Path root = realDirectory(directory);
        if (!root.getFileName().toString().equals(request.captureId().toString())) {
            throw new IOException("capture directory identity");
        }
        byte[] raw = read(root.resolve("raw.json"), PaintObservationCodec.MAX_JSON_BYTES);
        byte[] png = read(root.resolve("capture.png"), PaintObservationCodec.MAX_PNG_BYTES);
        byte[] paint = read(root.resolve("paint.json"), PaintObservationCodec.MAX_JSON_BYTES);
        byte[] commit = read(root.resolve("commit.json"), PaintObservationCodec.MAX_JSON_BYTES);
        try {
            if (!Arrays.equals(png, capture.png())) throw new IllegalArgumentException("persisted PNG differs");
            PaintObservationCodec.readCommit(commit).verify(request, capture, raw, paint);
        } catch (RuntimeException error) {
            throw new IOException("capture bundle invalid", error);
        }
    }

    /** Проверяет каждый существующий сегмент, не разрешая ссылочный выход из области теста. */
    private static Path realDirectory(Path path) throws IOException {
        Path absolute = Objects.requireNonNull(path).toAbsolutePath().normalize();
        for (Path current = absolute; current != null; current = current.getParent()) {
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
                throw new IOException("capture directory is not an ordinary directory");
            }
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    /** Пишет новый файл целиком и принудительно сохраняет байты до следующего шага. */
    private static void durableWrite(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    /** Ограничивает чтение, отклоняет ссылки и запрещает неполные пустые документы. */
    private static byte[] read(Path file, int limit) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("capture file is not ordinary");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0 || size > limit) throw new IOException("capture file size");
            ByteBuffer bytes = ByteBuffer.allocate((int) size);
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) throw new IOException("capture file truncated");
            }
            if (channel.size() != size || channel.read(ByteBuffer.allocate(1)) != -1) {
                throw new IOException("capture file changed");
            }
            return bytes.array();
        }
    }
}
