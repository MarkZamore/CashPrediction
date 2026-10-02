package ru.cashprediction.core.app.controller.clienterror;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.app.flow.SessionBridge;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет отдельную ошибку Web-страницы через настоящий контроллер и изолированный снимок сеанса. */
class ClientErrorIntegrationTest {
    @TempDir Path home;

    @Test void continuePreservesSnapshotRawDetailsAndResetsCoalescing() throws Exception {
        Fixture f = new Fixture(home);
        f.app.document().replace(f.app.document().plan(), null, true, List.of());
        String message = "TypeError: missing value", stack = "render.js:12\n  at paint (render.js:9)\n";
        f.app.clientError(message, stack);
        var alert = f.port.pendingAlerts().getLast();
        assertEquals(UiText.get("alert.uncaught.title"), alert.spec().windowTitle());
        assertEquals(UiText.get("s2.recovery.uncaughtHeader"), alert.spec().header());
        assertEquals(message, alert.spec().content());
        assertEquals(stack, alert.spec().details());
        assertFalse(alert.spec().detailsExpanded());
        assertEquals(List.of("reloadPage", "continueWork"), alert.spec().buttons().stream().map(b -> b.id()).toList());
        var snapshot = f.store.load().orElseThrow();
        assertTrue(snapshot.plan().dirty());
        assertTrue(f.store.readMarker().orElseThrow().isRunning());
        f.app.clientError("repeat", "repeat stack");
        assertEquals(1, f.port.alerts().size());
        assertEquals(snapshot, f.store.load().orElseThrow());
        alert.press("continueWork");
        assertTrue(f.port.exitKind().isEmpty());
        assertTrue(f.port.calls("reloadPage").isEmpty());
        assertEquals(snapshot, f.store.load().orElseThrow());
        assertFalse(f.app.recorder().isClosed());
        f.app.clientError("next", "next stack");
        assertEquals(2, f.port.alerts().size());
        f.port.pendingAlerts().getLast().press("continueWork");
        assertTrue(f.port.exitKind().isEmpty());
    }

    @Test void reloadIsOnceAndDoesNotCloseServerOrPreventNextError() throws Exception {
        Fixture f = new Fixture(home);
        f.app.clientError("first", "raw stack");
        var alert = f.port.pendingAlerts().getLast();
        alert.press("reloadPage");
        alert.onButton().accept("reloadPage");
        assertEquals(1, f.port.calls("reloadPage").size());
        assertTrue(f.port.exitKind().isEmpty());
        assertFalse(f.app.recorder().isClosed());
        f.app.clientError("next", "next stack");
        f.port.pendingAlerts().getLast().press("reloadPage");
        assertEquals(2, f.port.calls("reloadPage").size());
        assertTrue(f.store.readMarker().orElseThrow().isRunning());
    }

    @Test void unrelatedJvmFailureStillHaltsAndLateClientErrorAndReloadAreIgnored() throws Exception {
        Fixture f = new Fixture(home);
        f.app.clientError("page", "page stack");
        var pageAlert = f.port.pendingAlerts().getLast();
        f.app.uncaught(Thread.currentThread(), new IllegalArgumentException("server"));
        var fatal = f.port.pendingAlerts().getLast();
        assertEquals(List.of("closeProgram"), fatal.spec().buttons().stream().map(b -> b.id()).toList());
        fatal.press("closeProgram");
        assertEquals(ExitKind.HALT, f.port.exitKind().orElseThrow());
        assertEquals(2, f.port.exitCode());
        var snapshot = f.store.load().orElseThrow();
        int calls = f.port.calls().size();
        f.app.clientError("late", "late stack");
        pageAlert.onButton().accept("reloadPage");
        f.app.port().reloadPage();
        assertEquals(calls, f.port.calls().size());
        assertEquals(snapshot, f.store.load().orElseThrow());
        assertTrue(f.store.readMarker().orElseThrow().isRunning());
    }

    @Test void desktopRejectsPageOnlyOperation() {
        FakeUiPort port = new FakeUiPort(ClientProfile.fx("25"));
        assertThrows(UnsupportedOperationException.class, port::reloadPage);
        var app = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                home, home.resolve("CashMemory"), AppClock.fixedToday(LocalDate.of(2026, 9, 13))));
        assertThrows(IllegalStateException.class, () -> app.clientError("page", "stack"));
        assertTrue(port.alerts().isEmpty());
    }

    /** Создаёт ядро и запись Web-сеанса без настоящего реестра и фоновых таймеров. */
    private static final class Fixture {
        final FakeUiPort port = new FakeUiPort(ClientProfile.web());
        final AppController app;
        final SessionStore store;
        Fixture(Path home) throws Exception {
            Path memory = home.resolve("CashMemory"); Files.createDirectories(memory);
            app = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                    home, memory, AppClock.fixedToday(LocalDate.of(2026, 9, 13))));
            app.showMain(null);
            store = app.environment().webStore();
            var recorder = new SessionRecorder("web", List.of(store), app.port().executor(), new SessionBridge(app),
                    app.port().scheduler(), app.environment().clock().clock());
            app.installRecorder(recorder); recorder.start();
        }
    }
}
