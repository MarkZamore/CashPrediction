package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageOutputStreamSpi;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.chart.*;

/** Проверяет экранный художник PNG по геометрическим примитивам контракта. */
@ResourceLock("imageio-registry")
class SwingChartTest {
    @Test void dormantWidgetIsPaintedAndNewRevisionInvalidatesObservedOutput() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            SwingChart widget = new SwingChart(null);
            javax.swing.JPanel parent = new javax.swing.JPanel(); parent.setSize(500, 300); parent.add(widget);
            ChartModel model = new ChartModel() {
                public long revision() { return 1; }
                public ChartScene layout(double width, double height) { return new ChartScene(width, height, plot(), List.of(new ChartPrimitive.Circle(40, 40, 3, ColorToken.ACCENT, null, 0)), List.of(), List.of(), "", ColorToken.TEXT_MUTED); }
                public ru.cashprediction.core.ui.view.popup.DayCardModel dayCard(java.time.LocalDate date) { return null; }
                public java.util.Optional<ChartHover> hover(double x, double y, double width, double height) { return java.util.Optional.empty(); }
            };
            widget.render(model); assertNull(widget.drawn); widget.observeDormant();
            assertEquals(1, widget.drawn.markerCount()); assertEquals(500, widget.painted.width());
            widget.render(model); assertNull(widget.drawn);
        });
    }
    @Test void pngHasRequiredDimensionsBackgroundAndPrimitiveColor() throws Exception {
        ChartScene scene = new ChartScene(1200, 700, plot(), List.of(new ChartPrimitive.Box(10, 10, 20, 20, ColorToken.INCOME, 1)), List.of(), List.of(), "", ColorToken.TEXT_MUTED);
        var image = ImageIO.read(new ByteArrayInputStream(SwingChart.png(scene)));
        assertEquals(1200, image.getWidth()); assertEquals(700, image.getHeight());
        assertEquals(ColorToken.BG_SURFACE.argb(), image.getRGB(100, 100)); assertEquals(ColorToken.INCOME.argb(), image.getRGB(20, 20));
    }
    @Test void alphaIsAppliedToFill() throws Exception {
        ChartScene scene = new ChartScene(100, 100, plot(), List.of(new ChartPrimitive.Box(10, 10, 20, 20, ColorToken.ACCENT, .1)), List.of(), List.of(), "", ColorToken.TEXT_MUTED);
        var image = ImageIO.read(new ByteArrayInputStream(SwingChart.png(scene)));
        assertNotEquals(ColorToken.ACCENT.argb(), image.getRGB(20, 20)); assertNotEquals(ColorToken.BG_SURFACE.argb(), image.getRGB(20, 20));
    }

    /** Явный memory output обходит SPI-фабрику и не меняет настройки соседних потребителей ImageIO. */
    @Test void pngDoesNotRequestAnImageIoOutputStreamOrChangeCacheSettings() throws Exception {
        boolean useCache = ImageIO.getUseCache();
        File cacheDirectory = ImageIO.getCacheDirectory();
        IIORegistry registry = IIORegistry.getDefaultInstance();
        RejectOutputStreamSpi guard = new RejectOutputStreamSpi();
        List<ImageOutputStreamSpi> previous = new ArrayList<>();
        registry.getServiceProviders(ImageOutputStreamSpi.class, true).forEachRemaining(previous::add);
        registry.registerServiceProvider(guard, ImageOutputStreamSpi.class);
        try {
            for (ImageOutputStreamSpi provider : previous) registry.setOrdering(ImageOutputStreamSpi.class, guard, provider);
            byte[] png = SwingChart.png(pngScene());
            assertTrue(png.length > 8);
            assertArrayEquals(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, java.util.Arrays.copyOf(png, 8));
            assertEquals(0, guard.calls, "PNG must not request an OutputStream cache factory");
            assertEquals(useCache, ImageIO.getUseCache());
            assertEquals(cacheDirectory, ImageIO.getCacheDirectory());
        } finally {
            registry.deregisterServiceProvider(guard, ImageOutputStreamSpi.class);
        }
    }

    /** Тот же PNG writer и сцена дают полные прежние байты без выбора файлового кэша. */
    @Test void pngBytesMatchDirectMemoryEncodingAndRemainStable() throws Exception {
        ChartScene scene = pngScene();
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { SwingChart.paint(graphics, scene); } finally { graphics.dispose(); }
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(expected)) {
            assertTrue(ImageIO.write(image, "png", output));
        }
        byte[] actual = SwingChart.png(scene);
        assertArrayEquals(expected.toByteArray(), actual);
        assertArrayEquals(actual, SwingChart.png(scene));
    }

    /** Небольшая сцена без native окна, шрифтов и зависимости от прогноза. */
    private ChartScene pngScene() {
        return new ChartScene(100, 100, plot(),
                List.of(new ChartPrimitive.Box(10, 10, 20, 20, ColorToken.INCOME, 1)),
                List.of(), List.of(), "", ColorToken.TEXT_MUTED);
    }

    /** Отрицательный sentinel фабрики; не подменяет настоящий PNG writer или painter. */
    private static final class RejectOutputStreamSpi extends ImageOutputStreamSpi {
        private int calls;

        /** Ограничивает provider только OutputStream, не затрагивая декодирование или другие типы stream. */
        private RejectOutputStreamSpi() {
            super("CashPrediction", "1", OutputStream.class);
        }

        /** {@inheritDoc} Любой запрос фабрики означает возврат к небезопасному OutputStream overload. */
        @Override public ImageOutputStream createOutputStreamInstance(Object output, boolean useCache, File cacheDir) throws IOException {
            calls++;
            throw new IOException("Unexpected ImageIO OutputStream factory");
        }

        /** {@inheritDoc} Возвращает техническое описание test-only provider. */
        @Override public String getDescription(Locale locale) {
            return "CashPrediction PNG output stream sentinel";
        }
    }

    private PlotTransform plot() { return new PlotTransform(0, 0, 100, 100, java.time.LocalDate.of(2026, 9, 13), 30, 0, 100); }
}
