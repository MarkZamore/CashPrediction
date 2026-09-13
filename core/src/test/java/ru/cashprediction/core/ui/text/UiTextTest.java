package ru.cashprediction.core.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.Texts;

/**
 * Тексты интерфейса поверх общего каталога: файлы {@code <область>_ru.properties} в UTF-8, собственная подстановка
 * {@code {n}}, строгий и мягкий режимы (решение L13).
 */
class UiTextTest {

    private String savedStrict;

    @BeforeEach
    void rememberStrict() {
        savedStrict = System.getProperty(UiText.STRICT_PROPERTY);
    }

    @AfterEach
    void restoreStrict() {
        if (savedStrict == null) {
            System.clearProperty(UiText.STRICT_PROPERTY);
        } else {
            System.setProperty(UiText.STRICT_PROPERTY, savedStrict);
        }
    }

    @Test
    void readsUtf8AndSubstitutes() {
        assertEquals("Мой план", UiText.get("plan.defaultName"));
        assertEquals("CashPrediction — Пример", UiText.get("main.title", "Пример"));
        assertEquals("CashPrediction — План «Отпуск» *", UiText.get("main.title.dirty", "План «Отпуск»"));
        assertTrue(UiText.has("format.million"));
        assertFalse(UiText.has("нет.такого.ключа"));
        assertEquals(Optional.of("app"), UiText.area("plan.defaultName"));
        assertEquals(Optional.of("model"), UiText.area("money.error.grouping"));
        assertTrue(UiText.keys().contains("client.web"));
        assertEquals(Optional.of("JavaFX {0}"), UiText.template("client.fx"));
        assertEquals(Texts.get("sample.name"), UiText.get("sample.name"));
    }

    @Test
    void substitutionHelpersDelegateToCatalog() {
        assertEquals("a {1} b", UiText.format("{0} b", "a {1}"));
        assertEquals(Set.of(0, 1), UiText.placeholders("{1}: {0}"));
    }

    @Test
    void strictModeThrowsOnMissingKeyAndArgument() {
        System.setProperty(UiText.STRICT_PROPERTY, "true");
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> UiText.get("нет.такого.ключа"));
        assertEquals("Missing text key: нет.такого.ключа", missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> UiText.get("main.title"));
    }

    @Test
    void lenientModeShowsKeyAndKeepsPlaceholder() {
        System.setProperty(UiText.STRICT_PROPERTY, "false");
        assertEquals("!нет.такого.ключа!", UiText.get("нет.такого.ключа"));
        assertEquals("CashPrediction — {0}", UiText.get("main.title"));
    }

    @Test
    void everyAreaIsALocaleSuffixedUtf8File() {
        assertEquals(List.of(), UiText.loadProblems());
        assertEquals(List.of("menu", "toolbar", "status", "hotkeys", "summary", "table", "popup", "chart", "forms-misc",
                "alerts", "buttons", "restore", "forms-plan", "forms-ops", "app"), Texts.UI_AREAS);
        assertTrue(UiText.AREAS.containsAll(Texts.UI_AREAS));
        for (String area : UiText.AREAS) {
            assertTrue(UiText.class.getResource(UiText.RESOURCE_DIR + area + "_ru.properties") != null, area);
            // Файлы без суффикса языка не используются: один набор <область>_ru для всех клиентов.
            assertNull(UiText.class.getResource(UiText.RESOURCE_DIR + area + ".properties"), area);
        }
    }

    @Test
    void pluralsUseCatalogForms() {
        assertEquals("1 месяц", Plurals.count(Plurals.MONTH, 1));
        assertEquals("3 месяца", Plurals.count(Plurals.MONTH, 3));
        assertEquals("11 месяцев", Plurals.count(Plurals.MONTH, 11));
        assertEquals("21 год", Plurals.count(Plurals.YEAR, 21));
        assertEquals("лет", Plurals.form(Plurals.YEAR, 50));
    }
}
