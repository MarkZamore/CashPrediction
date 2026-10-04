package ru.cashprediction.core.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Настоящий финансовый план на 600 месяцев для тестов домена и будущих запусков трёх клиентов.
 * Строки операций являются пользовательскими данными, а не подписями интерфейса.
 * Фикстура не создаёт прогноз, состояние приложения, настройки или снимок сессии.
 */
public final class LargeWeeklyPlanFixture {
    /** Фиксированные часы: начало плана приходится на понедельник. */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-05T00:00:00Z"), ZoneOffset.UTC);

    private LargeWeeklyPlanFixture() {
    }

    /** @return фиксированная дата для чтения плана и расчёта прогноза */
    public static LocalDate today() {
        return LocalDate.now(CLOCK);
    }

    /**
     * Создаёт пять еженедельных доходов и пять еженедельных расходов на всём горизонте.
     * Корректировка заметки замещает заметку правила согласно семантике движка.
     * @return неизменяемый план без вычисленных строк
     */
    public static Plan create() {
        LocalDate start = today();
        LocalDate end = start.plusMonths(600).minusDays(1);
        List<RecurringRule> rules = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rules.add(new RecurringRule(new RuleId("r" + (i + 1)), "Weekly income " + i,
                    Kind.INCOME, Money.ofMajor(12_000 + i * 100), "Доход Ёлка",
                    new Recurrence.Weekly(DayOfWeek.MONDAY, 1), start, end,
                    WeekendPolicy.NONE, true, "Премия   ALPHA"));
        }
        for (int i = 0; i < 5; i++) {
            rules.add(new RecurringRule(new RuleId("r" + (i + 6)), "Weekly expense " + i,
                    Kind.EXPENSE, Money.ofMajor(15_000 + i * 100), "Расход Быт",
                    new Recurrence.Weekly(DayOfWeek.FRIDAY, 1), start, end,
                    WeekendPolicy.NONE, true, "покупка beta"));
        }
        return new Plan("S5-weekly-600", "", Plan.DEFAULT_CURRENCY, start, Money.ofMajor(25_000),
                new Horizon.Months(600), Money.ofMajor(20_000),
                new Goal("Резерв", Money.ofMajor(100_000), end), rules,
                List.of(new OneTimeTransaction(new TxId("t1"), end, "Equipment",
                        Kind.EXPENSE, Money.ofMajor(345), "Резерв ёж", "Разовая ГАММА")),
                List.of(new Adjustment(new OccurrenceKey(new RuleId("r1"), start.plusDays(14)),
                                new Adjustment.ChangeAmount(Money.ofMajor(17_500)), "Замена DELTA"),
                        new Adjustment(new OccurrenceKey(new RuleId("r6"), start.plusDays(11)),
                                new Adjustment.Skip(), "Отмена SIGMA")),
                List.of());
    }

    /**
     * Сохраняет план каноническим писателем через настоящий репозиторий.
     * Вызывать только с отдельной тестовой папкой CashMemory; существующий одноимённый файл запрещён.
     * @param isolatedCashMemory изолированная папка, принадлежащая вызывающему тесту
     * @return путь к обычному файлу плана для последующего открытия клиентом
     * @throws IOException при существующем файле или ошибке записи
     */
    public static Path write(Path isolatedCashMemory) throws IOException {
        Files.createDirectories(isolatedCashMemory);
        PlanRepository repository = new PlanRepository(isolatedCashMemory);
        Plan plan = create();
        Path file = repository.pathFor(plan.name());
        if (Files.exists(file)) {
            throw new IOException("Fixture target already exists: " + file.getFileName());
        }
        repository.save(plan, file);
        return file;
    }
}
