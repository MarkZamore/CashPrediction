package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.format.DashFreeOutput;

/**
 * Тесты файла настроек: канонический вид, круговое преобразование, терпимость к мусору.
 */
class SettingsMarkdownTest {

    /** Пример из раздела 4.5 плана проекта с двумя ключами, добавленными уточнениями. */
    private static final String SAMPLE = """
            # Настройки CashPrediction

            - Последний план: Семейный бюджет 2026.md
            - Недавние планы: Семейный бюджет 2026.md; Сценарий без аренды.md
            - Хранилище восстановления: реестр
            - Автосохранение плана: нет
            - Вид: таблица
            - Период: 12 месяцев
            - Показывать доходы: да
            - Показывать расходы: да
            - Показывать разовые: да
            - Показывать пропущенные: нет
            - Итоги по месяцам: да
            - Маркеры на графике: да
            - Столбцы по месяцам: нет
            - Панель сводки: да
            """;

    @Test
    void readsSampleAndWritesItBack() {
        AppSettings settings = SettingsMarkdown.read(SAMPLE);
        assertEquals("Семейный бюджет 2026.md", settings.lastPlan());
        assertEquals(List.of("Семейный бюджет 2026.md", "Сценарий без аренды.md"), settings.recentPlans());
        assertEquals(AppSettings.defaults().withPlanOpened("Сценарий без аренды.md").withPlanOpened("Семейный бюджет 2026.md"), settings);
        assertEquals(SAMPLE, SettingsMarkdown.write(settings));
    }

    @Test
    void defaultsOmitEmptyPlanLines() {
        assertEquals("""
                # Настройки CashPrediction

                - Хранилище восстановления: реестр
                - Автосохранение плана: нет
                - Вид: таблица
                - Период: 12 месяцев
                - Показывать доходы: да
                - Показывать расходы: да
                - Показывать разовые: да
                - Показывать пропущенные: нет
                - Итоги по месяцам: да
                - Маркеры на графике: да
                - Столбцы по месяцам: нет
                - Панель сводки: да
                """, SettingsMarkdown.write(AppSettings.defaults()));
    }

    @Test
    void nonDefaultSettingsRoundTrip() {
        AppSettings settings = new AppSettings("C:\\Планы\\a;b.md", List.of("C:\\Планы\\a;b.md", "второй.md"),
                RecoveryStoreKind.XML, true, ViewMode.CHART, PeriodChoice.ALL,
                false, false, false, true, false, false, true, false);
        String text = SettingsMarkdown.write(settings);
        assertTrue(text.contains("- Недавние планы: C:\\Планы\\a\\;b.md; второй.md\n"), text);
        assertTrue(text.contains("- Период: Весь горизонт\n"), text);
        assertTrue(text.contains("- Хранилище восстановления: XML\n"), text);
        assertEquals(settings, SettingsMarkdown.read(text));
    }

    /** Решение 2026-09-14: settings.md со всеми значениями пишется только с дефисом-минусом и читается обратно. */
    @Test
    void writtenSettingsHaveNoDashesAndReadBack() {
        List<AppSettings> all = new ArrayList<>(List.of(AppSettings.defaults(), SettingsMarkdown.read(SAMPLE)));
        for (RecoveryStoreKind store : RecoveryStoreKind.values()) {
            for (ViewMode view : ViewMode.values()) {
                for (PeriodChoice period : PeriodChoice.values()) {
                    all.add(new AppSettings("C:\\Планы\\a;b.md", List.of("C:\\Планы\\a;b.md", "второй-план.md"),
                            store, true, view, period, false, true, false, true, false, true, false, true));
                }
            }
        }
        for (AppSettings settings : all) {
            String text = SettingsMarkdown.write(settings);
            DashFreeOutput.assertNoDashes("settings.md", text);
            assertEquals(settings, SettingsMarkdown.read(text), text);
        }
    }

    @Test
    void everyPeriodRoundTrips() {
        for (PeriodChoice period : PeriodChoice.values()) {
            AppSettings settings = AppSettings.defaults().withPeriod(period);
            assertEquals(settings, SettingsMarkdown.read(SettingsMarkdown.write(settings)), period.label());
        }
    }

    @Test
    void garbageFallsBackToDefaults() {
        assertEquals(AppSettings.defaults(), SettingsMarkdown.read(null));
        assertEquals(AppSettings.defaults(), SettingsMarkdown.read(""));
        assertEquals(AppSettings.defaults(), SettingsMarkdown.read("\u0000\u0001 бинарный мусор |||| ###"));
        assertEquals(AppSettings.defaults(), SettingsMarkdown.read("""
                мусор
                - Вид: радуга
                - Период: вечность
                - Показывать доходы: может быть
                - Хранилище восстановления: облако
                - Автосохранение плана:
                - Недавние планы: -
                - Последний план: -
                """));
    }

    @Test
    void unknownKeysAreIgnored() {
        AppSettings settings = SettingsMarkdown.read(SAMPLE + "- Цвет: синий\n- Шрифт: крупный\n");
        assertEquals(SettingsMarkdown.read(SAMPLE), settings);
    }

    @Test
    void tolerantValuesAndLineEndings() {
        String text = "\uFEFF# настройки\r\n\r\n* вид : ГРАФИК\r\n- период: 6 мес\r\n- Показывать пропущенные: YES\r\n"
                + "-  хранилище   восстановления: registry\r\n- Автосохранение плана: 1\r\n";
        AppSettings settings = SettingsMarkdown.read(text);
        assertEquals(ViewMode.CHART, settings.view());
        assertEquals(PeriodChoice.M6, settings.period());
        assertTrue(settings.showSkipped());
        assertTrue(settings.autosave());
        assertEquals(RecoveryStoreKind.REGISTRY, settings.recoveryStore());
    }

    @Test
    void recentPlansAreDedupedAndCapped() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            names.add("план " + i + ".md");
        }
        String text = "- Недавние планы: План 1.md; " + String.join("; ", names) + ";;\n";
        AppSettings settings = SettingsMarkdown.read(text);
        assertEquals(AppSettings.MAX_RECENT, settings.recentPlans().size());
        assertEquals("План 1.md", settings.recentPlans().get(0));
        assertEquals("план 10.md", settings.recentPlans().get(9));
    }

    @Test
    void loadAndSaveFiles(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.md");
        assertEquals(AppSettings.defaults(), SettingsMarkdown.load(file), "нет файла - умолчания");
        AppSettings settings = AppSettings.defaults().withView(ViewMode.CHART).withPlanOpened("a.md");
        SettingsMarkdown.save(file, settings);
        assertEquals(SettingsMarkdown.write(settings), Files.readString(file));
        assertEquals(settings, SettingsMarkdown.load(file));
        assertFalse(SettingsMarkdown.load(dir).autosave(), "папка вместо файла - умолчания");
    }
}
