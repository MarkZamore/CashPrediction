package ru.cashprediction.core.update.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Общие строгие ограничения формата, путей и идентичностей обновления. */
public final class UpdateValidation {
    /** Максимальный размер контейнера и одного файла. */
    public static final long MAX_FILE = 512L * 1024 * 1024;
    /** Максимальный размер распакованного дерева. */
    public static final long MAX_TREE = 2L * 1024 * 1024 * 1024;
    /** Максимальный размер JSON в байтах UTF-8. */
    public static final int MAX_JSON = 8 * 1024 * 1024;
    /** Максимальное количество файлов дерева. */
    public static final int MAX_FILES = 20000;
    /** Имя зафиксированного алгоритма. */
    public static final String ALGORITHM = "cashprediction-tree-delta";
    private UpdateValidation() { }

    /** Отвергает непарные суррогаты, не изменяя строку. */
    public static void unicode(String value) throws IOException {
        if (value == null) throw new IOException("NULL_TEXT");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IOException("INVALID_UNICODE");
            } else if (Character.isLowSurrogate(c)) throw new IOException("INVALID_UNICODE");
        }
    }

    /** Проверяет безопасный относительный путь с разделителем /. */
    public static void path(String value) throws IOException {
        unicode(value);
        if (value.isEmpty() || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || value.getBytes(StandardCharsets.UTF_8).length > 65535)
            throw new IOException("INVALID_PATH");
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")
                    || part.endsWith(".") || part.endsWith(" ")) throw new IOException("INVALID_SEGMENT");
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (Character.isISOControl(c) || "\\:<>\"|?*".indexOf(c) >= 0)
                    throw new IOException("UNSAFE_CHARACTER");
            }
            String stem = part.split("\\.", 2)[0].replaceFirst(" +$", "").toUpperCase(Locale.ROOT);
            if (Set.of("CON", "PRN", "AUX", "NUL", "CLOCK$", "CONIN$", "CONOUT$").contains(stem)
                    || stem.matches("(?:COM|LPT)[1-9¹²³]")) throw new IOException("RESERVED_NAME");
        }
    }

    /** Проверяет, что файл входит в управляемую часть приложения. */
    public static void managedPath(String value) throws IOException {
        path(value);
        if (!(value.equals("CashPrediction.exe") || value.equals("CashPrediction-Swing.exe")
                || value.equals("CashPrediction-Web.exe") || value.startsWith("app/")
                || value.startsWith("runtime/"))) throw new IOException("UNMANAGED_PATH");
    }

    /** Возвращает ключ сравнения Windows без нормализации опасных входов. */
    public static String collisionKey(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toUpperCase(Locale.ROOT);
    }

    /** Сравнивает пути по беззнаковым байтам UTF-8. */
    public static int comparePaths(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Проверяет SHA в канонической нижней шестнадцатеричной записи. */
    public static void hash(String value, int digits) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{" + digits + "}")) throw new IOException("INVALID_HASH");
    }

    /** Проверяет инвентарь, включая коллизии каталогов и файлов. */
    public static void files(List<FileEntry> entries, boolean sorted) throws IOException {
        if (entries == null || entries.size() > MAX_FILES) throw new IOException("FILE_COUNT");
        Map<String, String> nodes = new HashMap<>();
        Set<String> files = new HashSet<>();
        long total = 0;
        String previous = null;
        for (FileEntry f : entries) {
            if (f == null) throw new IOException("NULL_FILE");
            managedPath(f.path());
            hash(f.sha256(), 64);
            if (f.sizeBytes() < 0 || f.sizeBytes() > MAX_FILE) throw new IOException("FILE_SIZE");
            total += f.sizeBytes();
            if (total > MAX_TREE) throw new IOException("TREE_SIZE");
            if (sorted && previous != null && comparePaths(previous, f.path()) >= 0)
                throw new IOException("FILE_ORDER");
            previous = f.path();
            String key = collisionKey(f.path());
            if (!files.add(key)) throw new IOException("DUPLICATE_FILE");
            String node = f.path();
            while (true) {
                String old = nodes.putIfAbsent(collisionKey(node), node);
                if (old != null && !old.equals(node)) throw new IOException("PATH_COLLISION");
                int slash = node.lastIndexOf('/');
                if (slash < 0) break;
                node = node.substring(0, slash);
            }
        }
        for (FileEntry f : entries) {
            String p = f.path();
            while (p.contains("/")) {
                p = p.substring(0, p.lastIndexOf('/'));
                if (files.contains(collisionKey(p))) throw new IOException("FILE_DIRECTORY_COLLISION");
            }
        }
    }

    /** Проверяет установленную релизную идентичность. */
    public static void installed(InstalledVersion version) throws IOException {
        if (version == null || version.releaseNumber() <= 0) throw new IOException("INVALID_RELEASE");
        hash(version.commitSha(), 40);
        hash(version.treeSha256(), 64);
    }

    /** Проверяет дескриптор контейнера дельты. */
    public static void delta(DeltaPatch d) throws IOException {
        if (d == null) throw new IOException("NULL_DELTA");
        installed(new InstalledVersion(d.baseReleaseNumber(), d.baseCommitSha(), d.baseTreeSha256()));
        path(d.assetName());
        if (!d.assetName().equals("CashPrediction.cpdelta")
                && !d.assetName().equals("CashPrediction.from-" + d.baseReleaseNumber() + ".cpdelta"))
            throw new IOException("DELTA_ASSET");
        if (d.sizeBytes() <= 0 || d.sizeBytes() > MAX_FILE) throw new IOException("CONTAINER_SIZE");
        hash(d.sha256(), 64);
    }

    /** Проверяет манифест и уникальность прямых баз. */
    public static void manifest(UpdateManifest m) throws IOException {
        if (m == null) throw new IOException("NULL_MANIFEST");
        installed(new InstalledVersion(m.releaseNumber(), m.commitSha(), m.treeSha256()));
        unicode(m.version());
        if (m.version().isBlank() || m.publishedAtUtc() == null) throw new IOException("MANIFEST_METADATA");
        if (!"CashPrediction-portable.zip".equals(m.assetName())) throw new IOException("FULL_ASSET");
        if (m.sizeBytes() <= 0 || m.sizeBytes() > MAX_FILE) throw new IOException("CONTAINER_SIZE");
        hash(m.sha256(), 64);
        files(m.files(), true);
        if (m.deltaPatches().size() > 2) throw new IOException("DELTA_COUNT");
        Set<String> bases = new HashSet<>();
        Set<Integer> releases = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (DeltaPatch d : m.deltaPatches()) {
            delta(d);
            if (d.baseReleaseNumber() >= m.releaseNumber() || d.baseCommitSha().equals(m.commitSha())
                    || !releases.add(d.baseReleaseNumber()) || !bases.add(d.baseCommitSha()) || !names.add(d.assetName()))
                throw new IOException("DELTA_BASE");
        }
    }
}
