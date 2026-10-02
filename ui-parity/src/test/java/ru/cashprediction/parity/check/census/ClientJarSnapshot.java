package ru.cashprediction.parity.check.census;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.jar.JarFile;
import ru.cashprediction.parity.launch.ClientTarget;

/** Отделяет jar запускаемой JVM от общих target, чтобы Windows не блокировала сборку ядра и клиентов. */
public final class ClientJarSnapshot {
    private ClientJarSnapshot() { }

    /** Копирует каждый разрешённый элемент module path в уникальный снимок и сохраняет остальные параметры запуска. */
    public static ClientTarget copy(ClientTarget source, Path output) throws IOException {
        Files.createDirectories(output);
        Path snapshot = Files.createTempDirectory(output, "client-jars-").toRealPath();
        var paths = new ArrayList<Path>();
        int index = 0;
        for (Path entry : source.modulePath()) {
            Path original = entry.toRealPath();
            if (!Files.isRegularFile(original) || !original.getFileName().toString().endsWith(".jar"))
                throw new IOException("Client snapshot requires a jar: " + original);
            var before = Files.readAttributes(original, BasicFileAttributes.class);
            // Отдельная подпапка сохраняет имя автоматического модуля и не допускает коллизии имён файлов.
            Path folder = Files.createDirectory(snapshot.resolve(Integer.toString(index++)));
            Path copied = Files.copy(original, folder.resolve(original.getFileName()));
            var after = Files.readAttributes(original, BasicFileAttributes.class);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
                throw new IOException("Client jar changed while copying: " + original);
            // Проверка читает только снимок; дескриптор закрывается до запуска JVM.
            try (JarFile jar = new JarFile(copied.toFile())) { jar.size(); }
            paths.add(copied);
        }
        return new ClientTarget(source.client(), paths, source.mainModule(), source.mainClass(),
                source.addModules(), source.jvmOptions(), source.arguments());
    }
}
