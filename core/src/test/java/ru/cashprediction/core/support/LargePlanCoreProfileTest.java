package ru.cashprediction.core.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.AppClock;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.app.UiPort;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.support.LargePerformancePlanFixture.ExpectedEvent;
import ru.cashprediction.core.support.LargePerformancePlanFixture.ExpectedLedger;
import ru.cashprediction.core.support.LargePerformancePlanFixture.ExpectedView;
import ru.cashprediction.core.support.LargePerformancePlanFixture.Prepared;
import ru.cashprediction.core.support.LargePerformancePlanFixture.SearchCase;
import ru.cashprediction.core.text.CoreModuleDir;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.menu.MenuModels;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.ScreenPart;
import ru.cashprediction.core.ui.view.chart.ChartLayout;
import ru.cashprediction.core.ui.view.chart.ChartModel;
import ru.cashprediction.core.ui.view.chart.ChartPrimitive;
import ru.cashprediction.core.ui.view.chart.ChartScene;
import ru.cashprediction.core.ui.view.chart.HitRegion;
import ru.cashprediction.core.ui.view.status.StatusBuilder;
import ru.cashprediction.core.ui.view.status.StatusModel;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.ui.view.table.LazyTableModel;
import ru.cashprediction.core.ui.view.table.Placeholder;
import ru.cashprediction.core.ui.view.table.RowKind;
import ru.cashprediction.core.ui.view.table.TableModel;
import ru.cashprediction.core.ui.view.table.TableRowView;

/**
 * Добровольный диагностический профиль настоящего общего ядра на сохранённом плане S5.
 * Запуск включается только свойством {@code cashprediction.performance.profile=true}.
 * Без него JUnit пропускает тест до создания файлов и независимых ответов.
 *
 * <p>Первое чтение, первый ленивый прогноз и первый индекс измеряются отдельно, до разогрева большого плана.
 * Далее сохраняются все разогревочные и все измеряемые пробы, включая медленные и ошибочные.
 * Полный обход строк является отдельной диагностикой материализации, а не работой виртуального viewport.
 * Проверки и подготовка ответов не входят в таймеры; небольшие счётчики внутри обходов входят и не вычитаются.</p>
 *
 * <p>Нет клиентов, debounce, HTTP, фоновой записи сеанса и подтверждения paint. Порт только считает синхронные
 * публикации общего контроллера; отдельные вызовы построителей не являются инструментированием его внутренних
 * refresh. Эти времена нельзя складывать с временем controller.filterText, где те же построители уже вызываются.
 * Тест не проверяет UI latency acceptance, не заменяет DomainLatencyGate и не задаёт временного PASS-бюджета.
 * Пропуск также не является приёмкой S5. Запись ограничена новой папкой UUID в core/target/performance.</p>
 */
