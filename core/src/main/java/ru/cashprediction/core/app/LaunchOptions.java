package ru.cashprediction.core.app;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.util.DateFormats;

/**
 * Параметры запуска из командной строки и системных свойств (архитектура §3.8), одинаковые для трёх exe.
 *
 * <p>Аргументы командной строки сильнее системных свойств. Прежние имена свойств сохранены:</p>
 * <table>
 *   <caption>Аргументы и свойства</caption>
 *   <tr><th>Аргумент</th><th>Свойство</th><th>Смысл</th></tr>
 *   <tr><td>{@code --home <папка>}</td><td>{@code cashprediction.home}</td><td>папка, рядом с которой CashMemory</td></tr>
 *   <tr><td>{@code --registry-node <префикс>}</td><td>{@code cashprediction.registry.node}</td>
 *       <td>префикс узла реестра, начинается с {@code ru/cashprediction/}; узел = префикс/клиент</td></tr>
 *   <tr><td>{@code --registry memory}</td><td>—</td><td>реестр в памяти процесса, настоящий не трогается</td></tr>
 *   <tr><td>{@code --today <дата>}</td><td>{@code cashprediction.today}</td><td>зафиксировать «сегодня»</td></tr>
 *   <tr><td>{@code --ui core|legacy}</td><td>{@code cashprediction.ui}</td><td>новый интерфейс ядра или прежний</td></tr>
 *   <tr><td>{@code --selftest <сценарий>}</td><td>{@code cashprediction.selftest}</td><td>запустить сценарий самотеста</td></tr>
 *   <tr><td>{@code --selftest-out <папка>}</td><td>{@code cashprediction.selftest.log}</td>
 *       <td>куда писать результаты; свойство по-прежнему может указывать файл журнала {@code *.log}</td></tr>
 *   <tr><td>{@code --selftest-recovery registry|xml|none|already-ok}</td><td>{@code cashprediction.selftest.recovery}</td>
 *       <td>автоответ на диалог восстановления</td></tr>
 *   <tr><td>{@code --test-api}</td><td>—</td><td>web: включить тестовый API</td></tr>
 *   <tr><td>{@code --no-browser}, {@code --no-window}</td><td>—</td><td>web: не открывать браузер / окно сервера</td></tr>
 * </table>
 *
 * <p>Значение можно передать отдельным словом ({@code --home D:\x}) или через знак равенства
 * ({@code --home=D:\x}). <b>Неизвестные аргументы не ошибка:</b> они пропускаются и попадают в {@link #warnings()}
 * (лаунчер или ОС могут добавить свои). <b>Некорректное значение известного аргумента — ошибка</b>
 * ({@link IllegalArgumentException} с текстом из каталога {@code launch.error.*}): молча проигнорированный
 * {@code --registry-node} отправил бы тестовый запуск в настоящий узел реестра.</p>
 *
 * @param home             папка приложения (рядом с ней CashMemory) или {@code null} — определить автоматически
 * @param registryNode     явный префикс узла реестра или {@code null} — узел установки
 * @param registryMemory   {@code true} — хранилище «реестр» в памяти процесса
 * @param today            зафиксированная дата «сегодня» или {@code null}
 * @param ui               какой интерфейс запускать
 * @param selftest         имя или путь сценария самотеста или {@code null}
 * @param selftestOut      папка результатов самотеста (или файл журнала из прежнего свойства) или {@code null}
 * @param selftestRecovery автоответ на диалог восстановления или {@code null}
 * @param testApi          web: тестовый API включён
 * @param noBrowser        web: не открывать браузер
 * @param noWindow         web: не показывать окно сервера
 * @param warnings         предупреждения разбора (неизвестные аргументы), готовые тексты из каталога
 */
