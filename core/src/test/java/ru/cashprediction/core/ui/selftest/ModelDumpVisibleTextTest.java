package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;
import static org.junit.jupiter.api.Assertions.*;

/** Эталон описывает видимый текст формы, а не внутренние коды и невидимые строки модели. */
class ModelDumpVisibleTextTest {
    @TempDir Path root;

    /** Выбор содержит подпись текущего пункта и все реальные подписи списка. */
    @Test void choiceShowsCaptionInsteadOfPersistedCode() throws Exception {
        var window = run("menu edit.addIncome\n").windows().getLast();
        var choice = window.fields().stream().filter(f -> f.id().equals("recurrenceKind")).findFirst().orElseThrow();
        assertEquals(UiText.get("recurrence.kind.monthly"), choice.text());
        assertTrue(choice.options().contains(choice.text()));
        assertNotEquals("MONTHLY", choice.text());
        assertTrue(window.fields().stream().noneMatch(f -> !f.visible()));
    }

    /** Ошибка и предупреждение включают обязательный значок §6.0, а пустая проблема не получает его. */
    @Test void problemIncludesSeverityGlyphAndPreviewContainsActualLines() throws Exception {
        var window = run("menu edit.addIncome\nfill last amount=0\n").windows().getLast();
        assertTrue(window.problem().startsWith("✖ "));
        var preview = window.fields().stream().filter(f -> f.kind().equals("PREVIEW")).findFirst().orElseThrow();
        assertFalse(preview.options().isEmpty());
        assertEquals(window.preview(), preview.options());
        var valid = run("menu edit.addIncome\nfill last title=Test amount=25000\n").windows().getLast();
        assertEquals("", valid.problem());
    }

    /** Два токена одного ARGB нормализуются одинаково в модели и в настоящем виджете. */
    @Test void whatIfToolbarUsesCanonicalToken() throws Exception {
        var toolbar = run("menu whatIf.income\n").toolbar();
        var item = toolbar.items().stream().filter(i -> i.id().equals("tb.whatIf")).findFirst().orElseThrow();
        assertTrue(item.bold());
        assertEquals(ColorToken.WHATIF.canonical().id(), item.color());
    }

    /** Спарклайн с данными не добавляет строку для отсутствующей надписи «нет данных». */
    @Test void sparklineOmitsUnpaintedEmptyFooter() throws Exception {
        var popup = run("hover card:now\n").popups().getFirst();
        assertEquals("sparkline", popup.kind());
        assertEquals(4, popup.lines().size());
        assertTrue(popup.lines().stream().noneMatch(String::isEmpty));
    }

    /** Проверяет настоящие модели контроллера; никакие дампы клиента не подменяются этими тестами. */
    private UiDump run(String commands) throws Exception {
        Path home = root.resolve(java.util.UUID.randomUUID().toString());
        var environment = AppEnvironment.from(LaunchOptions.parse("--home", home.toString(), "--registry", "memory", "--today", "2026-09-13"));
        var driver = new ModelUiDriver(environment, ClientProfile.fx("25"));
        try {
            var report = new SelfTestRunner(driver, home.resolve("out"))
                    .run(SelfTestScript.parse("visible", "key Esc\nsample\n" + commands + "dump visible\n"));
            assertTrue(report.ok(), report.toString());
            return driver.dump("visible");
        } finally { driver.stopTimers(); }
    }
}
