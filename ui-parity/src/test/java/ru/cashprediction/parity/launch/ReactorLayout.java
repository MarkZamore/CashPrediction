package ru.cashprediction.parity.launch;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Раскладка реактора Maven, из которой {@link ClientLauncher} собирает module path клиентов.
 *
 * <p>Значения передаёт surefire через системные свойства (см. {@code ui-parity/pom.xml}); при запуске теста
 * из IDE без них корень реактора, папка сборки и локальный репозиторий выводятся из рабочей папки модуля
 * и {@code user.home}, а версии обязательны только когда нужен jar модуля или JavaFX.</p>
 *
 * @param root            корень реактора (папка с родительским pom.xml)
 * @param version         версия проекта, например {@code 1.0.0}, или {@code null}, если неизвестна
 * @param mavenRepository локальный репозиторий Maven (там лежат jar OpenJFX)
 * @param javafxVersion   версия OpenJFX или {@code null}, если неизвестна
 * @param buildDirectory  папка сборки модуля ui-parity ({@code ui-parity/target})
 */
public record ReactorLayout(Path root, String version, Path mavenRepository, String javafxVersion, Path buildDirectory) {

    /** Свойство корня реактора. */
    public static final String PROP_ROOT = "parity.reactor.root";
    /** Свойство версии проекта. */
    public static final String PROP_VERSION = "parity.project.version";
    /** Свойство версии OpenJFX. */
    public static final String PROP_JAVAFX_VERSION = "parity.javafx.version";
    /** Свойство локального репозитория Maven. */
    public static final String PROP_MAVEN_REPOSITORY = "parity.maven.repository";
    /** Свойство папки сборки модуля ui-parity. */
    public static final String PROP_BUILD_DIRECTORY = "parity.build.directory";

    /** Модули OpenJFX, нужные клиенту JavaFX (controls тянет graphics и base). */
    private static final List<String> JAVAFX_MODULES = List.of("javafx-base", "javafx-graphics", "javafx-controls");

    /** Приводит пути к абсолютным, чтобы командная строка дочерней JVM не зависела от её рабочей папки. */
    public ReactorLayout {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        mavenRepository = Objects.requireNonNull(mavenRepository, "mavenRepository").toAbsolutePath().normalize();
        buildDirectory = Objects.requireNonNull(buildDirectory, "buildDirectory").toAbsolutePath().normalize();
    }

    /**
     * Читает раскладку из системных свойств surefire с запасными значениями для запуска из IDE.
     *
     * @return раскладка реактора
     */
    public static ReactorLayout fromSystemProperties() {
        // Surefire запускает тесты в папке модуля ui-parity: её родитель и есть корень реактора.
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path root = pathProperty(PROP_ROOT, moduleDir.getParent() != null ? moduleDir.getParent() : moduleDir);
        Path repository = pathProperty(PROP_MAVEN_REPOSITORY,
                Path.of(System.getProperty("user.home"), ".m2", "repository"));
        Path build = pathProperty(PROP_BUILD_DIRECTORY, moduleDir.resolve("target"));
        return new ReactorLayout(root, blankToNull(System.getProperty(PROP_VERSION)), repository,
                blankToNull(System.getProperty(PROP_JAVAFX_VERSION)), build);
    }

    /**
     * Jar модуля реактора после фазы {@code package}.
     *
     * @param module имя папки модуля, например {@code ui-swing}
     * @return {@code <root>/<module>/target/cashprediction-<module>-<version>.jar}
     * @throws IllegalStateException если версия проекта неизвестна
     */
    public Path moduleJar(String module) {
        String v = require(version, PROP_VERSION);
        return root.resolve(module).resolve("target").resolve("cashprediction-" + module + "-" + v + ".jar");
    }

    /**
     * Платформенные jar OpenJFX для Windows из локального репозитория Maven.
     *
     * <p>Берутся только jar с классификатором {@code win}: jar без классификатора пустые
     * и на module path дали бы второй модуль с тем же пакетом.</p>
     *
     * @return jar javafx-base, javafx-graphics и javafx-controls
     * @throws IllegalStateException если версия OpenJFX неизвестна
     */
    public List<Path> javafxJars() {
        String v = require(javafxVersion, PROP_JAVAFX_VERSION);
        return JAVAFX_MODULES.stream()
                .map(name -> mavenRepository.resolve("org").resolve("openjfx").resolve(name).resolve(v)
                        .resolve(name + "-" + v + "-win.jar"))
                .toList();
    }

    /**
     * Корень рабочих папок стенда: {@code ui-parity/target/parity} (архитектура §6.3).
     *
     * @return папка для домашних папок клиентов, журналов, снимков экрана и профилей браузера
     */
    public Path parityRoot() {
        return buildDirectory.resolve("parity");
    }

    private static Path pathProperty(String name, Path fallback) {
        String value = blankToNull(System.getProperty(name));
        return value == null ? fallback : Path.of(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String require(String value, String property) {
        if (value == null) {
            throw new IllegalStateException("System property " + property
                    + " is not set; run the tests through Maven (mvn -Pui-tests -pl ui-parity -am verify)");
        }
        return value;
    }
}
