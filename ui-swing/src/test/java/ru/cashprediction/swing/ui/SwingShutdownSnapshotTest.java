package ru.cashprediction.swing.ui;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.XmlSessionStore;
import static org.junit.jupiter.api.Assertions.*;

/** Настоящий shutdown hook проверяется отдельной headless JVM с рекордером и XML, без Swing-окон. */
class SwingShutdownSnapshotTest {
    @TempDir Path temporary;

    /** Hook записывает последний захваченный снимок, но не более поздний незахваченный ввод. */
    @Test void externalExitWritesLastCaptureWithoutClosingSessionOrUsingUi() throws Exception {
        run("running");
        var store = XmlSessionStore.inCashMemory(temporary.resolve("CashMemory"), "swing");
        assertEquals("captured-new", store.load().orElseThrow().plan().markdown());
        assertTrue(store.readMarker().orElseThrow().isRunning());
        assertFalse(Files.exists(temporary.resolve("ui-used")));
        assertListenersUnchanged();
    }

    /** После явного чистого закрытия hook не переписывает XML и не возвращает маркер running. */
    @Test void cleanClosedSnapshotIsNotRewrittenByHook() throws Exception {
        run("clean");
        var store = XmlSessionStore.inCashMemory(temporary.resolve("CashMemory"), "swing");
        assertFalse(store.readMarker().orElseThrow().isRunning());
        assertArrayEquals(Files.readAllBytes(temporary.resolve("before.xml")), Files.readAllBytes(store.file()));
        assertFalse(Files.exists(temporary.resolve("ui-used")));
        assertListenersUnchanged();
    }

    /** Регистрация до установки рекордера безопасна и не создаёт снимок чужого сеанса. */
    @Test void exitBeforeRecorderInstallationDoesNotCreateSnapshot() throws Exception {
        run("absent");
        assertFalse(Files.exists(temporary.resolve("CashMemory/session-swing.xml")));
        assertFalse(Files.exists(temporary.resolve("ui-used")));
    }

    /** Независимая запись счётчика доказывает, что hook не рассылал даже не-UI уведомления. */
    private void assertListenersUnchanged() throws Exception {
        assertEquals(Files.readString(temporary.resolve("listener-count-before")),
                Files.readString(temporary.resolve("listener-count")));
    }

    /** Ограничивает ожидание только собственного дочернего процесса; времени IO hook контракт не обещает. */
    private void run(String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isRegularFile(java)) java = java.resolveSibling("java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path output = temporary.resolve("child.log");
        Process child = new ProcessBuilder(java.toString(), "-Djava.awt.headless=true", "-XX:-UsePerfData",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", classpath,
                Probe.class.getName(), temporary.toString(), mode).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Test fixture must terminate");
            assertEquals(0, child.exitValue(), Files.readString(output));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** Подменяет только доставку UI и таймеры; контроллер, рекордер, hook и XML настоящие. */
    public static final class Probe {
        private Probe() { }

        /** Готовит последний снимок и вызывает System.exit, который действительно запускает hook. */
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]); String mode = args[1];
            GateUi ui = new GateUi(root.resolve("ui-used")); DeferredScheduler scheduler = new DeferredScheduler();
            UiPort port = (UiPort) Proxy.newProxyInstance(UiPort.class.getClassLoader(), new Class<?>[]{UiPort.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "profile" -> ClientProfile.swing();
                        case "executor" -> ui;
                        case "scheduler" -> scheduler;
                        case "mainGeometry" -> MainGeometry.UNKNOWN;
                        default -> null;
                    });
            var environment = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry", "memory"));
            var app = new AppController(port, environment);
            SwingCoreMain.installShutdownHook(app);
            if (!mode.equals("absent")) {
                Files.createDirectories(root.resolve("CashMemory"));
                var store = XmlSessionStore.inCashMemory(root.resolve("CashMemory"), "swing");
                String[] raw = {"captured-old"};
                SnapshotSource source = new SnapshotSource() {
                    /** Источник запрещён после остановки UI. */
                    @Override public MainWindowState captureMain() { ui.requireUi(); return MainWindowState.empty(); }
                    /** Снимает исходное значение только на доступном UI-потоке. */
                    @Override public PlanState capturePlan() { ui.requireUi(); return PlanState.dirty(raw[0]); }
                };
                var recorder = new SessionRecorder("swing", List.of(store), ui, source, scheduler, Clock.systemUTC());
                app.installRecorder(recorder);
                var listenerCount = new java.util.concurrent.atomic.AtomicInteger();
                recorder.addStatusListener(status -> {
                    try { Files.writeString(root.resolve("listener-count"), Integer.toString(listenerCount.incrementAndGet())); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                });
                recorder.start(); recorder.saveNow();
                raw[0] = "captured-new"; recorder.touch(); scheduler.pending.run();
                if (!recorder.lastCaptured().orElseThrow().plan().markdown().equals("captured-new")) throw new IllegalStateException("Missing new capture");
                // Фоновая запись намеренно не исполняется: только hook сможет заменить старый файл.
                if (!store.load().orElseThrow().plan().markdown().equals("captured-old")) throw new IllegalStateException("Unexpected background write");
                if (mode.equals("clean")) { recorder.shutdownClean(); Files.copy(store.file(), root.resolve("before.xml")); }
                Files.copy(root.resolve("listener-count"), root.resolve("listener-count-before"));
                raw[0] = "raw-unflushed";
            }
            ui.active = false;
            System.exit(0);
        }
    }

    /** Исполнитель немедленно отвергает любую попытку обратиться к остановленному UI. */
    private static final class GateUi implements UiExecutor {
        private final Thread owner = Thread.currentThread();
        private final Path violation;
        private boolean active = true;
        private GateUi(Path violation) { this.violation = violation; }
        private void requireUi() {
            if (!isUiThread()) {
                try { Files.writeString(violation, "unexpected-ui-access"); }
                catch (Exception error) { throw new IllegalStateException(error); }
                throw new IllegalStateException("UI is stopped");
            }
        }
        /** Исполняет только до остановки фикстуры и только на её UI-потоке. */
        @Override public void execute(Runnable action) { requireUi(); action.run(); }
        /** Shutdown hook не должен даже спрашивать остановленный исполнитель о принадлежности к UI. */
        @Override public boolean isUiThread() {
            if (!active) {
                try { Files.writeString(violation, "unexpected-ui-thread-query"); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }
            return active && Thread.currentThread() == owner;
        }
    }

    /** Захват можно запустить отдельно, а запись остаётся отложенной до завершения JVM. */
    private static final class DeferredScheduler implements Scheduler {
        private Runnable pending;
        /** Сохраняет debounce-задачу, не исполняя её автоматически. */
        @Override public Task schedule(Runnable action, Duration delay) { pending = action; return () -> { }; }
        /** Периодические задачи в детерминированной фикстуре не исполняются. */
        @Override public Task scheduleAtFixedRate(Runnable action, Duration initial, Duration period) { return () -> { }; }
        /** Фоновая запись намеренно остаётся невыполненной. */
        @Override public void execute(Runnable action) { }
        /** Не запускает отложенные задачи при закрытии. */
        @Override public void shutdown() { }
    }
}
