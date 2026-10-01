package ru.cashprediction.parity.pipeline;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.cashprediction.core.ui.dump.*;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.parity.dummy.FakeParityClientMain;
import ru.cashprediction.parity.launch.*;

/** Проверка полного запуска JVM фиктивного клиента и отчёта; не является проверкой реальных рендереров. */
class ParityPipelineIT {
    /** Собирает три фиктивных клиента и находит намеренную мутацию и попарные расхождения. */
    @Test void fakeClientMutationIsReported() throws Exception {
        Path root = Path.of(System.getProperty("parity.reactor.root", ".")).toAbsolutePath().normalize()
                .resolve("ui-parity/target/parity/infra-" + UUID.randomUUID());
        Path goldens = root.resolve("goldens"), scenario = goldens.resolve("s02-sample-table");
        Files.createDirectories(scenario);
        Files.writeString(scenario.resolve("sample.json"), UiJson.write(fixture("model", "s02-sample-table", "sample")));
        Path jar = DummyClientJar.build(root.resolve("jar"));
        Path core = DummyClientJar.codeSource(UiDump.class);
        ScenarioCollector collector = new ScenarioCollector(client -> new ClientTarget(client, List.of(jar, core),
                DummyClientJar.MODULE_NAME, FakeParityClientMain.class.getName(), List.of("ALL-MODULE-PATH"),
                List.of("-Dparity.fake.goldens=" + goldens, "-Dparity.fake.client=" + client,
                        "-Dparity.fake.mutate=" + client.equals("web")), List.of()), Duration.ofSeconds(20));
        var result = ParityPipeline.run(goldens, root.resolve("output"), List.of("fx", "swing", "web"),
                List.of("s02-sample-table"), new AllowedDiffs(List.of()), true, collector);
        assertFalse(result.ok());
        String html = Files.readString(result.report());
        assertTrue(html.contains("/counters/unexpected"), html);
        assertTrue(html.contains("fx vs web"), html);
        assertTrue(html.contains("sample: PASS"), html);
        assertThrows(AssertionError.class, result::requireSuccess);
    }

    /** Минимальный синтетический дамп для проверок инфраструктуры. */
    static UiDump fixture(String client, String scenario, String step) {
        return new UiDump(1, client, scenario, step, null, List.of(), null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

}
