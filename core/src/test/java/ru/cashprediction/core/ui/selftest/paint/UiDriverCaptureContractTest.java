package ru.cashprediction.core.ui.selftest.paint;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.UiDriver;

/** Не разрешает выдавать раздельные legacy-наблюдения за согласованный захват. */
final class UiDriverCaptureContractTest {
    /** Отсутствие capability явно отклоняется до чтения несогласованного дампа или PNG. */
    @Test void missingCapabilityDoesNotFallbackToIndependentScreenshot() {
        UiDriver driver = new UiDriver() {
            /** Возвращает тип тестируемого настоящего адаптера. */
            @Override public ClientKind client() { return ClientKind.FX; }
            /** Ввод не относится к проверке отсутствующей capability. */
            @Override public void execute(SelfTestCommand command) { fail("unexpected execute"); }
            /** Ожидание не может сделать два независимых вызова единым захватом. */
            @Override public void awaitIdle(Duration timeout) { fail("unexpected await"); }
            /** Старый дамп не должен читаться при отсутствии барьера. */
            @Override public UiDump dump(String step) { fail("legacy dump fallback"); return null; }
            /** Старый PNG не должен использоваться как связанное доказательство. */
            @Override public byte[] screenshot(String step) { fail("legacy PNG fallback"); return null; }
        };
        var error = assertThrows(UnsupportedOperationException.class,
                () -> driver.capture(PaintCaptureFixtures.request()));
        assertEquals("widget-paint-v1", error.getMessage());
    }
}
