package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.*;
import javax.imageio.stream.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Только PNG encoding driver: реальные ImageIO SPI и codec, без driver constructor/Robot/GUI. */
@ResourceLock("imageio-registry")
class SwingDriverPngEncodingTest {
    @TempDir Path ownCache;

    /** Реальный FileCache SPI не должен вызываться; negative old overload оставляет наблюдаемый factory call. */
    @Test void encodingNeverRequestsFileCacheProviderOrCreatesCacheFile() throws Exception {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        boolean cache = ImageIO.getUseCache(); File directory = ImageIO.getCacheDirectory();
        List<ImageOutputStreamSpi> previous = providers(ImageOutputStreamSpi.class);
        FileCacheProbe spi = new FileCacheProbe(ownCache);
        registry.registerServiceProvider(spi);
        try {
            for (var other : previous) registry.setOrdering(ImageOutputStreamSpi.class, spi, other);
            byte[] png = SwingUiDriver.encodePng(image());
            assertTrue(png.length > 33);
            System.out.println("OUTPUT_FACTORY_CALLS=" + spi.calls + " CREATED_REAL_CACHE_FILES=" + spi.created);
            assertEquals(0, spi.calls, "ImageIO OutputStream overload consulted real FileCache provider");
            assertEquals(0, spi.created);
            try (var files = Files.list(ownCache)) { assertEquals(0, files.count()); }
            assertEquals(cache, ImageIO.getUseCache()); assertEquals(directory, ImageIO.getCacheDirectory());
        } finally { registry.deregisterServiceProvider(spi); }
        assertEquals(previous, providers(ImageOutputStreamSpi.class));
    }

