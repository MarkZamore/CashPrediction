package ru.cashprediction.core.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Настройки приложения, общие для трёх клиентов и хранимые в {@code CashMemory/settings.md}.
 *
 * <p>Здесь только то, что имеет смысл между запусками: какой план открыть, недавние планы, вид и фильтры.
 * Геометрия окон сюда не входит — она живёт только в снимке сессии.</p>
 *
 * <p>Record неизменяем и потокобезопасен; изменение — через {@code with...}-методы.</p>
 *
 * @param lastPlan      последний открытый план (имя файла в CashMemory или полный путь); пустая строка — нет
 * @param recentPlans   недавние планы, самый свежий первым, без повторов, не больше {@link #MAX_RECENT}
 * @param recoveryStore хранилище, предлагаемое по умолчанию при восстановлении после сбоя
 * @param autosave      сохранять ли план автоматически после каждого изменения
 * @param view          режим центральной области: таблица или график
 * @param period        период отображения
 * @param showIncome    показывать доходы
 * @param showExpense   показывать расходы
 * @param showOneTime   показывать разовые операции
 * @param showSkipped   показывать пропущенные события (зачёркнутыми)
 * @param monthTotals   показывать строки итогов по месяцам в таблице
 * @param chartMarkers  показывать маркеры событий на графике
 * @param chartBars     показывать столбцы доходов и расходов по месяцам на графике
 * @param summaryPanel  показывать панель сводки над таблицей или графиком
 */
public record AppSettings(
        String lastPlan,
        List<String> recentPlans,
        RecoveryStoreKind recoveryStore,
        boolean autosave,
        ViewMode view,
        PeriodChoice period,
        boolean showIncome,
        boolean showExpense,
        boolean showOneTime,
        boolean showSkipped,
        boolean monthTotals,
        boolean chartMarkers,
        boolean chartBars,
        boolean summaryPanel) {

    /** Наибольшее число недавних планов в меню «Недавние». */
    public static final int MAX_RECENT = 10;

    /**
     * Нормализует значения: {@code null} заменяется значениями по умолчанию, из списка недавних удаляются
     * пустые строки и повторы (без учёта регистра, как в файловой системе Windows), список обрезается до
     * {@link #MAX_RECENT}.
     */
    public AppSettings {
        lastPlan = lastPlan == null ? "" : lastPlan.strip();
        recentPlans = normalizeRecent(recentPlans);
        recoveryStore = recoveryStore == null ? RecoveryStoreKind.REGISTRY : recoveryStore;
        view = view == null ? ViewMode.TABLE : view;
        period = period == null ? PeriodChoice.M12 : period;
    }

    /**
     * Настройки по умолчанию для первого запуска.
     *
     * @return настройки: нет последнего плана, реестр, без автосохранения, таблица, 12 месяцев,
     *         доходы/расходы/разовые видны, пропущенные скрыты, итоги по месяцам, маркеры и панель сводки включены
     */
    public static AppSettings defaults() {
        return new AppSettings("", List.of(), RecoveryStoreKind.REGISTRY, false, ViewMode.TABLE, PeriodChoice.M12,
                true, true, true, false, true, true, false, true);
    }

    private static List<String> normalizeRecent(List<String> source) {
        if (source == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String entry : source) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String value = entry.strip();
            if (seen.add(value.toLowerCase(Locale.ROOT)) && result.size() < MAX_RECENT) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Отмечает открытие плана: он становится последним и перемещается в начало списка недавних.
     *
     * @param plan имя файла плана или путь
     * @return новые настройки
     */
    public AppSettings withPlanOpened(String plan) {
        if (plan == null || plan.isBlank()) {
            return this;
        }
        List<String> recent = new ArrayList<>();
        recent.add(plan);
        recent.addAll(recentPlans);
        return new AppSettings(plan, recent, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /**
     * Убирает план из недавних (например, файл удалён или переименован). Если он был последним,
     * «последний план» очищается.
     *
     * @param plan имя файла плана или путь
     * @return новые настройки
     */
    public AppSettings withRecentPlanRemoved(String plan) {
        if (plan == null) {
            return this;
        }
        List<String> recent = recentPlans.stream().filter(p -> !p.equalsIgnoreCase(plan.strip())).toList();
        String last = lastPlan.equalsIgnoreCase(plan.strip()) ? "" : lastPlan;
        return new AppSettings(last, recent, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим последним планом */
    public AppSettings withLastPlan(String value) {
        return new AppSettings(value, recentPlans, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим списком недавних планов */
    public AppSettings withRecentPlans(List<String> value) {
        return new AppSettings(lastPlan, value, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим хранилищем восстановления по умолчанию */
    public AppSettings withRecoveryStore(RecoveryStoreKind value) {
        return new AppSettings(lastPlan, recentPlans, value, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим признаком автосохранения */
    public AppSettings withAutosave(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, value, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим режимом отображения */
    public AppSettings withView(ViewMode value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, value, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим периодом отображения */
    public AppSettings withPeriod(PeriodChoice value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, value, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим фильтром «Доходы» */
    public AppSettings withShowIncome(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, period, value, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим фильтром «Расходы» */
    public AppSettings withShowExpense(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, period, showIncome, value,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим фильтром «Разовые» */
    public AppSettings withShowOneTime(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, period, showIncome, showExpense,
                value, showSkipped, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим фильтром «Пропущенные» */
    public AppSettings withShowSkipped(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, value, monthTotals, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим признаком «Итоги по месяцам» */
    public AppSettings withMonthTotals(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, value, chartMarkers, chartBars, summaryPanel);
    }

    /** @return копия с другим признаком «Маркеры на графике» */
    public AppSettings withChartMarkers(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, value, chartBars, summaryPanel);
    }

    /** @return копия с другим признаком «Столбцы по месяцам» */
    public AppSettings withChartBars(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, value, summaryPanel);
    }

    /** @return копия с другим признаком «Панель сводки» */
    public AppSettings withSummaryPanel(boolean value) {
        return new AppSettings(lastPlan, recentPlans, recoveryStore, autosave, view, period, showIncome, showExpense,
                showOneTime, showSkipped, monthTotals, chartMarkers, chartBars, value);
    }
}
