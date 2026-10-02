package ru.cashprediction.parity.check.census;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.parity.launch.ClientTarget;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет независимость module path снимка без запуска JVM и без чтения общих target. */
class ClientJarSnapshotTest {
    @TempDir Path directory;

    /** Сохраняет порядок jar, одинаковые имена, параметры запуска и неизменяемый исходный объект. */
    @Test void snapshotsEveryJarAndPreservesLaunchConfiguration() throws Exception {
        Path a = jar("a/same.jar", "first");
        Path b = jar("b/same.jar", "second");
        var target = new ClientTarget("fx", List.of(a, b), "main.module", "Main",
                List.of("extra.module"), List.of("-Dexample=true"), List.of("--example"));
        var first = ClientJarSnapshot.copy(target, directory.resolve("snapshots"));
        var second = ClientJarSnapshot.copy(target, directory.resolve("snapshots"));
        assertEquals(target.client(), first.client()); assertEquals(target.mainModule(), first.mainModule());
        assertEquals(target.mainClass(), first.mainClass()); assertEquals(target.addModules(), first.addModules());
        assertEquals(target.jvmOptions(), first.jvmOptions()); assertEquals(target.arguments(), first.arguments());
        assertEquals(List.of(a, b), target.modulePath());
        for (int i = 0; i < target.modulePath().size(); i++) {
            Path copied = first.modulePath().get(i);
            assertNotEquals(target.modulePath().get(i), copied);
            assertNotEquals(second.modulePath().get(i), copied);
            assertTrue(copied.startsWith(directory.resolve("snapshots")));
            assertEquals("same.jar", copied.getFileName().toString());
            assertArrayEquals(Files.readAllBytes(target.modulePath().get(i)), Files.readAllBytes(copied));
        }
        byte[] retained = Files.readAllBytes(first.modulePath().getFirst());
        jar("a/same.jar", "repackaged");
        assertArrayEquals(retained, Files.readAllBytes(first.modulePath().getFirst()));
        // Успешная перезапись источника доказывает, что помощник не оставил открытый дескриптор jar.
        assertFalse(java.util.Arrays.equals(Files.readAllBytes(a), retained));
    }

    /** Не возвращает цель запуска при отсутствующем, повреждённом jar или каталоге вместо jar. */
    @Test void unusableSourcesFailBeforeAnyLaunch() throws Exception {
        Path broken = directory.resolve("broken.jar"); Files.writeString(broken, "not a jar");
        Path folder = Files.createDirectory(directory.resolve("folder.jar"));
        for (Path source : List.of(broken, folder, directory.resolve("missing.jar"))) {
            var target = new ClientTarget("fx", List.of(source), "main.module", "Main", List.of(), List.of(), List.of());
            assertThrows(java.io.IOException.class, () -> ClientJarSnapshot.copy(target, directory.resolve("snapshots")));
        }
    }

    /** Создаёт небольшой действительный jar только для проверки копирования файлов. */
    private Path jar(String name, String content) throws Exception {
        Path path = directory.resolve(name); Files.createDirectories(path.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry("content.txt")); out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }
}
