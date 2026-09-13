package ru.cashprediction.parity.launch;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ru.cashprediction.core.app.LaunchOptions;

/**
 * Запускает клиента в отдельной JVM с module path из jar реактора и списком аргументов архитектуры §6.3.
 *
 * <p>Имена аргументов приложения взяты из таблицы архитектуры §3.8 (класс ядра {@code LaunchOptions})
 * и не придумываются: тест {@code ClientLauncherCommandTest} разбирает собранную командную строку
 * настоящим {@code LaunchOptions.parse} и требует ноль предупреждений о неизвестных аргументах.</p>
 *
 * <p>Порядок командной строки:</p>
 * <pre>
 * java -Dglass.win.uiScale=1 -Dsun.java2d.uiScale=1 -Duser.language=ru -XX:-UsePerfData
 *      [опции клиента] [опции запроса] -Dcashprediction.home=H [-Dcashprediction.registry.node=N]
 *      --module-path a.jar;b.jar [--add-modules X] --module модуль/класс
 *      --home H --registry-node N --today 2026-09-13 --selftest S --selftest-out O [--ui M]
 *      [аргументы запроса] [аргументы клиента]
 * </pre>
 */
public final class ClientLauncher {

    /** Аргумент папки приложения (§3.8). */
    public static final String ARG_HOME = "--home";
    /** Аргумент префикса узла реестра (§3.8). */
    public static final String ARG_REGISTRY_NODE = "--registry-node";
    /** Аргумент хранилища реестра, значение {@code memory} (§3.8). */
    public static final String ARG_REGISTRY = "--registry";
    /** Аргумент даты «сегодня» (§3.8). */
    public static final String ARG_TODAY = "--today";
    /** Аргумент выбора интерфейса {@code core|legacy} (§3.8). */
    public static final String ARG_UI = "--ui";
    /** Аргумент сценария самотеста (§3.8). */
    public static final String ARG_SELFTEST = "--selftest";
    /** Аргумент папки результатов самотеста (§3.8). */
    public static final String ARG_SELFTEST_OUT = "--selftest-out";
    /** Аргумент автоответа на диалог восстановления (§3.8). */
    public static final String ARG_SELFTEST_RECOVERY = "--selftest-recovery";
    /** Web: включить тестовый API (§3.8). */
    public static final String ARG_TEST_API = "--test-api";
    /** Web: не открывать браузер (§3.8). */
    public static final String ARG_NO_BROWSER = "--no-browser";
    /** Web: не показывать окно сервера (§3.8). */
    public static final String ARG_NO_WINDOW = "--no-window";

    /**
     * Общие опции JVM стенда: масштаб 1 для JavaFX и Java2D (иначе размеры в дампах зависят от настроек экрана),
     * русская локаль (§6.3) и отказ от файлов hsperfdata во временной папке.
     */
    public static final List<String> STANDARD_JVM_OPTIONS = List.of(
            "-Dglass.win.uiScale=1", "-Dsun.java2d.uiScale=1", "-Duser.language=ru", "-XX:-UsePerfData");

    /** Системное свойство папки приложения, которое читают и прежние клиенты ({@code AppPaths}). */
    public static final String PROP_HOME = LaunchOptions.PROP_HOME;
    /** Системное свойство префикса узла реестра, которое читает и прежний {@code RegistrySessionStore.forClient(client)}. */
    public static final String PROP_REGISTRY_NODE = LaunchOptions.PROP_REGISTRY_NODE;

    /**
     * Клиенты, у которых до этапа S4 есть прежний интерфейс. Он не разбирает аргументы {@code --home} и
     * {@code --registry-node}, поэтому изоляцию ему дают только системные свойства, а {@code --registry memory} он не
     * понимает вовсе.
     */
    private static final Set<String> LEGACY_CAPABLE_CLIENTS = Set.of("fx", "swing", "web");

    /**
     * Переменные окружения, через которые JVM подмешивает опции к любой командной строке.
     * Их убирают, чтобы дочерняя JVM получила ровно ту команду, которую собрал стенд.
     */
    private static final List<String> JVM_OPTION_VARIABLES = List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    private ClientLauncher() {
    }

