package ru.cashprediction.core.document;

import java.time.LocalDate;
import java.util.Objects;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.markdown.RuFormats;
import ru.cashprediction.core.model.Plan;

/**
 * Параметры отображения открытого плана: режим, период, фильтры строк, строка поиска и режим «что-если».
 *
 * <p>Это «вид», а не «данные»: ничего отсюда не попадает в файл плана и не делает документ несохранённым.
 * Большая часть полей переживает перезапуск через {@link AppSettings} ({@link #fromSettings}/{@link #applyTo}),
 * а строка поиска и «что-если» живут только в текущей сессии (и в её снимке).</p>
 *
 * <p>Один и тот же record используют JavaFX, Swing и Web, поэтому правила фильтрации ({@link #accepts})
 * и границы периода ({@link #periodEnd}) определены здесь, а не в клиентах: иначе три клиента
 * показывали бы разные строки.</p>
 *
 * <p>Record неизменяем и потокобезопасен; изменение — через {@code with...}-методы.</p>
 *
 * @param mode         режим центральной области: таблица или график
 * @param period       период отображения
 * @param showIncome   показывать доходы
 * @param showExpense  показывать расходы
 * @param showOneTime  показывать разовые операции
 * @param showSkipped  показывать пропущенные события (зачёркнутыми); влияет на расчёт прогноза
 * @param monthTotals  показывать строки итогов по месяцам
 * @param chartMarkers показывать маркеры событий на графике
 * @param chartBars    показывать столбцы доходов и расходов по месяцам на графике
 * @param summaryPanel показывать панель сводки
 * @param filterText   строка поиска по названию, категории и заметке; пустая — без фильтра
 * @param whatIf       параметры «что-если»; влияют на расчёт прогноза
 */
