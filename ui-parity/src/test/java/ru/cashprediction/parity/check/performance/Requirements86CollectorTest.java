package ru.cashprediction.parity.check.performance;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cashprediction.parity.check.performance.DomainLatencyGate.*;

/** Headless mock transport проверяет новый collector, не physical/native acceptance. */
final class Requirements86CollectorTest {
    @TempDir Path root;
    private static final String SHA = "a".repeat(64);
    private static final List<Row> ROWS = List.of(new Row("r1", List.of("actual cell")));

    /** Собственные timestamps и immutable run связываются existing gate; mock scope сохраняется. */
    @Test void collectsAndWritesMockWithoutPromotion() throws Exception {
        try (var collector = new Requirements86Collector(contract())) {
            for (Client client : Client.values()) {
                var port = new Fake(client);
                collector.launch(client, () -> port);
                assertNotNull(collector.collect(client, new int[]{10}).requestedAt());
                port.input = "a"; port.revision = 12;
                collector.collect(client, new int[]{65});
            }
            collector.write(root.resolve("evidence"));
            String scope = Files.readString(root.resolve("evidence/collector.json"));
            assertTrue(scope.contains("HEADLESS_MOCK_MECHANICS")); assertTrue(scope.contains("COLLECTED_NOT_ACCEPTED"));
            assertTrue(Files.readString(root.resolve("evidence/input-transcript.json")).contains("requestedAt"));
            Run run = collector.finish();
            Requirements86Collector.verifyTransportIntegrity(root.resolve("evidence"), run, false);
            assertEquals("mock-not-native", assertThrows(AssertionError.class,
                    () -> Requirements86Collector.verifyEvidence(root.resolve("evidence"), run)).getMessage());
        }
    }

    /** Ни Sample, ни Stamp не принимаются API collector как импортированные timings. */
    @Test void inventedTimingImportHasNoEntryPoint() {
        assertTrue(Arrays.stream(Requirements86Collector.class.getMethods()).filter(method -> !java.lang.reflect.Modifier.isStatic(method.getModifiers())).noneMatch(method ->
                Arrays.stream(method.getParameterTypes()).anyMatch(type -> type == Sample.class || type == Stamp.class || type == Run.class)));
    }

    /** Подменённый timestamp в сохранённых raw observations не равен измеренному Run. */
    @Test void inventedPersistedTimingRejected() throws Exception {
        Run run = evidence(); Path frames = root.resolve("evidence/presented-frames.json");
        var list = new ArrayList<Object>(jsonList(frames)); var frame = new LinkedHashMap<>(object(list.getFirst()));
        frame.put("presentedAtUpperBoundNanos", 1); list.set(0, frame);
        Files.writeString(frames, ru.cashprediction.core.json.JsonWriter.write(list));
        assertEquals("invented-or-foreign-timing", assertThrows(AssertionError.class,
                () -> Requirements86Collector.verifyTransportIntegrity(root.resolve("evidence"), run, false)).getMessage());
    }

    /** Пропущенное timing или foreign frame SHA не принимаются вместе с зелёным Run. */
    @Test void missingPersistedTimingRejected() throws Exception {
        Run run = evidence(); Path inputs = root.resolve("evidence/input-transcript.json");
        Files.writeString(inputs, "[]");
        assertEquals("missing-timings", assertThrows(AssertionError.class,
                () -> Requirements86Collector.verifyTransportIntegrity(root.resolve("evidence"), run, false)).getMessage());
    }

    /** PNG pin проверяется независимо от sample timing. */
    @Test void foreignFrameShaRejected() throws Exception {
        Run run = evidence(); Path frames = root.resolve("evidence/presented-frames.json");
        var list = new ArrayList<Object>(jsonList(frames)); var frame = new LinkedHashMap<>(object(list.getFirst()));
        frame.put("pngSha256", "b".repeat(64)); list.set(0, frame);
        Files.writeString(frames, ru.cashprediction.core.json.JsonWriter.write(list));
        assertEquals("missing-presented-frame", assertThrows(AssertionError.class,
                () -> Requirements86Collector.verifyTransportIntegrity(root.resolve("evidence"), run, false)).getMessage());
    }

