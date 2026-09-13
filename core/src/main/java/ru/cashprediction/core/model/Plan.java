package ru.cashprediction.core.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Финансовый план: всё, что пользователь ввёл, и ничего вычисленного.
 *
 * <p>План неизменяем. Любое редактирование создаёт новый экземпляр через {@code with...}-методы.
 * Это даёт бесплатную отмену/повтор (стек предыдущих планов в {@code document.PlanDocument}),
 * безопасную передачу плана между UI-потоком и фоновой записью и простые тесты.</p>
 *
 * <p>Один план = один файл {@code CashMemory/<имя>.md}.</p>
 *
 * @param name         имя плана (совпадает с именем файла без .md)
 * @param note         свободная заметка
 * @param currency     обозначение валюты для вывода: ₽, $, € ...
 * @param startDate    дата, на которую известен начальный баланс
 * @param startBalance баланс на {@code startDate}
 * @param horizon      на какой срок строится прогноз
 * @param cushion      подушка безопасности: баланс ниже неё подсвечивается; ноль — подушка не задана
 * @param goal         цель накопления или {@code null}
 * @param rules        регулярные операции в порядке, заданном пользователем
 * @param oneTimes     разовые операции
 * @param adjustments  корректировки конкретных событий
 * @param rawBlocks    нераспознанные фрагменты файла, сохраняемые как есть
 */
