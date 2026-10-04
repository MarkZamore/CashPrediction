package ru.cashprediction.parity.audit;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Выполняет CLI-агрегацию после настоящих сборщиков без создания синтетических наблюдений. */
public final class FreshAllowanceEvidenceTest {
    /** Требует новые каталоги, заданные ведущим скриптом текущего запуска. */
    @Test @EnabledIfSystemProperty(named = "parity.freshEvidence", matches = "true")
    void aggregateActualObservations() throws Exception {
        FreshAllowanceEvidence.main(new String[]{System.getProperty("parity.freshEvidence.input"),
                System.getProperty("parity.freshEvidence.output"), System.getProperty("parity.freshEvidence.runId")});
    }
}