    /** Сравнивает весь PNG с независимо созданным JDK writer и проверяет реальные decoded pixels. */
    @Test void bytesEqualIndependentEncoderAndDecodeEveryPixel() throws Exception {
        BufferedImage original = image();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        byte[] expected;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output); writer.write(original);
        } finally { writer.dispose(); }
        expected = bytes.toByteArray();
        byte[] actual = SwingUiDriver.encodePng(original);
        assertArrayEquals(expected, actual);
        assertArrayEquals(new byte[] {(byte)137,80,78,71,13,10,26,10}, Arrays.copyOf(actual, 8));
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(actual))) {
            // ImageIO.read(ImageInputStream) сам закрывает input: direct reader сохраняет caller ownership.
            ImageReader reader=ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input); BufferedImage decoded=reader.read(0);
                assertNotNull(decoded); assertEquals(original.getWidth(), decoded.getWidth()); assertEquals(original.getHeight(), decoded.getHeight());
                for (int y=0;y<original.getHeight();y++) for (int x=0;x<original.getWidth();x++) assertEquals(original.getRGB(x,y), decoded.getRGB(x,y));
            } finally {reader.dispose();}
        }
    }

    /** Writer сохраняет именно фактический memory stream; после успешного return он должен быть закрыт. */
    @Test void actualMemoryOutputIsClosedAfterRealEncoderSuccess() throws Exception { observeClose(false); }

    /** Отказ writer не обходит close и сохраняет исходную IOException. */
    @Test void actualMemoryOutputIsClosedAfterEncoderFailure() throws Exception { observeClose(true); }

    /** Отсутствие PNG writer не выдаёт пустой byte[] вместо ошибки. */
    @Test void missingWriterRemainsExplicitIOException() throws Exception {
        IIORegistry registry=IIORegistry.getDefaultInstance();
        List<ImageWriterSpi> png = providers(ImageWriterSpi.class).stream()
                .filter(spi -> Arrays.stream(spi.getFormatNames()).anyMatch("png"::equalsIgnoreCase)).toList();
        try {
            png.forEach(registry::deregisterServiceProvider);
            assertEquals("PNG encoder unavailable", assertThrows(IOException.class, () -> SwingUiDriver.encodePng(image())).getMessage());
        } finally { png.forEach(registry::registerServiceProvider); }
    }

    /** Проверяет stream identity/close без подмены codec в success case. */
    private void observeClose(boolean fail) throws Exception {
        ImageWriter real=ImageIO.getImageWritersByFormatName("png").next();
        ObserveWriterSpi spi=new ObserveWriterSpi(real, fail);
        IIORegistry registry=IIORegistry.getDefaultInstance(); List<ImageWriterSpi> previous=providers(ImageWriterSpi.class);
        registry.registerServiceProvider(spi);
        try {
            for (var other:previous) registry.setOrdering(ImageWriterSpi.class,spi,other);
            if (fail) assertSame(spi.failure, assertThrows(IOException.class, () -> SwingUiDriver.encodePng(image())));
            else assertTrue(SwingUiDriver.encodePng(image()).length>33);
            assertInstanceOf(MemoryCacheImageOutputStream.class,spi.observed);
            assertThrows(IOException.class, () -> spi.observed.getStreamPosition());
            System.out.println("MEMORY_STREAM_CLOSED=true ENCODER_FAILURE="+fail);
        } finally {registry.deregisterServiceProvider(spi); real.dispose();}
        assertEquals(previous,providers(ImageWriterSpi.class));
    }

    /** Изображение с прозрачностью и разными пикселями, не screenshot substitute evidence. */
    private BufferedImage image() {
        BufferedImage image=new BufferedImage(7,5,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<5;y++) for(int x=0;x<7;x++) image.setRGB(x,y,((30+x*20+y*5)<<24)|((x*31)<<16)|((y*41)<<8)|(x+y*7));
        return image;
    }

    /** Сохраняет ordered реальные provider instances для точного cleanup. */
    private static <T> List<T> providers(Class<T> type) {
        List<T> values=new ArrayList<>(); IIORegistry.getDefaultInstance().getServiceProviders(type,true).forEachRemaining(values::add); return values;
    }

    /** Реальная файловая cache фабрика для bounded отрицательного контроля только в ownCache. */
    private static final class FileCacheProbe extends ImageOutputStreamSpi {
        private final Path own; private int calls; private long created;
        private FileCacheProbe(Path own) {super("fixture","1",OutputStream.class);this.own=own;}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public String getDescription(Locale locale) {return "own file cache probe";}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public ImageOutputStream createOutputStreamInstance(Object output,boolean useCache,File cacheDir) throws IOException {
            calls++; FileCacheImageOutputStream stream=new FileCacheImageOutputStream((OutputStream)output,own.toFile());
            try (var files=Files.list(own)) {created+=files.count();}
            return stream;
        }
    }

    /** SPI wrapper наблюдает stream и делегирует настоящий JDK PNG writer, либо бросает выделенную ошибку. */
    private static final class ObserveWriterSpi extends ImageWriterSpi {
        private final ImageWriter real; private final boolean fail;
        private final IOException failure=new IOException("OWN_ENCODER_FAILURE"); private ImageOutputStream observed;
        private ObserveWriterSpi(ImageWriter real,boolean fail) {
            super("fixture","1",new String[]{"png"},new String[]{"png"},new String[]{"image/png"},
                    ObserveWriter.class.getName(),new Class<?>[]{ImageOutputStream.class},null,false,null,null,null,null,false,null,null,null,null);
            this.real=real;this.fail=fail;
        }
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public boolean canEncodeImage(ImageTypeSpecifier image) {return real.getOriginatingProvider().canEncodeImage(image);}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public ImageWriter createWriterInstance(Object extension) {return new ObserveWriter(this);}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public String getDescription(Locale locale) {return "observed JDK encoder";}
    }

    /** Наблюдатель output lifecycle; не имитирует PNG success bytes. */
    private static final class ObserveWriter extends ImageWriter {
        private final ObserveWriterSpi spi;
        private ObserveWriter(ObserveWriterSpi spi) {super(spi);this.spi=spi;}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public IIOMetadata getDefaultStreamMetadata(ImageWriteParam p) {return spi.real.getDefaultStreamMetadata(p);}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier t,ImageWriteParam p) {return spi.real.getDefaultImageMetadata(t,p);}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public IIOMetadata convertStreamMetadata(IIOMetadata m,ImageWriteParam p) {return spi.real.convertStreamMetadata(m,p);}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public IIOMetadata convertImageMetadata(IIOMetadata m,ImageTypeSpecifier t,ImageWriteParam p) {return spi.real.convertImageMetadata(m,t,p);}
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public void write(IIOMetadata metadata,IIOImage image,ImageWriteParam param) throws IOException {
            spi.observed=(ImageOutputStream)getOutput();
            if(spi.fail) throw spi.failure;
            spi.real.setOutput(spi.observed);spi.real.write(metadata,image,param);
        }
        /** Выполняет операцию тестового SPI, сохраняя контракт реального PNG codec. */
        @Override public void dispose() {spi.real.dispose();super.dispose();}
    }
}
