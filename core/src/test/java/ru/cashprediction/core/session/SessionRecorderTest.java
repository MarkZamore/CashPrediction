package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.store.InMemoryRegistryBackend;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;

/**
 * Тесты рекордера с фейковыми планировщиком, часами и хранилищами: debounce, периодическая запись,
 * флаг closed, ожидание фоновой записи в saveNow, порядок shutdownClean, независимость хранилищ
 * и события статуса.
 */
class SessionRecorderTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final FakeStore registry = new FakeStore("registry", events);
    private final FakeStore xml = new FakeStore("xml", events);
    private final FakeScheduler scheduler = new FakeScheduler();
    private final MutableClock clock = new MutableClock(SessionFixtures.STARTED);
    private final FakeSource source = new FakeSource();
    private final List<StoreStatus> statuses = new CopyOnWriteArrayList<>();

    private SessionRecorder recorder(UiExecutor ui) {
        SessionRecorder recorder = new SessionRecorder("fx", List.of(registry, xml), ui, source, scheduler, clock, 4242);
        recorder.addStatusListener(statuses::add);
        return recorder;
    }

    private SessionRecorder recorder() {
        return recorder(UiExecutor.direct());
    }

    @Test
    void startWritesRunningMarkerToAllStores() {
        SessionRecorder recorder = recorder();
        recorder.start();
        recorder.start();
        assertEquals(List.of("registry:markDirty", "xml:markDirty"), events);
        assertEquals(SessionMarker.running(4242, SessionFixtures.STARTED, "fx"), registry.marker);
        assertEquals(registry.marker, xml.marker);
        assertEquals(registry.marker, recorder.marker().orElseThrow());
        assertEquals(2, statuses.size());
        assertTrue(statuses.stream().allMatch(StoreStatus::ok));
    }

    @Test
    void nothingIsWrittenBeforeStart() {
        SessionRecorder recorder = recorder();
        recorder.touch();
        scheduler.advance(Duration.ofSeconds(10));
        recorder.saveNow();
        assertTrue(events.isEmpty());
        assertTrue(recorder.lastCaptured().isEmpty());
    }

    @Test
    void touchesAreDebouncedAndCoalesced() {
        SessionRecorder recorder = recorder();
        recorder.start();
        recorder.touch();
        scheduler.advance(Duration.ofMillis(300));
        source.select("r1@2026-10-05");
        recorder.touch();
        scheduler.advance(Duration.ofMillis(300));
        source.select("r2@2026-10-01");
        recorder.touch();
        scheduler.advance(Duration.ofMillis(399));
        assertEquals(0, registry.saved.size(), "до истечения 400 мс после последнего touch записи нет");
        scheduler.advance(Duration.ofMillis(1));
        assertEquals(1, registry.saved.size());
        assertEquals(1, xml.saved.size());
        assertEquals("r2@2026-10-01", registry.saved.get(0).main().selectedRowId(), "пишется самое свежее состояние");
    }

    @Test
    void periodicTimerWritesOnlyChangedSnapshots() {
        SessionRecorder recorder = recorder();
        recorder.start();
        scheduler.advance(SessionRecorder.PERIOD);
        assertEquals(1, registry.saved.size());
        clock.advance(Duration.ofSeconds(5));
        scheduler.advance(SessionRecorder.PERIOD);
        assertEquals(1, registry.saved.size(), "неизменившийся снимок повторно не пишется");
        source.select("r7@2026-12-01");
        scheduler.advance(SessionRecorder.PERIOD);
        assertEquals(2, registry.saved.size());
        assertEquals(SessionFixtures.STARTED.plusSeconds(5), registry.saved.get(1).savedAt());
    }

    @Test
    void windowsAreCapturedInRegistrationOrder() {
        SessionRecorder recorder = recorder();
        recorder.start();
        assertEquals("w1", recorder.nextWindowId());
        assertEquals("w2", recorder.nextWindowId());
        FakeWindow second = new FakeWindow("w2", WindowType.ALERT, true, "w1");
        FakeWindow first = new FakeWindow("w1", WindowType.RULE_EDITOR, true, "main");
        first.fields.put("title", "Аренда");
        FakeWindow broken = new FakeWindow("w3", WindowType.CHOICE, true, "main");
        broken.failCapture = true;
        recorder.register(second);
        recorder.register(first);
        recorder.register(broken);
        recorder.saveNow();
        SessionSnapshot snapshot = registry.saved.get(0);
        assertEquals(List.of("w2", "w1"), snapshot.windows().stream().map(WindowState::id).toList(),
                "порядок регистрации; сломанное окно пропущено");
        assertEquals("Аренда", snapshot.windows().get(1).field("title"));
        recorder.unregister(second);
        recorder.saveNow();
        assertEquals(List.of("w1"), registry.saved.get(1).windows().stream().map(WindowState::id).toList());
        assertEquals(List.of(first, broken), recorder.registeredWindows());
    }

    @Test
    void closedFlagDropsLateBackgroundWrites() {
        scheduler.mode = FakeScheduler.Mode.QUEUE;
        SessionRecorder recorder = recorder();
        recorder.start();
        source.select("r1@2026-10-05");
        recorder.touch();
        scheduler.advance(SessionRecorder.DEBOUNCE);
        // Фоновая запись снята и стоит в очереди, но ещё не выполнена.
        source.select("r2@2026-10-01");
        recorder.shutdownClean();
        assertTrue(recorder.isClosed());
        assertEquals(1, registry.saved.size());
        scheduler.runQueued();
        recorder.touch();
        scheduler.advance(Duration.ofSeconds(30));
        recorder.saveNow();
        assertEquals(1, registry.saved.size(), "после shutdownClean записи отбрасываются");
        assertEquals("r2@2026-10-01", registry.snapshot.main().selectedRowId());
        assertEquals(0, scheduler.pendingCount(), "таймеры остановлены");
        assertTrue(scheduler.shutdown);
    }

    @Test
    void shutdownCleanSavesThenMarksClean() {
        SessionRecorder recorder = recorder();
        recorder.start();
        recorder.shutdownClean();
        recorder.shutdownClean();
        assertEquals(List.of("registry:markDirty", "xml:markDirty", "registry:save", "xml:save",
                "registry:markClean", "xml:markClean"), events);
        assertEquals(SessionMarker.CLOSED, registry.marker.state());
    }

    @Test
    void saveNowWaitsForInFlightBackgroundWrite() throws InterruptedException {
        scheduler.mode = FakeScheduler.Mode.THREAD;
        SessionRecorder recorder = recorder();
        recorder.start();
        CountDownLatch release = new CountDownLatch(1);
        registry.blockSave = release;
        source.select("r1@2026-10-05");
        recorder.touch();
        scheduler.advance(SessionRecorder.DEBOUNCE);
        assertTrue(registry.saveEntered.await(5, TimeUnit.SECONDS), "фоновая запись началась");

        source.select("r2@2026-10-01");
        Thread uiThread = new Thread(recorder::saveNow, "fake-ui");
        uiThread.start();
        uiThread.join(300);
        assertTrue(uiThread.isAlive(), "saveNow ждёт завершения фоновой записи");
        assertEquals(0, registry.saved.size());

        release.countDown();
        uiThread.join(5000);
        for (Thread thread : scheduler.threads) {
            thread.join(5000);
        }
        assertFalse(uiThread.isAlive());
        assertEquals(2, registry.saved.size());
        assertEquals("r1@2026-10-05", registry.saved.get(0).main().selectedRowId());
        assertEquals("r2@2026-10-01", registry.saved.get(1).main().selectedRowId(), "свежий снимок записан последним");
        assertEquals("r2@2026-10-01", xml.snapshot.main().selectedRowId());
    }

    @Test
    void failingStoreDoesNotBlockOtherStore() {
        SessionRecorder recorder = recorder();
        recorder.start();
        statuses.clear();
        registry.failSave = "Реестр Windows недоступен: тест";
        recorder.saveNow();
        assertEquals(0, registry.saved.size());
        assertEquals(1, xml.saved.size());
        assertEquals(2, statuses.size());
        StoreStatus failed = statuses.get(0);
        assertEquals("registry", failed.storeId());
        assertFalse(failed.ok());
        assertEquals("Реестр Windows недоступен: тест", failed.message());
        StoreStatus ok = statuses.get(1);
        assertEquals("xml", ok.storeId());
        assertTrue(ok.ok());
        assertEquals(SessionFixtures.STARTED, ok.savedAt());
        assertEquals(SessionFixtures.STARTED, recorder.lastSavedAt("xml").orElseThrow());
        assertTrue(recorder.lastSavedAt("registry").isEmpty());
    }

    @Test
    void unavailableStoreIsSkippedWithStatus() {
        registry.available = false;
        SessionRecorder recorder = recorder();
        recorder.start();
        recorder.saveNow();
        assertEquals(List.of("xml:markDirty", "xml:save"), events);
        assertTrue(statuses.stream().anyMatch(s -> s.storeId().equals("registry") && !s.ok()
                && s.message().contains("недоступно: выключено в тесте")));
    }

    @Test
    void disabledRecorderWritesNothing() {
        SessionRecorder recorder = recorder();
        recorder.setEnabled(false);
        recorder.start();
        recorder.touch();
        scheduler.advance(Duration.ofSeconds(20));
        recorder.saveNow();
        recorder.shutdownClean();
        assertTrue(events.isEmpty(), "второй экземпляр не трогает хранилища первого");
        assertFalse(recorder.isEnabled());
    }

    @Test
    void enablingLaterWritesMarker() {
        SessionRecorder recorder = recorder();
        recorder.setEnabled(false);
        recorder.start();
        recorder.setEnabled(true);
        assertEquals(List.of("registry:markDirty", "xml:markDirty"), events);
    }

    @Test
    void saveNowFromNonUiThreadWritesLastCapturedSnapshot() {
        UiExecutor notUi = new UiExecutor() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }

            @Override
            public boolean isUiThread() {
                return false;
            }
        };
        SessionRecorder recorder = recorder(notUi);
        recorder.start();
        recorder.saveNow();
        assertEquals(0, registry.saved.size(), "снимков ещё не снимали - писать нечего");
        source.select("r1@2026-10-05");
        recorder.touch();
        scheduler.advance(SessionRecorder.DEBOUNCE);
        source.select("r9@2027-01-01");
        recorder.saveNow();
        assertEquals(2, registry.saved.size());
        assertEquals("r1@2026-10-05", registry.saved.get(1).main().selectedRowId(),
                "из не-UI потока UI не опрашивается, пишется последний снятый снимок");
    }

    @Test
    void captureFailureIsReportedAndSaveNowFallsBack() {
        SessionRecorder recorder = recorder();
        recorder.start();
        recorder.saveNow();
        statuses.clear();
        source.fail = true;
        recorder.saveNow();
        assertEquals(2, registry.saved.size(), "при сломанном UI пишется предыдущий снимок");
        assertTrue(statuses.stream().anyMatch(s -> !s.ok() && s.message().startsWith("Не удалось снять состояние окон")));
    }

    @Test
    void listenerExceptionsDoNotBreakWrites() {
        SessionRecorder recorder = recorder();
        recorder.addStatusListener(s -> {
            throw new IllegalStateException("сломанная строка состояния");
        });
        recorder.start();
        recorder.saveNow();
        assertEquals(1, registry.saved.size());
        assertEquals(1, xml.saved.size());
    }

    // ------------------------------------------------------------------ регрессии

    /** Проба процессов, для которой процесс упавшего сеанса мёртв. */
    private static final CrashDetector.ProcessProbe DEAD_PROCESS = new CrashDetector.ProcessProbe() {
        @Override
        public long currentPid() {
            return 1;
        }

        @Override
        public boolean isAlive(long pid) {
            return false;
        }

        @Override
        public boolean sameExecutable(long pid) {
            return false;
        }

        @Override
        public Optional<Instant> startInstant(long pid) {
            return Optional.empty();
        }
    };

    /** «Очистить снимки»: хранилища очищаются, маркер текущего сеанса ставится заново, снимок пишется снова. */
    @Test
    void clearSnapshotsClearsStoresAndRestoresMarker() {
        SessionRecorder recorder = recorder();
        recorder.start();
        recorder.saveNow();
        events.clear();

        recorder.clearSnapshots();

        assertEquals(List.of("registry:clear", "xml:clear", "registry:markDirty", "xml:markDirty"), events);
        assertEquals(recorder.marker().orElseThrow(), registry.marker);
        assertEquals(recorder.marker().orElseThrow(), xml.marker);
        assertTrue(recorder.lastSavedAt("registry").isEmpty(), "снимка больше нет");
        scheduler.advance(SessionRecorder.DEBOUNCE);
        assertEquals(2, registry.saved.size(), "неизменившийся снимок записан в опустевшее хранилище");
        assertEquals(2, xml.saved.size());
    }

    /** Регрессия: очистка настоящих хранилищ посреди сеанса не выключает обнаружение сбоя до конца сеанса. */
    @Test
    void clearSnapshotsMidSessionKeepsCrashDetection(@TempDir Path dir) {
        RegistrySessionStore registryStore = new RegistrySessionStore(new InMemoryRegistryBackend(), "fx");
        XmlSessionStore xmlStore = XmlSessionStore.inCashMemory(dir, "fx");
        List<SessionStore> stores = List.of(registryStore, xmlStore);
        SessionRecorder recorder = new SessionRecorder("fx", stores, UiExecutor.direct(), source, scheduler, clock, 4242);
        recorder.start();
        recorder.saveNow();
        assertEquals(CrashDetector.Status.CRASHED, CrashDetector.detect(stores, "fx", DEAD_PROCESS).status());

        recorder.clearSnapshots();
        source.select("r1@2026-10-05");
        recorder.touch();
        scheduler.advance(SessionRecorder.DEBOUNCE);

        SessionMarker running = SessionMarker.running(4242, SessionFixtures.STARTED, "fx");
        assertEquals(Optional.of(running), registryStore.readMarker());
        assertEquals(Optional.of(running), xmlStore.readMarker());
        CrashDetector.Detection detection = CrashDetector.detect(stores, "fx", DEAD_PROCESS);
        assertEquals(CrashDetector.Status.CRASHED, detection.status(), "сбой после очистки по-прежнему обнаруживается");
        assertTrue(detection.stores().get(registryStore.id()).restorable());
        assertTrue(detection.stores().get(xmlStore.id()).restorable());
    }

    /** Второй экземпляр программы не очищает хранилища первого. */
    @Test
    void clearSnapshotsInDisabledRecorderTouchesNothing() {
        SessionRecorder recorder = recorder();
        recorder.setEnabled(false);
        recorder.start();
        recorder.clearSnapshots();
        assertTrue(events.isEmpty());
    }

    /**
     * Регрессия: снимок корректно закрытого прошлого сеанса заменяется при старте ДО маркера running,
     * чтобы сбой в первые секунды не выдал старый снимок за снимок сбоя.
     */
    @Test
    void startReplacesSnapshotLeftByCleanlyClosedSession() {
        registry.marker = SessionMarker.running(77, SessionFixtures.STARTED.minusSeconds(86_400), "fx").closed();
        registry.snapshot = SessionFixtures.simple("fx");
        SessionRecorder recorder = recorder();
        recorder.start();
        assertEquals(List.of("registry:save", "xml:save", "registry:markDirty", "xml:markDirty"), events);
        assertEquals(SessionFixtures.STARTED, registry.snapshot.savedAt());
        assertEquals(PlanState.CLEAN, registry.snapshot.plan(), "отброшенные при выходе правки не выдаются за снимок сбоя");
        assertEquals(SessionFixtures.STARTED, CrashDetector.detect(List.of(registry, xml), "fx", DEAD_PROCESS)
                .stores().get("registry").snapshotAt().orElseThrow());
    }

    /** Снимок сбоя (маркер running) при старте не трогается: из него, возможно, только что восстановились. */
    @Test
    void startKeepsSnapshotOfCrashedSession() {
        SessionSnapshot crash = SessionFixtures.simple("fx");
        registry.marker = SessionFixtures.running("fx");
        registry.snapshot = crash;
        SessionRecorder recorder = recorder();
        recorder.start();
        assertEquals(List.of("registry:markDirty", "xml:markDirty"), events);
        assertSame(crash, registry.snapshot);
    }

    /** Регрессия: {@link Error} при снятии состояния не отменяет запасной снимок и не выходит в поток планировщика. */
    @Test
    void errorDuringCaptureFallsBackAndDoesNotEscape() {
        SessionRecorder recorder = recorder();
        recorder.start();
        recorder.saveNow();
        statuses.clear();
        source.error = new OutOfMemoryError();
        recorder.saveNow();
        assertEquals(2, registry.saved.size(), "при OutOfMemoryError пишется предыдущий снимок");
        assertTrue(statuses.stream().anyMatch(s -> !s.ok() && s.message().equals("Не удалось снять состояние окон: OutOfMemoryError")),
                statuses.toString());

        source.error = null;
        recorder.register(new StatefulWindow() {
            @Override
            public String windowId() {
                return "w1";
            }

            @Override
            public WindowType windowType() {
                return WindowType.ALERT;
            }

            @Override
            public boolean modal() {
                return true;
            }

            @Override
            public String ownerId() {
                return WindowState.MAIN_OWNER;
            }

            @Override
            public WindowState captureState() {
                throw new AssertionError("окно сломано");
            }

            @Override
            public void applyState(WindowState state) {
            }
        });
        source.select("r5@2026-10-05");
        scheduler.advance(SessionRecorder.DEBOUNCE);
        assertEquals(3, registry.saved.size(), "окно с AssertionError пропущено, остальное записано");
        assertEquals(List.of(), registry.saved.get(2).windows());

        source.error = new AssertionError("главное окно");
        source.select("r6@2026-10-05");
        recorder.touch();
        scheduler.advance(SessionRecorder.DEBOUNCE);
        assertEquals(3, registry.saved.size(), "фоновая запись пропущена без исключения");
    }
}
