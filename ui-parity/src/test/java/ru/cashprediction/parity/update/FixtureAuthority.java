package ru.cashprediction.parity.update;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ru.cashprediction.core.update.model.FileEntry;

/** Независимая от production engine инвентаризация и формула замороженного tree digest. */
public final class FixtureAuthority {
    private FixtureAuthority() { }

    /** Проверяет предков и дерево без перехода по ссылкам или reparse points. */
    public static void noLinks(Path root) throws IOException {
        for (Path p = root.toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) check(p);
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) check(path);
        }
    }

    private static void check(Path p) throws IOException {
        var attributes = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("FIXTURE_LINK");
    }

    /** Читает все управляемые файлы, игнорируя CashMemory и посторонние файлы корня. */
    public static List<FileEntry> managed(Path root) throws IOException {
        noLinks(root);
        List<FileEntry> files = new ArrayList<>();
        long total = 0;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(p).toString().replace('\\', '/');
                if (relative.startsWith("app/") || relative.startsWith("runtime/")
                        || List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe").contains(relative)) {
                    boolean ro = Files.getFileStore(p).supportsFileAttributeView("dos")
                            && Files.readAttributes(p, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isReadOnly();
                    files.add(new FileEntry(relative, Files.size(p), sha(p), ro));
                    total += Files.size(p);
                    if (files.size() > 20000 || total > 2L * 1024 * 1024 * 1024) throw new IOException("FIXTURE_TREE_LIMIT");
                }
            }
        }
        files.sort((a, b) -> Arrays.compareUnsigned(a.path().getBytes(StandardCharsets.UTF_8), b.path().getBytes(StandardCharsets.UTF_8)));
        return List.copyOf(files);
    }

    /** Сохраняет содержимое и атрибуты пользовательских файлов; Updates исключён по границе каталога. */
    public static Map<String, String> user(Path root) throws IOException {
        noLinks(root);
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path p : paths.toList()) {
                String relative = root.relativize(p).toString().replace('\\', '/');
                if (relative.equals("CashMemory/Updates") || relative.startsWith("CashMemory/Updates/")) continue;
                if (!relative.isEmpty() && !relative.equals("app") && !relative.startsWith("app/")
                        && !relative.equals("runtime") && !relative.startsWith("runtime/")
                        && !List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe").contains(relative)) {
                    result.put(relative, Files.isDirectory(p) ? "directory" : sha(p) + ":" + Files.size(p)
                            + ":" + (Files.getFileStore(p).supportsFileAttributeView("dos")
                            && Files.readAttributes(p, DosFileAttributes.class).isReadOnly()));
                }
            }
        }
        return result;
    }

    /** Вычисляет SHA-256 по точным байтам, без использования кода update engine. */
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Хеширует большой контейнер потоком с ограничением контракта в 512 MiB. */
    public static String sha(Path path) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; long total = 0; int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count; if (total > 512L * 1024 * 1024) throw new IOException("FIXTURE_FILE_LIMIT");
                    hash.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Кодирует независимый big-endian вектор tree-v1; timestamps не учитываются. */
    public static String treeHash(List<FileEntry> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write("cashprediction-tree-v1".getBytes(StandardCharsets.UTF_8)); out.writeByte(0);
            for (FileEntry file : files) {
                byte[] path = file.path().getBytes(StandardCharsets.UTF_8);
                out.writeInt(path.length); out.write(path); out.writeLong(file.sizeBytes());
                out.write(HexFormat.of().parseHex(file.sha256())); out.writeByte(file.readOnly() ? 1 : 0);
            }
        }
        return sha(bytes.toByteArray());
    }
}
