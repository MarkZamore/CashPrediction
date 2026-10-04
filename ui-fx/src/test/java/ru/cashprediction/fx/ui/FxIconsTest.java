package ru.cashprediction.fx.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.core.ui.token.ColorToken;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет источник изображений и разделение декораций без запуска JavaFX toolkit. */
class FxIconsTest {
    /** Отдельные значки и последовательности отметок читаются как ключи общего каталога. */
    @Test void pureGlyphsAndMarksUseSharedManifest() {
        for (String key : UiIcons.manifest().keySet()) if (key.codePointCount(0, key.length()) == 1) {
            assertEquals(List.of(new FxIcons.Part(key, true)), FxIcons.parts(key, false));
            assertTrue(UiIcons.png(key).isPresent());
        }
        assertEquals(3, FxIcons.parts("\u270e \u2192 \u0394", true).stream().filter(FxIcons.Part::icon).count());
    }

    /** Валюта, вопрос в предложении и ссылки на меню не превращаются в графику. */
    @Test void ordinaryProseAndCurrencyStayText() {
        for (String text : List.of("100 \u20bd", "\u20bd 100", "Open \u2192 File", "Goal?", "Title \u0394 value"))
            assertEquals(List.of(new FxIcons.Part(text, false)), FxIcons.parts(text, false));
        assertTrue(FxIcons.parts("", false).isEmpty());
    }

    /** Декоративные префиксы, стрелки раскрытия и отметки состояния не попадают в рисуемый текст. */
    @Test void decoratedLabelsPreserveLogicalText() {
        for (String text : List.of("\u26a0 Problem", "Details \u25b8", "\u2039 Back", "Next \u203a",
                "Registry \u2713 10:00 | XML \u2717", "\n  \u270e Changed")) {
            List<FxIcons.Part> parts = FxIcons.parts(text, false);
            assertTrue(parts.stream().anyMatch(FxIcons.Part::icon), text);
            assertEquals(text, parts.stream().map(FxIcons.Part::text).reduce("", String::concat));
        }
    }

    /** Фоновая графика Region скина содержит исходные байты общего PNG. */
    @Test void skinBackgroundUsesSamePhysicalPng() {
        for (String key : List.of("\u25be", "\u25b8", "\u2191", "\u2713", "\u25cf")) {
            String css = FxIcons.imageCss(key);
            String prefix = "data:image/png;base64,";
            int begin = css.indexOf(prefix) + prefix.length();
            int end = css.indexOf("'", begin);
            assertArrayEquals(UiIcons.png(key).orElseThrow(), java.util.Base64.getDecoder().decode(css.substring(begin, end)));
            assertTrue(css.contains("background-repeat: no-repeat"));
        }
    }

    /** Заголовки, сообщения, календарь и таблица подключены к единственному адаптеру. */
    @Test void renderingSourcesUseSharedAdapter() throws Exception {
        Path root = Path.of(System.getProperty("fx.basedir"), "src/main/java/ru/cashprediction/fx/ui");
        String helper = Files.readString(root.resolve("FxIcons.java"));
        assertTrue(helper.contains("UiIcons.png(key, color)"));
        assertTrue(helper.contains("UiIcons.applicationPng()"));
        assertTrue(helper.contains("ContentDisplay.GRAPHIC_ONLY"));
        assertTrue(helper.contains("cp.logicalText"));
        assertFalse(helper.contains("getResourceAsStream"));
        assertFalse(helper.contains("java.awt"));
        for (String file : List.of("FxApp.java", "FxUiPort.java", "FxAlerts.java", "FxStartupErrors.java",
                "FxFormDialog.java", "FxFieldWidgets.java", "FxToolbar.java", "FxMenus.java", "FxChartCanvas.java"))
            assertTrue(Files.readString(root.resolve(file)).contains("FxIcons."), file);
        String table = Files.readString(root.resolve("FxTable.java"));
        assertTrue(table.contains("FxIcons.tableGraphic"));
        assertTrue(table.contains("COLUMN_MARKS"));
        assertTrue(table.contains("cp.paintText"));
        assertTrue(Files.readString(root.resolve("FxUiDumper.java")).contains("cp.logicalText"));
    }

    /** Цветовые варианты скина используют байты ядра, включая безопасный запасной вариант. */
    @Test void coloredSkinBackgroundUsesSharedVariant() {
        for (ColorToken color : List.of(ColorToken.ACCENT, ColorToken.WHATIF, ColorToken.EXPENSE,
                ColorToken.INCOME, ColorToken.TEXT_PRIMARY, ColorToken.TEXT_MUTED, ColorToken.WARN,
                ColorToken.TOOLTIP_TEXT, ColorToken.TEXT_PAST, ColorToken.BORDER)) {
            String css = FxIcons.imageCss("\u2713", color);
            String prefix = "data:image/png;base64,";
            int begin = css.indexOf(prefix) + prefix.length();
            assertArrayEquals(UiIcons.png("\u2713", color).orElseThrow(),
                    java.util.Base64.getDecoder().decode(css.substring(begin, css.indexOf("'", begin))));
        }
    }
}
