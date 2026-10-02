package ru.cashprediction.parity.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.parity.check.census.ClientJarSnapshot;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.pipeline.DumpTrees;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import static org.junit.jupiter.api.Assertions.*;

/** Отдельные настоящие FX/Swing references; запуск допустим только после освобождения desktop ведущим. */
public final class NativeAllowanceReferenceTest {
    /** Требует отдельное подтверждение слота, не запускается обычной сборкой. */
    @TestFactory Stream<DynamicTest> nativeReferences() {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.realClients")
                && Boolean.getBoolean("parity.allowance.desktopReleased"), "MAIN desktop release required");
        return List.of("fx", "swing").stream().map(client -> DynamicTest.dynamicTest(client, () -> collect(client)));
    }

    /** Исполняет внешний CPS существующим widget driver, не меняя встроенные сценарии или golden. */
    private static void collect(String client) throws Exception {
        var layout = ReactorLayout.fromSystemProperties(); Files.createDirectories(layout.parityRoot());
        Path output = Files.createTempDirectory(layout.parityRoot(), "allowance-native-" + client + "-");
        String node = RegistryNodeCleaner.newSelftestNode();
        var before = RegistryNodeCleaner.snapshotRealSessionNodes();
        Path script = output.resolve("native-allowances.cps"), dumps = output.resolve("dumps");
        String text = "today 2026-09-13\nkey Esc\nsample\ndump prepared-sample\n"
                + "menu recovery.simulate.halt\ndump halt-question\nanswer " + quote(UiText.get("button.cancel"))
                + "\ndump halt-cancelled\nmenu recovery.clear\nanswer " + quote(UiText.get("button.clear"))
                + "\nmenu recovery.showLast\ndump snapshot-after-clear\nanswer " + quote(UiText.get("button.ok"))
                + "\nsave\nsnapshot\nmenu recovery.showLast\ndump snapshot-prepared\nanswer " + quote(UiText.get("button.ok"))
                + "\nchooser " + quote(UiText.get("sample.name") + ".md") + "\nmenu file.saveAs\ndump replace-file\n"
                + (client.equals("swing") ? "answer " + quote(UiText.get("button.cancel")) + "\n" : "")
                + "size 900 800\ndump narrow-toolbar\nmenu recovery.simulate.exception\ndump uncaught-reference\n";
        Files.writeString(script, text);
        var expected = SelfTestScript.parse("native-allowances", text);
        var target = ClientJarSnapshot.copy(client.equals("fx") ? ClientTarget.fx(layout) : ClientTarget.swing(layout), output);
        var request = new LaunchRequest(client, "native-allowances", output.resolve("home"), node,
                LaunchRequest.PARITY_TODAY, script.toString(), dumps, "core", List.of(), List.of());
        try {
            try (var launched = ClientLauncher.launch(target, request)) {
                Path log = dumps.resolve("selftest.log");
                launched.waitUntil(() -> {
                    try { return Files.readString(log).endsWith("SELFTEST DONE\n"); }
                    catch (java.io.IOException e) { return false; }
                }, Duration.ofSeconds(45), "native allowance DONE");
                var lines = Files.readAllLines(log);
                assertEquals(expected.lines().size() + 1, lines.size(), "artifacts=" + output);
                for (int i = 0; i < expected.lines().size(); i++) {
                    var line = expected.lines().get(i);
                    assertEquals("SELFTEST " + line.number() + " OK " + line.text(), lines.get(i), "artifacts=" + output);
                }
                Path root = dumps.resolve("native-allowances");
                assertEquals(1, DumpTrees.read(Files.readString(root.resolve("prepared-sample.json"))).counters().get("file.sample"));
                var halt = DumpTrees.read(Files.readString(root.resolve("halt-question.json")));
                assertEquals(UiText.get("alert.halt.header"), halt.alerts().getFirst().header());
                assertTrue(DumpTrees.read(Files.readString(root.resolve("halt-cancelled.json"))).alerts().isEmpty());
                for (String step : List.of("snapshot-after-clear", "snapshot-prepared")) {
                    var snapshot = DumpTrees.read(Files.readString(root.resolve(step + ".json"))).alerts().getFirst();
                    assertEquals("lastSnapshot", snapshot.purpose());
                    requirePreservedNode(snapshot.details(), client);
                }
                var replace = DumpTrees.read(Files.readString(root.resolve("replace-file.json")));
                if (client.equals("fx")) assertTrue(replace.alerts().isEmpty());
                else assertEquals("replaceFile", replace.alerts().getFirst().purpose());
                var uncaught = DumpTrees.read(Files.readString(root.resolve("uncaught-reference.json")));
                assertEquals(List.of("closeProgram"), uncaught.alerts().getFirst().buttons().stream().map(b -> b.id()).toList());
                assertEquals(900, DumpTrees.read(Files.readString(root.resolve("narrow-toolbar.json"))).frame().contentWidth());
                Files.writeString(output.resolve("reference-context.json"), UiJson.write(Map.of("client", client,
                        "cashMemory", output.resolve("home/CashMemory"), "node", node, "dumps", root,
                        "observation", "real-native-widget-driver", "chooserNativeConfirmation", "not-tested")));
            }
        } catch (Exception | AssertionError failure) {
            throw new AssertionError("Native reference artifacts=" + output, failure);
        } finally {
            RegistryNodeCleaner.delete(node); assertFalse(RegistryNodeCleaner.exists(node));
            assertEquals(before, RegistryNodeCleaner.snapshotRealSessionNodes());
        }
    }

    /** Экранирует локализованный текст в грамматике существующего CPS. */
    private static String quote(String value) { return "\"" + value.replace("\"", "\\\"") + "\""; }

    /** Не принимает раннее стирание client suffix или подстановку узла другого клиента. */
    static void requirePreservedNode(String details, String client) {
        if (!List.of("fx", "swing").contains(client)) throw new IllegalArgumentException("Native client required");
        String pattern = "<node>[/\\\\]" + java.util.regex.Pattern.quote(client) + "(?=[/\\\\\\s)]|$)";
        assertTrue(java.util.regex.Pattern.compile(pattern).matcher(details).find(), "Missing normalized node/client: " + client);
    }
}
