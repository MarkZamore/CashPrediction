package ru.cashprediction.core.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
import ru.cashprediction.core.util.DateFormats;

/**
 * Движок прогноза: превращает план в таблицу событий с нарастающим балансом.
 *
 * <p>Алгоритм:</p>
 * <ol>
 *   <li>{@code end = horizon.endDate(startDate)}, {@code anchor = max(startDate, today)}.</li>
 *   <li>Корректировки собираются в словарь по ключу события; при повторах действует последняя
 *       (предупреждение {@link WarningType#DUPLICATE_ADJUSTMENT}).</li>
 *   <li>Для каждого включённого правила (в порядке плана) — номинальные даты в горизонте, расширенном на
 *       {@link OccurrenceGenerator#MAX_WEEKEND_SHIFT_DAYS} дня в обе стороны
 *       ({@link OccurrenceGenerator#nominalDatesBetween}), и события из-за этих пределов, перенесённые
 *       корректировкой внутрь горизонта; к каждой дате применяется корректировка:
 *       «пропустить» убирает строку (или оставляет её с отметкой при {@code includeSkipped}),
 *       «изменить» меняет сумму, «перенести»/«заменить» задают точную дату без сдвига с выходных;
 *       без корректировки дата сдвигается по политике выходных (сдвиг за конец горизонта не применяется).
 *       Строки, чья итоговая дата вне горизонта, отбрасываются.</li>
 *   <li>Разовые операции вне горизонта отбрасываются с предупреждением.</li>
 *   <li>«Что-если»: суммы правил и разовых умножаются на коэффициент по типу; дополнительная экономия —
 *       синтетическая строка дохода в последний день каждого месяца из {@code [anchor, end]}.</li>
 *   <li>Сортировка: дата → доходы раньше расходов → источник (начальный баланс, правила в порядке плана,
 *       разовые в порядке плана, «что-если») → порядок появления. Первая строка — «Начальный баланс».</li>
 *   <li>Прогон баланса: {@code balanceAfter} каждой строки и {@code dailyBalance[i]} на конец каждого дня.</li>
 *   <li>Сводка от {@code anchor}, предупреждения о минусе, подушке, цели и «сиротах» корректировок.</li>
 * </ol>
 *
 * <p>Сложность O(N log N) по числу строк; 50 лет и 10 правил считаются за миллисекунды.</p>
 *
 * <p>Класс без состояния, метод {@link #forecast} потокобезопасен (план неизменяем) и может вызываться
 * из фонового потока.</p>
 */
public final class ForecastEngine {

    /** Название строки начального баланса. */
    public static final String START_TITLE = "Начальный баланс";

    /** Название синтетической строки дополнительной экономии. */
    public static final String WHAT_IF_TITLE = "Доп. экономия (что-если)";

    /**
     * Максимальная длина горизонта в днях. Горизонт «до даты» можно задать сколь угодно далёким,
     * а ежедневная серия занимает 8 байт на день; 200 000 дней (≈ 547 лет) — заведомо больше разумного.
     */
    public static final int MAX_DAYS = 200_000;

    /** Среднее число дней в месяце по григорианскому календарю: 365,25 / 12. */
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375");

    /** Порядок строк внутри дня (без учёта даты). */
    private static final Comparator<Draft> ORDER = Comparator
            .comparing(Draft::date)
            .thenComparingInt((Draft d) -> d.kind() == Kind.INCOME ? 0 : 1)
            .thenComparingInt(d -> d.origin().ordinal())
            .thenComparingInt(Draft::sequence);

    private ForecastEngine() {
    }

    /**
     * Строит прогноз.
     *
     * @param plan           план
     * @param whatIf         параметры «что-если»; {@code null} равносилен {@link WhatIf#NONE}
     * @param today          сегодняшняя дата (передаётся явно ради детерминированных тестов)
     * @param includeSkipped показывать ли пропущенные события строками с отметкой {@link Flags#skipped()}
     * @return прогноз
     * @throws IllegalStateException если горизонт длиннее {@link #MAX_DAYS} дней или правило даёт слишком много дат
     */
    public static Forecast forecast(Plan plan, WhatIf whatIf, LocalDate today, boolean includeSkipped) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(today, "today");
        WhatIf wi = whatIf == null ? WhatIf.NONE : whatIf;
        LocalDate start = plan.startDate();
        LocalDate end = plan.endDate();
        LocalDate anchor = today.isAfter(start) ? today : start;
        long dayCount = ChronoUnit.DAYS.between(start, end) + 1;
        if (dayCount > MAX_DAYS) {
            throw new IllegalStateException("Горизонт прогноза слишком длинный: " + dayCount + " дней (допустимо не больше "
                    + MAX_DAYS + ")");
        }

