package ru.cashprediction.parity.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.parity.launch.ClientTarget;

/** Отдельные копии модулей рабочего дерева: запущенная JVM не блокирует повторную сборку JAR в Windows. */
final class ModuleSnapshot {
    private ModuleSnapshot() { }

    /** Сохраняет эталоны одной матрицы и отклоняет изменение исходного дерева во время копирования. */
    static Path captureTree(Path source, Path destination) throws IOException {
        source = source.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        if (destination.startsWith(source)) throw new IOException("Snapshot destination is inside source: " + destination);
        if (!Files.isDirectory(source)) throw new IOException("Snapshot tree is missing: " + source);
        String before = fingerprint(source);
        copy(source, destination);
        if (!before.equals(fingerprint(source)) || !before.equals(fingerprint(destination)))
            throw new IOException("Snapshot tree changed while copying: " + source);
        return destination;
    }

    /** Копирует изменяемые модули проекта, сохраняя имена JAR и неизменяемые зависимости Maven. */
    static ClientTarget capture(ClientTarget target, Path destination, Path workspace) throws IOException {
        Path base = workspace.toAbsolutePath().normalize();
        var paths = new ArrayList<Path>();
        for (int i = 0; i < target.modulePath().size(); i++) {
            Path source = target.modulePath().get(i).toAbsolutePath().normalize();
            if (!source.startsWith(base)) { paths.add(source); continue; }
            Path copy = destination.resolve(Integer.toString(i)).resolve(source.getFileName());
            copy(source, copy); paths.add(copy);
        }
        return new ClientTarget(target.client(), paths, target.mainModule(), target.mainClass(),
                target.addModules(), target.jvmOptions(), target.arguments());
    }

    /** Замораживает весь набор клиентов до первого сценария; один общий модуль получает одну копию. */
    static Map<String, ClientTarget> captureAll(Map<String, ClientTarget> targets, Path destination, Path workspace) throws IOException {
        Path base = workspace.toAbsolutePath().normalize();
        Map<Path, Path> copies = new LinkedHashMap<>();
        Map<Path, String> before = new LinkedHashMap<>();
        for (ClientTarget target : targets.values()) for (Path path : target.modulePath()) {
            Path source = path.toAbsolutePath().normalize();
            if (source.startsWith(base) && !before.containsKey(source)) before.put(source, fingerprint(source));
        }
        Map<String, ClientTarget> result = new LinkedHashMap<>();
        for (var entry : targets.entrySet()) {
            ClientTarget target = entry.getValue();
            List<Path> paths = new ArrayList<>();
            for (Path path : target.modulePath()) {
                Path source = path.toAbsolutePath().normalize();
                if (!source.startsWith(base)) { paths.add(source); continue; }
                Path frozen = copies.get(source);
                if (frozen == null) {
                    frozen = destination.resolve(Integer.toString(copies.size())).resolve(source.getFileName());
                    copy(source, frozen); copies.put(source, frozen);
                }
                paths.add(frozen);
            }
            result.put(entry.getKey(), new ClientTarget(target.client(), paths, target.mainModule(), target.mainClass(),
                    target.addModules(), target.jvmOptions(), target.arguments()));
        }
        for (var entry : before.entrySet()) {
            if (!entry.getValue().equals(fingerprint(entry.getKey())) || !entry.getValue().equals(fingerprint(copies.get(entry.getKey()))))
                throw new IOException("Matrix module changed while snapshotting: " + entry.getKey());
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /** Хеширует байты и имена файлов, обнаруживая замену даже при сохранённых размере и времени. */
    private static String fingerprint(Path source) throws IOException {
        if (Files.isSymbolicLink(source)) throw new IOException("Linked module source: " + source);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            if (Files.isDirectory(source)) {
                try (var entries = Files.walk(source)) {
                    for (Path path : entries.sorted().toList()) {
                        if (Files.isSymbolicLink(path)) throw new IOException("Linked module source: " + path);
                        digest.update(source.relativize(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        if (Files.isRegularFile(path)) hashFile(path, digest);
                    }
                }
            } else hashFile(source, digest);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    /** Читает модуль потоком, не удерживая большие JAR целиком в памяти. */
    private static void hashFile(Path file, java.security.MessageDigest digest) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
    }

    /** Копирует файл или каталог классов без перехода по ссылкам и без удаления исходных файлов. */
    private static void copy(Path source, Path destination) throws IOException {
        if (Files.isSymbolicLink(source)) throw new IOException("Linked module source: " + source);
        if (Files.isDirectory(source)) {
            Files.createDirectories(destination);
            try (var children = Files.list(source)) {
                for (Path child : children.toList()) copy(child, destination.resolve(child.getFileName()));
            }
        } else {
            var before = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class);
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination);
            var after = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                throw new IOException("Module changed while snapshotting: " + source);
        }
    }
}
