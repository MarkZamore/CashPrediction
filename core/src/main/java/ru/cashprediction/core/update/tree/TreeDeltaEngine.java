package ru.cashprediction.core.update.tree;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.*;

/** Потоковый движок инвентаризации и детерминированных прямых ZIP-дельт. */
public final class TreeDeltaEngine {
    private static final List<String> ROOTS = List.of("CashPrediction.exe", "CashPrediction-Swing.exe",
            "CashPrediction-Web.exe", "app", "runtime");
    // 01.01.1980 в JDK является специальным DOS sentinel: setTimeLocal добавляет
    // UTC extra timestamp через системную зону. Следующий день кодируется только
    // DOS-полем и сохраняет одинаковые байты патча в любых часовых поясах.
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 2, 0, 0);
    private TreeDeltaEngine() { }

    /** Инвентаризирует только управляемые файлы, сохраняя сторонние данные корня. */
    public static List<FileEntry> inventory(Path root) throws IOException {
        Path safe = SafeTree.root(root);
        List<FileEntry> files = new ArrayList<>();
        Map<String, String> nodes = new HashMap<>();
        Set<Object> fileKeys = new HashSet<>();
        Map<String, List<FileIdentity>> identities = new HashMap<>();
        long[] total = {0};
        try (DirectoryStream<Path> children = Files.newDirectoryStream(safe)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                for (String managed : ROOTS) {
                    if (UpdateValidation.collisionKey(name).equals(UpdateValidation.collisionKey(managed))
                            && !name.equals(managed)) throw new IOException("MANAGED_ROOT_COLLISION");
                }
                if (!ROOTS.contains(name)) continue;
                BasicFileAttributes a = SafeTree.attributes(child);
                if (name.endsWith(".exe")) {
                    if (!a.isRegularFile()) throw new IOException("LAUNCHER_TYPE");
                    total[0] += a.size();
                    if (total[0] > UpdateValidation.MAX_TREE) throw new IOException("TREE_SIZE");
                    FileEntry entry = SafeTree.entry(safe, child);
                    uniqueFile(child, a, entry, fileKeys, identities);
                    files.add(entry);
                } else {
                    if (!a.isDirectory()) throw new IOException("MANAGED_DIRECTORY_TYPE");
                    Files.walkFileTree(child, new SimpleFileVisitor<>() {
                        private void node(Path p) throws IOException {
                            String relative = safe.relativize(p).toString().replace(File.separatorChar, '/');
                            UpdateValidation.path(relative);
                            String old = nodes.putIfAbsent(UpdateValidation.collisionKey(relative), relative);
                            if (old != null) throw new IOException("TREE_PATH_COLLISION");
                            SafeTree.attributes(p);
                        }
                        /** Проверяет каталог до обхода его детей. */
                        @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes attrs) throws IOException {
                            node(p);
                            return FileVisitResult.CONTINUE;
                        }
                        /** Хеширует обычный файл без следования ссылке. */
                        @Override public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                            node(p);
                            BasicFileAttributes checked = SafeTree.attributes(p);
                            total[0] += attrs.size();
                            if (total[0] > UpdateValidation.MAX_TREE) throw new IOException("TREE_SIZE");
                            if (files.size() >= UpdateValidation.MAX_FILES) throw new IOException("FILE_COUNT");
                            FileEntry entry = SafeTree.entry(safe, p);
                            uniqueFile(p, checked, entry, fileKeys, identities);
                            files.add(entry);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        }
        files.sort((a, b) -> UpdateValidation.comparePaths(a.path(), b.path()));
        UpdateValidation.files(files, true);
        return List.copyOf(files);
    }

    /** Вычисляет канонический SHA-256 по framing tree-v1; порядок входа не важен. */
    public static String treeHash(List<FileEntry> files) throws IOException {
        UpdateValidation.files(files, false);
        List<FileEntry> sorted = new ArrayList<>(files);
        sorted.sort((a, b) -> UpdateValidation.comparePaths(a.path(), b.path()));
        var md = SafeTree.digest();
        md.update("cashprediction-tree-v1".getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        for (FileEntry f : sorted) {
            byte[] path = f.path().getBytes(StandardCharsets.UTF_8);
            md.update(ByteBuffer.allocate(4).putInt(path.length).array());
            md.update(path);
            md.update(ByteBuffer.allocate(8).putLong(f.sizeBytes()).array());
            md.update(HexFormat.of().parseHex(f.sha256()));
            md.update((byte) (f.readOnly() ? 1 : 0));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Сверяет каждый фактический файл, атрибут и итоговый хеш управляемого дерева. */
    public static void verify(Path root, List<FileEntry> files, String treeSha256) throws IOException {
        UpdateValidation.files(files, true);
        UpdateValidation.hash(treeSha256, 64);
        if (!treeHash(files).equals(treeSha256) || !inventory(root).equals(files))
            throw new IOException("TREE_DIGEST");
    }

    /** Создаёт прямую дельту из двух проверенных деревьев в новый выходной файл. */
    public static void create(Path baseRoot, InstalledVersion base, Path targetRoot,
                              UpdateManifest target, Path output) throws IOException {
        transition(base, target);
        Path oldRoot = SafeTree.root(baseRoot), newRoot = SafeTree.root(targetRoot);
        List<FileEntry> oldFiles = inventory(oldRoot);
        verify(oldRoot, oldFiles, base.treeSha256());
        verify(newRoot, target.files(), target.treeSha256());
        List<FileEntry> payload = changed(oldFiles, target.files());
        Path out = SafeTree.vacant(output);
        if (out.startsWith(oldRoot) || out.startsWith(newRoot)) throw new IOException("OUTPUT_INSIDE_INPUT");
        byte[] json = patchJson(base, target, payload).getBytes(StandardCharsets.UTF_8);
        if (json.length > UpdateValidation.MAX_JSON) throw new IOException("JSON_SIZE");
        boolean created = false;
        try {
            OutputStream file = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            created = true;
            try (ZipOutputStream zip = new ZipOutputStream(limited(file), StandardCharsets.UTF_8)) {
                zip.setLevel(9);
                List<String> names = new ArrayList<>();
                names.add("patch.json");
                for (FileEntry f : payload) names.add("payload/" + f.path());
                names.sort(UpdateValidation::comparePaths);
                Map<String, FileEntry> byName = new HashMap<>();
                for (FileEntry f : payload) byName.put("payload/" + f.path(), f);
                for (String name : names) {
                    ZipEntry entry = new ZipEntry(name);
                    entry.setTimeLocal(ZIP_TIME);
                    zip.putNextEntry(entry);
                    if (name.equals("patch.json")) zip.write(json);
                    else {
                        FileEntry f = byName.get(name);
                        Path source = SafeTree.resolve(newRoot, f.path());
                        if (!SafeTree.entry(newRoot, source).equals(f)) throw new IOException("SOURCE_CHANGED");
                        try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                            SafeTree.copy(in, zip, f);
                        }
                    }
                    zip.closeEntry();
                }
            }
            verify(oldRoot, oldFiles, base.treeSha256());
            verify(newRoot, target.files(), target.treeSha256());
            ZipDirectory.inspect(out);
        } catch (IOException | RuntimeException e) {
            IOException failure = io(e);
            if (created) {
                try { Files.delete(out); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /** Применяет дельту к проверенной базе, создавая только отсутствующий каталог. */
    public static void apply(Path baseRoot, InstalledVersion base, Path patch,
                             UpdateManifest target, Path destination) throws IOException {
        transition(base, target);
        Path root = SafeTree.root(baseRoot), out = SafeTree.vacant(destination);
        if (out.startsWith(root)) throw new IOException("DESTINATION_INSIDE_BASE");
        List<FileEntry> oldFiles = inventory(root);
        verify(root, oldFiles, base.treeSha256());
        DeltaPatch descriptor = null;
        for (DeltaPatch d : target.deltaPatches()) {
            if (d.baseReleaseNumber() == base.releaseNumber() && d.baseCommitSha().equals(base.commitSha())
                    && d.baseTreeSha256().equals(base.treeSha256())) descriptor = d;
        }
        if (descriptor == null) throw new IOException("PATCH_DESCRIPTOR_MISSING");
        container(patch, descriptor.sizeBytes(), descriptor.sha256());
        Map<String, ZipDirectory.Entry> entries = ZipDirectory.inspect(patch);
        ZipDirectory.Entry metadata = entries.get("patch.json");
        if (metadata == null || metadata.size() > UpdateValidation.MAX_JSON) throw new IOException("PATCH_METADATA");
        boolean created = false;
        try (ZipFile zip = new ZipFile(patch.toFile(), StandardCharsets.UTF_8)) {
            Map<String, Object> m;
            try (InputStream in = ZipDirectory.stream(zip, metadata)) {
                m = UpdateCodec.object(UpdateCodec.parse(ZipDirectory.utf8(in.readNBytes(UpdateValidation.MAX_JSON + 1))));
            }
            List<FileEntry> payload = readPatch(m, base, target);
            if (!payload.equals(changed(oldFiles, target.files()))) throw new IOException("PATCH_PAYLOAD_SET");
            Set<String> expected = new HashSet<>(); expected.add("patch.json");
            for (FileEntry f : payload) {
                String name = "payload/" + f.path(); expected.add(name);
                ZipDirectory.Entry entry = entries.get(name);
                if (entry == null || entry.size() != f.sizeBytes()) throw new IOException("PATCH_PAYLOAD_SIZE");
            }
            if (!entries.keySet().equals(expected)) throw new IOException("PATCH_ENTRIES");
            Files.createDirectory(out); created = true;
            Set<String> payloadPaths = new HashSet<>();
            for (FileEntry f : payload) payloadPaths.add(f.path());
            for (FileEntry f : target.files()) {
                if (payloadPaths.contains(f.path())) {
                    try (InputStream in = ZipDirectory.stream(zip, entries.get("payload/" + f.path()))) {
                        SafeTree.write(out, f, in);
                    }
                } else {
                    Path source = SafeTree.resolve(root, f.path());
                    if (!SafeTree.entry(root, source).equals(f)) throw new IOException("BASE_CHANGED");
                    try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                        SafeTree.write(out, f, in);
                    }
                }
            }
            verify(root, oldFiles, base.treeSha256());
            verify(out, target.files(), target.treeSha256());
            container(patch, descriptor.sizeBytes(), descriptor.sha256());
        } catch (IOException | RuntimeException e) {
            IOException failure = io(e);
            if (created) SafeTree.cleanup(out, failure);
            throw failure;
        }
    }

    /** Извлекает полный ZIP с единственным корнем CashPrediction в новый каталог. */
    public static void extractFull(Path archive, UpdateManifest target, Path destination) throws IOException {
        target(target);
        Path out = SafeTree.vacant(destination);
        container(archive, target.sizeBytes(), target.sha256());
        Map<String, ZipDirectory.Entry> entries = ZipDirectory.inspect(archive);
        if (entries.isEmpty()) throw new IOException("FULL_ROOT_MISSING");
        Set<String> expected = new HashSet<>();
        for (FileEntry f : target.files()) expected.add("CashPrediction/" + f.path());
        Set<String> actual = new HashSet<>();
        for (ZipDirectory.Entry e : entries.values()) {
            if (!e.name().startsWith("CashPrediction/")) throw new IOException("FULL_ROOT");
            if (e.name().endsWith("/")) {
                String relative = e.name().equals("CashPrediction/") ? ""
                        : e.name().substring("CashPrediction/".length(), e.name().length() - 1);
                if (!(relative.isEmpty() || relative.equals("app") || relative.equals("runtime")
                        || relative.startsWith("app/") || relative.startsWith("runtime/")))
                    throw new IOException("FULL_DIRECTORY");
            } else actual.add(e.name());
        }
        if (!actual.equals(expected)) throw new IOException("FULL_ENTRIES");
        for (FileEntry f : target.files())
            if (entries.get("CashPrediction/" + f.path()).size() != f.sizeBytes()) throw new IOException("FULL_FILE_SIZE");
        boolean created = false;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            // Даже пустые directory entries проверяются по CRC до создания дерева.
            for (ZipDirectory.Entry e : entries.values()) if (e.name().endsWith("/")) {
                try (InputStream in = ZipDirectory.stream(zip, e)) {
                    if (in.read() != -1) throw new IOException("DIRECTORY_CONTENT");
                }
            }
            Files.createDirectory(out); created = true;
            for (FileEntry f : target.files()) {
                try (InputStream in = ZipDirectory.stream(zip, entries.get("CashPrediction/" + f.path()))) {
                    SafeTree.write(out, f, in);
                }
            }
            verify(out, target.files(), target.treeSha256());
            container(archive, target.sizeBytes(), target.sha256());
        } catch (IOException | RuntimeException e) {
            IOException failure = io(e);
            if (created) SafeTree.cleanup(out, failure);
            throw failure;
        }
    }

    private static void container(Path p, long size, String hash) throws IOException {
        Path file = SafeTree.absolute(p);
        if (SafeTree.attributes(file).size() != size || !SafeTree.hash(file).equals(hash))
            throw new IOException("CONTAINER_DIGEST");
    }

    private static void target(UpdateManifest target) throws IOException {
        UpdateValidation.manifest(target);
        if (!treeHash(target.files()).equals(target.treeSha256())) throw new IOException("TARGET_TREE");
    }

    private static void transition(InstalledVersion base, UpdateManifest target) throws IOException {
        UpdateValidation.installed(base);
        target(target);
        if (target.releaseNumber() <= base.releaseNumber()) throw new IOException("DOWNGRADE");
        if (target.commitSha().equals(base.commitSha())) throw new IOException("SAME_COMMIT");
    }

    /** Путь и необязательный идентификатор физического файла для одного обхода. */
    private record FileIdentity(Path path, Object key) { }

    // Windows-провайдер JDK вправе возвращать null вместо fileKey. Тогда
    // isSameFile проверяет физическую идентичность, а не совпадение содержимого.
    // Хеш и размер сужают поиск: независимые одинаковые файлы разрешены.
    private static void uniqueFile(Path path, BasicFileAttributes attrs, FileEntry entry,
                                   Set<Object> keys, Map<String, List<FileIdentity>> identities) throws IOException {
        Object key = attrs.fileKey();
        if (key != null && !keys.add(key)) throw new IOException("TREE_FILE_ALIAS");
        String content = entry.sha256() + ":" + entry.sizeBytes();
        List<FileIdentity> peers = identities.computeIfAbsent(content, ignored -> new ArrayList<>());
        for (FileIdentity peer : peers) {
            if (key == null || peer.key() == null) {
                SafeTree.attributes(peer.path());
                SafeTree.attributes(path);
                if (Files.isSameFile(path, peer.path())) throw new IOException("TREE_FILE_ALIAS");
            }
        }
        peers.add(new FileIdentity(path, key));
    }

    private static List<FileEntry> changed(List<FileEntry> base, List<FileEntry> target) {
        Map<String, FileEntry> previous = new HashMap<>();
        for (FileEntry f : base) previous.put(f.path(), f);
        return target.stream().filter(f -> !f.equals(previous.get(f.path()))).toList();
    }

    private static String patchJson(InstalledVersion base, UpdateManifest target, List<FileEntry> payload) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1); m.put("algorithm", UpdateValidation.ALGORITHM); m.put("algorithmVersion", 1);
        m.put("baseReleaseNumber", base.releaseNumber()); m.put("baseCommitSha", base.commitSha());
        m.put("baseTreeSha256", base.treeSha256()); m.put("targetReleaseNumber", target.releaseNumber());
        m.put("targetCommitSha", target.commitSha()); m.put("targetTreeSha256", target.treeSha256());
        m.put("payloadFiles", UpdateCodec.fileObjects(payload));
        return JsonWriter.write(m);
    }

    private static List<FileEntry> readPatch(Map<String, Object> m, InstalledVersion base, UpdateManifest target) throws IOException {
        UpdateCodec.keys(m, "schemaVersion", "algorithm", "algorithmVersion", "baseReleaseNumber", "baseCommitSha",
                "baseTreeSha256", "targetReleaseNumber", "targetCommitSha", "targetTreeSha256", "payloadFiles");
        UpdateCodec.algorithm(m);
        UpdateCodec.require(UpdateCodec.number(m, "schemaVersion") == 1, "PATCH_SCHEMA");
        UpdateCodec.require(UpdateCodec.number(m, "baseReleaseNumber") == base.releaseNumber()
                && UpdateCodec.string(m, "baseCommitSha").equals(base.commitSha())
                && UpdateCodec.string(m, "baseTreeSha256").equals(base.treeSha256()), "PATCH_BASE");
        UpdateCodec.require(UpdateCodec.number(m, "targetReleaseNumber") == target.releaseNumber()
                && UpdateCodec.string(m, "targetCommitSha").equals(target.commitSha())
                && UpdateCodec.string(m, "targetTreeSha256").equals(target.treeSha256()), "PATCH_TARGET");
        return UpdateCodec.readFiles(m.get("payloadFiles"));
    }

    private static OutputStream limited(OutputStream out) {
        return new FilterOutputStream(out) {
            private long count;
            /** Ограничивает размер контейнера при одиночной записи. */
            @Override public void write(int b) throws IOException {
                if (++count > UpdateValidation.MAX_FILE) throw new IOException("CONTAINER_SIZE");
                out.write(b);
            }
            /** Ограничивает размер контейнера до записи блока. */
            @Override public void write(byte[] b, int off, int len) throws IOException {
                count += len;
                if (count > UpdateValidation.MAX_FILE) throw new IOException("CONTAINER_SIZE");
                out.write(b, off, len);
            }
        };
    }

    private static IOException io(Exception e) {
        return e instanceof IOException failure ? failure : new IOException("INVALID_UPDATE_INPUT", e);
    }
}
