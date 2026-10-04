package ru.cashprediction.parity.audit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.ui.dump.DumpNormalizer;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.check.census.ClientJarSnapshot;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.pipeline.DumpTrees;
import static org.junit.jupiter.api.Assertions.*;

/** Читает один неизменный архив через настоящие FX/Swing widgets; RAW payload не подменяется после capture. */
public final class ControlledSnapshotNativeTest {
    /** Отдельный opt-in требует разрешённый MAIN desktop slot. */
    @TestFactory Stream<DynamicTest> controlledNativeSnapshots() {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.realClients")
                && Boolean.getBoolean("parity.allowance.desktopReleased"), "MAIN desktop release required");
        return List.of("prepared", "absent").stream().map(role -> DynamicTest.dynamicTest(role, () -> collect(role)));
    }

    /** Один home/node/holder на две последовательные native страницы. */
    private static void collect(String role) throws Exception {
        var layout = ReactorLayout.fromSystemProperties(); Files.createDirectories(layout.parityRoot());
        Path output = Files.createTempDirectory(layout.parityRoot(), "controlled-" + role + "-");
        try (var fixture = ControlledSnapshotFixture.open(output, role.equals("prepared"))) {
            for (String client : List.of("fx", "swing")) capture(layout, fixture, client);
            fixture.requireUnchanged();
            Files.writeString(output.resolve("result.txt"), "PASS " + role + "\n");
        } catch (Exception | AssertionError failure) {
            throw new AssertionError("Controlled snapshot artifacts=" + output, failure);
        }
    }

    /** CPS открывает second-instance branch реальной кнопкой, затем только читает showLast. */
    private static void capture(ReactorLayout layout, ControlledSnapshotFixture fixture, String client) throws Exception {
        Path output = fixture.output.resolve(client); Files.createDirectories(output);
        String text = "dump already-running\nanswer " + quote(UiText.get("button.openWithoutRestore"))
                + "\ndump recording-disabled\nmenu recovery.showLast\ndump controlled-snapshot\nanswer "
                + quote(UiText.get("button.ok")) + "\n";
        Path script = output.resolve("controlled-snapshot.cps"); Files.writeString(script, text);
        var parsed = SelfTestScript.parse("controlled-snapshot", text);
        var target = ClientJarSnapshot.copy(client.equals("fx") ? ClientTarget.fx(layout) : ClientTarget.swing(layout), output);
        var request = new LaunchRequest(client, "controlled-snapshot", fixture.home, fixture.node,
                LaunchRequest.PARITY_TODAY, script.toString(), output.resolve("dumps"), List.of(), List.of());
        fixture.requireAlreadyRunning(); fixture.requireUnchanged();
        LaunchedClient launched = ClientLauncher.launch(target, request);
        try (launched) {
            Path log = request.selftestOut().resolve("selftest.log");
            launched.waitUntil(() -> {
                try { return Files.readString(log).endsWith("SELFTEST DONE\n"); }
                catch (java.io.IOException e) { return false; }
            }, Duration.ofSeconds(40), "controlled snapshot DONE");
            var lines = Files.readAllLines(log); assertEquals(parsed.lines().size() + 1, lines.size());
            for (int i = 0; i < parsed.lines().size(); i++) {
                var line = parsed.lines().get(i);
                assertEquals("SELFTEST " + line.number() + " OK " + line.text(), lines.get(i));
            }
            Path dumps = request.selftestOut().resolve("controlled-snapshot");
            var startup = DumpTrees.read(Files.readString(dumps.resolve("already-running.json")));
            assertEquals("alreadyRunning", startup.alerts().getFirst().purpose());
            var disabled = DumpTrees.read(Files.readString(dumps.resolve("recording-disabled.json")));
            assertTrue(disabled.status().stream().anyMatch(s -> s.id().equals("session")
                    && s.text().equals(UiText.get("status.session.disabled"))));
            var observed = DumpTrees.read(Files.readString(dumps.resolve("controlled-snapshot.json")));
            assertEquals(client, observed.client());
            var alert = observed.alerts().getFirst(); assertEquals("lastSnapshot", alert.purpose()); assertTrue(alert.detailsExpanded());
            NativeAllowanceReferenceTest.requirePreservedNode(alert.details(), client);
            var registry = fixture.environment.registryStore(client);
            var xml = fixture.environment.xmlStore(client);
            String registryPayload = fixture.snapshot == null ? UiText.get("s2.recovery.noSnapshot")
                    : JsonWriter.writePretty(new JsonSnapshotCodec().toJsonObject(registry.load().orElseThrow()));
            // Оракул строится из real store inputs; ни наблюдение, ни RAW body не переписываются для сравнения.
            String expected = UiText.get("s2.recovery.registryBlock", registry.title(), registry.nodePath().replace('/', '\\'), registryPayload)
                    + "\n\n" + UiText.get("s2.recovery.fileBlock", xml.title(), xml.file(), Files.readString(xml.file(), StandardCharsets.UTF_8));
            assertEquals(DumpNormalizer.normalizeText(expected, fixture.environment.cashMemory(), fixture.node), alert.details());
            if (fixture.snapshot == null) assertTrue(alert.content().contains(UiText.get("s2.recovery.absent")));
            else assertFalse(alert.content().contains(UiText.get("s2.recovery.absent")));
            fixture.requireUnchanged();
            Files.writeString(output.resolve("reference-context.json"), UiJson.write(Map.of("client", client,
                    "cashMemory", fixture.environment.cashMemory(), "node", fixture.node, "dumps", dumps,
                    "role", fixture.snapshot == null ? "controlled-absent" : "controlled-identical-archive",
                    "archiveClient", "fx", "recorder", "DISABLED_SECOND_INSTANCE")));
        } finally {
            // Shared home порождает одинаковые stdout paths; сохраняем каждый журнал до следующего клиента.
            Files.copy(launched.stdout(), output.resolve("client.stdout.log"));
            Files.copy(launched.stderr(), output.resolve("client.stderr.log"));
        }
        fixture.requireUnchanged();
    }

    /** Локализованный текст кнопки остаётся настоящим CPS answer. */
    private static String quote(String value) { return "\"" + value.replace("\"", "\\\"") + "\""; }
}
