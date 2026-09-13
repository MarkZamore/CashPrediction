package ru.cashprediction.parity.dummy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import ru.cashprediction.core.app.LaunchOptions;

/**
 * Тестовая заглушка клиента для {@code LauncherSmokeIT}: ведёт себя как exe jpackage, не завися от клиентов.
 *
 * <p>Родительская роль (по умолчанию):</p>
 * <ol>
 *   <li>разбирает аргументы настоящим {@link LaunchOptions#parse} — неизвестное имя аргумента попадёт
 *       в предупреждения, некорректное значение завершит процесс с кодом 2;</li>
 *   <li>запускает дочернюю JVM с той же командой (как лаунчер jpackage перезапускает себя потомком),
 *       чтобы тест проверил завершение всего дерева процессов;</li>
 *   <li>пишет отметку в тестовый узел реестра {@code <префикс>/dummy}, только если префикс лежит
 *       под {@code ru/cashprediction/selftest/};</li>
 *   <li>пишет {@value #READY_FILE} в папку {@code --selftest-out} и спит, пока его не завершат.</li>
 * </ol>
 * <p>Дочерняя роль ({@code -Dparity.dummy.role=child}) пишет {@value #CHILD_FILE} и спит.
 * Обе роли сами завершаются через {@value #MAX_LIFE_PROPERTY} секунд (по умолчанию 600),
 * чтобы упавший тест не оставил процессы навсегда. Смерть родителя дочерний процесс не отслеживает
 * намеренно: иначе тест прошёл бы, даже если бы стенд завершал только корень дерева.</p>
 */
public final class DummyClientMain {

    /** Системное свойство роли процесса. */
    public static final String ROLE_PROPERTY = "parity.dummy.role";
    /** Значение роли дочернего процесса. */
    public static final String ROLE_CHILD = "child";
    /** Системное свойство предельного времени жизни в секундах. */
    public static final String MAX_LIFE_PROPERTY = "parity.dummy.maxLifeSeconds";
    /** Отметка готовности родителя в папке результатов самотеста. */
    public static final String READY_FILE = "dummy-ready.properties";
    /** Отметка дочернего процесса в папке результатов самотеста. */
    public static final String CHILD_FILE = "dummy-child.properties";
    /** Подузел, куда родитель пишет отметку в тестовом реестре. */
    public static final String REGISTRY_CHILD_NODE = "dummy";
    /** Разрешённый корень узлов, в которые заглушке можно писать. */
    public static final String SELFTEST_ROOT = "ru/cashprediction/selftest/";

    private DummyClientMain() {
    }

    /**
     * Точка входа заглушки.
     *
     * @param args аргументы в формате {@code LaunchOptions}
     * @throws Exception любая ошибка завершает процесс с трассой в stderr (её читает тест)
     */
    public static void main(String[] args) throws Exception {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("dummy: bad arguments: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (options.selftestOut() == null) {
            System.err.println("dummy: --selftest-out is required");
            System.exit(2);
            return;
        }
        Files.createDirectories(options.selftestOut());
        long maxLife = Long.getLong(MAX_LIFE_PROPERTY, 600L);
        if (ROLE_CHILD.equals(System.getProperty(ROLE_PROPERTY))) {
            Properties child = new Properties();
            child.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
            writeAtomically(options.selftestOut().resolve(CHILD_FILE), child);
        } else {
            runParent(args, options);
        }
        sleepUpTo(Duration.ofSeconds(maxLife));
        System.exit(0);
    }

    private static void runParent(String[] args, LaunchOptions options) throws IOException, InterruptedException {
        Path childFile = options.selftestOut().resolve(CHILD_FILE);
        Files.deleteIfExists(childFile);
        Process child = startChild(args);

        if (options.registryNode() != null && (options.registryNode() + "/").startsWith(SELFTEST_ROOT)) {
            Preferences node = Preferences.userRoot().node(options.registryNode() + "/" + REGISTRY_CHILD_NODE);
            node.put("pid", Long.toString(ProcessHandle.current().pid()));
            try {
                node.flush();
            } catch (BackingStoreException e) {
                System.err.println("dummy: registry flush failed: " + e.getMessage());
            }
        }

        // Отметку готовности пишем только после появления дочерней: тест сразу видит оба pid.
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!Files.isRegularFile(childFile) && child.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        Properties childProps = new Properties();
        if (Files.isRegularFile(childFile)) {
            try (var in = Files.newInputStream(childFile)) {
                childProps.load(in);
            }
        }

        Properties ready = new Properties();
        ready.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        ready.setProperty("childPid", Long.toString(child.pid()));
        ready.setProperty("childReportedPid", childProps.getProperty("pid", ""));
        ready.setProperty("home", String.valueOf(options.home()));
        ready.setProperty("cwd", Path.of("").toAbsolutePath().toString());
        ready.setProperty("registryNode", String.valueOf(options.registryNode()));
        ready.setProperty("today", String.valueOf(options.today()));
        ready.setProperty("selftest", String.valueOf(options.selftest()));
        ready.setProperty("selftestOut", String.valueOf(options.selftestOut()));
        ready.setProperty("warnings", Integer.toString(options.warnings().size()));
        ready.setProperty("warningText", String.join(" | ", options.warnings()));
        ready.setProperty("user.language", System.getProperty("user.language", ""));
        ready.setProperty("sun.java2d.uiScale", System.getProperty("sun.java2d.uiScale", ""));
        ready.setProperty("glass.win.uiScale", System.getProperty("glass.win.uiScale", ""));
        ready.setProperty("modulePath", System.getProperty("jdk.module.path", ""));
        writeAtomically(options.selftestOut().resolve(READY_FILE), ready);
    }

    private static Process startChild(String[] args) throws IOException {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-XX:-UsePerfData");
        command.add("-D" + ROLE_PROPERTY + "=" + ROLE_CHILD);
        command.add("-D" + MAX_LIFE_PROPERTY + "=" + Long.getLong(MAX_LIFE_PROPERTY, 600L));
        command.add("--module-path");
        command.add(System.getProperty("jdk.module.path"));
        command.add("--add-modules");
        command.add("ALL-MODULE-PATH");
        command.add("--module");
        command.add(DummyClientMain.class.getModule().getName() + "/" + DummyClientMain.class.getName());
        command.addAll(List.of(args));
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void writeAtomically(Path file, Properties properties) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            properties.store(out, null);
        }
        // Атомарная замена: тест не прочитает наполовину записанный файл.
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void sleepUpTo(Duration life) {
        long deadline = System.nanoTime() + life.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
