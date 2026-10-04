package ru.cashprediction.core.update.install;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import ru.cashprediction.core.json.JsonParser;

/** Проверка путей и устойчивая публикация служебных файлов установщика. */
final class InstallFiles {
    static final int MAX_METADATA_BYTES = 32 * 1024 * 1024;

    private InstallFiles() { }

    static void guard(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (!absolute.equals(absolute.normalize())) throw new IOException("PATH_NOT_CANONICAL");
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(current,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // Junction в Windows имеет признак other при чтении без следования ссылкам.
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("REPARSE_PATH");
            if (Files.getFileStore(current).supportsFileAttributeView("dos")) {
                // JDK не публикует произвольный reparse tag: помощник дополнительно проверяет ReparsePoint.
                if (!current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                    throw new IOException("REPARSE_PATH");
                }
            }
        }
    }

    static Path root(Path root, Path memory) throws IOException {
        guard(root);
        Path canonical = root.toRealPath();
        if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ROOT_DIRECTORY");
        Path expected = canonical.resolve("CashMemory");
        if (!memory.toAbsolutePath().equals(expected)) throw new IOException("CASHMEMORY_ROOT");
        guard(expected.resolve("Updates"));
        return canonical;
    }

    static void write(Path path, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) throw new IOException("METADATA_LIMIT");
        guard(path);
        Files.createDirectories(path.getParent());
        guard(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, StandardOpenOption.SYNC)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            guard(path);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            guard(temporary);
            Files.deleteIfExists(temporary);
        }
    }

    static String read(Path path, int maximum) throws IOException {
        guard(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("METADATA_FILE");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("METADATA_LIMIT");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    static java.util.Map<String, Object> object(Path path) throws IOException {
        try {
            return JsonParser.parseObject(read(path, MAX_METADATA_BYTES));
        } catch (RuntimeException failure) {
            throw new IOException("METADATA_JSON", failure);
        }
    }

    static Lock lock(Path updates) throws IOException {
        guard(updates);
        Files.createDirectories(updates);
        guard(updates);
        Path path = updates.resolve("lifecycle.lock");
        guard(path);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            return new Lock(channel, channel.lock());
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    /** Блокировка, общая с FileStream.Lock скрытого помощника. */
    record Lock(FileChannel channel, FileLock lock) implements AutoCloseable {
        /** Освобождает дескриптор и диапазон блокировки. */
        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
