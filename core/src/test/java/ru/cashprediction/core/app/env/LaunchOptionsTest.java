package ru.cashprediction.core.app.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.app.LaunchOptions.RecoveryAnswer;
import ru.cashprediction.core.app.LaunchOptions.UiMode;

/**
 * Параметры запуска трёх exe (архитектура §3.8): аргументы и свойства, приоритет аргументов, неизвестные аргументы как
 * предупреждения, ошибки известных аргументов и каноническая командная строка.
 */
class LaunchOptionsTest {

    private static LaunchOptions parse(Properties properties, String... args) {
        return LaunchOptions.parse(List.of(args), properties);
    }

    @Test
    void defaultsWhenNothingIsGiven() {
        LaunchOptions options = parse(new Properties());
        assertEquals(LaunchOptions.defaults(), options);
        assertNull(options.home());
        assertNull(options.registryNode());
        assertFalse(options.registryMemory());
        assertNull(options.today());
        assertEquals(UiMode.LEGACY, options.ui(), "прежний интерфейс по умолчанию до паритета (R4)");
        assertFalse(options.isSelftest());
        assertEquals(List.of(), options.warnings());
    }

    @Test
    void everyArgumentOfTheDesignTable() {
        LaunchOptions options = parse(new Properties(),
                "--home", "C:\\Temp\\cp home", "--registry-node", "ru/cashprediction/selftest/1b2c", "--registry", "memory",
                "--today", "2026-09-13", "--ui", "core", "--selftest", "s02-sample-table", "--selftest-out", "out",
                "--selftest-recovery", "already-ok", "--test-api", "--no-browser", "--no-window");
        assertEquals(Path.of("C:\\Temp\\cp home"), options.home());
        assertEquals("ru/cashprediction/selftest/1b2c", options.registryNode());
        assertTrue(options.registryMemory());
        assertEquals(LocalDate.of(2026, 9, 13), options.today());
        assertEquals(UiMode.CORE, options.ui());
        assertEquals("s02-sample-table", options.selftest());
        assertEquals(Path.of("out"), options.selftestOut());
        assertEquals(RecoveryAnswer.ALREADY_OK, options.selftestRecovery());
        assertTrue(options.testApi() && options.noBrowser() && options.noWindow());
        assertTrue(options.isSelftest());
        assertEquals(List.of(), options.warnings());
    }

    @Test
    void inlineValuesAndRussianDates() {
        LaunchOptions options = parse(new Properties(), "--home=D:\\x", "--today=13.09.2026", "--ui=LEGACY",
                "--selftest-recovery=XML", "--registry=memory");
        assertEquals(Path.of("D:\\x"), options.home());
        assertEquals(LocalDate.of(2026, 9, 13), options.today());
        assertEquals(UiMode.LEGACY, options.ui());
        assertEquals(RecoveryAnswer.XML, options.selftestRecovery());
        assertTrue(options.registryMemory());
    }

    @Test
    void propertiesAreHonouredAndArgumentsWin() {
        Properties properties = new Properties();
        properties.setProperty("cashprediction.home", "from-property");
        properties.setProperty("cashprediction.registry.node", "ru/cashprediction/selftest/prop/");
        properties.setProperty("cashprediction.today", "2026-01-31");
        properties.setProperty("cashprediction.ui", "core");
        properties.setProperty("cashprediction.selftest", "script.txt");
        properties.setProperty("cashprediction.selftest.log", "run.log");
        properties.setProperty("cashprediction.selftest.recovery", "registry");

        LaunchOptions fromProperties = parse(properties);
        assertEquals(Path.of("from-property"), fromProperties.home());
        assertEquals("ru/cashprediction/selftest/prop", fromProperties.registryNode(), "завершающая косая черта снимается");
        assertEquals(LocalDate.of(2026, 1, 31), fromProperties.today());
        assertEquals(UiMode.CORE, fromProperties.ui());
        assertEquals("script.txt", fromProperties.selftest());
        assertEquals(Path.of("run.log"), fromProperties.selftestOut());
        assertEquals(RecoveryAnswer.REGISTRY, fromProperties.selftestRecovery());

        LaunchOptions overridden = parse(properties, "--home", "from-arg", "--ui", "legacy", "--selftest-recovery", "none");
        assertEquals(Path.of("from-arg"), overridden.home());
        assertEquals(UiMode.LEGACY, overridden.ui());
        assertEquals(RecoveryAnswer.NONE, overridden.selftestRecovery());
        assertEquals("ru/cashprediction/selftest/prop", overridden.registryNode());
    }

