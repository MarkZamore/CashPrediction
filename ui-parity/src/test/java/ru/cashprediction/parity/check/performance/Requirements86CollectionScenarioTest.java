package ru.cashprediction.parity.check.performance;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cashprediction.parity.check.performance.DomainLatencyGate.*;
import static ru.cashprediction.parity.check.performance.Requirements86CollectionScenario.*;

/** Только headless synthetic contract fixtures; ни OS-ввода, ни production/native acceptance. */
final class Requirements86CollectionScenarioTest {
    @TempDir Path root;
    private Inputs inputs; private Contract contract;

    private void fixture() throws Exception {
        Path jar = root.resolve("core.jar"), plan = root.resolve("plan.md"), keys = root.resolve("keys.json"), file = root.resolve("contract.json");
        Files.writeString(jar, "MOCK_JAR_NOT_PRODUCTION"); Files.writeString(plan, "MOCK_PLAN_NOT_PRODUCTION");
        Files.writeString(keys, "[{\"input\":\"a\"}]");
        var jars = new EnumMap<Client, Map<String, String>>(Client.class);
        var events = new EnumMap<Client, List<Expected>>(Client.class);
        var gestures = new ArrayList<Gesture>();
        for (Client client : Client.values()) {
            jars.put(client, Map.of("core.jar", sha(jar)));
            events.put(client, List.of(new Expected(1, "open", Operation.OPEN, "", 1, sha(plan), List.of()),
                    new Expected(2, "key-a", Operation.FILTER, "a", 2, sha(plan), List.of())));
            gestures.add(new Gesture(client, 1, List.of(10))); gestures.add(new Gesture(client, 2, List.of(65)));
        }
        contract = new Contract("SYNTHETIC_ONLY", Instant.now().minusSeconds(1), Duration.ofMinutes(2), jars, events);
        Files.writeString(file, ru.cashprediction.core.json.JsonWriter.write(Requirements86Acceptance.encode(contract)));
        inputs = new Inputs("a".repeat(40), List.of(new Pin(jar.toString(), sha(jar)), new Pin(plan.toString(), sha(plan)),
                new Pin(file.toString(), sha(file)), new Pin(keys.toString(), sha(keys))), file.toString(), keys.toString(), gestures);
    }

    private Inputs gestures(List<Gesture> list) {
        return new Inputs(inputs.sourceHeadSha(), inputs.pins(), inputs.contractPath(), inputs.keypressesPath(), list);
    }

    /** Полный независимый вход разрешает только contract сбор, а не native PASS. */
    @Test void fullSyntheticInputsAcceptedByValidatorOnly() throws Exception { fixture(); assertDoesNotThrow(() -> validate(inputs, contract)); }

    /** Missing renderer key является fatal даже при корректном DomainLatencyGate contract. */
    @Test void missingClientKeyFails() throws Exception {
        fixture(); assertEquals("missing-or-foreign-key", assertThrows(AssertionError.class,
                () -> validate(gestures(inputs.gestures().subList(0, 5)), contract)).getMessage());
    }

    /** Повтор gesture запрещён, чтобы не выбирать быстрый повтор вместо исходного. */
    @Test void duplicateKeyFails() throws Exception {
        fixture(); var keys = new ArrayList<>(inputs.gestures()); keys.add(keys.getFirst());
        assertEquals("invalid-or-duplicate-key", assertThrows(AssertionError.class, () -> validate(gestures(keys), contract)).getMessage());
    }

    /** Чужие input bytes не принимаются как свежие по одному git HEAD. */
    @Test void changedActualInputFails() throws Exception {
        fixture(); Files.writeString(root.resolve("core.jar"), "foreign bytes");
        assertEquals("foreign-input-sha", assertThrows(AssertionError.class, () -> validate(inputs, contract)).getMessage());
    }

    /** Контракт без pin не может подтянуть произвольные timings/expectations после начала. */
    @Test void missingContractPinFails() throws Exception {
        fixture(); var reduced = new Inputs(inputs.sourceHeadSha(), inputs.pins().stream().filter(pin -> !pin.path().equals(inputs.contractPath())).toList(),
                inputs.contractPath(), inputs.keypressesPath(), inputs.gestures());
        assertEquals("missing-contract-pin", assertThrows(AssertionError.class, () -> validate(reduced, contract)).getMessage());
    }

    /** Все независимые expected prefixes обязательны; сокращённая матрица не зелёная. */
    @Test void missingExpectedPrefixFails() throws Exception {
        fixture();
        var events = new EnumMap<Client, List<Expected>>(Client.class); events.putAll(contract.events());
        events.put(Client.WEB, events.get(Client.WEB).subList(0, 1));
        var shortened = new Contract(contract.runId(), contract.notBefore(), contract.maxAge(), contract.jars(), events);
        assertEquals("missing-ordinary-keys", assertThrows(AssertionError.class, () -> validate(inputs, shortened)).getMessage());
    }

