package ru.cashprediction.core.support;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Канонический нагрузочный план S5: 77 еженедельных правил на 600 месяцев и независимый календарный оракул.
 * Суммы, порядок правил и дни недели совпадают с нагрузкой S1; поисковые маркеры являются данными операций.
 * В отличие от меньшей {@link LargeWeeklyPlanFixture}, здесь нет разовых, корректировок и синтетических событий.
 *
 * <p>{@link #prepare(Path)} выполняется ДО будущего таймера открытия. Подготовка сохраняет обычный Markdown
 * через репозиторий и рассчитывает только независимые ожидания. Она не читает целевой файл обратно,
 * не создаёт документ/контроллер и не вызывает движок, генератор повторов, таблицу или matcher приложения.
 * Первый настоящий load/forecast остаётся частью измеряемого открытия.</p>
 *
 * <p>Ожидания таблицы определены для PeriodChoice.ALL. Обычное открытие сворачивает прошедшие события;
 * отдельный опыт раскрывает их. START, PAST_HEADER и итоги месяцев не входят в число реальных событий.
 * Сама фикстура не задаёт таймеров и не доказывает задержку какого-либо клиента.</p>
 */
public final class LargePerformancePlanFixture {
    /** Фиксированное воскресенье из контракта S5. */
    public static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    /** Начало за шесть месяцев до сегодня, включая прошедшие события. */
    public static final LocalDate START = TODAY.minusMonths(6);
    /** Горизонт в месяцах, не период отображения M12. */
    public static final int MONTHS = 600;
    /** Включённые правила: по одиннадцать на каждый день недели. */
    public static final int WEEKLY_RULES = 77;
    /** Конец горизонта включительно, рассчитанный календарём JDK. */
    public static final LocalDate END = START.plusMonths(MONTHS).minusDays(1);
    /** Начальный миллион рублей в копейках. */
    public static final long START_BALANCE_MINOR = 100_000_000L;
    /** Реальные события без START, PAST_HEADER и итогов. */
    public static final int EXPECTED_EVENT_COUNT = 200_893;
    /** Реальные события строго раньше TODAY. */
    public static final int EXPECTED_PAST_EVENT_COUNT = 2_024;
    /** Часы для будущего контроллера, не зависящие от даты запуска теста. */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);

    private LargePerformancePlanFixture() {
    }

    /** @return фиксированная сегодняшняя дата для чтения плана и запуска клиента */
    public static LocalDate today() {
        return TODAY;
    }

    /**
     * Создаёт только входной домен: правило i имеет сумму 100+i рублей и weekday i%7+1.
     * Чётные индексы означают доход, нечётные расход; переносов с выходных нет.
     * @return неизменяемый план без предварительно рассчитанного прогноза
     */
    public static Plan create() {
        List<RecurringRule> rules = new ArrayList<>(WEEKLY_RULES);
        for (int i = 0; i < WEEKLY_RULES; i++) {
            rules.add(new RecurringRule(new RuleId("r" + (i + 1)), title(i),
                    i % 2 == 0 ? Kind.INCOME : Kind.EXPENSE, Money.ofMajor(100L + i), category(i),
                    new Recurrence.Weekly(DayOfWeek.of(i % 7 + 1), 1), START, END,
                    WeekendPolicy.NONE, true, note(i)));
        }
        return new Plan("S5-performance-weekly-600", "", Plan.DEFAULT_CURRENCY, START,
                new Money(START_BALANCE_MINOR), new Horizon.Months(MONTHS), Money.ZERO, null,
                rules, List.of(), List.of(), List.of());
    }

    /**
     * Сохраняет обычный файл плана производственным писателем в изолированную CashMemory.
     * @param isolatedCashMemory папка, принадлежащая вызывающему тесту
     * @return абсолютный путь к файлу для последующего настоящего открытия
     * @throws IOException при существующем целевом файле или ошибке записи
     */
    public static Path write(Path isolatedCashMemory) throws IOException {
        Files.createDirectories(isolatedCashMemory);
        PlanRepository repository = new PlanRepository(isolatedCashMemory);
        Plan plan = create();
        Path file = repository.pathFor(plan.name());
        if (Files.exists(file)) {
            throw new FileAlreadyExistsException(file.toString());
        }
        repository.save(plan, file);
        return file;
    }

    /**
     * Готовит файл и ожидания вне будущего таймера; файл не читается, прогноз приложения не рассчитывается.
     * @param isolatedCashMemory изолированная папка CashMemory
     * @return входной файл и независимые ответы для будущей проверки после render checkpoint
     * @throws IOException при ошибке сохранения
     */
    public static Prepared prepare(Path isolatedCashMemory) throws IOException {
        return new Prepared(write(isolatedCashMemory), expected());
    }

    /**
     * Перебирает дни григорианского календаря и известные индексы правил, затем складывает целые копейки.
     * Доходы каждого дня предшествуют расходам, внутри типа сохраняется входной порядок правил.
     * Ни даты, ни суммы, ни балансы не берутся из production forecast или сохранённого/прочитанного Plan.
     * @return все реальные события, ежедневные балансы и полные итоги каждого месяца
     */
    public static ExpectedLedger expected() {
        List<ExpectedEvent> events = new ArrayList<>(EXPECTED_EVENT_COUNT);
        Map<LocalDate, Long> daily = new LinkedHashMap<>();
        Map<YearMonth, long[]> sums = new LinkedHashMap<>();
        String[] titles = new String[WEEKLY_RULES];
        String[] categories = new String[WEEKLY_RULES];
        String[] notes = new String[WEEKLY_RULES];
        for (int i = 0; i < WEEKLY_RULES; i++) {
            titles[i] = title(i);
            categories[i] = category(i);
            notes[i] = note(i);
        }
        long balance = START_BALANCE_MINOR;
        long income = 0;
        long expense = 0;
        for (LocalDate date = START; !date.isAfter(END); date = date.plusDays(1)) {
            long[] month = sums.computeIfAbsent(YearMonth.from(date), ignored -> new long[2]);
            // Два прохода задают порядок из финансового контракта, без сортировки строк движка.
            for (int parity = 0; parity < 2; parity++) {
                for (int i = date.getDayOfWeek().getValue() - 1; i < WEEKLY_RULES; i += 7) {
                    if (i % 2 != parity) continue;
                    long amount = (100L + i) * 100 * (parity == 0 ? 1 : -1);
                    balance = Math.addExact(balance, amount);
                    if (amount > 0) {
                        income = Math.addExact(income, amount);
                        month[0] = Math.addExact(month[0], amount);
                    } else {
                        expense = Math.subtractExact(expense, amount);
                        month[1] = Math.subtractExact(month[1], amount);
                    }
                    events.add(new ExpectedEvent(i, "r" + (i + 1) + "@" + date, date,
                            titles[i], categories[i], notes[i], amount, balance));
                }
            }
            daily.put(date, balance);
        }
        Map<YearMonth, ExpectedMonth> months = new LinkedHashMap<>();
        sums.forEach((month, amounts) -> {
            LocalDate close = month.atEndOfMonth().isAfter(END) ? END : month.atEndOfMonth();
            months.put(month, new ExpectedMonth(amounts[0], amounts[1], amounts[0] - amounts[1], daily.get(close)));
        });
        return new ExpectedLedger(events, daily, months, income, expense, balance);
    }

    /**
     * Задаёт ожидаемые совпадения индексами правил, не нормализуя и не сопоставляя строки.
     * category-only и note-only маркеры находятся ровно в одном поле и отсутствуют в названиях.
     * @return независимые случаи пустого, широкого, точечного, отрицательного поиска, регистра и ё/е
     */
    public static List<SearchCase> searchCases() {
        Set<Integer> all = new LinkedHashSet<>();
        for (int i = 0; i < WEEKLY_RULES; i++) all.add(i);
        return List.of(
                new SearchCase("clear", "", all),
                new SearchCase("title-large", "weekly titleprobe", all),
                new SearchCase("title-mixed-case-spaces", "  wEeKlY   tItLePrObE   07  ", Set.of(6)),
                new SearchCase("category-only", "categoryneedle only", weekdayIndexes(0)),
                new SearchCase("note-only", "noteneedle only", weekdayIndexes(1)),
                new SearchCase("note-mixed-case-spaces", "  nOtEnEeDlE   oNlY  ", weekdayIndexes(1)),
                new SearchCase("category-yo-as-e", "еЛкА mIxEdCaTeGoRy", weekdayIndexes(2)),
                new SearchCase("category-yo-uppercase", "ЁЛКА MIXEDCATEGORY", weekdayIndexes(2)),
                new SearchCase("note-yo-as-e", "ЕЖ mixednote", weekdayIndexes(3)),
                new SearchCase("note-e-as-yo", "ЁнОт pLaInNoTe", weekdayIndexes(4)),
                new SearchCase("no-match", "absent-search-needle-928461", Set.of()));
    }

    /**
     * Строит независимый порядок строк ALL: START, при необходимости группа, события и итоги видимых месяцев.
     * Пустой набор событий означает filtered placeholder без одиночной строки START.
     * Счётчик прошедших относится ко всем совпавшим событиям, даже когда группа свёрнута.
     * Финансы строк и итогов всегда берутся из полного ledger, а не из результатов поиска.
     * Рассчитать нужные варианты ДО будущего таймера фильтра; полные списки сравнивать после checkpoint.
     * @param ledger полный независимый календарь
     * @param search случай с заранее заданными индексами совпадающих правил
     * @param pastExpanded раскрыта ли группа прошедших
     * @param monthTotals включены ли итоги месяцев
     * @return порядок id, отдельные счётчики и первая строка для прокрутки к сегодня
     */
    public static ExpectedView expectedAllView(ExpectedLedger ledger, SearchCase search,
                                              boolean pastExpanded, boolean monthTotals) {
        List<ExpectedEvent> matched = ledger.events().stream()
                .filter(event -> search.matchingRuleIndexes().contains(event.ruleIndex())).toList();
        int past = (int) matched.stream().filter(event -> event.date().isBefore(TODAY)).count();
        if (matched.isEmpty()) {
            return new ExpectedView(List.of(), 0, 0, 0, 0, "");
        }
        List<ExpectedEvent> visible = pastExpanded ? matched
                : matched.stream().filter(event -> !event.date().isBefore(TODAY)).toList();
        List<String> ids = new ArrayList<>();
        ids.add("start");
        if (past > 0) ids.add("past@group");
        int totalCount = 0;
        for (int i = 0; i < visible.size(); i++) {
            ExpectedEvent event = visible.get(i);
            ids.add(event.rowId());
            YearMonth month = YearMonth.from(event.date());
            if (monthTotals && (i + 1 == visible.size()
                    || !month.equals(YearMonth.from(visible.get(i + 1).date())))) {
                ids.add("total@" + month);
                totalCount++;
            }
        }
        String scrollTo = matched.stream().filter(event -> !event.date().isBefore(TODAY))
                .map(ExpectedEvent::rowId).findFirst().orElse("");
        return new ExpectedView(ids, matched.size(), past, visible.size(), totalCount, scrollTo);
    }

    /** @return название с двумя цифрами, чтобы точечный поиск 07 не совпадал с 70..77 */
    private static String title(int index) {
        int number = index + 1;
        return "Weekly TitleProbe " + (number < 10 ? "0" : "") + number;
    }

    /** @return категория с отдельными маркерами только у понедельника и среды */
    private static String category(int index) {
        return switch (index % 7) {
            case 0 -> "CategoryNeedle Only";
            case 2 -> "ЁлКа MixedCategory";
            default -> "Group " + (index % 7);
        };
    }

    /** @return заметка с отдельными маркерами вторника, четверга и пятницы */
    private static String note(int index) {
        return switch (index % 7) {
            case 1 -> "NoteNeedle   Only";
            case 3 -> "ёж MixedNote";
            case 4 -> "Енот PlainNote";
            default -> "Memo " + (index + 1);
        };
    }

    /** @return известные индексы одиннадцати правил заданного дня недели, от понедельника 0 */
    private static Set<Integer> weekdayIndexes(int weekday) {
        Set<Integer> indexes = new LinkedHashSet<>();
        for (int i = weekday; i < WEEKLY_RULES; i += 7) indexes.add(i);
        return Set.copyOf(indexes);
    }

    /**
     * Подготовка для будущего таймера: только файл и независимые ответы, без production forecast.
     * @param file сохранённый обычный Markdown
     * @param expected независимый календарь
     */
    public record Prepared(Path file, ExpectedLedger expected) {
    }

    /**
     * Одно настоящее событие календаря; начальный баланс не входит в этот список.
     * @param ruleIndex индекс правила от нуля в исходном плане
     * @param rowId независимый id вида r1@2026-03-16
     * @param date календарная дата без переносов
     * @param title исходное название
     * @param category исходная категория
     * @param note исходная заметка
     * @param amountMinor сумма со знаком в копейках
     * @param balanceAfterMinor полный баланс после события, включая не найденные поиском операции
     */
    public record ExpectedEvent(int ruleIndex, String rowId, LocalDate date, String title, String category,
                                String note, long amountMinor, long balanceAfterMinor) {
    }

    /**
     * Полный итог месяца независимо от поиска и сворачивания прошедших.
     * @param incomeMinor положительная сумма доходов
     * @param expenseMinor положительная сумма расходов
     * @param netMinor доходы минус расходы
     * @param closingBalanceMinor баланс на конец месяца либо на END в последнем неполном месяце
     */
    public record ExpectedMonth(long incomeMinor, long expenseMinor, long netMinor, long closingBalanceMinor) {
    }

    /**
     * Неизменяемые независимые ответы всего горизонта, включая оба неполных крайних месяца.
     * @param events реальные события в финансовом порядке
     * @param dailyBalances баланс на конец каждого дня START..END
     * @param months полные финансовые итоги 601 календарного месяца
     * @param totalIncomeMinor все доходы горизонта
     * @param totalExpenseMinor все расходы горизонта
     * @param endBalanceMinor конечный баланс
     */
    public record ExpectedLedger(List<ExpectedEvent> events, Map<LocalDate, Long> dailyBalances,
                                 Map<YearMonth, ExpectedMonth> months, long totalIncomeMinor,
                                 long totalExpenseMinor, long endBalanceMinor) {
        /** Защищает ответы от изменений и сохраняет календарный порядок словарей. */
        public ExpectedLedger {
            events = List.copyOf(events);
            dailyBalances = Collections.unmodifiableMap(new LinkedHashMap<>(dailyBalances));
            months = Collections.unmodifiableMap(new LinkedHashMap<>(months));
        }
    }

    /**
     * Поисковый случай с явным независимым набором правил; приложение проверяется этим набором, не наоборот.
     * @param name стабильное имя опыта
     * @param query ввод в настоящий фильтр
     * @param matchingRuleIndexes ожидаемые индексы правил от нуля; START не является совпадением
     */
    public record SearchCase(String name, String query, Set<Integer> matchingRuleIndexes) {
        /** Копирует набор, чтобы будущий runner не мог поменять ожидаемый результат. */
        public SearchCase {
            matchingRuleIndexes = Set.copyOf(matchingRuleIndexes);
        }
    }

    /**
     * Ожидания только для ALL, с отдельными счётчиками событий и служебных строк.
     * @param rowIds все видимые id; пустой список требует filtered placeholder
     * @param matchedEventCount все совпавшие реальные события, до сворачивания
     * @param pastEventCount совпавшие события строго раньше TODAY
     * @param visibleEventCount реальные события после сворачивания
     * @param monthTotalCount видимые месячные итоги, без START и PAST_HEADER
     * @param scrollToRowId первая совпавшая строка с датой не раньше TODAY
     */
    public record ExpectedView(List<String> rowIds, int matchedEventCount, int pastEventCount,
                               int visibleEventCount, int monthTotalCount, String scrollToRowId) {
        /** Сохраняет независимый порядок id неизменяемым. */
        public ExpectedView {
            rowIds = List.copyOf(rowIds);
        }
    }
}
