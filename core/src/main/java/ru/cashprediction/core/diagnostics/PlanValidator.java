package ru.cashprediction.core.diagnostics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Проверка плана перед прогнозом и перед сохранением: команда «Инструменты → Проверить план»,
 * подсветка ошибок в редакторах и защита движка от заведомо неразумных планов.
 *
 * <p>Почему проверки не в конструкторах модели: план мог прийти из вручную испорченного файла, и его всё
 * равно нужно открыть, показать и объяснить, что не так, а не отказаться читать.</p>
 *
 * <p>Уровни: {@link Severity#ERROR} — данные не попадут в прогноз или прогноз не построится;
 * {@link Severity#WARNING} — план работает, но, вероятно, не так, как задумано;
 * {@link Severity#INFO} — программа сама подставила разумное значение.</p>
 *
 * <p>Все тексты сообщений берутся из общего каталога текстов (область {@code diagnostics},
 * ключи {@code diagnostic.*}); сообщение об элементе плана собирается из «о чём речь» и «что не так»
 * по шаблону {@code diagnostic.item.message}.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class PlanValidator {

    /** Максимальная сумма одной операции: 999 999 999 999,99. */
    public static final Money MAX_AMOUNT = Money.ofMinor(99_999_999_999_999L);

    /** Максимальная длина имени плана в символах. */
    public static final int MAX_NAME_LENGTH = 80;

    /** Горизонт длиннее этого числа месяцев вызывает предупреждение. */
    public static final int LONG_HORIZON_MONTHS = 240;

    /** Максимальное оценочное число строк прогноза. */
    public static final long MAX_ROWS = 200_000;

    /**
     * Имена, занятые служебными файлами CashMemory (сравниваются без учёта регистра).
     * План с таким именем перезаписал бы настройки или снимок сессии.
     */
    public static final Set<String> RESERVED_NAMES = Set.of("settings", "web-session", "web-session.plan", "session-fx", "session-swing");

    /** Символы, запрещённые в именах файлов Windows. */
    private static final String FORBIDDEN_CHARS = "\\/:*?\"<>|";

    /** Имена устройств Windows: файл «CON.md» создать нельзя. */
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private PlanValidator() {
    }

    /**
     * Проверяет план целиком.
     *
     * @param plan план
     * @return список сообщений (без номеров строк) в порядке: имя, горизонт, правила, разовые, корректировки, цель;
     *         пустой, если замечаний нет
     */
    public static List<Diagnostic> validate(Plan plan) {
        List<Diagnostic> out = new ArrayList<>();
        checkPlanName(plan.name()).ifPresent(message -> out.add(Diagnostic.error(message)));
        validateHorizon(plan, out);
        long rows = estimateRowCount(plan);
        if (rows > MAX_ROWS) {
            // Предел подставляется из константы с разделением разрядов («200 000»), а не повторяется в тексте каталога.
            out.add(Diagnostic.error(Texts.get("diagnostic.plan.tooManyRows", rows, RuText.groupDigits(MAX_ROWS))));
        }
        if (plan.cushion().isNegative()) {
            out.add(Diagnostic.warning(Texts.get("diagnostic.plan.cushionNegative")));
        }
        validateRules(plan, out);
        validateOneTimes(plan, out);
        validateAdjustments(plan, out);
        if (plan.goal() != null && !plan.goal().target().isPositive()) {
            out.add(Diagnostic.warning(Texts.get("diagnostic.plan.goalNotPositive")));
        }
        return List.copyOf(out);
    }

    /**
     * Проверяет имя плана (оно же имя файла {@code CashMemory/<имя>.md}).
     *
     * @param name имя
     * @return текст ошибки для пользователя или пусто, если имя допустимо
     */
    public static Optional<String> checkPlanName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of(Texts.get("diagnostic.name.empty"));
        }
        String n = name.strip();
        if (n.codePointCount(0, n.length()) > MAX_NAME_LENGTH) {
            return Optional.of(Texts.get("diagnostic.name.tooLong", MAX_NAME_LENGTH));
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (FORBIDDEN_CHARS.indexOf(c) >= 0) {
                return Optional.of(Texts.get("diagnostic.name.forbiddenChars"));
            }
            if (Character.isISOControl(c)) {
                return Optional.of(Texts.get("diagnostic.name.controlChars"));
            }
        }
        if (n.endsWith(".")) {
            // Windows молча отбрасывает точку в конце имени файла, и файл не нашёлся бы по имени плана.
            return Optional.of(Texts.get("diagnostic.name.endsWithDot"));
        }
        String lower = n.toLowerCase(Locale.ROOT);
        if (RESERVED_NAMES.contains(lower)) {
            return Optional.of(Texts.get("diagnostic.name.reservedByApp", n));
        }
        if (WINDOWS_DEVICE_NAMES.contains(lower)) {
            return Optional.of(Texts.get("diagnostic.name.reservedByWindows", n));
        }
        return Optional.empty();
    }

    /**
     * Быстрая оценка числа строк прогноза без генерации дат: чтобы отвергнуть план до того,
     * как движок начнёт расходовать память.
     *
     * <p>Учитываются включённые правила (по окну «С/По» внутри горизонта, с запасом +1 на правило),
     * все разовые операции и строка начального баланса. Строки «что-если» не учитываются: они зависят не от плана.</p>
     *
     * @param plan план
     * @return оценка сверху числа строк
     */
    public static long estimateRowCount(Plan plan) {
        LocalDate start = plan.startDate();
        LocalDate end = plan.endDate();
        long total = 1L + plan.oneTimes().size();
        for (RecurringRule rule : plan.rules()) {
            if (!rule.enabled()) {
                continue;
            }
            LocalDate lo = rule.from() != null && rule.from().isAfter(start) ? rule.from() : start;
            LocalDate hi = rule.until() != null && rule.until().isBefore(end) ? rule.until() : end;
            if (lo.isAfter(hi)) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(lo, hi) + 1;
            long estimate = switch (rule.recurrence()) {
                case Recurrence.Monthly monthly ->
                        (ChronoUnit.MONTHS.between(YearMonth.from(lo), YearMonth.from(hi)) + 1) / monthly.everyMonths() + 1;
                case Recurrence.Weekly weekly -> days / (7L * weekly.everyWeeks()) + 1;
                case Recurrence.EveryNDays every -> days / every.days() + 1;
                case Recurrence.Yearly _ -> hi.getYear() - lo.getYear() + 1L;
            };
            total += estimate;
        }
        return total;
    }

    /**
     * Горизонт: «до даты» не раньше начала, не длиннее {@link Horizon#MAX_MONTHS} месяцев, длиннее
     * {@link #LONG_HORIZON_MONTHS} — предупреждение. Пределы подставляются в тексты из этих констант.
     */
    private static void validateHorizon(Plan plan, List<Diagnostic> out) {
        LocalDate start = plan.startDate();
        if (plan.horizon() instanceof Horizon.Until until && until.end().isBefore(start)) {
            out.add(Diagnostic.error(Texts.get("diagnostic.plan.horizonEndBeforeStart",
                    DateFormats.ru(until.end()), DateFormats.ru(start))));
            return;
        }
        long months = plan.horizon().approximateMonths(start);
        if (months > Horizon.MAX_MONTHS) {
            out.add(Diagnostic.error(Texts.get("diagnostic.plan.horizonTooLong", Horizon.MAX_MONTHS / 12,
                    Horizon.MAX_MONTHS)));
        } else if (months > LONG_HORIZON_MONTHS) {
            out.add(Diagnostic.warning(Texts.get("diagnostic.plan.horizonLong", LONG_HORIZON_MONTHS / 12, months)));
        }
    }

    /** Регулярные операции. */
    private static void validateRules(Plan plan, List<Diagnostic> out) {
        LocalDate start = plan.startDate();
        LocalDate end = plan.endDate();
        Set<String> ids = new HashSet<>();
        for (RecurringRule rule : plan.rules()) {
            String subject = rule.title().isEmpty()
                    ? Texts.get("diagnostic.rule.subject", rule.id())
                    : Texts.get("diagnostic.rule.subjectTitled", rule.id(), rule.title());
            if (!ids.add(rule.id().value())) {
                out.add(Diagnostic.error(item(subject, Texts.get("diagnostic.item.duplicateId", rule.id()))));
            }
            if (rule.title().isBlank()) {
                out.add(Diagnostic.warning(item(subject, Texts.get("diagnostic.item.noTitle"))));
            }
            checkAmount(rule.amount(), subject, out);
            if (rule.from() != null && rule.until() != null && rule.until().isBefore(rule.from())) {
                out.add(Diagnostic.error(item(subject, Texts.get("diagnostic.rule.untilBeforeFrom",
                        DateFormats.ru(rule.until()), DateFormats.ru(rule.from())))));
            } else if (rule.enabled()) {
                LocalDate lo = rule.from() != null && rule.from().isAfter(start) ? rule.from() : start;
                LocalDate hi = rule.until() != null && rule.until().isBefore(end) ? rule.until() : end;
                if (lo.isAfter(hi)) {
                    out.add(Diagnostic.warning(item(subject, Texts.get("diagnostic.rule.outsideHorizon",
                            DateFormats.ru(start), DateFormats.ru(end)))));
                }
            }
            if (rule.recurrence().needsAnchor() && rule.from() == null) {
                out.add(Diagnostic.info(item(subject, Texts.get("diagnostic.rule.fromMissing", DateFormats.ru(start)))));
            }
        }
    }

    /** Разовые операции. */
    private static void validateOneTimes(Plan plan, List<Diagnostic> out) {
        Set<String> ids = new HashSet<>();
        for (OneTimeTransaction tx : plan.oneTimes()) {
            String subject = tx.title().isEmpty()
                    ? Texts.get("diagnostic.oneTime.subject", tx.id())
                    : Texts.get("diagnostic.oneTime.subjectTitled", tx.id(), tx.title());
            if (!ids.add(tx.id().value())) {
                out.add(Diagnostic.error(item(subject, Texts.get("diagnostic.item.duplicateId", tx.id()))));
            }
            if (tx.title().isBlank()) {
                out.add(Diagnostic.warning(item(subject, Texts.get("diagnostic.item.noTitle"))));
            }
            checkAmount(tx.amount(), subject, out);
        }
    }

    /** Корректировки: у «изменить» и «заменить» новая сумма должна быть положительной и в пределах. */
    private static void validateAdjustments(Plan plan, List<Diagnostic> out) {
        for (Adjustment adjustment : plan.adjustments()) {
            Optional<Money> amount = switch (adjustment.action()) {
                case Adjustment.ChangeAmount change -> Optional.of(change.amount());
                case Adjustment.Replace replace -> Optional.of(replace.amount());
                case Adjustment.Skip _, Adjustment.MoveDate _ -> Optional.empty();
            };
            if (amount.isEmpty()) {
                continue;
            }
            String subject = Texts.get("diagnostic.adjustment.subject", adjustment.key().ruleId(),
                    DateFormats.ru(adjustment.key().originalDate()));
            if (!amount.get().isPositive()) {
                out.add(Diagnostic.error(item(subject, Texts.get("diagnostic.adjustment.amountNotPositive"))));
            } else if (amount.get().compareTo(MAX_AMOUNT) > 0) {
                out.add(Diagnostic.error(item(subject, Texts.get("diagnostic.adjustment.amountTooLarge", MAX_AMOUNT.format()))));
            }
        }
    }

    /** Проверка суммы правила или разовой операции. */
    private static void checkAmount(Money amount, String subject, List<Diagnostic> out) {
        if (!amount.isPositive()) {
            out.add(Diagnostic.error(item(subject, Texts.get("diagnostic.item.amountNotPositive"))));
        } else if (amount.compareTo(MAX_AMOUNT) > 0) {
            out.add(Diagnostic.error(item(subject, Texts.get("diagnostic.item.amountTooLarge", MAX_AMOUNT.format()))));
        }
    }

    /**
     * Сообщение об элементе плана.
     *
     * @param subject о чём речь, например «Правило r1 «Зарплата»»
     * @param problem что не так, например «сумма должна быть больше нуля»
     * @return «Правило r1 «Зарплата»: сумма должна быть больше нуля»
     */
    private static String item(String subject, String problem) {
        return Texts.get("diagnostic.item.message", subject, problem);
    }
}
