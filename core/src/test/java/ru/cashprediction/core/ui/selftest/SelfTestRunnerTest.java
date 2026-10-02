package ru.cashprediction.core.ui.selftest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.dump.UiDump;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки журнала и артефактов с драйвером, явно воспроизводящим сбои шагов. */
class SelfTestRunnerTest {
    @TempDir Path temporary;

    /** Драйвер с наблюдаемым порядком вызовов и намеренным отказом сохранения. */
    private static final class Driver implements UiDriver {
        final List<String> events = new ArrayList<>();
        /** Возвращает профиль самотеста. */
        @Override public ClientKind client() { return ClientKind.FX; }
        /** Исполняет шаг или воспроизводит отказ. */
        @Override public void execute(SelfTestCommand command) { events.add(command.getClass().getSimpleName()); if (command instanceof SelfTestCommand.Save) throw new IllegalStateException("write failed"); }
        /** Фиксирует ожидание событий. */
        @Override public void awaitIdle(Duration duration) { events.add("idle"); }
        /** Возвращает дамп с контролируемой строкой времени. */
        @Override public UiDump dump(String step) {
            events.add("dump"); return new UiDump(1, "fx", "", step,
                    new UiDump.Frame("", "os", null, 1200, 800,
                            Map.of("edge", new UiDump.Box(1113, 32, 87, 28))), List.of(), null, null, null, null,
                    List.of(new UiDump.Segment("session", "12:00:00", "", "text.muted", true)), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), Map.of(), Map.of());
        }
        /** Возвращает фиксированные байты снимка, не требуя графического клиента. */
        @Override public byte[] screenshot(String step) { events.add("shot"); return new byte[] {1, 2, 3}; }
    }

    @Test void continuesAfterFailureAndWritesNormalizedArtifactsAndJournal() throws Exception {
        Driver driver = new Driver();
        var report = new SelfTestRunner(driver, temporary).run(SelfTestScript.parse("test", "sample\nsave\ndump table\nshot screen\nfiltertype \"\""));
        assertFalse(report.ok()); assertEquals(5, report.steps().size());
        assertFalse(report.steps().get(1).ok()); assertTrue(report.steps().getLast().ok());
        String dump = Files.readString(temporary.resolve("test/table.json"));
        assertTrue(dump.contains("<time>")); assertTrue(dump.contains("\"scenario\":\"test\""));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(temporary.resolve("test/screen.png")));
        String measured = Files.readString(temporary.resolve("test/screen.raw.json"));
        assertTrue(measured.contains("12:00:00")); assertFalse(measured.contains("<time>"));
        assertTrue(measured.contains("\"scenario\":\"test\""));
        assertTrue(measured.contains("\"step\":\"screen\""));
        String log = Files.readString(temporary.resolve("selftest.log"));
        assertTrue(log.contains("SELFTEST 2 FAIL save: write failed")); assertTrue(log.endsWith("SELFTEST DONE\n"));
        assertTrue(driver.events.indexOf("idle") < driver.events.indexOf("dump"));
    }

    @Test void refusesOutputTraversalAndStillRunsLaterStep() throws Exception {
        Driver driver = new Driver();
        var report = new SelfTestRunner(driver, temporary).run(SelfTestScript.parse("safe", "dump ../escaped\nsample"));
        assertFalse(report.steps().getFirst().ok()); assertTrue(report.steps().getLast().ok());
        assertFalse(Files.exists(temporary.resolve("escaped.json")));
        assertThrows(IllegalArgumentException.class, () -> new SelfTestRunner(driver, temporary).run(SelfTestScript.parse("../escape", "sample")));
    }

    /** Округлённый эталон и точное наблюдение пикселей являются разными артефактами. */
    @Test void screenshotMeasurementsKeepOddEdgesWithoutChangingParityNormalization() throws Exception {
        Driver driver = new Driver();
        assertTrue(new SelfTestRunner(driver, temporary)
                .run(SelfTestScript.parse("edges", "dump edge\nshot edge")).ok());
        var raw = edge(Files.readString(temporary.resolve("edges/edge.raw.json")));
        var normalized = edge(Files.readString(temporary.resolve("edges/edge.json")));
        assertEquals(1113, ((Number) raw.get("x")).doubleValue());
        assertEquals(87, ((Number) raw.get("width")).doubleValue());
        assertEquals(1200, ((Number) raw.get("x")).doubleValue() + ((Number) raw.get("width")).doubleValue());
        assertEquals(1114, ((Number) normalized.get("x")).doubleValue());
        assertEquals(88, ((Number) normalized.get("width")).doubleValue());
        assertTrue(driver.events.lastIndexOf("dump") < driver.events.indexOf("shot"));
    }

    /** Читает проверяемую рамку из настоящего JSON раннера. */
    private static Map<?, ?> edge(String contents) {
        var root = ru.cashprediction.core.json.JsonParser.parseObject(contents);
        var frame = (Map<?, ?>) root.get("frame");
        return (Map<?, ?>) ((Map<?, ?>) frame.get("regions")).get("edge");
    }

    @Test void signalSynchronizesWithExternalHarness() throws Exception {
        Path signal = temporary.resolve("ready");
        var harness = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!Files.exists(signal)) { if (System.nanoTime() > deadline) throw new AssertionError("signal absent"); Thread.sleep(10); }
                Files.delete(signal);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue(new SelfTestRunner(new Driver(), temporary).run(SelfTestScript.parse("signal-test", "signal ready\nsample")).ok());
        harness.get(5, java.util.concurrent.TimeUnit.SECONDS); assertFalse(Files.exists(signal));
    }
}
