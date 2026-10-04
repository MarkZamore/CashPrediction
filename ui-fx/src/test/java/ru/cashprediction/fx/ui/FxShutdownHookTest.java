package ru.cashprediction.fx.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.XmlSessionStore;

/** Настоящий shutdown hook проверяется дочерней JVM и XML-хранилищем без toolkit и окон. */
public class FxShutdownHookTest {
    @TempDir Path temporary;

    /** Hook пишет снятое на UI состояние, сохраняя running и тишину слушателей статуса. */
    @Test void shutdownWritesOnlyLastCapturedStateAndKeepsRunningMarker() throws Exception {
        Path file = runChild("dirty");
        var store = new XmlSessionStore(file, "fx");
        assertTrue(store.readMarker().orElseThrow().isRunning());
        assertEquals("captured-raw", store.load().orElseThrow().main().filterText());
        assertEquals("captured-plan", store.load().orElseThrow().plan().markdown());
        assertQuietShutdown("dirty");
    }

    /** Чистое закрытие запрещает повторную запись и уведомления из shutdown-потока. */
    @Test void alreadyCleanRecorderDoesNotRewriteSnapshotOrDirtyMarker() throws Exception {
        Path file = runChild("clean");
        assertArrayEquals(Files.readAllBytes(file.resolveSibling("clean-before.xml")), Files.readAllBytes(file));
        assertFalse(new XmlSessionStore(file, "fx").readMarker().orElseThrow().isRunning());
        assertQuietShutdown("clean");
    }

    /** Ранняя регистрация hook безопасна до установки рекордера. */
    @Test void missingRecorderIsSafeUntilItIsInstalled() throws Exception {
        assertFalse(Files.exists(runChild("missing")));
    }

    /** Файловые свидетельства сохраняются даже при перехваченном исключении внутри рекордера. */
    private void assertQuietShutdown(String mode) throws Exception {
        assertFalse(Files.exists(temporary.resolve(mode + "-ui-access")), "hook не должен запрашивать UI");
        assertFalse(Files.exists(temporary.resolve(mode + "-listener-off-ui")),
                "слушатель статуса не должен вызываться вне UI при завершении");
        String before = Files.readString(temporary.resolve(mode + "-listener-count-before"));
        assertTrue(Integer.parseInt(before) > 0, "слушатель должен наблюдать обычное сохранение");
        assertEquals(before, Files.readString(temporary.resolve(mode + "-listener-count")),
                "тихий shutdown не должен вызывать слушателей статуса");
    }

    /** Запускает helper с ограниченным ожиданием; все данные остаются в папке теста. */
    private Path runChild(String mode) throws Exception {
        Path file = temporary.resolve(mode + ".xml");
        Path log = temporary.resolve(mode + ".log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isRegularFile(java)) java = java.resolveSibling("java");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(java.toString(), "-XX:-UsePerfData", "-cp", cp,
                Probe.class.getName(), file.toString(), mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "helper не должен зависнуть");
            assertEquals(0, child.exitValue(), Files.readString(log));
            return file;
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** Дочерний процесс использует настоящий рекордер, но управляемый UI и таймер без потоков. */
    public static final class Probe {
        private Probe() { }

        /** Снимает состояние в тестовом UI-потоке, затем завершает JVM с установленным боевым hook. */
        public static void main(String[] args) throws Exception {
            Path file = Path.of(args[0]);
            String mode = args[1];
            AtomicReference<SessionRecorder> current = new AtomicReference<>();
            FxShutdownHook.install(() -> {
                SessionRecorder recorder = current.get();
                if (recorder != null) recorder.saveShutdownSnapshot();
            });
            if (mode.equals("missing")) return;
            Thread uiThread = Thread.currentThread();
            AtomicReference<String> raw = new AtomicReference<>("captured-raw");
            UiExecutor ui = new UiExecutor() {
                /** Любой запрос из shutdown-потока оставляет свидетельство обращения к UI. */
                @Override public boolean isUiThread() {
                    if (Thread.currentThread() != uiThread) {
                        recordUiAccess();
                        return false;
                    }
                    return true;
                }
                /** Очередь запрещена: фикстура снимает состояние непосредственно на UI. */
                @Override public void execute(Runnable task) {
                    recordUiAccess();
                    throw new AssertionError("UI queue forbidden");
                }
                /** Записывает нарушение до исключения, которое рекордер может перехватить. */
                private void recordUiAccess() {
                    try { Files.writeString(file.resolveSibling(mode + "-ui-access"), "unexpected"); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }
            };
            Scheduler scheduler = new Scheduler() {
                /** Таймеры не исполняются автоматически. */
                @Override public Task schedule(Runnable task, Duration delay) { return () -> { }; }
                /** Периодическая запись не конкурирует с проверяемым hook. */
                @Override public Task scheduleAtFixedRate(Runnable task, Duration first, Duration period) { return () -> { }; }
                /** Фоновая очередь запрещена в синхронной фикстуре. */
                @Override public void execute(Runnable task) { throw new AssertionError("Background queue forbidden"); }
                /** Остановка не запускает отложенных задач. */
                @Override public void shutdown() { }
            };
            SnapshotSource source = new SnapshotSource() {
                /** Состояние окна снимается только в UI-потоке. */
                @Override public MainWindowState captureMain() {
                    if (!ui.isUiThread()) throw new AssertionError("Capture outside UI");
                    return new MainWindowState(null, false, "table", "", "all", Map.of(), raw.get(), "");
                }
                /** Состояние плана подчиняется той же проверке потока, что и состояние окна. */
                @Override public PlanState capturePlan() {
                    if (!ui.isUiThread()) throw new AssertionError("Capture outside UI");
                    return PlanState.dirty("captured-plan");
                }
            };
            SessionRecorder recorder = new SessionRecorder("fx", List.of(new XmlSessionStore(file, "fx")),
                    ui, source, scheduler, Clock.systemUTC());
            current.set(recorder);
            AtomicInteger listenerCount = new AtomicInteger();
            recorder.addStatusListener(status -> {
                // Сначала фиксируем вызов: исключения слушателей рекордер намеренно перехватывает.
                try {
                    Files.writeString(file.resolveSibling(mode + "-listener-count"),
                            Integer.toString(listenerCount.incrementAndGet()));
                    if (Thread.currentThread() != uiThread) {
                        Files.writeString(file.resolveSibling(mode + "-listener-off-ui"), "unexpected");
                    }
                } catch (Exception e) { throw new IllegalStateException(e); }
            });
            recorder.start();
            recorder.saveNow();
            if (!Files.exists(file)) throw new AssertionError("Initial snapshot absent");
            if (!recorder.lastCaptured().orElseThrow().main().filterText().equals("captured-raw")) {
                throw new AssertionError("Initial UI capture absent");
            }
            raw.set("unflushed-raw");
            if (mode.equals("clean")) {
                recorder.shutdownClean();
                Files.copy(file, file.resolveSibling("clean-before.xml"));
            } else {
                // Отсутствие файла доказывает, что запись ниже выполнит именно shutdown hook, а не первая saveNow.
                Files.delete(file);
            }
            // Контрольная копия учитывает обычные уведомления, включая явное чистое закрытие.
            Files.copy(file.resolveSibling(mode + "-listener-count"),
                    file.resolveSibling(mode + "-listener-count-before"));
        }
    }
}
