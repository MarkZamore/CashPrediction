package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.selftest.SelfTestScript;

/** Проверяет только доставку сценария и результаты моста; синтетические тела не доказывают UI-паритет. */
class WebSelfTestBridgeTest {
    @TempDir Path out;
    @Test void physicalLineNumbersAndEveryResultAreWrittenInOrder() throws Exception {
        EffectLog log = new EffectLog();
        var script = SelfTestScript.parse("bridge", "# comment\nmenu file.new\n\ndump step\n");
        WebSelfTestBridge bridge = new WebSelfTestBridge(log, script, out, () -> java.time.LocalDate.of(2026, 9, 13));
        bridge.connected("one"); bridge.connected("two");
        assertEquals(1, effects(log, 0).size()); assertEquals(2, effects(log, 0).getFirst().get("n"));
        assertThrows(IllegalArgumentException.class, () -> bridge.receive("/api/test/result", Map.of("n", 1, "ok", true)));
        bridge.receive("/api/test/result", Map.of("n", 2, "ok", true));
        assertThrows(IllegalArgumentException.class, () -> bridge.receive("/api/test/result", Map.of("n", 2, "ok", true)));
        assertThrows(IllegalArgumentException.class, () -> bridge.receive("/api/test/result", Map.of("n", 4, "ok", true)));
        assertThrows(IllegalArgumentException.class, () -> bridge.receive("/api/test/dump", Map.of("n", 4,
                "dump", Map.of("client", "model", "schema", 1))));
        bridge.receive("/api/test/dump", Map.of("n", 4, "dump", Map.of("client", "web", "schema", 1)));
        bridge.receive("/api/test/result", Map.of("n", 4, "ok", true));
        assertTrue(Files.exists(out.resolve("bridge/step.json")));
        assertEquals("SELFTEST 2 OK menu file.new\nSELFTEST 4 OK dump step\nSELFTEST DONE\n", Files.readString(out.resolve("selftest.log")));
        bridge.completion().get(1, TimeUnit.SECONDS);
    }
    @Test void waitAndSignalRunOutsideControllerAndRemainInJournal() throws Exception {
        EffectLog log = new EffectLog();
        var script = SelfTestScript.parse("bridge", "wait 10\nsignal external-change\nmenu file.new\n");
        var bridge = new WebSelfTestBridge(log, script, out, () -> java.time.LocalDate.of(2026, 9, 13));
        bridge.connected("one");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(out.resolve("external-change"))) {
            if (System.nanoTime() >= deadline) fail("signal missing"); Thread.sleep(5);
        }
        Files.delete(out.resolve("external-change"));
        log.await(0, java.time.Duration.ofSeconds(3));
        assertEquals(3, effects(log, 0).getFirst().get("n"));
        bridge.receive("/api/test/result", Map.of("n", 3, "ok", false, "message", "unsupported"));
        assertEquals("SELFTEST 1 OK wait 10\nSELFTEST 2 OK signal external-change\nSELFTEST 3 FAIL menu file.new: unsupported\nSELFTEST DONE\n", Files.readString(out.resolve("selftest.log")));
    }
    @Test void unsolicitedResultIsRejectedWithoutScript() {
        var bridge = new WebSelfTestBridge(new EffectLog(), null, out, () -> java.time.LocalDate.of(2026, 9, 13));
        bridge.connected("one");
        assertThrows(IllegalArgumentException.class, () -> bridge.receive("/api/test/result", Map.of("n", 1, "ok", true)));
    }
    @Test void laterBootstrapCannotReplayPendingStepOrRestartScript() throws Exception {
        EffectLog log = new EffectLog();
        try (var bridge = new WebSelfTestBridge(log,
                SelfTestScript.parse("bridge", "today 2026-09-13\nsize 1200 800\nmenu file.sample\n"),
                out, () -> java.time.LocalDate.of(2026, 9, 13))) {
            bridge.connected("measured-page");
            long firstSeq = log.sequence();
            bridge.connected("measured-page");
            assertEquals(firstSeq, log.sequence(), "The same page does not receive duplicate steps");
            bridge.connected("replacement-page");
            assertEquals(firstSeq, log.sequence(), "A later page cannot replay the first page's step");
            assertTrue(effects(log, firstSeq).isEmpty());
            assertEquals(2, effects(log, 0).getFirst().get("n"));
            assertEquals("size 1200 800", effects(log, 0).getFirst().get("command"));
            assertEquals("SELFTEST 1 OK today 2026-09-13\n", Files.readString(out.resolve("selftest.log")));
            bridge.receive("/api/test/result", Map.of("n", 2, "ok", true));
            assertEquals(3, effects(log, firstSeq).getLast().get("n"));
            bridge.receive("/api/test/result", Map.of("n", 3, "ok", true));
            long doneSeq = log.sequence();
            bridge.connected("after-completion");
            assertEquals(doneSeq, log.sequence());
            assertEquals(1, Files.readString(out.resolve("selftest.log")).lines()
                    .filter(line -> line.equals("SELFTEST 2 OK size 1200 800")).count());
        }
    }
    @Test void todayValidatesServerClockWithoutPublishingDomStepAndMismatchFails() throws Exception {
        var date = java.time.LocalDate.of(2026, 9, 13);
        var script = SelfTestScript.parse("bridge", "today 2026-09-13\nmenu file.new\n");
        EffectLog log = new EffectLog();
        try (var bridge = new WebSelfTestBridge(log, script, out, () -> date)) {
            bridge.connected("one");
            assertEquals(1, effects(log, 0).size());
            assertEquals(2, effects(log, 0).getFirst().get("n"));
            assertEquals("menu file.new", effects(log, 0).getFirst().get("command"));
            assertEquals("SELFTEST 1 OK today 2026-09-13\n", Files.readString(out.resolve("selftest.log")));
        }
        EffectLog wrongLog = new EffectLog();
        try (var bridge = new WebSelfTestBridge(wrongLog, script, out, () -> date.plusDays(1))) {
            var error = assertThrows(IllegalStateException.class, () -> bridge.connected("one"));
            assertTrue(error.getMessage().contains("2026-09-14"));
            assertTrue(bridge.completion().isCompletedExceptionally());
            assertTrue(effects(wrongLog, 0).isEmpty());
            assertTrue(Files.readString(out.resolve("selftest.log")).startsWith("SELFTEST 1 FAIL today 2026-09-13:"));
        }
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> effects(EffectLog log, long after) {
        return (List<Map<String, Object>>) log.after(after).get("effects");
    }
}