public record Plan(
        String name,
        String note,
        String currency,
        LocalDate startDate,
        Money startBalance,
        Horizon horizon,
        Money cushion,
        Goal goal,
        List<RecurringRule> rules,
        List<OneTimeTransaction> oneTimes,
        List<Adjustment> adjustments,
        List<RawBlock> rawBlocks) {

    /** Валюта по умолчанию. */
    public static final String DEFAULT_CURRENCY = "₽";

    /** Проверяет обязательные поля, заменяет {@code null} значениями по умолчанию и копирует списки. */
    public Plan {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(horizon, "horizon");
        name = name.strip();
        note = note == null ? "" : note;
        currency = currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency.strip();
        startBalance = startBalance == null ? Money.ZERO : startBalance;
        cushion = cushion == null ? Money.ZERO : cushion;
        rules = rules == null ? List.of() : List.copyOf(rules);
        oneTimes = oneTimes == null ? List.of() : List.copyOf(oneTimes);
        adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
        rawBlocks = rawBlocks == null ? List.of() : List.copyOf(rawBlocks);
    }

    /**
     * Пустой план: нулевой баланс, горизонт 12 месяцев, начало сегодня.
     *
     * @param name  имя плана
     * @param today сегодняшняя дата (передаётся явно, чтобы тесты были детерминированными)
     * @return новый план без операций
     */
    public static Plan empty(String name, LocalDate today) {
        return new Plan(name, "", DEFAULT_CURRENCY, today, Money.ZERO, new Horizon.Months(12),
                Money.ZERO, null, List.of(), List.of(), List.of(), List.of());
    }

    /** @return последний день прогноза */
    public LocalDate endDate() {
        return horizon.endDate(startDate);
    }

    /** @return цель, если задана */
    public Optional<Goal> goalOptional() {
        return Optional.ofNullable(goal);
    }

    // ------------------------------------------------------------------ поиск

    /** @return регулярная операция с данным идентификатором */
    public Optional<RecurringRule> findRule(RuleId id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** @return разовая операция с данным идентификатором */
    public Optional<OneTimeTransaction> findOneTime(TxId id) {
        return oneTimes.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /**
     * Корректировка события. Если в файле оказалось несколько корректировок одного события,
     * действует последняя (так же поступает движок прогноза).
     *
     * @param key ключ события
     * @return корректировка, если есть
     */
    public Optional<Adjustment> findAdjustment(OccurrenceKey key) {
        Adjustment found = null;
        for (Adjustment a : adjustments) {
            if (a.key().equals(key)) {
                found = a;
            }
        }
        return Optional.ofNullable(found);
    }

    /** @return корректировки, относящиеся к правилу */
    public List<Adjustment> adjustmentsOf(RuleId ruleId) {
        return adjustments.stream().filter(a -> a.key().ruleId().equals(ruleId)).toList();
    }

    /** @return отсортированный список непустых категорий из всех операций (для подсказок в редакторах) */
    public List<String> categories() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        rules.forEach(r -> addIfPresent(set, r.category()));
        oneTimes.forEach(t -> addIfPresent(set, t.category()));
        return List.copyOf(set);
    }

    private static void addIfPresent(TreeSet<String> set, String value) {
        if (!value.isBlank()) {
            set.add(value);
        }
    }

    /**
     * Следующий свободный идентификатор правила: {@code r} + (максимальный номер + 1).
     * Идентификаторы нестандартного вида из ручных правок не мешают: они просто не учитываются.
     *
     * @return свободный идентификатор
     */
    public RuleId nextRuleId() {
        return new RuleId("r" + (maxNumber(rules.stream().map(r -> r.id().value()).toList(), "r") + 1));
    }

    /** @return следующий свободный идентификатор разовой операции: {@code t} + номер */
    public TxId nextTxId() {
        return new TxId("t" + (maxNumber(oneTimes.stream().map(t -> t.id().value()).toList(), "t") + 1));
    }

    private static long maxNumber(List<String> ids, String prefix) {
        long max = 0;
        for (String id : ids) {
            if (id.startsWith(prefix) && id.length() > prefix.length()) {
                try {
                    max = Math.max(max, Long.parseLong(id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // «r-аренда» и подобные ручные идентификаторы номер не занимают.
                }
            }
        }
        return max;
    }

    // ------------------------------------------------------------------ изменения параметров

    /** @return копия с другим именем */
    public Plan withName(String value) {
        return new Plan(value, note, currency, startDate, startBalance, horizon, cushion, goal, rules, oneTimes, adjustments, rawBlocks);
    }

    /** @return копия с другой заметкой */
    public Plan withNote(String value) {
        return new Plan(name, value, currency, startDate, startBalance, horizon, cushion, goal, rules, oneTimes, adjustments, rawBlocks);
    }

    /** @return копия с другой валютой */
    public Plan withCurrency(String value) {
        return new Plan(name, note, value, startDate, startBalance, horizon, cushion, goal, rules, oneTimes, adjustments, rawBlocks);
    }

    /** @return копия с другой датой начала и начальным балансом */
    public Plan withStart(LocalDate date, Money balance) {
        return new Plan(name, note, currency, date, balance, horizon, cushion, goal, rules, oneTimes, adjustments, rawBlocks);
    }

    /** @return копия с другим горизонтом */
    public Plan withHorizon(Horizon value) {
        return new Plan(name, note, currency, startDate, startBalance, value, cushion, goal, rules, oneTimes, adjustments, rawBlocks);
    }

    /** @return копия с другой подушкой безопасности */
    public Plan withCushion(Money value) {
        return new Plan(name, note, currency, startDate, startBalance, horizon, value, goal, rules, oneTimes, adjustments, rawBlocks);
    }

    /** @return копия с другой целью ({@code null} удаляет цель) */
    public Plan withGoal(Goal value) {
        return new Plan(name, note, currency, startDate, startBalance, horizon, cushion, value, rules, oneTimes, adjustments, rawBlocks);
    }

    /** @return копия с другими нераспознанными фрагментами */
    public Plan withRawBlocks(List<RawBlock> value) {
        return new Plan(name, note, currency, startDate, startBalance, horizon, cushion, goal, rules, oneTimes, adjustments, value);
    }

    // ------------------------------------------------------------------ регулярные операции

    /** @return копия с добавленным в конец правилом */
    public Plan withRuleAdded(RecurringRule rule) {
        List<RecurringRule> list = new ArrayList<>(rules);
        list.add(rule);
        return withRules(list);
    }

    /**
     * Заменяет правило с тем же идентификатором, сохраняя его позицию в списке.
     * Если такого правила нет, добавляет в конец.
     *
     * @param rule новая версия правила
     * @return изменённый план
     */
    public Plan withRuleReplaced(RecurringRule rule) {
        List<RecurringRule> list = new ArrayList<>(rules);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(rule.id())) {
                list.set(i, rule);
                return withRules(list);
            }
        }
        list.add(rule);
        return withRules(list);
    }

    /**
     * Удаляет правило вместе со всеми его корректировками: без правила они бессмысленны.
     *
     * @param id идентификатор правила
     * @return изменённый план
     */
    public Plan withRuleRemoved(RuleId id) {
        List<RecurringRule> list = rules.stream().filter(r -> !r.id().equals(id)).toList();
        List<Adjustment> adj = adjustments.stream().filter(a -> !a.key().ruleId().equals(id)).toList();
        return new Plan(name, note, currency, startDate, startBalance, horizon, cushion, goal, list, oneTimes, adj, rawBlocks);
    }

    /** @return копия с другим списком правил */
    public Plan withRules(List<RecurringRule> value) {
        return new Plan(name, note, currency, startDate, startBalance, horizon, cushion, goal, value, oneTimes, adjustments, rawBlocks);
    }

    // ------------------------------------------------------------------ разовые операции

    /** @return копия с добавленной разовой операцией */
    public Plan withOneTimeAdded(OneTimeTransaction tx) {
        List<OneTimeTransaction> list = new ArrayList<>(oneTimes);
        list.add(tx);
        return withOneTimes(list);
    }

    /** @return копия, где операция с тем же идентификатором заменена (или добавлена, если её не было) */
    public Plan withOneTimeReplaced(OneTimeTransaction tx) {
        List<OneTimeTransaction> list = new ArrayList<>(oneTimes);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(tx.id())) {
                list.set(i, tx);
                return withOneTimes(list);
            }
        }
        list.add(tx);
        return withOneTimes(list);
    }

    /** @return копия без разовой операции */
    public Plan withOneTimeRemoved(TxId id) {
        return withOneTimes(oneTimes.stream().filter(t -> !t.id().equals(id)).toList());
    }

    /** @return копия без разовых операций, удовлетворяющих условию (для «Актуализировать») */
    public Plan withOneTimesRemovedIf(Predicate<OneTimeTransaction> condition) {
        return withOneTimes(oneTimes.stream().filter(condition.negate()).toList());
    }

    /** @return копия с другим списком разовых операций */
    public Plan withOneTimes(List<OneTimeTransaction> value) {
        return new Plan(name, note, currency, startDate, startBalance, horizon, cushion, goal, rules, value, adjustments, rawBlocks);
    }

    // ------------------------------------------------------------------ корректировки

    /**
     * Добавляет корректировку или заменяет существующую для того же события.
     * Все прежние корректировки этого события удаляются, чтобы в файле не копились дубликаты.
     *
     * @param adjustment корректировка
     * @return изменённый план
     */
    public Plan withAdjustmentPut(Adjustment adjustment) {
        List<Adjustment> list = new ArrayList<>();
        for (Adjustment a : adjustments) {
            if (!a.key().equals(adjustment.key())) {
                list.add(a);
            }
        }
        list.add(adjustment);
        return withAdjustments(list);
    }

    /** @return копия без корректировки события («Вернуть как по правилу») */
    public Plan withAdjustmentRemoved(OccurrenceKey key) {
        return withAdjustments(adjustments.stream().filter(a -> !a.key().equals(key)).toList());
    }

    /** @return копия с другим списком корректировок */
    public Plan withAdjustments(List<Adjustment> value) {
        return new Plan(name, note, currency, startDate, startBalance, horizon, cushion, goal, rules, oneTimes, value, rawBlocks);
    }
}
