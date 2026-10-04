package ru.cashprediction.parity.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.token.DesignTokens;
import ru.cashprediction.parity.launch.ReactorLayout;

/** Дополнительная функциональная DOM-проба icons-only; не подменяет проверку настоящего сервера. */
@EnabledIfSystemProperty(named = "parity.iconsProof", matches = "true")
class SharedIconsBrowserTest {
    /** Требует новый полный отчёт браузера с фокусом, доступностью, текстом и девятью вариантами PNG. */
    @Test
    @Timeout(180)
    void iconsOnlyProducesFreshCompleteDomEvidence(@TempDir Path output) throws Exception {
        Path root = ReactorLayout.fromSystemProperties().root();
        assertTrue(Files.isRegularFile(root.resolve("web/src/test/resources/ui/icon-regression-probe.js")),
                "Repository root required: " + root);
        Path report = output.resolve("shared-icons.json");
        assertFalse(Files.exists(report), "Prior evidence cannot satisfy this test");
        // Стенд браузера принадлежит ui-parity; Web не получает обратной тестовой зависимости.
        Path webTests = root.resolve("web/target/test-classes");
        assertTrue(Files.isRegularFile(webTests.resolve("ru/cashprediction/web/js/BrowserFixtureProbe.class")),
                "Build Web tests before the browser gate");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {
                webTests.toUri().toURL(), root.resolve("web/target/classes").toUri().toURL()
        }, SharedIconsBrowserTest.class.getClassLoader())) {
            try {
                loader.loadClass("ru.cashprediction.web.js.BrowserFixtureProbe")
                        .getMethod("main", String[].class)
                        .invoke(null, (Object) new String[] {root.toString(), output.toString(), "icons-only"});
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }
        assertTrue(Files.isRegularFile(report), "Exit without new DOM evidence is not proof");
        Map<String, Object> result = JsonParser.parseObject(Files.readString(report));
        List<?> checks = assertInstanceOf(List.class, result.get("checks"));
        Set<String> expected = expectedChecks();
        assertEquals(42, expected.size());
        assertEquals(expected.size(), checks.size(), "All 42 DOM checks must execute");
        Set<String> actual = new HashSet<>();
        for (Object item : checks) {
            Map<?, ?> check = assertInstanceOf(Map.class, item);
            String name = assertInstanceOf(String.class, check.get("name"));
            assertTrue(actual.add(name), "Duplicate DOM check: " + name);
            assertEquals(Boolean.TRUE, check.get("passed"), name);
        }
        assertEquals(expected, actual, "Missing or unexpected DOM checks");
        Map<?, ?> sizes = assertInstanceOf(Map.class, result.get("sizes"));
        assertEquals(Set.of("inline", "form", "alert", "spinner"), sizes.keySet());
        for (String kind : List.of("inline", "form", "alert", "spinner")) {
            int expectedSize = switch (kind) {
                case "form" -> DesignTokens.DIALOG_ICON_SIZE;
                case "alert" -> DesignTokens.ALERT_ICON_SIZE;
                default -> DesignTokens.INLINE_ICON_SIZE;
            };
            Map<?, ?> size = assertInstanceOf(Map.class, sizes.get(kind));
            for (String dimension : List.of("width", "height")) {
                Number measured = assertInstanceOf(Number.class, size.get(dimension));
                assertEquals(0, new BigDecimal(measured.toString()).compareTo(BigDecimal.valueOf(expectedSize)),
                        kind + ": " + dimension);
            }
        }
    }

    /** Закрытый набор имён существующей пробы не позволяет принять пустой или частичный отчёт. */
    private static Set<String> expectedChecks() {
        Set<String> names = new HashSet<>(List.of("button caption and tooltip accessibility",
                "radio paints dot with stable logical check", "checkbox paints check",
                "radio and check dumps retain checked semantics", "menu accessible name is visible caption",
                "embedded prose and currency preserved", "only explicit leading position decorated",
                "explicit details suffix uses shared image", "currency is text", "unknown color defaults to original",
                "unavailable variant defaults to original", "unknown glyph stays absent",
                "spinner restores input focus and commits step", "disabled spinner preserves focus and value",
                "readonly spinner preserves focus and value", "tooltip uses shared light PNG and semantic text",
                "recent filename tooltip remains user text", "all shared PNGs actually loaded",
                "explicit positions preserve other glyphs and currency", "invalid positions never replace text",
                "empty positions preserve even a glyph-only value",
                "status embedded save marks use colored PNGs", "status filenames and descriptions remain text",
                "rule preview shift and edit marks use colored PNGs", "rule preview retains selection and activation",
                "table header tooltip paints all service marks",
                "multiline tooltip decorates only declared service positions",
                "unstructured table tooltip preserves multiline user text"));
        for (int index = 0; index < 5; index++) names.add("recent and preview preserve user text " + index);
        for (String token : List.of("ACCENT", "WHATIF", "EXPENSE", "INCOME", "TEXT_PRIMARY", "TEXT_MUTED",
                "WARN", "TOOLTIP_TEXT", "TEXT_PAST")) names.add("shared variant " + token);
        return Set.copyOf(names);
    }
}
