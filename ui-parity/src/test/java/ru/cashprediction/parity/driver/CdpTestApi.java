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
    private final CdpClient cdp;
    private final Set<Class<? extends SelfTestCommand>> supported;

    /** Проверяет явные возможности тестового порта window.cpParityTestApi, доступного только с --test-api. */
    public CdpTestApi(CdpClient cdp) {
        this.cdp = Objects.requireNonNull(cdp);
        Object names = cdp.evaluate("(() => { const a = window.cpParityTestApi; "
                + "if (!a || !Array.isArray(a.supported)) throw new Error('S3 test API unavailable'); return a.supported; })()");
        Set<Class<? extends SelfTestCommand>> capabilities = new HashSet<>();
        for (Object name : (List<?>) names) {
            Class<?> command = Arrays.stream(SelfTestCommand.class.getPermittedSubclasses())
                    .filter(c -> c.getSimpleName().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown test capability: " + name));
            capabilities.add(command.asSubclass(SelfTestCommand.class));
        }
        supported = Set.copyOf(capabilities);
    }

    /** @return объявленные страницей операции */
    @Override public Set<Class<? extends SelfTestCommand>> supported() { return supported; }

    /** Выполняет операцию через тестовый порт событий страницы; Promise ждёт существующий JDK CDP клиент. */
    @Override public void execute(SelfTestCommand command) {
        if (!supported.contains(command.getClass())) throw new UnsupportedOperationException(command.getClass().getSimpleName());
        call("execute", Map.of("kind", command.getClass().getSimpleName(), "args", UiJson.toTree(command)));
    }

    /** Ждёт обработку очереди и debounce на стороне настоящего клиента. */
    @Override public void awaitIdle(Duration timeout) { call("awaitIdle", Map.of("timeoutMs", timeout.toMillis())); }

    /** @return дамп настоящих DOM виджетов; модель ядра вместо него не принимается адаптером UiTestDriver */
    @Override public UiDump dump(String step) { return DumpTrees.read(UiJson.write(call("dump", Map.of("step", step)))); }

    /** Снимает страницу существующей командой Page.captureScreenshot. */
    @Override public byte[] screenshot(String step) throws IOException {
        try { return cdp.captureScreenshot(); }
        catch (RuntimeException e) { throw new IOException("CDP screenshot failed: " + step, e); }
    }

    /** Требует явное успешное подтверждение; undefined и молчаливый возврат не являются успехом. */
    private Object call(String method, Map<String, Object> args) {
        Map<String, Object> result = Json.asObject(cdp.evaluate("(async () => { const a = window.cpParityTestApi; "
                + "if (!a || typeof a[" + UiJson.write(method) + "] !== 'function') throw new Error('Unsupported test API operation'); "
                + "return await a[" + UiJson.write(method) + "](" + UiJson.write(args) + "); })()"), "test API response");
        if (!Boolean.TRUE.equals(result.get("ok"))) throw new IllegalStateException("Widget operation failed: " + result.get("message"));
        return result.get("value");
    }
}