    /**
     * Собирает полную командную строку без запуска (для проверки и журнала).
     *
     * <p><b>Изоляция (решение L12).</b> Папка приложения и префикс узла реестра передаются дважды: аргументами
     * {@code --home}/{@code --registry-node} для интерфейса ядра и системными свойствами {@value #PROP_HOME} /
     * {@value #PROP_REGISTRY_NODE} для прежнего интерфейса, который аргументы не разбирает. Свойства стоят последними
     * опциями JVM, чтобы опции запроса не могли их перекрыть. Прежний интерфейс без тестового узла реестра писал бы в
     * настоящий общий узел, поэтому такой запуск клиентов fx, swing и web отклоняется, если не выбран
     * {@code --ui core}.</p>
     *
     * @param target  что запускать
     * @param request параметры запуска
     * @return команда, первый элемент — исполняемый файл java текущего JDK
     * @throws IllegalArgumentException если клиент с прежним интерфейсом запускается без тестового узла реестра
     */
    public static List<String> command(ClientTarget target, LaunchRequest request) {
        if (LEGACY_CAPABLE_CLIENTS.contains(target.client()) && request.registryNodePrefix() == null
                && !"core".equalsIgnoreCase(request.ui())) {
            throw new IllegalArgumentException("Client " + target.client() + " may start its legacy UI, which ignores"
                    + " --registry memory and would write the real session node; pass a selftest registry node prefix"
                    + " or --ui core");
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(STANDARD_JVM_OPTIONS);
        command.addAll(target.jvmOptions());
        command.addAll(request.jvmOptions());
        command.addAll(isolationJvmOptions(request));
        command.add("--module-path");
        command.add(String.join(File.pathSeparator, target.modulePath().stream().map(Path::toString).toList()));
        if (!target.addModules().isEmpty()) {
            command.add("--add-modules");
            command.add(String.join(",", target.addModules()));
        }
        command.add("--module");
        command.add(target.mainModule() + "/" + target.mainClass());
        command.addAll(applicationArguments(target, request));
        return List.copyOf(command);
    }

    /**
     * Системные свойства изоляции для прежнего интерфейса: папка приложения всегда, префикс узла реестра — если задан.
     *
     * @param request параметры запуска
     * @return опции {@code -D…}
     */
    public static List<String> isolationJvmOptions(LaunchRequest request) {
        List<String> options = new ArrayList<>();
        options.add("-D" + PROP_HOME + "=" + request.home());
        if (request.registryNodePrefix() != null) {
            options.add("-D" + PROP_REGISTRY_NODE + "=" + request.registryNodePrefix());
        }
        return List.copyOf(options);
    }

    /**
     * Аргументы приложения из запроса и цели (всё, что идёт после {@code --module модуль/класс}).
     *
     * @param target  что запускать
     * @param request параметры запуска
     * @return аргументы в порядке §6.3
     */
    public static List<String> applicationArguments(ClientTarget target, LaunchRequest request) {
        List<String> args = new ArrayList<>();
        args.add(ARG_HOME);
        args.add(request.home().toString());
        if (request.registryNodePrefix() != null) {
            args.add(ARG_REGISTRY_NODE);
            args.add(request.registryNodePrefix());
        }
        if (request.today() != null) {
            args.add(ARG_TODAY);
            args.add(DateTimeFormatter.ISO_LOCAL_DATE.format(request.today()));
        }
        if (request.selftest() != null) {
            args.add(ARG_SELFTEST);
            args.add(request.selftest());
        }
        if (request.selftestOut() != null) {
            args.add(ARG_SELFTEST_OUT);
            args.add(request.selftestOut().toString());
        }
        if (request.ui() != null) {
            args.add(ARG_UI);
            args.add(request.ui().toLowerCase(Locale.ROOT));
        }
        args.addAll(request.extraArguments());
        args.addAll(target.arguments());
        return List.copyOf(args);
    }

    /**
     * Запускает клиента: создаёт домашнюю папку, проверяет module path, пишет команду и потоки вывода
     * в журналы рядом с домашней папкой.
     *
     * <p>Рабочая папка процесса — его домашняя папка: иначе процесс держал бы открытой папку модуля,
     * а относительные пути приложения попали бы в чужое место.</p>
     *
     * @param target  что запускать
     * @param request параметры запуска
     * @return запущенный клиент; закрывать через try-with-resources (завершает всё дерево процессов)
     * @throws IllegalStateException если элемента module path нет (модули не собраны)
     * @throws UncheckedIOException  если не удалось создать папки или запустить процесс
     */
    public static LaunchedClient launch(ClientTarget target, LaunchRequest request) {
        for (Path entry : target.modulePath()) {
            if (!Files.exists(entry)) {
                throw new IllegalStateException("Module path entry " + entry + " does not exist; build the reactor first"
                        + " (mvn -B -Pui-tests -pl ui-parity -am verify)");
            }
        }
        List<String> command = command(target, request);
        Path logs = request.logDirectory();
        String base = request.home().getFileName().toString();
        Path stdout = logs.resolve(base + ".stdout.log");
        Path stderr = logs.resolve(base + ".stderr.log");
        try {
            Files.createDirectories(request.home());
            Files.createDirectories(logs);
            Files.write(logs.resolve(base + ".command.txt"), command);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(request.home().toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            Map<String, String> environment = builder.environment();
            JVM_OPTION_VARIABLES.forEach(environment::remove);
            Process process = builder.start();
            return new LaunchedClient(target, request, command, process, stdout, stderr);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot launch " + target.client() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Исполняемый файл java того JDK, в котором идут тесты (тот же JDK 25, что собирал модули).
     *
     * @return путь к {@code java.exe} или {@code java}
     */
    public static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }
}
