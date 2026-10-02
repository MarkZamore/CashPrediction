package ru.cashprediction.parity.pipeline;

import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import ru.cashprediction.core.ui.dump.AllowedDiffs;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.parity.launch.*;

/** Реальная матрица включается явно после подключения рендереров S3, а не подменяется моделями. */
class ParityTest {
    /** Запускает выбранную матрицу и проверяет итоговый отчёт. */
    @Test void realClientsAgainstGoldens() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.realClients"),
                "S2 infrastructure only; enable -Dparity.realClients=true after S3 widget drivers are connected");
        ReactorLayout layout = ReactorLayout.fromSystemProperties();
        var clients = ParityPipeline.clients(System.getProperty("parity.clients", "fx,swing,web"));
        var scenarios = ParityPipeline.scenarios(SelfTestScript.SCENARIOS, System.getProperty("parity.scenarios", ""));
        var matrixInput = layout.parityRoot().resolve("matrix-input-" + java.util.UUID.randomUUID());
        // Эталоны и таблица допустимых различий также неизменны на протяжении всей матрицы.
        var goldens = ModuleSnapshot.captureTree(layout.root().resolve("core/src/test/resources/ui-golden"),
                matrixInput.resolve("goldens"));
        var allowed = AllowedDiffs.parse(Files.readString(goldens.resolve("allowed-diffs.json")));
        // Дополнительные реальные артефакты закреплены SHA и прочитаны до запуска матрицы.
        String evidenceManifest = System.getProperty("parity.allowance.evidence", "");
        var evidence = evidenceManifest.isBlank() ? java.util.List.<ParityPipeline.EvidencePair>of()
                : ru.cashprediction.parity.audit.AllowanceEvidence.load(java.nio.file.Path.of(evidenceManifest));
        var targets = new java.util.LinkedHashMap<String, ClientTarget>();
        for (String client : clients) targets.put(client, switch (client) {
            case "fx" -> ClientTarget.fx(layout);
            case "swing" -> ClientTarget.swing(layout);
            case "web" -> ClientTarget.web(layout);
            default -> throw new IllegalArgumentException(client);
        });
        // Все сценарии проверяют один набор сборок, даже если параллельно пересобирается клиент.
        var frozen = ModuleSnapshot.captureAll(targets,
                matrixInput.resolve("modules"), layout.root());
        var collector = new ScenarioCollector(frozen::get, Duration.ofSeconds(45));
        ParityPipeline.run(goldens, layout.parityRoot(), clients, scenarios, allowed,
                clients.size() == 3 && scenarios.equals(SelfTestScript.SCENARIOS), collector, () -> evidence).requireSuccess();
    }
}
