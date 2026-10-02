package ru.cashprediction.parity.driver;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.parity.browser.CdpClient;
import ru.cashprediction.parity.pipeline.DumpTrees;

/** JDK CDP адаптер тестового порта страницы S3; отсутствие порта является ошибкой, а не успехом. */
public final class CdpTestApi implements UiTestDriver.TestApi {
    private final java.util.function.BiFunction<String, Duration, Object> evaluate;
    private final java.util.function.Supplier<byte[]> capture;
    private final Set<Class<? extends SelfTestCommand>> supported;

    /** Проверяет явные возможности тестового порта window.cpParityTestApi, доступного только с --test-api. */
    public CdpTestApi(CdpClient cdp) {
        this((expression, timeout) -> Objects.requireNonNull(cdp).evaluate(expression, timeout), cdp::captureScreenshot);
    }

    /** Подменяет только транспорт CDP для строгих тестов ответов и handshake. */
    CdpTestApi(java.util.function.Function<String, Object> evaluate, java.util.function.Supplier<byte[]> capture) {
        this((expression, timeout) -> evaluate.apply(expression), capture);
    }

    /** Сохраняет ограничение срока на уровне транспорта, включая незавершённые Promise. */
    private CdpTestApi(java.util.function.BiFunction<String, Duration, Object> evaluate, java.util.function.Supplier<byte[]> capture) {
        this.evaluate = Objects.requireNonNull(evaluate);
        this.capture = Objects.requireNonNull(capture);
        Object names = evaluate.apply("(() => { const a = window.cpParityTestApi; "
                + "if (!a || !Array.isArray(a.supported) || ['execute','awaitIdle','dump','takeStep'].some(k => typeof a[k] !== 'function')) "
                + "throw new Error('S3 test API unavailable'); return a.supported; })()", Duration.ofSeconds(5));
        Set<Class<? extends SelfTestCommand>> capabilities = new HashSet<>();
        if (!(names instanceof List<?>)) throw new IllegalArgumentException("Expected test capabilities array");
        for (Object name : (List<?>) names) {
            Class<?> command = Arrays.stream(SelfTestCommand.class.getPermittedSubclasses())
                    .filter(c -> c.getSimpleName().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown test capability: " + name));
            if (!capabilities.add(command.asSubclass(SelfTestCommand.class)))
                throw new IllegalArgumentException("Duplicate test capability: " + name);
        }
        supported = Set.copyOf(capabilities);
    }

    /** @return объявленные страницей операции */
    @Override public Set<Class<? extends SelfTestCommand>> supported() { return supported; }

    /** Забирает следующий test.step из очереди реальной страницы; пустая очередь возвращает null. */
    public Map<String, Object> takeStep() {
        Object value = call("takeStep", Map.of());
        return value == null ? null : Json.asObject(value, "test.step");
    }

    /** Выполняет операцию через тестовый порт событий страницы; Promise ждёт существующий JDK CDP клиент. */
    @Override public void execute(SelfTestCommand command) {
        if (!supported.contains(command.getClass())) throw new UnsupportedOperationException(command.getClass().getSimpleName());
        call("execute", Map.of("kind", command.getClass().getSimpleName(), "args", UiJson.toTree(command)));
    }

    /** Ждёт обработку очереди и debounce на стороне настоящего клиента. */
    @Override public void awaitIdle(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout");
        call("awaitIdle", Map.of("timeoutMs", timeout.toMillis()), timeout);
    }

    /** @return дамп настоящих DOM виджетов; модель ядра вместо него не принимается адаптером UiTestDriver */
    @Override public UiDump dump(String step) { return DumpTrees.read(UiJson.write(call("dump", Map.of("step", step)))); }

    /** Снимает страницу существующей командой Page.captureScreenshot. */
    @Override public byte[] screenshot(String step) throws IOException {
        try { return capture.get(); }
        catch (RuntimeException e) { throw new IOException("CDP screenshot failed: " + step, e); }
    }

    /** Требует явное успешное подтверждение; undefined и молчаливый возврат не являются успехом. */
    private Object call(String method, Map<String, Object> args) {
        return call(method, args, Duration.ofSeconds(5));
    }

    /** Требует подтверждение до истечения срока операции. */
    private Object call(String method, Map<String, Object> args, Duration timeout) {
        Map<String, Object> result = Json.asObject(evaluate.apply("(async () => { const a = window.cpParityTestApi; "
                + "if (!a || typeof a[" + UiJson.write(method) + "] !== 'function') throw new Error('Unsupported test API operation'); "
                + "return await a[" + UiJson.write(method) + "](" + UiJson.write(args) + "); })()", timeout), "test API response");
        if (!Boolean.TRUE.equals(result.get("ok"))) throw new IllegalStateException("Widget operation failed: " + result.get("message"));
        return result.get("value");
    }
}
