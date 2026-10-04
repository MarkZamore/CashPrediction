package ru.cashprediction.parity.e2e.desktop;

import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Настоящая аварийная остановка и восстановление двух настольных клиентов, без подмены UI снимками. */
@EnabledIfSystemProperty(named = "parity.e2e", matches = "true")
class CrashRestoreE2ETest {
    /** Все независимые сочетания клиента, источника и юридически допустимого набора окон. */
    static Stream<Arguments> cases() {
        return Stream.of("fx", "swing").flatMap(client -> Stream.of("registry", "xml", "none")
                .flatMap(store -> Stream.of(DesktopCrashHarness.Cohort.values())
                        .map(cohort -> Arguments.of(client, store, cohort))));
    }

    /** Включённый gate обязан завершаться ошибкой при отсутствии рабочего desktop или артефактов. */
    @ParameterizedTest(name = "{0}/{1}/{2}")
    @MethodSource("cases")
    void forcedDeathThenRestore(String client, String store, DesktopCrashHarness.Cohort cohort) throws Exception {
        try (var harness = new DesktopCrashHarness(client)) {
            harness.run(store, cohort);
        }
    }

    /** Встроенный halt проверяется отдельно: главное меню должно оставаться доступным. */
    @ParameterizedTest(name = "confirmed halt/{0}")
    @ValueSource(strings = {"fx", "swing"})
    void confirmedBuiltInHalt(String client) throws Exception {
        try (var harness = new DesktopCrashHarness(client)) { harness.confirmedHalt(); }
    }
}
