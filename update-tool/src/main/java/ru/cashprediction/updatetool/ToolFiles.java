package ru.cashprediction.updatetool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Безопасные пути и ограниченный потоковый ввод-вывод артефактов сборочного инструмента. */
final class ToolFiles {
    private static final long MAX_CONTAINER = 512L * 1024 * 1024;
    private static final int MAX_JSON = 8 * 1024 * 1024;

    private ToolFiles() { }

    /** Фактическая идентичность прочитанного контейнера. */
    record Digest(long size, String sha256) { }

    static Path path(String text) throws IOException {
        // Для CLI допустим абсолютный путь хоста, но недопустимы UNC, ADS и опасные сегменты.
        if (text.startsWith("\\\\") || text.startsWith("//")) throw new IOException("UNSAFE_PATH");
        String separators = text.replace('\\', '/');
        if (separators.contains("//")) throw new IOException("EMPTY_PATH_SEGMENT");
        Path path;
        try { path = Path.of(text); }
        catch (java.nio.file.InvalidPathException ex) { throw new IOException("INVALID_HOST_PATH", ex); }
        for (Path segment : path) checkSegment(segment.toString());
        Path absolute = path.toAbsolutePath();
        checkAncestors(absolute);
        // Канонические существующие родители закрывают обход через Windows short-name aliases.
        Path existing = absolute;
        java.util.ArrayDeque<Path> missing = new java.util.ArrayDeque<>();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
            if (existing == null) throw new IOException("PATH_ROOT_MISSING");
        }
        Path canonical = existing.toRealPath(LinkOption.NOFOLLOW_LINKS);
        for (Path segment : missing) canonical = canonical.resolve(segment);
        return canonical;
    }

    private static void checkSegment(String value) throws IOException {
        if (value.isEmpty() || value.equals(".") || value.equals("..")
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || value.endsWith(".") || value.endsWith(" ")
                || value.codePoints().anyMatch(c -> Character.isISOControl(c)
                        || c >= 0xD800 && c <= 0xDFFF || "<>:\"/\\|?*".indexOf(c) >= 0)) {
            throw new IOException("UNSAFE_PATH_SEGMENT");
        }
        // Пробел перед расширением не снимает запрет на имя устройства Windows.
        String stem = value.split("\\.", 2)[0].replaceFirst(" +$", "").toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|CLOCK\\$|COM[1-9¹²³]|LPT[1-9¹²³]|CONIN\\$|CONOUT\\$")) {
            throw new IOException("RESERVED_PATH_SEGMENT");
        }
    }

    static void checkAncestors(Path path) throws IOException {
        Path current = path.getRoot();
        if (current == null) throw new IOException("ABSOLUTE_PATH_REQUIRED");
        for (Path segment : path) {
            checkSegment(segment.toString());
            current = current.resolve(segment);
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException ex) {
                continue;
            }
            if (attrs.isSymbolicLink() || attrs.isOther()
                    || !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("LINK_OR_REPARSE_PATH");
            }
            if (!current.equals(path) && !attrs.isDirectory()) throw new IOException("NON_DIRECTORY_ANCESTOR");
        }
    }

    static void output(Path output, List<Path> roots, List<Path> inputs) throws IOException {
        checkAncestors(output);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("OUTPUT_EXISTS");
        if (output.getParent() == null || !Files.isDirectory(output.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("OUTPUT_PARENT_MISSING");
        }
        String destination = folded(output);
        for (Path root : roots) {
            String source = folded(root);
            if (inside(destination, source) || inside(source, destination)) throw new IOException("OUTPUT_TREE_OVERLAP");
        }
        for (Path input : inputs) {
            if (inside(folded(input), destination) || inside(destination, folded(input))) {
                throw new IOException("OUTPUT_INPUT_OVERLAP");
            }
        }
    }

    private static boolean inside(String child, String parent) {
        return child.equals(parent) || child.startsWith(parent.endsWith("/") ? parent : parent + "/");
    }

    private static String folded(Path path) {
        return Normalizer.normalize(path.toAbsolutePath().toString().replace('\\', '/'), Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT);
    }

    static String json(Path input) throws IOException {
        checkAncestors(input);
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.size(input) > MAX_JSON) {
            throw new IOException("JSON_LIMIT_OR_TYPE");
        }
        byte[] bytes;
        try (InputStream stream = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(MAX_JSON + 1);
        }
        if (bytes.length > MAX_JSON) throw new IOException("JSON_LIMIT");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static Digest digest(Path input) throws IOException {
        checkAncestors(input);
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CONTAINER_TYPE");
        if (Files.size(input) > MAX_CONTAINER) throw new IOException("CONTAINER_LIMIT");
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream stream = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                size += count;
                if (size > MAX_CONTAINER) throw new IOException("CONTAINER_LIMIT");
                sha.update(buffer, 0, count);
            }
        }
        return new Digest(size, HexFormat.of().formatHex(sha.digest()));
    }

    static void checkDigest(Path input, long expectedSize, String expectedSha) throws IOException {
        Digest actual = digest(input);
        if (actual.size() != expectedSize || !actual.sha256().equals(expectedSha)) {
            throw new IOException("CONTAINER_IDENTITY_MISMATCH");
        }
    }

    static void write(Path output, String text) throws IOException {
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON) throw new IOException("JSON_LIMIT");
        checkAncestors(output);
        Path temporary = Files.createTempFile(output.getParent(), ".cp-tool-", ".json");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            checkAncestors(output);
            // Без REPLACE_EXISTING: появившийся параллельно output также не перезаписывается.
            Files.move(temporary, output);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void forceAndPublish(Path temporary, Path output) throws IOException {
        checkAncestors(temporary);
        if (Files.size(temporary) > MAX_CONTAINER) throw new IOException("CONTAINER_LIMIT");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        checkAncestors(output);
        Files.move(temporary, output);
    }

    static void removeScratch(Path scratch) throws IOException {
        // Удаляется только уникальная созданная CLI папка; обход не следует ссылкам.
        checkAncestors(scratch);
        Files.walkFileTree(scratch, new SimpleFileVisitor<>() {
            /** Удаляет свой временный файл, предварительно снимая целевой readOnly. */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isSymbolicLink() && !attrs.isOther()) {
                    DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (dos != null) dos.setReadOnly(false);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            /** Удаляет свой временный каталог после его детей. */
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
