package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.dump.DumpNormalizer;
import ru.cashprediction.core.ui.dump.UiDump;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки эффективного оформления эталона: поверхность, жирность баланса и нетекстовые элементы. */
class ModelDumpStyleTest {
    @TempDir Path root;

    /** Обычные строки наследуют белую поверхность, а баланс остаётся жирным во всех типах строк. */
    @Test void sampleRowsHaveEffectiveBackgroundAndBoldBalance() throws Exception {
        var dump = sample();
        assertTrue(dump.table().rows().stream().anyMatch(row -> row.background().equals("bg.surface")));
        assertTrue(dump.table().rows().stream().anyMatch(row -> row.background().equals("total.bg")));
        for (var row : dump.table().rows()) assertTrue(row.styles().get("balance").bold(), row.rowId());
        assertDoesNotThrow(() -> DumpNormalizer.comparisonTree(dump));
    }

    /** Разделитель и растяжка не имеют текста: цвет линии не выдаётся за цвет отсутствующей надписи. */
    @Test void nonTextToolbarItemsHaveNoTextColor() throws Exception {
        var items = sample().toolbar().items();
        assertTrue(items.stream().anyMatch(item -> item.kind().equals("Separator")));
        assertTrue(items.stream().anyMatch(item -> item.kind().equals("Spacer")));
        for (var item : items) {
            if (item.kind().equals("Separator") || item.kind().equals("Spacer")) assertEquals("", item.color(), item.id());
            else assertFalse(item.color().isBlank(), item.id());
        }
    }

    /** Исполняет настоящий контроллер модели; не создаёт ожидаемые строки вручную. */
    private UiDump sample() throws Exception {
        var env = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry", "memory", "--today", "2026-09-13"));
        var driver = new ModelUiDriver(env, ClientProfile.fx("25"));
        try {
            var report = new SelfTestRunner(driver, root.resolve("out")).run(SelfTestScript.parse("styles", "key Esc\nsample\ndump sample\n"));
            assertTrue(report.ok(), report.toString());
            return driver.dump("sample");
        } finally { driver.stopTimers(); }
    }
}