public record LaunchOptions(Path home, String registryNode, boolean registryMemory, LocalDate today, UiMode ui,
                            String selftest, Path selftestOut, RecoveryAnswer selftestRecovery, boolean testApi,
                            boolean noBrowser, boolean noWindow, List<String> warnings) {

    /** Свойство папки приложения. */
    public static final String PROP_HOME = "cashprediction.home";
    /** Свойство префикса узла реестра. */
    public static final String PROP_REGISTRY_NODE = RegistrySessionStore.PROPERTY_NODE;
    /** Свойство даты «сегодня». */
    public static final String PROP_TODAY = "cashprediction.today";
    /** Свойство выбора интерфейса. */
    public static final String PROP_UI = "cashprediction.ui";
    /** Свойство сценария самотеста. */
    public static final String PROP_SELFTEST = "cashprediction.selftest";
    /** Свойство журнала (папки результатов) самотеста. */
    public static final String PROP_SELFTEST_LOG = "cashprediction.selftest.log";
    /** Свойство автоответа на диалог восстановления. */
    public static final String PROP_SELFTEST_RECOVERY = "cashprediction.selftest.recovery";

    /** Интерфейс, который запускает exe. */
    public enum UiMode {
        /** Интерфейс, построенный из моделей ядра ({@code AppController}). */
        CORE,
        /** Прежний интерфейс клиента; остаётся по умолчанию до прохождения паритета (правило R4), удаляется в S4. */
        LEGACY
    }

    /** Автоответ самотеста на диалог восстановления или на вопрос о втором экземпляре. */
    public enum RecoveryAnswer {
        /** Восстановить из реестра. */
        REGISTRY("registry"),
        /** Восстановить из XML-файла. */
        XML("xml"),
        /** Не восстанавливать. */
        NONE("none"),
        /** Второй экземпляр: открыть без восстановления. */
        ALREADY_OK("already-ok");

        private final String argument;

        RecoveryAnswer(String argument) {
            this.argument = argument;
        }

        /** @return значение аргумента, например {@code already-ok} */
        public String argument() {
            return argument;
        }

        /**
         * Разбирает значение аргумента.
         *
         * @param text значение без учёта регистра
         * @return ответ
         * @throws IllegalArgumentException если значение неизвестно (текст {@code launch.error.recovery})
         */
        public static RecoveryAnswer parse(String text) {
            for (RecoveryAnswer answer : values()) {
                if (answer.argument.equalsIgnoreCase(text.strip())) {
                    return answer;
                }
            }
            throw new IllegalArgumentException(UiText.get("launch.error.recovery", "--selftest-recovery", text));
        }
    }

    /** Копирует список предупреждений и подставляет интерфейс по умолчанию. */
    public LaunchOptions {
        ui = ui == null ? UiMode.LEGACY : ui;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * Параметры по умолчанию: всё определяется автоматически, прежний интерфейс.
     *
     * @return параметры без аргументов и свойств
     */
    public static LaunchOptions defaults() {
        return new LaunchOptions(null, null, false, null, UiMode.LEGACY, null, null, null, false, false, false, List.of());
    }

    /**
     * Разбирает аргументы {@code main} вместе с системными свойствами JVM.
     *
     * @param args аргументы командной строки
     * @return параметры
     * @throws IllegalArgumentException если у известного аргумента нет значения или оно некорректно
     */
    public static LaunchOptions parse(String... args) {
        return parse(args == null ? List.of() : Arrays.asList(args), System.getProperties());
    }

    /**
     * Разбирает аргументы и заданный набор свойств (для тестов без изменения свойств JVM).
     *
     * @param args       аргументы командной строки
     * @param properties системные свойства
     * @return параметры
     * @throws IllegalArgumentException если у известного аргумента нет значения или оно некорректно
     */
    public static LaunchOptions parse(List<String> args, Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Builder b = new Builder();
        // Сначала свойства, затем аргументы: так аргумент командной строки перезаписывает свойство.
        property(properties, PROP_HOME).ifPresent(v -> b.home = path(v, PROP_HOME));
        property(properties, PROP_REGISTRY_NODE).ifPresent(v -> b.registryNode = node(v, PROP_REGISTRY_NODE));
        property(properties, PROP_TODAY).ifPresent(v -> b.today = date(v, PROP_TODAY));
        property(properties, PROP_UI).ifPresent(v -> b.ui = ui(v, PROP_UI));
        property(properties, PROP_SELFTEST).ifPresent(v -> b.selftest = v);
        property(properties, PROP_SELFTEST_LOG).ifPresent(v -> b.selftestOut = path(v, PROP_SELFTEST_LOG));
        property(properties, PROP_SELFTEST_RECOVERY).ifPresent(v -> b.selftestRecovery = recovery(v, PROP_SELFTEST_RECOVERY));

        List<String> list = args == null ? List.of() : args;
        for (int i = 0; i < list.size(); i++) {
            String raw = Objects.requireNonNullElse(list.get(i), "");
            String name = raw;
            String inline = null;
            int eq = raw.indexOf('=');
            if (raw.startsWith("--") && eq > 2) {
                name = raw.substring(0, eq);
                inline = raw.substring(eq + 1);
            }
            switch (name) {
                case "--home" -> b.home = path(inline != null ? inline : value(list, ++i, name), name);
                case "--registry-node" -> b.registryNode = node(inline != null ? inline : value(list, ++i, name), name);
                case "--registry" -> {
                    String mode = inline != null ? inline : value(list, ++i, name);
                    if (!mode.strip().equalsIgnoreCase("memory")) {
                        throw new IllegalArgumentException(UiText.get("launch.error.registryMode", mode));
                    }
                    b.registryMemory = true;
                }
                case "--today" -> b.today = date(inline != null ? inline : value(list, ++i, name), name);
                case "--ui" -> b.ui = ui(inline != null ? inline : value(list, ++i, name), name);
                case "--selftest" -> b.selftest = inline != null ? inline : value(list, ++i, name);
                case "--selftest-out" -> b.selftestOut = path(inline != null ? inline : value(list, ++i, name), name);
                case "--selftest-recovery" ->
                        b.selftestRecovery = recovery(inline != null ? inline : value(list, ++i, name), name);
                case "--test-api" -> b.testApi = flag(inline, name, b);
                case "--no-browser" -> b.noBrowser = flag(inline, name, b);
                case "--no-window" -> b.noWindow = flag(inline, name, b);
                default -> b.warnings.add(UiText.get("launch.warn.unknownArgument", raw));
            }
        }
        return new LaunchOptions(b.home, b.registryNode, b.registryMemory, b.today, b.ui, b.selftest, b.selftestOut,
                b.selftestRecovery, b.testApi, b.noBrowser, b.noWindow, b.warnings);
    }

    /**
     * Каноническая командная строка этих параметров (для запуска дочерних JVM и проброса через лаунчер).
     * Предупреждения не переносятся.
     *
     * @return аргументы в порядке таблицы класса
     */
    public List<String> toArguments() {
        List<String> result = new ArrayList<>();
        if (home != null) {
            result.addAll(List.of("--home", home.toString()));
        }
        if (registryNode != null) {
            result.addAll(List.of("--registry-node", registryNode));
        }
        if (registryMemory) {
            result.addAll(List.of("--registry", "memory"));
        }
        if (today != null) {
            result.addAll(List.of("--today", DateFormats.iso(today)));
        }
        result.addAll(List.of("--ui", ui.name().toLowerCase(Locale.ROOT)));
        if (selftest != null) {
            result.addAll(List.of("--selftest", selftest));
        }
        if (selftestOut != null) {
            result.addAll(List.of("--selftest-out", selftestOut.toString()));
        }
        if (selftestRecovery != null) {
            result.addAll(List.of("--selftest-recovery", selftestRecovery.argument()));
        }
        if (testApi) {
            result.add("--test-api");
        }
        if (noBrowser) {
            result.add("--no-browser");
        }
        if (noWindow) {
            result.add("--no-window");
        }
        return List.copyOf(result);
    }

    /** @return включён ли режим самотеста */
    public boolean isSelftest() {
        return selftest != null;
    }

    /** Изменяемые поля во время разбора. */
    private static final class Builder {
        private Path home;
        private String registryNode;
        private boolean registryMemory;
        private LocalDate today;
        private UiMode ui = UiMode.LEGACY;
        private String selftest;
        private Path selftestOut;
        private RecoveryAnswer selftestRecovery;
        private boolean testApi;
        private boolean noBrowser;
        private boolean noWindow;
        private final List<String> warnings = new ArrayList<>();
    }

    private static Optional<String> property(Properties properties, String name) {
        String value = properties.getProperty(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    private static String value(List<String> args, int index, String name) {
        if (index >= args.size() || args.get(index) == null || args.get(index).startsWith("--")) {
            throw new IllegalArgumentException(UiText.get("launch.error.noValue", name));
        }
        return args.get(index);
    }

    private static boolean flag(String inline, String name, Builder b) {
        if (inline != null) {
            b.warnings.add(UiText.get("launch.warn.flagValue", name, inline));
        }
        return true;
    }

    private static Path path(String text, String name) {
        try {
            return Path.of(text.strip());
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(UiText.get("launch.error.path", name, text), e);
        }
    }

    private static String node(String text, String name) {
        String prefix = text.strip();
        try {
            // Проверка префикса та же, что у хранилища: иначе ошибка всплыла бы только при записи снимка.
            String node = RegistrySessionStore.nodePath(prefix, "fx");
            return node.substring(0, node.length() - "/fx".length());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(UiText.get("launch.error.value", name, e.getMessage()), e);
        }
    }

    private static LocalDate date(String text, String name) {
        try {
            return DateFormats.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(UiText.get("launch.error.value", name, e.getMessage()), e);
        }
    }

    private static RecoveryAnswer recovery(String text, String name) {
        for (RecoveryAnswer answer : RecoveryAnswer.values()) {
            if (answer.argument().equalsIgnoreCase(text.strip())) {
                return answer;
            }
        }
        throw new IllegalArgumentException(UiText.get("launch.error.recovery", name, text));
    }

    private static UiMode ui(String text, String name) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "core" -> UiMode.CORE;
            case "legacy" -> UiMode.LEGACY;
            default -> throw new IllegalArgumentException(UiText.get("launch.error.ui", name, text));
        };
    }
}
