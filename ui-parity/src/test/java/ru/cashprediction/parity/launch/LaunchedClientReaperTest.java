package ru.cashprediction.parity.launch;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Воспроизводит задержку Java-reaper после уже наблюдаемого завершения собственного ОС-процесса. */
class LaunchedClientReaperTest {
    /** kill обязан дождаться Java Process, а не только исчезновения ProcessHandle. */
    @Test
    void cleanHandleReportAlsoSettlesProcessReaper() throws Exception {
        Process real = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version").start();
        try {
            assertTrue(real.waitFor(10, TimeUnit.SECONDS));
            var delayed = new DelayedReaper(real);
            assertFalse(delayed.toHandle().isAlive());
            assertTrue(delayed.isAlive(), "Fixture must reproduce the two distinct observations");
            var client = new LaunchedClient(null, null, List.of(), delayed, Path.of("unused.stdout"), Path.of("unused.stderr"));
            var report = client.kill();
            assertTrue(report.isClean());
            assertFalse(client.process().isAlive());
            assertTrue(delayed.waited);
            assertSame(report, client.kill());
        } finally {
            if (real.isAlive()) { real.destroyForcibly(); assertTrue(real.waitFor(10, TimeUnit.SECONDS)); }
        }
    }

    /** Удерживает только Java-наблюдение живым; handle относится к уже завершённому собственному процессу. */
    private static final class DelayedReaper extends Process {
        private final Process delegate;
        private boolean waited;
        private DelayedReaper(Process delegate) { this.delegate = delegate; }
        /** Возвращает поток реального дочернего процесса. */
        @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
        /** Возвращает поток реального дочернего процесса. */
        @Override public InputStream getInputStream() { return delegate.getInputStream(); }
        /** Возвращает поток ошибок реального дочернего процесса. */
        @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
        /** Подтверждает обработку завершения Java-обёрткой. */
        @Override public int waitFor() throws InterruptedException { int code = delegate.waitFor(); waited = true; return code; }
        /** Подтверждает завершение только после настоящего ограниченного ожидания. */
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            boolean exited = delegate.waitFor(timeout, unit); if (exited) waited = true; return exited;
        }
        /** Имитирует ещё не обработанное уведомление Java-reaper. */
        @Override public boolean isAlive() { return !waited; }
        /** Возвращает exit code после подтверждения завершения. */
        @Override public int exitValue() { if (!waited) throw new IllegalThreadStateException("reaper pending"); return delegate.exitValue(); }
        /** Завершает только принадлежащий тесту процесс. */
        @Override public void destroy() { delegate.destroy(); }
        /** Возвращает реальный PID собственного процесса. */
        @Override public long pid() { return delegate.pid(); }
        /** Возвращает реальный handle, уже сообщающий о завершении. */
        @Override public ProcessHandle toHandle() { return delegate.toHandle(); }
    }
}
