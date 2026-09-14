package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Тесты обнаружения сбоя с фейковой пробой процессов.
 */
class CrashDetectorTest {

    /** Проба с настраиваемыми ответами. */
    private static final class FakeProbe implements CrashDetector.ProcessProbe {
        long current = 1000;
        boolean alive;
        boolean same;
        /** Момент запуска живого процесса; {@code null} — неизвестен. По умолчанию чуть раньше начала сеанса. */
        Instant started = SessionFixtures.STARTED.minusSeconds(3);

        @Override
        public Optional<Instant> startInstant(long pid) {
            return Optional.ofNullable(started);
        }

        @Override
        public long currentPid() {
            return current;
        }

        @Override
        public boolean isAlive(long pid) {
            return alive;
        }

        @Override
        public boolean sameExecutable(long pid) {
            return same;
        }
    }

    private final FakeStore registry = new FakeStore("registry");
    private final FakeStore xml = new FakeStore("xml");
    private final FakeProbe probe = new FakeProbe();

    private CrashDetector.Detection detect() {
        return CrashDetector.detect(List.of(registry, xml), "fx", probe);
    }

    @Test
    void noMarkersMeansCleanStart() {
        CrashDetector.Detection detection = detect();
        assertEquals(CrashDetector.Status.CLEAN_START, detection.status());
        assertNull(detection.marker());
        assertEquals(List.of("registry", "xml"), List.copyOf(detection.stores().keySet()));
    }

    @Test
    void closedMarkerMeansCleanStart() {
        registry.marker = SessionFixtures.running("fx").closed();
        CrashDetector.Detection detection = detect();
        assertEquals(CrashDetector.Status.CLEAN_START, detection.status());
        assertEquals(registry.marker, detection.findMarker().orElseThrow());
    }

    @Test
    void runningMarkerOfDeadProcessMeansCrash() {
        SessionMarker marker = SessionFixtures.running("fx");
        registry.marker = marker;
        xml.marker = marker;
        registry.snapshot = SessionFixtures.simple("fx");
        xml.failLoad = "XML-файл сессии повреждён (строка 3, столбец 7): ...";
        CrashDetector.Detection detection = detect();
        assertEquals(CrashDetector.Status.CRASHED, detection.status());
        assertEquals(marker, detection.marker());
        CrashDetector.StoreInfo registryInfo = detection.stores().get("registry");
        assertTrue(registryInfo.available());
        assertEquals(Optional.of(SessionFixtures.SAVED), registryInfo.snapshotAt());
        assertTrue(registryInfo.restorable());
        CrashDetector.StoreInfo xmlInfo = detection.stores().get("xml");
        assertFalse(xmlInfo.restorable());
        assertEquals("XML-файл сессии повреждён (строка 3, столбец 7): ...", xmlInfo.problem());
        assertTrue(detection.anyRestorable());
    }

    @Test
    void crashWithoutSnapshotsIsNotRestorable() {
        registry.marker = SessionFixtures.running("fx");
        xml.available = false;
        CrashDetector.Detection detection = detect();
        assertEquals(CrashDetector.Status.CRASHED, detection.status());
        assertEquals("Снимок не найден", detection.stores().get("registry").problem());
        assertFalse(detection.stores().get("xml").available());
        assertTrue(detection.stores().get("xml").problem().contains("недоступно"));
        assertFalse(detection.anyRestorable());
    }

    @Test
    void aliveSameExecutableOtherPidMeansAlreadyRunning() {
        registry.marker = SessionFixtures.running("fx");
        probe.alive = true;
        probe.same = true;
        assertEquals(CrashDetector.Status.ALREADY_RUNNING, detect().status());
    }

    /** Регрессия: pid упавшего сеанса достался процессу того же java.exe, запущенному позже начала сеанса. */
    @Test
    void aliveSameExecutableStartedAfterMarkerMeansCrash() {
        registry.marker = SessionFixtures.running("fx");
        registry.snapshot = SessionFixtures.simple("fx");
        probe.alive = true;
        probe.same = true;
        probe.started = SessionFixtures.STARTED.plus(java.time.Duration.ofDays(1));
        CrashDetector.Detection detection = detect();
        assertEquals(CrashDetector.Status.CRASHED, detection.status(), "pid использован повторно");
        assertTrue(detection.anyRestorable());
        probe.started = null;
        assertEquals(CrashDetector.Status.CRASHED, detect().status(), "момент запуска неизвестен - не тот экземпляр");
        probe.started = SessionFixtures.STARTED.plus(CrashDetector.START_TOLERANCE);
        assertEquals(CrashDetector.Status.ALREADY_RUNNING, detect().status(), "в пределах допуска");
        probe.started = SessionFixtures.STARTED.plus(CrashDetector.START_TOLERANCE).plusMillis(1);
        assertEquals(CrashDetector.Status.CRASHED, detect().status());
    }

    @Test
    void aliveButDifferentExecutableMeansCrash() {
        registry.marker = SessionFixtures.running("fx");
        probe.alive = true;
        probe.same = false;
        assertEquals(CrashDetector.Status.CRASHED, detect().status(), "pid достался другой программе после перезагрузки");
    }

    @Test
    void ownPidMeansCrash() {
        registry.marker = SessionFixtures.running("fx");
        probe.current = 12345;
        probe.alive = true;
        probe.same = true;
        assertEquals(CrashDetector.Status.CRASHED, detect().status());
    }

    @Test
    void markersOfOtherClientsAreIgnored() {
        registry.marker = SessionFixtures.running("swing");
        assertEquals(CrashDetector.Status.CLEAN_START, detect().status());
    }

    @Test
    void closedInOneStoreWinsOverRunningOfSameSession() {
        SessionMarker marker = SessionFixtures.running("fx");
        registry.marker = marker;
        xml.marker = marker.closed();
        assertEquals(CrashDetector.Status.CLEAN_START, detect().status());
        // Но более новый running-сеанс — это уже сбой.
        registry.marker = SessionMarker.running(777, Instant.parse("2026-09-14T08:00:00Z"), "fx");
        CrashDetector.Detection detection = detect();
        assertEquals(CrashDetector.Status.CRASHED, detection.status());
        assertEquals(777, detection.marker().pid());
    }

    @Test
    void systemProbeRecognizesCurrentProcess() {
        CrashDetector.ProcessProbe system = CrashDetector.ProcessProbe.system();
        long pid = ProcessHandle.current().pid();
        assertEquals(pid, system.currentPid());
        assertTrue(system.isAlive(pid));
        if (ProcessHandle.current().info().command().isPresent()) {
            assertTrue(system.sameExecutable(pid));
        }
        assertFalse(system.isAlive(Long.MAX_VALUE));
        assertFalse(system.sameExecutable(Long.MAX_VALUE));
        assertEquals(ProcessHandle.current().info().startInstant(), system.startInstant(pid));
        assertEquals(Optional.empty(), system.startInstant(Long.MAX_VALUE));
    }
}
