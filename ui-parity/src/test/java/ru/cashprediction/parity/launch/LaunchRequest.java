package ru.cashprediction.parity.launch;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Параметры одного запуска клиента на стенде: изолированная домашняя папка, тестовый узел реестра,
 * зафиксированная дата и самотест (архитектура §6.3).
 *
 * @param client             идентификатор клиента для папок стенда
 * @param scenario           имя сценария (папка и значение {@code --selftest})
 * @param home               изолированная папка приложения ({@code --home}); рядом с ней журналы процесса
 * @param registryNodePrefix префикс тестового узла реестра ({@code --registry-node}) или {@code null}
 * @param today              зафиксированное «сегодня» ({@code --today}) или {@code null}
 * @param selftest           сценарий самотеста ({@code --selftest}) или {@code null}
 * @param selftestOut        папка результатов самотеста ({@code --selftest-out}) или {@code null}
 * @param ui                 {@code core} или {@code legacy} ({@code --ui}) или {@code null} — не передавать
 * @param extraArguments     дополнительные аргументы приложения (после стандартных)
 * @param jvmOptions         дополнительные опции JVM (после общих опций стенда)
 */
public record LaunchRequest(String client, String scenario, Path home, String registryNodePrefix, LocalDate today,
                            String selftest, Path selftestOut, String ui, List<String> extraArguments,
                            List<String> jvmOptions) {

    /** Дата «сегодня», общая для всех сценариев паритета и золотых дампов (архитектура §6.3). */
    public static final LocalDate PARITY_TODAY = LocalDate.of(2026, 9, 13);

    /** Имя папки результатов самотеста внутри домашней папки запуска. */
    public static final String SELFTEST_OUT_DIR = "selftest-out";

    /** Проверяет обязательные поля, приводит пути к абсолютным и копирует списки. */
    public LaunchRequest {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(scenario, "scenario");
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        selftestOut = selftestOut == null ? null : selftestOut.toAbsolutePath().normalize();
        extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
        jvmOptions = jvmOptions == null ? List.of() : List.copyOf(jvmOptions);
    }

    /**
     * Стандартный запуск сценария: {@code --home <parityRoot>/<client>/<scenario>}, тестовый узел реестра,
     * {@code --today 2026-09-13}, {@code --selftest <scenario>} и {@code --selftest-out <home>/selftest-out}.
     *
     * @param parityRoot         корень стенда ({@link ReactorLayout#parityRoot()})
     * @param client             идентификатор клиента
     * @param scenario           имя сценария
     * @param registryNodePrefix тестовый префикс {@code ru/cashprediction/selftest/<uuid>}
     * @return запрос
     */
    public static LaunchRequest forScenario(Path parityRoot, String client, String scenario, String registryNodePrefix) {
        Path home = parityRoot.resolve(client).resolve(scenario);
        return new LaunchRequest(client, scenario, home, registryNodePrefix, PARITY_TODAY, scenario,
                home.resolve(SELFTEST_OUT_DIR), null, List.of(), List.of());
    }

    /**
     * Копия запроса с выбранным интерфейсом.
     *
     * @param mode {@code core}, {@code legacy} или {@code null}
     * @return новый запрос
     */
    public LaunchRequest withUi(String mode) {
        return new LaunchRequest(client, scenario, home, registryNodePrefix, today, selftest, selftestOut, mode,
                extraArguments, jvmOptions);
    }

    /**
     * Копия запроса с дополнительными аргументами приложения.
     *
     * @param arguments аргументы, добавляемые к уже заданным
     * @return новый запрос
     */
    public LaunchRequest withExtraArguments(List<String> arguments) {
        List<String> all = new ArrayList<>(extraArguments);
        all.addAll(arguments);
        return new LaunchRequest(client, scenario, home, registryNodePrefix, today, selftest, selftestOut, ui, all,
                jvmOptions);
    }

    /**
     * Копия запроса с дополнительными опциями JVM.
     *
     * @param options опции, добавляемые к уже заданным
     * @return новый запрос
     */
    public LaunchRequest withJvmOptions(List<String> options) {
        List<String> all = new ArrayList<>(jvmOptions);
        all.addAll(options);
        return new LaunchRequest(client, scenario, home, registryNodePrefix, today, selftest, selftestOut, ui,
                extraArguments, all);
    }

    /**
     * Папка журналов процесса. Журналы лежат рядом с домашней папкой, а не в ней: так проверка
     * «приложение создало только CashMemory» не спотыкается о файлы самого стенда.
     *
     * @return родитель домашней папки
     */
    public Path logDirectory() {
        Path parent = home.getParent();
        return parent != null ? parent : home;
    }
}