@EnabledIfSystemProperty(named = "cashprediction.performance.profile", matches = "true")
class LargePlanCoreProfileTest {
    private static final int WARMUP_ROUNDS = 1;
    private static final int MEASURED_ROUNDS = 3;
    private static final int PAGE_ROWS = 300;
    private static final double CHART_WIDTH = 1200;
    private static final double CHART_HEIGHT = 700;
    private static final long HASH_SEED = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    /** Создаёт изолированный отчёт и выполняет настоящий профиль, сохраняя данные также при assertion failure. */
    @Test
    void profileSavedLargePlanAndActualControllerMutations() throws Exception {
        Path run = newReportDirectory();
        Path output = run.resolve("core-profile.json");
        Map<String, Object> report = metadata(run);
        List<Map<String, Object>> samples = new ArrayList<>();
        report.put("samples", samples);
        writeReport(output, report);
        try {
            // Здесь только сохранение входа и независимый календарь, без load/forecast/matcher приложения.
            Prepared prepared = LargePerformancePlanFixture.prepare(run.resolve("CashMemory"));
            List<Answers> answers = prepareAnswers(prepared.expected());
            report.put("fixture", fixtureMetadata(prepared));
            Answers clearCollapsed = answer(answers, "clear", false);
            profileFileComponents(samples, "first-open", 0, prepared, clearCollapsed);
            // Хеш читается после первого timed load: дополнительное чтение не прогревает его заранее.
            report.put("planSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(prepared.file()))));
            writeReport(output, report);

            for (int round = 0; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
                String phase = round < WARMUP_ROUNDS ? "warmup" : "measurement";
                profileFileComponents(samples, phase, round, prepared, clearCollapsed);
                writeReport(output, report);
            }
            profileController(samples, prepared, answers, output, report);
            report.put("outcome", "diagnostic-completed-semantic-checks-passed");
        } catch (Exception | AssertionError failure) {
            report.put("outcome", "diagnostic-failed");
            report.put("failure", Map.of("type", failure.getClass().getName(),
                    "message", String.valueOf(failure.getMessage())));
            throw failure;
        } finally {
            report.put("finishedUtc", Instant.now().toString());
            writeReport(output, report);
            System.out.println("Shared-core diagnostic report: " + output);
        }
    }

    /** Проверяет папку модуля из project.basedir (core.basedir) и запрещает выход отчёта через симлинки. */
    private static Path newReportDirectory() throws IOException {
        Path core = CoreModuleDir.get().toRealPath();
        assertTrue(Files.isRegularFile(core.resolve("pom.xml")), "core project.basedir is required");
        assertTrue(Files.isDirectory(core.resolve("src/main/java/ru/cashprediction/core")), "core source marker");
        Path root = core.resolve("target/performance");
        Files.createDirectories(root);
        assertEquals(root, root.toRealPath(), "Performance output must stay inside the real core module");
        return Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
    }

    /** Записывает границы диагностики, параметры процесса и все оговорки вместо фиктивной приёмки. */
    private static Map<String, Object> metadata(Path run) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "cashprediction-shared-core-profile-v1");
        report.put("outcome", "running");
        report.put("runDirectory", run.toString());
        report.put("startedUtc", Instant.now().toString());
        report.put("optInProperty", "cashprediction.performance.profile=true");
        report.put("uiLatencyAcceptance", false);
        report.put("domainLatencyGateExecuted", false);
        report.put("warmupRounds", WARMUP_ROUNDS);
        report.put("measuredRounds", MEASURED_ROUNDS);
        report.put("clock", "System.nanoTime; durations in nanoseconds; no overhead subtraction");
        report.put("firstOpenDefinition", "First repository.load -> new PlanDocument -> first forecast -> ALL table index");
        report.put("controllerDefinition", "Normal FILE_RECENT_OPEN/filterText through shared core, synchronous counting UiPort");
        report.put("componentDefinition", "Isolated public production calls on the resulting state; not internal refresh spans");
        report.put("samplingPolicy", "Every first-open/warmup/measurement sample retained; no best-of or outlier removal");
        report.put("limitations", List.of("JVM/JIT may already be warmed by other JUnit tests",
                "OS filesystem cache is not cold: setup saved the file",
                "Controller profile follows the first component measurements",
                "No real renderer, input listener, debounce, transport, startup, paint or physical display measurement",
                "Controller construction/showMain uses an empty document as untimed setup",
                "Manual scheduler is not advanced; settings/status timers and session persistence are excluded",
                "Full-table row scan includes small count/digest observers and does not represent viewport latency",
                "Controller totals overlap isolated component work; never add them together",
                "Skipping this opt-in test does not satisfy S5 acceptance"));
        report.put("environment", Map.of("javaVersion", System.getProperty("java.version"),
                "javaVm", System.getProperty("java.vm.name"), "osName", System.getProperty("os.name"),
                "osVersion", System.getProperty("os.version"), "osArch", System.getProperty("os.arch"),
                "processors", Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory(), "simulatedClient", "SWING",
                "chartWidth", CHART_WIDTH, "chartHeight", CHART_HEIGHT));
        return report;
    }

    /** Сохраняет JSON только по принадлежащему этому запуску пути. */
    private static void writeReport(Path output, Map<String, Object> report) throws IOException {
        Files.writeString(output, JsonWriter.writePretty(report) + "\n", StandardCharsets.UTF_8);
    }

    /** Фиксирует независимые точные числа и календарные границы входной нагрузки. */
    private static Map<String, Object> fixtureMetadata(Prepared prepared) {
        return Map.of("file", prepared.file().toString(), "weeklyRules", LargePerformancePlanFixture.WEEKLY_RULES,
                "months", LargePerformancePlanFixture.MONTHS, "realEvents", prepared.expected().events().size(),
                "dailyBalances", prepared.expected().dailyBalances().size(), "monthTotals", prepared.expected().months().size(),
                "today", LargePerformancePlanFixture.TODAY.toString(), "start", LargePerformancePlanFixture.START.toString(),
                "end", LargePerformancePlanFixture.END.toString(), "endBalanceMinor", prepared.expected().endBalanceMinor());
    }

    /** Готовит все варианты ALL, их digests и ожидаемые ячейки исключительно из независимого календаря. */
    private static List<Answers> prepareAnswers(ExpectedLedger ledger) {
        List<Answers> result = new ArrayList<>();
        for (SearchCase search : LargePerformancePlanFixture.searchCases()) {
            long matchedHash = HASH_SEED;
            for (ExpectedEvent event : ledger.events()) {
                if (search.matchingRuleIndexes().contains(event.ruleIndex())) matchedHash = hash(matchedHash, event.rowId());
            }
            for (boolean expanded : List.of(false, true)) {
                ExpectedView view = LargePerformancePlanFixture.expectedAllView(ledger, search, expanded, true);
                Map<Integer, List<String>> cells = new LinkedHashMap<>();
                for (int index : probeIndexes(view)) {
                    String id = view.rowIds().get(index);
                    if (id.equals("start") || id.equals("past@group")) continue;
                    if (id.startsWith("total@")) {
                        var month = ledger.months().get(YearMonth.parse(id.substring(6)));
                        cells.put(index, List.of(formatMinor(month.incomeMinor()), formatMinor(month.expenseMinor()),
                                formatMinor(month.closingBalanceMinor())));
                    } else {
                        ExpectedEvent event = expectedEvent(ledger, id);
                        cells.put(index, List.of(event.date().format(DateTimeFormatter.ofPattern("dd.MM.uuuu")),
                                event.title(), event.category(), event.amountMinor() > 0 ? formatMinor(event.amountMinor()) : "",
                                event.amountMinor() < 0 ? formatMinor(-event.amountMinor()) : "",
                                formatMinor(event.balanceAfterMinor())));
                    }
                }
                result.add(new Answers(search, expanded, view, digestIds(view.rowIds()), matchedHash, cells,
                        pageStarts(view), probeIndexes(view)));
            }
        }
        return List.copyOf(result);
    }

    /** Находит уже подготовленный ответ, не вычисляя ожидаемый результат из production модели. */
    private static Answers answer(List<Answers> answers, String name, boolean expanded) {
        return answers.stream().filter(value -> value.search().name().equals(name) && value.expanded() == expanded)
                .findFirst().orElseThrow();
    }

    /** Измеряет настоящее чтение, создание документа, первый forecast и полный ALL-индекс без precomputed forecast. */
    private static void profileFileComponents(List<Map<String, Object>> samples, String phase, int round,
                                               Prepared prepared, Answers expected) throws Exception {
        Map<String, Object> sample = sample(samples, phase, round, "file-components", expected, ViewMode.TABLE);
        PlanRepository repository = new PlanRepository(prepared.file().getParent());
        var read = timed(sample, "repository.load-read-parse-validate", () -> repository.load(prepared.file(), LargePerformancePlanFixture.TODAY));
        PlanDocument document = timed(sample, "document.construct-set-ALL-view", () -> {
            PlanDocument fresh = new PlanDocument(read.plan(), prepared.file(), LargePerformancePlanFixture::today);
            fresh.setViewState(all("", ViewMode.TABLE));
            return fresh;
        });
        Forecast forecast = timed(sample, "document.first-forecast", document::forecast);
        AppState state = timed(sample, "app-state.snapshot", () -> state(prepared.file(), document, forecast, false));
        TableModel table = timed(sample, "table.index-build-ALL", () -> LazyTableModel.build(state, 1));
        assertTrue(read.diagnostics().isEmpty(), read.diagnostics().toString());
        verifyForecast(sample, forecast, prepared.expected());
        profileModels(sample, state, document, table, expected, prepared.expected());
        check(sample, "saved-plan-read-without-diagnostics-and-first-forecast-verified");
    }

    /** Создаёт снимок для изолированных построителей; production прогноз получен только внутри timed открытия. */
    private static AppState state(Path file, PlanDocument document, Forecast forecast, boolean expanded) {
        DocumentView view = new DocumentView(document.plan(), file, false, false, "", false, "", forecast, "", List.of());
        return new AppState(1, ClientProfile.swing(), LargePerformancePlanFixture.TODAY, file.getParent(), null,
                view, document.viewState(), "", expanded, AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /** Открывает через настоящий flow и меняет реальные фильтры контроллера в TABLE/CHART и обоих видах прошлого. */
    private static void profileController(List<Map<String, Object>> samples, Prepared prepared, List<Answers> answers,
                                          Path output, Map<String, Object> report) throws Exception {
        CountingPort observer = new CountingPort();
        AppController app = new AppController(observer.port(), new AppEnvironment(
                LaunchOptions.parse("--registry", "memory"), prepared.file().getParent().getParent(), prepared.file().getParent(),
                AppClock.fixedToday(LargePerformancePlanFixture.TODAY)));
        app.showMain(null);
        app.updateView(view -> all("", ViewMode.TABLE));
        for (int round = -1; round < WARMUP_ROUNDS + MEASURED_ROUNDS; round++) {
            String phase = round == -1 ? "controller-first-open-after-components"
                    : round < WARMUP_ROUNDS ? "warmup" : "measurement";
            Answers clear = answer(answers, "clear", false);
            Map<String, Object> opened = sample(samples, phase, round, "controller-open", clear, ViewMode.TABLE);
            // Подготовка пустого фильтра/ALL вне таймера открытия, без прогноза целевого нового документа.
            app.updateView(view -> all("", ViewMode.TABLE));
            Forecast previous = app.document().forecast();
            observer.reset();
            timed(opened, "controller.FILE_RECENT_OPEN-sync-effects", () -> {
                app.command(CommandId.FILE_RECENT_OPEN, CommandArgs.keyValue("", prepared.file().toString()), InvokeSource.MENU);
                return null;
            });
            opened.put("effects", observer.evidence());
            Forecast forecast = app.document().forecast();
            assertNotSame(previous, forecast, "Every reopen must calculate a new forecast");
            assertEquals(prepared.file(), app.document().file().orElseThrow());
            assertFalse(app.state().pastExpanded());
            assertTrue(app.document().loadDiagnostics().isEmpty());
            assertEquals(clear.view().scrollToRowId(), observer.revealedRow);
            assertTrue(observer.renders > 0);
            verifyForecast(opened, forecast, prepared.expected());
            verifyTable(opened, observer.screen.table(), clear);
            check(opened, "actual-open-replaced-forecast-published-screen-and-revealed-today");
            writeReport(output, report);
            if (round == -1) continue;

            var baselineSummary = observer.screen.summary();
            for (ViewMode mode : List.of(ViewMode.TABLE, ViewMode.CHART)) {
                for (boolean expanded : List.of(false, true)) {
                    Map<String, Object> setup = sample(samples, phase, round, "controller-view-setup", clear, mode);
                    setup.put("pastExpanded", expanded);
                    observer.reset();
                    timed(setup, "controller.mode-period-ALL-chart-bars-and-past", () -> {
                        app.updateView(view -> all(view.filterText(), mode));
                        app.setPastExpanded(expanded);
                        return null;
                    });
                    setup.put("effects", observer.evidence());
                    // Каждая серия заканчивается no-match; для первой clear тоже нужна настоящая мутация.
                    if (app.state().view().filterText().isEmpty()) {
                        Answers noMatch = answer(answers, "no-match", expanded);
                        Map<String, Object> seed = sample(samples, phase, round, "controller-seed-no-match", noMatch, mode);
                        observer.reset();
                        timed(seed, "controller.filterText-sync-effects", () -> {
                            app.filterText(noMatch.search().query());
                            return null;
                        });
                        seed.put("effects", observer.evidence());
                        verifyTable(seed, observer.screen.table(), noMatch);
                        check(seed, "seed-is-a-real-no-match-filter-mutation");
                    }
                    for (Answers expected : answers) {
                        if (expected.expanded() != expanded) continue;
                        Map<String, Object> mutation = sample(samples, phase, round, "controller-filter", expected, mode);
                        TableModel oldTable = observer.screen.table();
                        ChartModel oldChart = observer.screen.chart();
                        long oldRevision = observer.screen.revision();
                        assertFalse(app.state().view().filterText().equals(expected.search().query()), "Must mutate filter text");
                        observer.reset();
                        timed(mutation, "controller.filterText-sync-effects", () -> {
                            app.filterText(expected.search().query());
                            return null;
                        });
                        mutation.put("effects", observer.evidence());
                        mutation.put("screenRevision", observer.screen.revision());
                        mutation.put("publishedChartRevision", observer.screen.chart().revision());
                        AppState state = timed(mutation, "controller.state-cached-forecast", app::state);
                        assertSame(forecast, state.document().forecast(), "Search must retain forecast identity");
                        assertEquals(expected.search().query(), state.view().filterText());
                        assertEquals(PeriodChoice.ALL, state.view().period());
                        assertEquals(mode, state.view().mode());
                        assertEquals(expanded, state.pastExpanded());
                        assertTrue(observer.screen.revision() > oldRevision);
                        assertNotSame(oldTable, observer.screen.table());
                        assertNotSame(oldChart, observer.screen.chart());
                        assertEquals(baselineSummary, observer.screen.summary());
                        assertTrue(observer.tablePublications > 0);
                        assertTrue(observer.chartPublications > 0);
                        assertEquals("", app.tableTooltip(oldTable.revision(), 0, "date"));
                        assertTrue(app.chartHover(oldChart.revision(), 100, 100, CHART_WIDTH, CHART_HEIGHT).isEmpty());
                        ChartScene actual = timed(mutation, "controller.chartScene-first-data-and-layout", () -> app.chartScene(CHART_WIDTH, CHART_HEIGHT));
                        verifyChart(mutation, actual, expected, prepared.expected());
                        profileModels(mutation, state, app.document(), observer.screen.table(), expected, prepared.expected());
                        check(mutation, "actual-filter-revision-forecast-identity-summary-and-stale-queries-verified");
                        writeReport(output, report);
                    }
                }
            }
        }
        observer.delegate.manualScheduler().shutdown();
    }

    /** Измеряет все построители refresh, matcher/navigation, viewport, полный обход строк и ленивый график. */
    private static void profileModels(Map<String, Object> sample, AppState state, PlanDocument document,
                                      TableModel actualTable, Answers expected, ExpectedLedger ledger) throws Exception {
        verifyTable(sample, actualTable, expected);
        MatchScan matches = timed(sample, "view.accepts-all-real-events", () -> scanMatches(state.document().forecast(), state.view()));
        assertEquals(expected.view().matchedEventCount(), matches.count());
        assertEquals(expected.matchedDigest(), matches.digest());
        sample.put("matchedEvents", matches.count());
        sample.put("matchedIdDigest", hex(matches.digest()));
        List<ForecastRow> visible = timed(sample, "document.visibleRows-filter-and-copy", document::visibleRows);
        assertEquals(expected.view().matchedEventCount() + 1, visible.size(), "visibleRows retains START, even for no-match");
        assertEquals(Origin.START, visible.getFirst().origin());
        long visibleDigest = HASH_SEED;
        for (int i = 1; i < visible.size(); i++) visibleDigest = hash(visibleDigest, visible.get(i).rowId());
        assertEquals(expected.matchedDigest(), visibleDigest);
        sample.put("documentVisibleRowsIncludingStart", visible.size());
        sample.put("documentVisibleRealEventIdDigest", hex(visibleDigest));
        var summary = timed(sample, "summary.build", () -> SummaryBuilder.build(state));
        assertEquals(9, summary.cards().size());
        sample.put("summaryCards", summary.cards().size());
        // JavaFX: MenuBar -> Swing: JMenuBar -> Web: nav.
        var menu = timed(sample, "menuBar.build", () -> MenuModels.menuBar(state, state.client()));
        // JavaFX: ToolBar -> Swing: JToolBar -> Web: toolbar.
        var toolbar = timed(sample, "toolbar.build", () -> MenuModels.toolbar(state, state.client()));
        StatusModel status = timed(sample, "status.build-including-matched-row-count", () -> StatusBuilder.build(state, LargePerformancePlanFixture.CLOCK.instant()));
        assertEquals(UiText.get("status.rows", matches.count() + 1), status.find(StatusModel.ROWS).orElseThrow().text());
        sample.put("statusRowsIncludingStart", matches.count() + 1);
        sample.put("statusDigest", hex(hash(HASH_SEED, UiJson.write(status))));

        TableModel isolated = timed(sample, "table.index-build-ALL-isolated", () -> LazyTableModel.build(state, state.revision()));
        sample.put("isolatedTableRevision", isolated.revision());
        verifyTable(sample, isolated, expected);
        List<Integer> indexes = timed(sample, "table.indexOf-start-today-middle-end", () -> {
            List<Integer> result = new ArrayList<>();
            for (int index : expected.probes()) result.add(isolated.indexOf(expected.view().rowIds().get(index)));
            result.add(isolated.indexOf("absent-row-id"));
            return result;
        });
        assertEquals(expected.probes(), indexes.subList(0, expected.probes().size()));
        assertEquals(-1, indexes.getLast().intValue());
        List<List<TableRowView>> pages = timed(sample, "table.viewport-pages-300-start-today-middle-end", () -> {
            List<List<TableRowView>> result = new ArrayList<>();
            for (int first : expected.pages()) {
                List<TableRowView> page = new ArrayList<>();
                for (int index = first; index < Math.min(isolated.rowCount(), first + PAGE_ROWS); index++) page.add(isolated.row(index));
                result.add(page);
            }
            return result;
        });
        int fetched = 0;
        for (int page = 0; page < pages.size(); page++) {
            int first = expected.pages().get(page);
            for (int i = 0; i < pages.get(page).size(); i++) {
                assertEquals(expected.view().rowIds().get(first + i), pages.get(page).get(i).rowId());
                fetched++;
            }
        }
        sample.put("viewportPageRowsFetched", fetched);
        sample.put("viewportPageStarts", expected.pages());
        verifyCells(isolated, expected);
        // Обход использует исходную опубликованную модель: isolated viewport не прогревает её LRU.
        TableScan full = timed(sample, "table.full-row-materialization-with-count-digest", () -> scanTable(actualTable));
        assertEquals(expected.view().rowIds().size(), full.rows());
        assertEquals(expected.tableDigest(), full.idDigest());
        assertEquals(expected.view().visibleEventCount(), full.rules());
        assertEquals(expected.view().monthTotalCount(), full.totals());
        assertEquals(expected.view().rowIds().isEmpty() ? 0 : 1, full.starts());
        assertEquals(expected.view().pastEventCount() > 0 ? 1 : 0, full.pastHeaders());
        sample.put("fullTable", Map.of("rows", full.rows(), "rules", full.rules(), "monthTotals", full.totals(),
                "starts", full.starts(), "pastHeaders", full.pastHeaders(), "idDigest", hex(full.idDigest()),
                "expectedIdDigest", hex(expected.tableDigest()), "cellDigest", hex(full.cellDigest()), "cellCharacters", full.characters()));

        ChartModel chart = timed(sample, "chart.model-create-lazy", () -> ChartLayout.model(state, state.revision()));
        ChartScene scene = timed(sample, "chart.first-data-and-scene-layout", () -> chart.layout(CHART_WIDTH, CHART_HEIGHT));
        ChartScene repeated = timed(sample, "chart.cached-data-scene-layout", () -> chart.layout(CHART_WIDTH, CHART_HEIGHT));
        assertEquals(scene, repeated);
        verifyChart(sample, scene, expected, ledger);
        String title = timed(sample, "window-title-localization", () -> UiText.get("main.title", state.document().plan().name()));
        MainScreenModel screen = timed(sample, "main-screen-model-assemble", () -> new MainScreenModel(
                state.revision(), title, menu, toolbar, summary, isolated, chart, status, state.view().mode()));
        String screenJson = timed(sample, "uiJson.screen-serialize-model-metadata", () -> UiJson.write(screen));
        String pageJson = timed(sample, "uiJson.viewport-pages-serialize", () -> UiJson.write(pages));
        String chartJson = timed(sample, "uiJson.chart-scene-serialize", () -> UiJson.write(scene));
        sample.put("jsonCharacters", Map.of("screen", screenJson.length(), "pages", pageJson.length(), "chartScene", chartJson.length()));
        check(sample, "independent-search-count-id-digest-table-layout-cells-status-chart-and-full-finances-verified");
    }

    /** Проверяет весь прогноз по независимым полям событий, ежедневным балансам и всем 601 финансовым итогам. */
    private static void verifyForecast(Map<String, Object> sample, Forecast forecast, ExpectedLedger ledger) {
        assertEquals(LargePerformancePlanFixture.EXPECTED_EVENT_COUNT, ledger.events().size());
        assertEquals(ledger.events().size() + 1, forecast.rows().size());
        assertEquals(LargePerformancePlanFixture.START, forecast.startDate());
        assertEquals(LargePerformancePlanFixture.END, forecast.endDate());
        assertEquals(LargePerformancePlanFixture.TODAY, forecast.anchor());
        assertEquals(Origin.START, forecast.rows().getFirst().origin());
        long actualHash = HASH_SEED;
        long expectedHash = HASH_SEED;
        for (int i = 0; i < ledger.events().size(); i++) {
            ExpectedEvent expected = ledger.events().get(i);
            ForecastRow row = forecast.rows().get(i + 1);
            assertEquals(Origin.RULE, row.origin());
            actualHash = eventHash(actualHash, row.rowId(), row.date(), row.title(), row.category(), row.note(), row.amount().minor(), row.balanceAfter().minor());
            expectedHash = eventHash(expectedHash, expected.rowId(), expected.date(), expected.title(), expected.category(), expected.note(), expected.amountMinor(), expected.balanceAfterMinor());
        }
        assertEquals(expectedHash, actualHash, "Every forecast event field and balance in calendar order");
        int day = 0;
        for (var entry : ledger.dailyBalances().entrySet()) assertEquals(entry.getValue().longValue(), forecast.balanceMinorAt(day++));
        assertEquals(ledger.dailyBalances().size(), forecast.dayCount());
        assertEquals(ledger.totalIncomeMinor(), forecast.summary().totalIncome().minor());
        assertEquals(ledger.totalExpenseMinor(), forecast.summary().totalExpense().minor());
        assertEquals(ledger.endBalanceMinor(), forecast.endBalance().minor());
        assertEquals(ledger.months().keySet(), forecast.summary().byMonth().keySet());
        ledger.months().forEach((month, expected) -> {
            var actual = forecast.summary().byMonth().get(month);
            assertEquals(expected.incomeMinor(), actual.income().minor());
            assertEquals(expected.expenseMinor(), actual.expense().minor());
            assertEquals(expected.netMinor(), actual.net().minor());
            assertEquals(expected.closingBalanceMinor(), actual.closingBalance().minor());
        });
        sample.put("forecast", Map.of("realEvents", ledger.events().size(), "rowsIncludingStart", forecast.rows().size(),
                "dayCount", forecast.dayCount(), "monthCount", forecast.summary().byMonth().size(),
                "eventDigest", hex(actualHash), "expectedEventDigest", hex(expectedHash), "endBalanceMinor", forecast.endBalance().minor()));
        check(sample, "forecast-all-event-fields-daily-balances-month-totals-and-end-balance");
    }

    /** Проверяет раскладку и filtered placeholder без materialization всех строк внутри таймера. */
    private static void verifyTable(Map<String, Object> sample, TableModel table, Answers expected) {
        assertEquals(expected.view().rowIds().size(), table.rowCount());
        assertEquals(expected.view().scrollToRowId(), table.scrollToRowId());
        if (expected.view().rowIds().isEmpty()) {
            assertEquals(Placeholder.Kind.FILTERED, table.placeholder().kind());
            assertEquals(UiText.get("table.empty.filtered"), table.placeholder().text());
            assertEquals(-1, table.indexOf("start"));
        } else {
            assertNull(table.placeholder());
            assertEquals(0, table.indexOf("start"));
            assertEquals(1, table.indexOf("past@group"));
        }
        sample.put("tableRowCount", table.rowCount());
        sample.putIfAbsent("tableRevision", table.revision());
        sample.put("scrollToRowId", table.scrollToRowId());
    }

    /** Сверяет независимо отформатированные выборочные ячейки начала/сегодня/середины/конца. */
    private static void verifyCells(TableModel table, Answers expected) {
        for (int index : expected.probes()) {
            String id = expected.view().rowIds().get(index);
            TableRowView row = table.row(index);
            assertEquals(id, row.rowId());
            if (id.equals("start")) {
                assertEquals(RowKind.START, row.kind());
                assertEquals("1 000 000,00", row.cells().get(6));
            } else if (id.equals("past@group")) {
                assertEquals(RowKind.PAST_HEADER, row.kind());
                assertEquals(UiText.get(expected.expanded() ? "table.past.expanded" : "table.past.collapsed", expected.view().pastEventCount()), row.cells().getFirst());
            } else if (id.startsWith("total@")) {
                assertEquals(expected.cells().get(index), row.cells().subList(4, 7));
            } else {
                List<String> cells = row.cells();
                assertEquals(expected.cells().get(index), List.of(cells.get(0), cells.get(2), cells.get(3), cells.get(4), cells.get(5), cells.get(6)));
            }
        }
    }

    /** Проверяет полный диапазон и финансы сцены, включая no-match без исчезновения линии/итогов. */
    private static void verifyChart(Map<String, Object> sample, ChartScene scene, Answers expected, ExpectedLedger ledger) {
        assertEquals("", scene.emptyText());
        assertEquals(LargePerformancePlanFixture.START, scene.plot().from());
        assertEquals(ledger.dailyBalances().size(), scene.plot().dayCount());
        List<String> bars = scene.hits().stream().filter(hit -> hit.kind() == HitRegion.Kind.BAR).map(HitRegion::id).toList();
        assertEquals(ledger.months().keySet().stream().map(month -> "bar@" + month).toList(), bars);
        assertEquals(0, scene.hits().stream().filter(hit -> hit.kind() == HitRegion.Kind.MARKER).count());
        assertEquals(!expected.search().matchingRuleIndexes().isEmpty(), scene.legend().stream().anyMatch(item -> item.id().equals("notice")));
        ChartPrimitive.Polyline line = scene.primitives().stream().filter(ChartPrimitive.Polyline.class::isInstance)
                .map(ChartPrimitive.Polyline.class::cast).findFirst().orElseThrow();
        assertEquals(scene.plot().yOf(ledger.endBalanceMinor()), line.points().getLast().y(), 0.000001);
        assertEquals(scene.plot().plotX() + scene.plot().plotWidth(), line.points().getLast().x(), 0.000001);
        sample.put("chart", Map.of("primitives", scene.primitives().size(), "balanceLineVertices", line.points().size(),
                "monthBarHits", bars.size(), "markerHits", 0, "legendItems", scene.legend().size(),
                "sceneDigest", hex(hash(HASH_SEED, UiJson.write(scene)))));
    }

    /** Перебирает production accepts; независимые ожидания и assertions остаются за таймером. */
    private static MatchScan scanMatches(Forecast forecast, ViewState view) {
        int count = 0;
        long digest = HASH_SEED;
        for (ForecastRow row : forecast.rows()) {
            if (row.origin() != Origin.START && view.accepts(row)) {
                count++;
                digest = hash(digest, row.rowId());
            }
        }
        return new MatchScan(count, digest);
    }

    /** Получает каждую строку production таблицы, удерживая только счётчики, digests и LRU самой модели. */
    private static TableScan scanTable(TableModel table) {
        int rules = 0;
        int totals = 0;
        int starts = 0;
        int headers = 0;
        long ids = HASH_SEED;
        long cells = HASH_SEED;
        long characters = 0;
        for (int index = 0; index < table.rowCount(); index++) {
            TableRowView row = table.row(index);
            ids = hash(ids, row.rowId());
            for (String cell : row.cells()) {
                cells = hash(cells, cell);
                characters += cell.length();
            }
            switch (row.kind()) {
                case RULE -> rules++;
                case MONTH_TOTAL -> totals++;
                case START -> starts++;
                case PAST_HEADER -> headers++;
                default -> throw new AssertionError("Unexpected row kind in weekly fixture: " + row.kind());
            }
        }
        return new TableScan(table.rowCount(), rules, totals, starts, headers, ids, cells, characters);
    }

    /** Создаёт запись до операции, чтобы ошибочная проба оставалась в отчёте. */
    private static Map<String, Object> sample(List<Map<String, Object>> samples, String phase, int round,
                                             String operation, Answers expected, ViewMode mode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ordinal", samples.size());
        result.put("phase", phase);
        result.put("round", round);
        result.put("operation", operation);
        result.put("searchCase", expected.search().name());
        result.put("query", expected.search().query());
        result.put("pastExpanded", expected.expanded());
        result.put("mode", mode.name());
        result.put("period", "ALL");
        result.put("chartBars", true);
        result.put("expected", Map.of("matchedRealEvents", expected.view().matchedEventCount(),
                "pastRealEvents", expected.view().pastEventCount(), "visibleRealEvents", expected.view().visibleEventCount(),
                "monthTotalRows", expected.view().monthTotalCount(), "tableRows", expected.view().rowIds().size(),
                "tableIdDigest", hex(expected.tableDigest()), "matchedIdDigest", hex(expected.matchedDigest()),
                "matchingRuleIndexes", expected.search().matchingRuleIndexes().stream().sorted().toList()));
        result.put("timings", new ArrayList<Map<String, Object>>());
        result.put("checks", new ArrayList<String>());
        samples.add(result);
        return result;
    }

    /** Измеряет только вызов, сохраняя каждый raw duration даже при исключении; проверок внутри нет. */
    private static <T> T timed(Map<String, Object> sample, String segment, Operation<T> operation) throws Exception {
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("segment", segment);
        timings(sample).add(timing);
        long start = System.nanoTime();
        try {
            return operation.run();
        } catch (Exception | AssertionError failure) {
            timing.put("error", failure.getClass().getName());
            throw failure;
        } finally {
            timing.put("nanos", System.nanoTime() - start);
        }
    }

    /** Получает принадлежащий записи список таймеров, без обработки production результата. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> timings(Map<String, Object> sample) {
        return (List<Map<String, Object>>) sample.get("timings");
    }

    /** Отмечает только уже завершённую группу семантических проверок. */
    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> sample, String name) {
        ((List<String>) sample.get("checks")).add(name);
    }

    /** Задаёт обязательный полный период и включённые столбцы, чтобы chart bar path тоже был измерен. */
    private static ViewState all(String query, ViewMode mode) {
        return ViewState.defaults().withPeriod(PeriodChoice.ALL).withMonthTotals(true)
                .withChartMarkers(true).withChartBars(true).withFilterText(query).withMode(mode);
    }

    /** Выбирает независимые места начала, сегодня, середины и конца для настоящих страниц по 300 строк. */
    private static List<Integer> pageStarts(ExpectedView view) {
        if (view.rowIds().isEmpty()) return List.of();
        Set<Integer> result = new LinkedHashSet<>();
        result.add(0);
        result.add(Math.max(0, view.rowIds().indexOf(view.scrollToRowId())));
        result.add(view.rowIds().size() / 2);
        result.add(Math.max(0, view.rowIds().size() - PAGE_ROWS));
        return List.copyOf(result);
    }

    /** Выбирает ограниченные индексы для независимой проверки ячеек и indexOf. */
    private static List<Integer> probeIndexes(ExpectedView view) {
        int size = view.rowIds().size();
        if (size == 0) return List.of();
        Set<Integer> result = new LinkedHashSet<>();
        for (int index : List.of(0, 1, 2, 3, size / 4, size / 2, size * 3 / 4, size - 2, size - 1,
                view.rowIds().indexOf(view.scrollToRowId()))) {
            if (index >= 0 && index < size) result.add(index);
        }
        return List.copyOf(result);
    }

    /** Находит календарный ответ среди ровно одиннадцати независимых событий дня. */
    private static ExpectedEvent expectedEvent(ExpectedLedger ledger, String id) {
        LocalDate date = LocalDate.parse(id.substring(id.indexOf('@') + 1));
        int first = Math.toIntExact(ChronoUnit.DAYS.between(LargePerformancePlanFixture.START, date)) * 11;
        return ledger.events().subList(first, first + 11).stream().filter(event -> event.rowId().equals(id)).findFirst().orElseThrow();
    }

    /** Форматирует известные копейки по спецификации без Money/UiFormats приложения. */
    private static String formatMinor(long value) {
        long absolute = Math.abs(value);
        return (value < 0 ? "-" : "") + String.format(Locale.ROOT, "%,d", absolute / 100).replace(',', ' ')
                + String.format(Locale.ROOT, ",%02d", absolute % 100);
    }

    /** Строит детерминированный FNV-1a digest UTF-16 с разделителями значений; это контроль, не криптография. */
    private static long hash(long seed, String value) {
        long result = seed;
        for (int i = 0; i < value.length(); i++) result = (result ^ value.charAt(i)) * HASH_PRIME;
        return (result ^ 0xffff) * HASH_PRIME;
    }

    /** Считает digest независимого порядка id до таймера. */
    private static long digestIds(List<String> ids) {
        long result = HASH_SEED;
        for (String id : ids) result = hash(result, id);
        return result;
    }

    /** Добавляет все проверяемые поля события в digest в фиксированном порядке. */
    private static long eventHash(long seed, String id, LocalDate date, String title, String category,
                                   String note, long amount, long balance) {
        long result = hash(hash(hash(hash(hash(seed, id), date.toString()), title), category), note);
        return hash(hash(result, Long.toString(amount)), Long.toString(balance));
    }

    /** Записывает все 64 бита digest как устойчивую шестнадцатеричную строку JSON. */
    private static String hex(long value) {
        return HexFormat.of().toHexDigits(value);
    }

    /** Независимые ответы одного поискового случая и состояния группы прошедших. */
    private record Answers(SearchCase search, boolean expanded, ExpectedView view, long tableDigest, long matchedDigest,
                           Map<Integer, List<String>> cells, List<Integer> pages, List<Integer> probes) { }

    /** Наблюдаемый результат прохода настоящего production matcher. */
    private record MatchScan(int count, long digest) { }

    /** Наблюдаемый полный обход настоящей таблицы без удержания всех materialized строк. */
    private record TableScan(int rows, int rules, int totals, int starts, int pastHeaders,
                            long idDigest, long cellDigest, long characters) { }

    /** Вызов production сегмента, который может закончиться обычной проверяемой ошибкой. */
    @FunctionalInterface
    private interface Operation<T> {
        /** Выполняет измеряемую операцию; независимые assertions вызываются после её возврата. */
        T run() throws Exception;
    }

    /**
     * Порт-счётчик без renderer: не удерживает прежние большие таблицы, только текущий экран и малые метаданные.
     * Неизмеряемые служебные методы делегируются существующему FakeUiPort с ManualScheduler, без реестра и потоков.
     */
    private static final class CountingPort implements InvocationHandler {
        private final FakeUiPort delegate = new FakeUiPort(ClientProfile.swing());
        private MainScreenModel screen;
        private int renders;
        private int tablePublications;
        private int chartPublications;
        private String revealedRow = "";
        private final List<Map<String, Object>> publications = new ArrayList<>();

        /** Создаёт только адаптер интерфейса; callback render не рисует и не проходит искусственный paint barrier. */
        private UiPort port() {
            return (UiPort) Proxy.newProxyInstance(UiPort.class.getClassLoader(), new Class<?>[] {UiPort.class}, this);
        }

        /** Сбрасывает счётчики между опытами, сохраняя актуальную модель экрана. */
        private void reset() {
            renders = 0;
            tablePublications = 0;
            chartPublications = 0;
            revealedRow = "";
            publications.clear();
        }

        /** Копирует корреляцию и число публикаций после timed операции, без удержания старых моделей. */
        private Map<String, Object> evidence() {
            return Map.of("renders", renders, "tablePublications", tablePublications,
                    "chartPublications", chartPublications, "revealedRow", revealedRow,
                    "publications", List.copyOf(publications));
        }

        /** Считает реальные эффекты контроллера; возвращение render здесь не считается UI latency acceptance. */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "showMain" -> {
                    screen = (MainScreenModel) args[0];
                    return null;
                }
                case "render" -> {
                    screen = (MainScreenModel) args[0];
                    Set<?> changed = (Set<?>) args[1];
                    renders++;
                    if (changed.contains(ScreenPart.TABLE)) tablePublications++;
                    if (changed.contains(ScreenPart.CHART)) chartPublications++;
                    publications.add(Map.of("screenRevision", screen.revision(), "tableRevision", screen.table().revision(),
                            "chartRevision", screen.chart().revision(), "changedParts", changed.stream().map(Object::toString).sorted().toList()));
                    return null;
                }
                case "revealRow" -> {
                    revealedRow = (String) args[0];
                    return null;
                }
                // JavaFX: Alert/Dialog -> Swing: JDialog -> Web: dialog.
                case "showAlert", "openForm" -> throw new AssertionError("Unexpected modal effect in shared-core profile: " + method.getName());
                default -> {
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
            }
        }
    }
}
