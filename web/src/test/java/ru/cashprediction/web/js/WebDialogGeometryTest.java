package ru.cashprediction.web.js;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.DesignTokens;
import static org.junit.jupiter.api.Assertions.*;

/** Регрессии геометрии формы и отдельного PNG заголовка без запуска браузера или сервера. */
class WebDialogGeometryTest {
    private static final Path MODULE = Files.isDirectory(Path.of("src/main/resources/web/app"))
            ? Path.of(".") : Path.of("web");

    /** PNG не добавляет строковую базовую линию; фактический DOM проверяет JDK/CDP-стенд ui-parity. */
    @Test void dialogGlyphDoesNotAddTextBaselineSpace() throws Exception {
        String css = Files.readString(MODULE.resolve("src/main/resources/web/app/layout.css"));
        assertTrue(rule(css, ".window-glyph").contains("display: flex"));
        assertTrue(rule(css, ".window-glyph .shared-icon").contains("display: block"));
    }

    /** Две стрелки помещаются в высоту контрола, а не задают её размером PNG. */
    @Test void spinnerStackUsesControlHeight() throws Exception {
        String css = Files.readString(MODULE.resolve("src/main/resources/web/app/layout.css"));
        verifySpinner(css);
        assertEquals(28, DesignTokens.CONTROL_HEIGHT);
        assertEquals(32, 2 * DesignTokens.INLINE_ICON_SIZE);
        assertThrows(AssertionError.class, () -> verifySpinner(css.replace("height: 50%", "height: var(--cp-inline-icon-size)")));
        assertThrows(AssertionError.class, () -> verifySpinner(css.replace("overflow: hidden;", "")));
    }

    /** Конструктор формы использует отдельную краску заголовка, а обычная валюта остаётся текстом. */
    @Test void formUsesHeaderGlyphWithoutChangingInlineCurrency() throws Exception {
        String form = Files.readString(MODULE.resolve("src/main/resources/web/app/render-form.js"));
        String inline = Files.readString(MODULE.resolve("src/main/resources/web/app/icon.js"));
        assertTrue(form.contains("this.header.append(dialogGlyph(this.spec.glyph),"));
        assertTrue(inline.contains("const exact = !explicit && icons[text] && text !== '\\u20bd' ? text : null;"));
        assertFalse(form.contains("SwingTextInputDialog"));
        assertFalse(form.contains("SwingChoiceDialog"));
        assertFalse(form.contains("SwingDialogPane"));
    }

    /** Проверяет локальный контракт CSS; не подменяет измерение браузерного layout. */
    private static void verifySpinner(String css) {
        assertTrue(rule(css, ".spinner-arrows").contains("height: var(--cp-control-height)"));
        assertTrue(rule(css, ".spinner-arrows button").contains("height: 50%"));
        assertTrue(rule(css, ".spinner-arrows button").contains("min-height: 0"));
        assertTrue(rule(css, ".spinner-arrows button").contains("overflow: hidden"));
        assertTrue(rule(css, ".spinner-arrows button .shared-icon").contains("flex-shrink: 0"));
        assertFalse(rule(css, ".spinner-arrows button .shared-icon").contains("height:"));
    }

    /** Читает декларации точного селектора, не принимая соседний селектор за него. */
    private static String rule(String css, String selector) {
        var matcher = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\s*\\{([^}]*)}").matcher(css);
        assertTrue(matcher.find(), selector);
        return matcher.group(1);
    }
}