    private Run evidence() throws Exception {
        try (var collector = new Requirements86Collector(contract())) {
            for (Client client : Client.values()) {
                var port = new Fake(client); collector.launch(client, () -> port);
                collector.collect(client, new int[]{10}); port.input = "a"; port.revision = 12;
                collector.collect(client, new int[]{65});
            }
            collector.write(root.resolve("evidence")); return collector.finish();
        }
    }

    /** Missing client порт тоже отравляет сессию, а не оставляет возможность последующего зелёного finish. */
    @Test void missingClientPoisonsSession() throws Exception {
        try (var collector = new Requirements86Collector(contract())) {
            assertThrows(NullPointerException.class, () -> collector.collect(Client.FX, new int[]{10}));
            assertThrows(IllegalStateException.class, collector::finish);
        }
    }

    /** Последовательные desktop slots закрывают каждый owned порт ровно один раз, scope не теряется. */
    @Test void releaseKeepsEvidenceAndClosesOnce() throws Exception {
        var ports = new ArrayList<Fake>();
        try (var collector = new Requirements86Collector(contract())) {
            for (Client client : Client.values()) {
                var port = new Fake(client); ports.add(port); collector.launch(client, () -> port);
                collector.collect(client, new int[]{10}); port.input = "a"; port.revision = 12;
                collector.collect(client, new int[]{65}); collector.release(client); collector.release(client);
                assertEquals(1, port.closes);
            }
            collector.write(root.resolve("sequential"));
            assertTrue(Files.readString(root.resolve("sequential/collector.json")).contains("HEADLESS_MOCK_MECHANICS"));
        }
        ports.forEach(port -> assertEquals(1, port.closes));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private static List<?> jsonList(Path path) throws Exception { return (List<?>) ru.cashprediction.core.json.JsonParser.parse(Files.readString(path)); }

    /** Отсутствующая actual observation ломает сессию; нельзя подставить nanoTime вместо неё. */
    @Test void missingObservationPoisonsSessionAndCleans() throws Exception {
        var port = new Fake(Client.FX); port.missing = true;
        var collector = new Requirements86Collector(contract()); collector.launch(Client.FX, () -> port);
        assertThrows(NullPointerException.class, () -> collector.collect(Client.FX, new int[]{10}));
        assertThrows(IllegalStateException.class, collector::finish);
        collector.writeDiagnostic(root.resolve("failure"), new IllegalStateException("missing-observation"));
        assertTrue(Files.readString(root.resolve("failure/collector.json")).contains("FAILED_DIAGNOSTIC"));
        collector.close(); collector.close(); assertEquals(1, port.closes);
    }

    /** Чужой jar SHA отвергается до ввода и resource factory не оставляет живой порт. */
    @Test void foreignJarBeforeLaunchClosesOwnedPort() throws Exception {
        var port = new Fake(Client.FX); port.jar = "b".repeat(64);
        try (var collector = new Requirements86Collector(contract())) {
            assertEquals("foreign-sha", assertThrows(AssertionError.class, () -> collector.launch(Client.FX, () -> port)).getMessage());
            assertEquals(1, port.closes); assertEquals(0, port.gestures);
        }
    }

    /** Подмена jar после attach не допускается; попытка не повторяется до зелёного результата. */
    @Test void foreignJarAfterAttachPoisonsSession() throws Exception {
        var port = new Fake(Client.FX);
        try (var collector = new Requirements86Collector(contract())) {
            collector.launch(Client.FX, () -> port); port.jar = "b".repeat(64);
            assertThrows(AssertionError.class, () -> collector.collect(Client.FX, new int[]{10}));
            assertThrows(IllegalStateException.class, () -> collector.collect(Client.FX, new int[]{10}));
            assertEquals(0, port.gestures);
        }
    }

    /** Чужой fixture SHA не заменяется ожидаемым digest. */
    @Test void foreignFixtureRejected() throws Exception {
        var port = new Fake(Client.FX); port.fixture = "b".repeat(64);
        try (var collector = new Requirements86Collector(contract())) {
            collector.launch(Client.FX, () -> port);
            assertThrows(AssertionError.class, () -> collector.collect(Client.FX, new int[]{10}));
        }
    }

    /** Старый/нестабильный кадр повторяет observation, не ввод, сохраняя исходный request time. */
    @Test void pendingFrameDoesNotRepeatGesture() throws Exception {
        var port = new Fake(Client.FX); port.pending = true;
        try (var collector = new Requirements86Collector(contract())) {
            collector.launch(Client.FX, () -> port); collector.collect(Client.FX, new int[]{10});
            assertEquals(1, port.gestures); assertEquals(2, port.reads);
        }
    }

    /** Пустой gesture и неполная матрица не проходят, скрытый skipped sample невозможен. */
    @Test void emptyGestureAndMissingTimingsFail() throws Exception {
        try (var collector = new Requirements86Collector(contract())) {
            collector.launch(Client.FX, () -> new Fake(Client.FX));
            assertThrows(AssertionError.class, () -> collector.collect(Client.FX, new int[]{}));
        }
        try (var collector = new Requirements86Collector(contract())) {
            assertEquals("all-clients", assertThrows(AssertionError.class, collector::finish).getMessage());
        }
    }

    /** Missing PNG не получает PAINTED и cleanup запускается у всех портов несмотря на исключение. */
    @Test void missingFrameAndCleanupFailures() throws Exception {
        var collector = new Requirements86Collector(contract()); var ports = new ArrayList<Fake>();
        for (Client client : Client.values()) { var port = new Fake(client); ports.add(port); collector.launch(client, () -> port); }
        ports.getFirst().png = new byte[0];
        assertThrows(AssertionError.class, () -> collector.collect(Client.FX, new int[]{10}));
        ports.getFirst().cleanupFails = true; ports.getLast().cleanupFails = true;
        Exception failure = assertThrows(Exception.class, collector::close);
        assertEquals(1, failure.getSuppressed().length); ports.forEach(port -> assertEquals(1, port.closes));
        collector.close(); assertThrows(IllegalStateException.class, collector::finish);
    }

    /** Исключение OS-ввода сохраняет fail-closed lifecycle вместо неполного PASS. */
    @Test void inputFailureClosesOnce() throws Exception {
        var port = new Fake(Client.FX); port.gestureFails = true;
        try (var collector = new Requirements86Collector(contract())) {
            collector.launch(Client.FX, () -> port);
            assertThrows(Exception.class, () -> collector.collect(Client.FX, new int[]{10}));
            assertThrows(IllegalStateException.class, collector::finish);
        }
        assertEquals(1, port.closes);
    }

    private static Contract contract() {
        var jars = new EnumMap<Client, Map<String, String>>(Client.class);
        var events = new EnumMap<Client, List<Expected>>(Client.class);
        for (Client client : Client.values()) {
            jars.put(client, Map.of("core.jar", SHA));
            events.put(client, List.of(new Expected(1, "open", Operation.OPEN, "", 11, SHA, ROWS),
                    new Expected(2, "filter", Operation.FILTER, "a", 12, SHA, ROWS)));
        }
        return new Contract("mock-collector-run", Instant.now().minusSeconds(2), Duration.ofMinutes(2), jars, events);
    }

    /** Подменяет только транспорт; frame, SHA, ошибки и cleanup контролируются негативными fixtures. */
    private static final class Fake implements Requirements86Collector.Transport {
        final Client client; int closes, gestures; long revision = 11;
        String jar = SHA, fixture = SHA, input = ""; byte[] png;
        boolean missing, cleanupFails, gestureFails, pending; int reads;
        Fake(Client client) throws Exception {
            this.client = client; var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes); png = bytes.toByteArray();
        }
        /** Mock SHA manifest. */
        @Override public Map<String, String> jars() { return Map.of("core.jar", jar); }
        /** Mock physical transport, никогда не native. */
        @Override public void gesture(int[] keys) throws Exception { gestures++; if (gestureFails) throw new Exception("input failed"); }
        /** Mock widget data без supplied timings. */
        @Override public Requirements86Collector.Observation observe(String step) throws Exception {
            reads++; if (pending) { pending = false; throw new Requirements86Collector.PendingFrame("intermediate"); }
            return missing ? null : new Requirements86Collector.Observation(client, revision, fixture, input, ROWS, png, "{\"mock\":true}");
        }
        /** Считает снятие всех owned resources. */
        @Override public void close() throws Exception { closes++; if (cleanupFails) throw new Exception("cleanup failed " + client); }
    }
}