        Context ctx = new Context(plan, wi, today, start, end, includeSkipped);
        ctx.collectAdjustments();
        ctx.addRuleRows();
        ctx.warnOrphanAdjustments();
        ctx.addOneTimeRows();
        ctx.addWhatIfRows(anchor);
        ctx.drafts.sort(ORDER);
        return ctx.build(anchor, (int) dayCount);
    }

    /**
     * Черновик строки до расчёта баланса.
     *
     * @param date         фактическая дата
     * @param originalDate номинальная дата
     * @param title        название
     * @param kind         тип
     * @param category     категория
     * @param amount       сумма со знаком
     * @param origin       источник
     * @param ruleId       правило или {@code null}
     * @param txId         разовая операция или {@code null}
     * @param flags        отметки
     * @param note         заметка
     * @param sequence     порядковый номер появления: сохраняет порядок правил и разовых из плана
     */
    private record Draft(LocalDate date, LocalDate originalDate, String title, Kind kind, String category, Money amount,
                         Origin origin, RuleId ruleId, TxId txId, Flags flags, String note, int sequence) {

        ForecastRow toRow(Money balanceAfter) {
            return new ForecastRow(date, originalDate, title, kind, category, amount, balanceAfter, origin, ruleId, txId, flags, note);
        }
    }

    /**
     * Состояние одного расчёта. Создаётся на каждый вызов {@link #forecast} и не выходит за его пределы,
     * поэтому изменяемые коллекции здесь безопасны.
     */
    private static final class Context {
        private final Plan plan;
        private final WhatIf whatIf;
        private final LocalDate today;
        private final LocalDate start;
        private final LocalDate end;
        private final boolean includeSkipped;
        private final String currency;

        /** Действующая (последняя) корректировка каждого события. */
        private final Map<OccurrenceKey, Adjustment> adjustments = new LinkedHashMap<>();
        /** Правила, у которых есть хотя бы одна корректировка: для остальных ключи событий не создаются. */
        private final Set<RuleId> adjustedRules = new HashSet<>();
        /** Ключи корректировок, нашедшие своё событие. */
        private final Set<OccurrenceKey> matched = new HashSet<>();
        private final List<Draft> drafts = new ArrayList<>();
        private final List<Warning> warnings = new ArrayList<>();
        private int sequence;

        Context(Plan plan, WhatIf whatIf, LocalDate today, LocalDate start, LocalDate end, boolean includeSkipped) {
            this.plan = plan;
            this.whatIf = whatIf;
            this.today = today;
            this.start = start;
            this.end = end;
            this.includeSkipped = includeSkipped;
            this.currency = plan.currency();
        }

        /** Шаг 2: словарь корректировок и предупреждения о дубликатах (одно на событие). */
        void collectAdjustments() {
            Map<OccurrenceKey, Integer> counts = new LinkedHashMap<>();
            for (Adjustment a : plan.adjustments()) {
                adjustments.put(a.key(), a);
                adjustedRules.add(a.key().ruleId());
                counts.merge(a.key(), 1, Integer::sum);
            }
            counts.forEach((key, count) -> {
                if (count > 1) {
                    warn(Severity.WARNING, key.originalDate(), WarningType.DUPLICATE_ADJUSTMENT,
                            "Для события " + key.ruleId() + " от " + DateFormats.ru(key.originalDate()) + " задано корректировок: "
                                    + count + "; действует последняя");
                }
            });
        }

        /**
         * Шаг 3: строки регулярных операций.
         *
         * <p>Кандидаты — номинальные даты правила в горизонте, расширенном на
         * {@link OccurrenceGenerator#MAX_WEEKEND_SHIFT_DAYS} дня в обе стороны, и события из-за этих пределов,
         * которые корректировка «перенести»/«заменить» переносит внутрь горизонта. В прогноз попадает событие,
         * чья итоговая дата лежит в {@code [start, end]}, где бы ни была его номинальная дата. Иначе после
         * «Актуализировать на сегодня» молча пропадали бы зарплата «от субботы», выплачиваемая в понедельник-сегодня,
         * и событие, перенесённое корректировкой из прошлого на будущую дату: начальный баланс их ещё не содержит.</p>
         */
        void addRuleRows() {
            LocalDate searchFrom = start.minusDays(OccurrenceGenerator.MAX_WEEKEND_SHIFT_DAYS);
            LocalDate searchTo = end.plusDays(OccurrenceGenerator.MAX_WEEKEND_SHIFT_DAYS);
            for (RecurringRule rule : plan.rules()) {
                if (!rule.enabled()) {
                    // Выключенное правило остаётся в плане, но в прогнозе его нет совсем — и предупреждений о нём тоже.
                    continue;
                }
                boolean hasAdjustments = adjustedRules.contains(rule.id());
                boolean landed = false;
                for (LocalDate nominal : OccurrenceGenerator.nominalDatesBetween(rule, start, searchFrom, searchTo)) {
                    Adjustment adjustment = hasAdjustments ? matchAdjustment(rule.id(), nominal) : null;
                    boolean inHorizon = !nominal.isBefore(start) && !nominal.isAfter(end);
                    landed |= addRuleOccurrence(rule, nominal, adjustment, inHorizon);
                }
                if (hasAdjustments) {
                    landed |= addMovedIntoHorizon(rule, searchFrom, searchTo);
                }
                LocalDate lo = OccurrenceGenerator.windowStart(rule, start);
                LocalDate hi = OccurrenceGenerator.windowEnd(rule, end);
                // Правило вне горизонта, чьё событие всё же попало в него сдвигом или переносом, «действует» —
                // предупреждать о нём было бы неправдой.
                if (lo.isAfter(hi) && !landed) {
                    warn(Severity.WARNING, null, WarningType.RULE_OUTSIDE_HORIZON,
                            "Правило " + describe(rule) + " не действует в пределах горизонта прогноза ("
                                    + DateFormats.ru(start) + " – " + DateFormats.ru(end) + ")");
                }
            }
        }

        /** @return действующая корректировка события (с отметкой «нашла событие») или {@code null} */
        private Adjustment matchAdjustment(RuleId ruleId, LocalDate nominal) {
            OccurrenceKey key = new OccurrenceKey(ruleId, nominal);
            Adjustment adjustment = adjustments.get(key);
            if (adjustment != null) {
                matched.add(key);
            }
            return adjustment;
        }

        /**
         * События правила, номинальная дата которых лежит дальше окна поиска {@code [searchFrom, searchTo]},
         * но корректировка с новой датой переносит их внутрь горизонта. Корректировка учитывается, только если
         * её исходная дата действительно является датой правила: иначе это «сирота», а не перенос.
         *
         * @return {@code true}, если хотя бы одно событие попало в горизонт
         */
        private boolean addMovedIntoHorizon(RecurringRule rule, LocalDate searchFrom, LocalDate searchTo) {
            boolean landed = false;
            for (Adjustment adjustment : adjustments.values()) {
                OccurrenceKey key = adjustment.key();
                LocalDate nominal = key.originalDate();
                if (!key.ruleId().equals(rule.id()) || (!nominal.isBefore(searchFrom) && !nominal.isAfter(searchTo))) {
                    continue;
                }
                Optional<LocalDate> newDate = adjustment.action().newDate();
                if (newDate.isEmpty() || newDate.get().isBefore(start) || newDate.get().isAfter(end)
                        || !OccurrenceGenerator.isNominalDate(rule, start, nominal)) {
                    continue;
                }
                matched.add(key);
                landed |= addRuleOccurrence(rule, nominal, adjustment, false);
            }
            return landed;
        }

        /**
         * Одно событие правила с учётом корректировки.
         *
         * @param inHorizon лежит ли номинальная дата внутри горизонта
         * @return {@code true}, если итоговая дата события попала в горизонт (даже если событие пропущено)
         */
        private boolean addRuleOccurrence(RecurringRule rule, LocalDate nominal, Adjustment adjustment, boolean inHorizon) {
            LocalDate shiftedDate = rule.weekendPolicy().apply(nominal);
            if (inHorizon && shiftedDate.isAfter(end)) {
                // Раздел 3.2 плана: сдвиг за конец горизонта не применяется, иначе последнее событие пропало бы.
                // Сдвиг раньше начала, наоборот, применяется и убирает событие: оно состоялось до начала плана
                // и уже входит в начальный баланс (после «Актуализировать» оно иначе учлось бы дважды).
                shiftedDate = nominal;
            }
            LocalDate date = shiftedDate;
            Money amount = rule.amount();
            boolean amountChanged = false;
            boolean moved = false;
            boolean skipped = false;
            switch (adjustment == null ? null : adjustment.action()) {
                case null -> {
                    // Без корректировки: сдвиг с выходных уже учтён в date.
                }
                case Adjustment.Skip _ -> skipped = true;
                case Adjustment.ChangeAmount(Money newAmount) -> {
                    amount = newAmount;
                    amountChanged = true;
                }
                case Adjustment.MoveDate(LocalDate newDate) -> {
                    // Пользователь указал точную дату: политику выходных к ней не применяем.
                    date = newDate;
                    moved = true;
                }
                case Adjustment.Replace(Money newAmount, LocalDate newDate) -> {
                    amount = newAmount;
                    date = newDate;
                    amountChanged = true;
                    moved = true;
                }
            }
            if (date.isBefore(start) || date.isAfter(end)) {
                // Для событий, номинальная дата которых и так вне горизонта, перенос «наружу» ничего не меняет.
                if (moved && inHorizon) {
                    warn(Severity.WARNING, nominal, WarningType.MOVED_OUT_OF_HORIZON,
                            "Событие " + describe(rule) + " от " + DateFormats.ru(nominal) + " перенесено на "
                                    + DateFormats.ru(date) + " — за пределы горизонта, в прогноз не попало");
                }
                return false;
            }
            if (skipped && !includeSkipped) {
                return true;
            }
            BigDecimal factor = factorFor(rule.kind());
            boolean scaled = factor.compareTo(BigDecimal.ONE) != 0;
            Money signed = rule.kind().signed(scaled ? amount.times(factor) : amount);
            boolean shifted = !moved && !shiftedDate.equals(nominal);
            Flags flags = new Flags(shifted, amountChanged, moved, skipped, date.isBefore(today), scaled);
            String note = adjustment != null && !adjustment.note().isEmpty() ? adjustment.note() : rule.note();
            drafts.add(new Draft(date, nominal, rule.title(), rule.kind(), rule.category(), signed, Origin.RULE,
                    rule.id(), null, flags, note, sequence++));
            return true;
        }

        /**
         * Корректировки, не нашедшие события. Даты вне горизонта не проверяются: после «Актуализировать»
         * старые корректировки законно остаются в прошлом, и засыпать пользователя предупреждениями незачем.
         */
        void warnOrphanAdjustments() {
            for (Adjustment a : adjustments.values()) {
                OccurrenceKey key = a.key();
                LocalDate date = key.originalDate();
                if (date.isBefore(start) || date.isAfter(end)) {
                    continue;
                }
                Optional<RecurringRule> rule = plan.findRule(key.ruleId());
                if (rule.isEmpty()) {
                    warn(Severity.WARNING, date, WarningType.ORPHAN_ADJUSTMENT,
                            "Корректировка события " + key.ruleId() + " от " + DateFormats.ru(date)
                                    + " ни к чему не относится: правила " + key.ruleId() + " нет в плане");
                    continue;
                }
                RecurringRule r = rule.get();
                if (!r.enabled() || matched.contains(key)) {
                    continue;
                }
                LocalDate lo = OccurrenceGenerator.windowStart(r, start);
                LocalDate hi = OccurrenceGenerator.windowEnd(r, end);
                if (!date.isBefore(lo) && !date.isAfter(hi)) {
                    warn(Severity.WARNING, date, WarningType.ORPHAN_ADJUSTMENT,
                            "Корректировка события " + key.ruleId() + " от " + DateFormats.ru(date)
                                    + " ни к чему не относится: правило " + describe(r) + " не создаёт событие в эту дату");
                }
            }
        }

        /** Шаг 4: разовые операции. */
        void addOneTimeRows() {
            for (OneTimeTransaction tx : plan.oneTimes()) {
                if (tx.date().isBefore(start) || tx.date().isAfter(end)) {
                    warn(Severity.WARNING, tx.date(), WarningType.ONE_TIME_OUTSIDE_HORIZON,
                            "Разовая операция " + tx.id() + " «" + tx.title() + "» от " + DateFormats.ru(tx.date())
                                    + " вне горизонта прогноза и не учитывается");
                    continue;
                }
                BigDecimal factor = factorFor(tx.kind());
                boolean scaled = factor.compareTo(BigDecimal.ONE) != 0;
                Money signed = tx.kind().signed(scaled ? tx.amount().times(factor) : tx.amount());
                Flags flags = new Flags(false, false, false, false, tx.date().isBefore(today), scaled);
                drafts.add(new Draft(tx.date(), tx.date(), tx.title(), tx.kind(), tx.category(), signed, Origin.ONE_TIME,
                        null, tx.id(), flags, tx.note(), sequence++));
            }
        }

        /** Шаг 5: строки дополнительной экономии в последний день каждого месяца из {@code [anchor, end]}. */
        void addWhatIfRows(LocalDate anchor) {
            Money saving = whatIf.extraMonthlySaving();
            if (!saving.isPositive() || anchor.isAfter(end)) {
                return;
            }
            YearMonth last = YearMonth.from(end);
            for (YearMonth month = YearMonth.from(anchor); !month.isAfter(last); month = month.plusMonths(1)) {
                LocalDate day = month.atEndOfMonth();
                if (!day.isBefore(anchor) && !day.isAfter(end)) {
                    Flags flags = new Flags(false, false, false, false, day.isBefore(today), true);
                    drafts.add(new Draft(day, day, WHAT_IF_TITLE, Kind.INCOME, "", saving, Origin.WHAT_IF,
                            null, null, flags, "", sequence++));
                }
            }
        }

        /** Шаги 6-8: прогон баланса, сводка, предупреждения. */
        Forecast build(LocalDate anchor, int dayCount) {
            long[] daily = new long[dayCount];
            long balance = plan.startBalance().minor();
            List<ForecastRow> rows = new ArrayList<>(drafts.size() + 1);
            rows.add(new ForecastRow(start, start, START_TITLE, Kind.INCOME, "", Money.ZERO, plan.startBalance(), Origin.START,
                    null, null, Flags.NONE.withPast(start.isBefore(today)), ""));

            // Итоги месяцев заводятся заранее для КАЖДОГО месяца горизонта, чтобы пустые месяцы тоже попали в сводку.
            Map<YearMonth, long[]> monthSums = new LinkedHashMap<>();
            for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(end)); m = m.plusMonths(1)) {
                monthSums.put(m, new long[2]);
            }
            long totalIncome = 0;
            long totalExpense = 0;
            int filled = 0;
            for (Draft d : drafts) {
                int index = (int) ChronoUnit.DAYS.between(start, d.date());
                // Дни без событий получают баланс предыдущего дня.
                while (filled < index) {
                    daily[filled++] = balance;
                }
                if (!d.flags().skipped()) {
                    balance = Math.addExact(balance, d.amount().minor());
                    long[] sums = monthSums.get(YearMonth.from(d.date()));
                    if (d.amount().isNegative()) {
                        totalExpense = Math.subtractExact(totalExpense, d.amount().minor());
                        sums[1] = Math.subtractExact(sums[1], d.amount().minor());
                    } else {
                        totalIncome = Math.addExact(totalIncome, d.amount().minor());
                        sums[0] = Math.addExact(sums[0], d.amount().minor());
                    }
                }
                rows.add(d.toRow(new Money(balance)));
            }
            while (filled < dayCount) {
                daily[filled++] = balance;
            }

            Map<YearMonth, MonthTotals> byMonth = new LinkedHashMap<>();
            monthSums.forEach((month, sums) -> {
                LocalDate closing = month.atEndOfMonth().isAfter(end) ? end : month.atEndOfMonth();
                byMonth.put(month, new MonthTotals(new Money(sums[0]), new Money(sums[1]), new Money(sums[0] - sums[1]),
                        BalanceSeries.balanceAt(start, daily, plan.startBalance(), closing)));
            });

            ForecastSummary summary = summarize(anchor, daily, new Money(totalIncome), new Money(totalExpense), byMonth);
            return new Forecast(plan, whatIf, today, anchor, rows, daily, start, summary, warnings);
        }

        /** Сводка и предупреждения о балансе и цели. */
        private ForecastSummary summarize(LocalDate anchor, long[] daily, Money totalIncome, Money totalExpense,
                                          Map<YearMonth, MonthTotals> byMonth) {
            Money startBalance = plan.startBalance();
            Money endBalance = new Money(daily[daily.length - 1]);

            Map<Integer, Money> afterMonths = new LinkedHashMap<>();
            for (int k : ForecastSummary.MONTH_MARKS) {
                LocalDate date = anchor.plusMonths(k);
                if (!date.isAfter(end)) {
                    afterMonths.put(k, BalanceSeries.balanceAt(start, daily, startBalance, date));
                }
            }

            // Минимум ищем только впереди: прошлые провалы уже не повлиять, а карточка «Мин» про будущее.
            Money minBalance = endBalance;
            LocalDate minDate = end;
            long from = BalanceSeries.indexOf(start, anchor);
            if (from < daily.length) {
                long min = Long.MAX_VALUE;
                for (int i = (int) from; i < daily.length; i++) {
                    if (daily[i] < min) {
                        min = daily[i];
                        minDate = start.plusDays(i);
                    }
                }
                minBalance = new Money(min);
            }

            Optional<LocalDate> firstNegative = BalanceSeries.firstMatch(start, daily, anchor, v -> v < 0);
            firstNegative.ifPresent(date -> warn(Severity.WARNING, date, WarningType.NEGATIVE_BALANCE,
                    "Баланс уходит в минус: " + BalanceSeries.balanceAt(start, daily, startBalance, date).format(currency)));

            long cushion = plan.cushion().minor();
            Optional<LocalDate> firstBelowCushion = cushion > 0
                    ? BalanceSeries.firstMatch(start, daily, anchor, v -> v < cushion)
                    : Optional.empty();
            firstBelowCushion.ifPresent(date -> warn(Severity.WARNING, date, WarningType.BELOW_CUSHION,
                    "Баланс опускается ниже подушки безопасности (" + plan.cushion().format(currency) + "): "
                            + BalanceSeries.balanceAt(start, daily, startBalance, date).format(currency)));

            Optional<LocalDate> goalReach = Optional.empty();
            Goal goal = plan.goal();
            if (goal != null) {
                goalReach = GoalCalculator.reachDate(start, daily, anchor, goal.target());
                warnGoal(goal, goalReach);
            }

            return new ForecastSummary(startBalance, endBalance, anchor, afterMonths, totalIncome, totalExpense,
                    averageMonthlyNet(startBalance, endBalance, daily.length), minBalance, minDate,
                    firstNegative, firstBelowCushion, goalReach, byMonth);
        }

        /** Предупреждение, если цель не достигается или достигается позже желаемой даты. */
        private void warnGoal(Goal goal, Optional<LocalDate> reach) {
            String name = "Цель" + (goal.title().isEmpty() ? "" : " «" + goal.title() + "»") + " (" + goal.target().format(currency) + ")";
            if (reach.isEmpty()) {
                warn(Severity.WARNING, goal.wishDate(), WarningType.GOAL_NOT_REACHED,
                        name + " не достигается до конца прогноза (" + DateFormats.ru(end) + ")");
            } else if (goal.wishDate() != null && reach.get().isAfter(goal.wishDate())) {
                warn(Severity.WARNING, goal.wishDate(), WarningType.GOAL_NOT_REACHED,
                        name + " достигается только " + DateFormats.ru(reach.get()) + ", позже желаемой даты "
                                + DateFormats.ru(goal.wishDate()));
            }
        }

        /**
         * Средний итог месяца. Формула {@code diff / (days / 30.4375)} переписана как {@code diff * 30.4375 / days},
         * чтобы деление было одно и округление HALF_UP применялось к точному значению.
         */
        private static Money averageMonthlyNet(Money startBalance, Money endBalance, int days) {
            BigDecimal diff = BigDecimal.valueOf(endBalance.minor()).subtract(BigDecimal.valueOf(startBalance.minor()));
            BigDecimal dayCount = BigDecimal.valueOf(days);
            if (dayCount.compareTo(DAYS_PER_MONTH) < 0) {
                // Горизонт короче месяца: делитель max(1, …) равен единице.
                return new Money(diff.longValueExact());
            }
            return new Money(diff.multiply(DAYS_PER_MONTH).divide(dayCount, 0, RoundingMode.HALF_UP).longValueExact());
        }

        /** @return коэффициент «что-если» для типа операции */
        private BigDecimal factorFor(Kind kind) {
            return kind == Kind.INCOME ? whatIf.incomeFactor() : whatIf.expenseFactor();
        }

        private void warn(Severity severity, LocalDate date, WarningType type, String message) {
            warnings.add(new Warning(severity, date, type, message));
        }

        /** @return «r1 «Зарплата»» или просто «r1», если название пустое */
        private static String describe(RecurringRule rule) {
            return rule.title().isEmpty() ? rule.id().value() : rule.id() + " «" + rule.title() + "»";
        }
    }
}