    @Test
    void unknownArgumentsAreWarningsNotErrors() {
        LaunchOptions options = parse(new Properties(), "-psn_0_1234", "--verbose", "file.md", "--test-api=yes");
        assertEquals(List.of(
                "Неизвестный аргумент «-psn_0_1234» пропущен",
                "Неизвестный аргумент «--verbose» пропущен",
                "Неизвестный аргумент «file.md» пропущен",
                "У флага --test-api не бывает значения: «=yes» пропущено"), options.warnings());
        assertTrue(options.testApi());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ru/other/session", "software/ru/cashprediction/x", "ru/cashprediction", "ru/cashprediction/../x"})
    void registryNodeOutsideOwnBranchIsRejected(String node) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse(new Properties(), "--registry-node", node));
        assertTrue(e.getMessage().startsWith("--registry-node: "), e.getMessage());
        assertTrue(e.getMessage().contains("узел реестра") || e.getMessage().contains("Узел реестра"), e.getMessage());

        Properties properties = new Properties();
        properties.setProperty("cashprediction.registry.node", node);
        IllegalArgumentException fromProperty = assertThrows(IllegalArgumentException.class, () -> parse(properties));
        assertTrue(fromProperty.getMessage().startsWith("cashprediction.registry.node: "), fromProperty.getMessage());
    }

    @Test
    void badValuesOfKnownArgumentsAreErrors() {
        assertEquals("У аргумента --home нет значения",
                assertThrows(IllegalArgumentException.class, () -> parse(new Properties(), "--home")).getMessage());
        assertEquals("У аргумента --today нет значения",
                assertThrows(IllegalArgumentException.class, () -> parse(new Properties(), "--today", "--ui", "core")).getMessage());
        assertEquals("Аргумент --registry: поддерживается только значение memory, а не «disk»",
                assertThrows(IllegalArgumentException.class, () -> parse(new Properties(), "--registry", "disk")).getMessage());
        assertEquals("--ui: ожидалось core или legacy, а не «web»",
                assertThrows(IllegalArgumentException.class, () -> parse(new Properties(), "--ui", "web")).getMessage());
        assertEquals("--selftest-recovery: ожидалось registry, xml, none или already-ok, а не «maybe»",
                assertThrows(IllegalArgumentException.class,
                        () -> parse(new Properties(), "--selftest-recovery", "maybe")).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(new Properties(), "--today", "13/09/2026"))
                .getMessage().startsWith("--today: "));
        assertEquals("--selftest-recovery: ожидалось registry, xml, none или already-ok, а не «x»",
                assertThrows(IllegalArgumentException.class, () -> RecoveryAnswer.parse("x")).getMessage());
    }

    @Test
    void canonicalArgumentsRoundTrip() {
        LaunchOptions options = parse(new Properties(), "--home", "h", "--registry-node", "ru/cashprediction/selftest/u",
                "--today", "13.09.2026", "--ui", "core", "--selftest", "s01-first-run", "--selftest-out", "o",
                "--selftest-recovery", "xml", "--test-api", "--no-browser", "--no-window", "--registry", "memory", "--junk");
        List<String> arguments = options.toArguments();
        assertTrue(arguments.containsAll(List.of("--today", "2026-09-13")), "дата пишется ISO: это аргумент, не интерфейс");
        LaunchOptions again = LaunchOptions.parse(arguments, new Properties());
        assertEquals(new LaunchOptions(options.home(), options.registryNode(), options.registryMemory(), options.today(),
                options.ui(), options.selftest(), options.selftestOut(), options.selftestRecovery(), options.testApi(),
                options.noBrowser(), options.noWindow(), List.of()), again);
        assertEquals(List.of("--ui", "legacy"), LaunchOptions.defaults().toArguments());
    }
}
