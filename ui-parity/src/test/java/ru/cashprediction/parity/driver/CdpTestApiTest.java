package ru.cashprediction.parity.driver;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет контракт браузера без подмены DOM моделями ядра. */
class CdpTestApiTest {
    /** Неверные и дублирующиеся возможности не принимаются. */
    @Test void rejectsInvalidCapabilities() {
        for (Object value : List.of("Sample", List.of("Unknown"), List.of("Sample", "Sample"), List.of(12)))
            assertThrows(IllegalArgumentException.class, () -> new CdpTestApi(s -> value, () -> new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new CdpTestApi(s -> null, () -> new byte[0]));
    }

    /** Пустой ответ, отсутствие ok и отрицательное подтверждение не являются успехом. */
    @Test void requiresExplicitSuccess() {
        for (Object value : List.of(Map.of(), Map.of("ok", false), Map.of("ok", "true"), "ok")) {
            var api = new CdpTestApi(s -> s.contains("return a.supported") ? List.of("Sample") : value, () -> new byte[0]);
            assertThrows(RuntimeException.class, () -> api.execute(new SelfTestCommand.Sample()));
        }
    }

    /** Необъявленная операция и неверный timeout не доходят до CDP. */
    @Test void unsupportedAndTimeoutRejectedBeforeTransport() {
        AtomicInteger calls = new AtomicInteger();
        var api = new CdpTestApi(s -> { calls.incrementAndGet(); return List.of(); }, () -> new byte[0]);
        assertThrows(UnsupportedOperationException.class, () -> api.execute(new SelfTestCommand.Sample()));
        assertThrows(IllegalArgumentException.class, () -> api.awaitIdle(Duration.ZERO));
        assertEquals(1, calls.get());
    }

    /** Ошибки промиса остаются ошибками транспорта. */
    @Test void promiseFailurePropagates() {
        var api = new CdpTestApi(s -> {
            if (s.contains("return a.supported")) return List.of("Sample");
            throw new IllegalStateException("Promise rejected");
        }, () -> new byte[0]);
        assertEquals("Promise rejected", assertThrows(IllegalStateException.class,
                () -> api.execute(new SelfTestCommand.Sample())).getMessage());
    }

    /** Очередь test.step возвращает только явное значение, null означает пустую очередь. */
    @Test void queueHandshakeAndArguments() {
        List<String> expressions = new ArrayList<>();
        var api = new CdpTestApi(s -> {
            expressions.add(s);
            return s.contains("return a.supported") ? List.of("Sample") : Map.of("ok", true);
        }, () -> new byte[0]);
        assertNull(api.takeStep());
        api.execute(new SelfTestCommand.Sample());
        api.awaitIdle(Duration.ofMillis(750));
        assertTrue(expressions.getFirst().contains("'takeStep'"));
        assertTrue(expressions.get(2).contains("\"kind\":\"Sample\""));
        assertTrue(expressions.get(3).contains("\"timeoutMs\":750"));
    }
}
