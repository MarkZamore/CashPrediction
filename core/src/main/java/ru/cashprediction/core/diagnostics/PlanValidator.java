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
import ru.cashprediction.core.util.DateFormats;

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
            out.add(Diagnostic.error("План даёт около " + rows + " строк прогноза (допустимо не больше 200 000): "
                    + "сократите горизонт или период частых операций"));
        }
        if (plan.cushion().isNegative()) {
            out.add(Diagnostic.warning("Подушка безопасности не может быть отрицательной"));
        }
        validateRules(plan, out);
        validateOneTimes(plan, out);
        validateAdjustments(plan, out);
        if (plan.goal() != null && !plan.goal().target().isPositive()) {
            out.add(Diagnostic.warning("Сумма цели должна быть больше нуля"));
        }
        return List.copyOf(out);
    }

    /**
     * Проверяет имя плана (оно же имя файла {@code CashMemory/<имя>.md}).
     *
     * @param name имя
     * @return текст ошибки на русском или пусто, если имя допустимо
     */
    public static Optional<String> checkPlanName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of("Имя плана не может быть пустым");
        }
        String n = name.strip();
        if (n.codePointCount(0, n.length()) > MAX_NAME_LENGTH) {
            return Optional.of("Имя плана длиннее " + MAX_NAME_LENGTH + " символов");
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (FORBIDDEN_CHARS.indexOf(c) >= 0) {
                return Optional.of("Имя плана не может содержать символы \\ / : * ? \" < > |");
            }
            if (Character.isISOControl(c)) {
                return Optional.of("Имя плана не может содержать управляющие символы");
            }
        }
        if (n.endsWith(".")) {
            // Windows молча отбрасывает точку в конце имени файла, и файл не нашёлся бы по имени плана.
            return Optional.of("Имя плана не может заканчиваться точкой");
        }
        String lower = n.toLowerCase(Locale.ROOT);
        if (RESERVED_NAMES.contains(lower)) {
            return Optional.of("Имя «" + n + "» зарезервировано программой для служебного файла");
        }
        if (WINDOWS_DEVICE_NAMES.contains(lower)) {
            return Optional.of("Имя «" + n + "» зарезервировано Windows");
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

    /** Горизонт: «до даты» не раньше начала, не длиннее 600 месяцев, длиннее 240 — предупреждение. */
    private static void validateHorizon(Plan plan, List<Diagnostic> out) {
        LocalDate start = plan.startDate();
        if (plan.horizon() instanceof Horizon.Until until && until.end().isBefore(start)) {
            out.add(Diagnostic.error("Дата окончания горизонта (" + DateFormats.ru(until.end())
                    + ") раньше даты начала плана (" + DateFormats.ru(start) + ")"));
            return;
        }
        long months = plan.horizon().approximateMonths(start);
        if (months > Horizon.MAX_MONTHS) {
            out.add(Diagnostic.error("Горизонт прогноза длиннее 50 лет (600 месяцев)"));
        } else if (months > LONG_HORIZON_MONTHS) {
            out.add(Diagnostic.warning("Горизонт прогноза длиннее 20 лет (" + months
                    + " мес.): на такой срок прогноз малоточен, а график строится медленнее"));
        }
    }

    /** Регулярные операции. */
    private static void validateRules(Plan plan, List<Diagnostic> out) {
        LocalDate start = plan.startDate();
        LocalDate end = plan.endDate();
        Set<String> ids = new HashSet<>();
        for (RecurringRule rule : plan.rules()) {
            String prefix = "Правило " + rule.id() + (rule.title().isEmpty() ? "" : " «" + rule.title() + "»") + ": ";
            if (!ids.add(rule.id().value())) {
                out.add(Diagnostic.error(prefix + "идентификатор " + rule.id() + " повторяется"));
            }
            if (rule.title().isBlank()) {
                out.add(Diagnostic.warning(prefix + "не указано название"));
            }
            checkAmount(rule.amount(), prefix, out);
            if (rule.from() != null && rule.until() != null && rule.until().isBefore(rule.from())) {
                out.add(Diagnostic.error(prefix + "дата окончания (" + DateFormats.ru(rule.until())
                        + ") раньше даты начала (" + DateFormats.ru(rule.from()) + ")"));
            } else if (rule.enabled()) {
                LocalDate lo = rule.from() != null && rule.from().isAfter(start) ? rule.from() : start;
                LocalDate hi = rule.until() != null && rule.until().isBefore(end) ? rule.until() : end;
                if (lo.isAfter(hi)) {
                    out.add(Diagnostic.warning(prefix + "не действует в пределах горизонта прогноза ("
                            + DateFormats.ru(start) + " – " + DateFormats.ru(end) + ")"));
                }
            }
            if (rule.recurrence().needsAnchor() && rule.from() == null) {
                out.add(Diagnostic.info(prefix + "дата «С» не задана, отсчёт от даты начала плана ("
                        + DateFormats.ru(start) + ")"));
            }
        }
    }

    /** Разовые операции. */
    private static void validateOneTimes(Plan plan, List<Diagnostic> out) {
        Set<String> ids = new HashSet<>();
        for (OneTimeTransaction tx : plan.oneTimes()) {
            String prefix = "Разовая операция " + tx.id() + (tx.title().isEmpty() ? "" : " «" + tx.title() + "»") + ": ";
            if (!ids.add(tx.id().value())) {
                out.add(Diagnostic.error(prefix + "идентификатор " + tx.id() + " повторяется"));
            }
            if (tx.title().isBlank()) {
                out.add(Diagnostic.warning(prefix + "не указано название"));
            }
            checkAmount(tx.amount(), prefix, out);
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
            String prefix = "Корректировка " + adjustment.key().ruleId() + " от "
                    + DateFormats.ru(adjustment.key().originalDate()) + ": ";
            if (!amount.get().isPositive()) {
                out.add(Diagnostic.error(prefix + "новая сумма должна быть больше нуля"));
            } else if (amount.get().compareTo(MAX_AMOUNT) > 0) {
                out.add(Diagnostic.error(prefix + "новая сумма больше " + MAX_AMOUNT.format()));
            }
        }
    }

    private static void checkAmount(Money amount, String prefix, List<Diagnostic> out) {
        if (!amount.isPositive()) {
            out.add(Diagnostic.error(prefix + "сумма должна быть больше нуля"));
        } else if (amount.compareTo(MAX_AMOUNT) > 0) {
            out.add(Diagnostic.error(prefix + "сумма больше " + MAX_AMOUNT.format()));
        }
    }
}
