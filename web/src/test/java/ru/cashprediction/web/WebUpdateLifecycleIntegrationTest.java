package ru.cashprediction.web;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящие пути сервера и ядра с подменой только внешнего обновлятора. */
final class WebUpdateLifecycleIntegrationTest {
    @TempDir Path home;

    private AppEnvironment environment() {
        return AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(), "--registry", "memory",
                "--registry-node", "ru/cashprediction/selftest/" + UUID.randomUUID(),
                "--no-window", "--no-browser"), new Properties()));
    }

    private static WebUpdateSession session(List<String> events, boolean allowed) {
        return new WebUpdateSession(() -> {
            events.add("create");
            return new WebUpdateSession.Calls(() -> { events.add("before"); return allowed; },
                    () -> events.add("ready"), () -> events.add("close"));
        });
    }

    @Test void deferredLaunchDoesNotBindOrCreateApplicationFiles() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        // Занятый порт доказывает, что отказ барьера происходит раньше попытки bind.
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName(WebServer.HOST))) {
            assertThrows(WebUpdateSession.DeferredLaunch.class, () ->
                    WebServer.startCore(environment(), new ServerLog(false), occupied.getLocalPort(), true,
                            session(events, false)));
        }
        assertEquals(List.of("create", "before", "close"), events);
        assertFalse(Files.exists(home.resolve("CashMemory")));
    }

    @Test void bindFailureClosesLeaseWithoutPreparingOrWritingSession() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName(WebServer.HOST))) {
            assertThrows(java.net.BindException.class, () ->
                    WebServer.startCore(environment(), new ServerLog(false), occupied.getLocalPort(), true,
                            session(events, true)));
        }
        assertEquals(List.of("create", "before", "close"), events);
        assertFalse(Files.exists(home.resolve("CashMemory")));
    }

    @Test void noWindowServerIsReadyWithoutBrowserAndClosesBeforeExitListener() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        WebServer server = WebServer.startCore(environment(), new ServerLog(false), 0, true, session(events, true));
        try {
            assertTrue(server.port() > 0);
            assertNotNull(server.coreRuntime().thread().submit(() -> server.coreRuntime().port().screen())
                    .get(5, TimeUnit.SECONDS));
            assertEquals(List.of("create", "before", "ready"), events);
            server.addStopListener(() -> events.add("exit-listener"));
            server.stop();
            server.stop();
            server.closeUpdates();
            assertEquals(List.of("create", "before", "ready", "close", "exit-listener"), events);
        } finally { server.stop(); }
    }

    @Test void readyExceptionDoesNotFailRealServerAndCloseExceptionDoesNotSkipExitListener() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        var session = new WebUpdateSession(() -> new WebUpdateSession.Calls(
                () -> { events.add("before"); return true; },
                () -> { events.add("ready"); throw new IllegalStateException("ready"); },
                () -> { events.add("close"); throw new IllegalStateException("close"); }));
        WebServer server = WebServer.startCore(environment(), new ServerLog(false), 0, true, session);
        try {
            assertTrue(server.port() > 0);
            server.addStopListener(() -> events.add("exit-listener"));
            assertDoesNotThrow(server::stop);
            assertEquals(List.of("before", "ready", "close", "exit-listener"), events);
        } finally { server.stop(); }
    }
}
