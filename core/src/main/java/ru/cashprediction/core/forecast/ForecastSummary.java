package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.model.Money;

/**
 * Сводка прогноза для панели карточек: «Сейчас», «через 1/3/6/12 мес.», «Мин (дата)», «Первый минус»,
 * «Средний итог/мес», «Цель: дата», а также итоги по месяцам.
 *
 * <p>Все «взгляды вперёд» считаются от {@code anchor = max(startDate, today)}: если дата начала плана
 * ушла в прошлое, «через 3 месяца» означает три месяца от сегодняшнего дня, а не от давней даты начала.</p>
 *
 * <p>Record неизменяем и потокобезопасен: словари копируются в неизменяемые {@link LinkedHashMap}
 * с сохранением порядка ключей.</p>
 *
 * @param startBalance          баланс на дату начала плана (до событий этого дня)
 * @param endBalance            баланс на конец последнего дня горизонта
 * @param anchor                опорная дата «сейчас»: {@code max(startDate, today)}
 * @param balanceAfterMonths    баланс на {@code anchor.plusMonths(k)} для k из {@link #MONTH_MARKS};
 *                              ключ присутствует, только если эта дата не позже конца горизонта
 * @param totalIncome           сумма всех доходов горизонта (включая строки «что-если»), без пропущенных
 * @param totalExpense          сумма всех расходов горизонта (положительное число), без пропущенных
 * @param averageMonthlyNet     средний итог месяца: {@code (endBalance - startBalance) / max(1, дней/30.4375)},
 *                              округлённый HALF_UP до копейки; «дней» — число дней горизонта включительно
 * @param minBalance            минимальный баланс на конец дня в интервале {@code [anchor, конец]}
 *                              (если {@code anchor} позже конца — конечный баланс)
 * @param minBalanceDate        первая дата, когда достигается {@code minBalance}
 * @param firstNegativeDate     первая дата в {@code [anchor, конец]} с отрицательным балансом
 * @param firstBelowCushionDate первая дата в {@code [anchor, конец]} с балансом ниже подушки (пусто при нулевой подушке)
 * @param goalReachDate         дата достижения цели плана (см. {@link GoalCalculator#reachDate}); пусто, если цели нет
 *                              или она не достигается
 * @param byMonth               итоги каждого месяца горизонта по порядку, включая месяцы без событий
 */
public record ForecastSummary(
        Money startBalance,
        Money endBalance,
        LocalDate anchor,
        Map<Integer, Money> balanceAfterMonths,
        Money totalIncome,
        Money totalExpense,
        Money averageMonthlyNet,
        Money minBalance,
        LocalDate minBalanceDate,
        Optional<LocalDate> firstNegativeDate,
        Optional<LocalDate> firstBelowCushionDate,
        Optional<LocalDate> goalReachDate,
        Map<YearMonth, MonthTotals> byMonth) {

    /** Отметки «через k месяцев», для которых считается баланс. */
    public static final List<Integer> MONTH_MARKS = List.of(1, 3, 6, 12, 24);

    /** Проверяет обязательные поля и делает словари неизменяемыми с сохранением порядка. */
    public ForecastSummary {
        Objects.requireNonNull(startBalance, "startBalance");
        Objects.requireNonNull(endBalance, "endBalance");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(totalIncome, "totalIncome");
        Objects.requireNonNull(totalExpense, "totalExpense");
        Objects.requireNonNull(averageMonthlyNet, "averageMonthlyNet");
        Objects.requireNonNull(minBalance, "minBalance");
        Objects.requireNonNull(minBalanceDate, "minBalanceDate");
        firstNegativeDate = firstNegativeDate == null ? Optional.empty() : firstNegativeDate;
        firstBelowCushionDate = firstBelowCushionDate == null ? Optional.empty() : firstBelowCushionDate;
        goalReachDate = goalReachDate == null ? Optional.empty() : goalReachDate;
        // Map.copyOf потерял бы порядок, а интерфейсу важно выводить месяцы и отметки по возрастанию.
        balanceAfterMonths = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(balanceAfterMonths, "balanceAfterMonths")));
        byMonth = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(byMonth, "byMonth")));
    }

    /**
     * Баланс «через k месяцев».
     *
     * @param months число месяцев (1, 3, 6, 12 или 24)
     * @return баланс, если дата попадает в горизонт
     */
    public Optional<Money> balanceAfter(int months) {
        return Optional.ofNullable(balanceAfterMonths.get(months));
    }
}
