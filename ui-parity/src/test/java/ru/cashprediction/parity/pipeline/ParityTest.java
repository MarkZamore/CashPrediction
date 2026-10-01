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
        var goldens = layout.root().resolve("core/src/test/resources/ui-golden");
        var allowed = AllowedDiffs.parse(Files.readString(goldens.resolve("allowed-diffs.json")));
        var collector = new ScenarioCollector(client -> switch (client) {
            case "fx" -> ClientTarget.fx(layout);
            case "swing" -> ClientTarget.swing(layout);
            case "web" -> ClientTarget.web(layout);
            default -> throw new IllegalArgumentException(client);
        }, Duration.ofSeconds(45));
        ParityPipeline.run(goldens, layout.parityRoot(), clients, scenarios, allowed,
                clients.size() == 3 && scenarios.equals(SelfTestScript.SCENARIOS), collector).requireSuccess();
    }
}
