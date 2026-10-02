package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет сроки подсказок и закрытие карточек реальным действием пользователя в эталоне. */
final class ModelHoverLifecycleTest {
    @TempDir Path root;

    /** Подсказка появляется через 600 мс и исчезает через 20 секунд после появления. */
    @Test void tooltipRespectsDelayAndDismiss() throws Exception {
        var driver = driver();
        try {
            driver.execute(new SelfTestCommand.Hover("menu:file.save"));
            driver.advance(Duration.ofMillis(599));
            assertTrue(driver.dump("before").popups().isEmpty());
            driver.advance(Duration.ofMillis(1));
            assertEquals("tooltip", driver.dump("shown").popups().getFirst().kind());
            driver.advance(Duration.ofMillis(19999));
            assertEquals(1, driver.dump("still").popups().size());
            driver.advance(Duration.ofMillis(1));
            assertTrue(driver.dump("dismissed").popups().isEmpty());
        } finally { driver.stopTimers(); }
    }

    /** Нажатие отменяет ожидающую подсказку и закрывает уже показанный спарклайн. */
    @Test void actionCancelsPendingTooltipAndVisibleSparkline() throws Exception {
        var driver = driver();
        try {
            driver.execute(new SelfTestCommand.Hover("menu:file.save"));
            driver.execute(new SelfTestCommand.Menu("view.table"));
            driver.advance(Duration.ofMillis(600));
            assertTrue(driver.dump("cancelled").popups().isEmpty());
            driver.execute(new SelfTestCommand.Hover("card:now"));
            assertEquals("sparkline", driver.dump("card").popups().getFirst().kind());
            driver.execute(new SelfTestCommand.Menu("view.chart"));
            assertTrue(driver.dump("clicked").popups().isEmpty());
        } finally { driver.stopTimers(); }
    }

    /** Изолированный контроллер с примером не меняет данные установленного приложения. */
    private ModelUiDriver driver() throws Exception {
        Path home = root.resolve(java.util.UUID.randomUUID().toString());
        var environment = AppEnvironment.from(LaunchOptions.parse("--home", home.toString(), "--registry", "memory", "--today", "2026-09-13"));
        var driver = new ModelUiDriver(environment, ClientProfile.fx("25"));
        var report = new SelfTestRunner(driver, home.resolve("out"))
                .run(SelfTestScript.parse("hover", "key Esc\nsample\n"));
        assertTrue(report.ok(), report.toString());
        return driver;
    }
}
