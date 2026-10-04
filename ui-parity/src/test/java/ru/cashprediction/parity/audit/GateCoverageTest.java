package ru.cashprediction.parity.audit;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import static org.junit.jupiter.api.Assertions.*;

/** Независимо требует полные реальные журналы и пары изображение/raw, а не только зелёный Surefire. */
public final class GateCoverageTest {
    /** Проверяет артефакты уникального запуска полного S4 UI gate. */
    @Test @EnabledIfSystemProperty(named = "parity.gateCoverage", matches = "true")
    void actualArtifactsAreComplete() throws Exception {
        Path root = Path.of(System.getProperty("parity.gateCoverage.root"));
        var receipt = FreshAllowanceEvidence.receipt(root);
        for (String client : List.of("fx", "swing", "web")) for (String scenario : SelfTestScript.SCENARIOS) {
            Path base = root.resolve(client).resolve(scenario);
            List<Path> logs;
            try (var files = Files.walk(base)) { logs = files.filter(p -> p.getFileName().toString().equals("selftest.log")).toList(); }
            assertEquals(1, logs.size(), "Exactly one fresh journal required: " + client + "/" + scenario);
            FreshAllowanceEvidence.requireFresh(logs.getFirst(), root, receipt.started());
            requireJournal(logs.getFirst(), scenario);
            List<Path> runs;
            try (var files = Files.list(base)) { runs = files.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith("run-")).toList(); }
            assertEquals(1, runs.size(), "Exactly one matrix run required");
            Path dumpRoot = logs.getFirst().getParent().resolve(scenario);
            var expectedSteps = new HashSet<String>();
            for (var line : SelfTestScript.load(scenario).lines())
                if (line.command() instanceof ru.cashprediction.core.ui.selftest.SelfTestCommand.Dump d) expectedSteps.add(d.step());
            var actualSteps = new HashSet<String>();
            try (var files = Files.list(dumpRoot)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json") && !p.getFileName().toString().endsWith(".raw.json")).toList()) {
                    FreshAllowanceEvidence.requireFresh(file, root, receipt.started());
                    var dump = ru.cashprediction.parity.pipeline.DumpTrees.read(Files.readString(file));
                    assertEquals(1, dump.schema()); assertEquals(client, dump.client()); assertEquals(scenario, dump.scenario());
                    assertEquals(dump.step() + ".json", file.getFileName().toString()); assertTrue(actualSteps.add(dump.step()));
                }
            }
            assertEquals(expectedSteps, actualSteps, "Exact matrix dump steps required");
        }
        List<Path> visual;
        try (var files = Files.list(root)) { visual = files.filter(p -> p.getFileName().toString().startsWith("visual-")).toList(); }
        assertEquals(1, visual.size(), "Exactly one visual run required");
        List<Path> raw;
        try (var files = Files.walk(visual.getFirst())) { raw = files.filter(p -> p.getFileName().toString().endsWith(".raw.json")).toList(); }
        assertEquals(12, raw.size(), "Four checkpoints by three actual clients required");
        var counts = new HashMap<String, Integer>();
        var identities = new ArrayList<String>();
        for (Path p : raw) {
            FreshAllowanceEvidence.requireFresh(p, root, receipt.started());
            Path png = p.resolveSibling(p.getFileName().toString().replace(".raw.json", ".png"));
            assertTrue(Files.size(png) > 8, "Missing actual PNG: " + png);
            var dump = ru.cashprediction.parity.pipeline.DumpTrees.read(Files.readString(p));
            assertEquals(1, dump.schema()); assertEquals(dump.step() + ".raw.json", p.getFileName().toString());
            identities.add(dump.client() + "/" + dump.scenario() + "/" + dump.step());
            counts.merge(dump.client(), 1, Integer::sum);
            var image = javax.imageio.ImageIO.read(png.toFile());
            assertNotNull(image, "Invalid PNG: " + png);
            assertEquals(1200, image.getWidth()); assertEquals(800, image.getHeight());
        }
        assertEquals(Map.of("fx", 4, "swing", 4, "web", 4), counts);
        requireVisualIdentities(identities);
        String report = Files.readString(root.resolve("report-fx-swing-web.html"));
        requireReport(report);
        List<Path> census;
        try (var files = Files.walk(root)) { census = files.filter(p -> p.getFileName().toString().equals("class-census.txt")).toList(); }
        assertEquals(1, census.size(), "Exactly one actual census report required");
        FreshAllowanceEvidence.requireFresh(census.getFirst(), root, receipt.started());
        var censusText = Files.readString(census.getFirst());
        assertTrue(censusText.startsWith("FX instantiated census: 23/23\n"));
        assertFalse(censusText.contains("NOT INSTANTIATED")); assertFalse(censusText.contains("FAILED scenario"));
        for (String name : ru.cashprediction.parity.check.census.FxCensus.REQUIRED) {
            var matches = censusText.lines().filter(line -> line.startsWith(name + ": ")).toList();
            assertEquals(1, matches.size());
            assertTrue(matches.getFirst().matches(".* count=[1-9][0-9]*$"), "Positive actual class instance required: " + name);
        }
    }

    /** Проверяет каждую пару клиент/сценарий/шаг; дубликат не заменяет отсутствующую точку. */
    static void requireVisualIdentities(Collection<String> actual) {
        var expected = new HashSet<String>();
        for (var point : ru.cashprediction.parity.check.visual.VisualPlan.checkpoints("", "first"))
            for (String client : List.of("fx", "swing", "web"))
                expected.add(client + "/" + point.script().name() + "/" + point.step());
        assertEquals(expected.size(), actual.size()); assertEquals(expected, new HashSet<>(actual));
    }

    /** Требует все 108 конкретных сравнений и двенадцать конкретных evidence-пар, не число HTML-заголовков. */
    static void requireReport(String report) {
        assertFalse(report.contains("Partial matrix")); assertTrue(report.contains("<p>Failures: 0</p>"));
        var expected = new HashSet<String>();
        for (String scenario : SelfTestScript.SCENARIOS) {
            for (String client : List.of("fx", "swing", "web")) expected.add(scenario + " / " + client + " / golden");
            for (String pair : List.of("fx vs swing", "fx vs web", "swing vs web")) expected.add(scenario + " / " + pair);
        }
        for (String label : List.of("halt-web", "snapshot-native-absent", "snapshot-web-absent", "snapshot-native-prepared", "snapshot-web-prepared",
                "replace-native", "replace-web", "uncaught-web", "narrow-web", "offline-web", "stopped-web", "crashed-web"))
            expected.add("evidence / " + label);
        var found = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("<h2>([^<]+)</h2>").matcher(report);
        while (matcher.find()) found.add(matcher.group(1));
        assertEquals(120, found.size()); assertEquals(expected, new HashSet<>(found));
    }

    /** Сверяет каждую команду встроенного сценария с подтверждением настоящего клиента. */
    static void requireJournal(Path log, String scenario) throws Exception {
        var commands = SelfTestScript.load(scenario).lines(); var lines = Files.readAllLines(log);
        assertEquals(commands.size() + 1, lines.size()); assertEquals("SELFTEST DONE", lines.getLast());
        for (int i = 0; i < commands.size(); i++) {
            var command = commands.get(i);
            assertEquals("SELFTEST " + command.number() + " OK " + command.text(), lines.get(i));
        }
    }
}
