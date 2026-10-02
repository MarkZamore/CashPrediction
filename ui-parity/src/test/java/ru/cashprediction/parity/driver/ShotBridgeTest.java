package ru.cashprediction.parity.driver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;

/** Синтетические PNG проверяют только протокол доставки; реальные снимки даёт CDP. */
class ShotBridgeTest {
    /** Снимок приходит раньше успешного подтверждения и сохраняет все исходные байты. */
    @Test void sendsExactlyOneCapturedPngBeforeAcknowledgement() throws Exception {
        Api api = new Api(png());
        List<String> routes = new ArrayList<>();
        List<Map<String, Object>> bodies = new ArrayList<>();
        TestApiBridge bridge = bridge(api, (route, body) -> { routes.add(route); bodies.add(body); });
        bridge.accept(effect());
        assertEquals(List.of("/api/test/shot", "/api/test/result"), routes);
        assertEquals(1, api.captures);
        assertEquals("visible", api.step);
        assertArrayEquals(api.png, Base64.getDecoder().decode((String) bodies.getFirst().get("png")));
        assertEquals(3L, bodies.getFirst().get("n"));
        assertEquals("web", ((Map<?, ?>) bodies.getFirst().get("dump")).get("client"));
        assertEquals(true, bodies.getLast().get("ok"));
        assertThrows(IllegalArgumentException.class, () -> bridge.accept(effect()));
        assertEquals(1, api.captures);
    }

    /** Ошибка доставки PNG не должна послать ложное успешное подтверждение. */
    @Test void failedUploadNeverAcknowledgesSuccess() throws Exception {
        List<String> routes = new ArrayList<>();
        TestApiBridge bridge = bridge(new Api(png()), (route, body) -> {
            routes.add(route); throw new IOException("upload failed");
        });
        assertThrows(IOException.class, () -> bridge.accept(effect()));
        assertEquals(List.of("/api/test/shot"), routes);
    }

    /** Неизображение получает явный FAIL вместо пустого файла или успеха. */
    @Test void invalidCaptureReportsFailureWithoutUpload() throws Exception {
        List<String> routes = new ArrayList<>();
        List<Map<String, Object>> bodies = new ArrayList<>();
        bridge(new Api(new byte[]{1, 2, 3}), (route, body) -> { routes.add(route); bodies.add(body); }).accept(effect());
        assertEquals(List.of("/api/test/result"), routes);
        assertEquals(false, bodies.getFirst().get("ok"));
        assertTrue(bodies.getFirst().get("message").toString().contains("IOException"));
    }

    private static Map<String, Object> effect() {
        return Map.of("type", "test.step", "n", 3L, "command", "shot visible");
    }
    private static TestApiBridge bridge(Api api, TestApiBridge.Sender sender) {
        return new TestApiBridge(new UiTestDriver(ClientKind.WEB, api), sender);
    }
    private static byte[] png() throws IOException {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new java.awt.image.BufferedImage(1200, 800, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes));
        return bytes.toByteArray();
    }

    /** Порт-заглушка доказывает, что Shot не исполняется как обычная команда DOM. */
    private static final class Api implements UiTestDriver.TestApi {
        private final byte[] png;
        private int captures;
        private String step;
        private Api(byte[] png) { this.png = png; }
        /** Возможности execute не нужны для операции самого раннера. */
        @Override public Set<Class<? extends SelfTestCommand>> supported() { return Set.of(); }
        /** Shot обязан пользоваться screenshot, а не execute. */
        @Override public void execute(SelfTestCommand command) { fail("Runner command reached DOM execute"); }
        /** Тестовая очередь уже пуста. */
        @Override public void awaitIdle(Duration timeout) { }
        /** Исходные измерения передаются вместе со снимком без нормализации. */
        @Override public UiDump dump(String step) {
            return new UiDump(1, "web", "", step, null, List.of(), null, null, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        }
        /** Возвращает байты тестового снимка без пересоздания в мосте. */
        @Override public byte[] screenshot(String step) { this.step = step; captures++; return png; }
    }
}