    /** Factory exception закрывает также собственный partial launch; invalid inputs не открывают клиентов. */
    @Test void factoryFailureRetainsDiagnosticAndCleans() throws Exception {
        fixture(); int[] opened = {0}, cleaned = {0};
        var sessions = new Sessions() {
            public Session open(Client client, Path home) { opened[0]++; throw new IllegalStateException("owned-partial-launch"); }
            public void close() { cleaned[0]++; }
        };
        assertThrows(IllegalStateException.class, () -> run(inputs, contract, sessions, root.resolve("collection"), false));
        assertEquals(1, opened[0]); assertEquals(1, cleaned[0]);
        assertTrue(Files.readString(root.resolve("collection/failed-diagnostic/collector.json")).contains("FAILED_DIAGNOSTIC"));
        assertFalse(Files.exists(root.resolve("collection/evidence")));
    }

    /** Правильные старые rows с готовой revision не превращаются в invented быстрый paint. */
    @Test void oldCompletedFrameCannotAcknowledgeNewInput() throws Exception {
        fixture(); var expected = contract.events().get(Client.FX).getFirst();
        var before = new Requirements86Collector.Observation(Client.FX, expected.revision(), expected.fixtureDigest(), expected.input(),
                expected.rows(), new byte[0], "mock");
        assertEquals("already-completed-before-input", assertThrows(AssertionError.class,
                () -> Requirements86Collector.requireUncompletedInput(before, expected)).getMessage());
    }

    /** Пустой physical gesture не выполняет контракт по совпадению contents. */
    @Test void emptyKeysFatal() throws Exception {
        fixture(); var keys = new ArrayList<>(inputs.gestures()); keys.set(0, new Gesture(Client.FX, 1, List.of()));
        assertEquals("invalid-or-duplicate-key", assertThrows(AssertionError.class, () -> validate(gestures(keys), contract)).getMessage());
    }

    /** Даже pinned fixture/JAR недостаточно: production source/resource snapshot обязателен. */
    @Test void missingDirtySourcePinsFatal() throws Exception {
        fixture(); Path source = root.resolve("source"); Files.createDirectory(source);
        for (String module : List.of("core", "ui-fx", "ui-swing", "web")) Files.createDirectories(source.resolve(module + "/src/main"));
        Files.createDirectories(source.resolve("ui-parity/src/test/java/ru/cashprediction/parity/check/performance"));
        assertEquals("missing-production-source-pin", assertThrows(AssertionError.class,
                () -> requireSourcePins(source, inputs)).getMessage());
    }

    /** Полный synthetic сценарий пишет шесть timings и 15 UX cells, закрывает slots и не повышает scope. */
    @Test void completeSyntheticScenarioRemainsMock() throws Exception {
        fixture(); int[] opened = {0}, closed = {0}, factoryClosed = {0};
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        var sessions = new Sessions() {
            public Session open(Client client, Path home) {
                assertEquals(opened[0], closed[0], "Previous desktop slot must be closed"); opened[0]++;
                Expected[] target = {contract.events().get(client).getFirst()};
                var port = new Requirements86Collector.Transport() {
                    public Map<String, String> jars() { return contract.jars().get(client); }
                    public void gesture(int[] keys) { /* Synthetic transport; native acceptance запрещена. */ }
                    public Requirements86Collector.Observation observe(String step) {
                        return new Requirements86Collector.Observation(client, target[0].revision(), target[0].fixtureDigest(),
                                target[0].input(), target[0].rows(), bytes.toByteArray(), "{\"mock\":true}");
                    }
                    public void close() { closed[0]++; }
                };
                return new Session() {
                    public Requirements86Collector.Transport transport() { return port; }
                    public void prepare(Expected event) { target[0] = event; }
                    public String ux(String step) { return "SYNTHETIC_NOT_REVIEWED"; }
                };
            }
            public void close() { factoryClosed[0]++; }
        };
        Path output = root.resolve("synthetic-complete");
        run(inputs, contract, sessions, output, false);
        assertEquals(3, opened[0]); assertEquals(3, closed[0]); assertEquals(1, factoryClosed[0]);
        Path evidence = output.resolve("evidence");
        var run = Requirements86Acceptance.decode(Files.readString(evidence.resolve("run.json")), Run.class);
        assertEquals(6, run.samples().values().stream().mapToInt(List::size).sum());
        assertEquals(15, ((List<?>) ru.cashprediction.core.json.JsonParser.parse(Files.readString(evidence.resolve("ux-observations.json")))).size());
        Requirements86Collector.verifyTransportIntegrity(evidence, run, false);
        assertEquals("mock-not-native", assertThrows(AssertionError.class, () -> Requirements86Collector.verifyEvidence(evidence, run)).getMessage());
    }
}
