package ru.cashprediction.parity.launch;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import ru.cashprediction.parity.dummy.DummyClientMain;

/**
 * Упаковывает тестовую заглушку {@link DummyClientMain} в jar автоматического модуля.
 *
 * <p>Зачем jar: клиенты запускаются с module path, а папка тестовых классов без {@code module-info.class}
 * на module path не допускается. Jar с {@code Automatic-Module-Name} проверяет тот же путь запуска,
 * что и у настоящих клиентов, и не требует компиляции {@code module-info} во время теста.</p>
 */
public final class DummyClientJar {

    /** Имя автоматического модуля заглушки. */
    public static final String MODULE_NAME = "ru.cashprediction.parity.dummy";

    /** Главный класс заглушки. */
    public static final String MAIN_CLASS = DummyClientMain.class.getName();

    /** Имя jar-файла. */
    public static final String JAR_NAME = "cashprediction-parity-dummy.jar";

    private DummyClientJar() {
    }

    /**
     * Собирает jar из всех классов пакета заглушки (включая вложенные классы).
     *
     * @param directory папка, куда положить jar (создаётся при необходимости)
     * @return путь к jar
     * @throws UncheckedIOException если классы не найдены или jar не записан
     */
    public static Path build(Path directory) {
        Path classesRoot = codeSource(DummyClientMain.class);
        String packagePath = DummyClientMain.class.getPackageName().replace('.', '/');
        Path packageDir = classesRoot.resolve(packagePath);
        try {
            List<Path> classes;
            try (Stream<Path> files = Files.list(packageDir)) {
                classes = files.filter(f -> f.getFileName().toString().endsWith(".class")).sorted().toList();
            }
            if (classes.isEmpty()) {
                throw new IOException("no classes in " + packageDir);
            }
            Files.createDirectories(directory);
            Path jar = directory.resolve(JAR_NAME);
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(new Attributes.Name("Automatic-Module-Name"), MODULE_NAME);
            try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
                // Каталоги пакета пишутся явными записями: так jar выглядит как собранный jar-плагином.
                StringBuilder dir = new StringBuilder();
                for (String part : packagePath.split("/")) {
                    dir.append(part).append('/');
                    jarOut.putNextEntry(new JarEntry(dir.toString()));
                    jarOut.closeEntry();
                }
                for (Path cls : classes) {
                    jarOut.putNextEntry(new JarEntry(packagePath + "/" + cls.getFileName()));
                    Files.copy(cls, jarOut);
                    jarOut.closeEntry();
                }
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build dummy client jar: " + e.getMessage(), e);
        }
    }

    /**
     * Папка классов или jar, из которого загружен класс.
     *
     * @param type класс
     * @return путь источника кода
     */
    public static Path codeSource(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Bad code source of " + type.getName(), e);
        }
    }
}
