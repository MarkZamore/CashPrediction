package ru.cashprediction.core.markdown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.io.AtomicFiles;

/**
 * Чтение и запись {@code CashMemory/settings.md}.
 *
 * <p>Пример файла:</p>
 * <pre>
 * # Настройки CashPrediction
 *
 * - Последний план: Семейный бюджет 2026.md
 * - Недавние планы: Семейный бюджет 2026.md; Сценарий без аренды.md
 * - Хранилище восстановления: реестр
 * - Автосохранение плана: нет
 * - Вид: таблица
 * - Период: 12 месяцев
 * - Показывать доходы: да
 * - Показывать расходы: да
 * - Показывать разовые: да
 * - Показывать пропущенные: нет
 * - Итоги по месяцам: да
 * - Маркеры на графике: да
 * - Столбцы по месяцам: нет
 * - Панель сводки: да
 * </pre>
 *
 * <p>Настройки не так ценны, как план, поэтому чтение никогда не бросает исключений: испорченное или
 * неизвестное значение молча заменяется значением по умолчанию, неизвестные ключи игнорируются.
 * Пустые «Последний план» и «Недавние планы» не записываются. Точка с запятой внутри имени плана
 * записывается как {@code \;}.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class SettingsMarkdown {

    /** Первая строка файла настроек. */
    public static final String TITLE = "# Настройки CashPrediction";

    /** Ключ последнего открытого плана. */
    public static final String KEY_LAST_PLAN = "Последний план";
    /** Ключ списка недавних планов (через «; »). */
    public static final String KEY_RECENT_PLANS = "Недавние планы";
    /** Ключ хранилища восстановления по умолчанию. */
    public static final String KEY_RECOVERY_STORE = "Хранилище восстановления";
    /** Ключ автосохранения плана. */
    public static final String KEY_AUTOSAVE = "Автосохранение плана";
    /** Ключ режима отображения. */
    public static final String KEY_VIEW = "Вид";
    /** Ключ периода отображения. */
    public static final String KEY_PERIOD = "Период";
    /** Ключ фильтра доходов. */
    public static final String KEY_SHOW_INCOME = "Показывать доходы";
    /** Ключ фильтра расходов. */
    public static final String KEY_SHOW_EXPENSE = "Показывать расходы";
    /** Ключ фильтра разовых операций. */
    public static final String KEY_SHOW_ONE_TIME = "Показывать разовые";
    /** Ключ фильтра пропущенных событий. */
    public static final String KEY_SHOW_SKIPPED = "Показывать пропущенные";
    /** Ключ итогов по месяцам. */
    public static final String KEY_MONTH_TOTALS = "Итоги по месяцам";
    /** Ключ маркеров на графике. */
    public static final String KEY_CHART_MARKERS = "Маркеры на графике";
    /** Ключ столбцов по месяцам на графике. */
    public static final String KEY_CHART_BARS = "Столбцы по месяцам";
    /** Ключ панели сводки. */
    public static final String KEY_SUMMARY_PANEL = "Панель сводки";

    /** Разделитель недавних планов при записи. */
    private static final String RECENT_SEPARATOR = "; ";

    private SettingsMarkdown() {
    }

    /**
     * Разбирает текст настроек.
     *
     * @param text содержимое {@code settings.md}; {@code null} или мусор дают настройки по умолчанию
     * @return настройки
     */
    public static AppSettings read(String text) {
        AppSettings settings = AppSettings.defaults();
        if (text == null) {
            return settings;
        }
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        for (String line : t.split("\\r\\n|\\r|\\n")) {
            ListItem item = ListItem.parse(line).orElse(null);
            if (item == null) {
                continue;
            }
            try {
                settings = apply(settings, RuFormats.normalize(item.key()), item.value());
            } catch (IllegalArgumentException e) {
                // Испорченное значение: остаётся значение по умолчанию (или предыдущее из этого же файла).
            }
        }
        return settings;
    }

    private static AppSettings apply(AppSettings s, String key, String value) {
        if (is(key, KEY_LAST_PLAN)) {
            return s.withLastPlan(RuFormats.isEmptyValue(value) ? "" : value);
        }
        if (is(key, KEY_RECENT_PLANS)) {
            return s.withRecentPlans(RuFormats.isEmptyValue(value) ? List.of() : splitRecent(value));
        }
        if (is(key, KEY_RECOVERY_STORE)) {
            return RecoveryStoreKind.parse(value).map(s::withRecoveryStore).orElse(s);
        }
        if (is(key, KEY_AUTOSAVE)) {
            return s.withAutosave(RuFormats.parseBoolean(value));
        }
        if (is(key, KEY_VIEW)) {
            return ViewMode.parse(value).map(s::withView).orElse(s);
        }
        if (is(key, KEY_PERIOD)) {
            return PeriodChoice.parse(value).map(s::withPeriod).orElse(s);
        }
        List<Flag> flags = flags();
        for (Flag flag : flags) {
            if (is(key, flag.key())) {
                return flag.setter().apply(s, RuFormats.parseBoolean(value));
            }
        }
        return s;
    }

    /**
     * Выводит настройки в канонический текст файла.
     *
     * @param settings настройки
     * @return текст {@code settings.md} с одним переводом строки в конце
     */
    public static String write(AppSettings settings) {
        List<String> lines = new ArrayList<>();
        lines.add(TITLE);
        lines.add("");
        if (!settings.lastPlan().isEmpty()) {
            lines.add(new ListItem(KEY_LAST_PLAN, singleLine(settings.lastPlan())).format());
        }
        if (!settings.recentPlans().isEmpty()) {
            lines.add(new ListItem(KEY_RECENT_PLANS, String.join(RECENT_SEPARATOR,
                    settings.recentPlans().stream().map(p -> singleLine(p).replace(";", "\\;")).toList())).format());
        }
        lines.add(new ListItem(KEY_RECOVERY_STORE, settings.recoveryStore().label()).format());
        lines.add(new ListItem(KEY_AUTOSAVE, RuFormats.formatBoolean(settings.autosave())).format());
        lines.add(new ListItem(KEY_VIEW, settings.view().label()).format());
        lines.add(new ListItem(KEY_PERIOD, settings.period().label()).format());
        boolean[] values = {settings.showIncome(), settings.showExpense(), settings.showOneTime(), settings.showSkipped(),
            settings.monthTotals(), settings.chartMarkers(), settings.chartBars(), settings.summaryPanel()};
        List<Flag> flags = flags();
        for (int i = 0; i < flags.size(); i++) {
            lines.add(new ListItem(flags.get(i).key(), RuFormats.formatBoolean(values[i])).format());
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * Загружает настройки из файла. Отсутствующий или нечитаемый файл даёт настройки по умолчанию.
     *
     * @param file путь к {@code settings.md}
     * @return настройки
     */
    public static AppSettings load(Path file) {
        try {
            return Files.isRegularFile(file) ? read(AtomicFiles.readString(file)) : AppSettings.defaults();
        } catch (IOException | RuntimeException e) {
            // Файл занят, нет прав или битая кодировка: работать с настройками по умолчанию лучше, чем не запуститься.
            return AppSettings.defaults();
        }
    }

    /**
     * Атомарно сохраняет настройки в файл.
     *
     * @param file     путь к {@code settings.md}
     * @param settings настройки
     * @throws IOException если запись не удалась
     */
    public static void save(Path file, AppSettings settings) throws IOException {
        AtomicFiles.writeString(file, write(settings));
    }

    /**
     * Логический параметр файла настроек: ключ и способ записать значение в настройки.
     *
     * @param key    ключ в файле
     * @param setter with-метод настроек
     */
    private record Flag(String key, BiFunction<AppSettings, Boolean, AppSettings> setter) {
    }

    /** Логические параметры в порядке вывода в файл. */
    private static List<Flag> flags() {
        return List.of(
                new Flag(KEY_SHOW_INCOME, AppSettings::withShowIncome),
                new Flag(KEY_SHOW_EXPENSE, AppSettings::withShowExpense),
                new Flag(KEY_SHOW_ONE_TIME, AppSettings::withShowOneTime),
                new Flag(KEY_SHOW_SKIPPED, AppSettings::withShowSkipped),
                new Flag(KEY_MONTH_TOTALS, AppSettings::withMonthTotals),
                new Flag(KEY_CHART_MARKERS, AppSettings::withChartMarkers),
                new Flag(KEY_CHART_BARS, AppSettings::withChartBars),
                new Flag(KEY_SUMMARY_PANEL, AppSettings::withSummaryPanel));
    }

    private static boolean is(String normalizedKey, String key) {
        return normalizedKey.equals(RuFormats.normalize(key));
    }

    /** Делит список недавних по «;», не трогая экранированные {@code \;}. */
    private static List<String> splitRecent(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && value.charAt(i + 1) == ';') {
                current.append(';');
                i++;
            } else if (c == ';') {
                result.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().strip());
        return result;
    }

    private static String singleLine(String value) {
        return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').strip();
    }
}
