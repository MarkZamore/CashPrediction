package ru.cashprediction.core.app;

import java.nio.file.Path;
import java.util.Objects;
import ru.cashprediction.core.io.AppPaths;
import ru.cashprediction.core.session.store.MarkdownSessionStore;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;

/**
 * Окружение процесса для {@code AppController}: параметры запуска, папки и часы.
 *
 * <p>Создание окружения ничего не пишет на диск и не открывает реестр: папку CashMemory создаёт
 * {@code StartupFlow}, хранилища — {@code SessionStores}. Благодаря этому окружение безопасно строить в тестах.</p>
 *
 * @param options    параметры запуска
 * @param appHome    папка приложения (рядом с exe или {@code --home})
 * @param cashMemory папка CashMemory ({@code appHome/CashMemory}); может ещё не существовать
 * @param clock      часы приложения
 */
public record AppEnvironment(LaunchOptions options, Path appHome, Path cashMemory, AppClock clock) {

    /** Проверяет поля и нормализует пути. */
    public AppEnvironment {
        Objects.requireNonNull(options, "options");
        appHome = Objects.requireNonNull(appHome, "appHome").toAbsolutePath().normalize();
        cashMemory = Objects.requireNonNull(cashMemory, "cashMemory").toAbsolutePath().normalize();
        Objects.requireNonNull(clock, "clock");
    }

    /**
     * Окружение по параметрам запуска: папка из {@code --home}, иначе {@link AppPaths#appHome()};
     * часы с зафиксированной датой, если задан {@code --today}.
     *
     * @param options параметры запуска
     * @return окружение
     */
    public static AppEnvironment from(LaunchOptions options) {
        Path home = options.home() != null ? options.home() : AppPaths.appHome();
        Path absolute = home.toAbsolutePath().normalize();
        AppClock clock = options.today() != null ? AppClock.fixedToday(options.today()) : AppClock.system();
        return new AppEnvironment(options, absolute, absolute.resolve(AppPaths.CASH_MEMORY_DIR), clock);
    }

    /**
     * Путь узла реестра, который получит клиент (без обращения к реестру).
     *
     * @param client {@code fx} или {@code swing}
     * @return путь относительно {@code HKCU\Software\JavaSoft\Prefs} или пустая строка при {@code --registry memory}
     */
    public String registryNodePath(String client) {
        return options.registryMemory() ? "" : RegistrySessionStore.resolveNodePath(client, cashMemory, options.registryNode());
    }

    /**
     * Хранилище «реестр Windows» клиента по решению L2: узел установки, явный префикс или память процесса.
     *
     * @param client {@code fx} или {@code swing}
     * @return хранилище (открывает узел реестра, если не {@code --registry memory})
     */
    public RegistrySessionStore registryStore(String client) {
        return options.registryMemory()
                ? RegistrySessionStore.inMemory(client, cashMemory)
                : RegistrySessionStore.forClient(client, cashMemory, options.registryNode());
    }

    /**
     * Хранилище «XML-файл» клиента: {@code CashMemory/session-<клиент>.xml}.
     *
     * @param client {@code fx} или {@code swing}
     * @return хранилище (файл не создаётся до первой записи)
     */
    public XmlSessionStore xmlStore(String client) {
        return XmlSessionStore.inCashMemory(cashMemory, client);
    }

    /**
     * Серверное хранилище web-клиента: {@code CashMemory/web-session.md}.
     *
     * @return хранилище (файл не создаётся до первой записи)
     */
    public MarkdownSessionStore webStore() {
        return MarkdownSessionStore.inCashMemory(cashMemory);
    }
}
