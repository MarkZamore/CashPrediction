package ru.cashprediction.parity.launch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Что запускать: module path, главный модуль и класс, а также постоянные опции и аргументы клиента.
 *
 * <p>Все три клиента запускаются с module path (архитектура §6.3), поэтому класс описывает запуск вида
 * {@code java [опции] --module-path <jar> [--add-modules ...] --module <модуль>/<класс> [аргументы]}.
 * Фабрики {@link #fx}, {@link #swing} и {@link #web} знают модули реактора; {@link #dummy} запускает
 * тестовую заглушку и не зависит от изменений клиентов.</p>
 *
 * @param client      идентификатор клиента для папок стенда ({@code fx}, {@code swing}, {@code web}, {@code dummy})
 * @param modulePath  элементы module path (jar или папки с {@code module-info.class})
 * @param mainModule  имя главного модуля
 * @param mainClass   полное имя главного класса
 * @param addModules  значения {@code --add-modules} (пусто — опция не передаётся)
 * @param jvmOptions  опции JVM, особые для клиента (добавляются после общих опций стенда)
 * @param arguments   аргументы приложения, особые для клиента (добавляются после аргументов запроса)
 */
public record ClientTarget(String client, List<Path> modulePath, String mainModule, String mainClass,
                           List<String> addModules, List<String> jvmOptions, List<String> arguments) {

    /** Имя модуля JavaFX-клиента. */
    public static final String FX_MODULE = "ru.cashprediction.fx";
    /** Главный класс JavaFX-клиента. */
    public static final String FX_MAIN = "ru.cashprediction.fx.FxMain";
    /** Имя модуля Swing-клиента. */
    public static final String SWING_MODULE = "ru.cashprediction.swing";
    /** Главный класс Swing-клиента. */
    public static final String SWING_MAIN = "ru.cashprediction.swing.SwingMain";
    /** Имя модуля web-сервера. */
    public static final String WEB_MODULE = "ru.cashprediction.web";
    /** Главный класс web-сервера. */
    public static final String WEB_MAIN = "ru.cashprediction.web.WebMain";

    /** Копирует списки и проверяет обязательные поля. */
    public ClientTarget {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(mainModule, "mainModule");
        Objects.requireNonNull(mainClass, "mainClass");
        modulePath = List.copyOf(modulePath);
        addModules = addModules == null ? List.of() : List.copyOf(addModules);
        jvmOptions = jvmOptions == null ? List.of() : List.copyOf(jvmOptions);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (modulePath.isEmpty()) {
            throw new IllegalArgumentException("modulePath must not be empty");
        }
    }

    /**
     * JavaFX-клиент из jar реактора: ui-fx, core и платформенные jar OpenJFX.
     *
     * @param layout раскладка реактора
     * @return цель запуска
     */
    public static ClientTarget fx(ReactorLayout layout) {
        List<Path> path = new ArrayList<>();
        path.add(layout.moduleJar("ui-fx"));
        path.add(layout.moduleJar("core"));
        path.addAll(layout.javafxJars());
        return new ClientTarget("fx", path, FX_MODULE, FX_MAIN, List.of(), List.of(), List.of());
    }

    /**
     * Swing-клиент из jar реактора: ui-swing и core.
     *
     * @param layout раскладка реактора
     * @return цель запуска
     */
    public static ClientTarget swing(ReactorLayout layout) {
        return new ClientTarget("swing", List.of(layout.moduleJar("ui-swing"), layout.moduleJar("core")),
                SWING_MODULE, SWING_MAIN, List.of(), List.of(), List.of());
    }

    /**
     * Web-сервер из jar реактора: web и core; без браузера и окна сервера, с тестовым API (архитектура §6.3).
     *
     * @param layout раскладка реактора
     * @return цель запуска
     */
    public static ClientTarget web(ReactorLayout layout) {
        return new ClientTarget("web", List.of(layout.moduleJar("web"), layout.moduleJar("core")),
                WEB_MODULE, WEB_MAIN, List.of(), List.of(),
                List.of(ClientLauncher.ARG_NO_BROWSER, ClientLauncher.ARG_NO_WINDOW, ClientLauncher.ARG_TEST_API));
    }

    /**
     * Тестовая заглушка клиента: автоматический модуль из {@code DummyClientJar} и модуль ядра.
     *
     * <p>Автоматический модуль не объявляет зависимостей, поэтому ядро и его {@code java.prefs}
     * подключаются через {@code --add-modules ALL-MODULE-PATH}.</p>
     *
     * @param dummyJar       jar заглушки
     * @param coreModulePath jar или папка классов модуля ядра
     * @return цель запуска
     */
    public static ClientTarget dummy(Path dummyJar, Path coreModulePath) {
        return new ClientTarget("dummy", List.of(dummyJar, coreModulePath), DummyClientJar.MODULE_NAME,
                DummyClientJar.MAIN_CLASS, List.of("ALL-MODULE-PATH"), List.of(), List.of());
    }
}
