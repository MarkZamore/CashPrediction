package ru.cashprediction.parity.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.cashprediction.parity.launch.ReactorLayout;
import ru.cashprediction.parity.process.ProcessTree;

/**
 * Дымовой тест браузерной части стенда (стадия S0): headless Edge (или Chrome) открывает статическую страницу,
 * {@link CdpClient} выполняет {@code Runtime.evaluate} и {@code Page.navigate}, снимок {@code Page.captureScreenshot}
 * сохраняется в {@code target/parity/edge-smoke} в размере 1200×800, а после закрытия не остаётся ни одного
 * процесса браузера. Без установленного браузера тест пропускается с пояснением.
 */
class EdgeCdpSmokeIT {

    private static final int WIDTH = 1200;
    private static final int HEIGHT = 800;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void headlessBrowserLoadsStaticPageAndSavesA1200x800Screenshot() throws Exception {
        Optional<Path> browser = EdgeLauncher.findBrowser();
        Assumptions.assumeTrue(browser.isPresent(),
                () -> "EdgeCdpSmokeIT skipped: no msedge.exe or chrome.exe found; searched " + EdgeLauncher.searchedLocations());

        Path out = ReactorLayout.fromSystemProperties().parityRoot().resolve("edge-smoke");
        Files.createDirectories(out);
        String url = Path.of(EdgeCdpSmokeIT.class.getResource("/parity/smoke-page.html").toURI()).toUri().toString();
        Path profile = out.resolve("profile");
        ProcessTree.KillReport report;
        BrowserSession session = EdgeLauncher.startWithViewport(browser.get(), profile, WIDTH, HEIGHT, url,
                Duration.ofSeconds(60));
        try (session; CdpClient cdp = CdpClient.connectToFirstPage(session.port(), Duration.ofSeconds(30))) {
            cdp.waitFor("document.readyState === 'complete' && !!window.paritySmoke", Duration.ofSeconds(30));
            assertEquals("parity-smoke", cdp.evaluate("document.title"));

            cdp.navigate(url + "?step=2");
            cdp.waitFor("document.readyState === 'complete' && !!window.paritySmoke && window.paritySmoke.step === '2'",
                    Duration.ofSeconds(30));
            assertEquals((long) WIDTH, cdp.evaluate("window.innerWidth"));
            assertEquals((long) HEIGHT, cdp.evaluate("window.innerHeight"));

            byte[] png = cdp.captureScreenshot();
            Files.write(out.resolve("smoke-1200x800.png"), png);

            assertEquals(new PngHeader(WIDTH, HEIGHT), PngHeader.read(png));
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertRgb(0xFAC814, image.getRGB(600, 50), "header block");
            assertRgb(0x1E6FD9, image.getRGB(600, 500), "page background");
        } finally {
            report = session.kill();
        }

        assertTrue(report.isClean(), "browser processes survived: " + report.survivors());
        assertTrue(report.processes().size() > 1, "the browser spawns helper processes: " + report.processes());
        assertFalse(Files.exists(profile), "browser profile must be removed");
        assertTrue(Files.size(out.resolve("smoke-1200x800.png")) > 0);
    }

    private static void assertRgb(int expected, int argb, String what) {
        int actual = argb & 0xFFFFFF;
        // Допуск на сглаживание и цветовой профиль: по 3 единицы на канал.
        for (int shift : new int[] {16, 8, 0}) {
            int e = (expected >> shift) & 0xFF;
            int a = (actual >> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= 3, what + ": expected #" + Integer.toHexString(expected)
                    + " but was #" + Integer.toHexString(actual));
        }
    }
}
