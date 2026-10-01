package ru.cashprediction.parity.driver;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;

/** Проверяет отказ неподдерживаемых операций и доставку FAIL через мост. */
class UiTestDriverTest {
    /** Тестовый порт намеренно не умеет ни одной операции. */
    private static class UnsupportedApi implements UiTestDriver.TestApi {
        /** @return пустой набор */
        public Set<Class<? extends SelfTestCommand>> supported() { return Set.of(); }
        /** Не должна вызываться для неподдерживаемой команды. */
        public void execute(SelfTestCommand command) { fail("Unsupported command reached API"); }
        /** Отклоняет неподключённую синхронизацию. */
        public void awaitIdle(Duration timeout) { throw new UnsupportedOperationException("idle"); }
        /** Отклоняет неподключённый дамп. */
        public UiDump dump(String step) { throw new UnsupportedOperationException("dump"); }
        /** Отклоняет неподключённый снимок. */
        public byte[] screenshot(String step) throws IOException { throw new IOException("shot"); }
    }
    /** Неподдерживаемая команда не может дать успех. */
    @Test void unsupportedIsNotSuccess() {
        var driver = new UiTestDriver(ClientKind.WEB, new UnsupportedApi());
        assertThrows(UnsupportedOperationException.class, () -> driver.execute(new SelfTestCommand.Sample()));
        assertThrows(IOException.class, () -> driver.screenshot("sample"));
    }
    /** Мост отправляет FAIL и отклоняет повтор шага, не повторяя события. */
    @Test void bridgePostsFailureAndRejectsDuplicates() throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        var bridge = new TestApiBridge(new UiTestDriver(ClientKind.WEB, new UnsupportedApi()),
                (route, body) -> { assertEquals("/api/test/result", route); messages.add(body); });
        var effect = Map.<String, Object>of("type", "test.step", "n", 1L, "command", "sample");
        bridge.accept(effect);
        assertEquals(false, messages.getFirst().get("ok"));
        assertTrue(messages.getFirst().get("message").toString().contains("UnsupportedOperationException"));
        assertThrows(IllegalArgumentException.class, () -> bridge.accept(effect));
        assertEquals(1, messages.size());
    }
    /** Ошибка транспорта не маскируется подтверждением выполнения. */
    @Test void bridgeTransportFailurePropagates() {
        var bridge = new TestApiBridge(new UiTestDriver(ClientKind.WEB, new UnsupportedApi()),
                (route, body) -> { throw new IOException("offline"); });
        assertThrows(IOException.class, () -> bridge.accept(Map.of("type", "test.step", "n", 1, "command", "sample")));
        assertThrows(IllegalArgumentException.class, () -> TestApiBridge.http(java.net.URI.create("https://example.com")));
    }
    /** Явно поддержанная операция исполняется один раз, затем дожидается очереди и подтверждается. */
    @Test void supportedOperationPostsSuccessAfterIdle() throws Exception {
        List<String> events = new ArrayList<>();
        var api = new UnsupportedApi() {
            /** @return единственная реализованная команда */
            @Override public Set<Class<? extends SelfTestCommand>> supported() { return Set.of(SelfTestCommand.Sample.class); }
            /** Регистрирует фактическое исполнение. */
            @Override public void execute(SelfTestCommand command) { events.add("execute"); }
            /** Регистрирует ожидание очереди. */
            @Override public void awaitIdle(Duration timeout) { events.add("idle"); }
        };
        var bridge = new TestApiBridge(new UiTestDriver(ClientKind.WEB, api), (route, body) -> {
            assertEquals(true, body.get("ok")); events.add("result");
        });
        bridge.accept(Map.of("type", "test.step", "n", 1, "command", "sample"));
        assertEquals(List.of("execute", "idle", "result"), events);
    }
    /** Дамп модели не выдаётся за дамп реального клиента, незавершённый PNG тоже отклоняется. */
    @Test void modelDumpAndTruncatedScreenshotAreRejected() {
        var api = new UnsupportedApi() {
            /** Возвращает намеренно неправильное происхождение. */
            @Override public UiDump dump(String step) {
                return new UiDump(1, "model", "test-api", step, null, List.of(), null, null, null, null,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
            }
            /** Возвращает только начало заголовка вместо изображения. */
            @Override public byte[] screenshot(String step) { return new byte[] {(byte) 137, 80, 78, 71}; }
        };
        var driver = new UiTestDriver(ClientKind.WEB, api);
        assertThrows(IllegalStateException.class, () -> driver.dump("sample"));
        assertThrows(IOException.class, () -> driver.screenshot("sample"));
    }
}