public record ViewState(
        ViewMode mode,
        PeriodChoice period,
        boolean showIncome,
        boolean showExpense,
        boolean showOneTime,
        boolean showSkipped,
        boolean monthTotals,
        boolean chartMarkers,
        boolean chartBars,
        boolean summaryPanel,
        String filterText,
        WhatIf whatIf) {

    /** Заменяет {@code null} значениями по умолчанию. */
    public ViewState {
        mode = mode == null ? ViewMode.TABLE : mode;
        period = period == null ? PeriodChoice.M12 : period;
        filterText = filterText == null ? "" : filterText;
        whatIf = whatIf == null ? WhatIf.NONE : whatIf;
    }

    /**
     * Вид по умолчанию; флаги совпадают с {@link AppSettings#defaults()}.
     *
     * @return таблица, 12 месяцев, доходы/расходы/разовые видны, пропущенные скрыты, итоги по месяцам,
     *         маркеры и панель сводки включены, столбцы выключены, без поиска и без «что-если»
     */
    public static ViewState defaults() {
        return fromSettings(AppSettings.defaults());
    }

    /**
     * Вид из сохранённых настроек приложения. Строка поиска и «что-если» в настройках не хранятся
     * и получают значения по умолчанию: после перезапуска пользователь видит реальный план без скрытых фильтров.
     *
     * @param settings настройки приложения
     * @return вид
     */
    public static ViewState fromSettings(AppSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new ViewState(settings.view(), settings.period(), settings.showIncome(), settings.showExpense(),
                settings.showOneTime(), settings.showSkipped(), settings.monthTotals(), settings.chartMarkers(),
                settings.chartBars(), settings.summaryPanel(), "", WhatIf.NONE);
    }

    /**
     * Переносит сохраняемую часть вида в настройки приложения (остальные настройки не меняются).
     *
     * @param settings текущие настройки
     * @return новые настройки с режимом, периодом и флагами из этого вида
     */
    public AppSettings applyTo(AppSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return settings.withView(mode)
                .withPeriod(period)
                .withShowIncome(showIncome)
                .withShowExpense(showExpense)
                .withShowOneTime(showOneTime)
                .withShowSkipped(showSkipped)
                .withMonthTotals(monthTotals)
                .withChartMarkers(chartMarkers)
                .withChartBars(chartBars)
                .withSummaryPanel(summaryPanel);
    }

    // ------------------------------------------------------------------ вычисления

    /**
     * Последний видимый день периода.
     *
     * <p>Период отсчитывается от «сейчас» ({@code anchor = max(startDate, today)}), как и карточки сводки:
     * «3 месяца» — это ближайшие три месяца, а не первые три месяца давно начатого плана.
     * Результат никогда не выходит за горизонт плана.</p>
     *
     * @param plan   план
     * @param anchor опорная дата «сейчас», обычно {@code Forecast.anchor()}
     * @return {@code anchor + months - 1 день}, но не позже конца плана; для {@link PeriodChoice#ALL} — конец плана
     */
    public LocalDate periodEnd(Plan plan, LocalDate anchor) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(anchor, "anchor");
        LocalDate planEnd = plan.endDate();
        if (period.months() == 0) {
            return planEnd;
        }
        LocalDate end = anchor.plusMonths(period.months()).minusDays(1);
        return end.isAfter(planEnd) ? planEnd : end;
    }

    /**
     * Проходит ли строка фильтры вида.
     *
     * <p>Правила: строка «Начальный баланс» видна всегда (это точка отсчёта таблицы); пропущенные события
     * видны только при {@code showSkipped}; разовые операции скрываются флагом {@code showOneTime};
     * доходы (включая строки «что-если») и расходы — флагами {@code showIncome}/{@code showExpense};
     * строка поиска ищется в названии, категории и заметке без учёта регистра, «ё/е» и лишних пробелов.</p>
     *
     * @param row строка прогноза
     * @return {@code true}, если строку нужно показать
     */
    public boolean accepts(ForecastRow row) {
        Objects.requireNonNull(row, "row");
        if (row.origin() == Origin.START) {
            return true;
        }
        if (row.flags().skipped() && !showSkipped) {
            return false;
        }
        if (row.origin() == Origin.ONE_TIME && !showOneTime) {
            return false;
        }
        if (row.isIncome() && !showIncome) {
            return false;
        }
        if (row.isExpense() && !showExpense) {
            return false;
        }
        String needle = RuFormats.normalize(filterText);
        if (needle.isEmpty()) {
            return true;
        }
        // Нормализуем и строку поиска, и поля: пользователь не должен думать о регистре и «ё».
        return RuFormats.normalize(row.title()).contains(needle)
                || RuFormats.normalize(row.category()).contains(needle)
                || RuFormats.normalize(row.note()).contains(needle);
    }

    /**
     * Меняет ли переход к другому виду сам прогноз (а не только видимую часть).
     *
     * @param other другой вид
     * @return {@code true}, если различаются «что-если» или показ пропущенных событий
     */
    public boolean affectsForecast(ViewState other) {
        Objects.requireNonNull(other, "other");
        return !whatIf.equals(other.whatIf) || showSkipped != other.showSkipped;
    }

    // ------------------------------------------------------------------ with-методы

    /**
     * @param value новый режим
     * @return копия с другим режимом
     */
    public ViewState withMode(ViewMode value) {
        return new ViewState(value, period, showIncome, showExpense, showOneTime, showSkipped, monthTotals,
                chartMarkers, chartBars, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value новый период
     * @return копия с другим периодом
     */
    public ViewState withPeriod(PeriodChoice value) {
        return new ViewState(mode, value, showIncome, showExpense, showOneTime, showSkipped, monthTotals,
                chartMarkers, chartBars, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value показывать ли доходы
     * @return копия с другим фильтром «Доходы»
     */
    public ViewState withShowIncome(boolean value) {
        return new ViewState(mode, period, value, showExpense, showOneTime, showSkipped, monthTotals,
                chartMarkers, chartBars, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value показывать ли расходы
     * @return копия с другим фильтром «Расходы»
     */
    public ViewState withShowExpense(boolean value) {
        return new ViewState(mode, period, showIncome, value, showOneTime, showSkipped, monthTotals,
                chartMarkers, chartBars, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value показывать ли разовые операции
     * @return копия с другим фильтром «Разовые»
     */
    public ViewState withShowOneTime(boolean value) {
        return new ViewState(mode, period, showIncome, showExpense, value, showSkipped, monthTotals,
                chartMarkers, chartBars, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value показывать ли пропущенные события
     * @return копия с другим фильтром «Пропущенные»
     */
    public ViewState withShowSkipped(boolean value) {
        return new ViewState(mode, period, showIncome, showExpense, showOneTime, value, monthTotals,
                chartMarkers, chartBars, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value показывать ли итоги по месяцам
     * @return копия с другим признаком «Итоги по месяцам»
     */
    public ViewState withMonthTotals(boolean value) {
        return new ViewState(mode, period, showIncome, showExpense, showOneTime, showSkipped, value,
                chartMarkers, chartBars, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value показывать ли маркеры на графике
     * @return копия с другим признаком «Маркеры»
     */
    public ViewState withChartMarkers(boolean value) {
        return new ViewState(mode, period, showIncome, showExpense, showOneTime, showSkipped, monthTotals,
                value, chartBars, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value показывать ли столбцы по месяцам
     * @return копия с другим признаком «Столбцы по месяцам»
     */
    public ViewState withChartBars(boolean value) {
        return new ViewState(mode, period, showIncome, showExpense, showOneTime, showSkipped, monthTotals,
                chartMarkers, value, summaryPanel, filterText, whatIf);
    }

    /**
     * @param value показывать ли панель сводки
     * @return копия с другим признаком «Панель сводки»
     */
    public ViewState withSummaryPanel(boolean value) {
        return new ViewState(mode, period, showIncome, showExpense, showOneTime, showSkipped, monthTotals,
                chartMarkers, chartBars, value, filterText, whatIf);
    }

    /**
     * @param value новая строка поиска ({@code null} — пустая)
     * @return копия с другой строкой поиска
     */
    public ViewState withFilterText(String value) {
        return new ViewState(mode, period, showIncome, showExpense, showOneTime, showSkipped, monthTotals,
                chartMarkers, chartBars, summaryPanel, value, whatIf);
    }

    /**
     * @param value новые параметры «что-если» ({@code null} — выключено)
     * @return копия с другим «что-если»
     */
    public ViewState withWhatIf(WhatIf value) {
        return new ViewState(mode, period, showIncome, showExpense, showOneTime, showSkipped, monthTotals,
                chartMarkers, chartBars, summaryPanel, filterText, value);
    }
}
