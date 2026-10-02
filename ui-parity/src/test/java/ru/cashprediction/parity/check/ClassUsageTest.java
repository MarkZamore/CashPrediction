package ru.cashprediction.parity.check;

import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.parity.check.census.FxCensus;
import ru.cashprediction.parity.check.census.DirectoryChooserProbe;
import ru.cashprediction.parity.check.census.ClientJarSnapshot;
import ru.cashprediction.parity.launch.ClientTarget;
import ru.cashprediction.parity.launch.ReactorLayout;
import ru.cashprediction.parity.pipeline.ParityPipeline;
import ru.cashprediction.parity.pipeline.ScenarioCollector;

/** Проверяет создание всех 23 классов JavaFX в новых запусках сценариев интерфейса ядра. */
public final class ClassUsageTest {
    /** Требует каждый ожидаемый класс в реальной переписи с положительным числом экземпляров. */
    public static void census(Set<String> expected, Map<String, Integer> actual) {
        if (expected.isEmpty()) throw new IllegalArgumentException("Empty expected census");
        actual.forEach((name, count) -> {
            if (count == null || count < 0) throw new AssertionError("Invalid count: " + name + "=" + count);
        });
        for (String name : expected) assertTrue(actual.getOrDefault(name, 0) > 0, "Not instantiated: " + name);
    }
    /** Проверяет, что список имён без экземпляров не засчитывается. */
    @Test void namesWithoutInstancesFail() {
        census(Set.of("Menu"), Map.of("Menu", 1));
        assertThrows(AssertionError.class, () -> census(Set.of("Menu"), Map.of("Menu", 0)));
    }

    /** Собирает настоящие дампы FX; фильтр сценариев никогда не уменьшает список обязательных классов. */
    @Test void realFxScenariosInstantiateRequired23() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.realClients"), "Enable -Dparity.realClients=true");
        var clients = ParityPipeline.clients(System.getProperty("parity.clients", "fx,swing,web"));
        Assumptions.assumeTrue(clients.contains("fx"), "Class census applies to fx only");
        ReactorLayout layout = ReactorLayout.fromSystemProperties();
        var scenarios = ParityPipeline.scenarios(SelfTestScript.SCENARIOS, System.getProperty("parity.scenarios", ""));
        Files.createDirectories(layout.parityRoot());
        var output = Files.createTempDirectory(layout.parityRoot(), "class-census-");
        var target = ClientJarSnapshot.copy(ClientTarget.fx(layout), output);
        // Все jar, включая ядро и OpenJFX, уже отделены от общей сборки; каталог тоже читается из снимка.
        var evidence = new FxCensus(FxCensus.toolkitClasses(target.modulePath()));
        var collector = new ScenarioCollector(client -> {
            if (!client.equals("fx")) throw new IllegalArgumentException(client);
            return target;
        }, Duration.ofSeconds(45));
        for (String scenario : scenarios) {
            try {
                var run = collector.collect("fx", scenario, output.resolve(scenario));
                evidence.addScenario(scenario, FxCensus.readScenario(run.dumps(), SelfTestScript.load(scenario)));
            } catch (Exception | AssertionError failure) {
                evidence.failedScenario(scenario, failure.toString());
            }
        }
        // Встроенные 18 сценариев не выбирают другую папку планов: дополнительный сценарий обязателен.
        try {
            evidence.addScenario(DirectoryChooserProbe.SCENARIO,
                    DirectoryChooserProbe.collect(target, output, Duration.ofSeconds(45)));
        } catch (Exception | AssertionError failure) {
            evidence.failedScenario(DirectoryChooserProbe.SCENARIO, failure.toString());
        }
        var report = output.resolve("class-census.txt");
        Files.writeString(report, evidence.report());
        var requiredScenarios = new LinkedHashSet<>(SelfTestScript.SCENARIOS);
        requiredScenarios.add(DirectoryChooserProbe.SCENARIO);
        evidence.requireComplete(requiredScenarios, report.toString());
    }
}
