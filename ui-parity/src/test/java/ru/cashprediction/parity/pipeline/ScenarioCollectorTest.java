package ru.cashprediction.parity.pipeline;

import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет, что завершение раннера не маскирует пропущенные, повторные и неподдержанные шаги. */
class ScenarioCollectorTest {
    /** Журнал должен покрывать каждую реальную строку каждого из 18 встроенных сценариев. */
    @Test void acceptsExactCompleteLogsForAllBuiltins() {
        for (String scenario : SelfTestScript.SCENARIOS) {
            StringBuilder log = new StringBuilder();
            for (var line : SelfTestScript.load(scenario).lines())
                log.append("SELFTEST ").append(line.number()).append(" OK ").append(line.text()).append('\n');
            log.append("SELFTEST DONE\n");
            assertDoesNotThrow(() -> ScenarioCollector.validateLog(scenario, log.toString()));
            assertThrows(IllegalStateException.class, () -> ScenarioCollector.validateLog(scenario, "SELFTEST DONE\n"));
        }
    }

    /** Номер или текст нельзя менять даже при явном OK, FAIL остаётся допустимым результатом строки. */
    @Test void rejectsChangedOrderAndPreservesFailureResults() {
        String scenario = "s17-recovery-dialog";
        StringBuilder log = new StringBuilder();
        for (var line : SelfTestScript.load(scenario).lines())
            log.append("SELFTEST ").append(line.number()).append(" FAIL ").append(line.text()).append(": unsupported\n");
        log.append("SELFTEST DONE\n");
        assertDoesNotThrow(() -> ScenarioCollector.validateLog(scenario, log.toString()));
        assertThrows(IllegalStateException.class, () -> ScenarioCollector.validateLog(scenario,
                log.toString().replaceFirst("SELFTEST [0-9]+", "SELFTEST 999")));
        assertThrows(IllegalStateException.class, () -> ScenarioCollector.validateLog(scenario,
                log.toString().replaceFirst("today", "sample")));
    }
}
