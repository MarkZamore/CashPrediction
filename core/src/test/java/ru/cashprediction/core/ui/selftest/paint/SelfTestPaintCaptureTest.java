package ru.cashprediction.core.ui.selftest.paint;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.SelfTestRunner;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.selftest.UiDriver;

/** Проверяет строгий путь раннера на синтетической паре, не доказывая качество клиента. */
final class SelfTestPaintCaptureTest {
    @TempDir Path root;

    /** Успех Shot записывается после полного набора, без отдельных legacy-вызовов. */
    @Test void recordsSuccessOnlyForPersistedBoundBundle() throws Exception {
        var driver = new Driver(true);
        var runner = runner(driver);
        var script = script();
        assertTrue(runner.run(script).ok());
        assertEquals(1, driver.captures);
        Path bundle = root.resolve("output/paint/" + PaintCaptureFixtures.CAPTURE);
        PaintCaptureFiles.verify(bundle, PaintCaptureFixtures.request(), capture());
        assertFalse(Files.exists(root.resolve("output/paint/normal.png")));
        String journal = Files.readString(root.resolve("output/selftest.log"));
        assertTrue(journal.contains("SELFTEST 3 OK shot normal"));
        // Повторное исполнение не может перезаписать прежнее доказательство.
        assertFalse(runner.run(script).steps().getLast().ok());
    }

    /** Отсутствующая capability даёт FAIL без ложного PNG и commit. */
    @Test void capabilityFailureCannotFallbackOrPublishSuccess() throws Exception {
        var report = runner(new Driver(false)).run(script());
        assertFalse(report.steps().getLast().ok());
        assertTrue(report.steps().getLast().message().contains("widget-paint-v1"));
        assertFalse(Files.exists(root.resolve("output/paint/" + PaintCaptureFixtures.CAPTURE)));
        assertFalse(Files.readString(root.resolve("output/selftest.log")).contains("3 OK"));
    }

    /** Неполный план отклоняется до исполнения пользовательских команд. */
    @Test void rejectsMissingShotBeforeAnyExecution() {
        var driver = new Driver(true);
        assertThrows(IllegalArgumentException.class, () -> runner(driver)
                .run(SelfTestScript.parse("paint", "sample\nsample\nshot normal\nshot extra")));
        assertEquals(0, driver.commands);
        assertEquals(0, driver.captures);
    }

    /** Запрос на другой сценарий или несуществующую строку не выполняется. */
    @Test void rejectsWrongScenarioAndUnusedLine() {
        var driver = new Driver(true);
        assertThrows(IllegalArgumentException.class, () -> runner(driver)
                .run(SelfTestScript.parse("other", "sample\nsample\nshot normal")));
        assertThrows(IllegalArgumentException.class, () -> runner(driver)
                .run(SelfTestScript.parse("paint", "sample")));
        assertEquals(0, driver.commands);
    }

    /** Создаёт изолированное окружение без настоящего реестра пользователя. */
    private SelfTestRunner runner(Driver driver) {
        var environment = AppEnvironment.from(LaunchOptions.parse(
                List.of("--home", root.toAbsolutePath().toString(), "--registry", "memory"), new Properties()));
        return new SelfTestRunner(driver, root.resolve("output"), environment,
                Map.of(3, PaintCaptureFixtures.request()));
    }

    /** Возвращает сценарий с номером Shot, соответствующим запросу unit-фикстуры. */
    private static SelfTestScript script() { return SelfTestScript.parse("paint", "sample\nsample\nshot normal"); }

    /** Создаёт согласованную unit-пару, не нативный эталон. */
    private static WidgetCapture capture() {
        return new WidgetCapture(PaintCaptureFixtures.raw("fx", "normal"),
                PaintCaptureFixtures.png(1200, 800), PaintCaptureFixtures.observation());
    }

    /** Наблюдает только строгие вызовы и явно запрещает независимый снимок. */
    private static final class Driver implements UiDriver {
        private final boolean supported;
        private int captures;
        private int commands;
        /** Сохраняет возможность, которой управляет негативный тест. */
        Driver(boolean supported) { this.supported = supported; }
        /** Возвращает профиль unit-адаптера. */
        @Override public ClientKind client() { return ClientKind.FX; }
        /** Подсчитывает команды, чтобы выявить преждевременное исполнение. */
        @Override public void execute(SelfTestCommand command) { commands++; }
        /** Unit-фикстура не имеет очереди графических событий. */
        @Override public void awaitIdle(Duration duration) { }
        /** Legacy-дамп запрещён в этом опыте. */
        @Override public UiDump dump(String step) { throw new AssertionError("legacy dump"); }
        /** Legacy-снимок запрещён в этом опыте. */
        @Override public byte[] screenshot(String step) { throw new AssertionError("legacy PNG"); }
        /** Проверяет запрос либо исполняет стандартный отказ отсутствующей capability. */
        @Override public WidgetCapture capture(PaintCaptureRequest request) throws Exception {
            if (!supported) return UiDriver.super.capture(request);
            captures++;
            var value = SelfTestPaintCaptureTest.capture(); value.requireRequest(request); return value;
        }
    }
}
