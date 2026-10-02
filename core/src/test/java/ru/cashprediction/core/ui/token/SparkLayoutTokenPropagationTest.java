package ru.cashprediction.core.ui.token;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет размеры строк карточки и спарклайна из §5.1 и передачу в CSS без потери нулевого зазора. */
class SparkLayoutTokenPropagationTest {
    /** Фиксирует именно договорённые значения, а не совпадение двух одинаково ошибочных представлений. */
    @Test
    void lineHeightsAndGapMatchSparkContract() throws Exception {
        assertEquals(20, token("SPARK_HEADER_LINE_HEIGHT"));
        assertEquals(15, token("SPARK_BODY_LINE_HEIGHT"));
        assertEquals(14, token("SPARK_FOOTER_LINE_HEIGHT"));
        assertEquals(0, token("SPARK_CONTENT_GAP"));
        assertEquals(240, token("SPARK_WIDTH"));
        assertEquals(60, token("SPARK_HEIGHT"));
        assertEquals(4, token("CARD_POPUP_GAP"));
    }

    /** Каждое новое свойство должно ровно один раз присутствовать в настоящем блоке :root в пикселях. */
    @ParameterizedTest
    @CsvSource({
            "SPARK_HEADER_LINE_HEIGHT, --cp-spark-header-line-height, 20",
            "SPARK_BODY_LINE_HEIGHT, --cp-spark-body-line-height, 15",
            "SPARK_FOOTER_LINE_HEIGHT, --cp-spark-footer-line-height, 14",
            "SPARK_CONTENT_GAP, --cp-spark-content-gap, 0",
            "CARD_TITLE_LINE_HEIGHT, --cp-card-title-line-height, 15",
            "CARD_VALUE_LINE_HEIGHT, --cp-card-value-line-height, 22",
            "CARD_CAPTION_LINE_HEIGHT, --cp-card-caption-line-height, 15",
            "CARD_CONTENT_GAP, --cp-card-content-gap, 3"
    })
    void webCssPropagatesEachSparkToken(String field, String property, int expected) throws Exception {
        assertEquals(expected, token(field));
        assertRootDeclaration(TokenCss.webCss(), property, expected + "px");
    }

    /** Генерация стабильна; ноль является явным размером, а не отсутствующим значением. */
    @Test
    void zeroGapIsExplicitAndGenerationIsStable() {
        String css = TokenCss.webCss();
        assertEquals(css, TokenCss.webCss());
        assertRootDeclaration(css, "--cp-spark-content-gap", "0px");
    }

    /** Сам тест CSS отвергает пропуск, неверные единицы, комментарий, чужой блок и дубликат. */
    @Test
    void declarationCheckRejectsFalseEvidence() {
        String property = "--cp-spark-content-gap";
        String declaration = property + ": 0px;";
        for (String css : new String[]{
                ":root {}",
                ":root {" + property + ": 0;}",
                ":root {" + property + ": 4px;}",
                ":root {/* " + declaration + " */}",
                ":root {} .spark {" + declaration + "}",
                ":root {" + declaration + declaration + "}"
        }) {
            assertThrows(AssertionError.class, () -> assertRootDeclaration(css, property, "0px"), css);
        }
    }

    /** Читает поле отражением: javac не должен подставлять старое значение константы в тест. */
    private static int token(String field) throws Exception {
        return DesignTokens.class.getField(field).getInt(null);
    }

    /** Проверяет декларацию в сгенерированном плоском CSS, исключая закомментированные совпадения. */
    private static void assertRootDeclaration(String css, String property, String expected) {
        String uncommented = css.replaceAll("(?s)/\\*.*?\\*/", "");
        var roots = Pattern.compile(":root\\s*\\{([^{}]*)}").matcher(uncommented);
        assertTrue(roots.find(), "generated :root required");
        String root = roots.group(1);
        assertFalse(roots.find(), "single generated :root required");
        var declarations = Pattern.compile("(?:^|;)\\s*" + Pattern.quote(property)
                + "\\s*:\\s*([^;]+);?").matcher(root);
        assertTrue(declarations.find(), property);
        assertEquals(expected, declarations.group(1).trim(), property);
        // Позиция после ';' нужна, чтобы не пропустить соседнюю дублирующую декларацию.
        int next = declarations.end();
        assertFalse(Pattern.compile(Pattern.quote(property) + "\\s*:").matcher(root.substring(next)).find(),
                "duplicate " + property);
    }
}
