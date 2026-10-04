package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.view.MainScreenModel;

/** Использует настоящий Java Web-port/thread без HTTP, browser, GUI или старта приложения. */
class WebBootstrapModelOwnershipTest {
    @TempDir Path home;

    /** Предстартовый placeholder не публикуется, а после showMain bootstrap сохраняет именно показанную модель. */
    @Test void pendingBootstrapUsesCoreButPublishedScreenIsNotRebuilt() throws Exception {
        var clock = AppClock.of(Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC), null);
        var environment = new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                home, home.resolve("CashMemory"), clock);
        try (var thread = new ControllerThread()) {
            var effects = new EffectLog();
            var port = new WebUiPort(thread, effects, false);
            var controller = new AppController(port, environment);
            // Проверяем настоящую границу потока, а не поддельную готовность native UI.
            assertThrows(IllegalStateException.class, controller::bootstrapPlaceholder);
            assertThrows(IllegalStateException.class, port::bootstrap);
            var ready = new java.util.concurrent.atomic.AtomicInteger();
            port.onMainReady(ready::incrementAndGet);
            thread.submit(() -> {
                port.bind(controller);
                var before = controller.state();
                var pending = port.bootstrap();
                assertEquals("RECOVERY_PENDING", pending.overlay());
                assertEquals(UiJson.write(controller.bootstrapPlaceholder()), UiJson.write(pending.screen()));
                assertEquals(0, pending.seq());
                assertNull(port.screen());
                assertEquals(0, ready.get());
                assertEquals(before, controller.state());
                var base = pending.screen();
                var published = new MainScreenModel(42, base.windowTitle(), base.menuBar(), base.toolbar(),
                        base.summary(), base.table(), base.chart(), base.status(), base.mode());
                port.showMain(published, null);
                assertEquals(1, ready.get());
                var shown = port.bootstrap();
                assertSame(published, shown.screen());
                assertNull(shown.overlay());
                assertEquals(42, shown.screen().revision());
                assertEquals(shown.seq(), port.bootstrap().seq());
                assertSame(published, port.bootstrap().screen());
                assertEquals(1, ready.get());
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
        assertFalse(Files.exists(environment.cashMemory()));
    }
}
