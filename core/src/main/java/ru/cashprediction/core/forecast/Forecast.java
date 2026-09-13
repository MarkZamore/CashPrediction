package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;

/**
 * Результат прогноза: строки таблицы, ежедневная серия баланса для графика, сводка и предупреждения.
 *
 * <p>Создаётся только {@link ForecastEngine}. Record неизменяем и потокобезопасен.</p>
 *
 * <p><b>Массив {@code dailyBalance}.</b> Массивы в Java изменяемы, поэтому конструктор делает защитную копию,
 * а метод доступа {@link #dailyBalance()} каждый раз возвращает новую копию. Для частого чтения (график,
 * калькулятор цели) без копирования используйте {@link #balanceAt(LocalDate)}, {@link #balanceMinorAt(int)}
 * и {@link #dayCount()}. По той же причине {@link #equals(Object)} и {@link #hashCode()} переопределены
 * и сравнивают массив по содержимому.</p>
 *
 * @param plan         план, по которому построен прогноз
 * @param whatIf       параметры «что-если», с которыми построен прогноз
 * @param today        «сегодня» на момент расчёта
 * @param anchor       опорная дата {@code max(plan.startDate, today)}
 * @param rows         строки таблицы по порядку; первая — «Начальный баланс»
 * @param dailyBalance баланс на конец каждого дня: {@code dailyBalance[i]} относится к {@code dailyStart.plusDays(i)};
 *                     длина равна числу дней горизонта включительно
 * @param dailyStart   первый день серии (дата начала плана)
 * @param summary      сводка
 * @param warnings     предупреждения в порядке обнаружения
 */
public record Forecast(
        Plan plan,
        WhatIf whatIf,
        LocalDate today,
        LocalDate anchor,
        List<ForecastRow> rows,
        long[] dailyBalance,
        LocalDate dailyStart,
        ForecastSummary summary,
        List<Warning> warnings) {

    /** Проверяет поля, копирует списки и массив. */
    public Forecast {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(whatIf, "whatIf");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(dailyStart, "dailyStart");
        Objects.requireNonNull(summary, "summary");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        // Защитная копия: вызывающий код не должен иметь возможности поменять серию уже созданного прогноза.
        dailyBalance = Objects.requireNonNull(dailyBalance, "dailyBalance").clone();
        if (dailyBalance.length == 0) {
            throw new IllegalArgumentException("Ежедневная серия баланса не может быть пустой");
        }
    }

    /**
     * Копия ежедневной серии баланса в копейках.
     *
     * @return новый массив при каждом вызове (изменение его не влияет на прогноз)
     */
    @Override
    public long[] dailyBalance() {
        return dailyBalance.clone();
    }

    /** @return число дней горизонта (длина ежедневной серии) */
    public int dayCount() {
        return dailyBalance.length;
    }

    /**
     * Баланс на конец дня по номеру дня без копирования массива.
     *
     * @param dayIndex номер дня от {@link #dailyStart()}, {@code 0..dayCount()-1}
     * @return баланс в копейках
     */
    public long balanceMinorAt(int dayIndex) {
        return dailyBalance[dayIndex];
    }

    /** @return первый день прогноза (дата начала плана) */
    public LocalDate startDate() {
        return dailyStart;
    }

    /** @return последний день прогноза */
    public LocalDate endDate() {
        return dailyStart.plusDays(dailyBalance.length - 1L);
    }

    /** @return баланс на дату начала плана до событий этого дня */
    public Money startBalance() {
        return summary.startBalance();
    }

    /** @return баланс на конец последнего дня */
    public Money endBalance() {
        return new Money(dailyBalance[dailyBalance.length - 1]);
    }

    /**
     * Баланс на конец дня.
     *
     * @param date день
     * @return баланс; для дней до начала плана — начальный баланс, после конца горизонта — конечный
     */
    public Money balanceAt(LocalDate date) {
        return BalanceSeries.balanceAt(dailyStart, dailyBalance, summary.startBalance(), date);
    }

    /**
     * Первый день не раньше {@code from}, когда баланс на конец дня не меньше порога.
     *
     * @param from      с какого дня искать
     * @param threshold порог
     * @return день или пусто, если в горизонте такого дня нет
     */
    public Optional<LocalDate> firstDateAtLeast(LocalDate from, Money threshold) {
        long t = threshold.minor();
        return BalanceSeries.firstMatch(dailyStart, dailyBalance, from, v -> v >= t);
    }

    /**
     * Первый день не раньше {@code from}, когда баланс на конец дня меньше порога.
     *
     * @param from      с какого дня искать
     * @param threshold порог
     * @return день или пусто, если в горизонте такого дня нет
     */
    public Optional<LocalDate> firstDateBelow(LocalDate from, Money threshold) {
        long t = threshold.minor();
        return BalanceSeries.firstMatch(dailyStart, dailyBalance, from, v -> v < t);
    }

    /**
     * Строки в интервале дат включительно, для таблицы выбранного периода и экспорта.
     *
     * @param from первый день
     * @param to   последний день
     * @return неизменяемый список строк; строка «Начальный баланс» входит всегда, когда {@code from <= startDate}
     */
    public List<ForecastRow> rowsBetween(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        List<ForecastRow> result = new ArrayList<>();
        for (ForecastRow row : rows) {
            boolean inside = !row.date().isBefore(from) && !row.date().isAfter(to);
            // Начальный баланс — точка отсчёта таблицы: без него период «с начала» читался бы с середины.
            boolean startRow = row.origin() == Origin.START && !from.isAfter(dailyStart);
            if (inside || startRow) {
                result.add(row);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Ищет строку по её идентификатору (для восстановления выделения в таблице).
     *
     * @param rowId идентификатор из {@link ForecastRow#rowId()}
     * @return строка, если есть
     */
    public Optional<ForecastRow> findRow(String rowId) {
        return rows.stream().filter(r -> r.rowId().equals(rowId)).findFirst();
    }

    /** Сравнивает прогнозы, включая содержимое ежедневной серии. */
    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Forecast other
                && plan.equals(other.plan)
                && whatIf.equals(other.whatIf)
                && today.equals(other.today)
                && anchor.equals(other.anchor)
                && rows.equals(other.rows)
                && Arrays.equals(dailyBalance, other.dailyBalance)
                && dailyStart.equals(other.dailyStart)
                && summary.equals(other.summary)
                && warnings.equals(other.warnings);
    }

    /** Хеш с учётом содержимого ежедневной серии. */
    @Override
    public int hashCode() {
        return Objects.hash(plan, whatIf, today, anchor, rows, Arrays.hashCode(dailyBalance), dailyStart, summary, warnings);
    }

    /** Краткое описание без распечатки всех строк и дней (их могут быть десятки тысяч). */
    @Override
    public String toString() {
        return "Forecast[plan=" + plan.name() + ", " + dailyStart + ".." + endDate() + ", rows=" + rows.size()
                + ", warnings=" + warnings.size() + ", end=" + endBalance() + "]";
    }
}
