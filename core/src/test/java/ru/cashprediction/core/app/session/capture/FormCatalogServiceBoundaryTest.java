package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.flow.FormCatalog;
import ru.cashprediction.core.app.flow.FormRequest;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.service.EngineForecastService;
import ru.cashprediction.core.forecast.service.ForecastRequest;
import ru.cashprediction.core.forecast.service.ForecastService;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.service.plan.LocalPlanCommands;
import ru.cashprediction.core.service.plan.PlanCommand;
import ru.cashprediction.core.service.plan.PlanCommandEffect;
import ru.cashprediction.core.service.plan.PlanCommandError;
import ru.cashprediction.core.service.plan.PlanCommandProblem;
import ru.cashprediction.core.service.plan.PlanCommandRequest;
import ru.cashprediction.core.service.plan.PlanCommandResult;
import ru.cashprediction.core.service.plan.PlanCommandSnapshot;
import ru.cashprediction.core.service.plan.PlanCommands;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;
import ru.cashprediction.core.service.storage.PlanStorageException;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.forms.simple.OpenPlanForm;
import ru.cashprediction.core.ui.text.UiText;

/** Узкие проверки зависимостей каталога восстановления без клиентов, реестра и сохранённых файлов планов. */
final class FormCatalogServiceBoundaryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Money SHARED_BALANCE = Money.ofMajor(777);

    @TempDir Path home;

    /** Список берётся из порта даже без файлов; ссылки, порядок, время и прежний выбор имени сохраняются. */
    @Test
    void restoredChoiceUsesStorageInventoryAndNormalizesLegacySelection() {
        Harness harness = new Harness(home);
        Path folder = home.resolve("selected-folder");
        Path selected = folder.resolve("Stored.md");
        Instant modified = Instant.parse("2026-10-02T08:09:10Z");
        List<PlanStorage.Entry> entries = List.of(
                new PlanStorage.Entry(FilePlanStorage.reference(selected), new PlanStorage.Version("version-z"), "Stored", modified),
                new PlanStorage.Entry(FilePlanStorage.reference(folder.resolve("Earlier.md")),
                        new PlanStorage.Version("version-a"), "Earlier", Instant.EPOCH));
        List<PlanStorage.Collection> listed = new ArrayList<>();
        AppState app = harness.app(folder, true, TODAY);
        WindowState saved = window(WindowType.CHOICE, "openPlan", Map.of("value", "Stored", "futureField", "raw"));
        FormRequest request = FormCatalog.forRestore(saved, app, harness,
                storage(PlanStorage.Result.success(entries), listed));
        assertEquals(List.of(FilePlanStorage.collection(folder)), listed);
        // JavaFX: ChoiceDialog → Swing: JDialog → Web: dialog.
        OpenPlanForm form = assertInstanceOf(OpenPlanForm.class, request.logic());
        assertEquals(List.of("Stored", "Earlier"), form.plans().stream().map(value -> value.name()).toList());
        assertEquals(selected, form.plans().getFirst().path());
        assertEquals(modified, form.plans().getFirst().lastModified().toInstant());
        assertEquals(saved, request.restored());
        FormContext context = harness.formContext(saved, app);
        Map<String, String> normalized = form.normalizeRestoredValues(request.restored().fields(), context);
        assertEquals(selected.toString(), normalized.get("value"));
        assertEquals("raw", normalized.get("futureField"));
        assertEquals(selected, assertInstanceOf(FormOutcome.Close.class,
                form.onButton("open", new FormState(0, normalized), context)).result());
        assertFalse(Files.exists(selected));
        assertTrue(harness.previewed.isEmpty());
        assertEquals(0, harness.executions);
    }

    /** Актуализация делает один предметный предпросмотр с ревизией владельца и его службой прогноза. */
    @Test
    void actualizePreviewUsesSharedForecastAndKeepsDocumentAndSession() {
        Harness harness = new Harness(home);
        AppState app = harness.app(home, true, TODAY);
        PlanCommandSnapshot before = harness.snapshot();
        harness.calculated.clear();
        WindowState saved = window(WindowType.ALERT, "actualize", Map.of("futureField", "raw"));
        FormRequest request = FormCatalog.forRestore(saved, app, harness, unusedStorage());
        assertEquals(1, harness.previewed.size());
        PlanCommandRequest preview = harness.previewed.getFirst();
        assertEquals(new PlanCommand.Actualize(TODAY, null), preview.command());
        assertEquals(before.revision(), preview.expectedRevision());
        assertEquals(Texts.get("document.edit.actualize", ru.cashprediction.core.ui.text.UiFormats.date(TODAY)), preview.description());
        assertTrue(app.revision() != preview.expectedRevision());
        assertFalse(harness.calculated.isEmpty());
        assertTrue(harness.calculated.stream().allMatch(value -> value.today().equals(TODAY)
                && value.plan().equals(before.plan())));
        assertEquals(before, harness.snapshot());
        assertTrue(harness.document.isDirty());
        assertEquals(app.view(), harness.document.viewState());
        assertEquals(0, harness.executions);
        assertEquals(saved, request.restored());
        assertEquals(saved.modal(), request.modal());
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        var expected = AlertCatalog.actualize(TODAY, SHARED_BALANCE, before.plan().currency());
        var spec = request.logic().spec(harness.formContext(saved, app));
        assertEquals(expected.windowTitle(), spec.windowTitle());
        assertEquals(expected.defaultButtonId(), spec.defaultButtonId());
        assertEquals(expected.content(), assertInstanceOf(FormRow.Hint.class, spec.pages().getFirst().rows().getFirst()).text());
    }

    /** Отказ проверки не восстанавливает кнопку применения, не выполняет команду и не выбирает иной расчёт. */
    @Test
    void rejectedPreviewIsReportedWithoutFallbackOrMutation() {
        Harness harness = new Harness(home);
        AppState app = harness.app(home, true, TODAY);
        PlanCommandSnapshot before = harness.snapshot();
        harness.calculated.clear();
        harness.previewResponse = request -> new PlanCommandResult(request.requestId(), PlanCommandResult.Status.REJECTED,
                before, PlanCommandEffect.NONE, List.of(new PlanCommandProblem(PlanCommandError.STALE_REVISION,
                        null, null, null, Map.of(), "rejected")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> FormCatalog.forRestore(
                window(WindowType.ALERT, "actualize", Map.of()), app, harness, unusedStorage()));
        assertEquals(UiText.get("s2.capture.actualizeUnavailable"), error.getMessage());
        assertEquals(1, harness.previewed.size());
        assertTrue(harness.calculated.isEmpty());
        assertEquals(before, harness.snapshot());
        assertEquals(0, harness.executions);
    }

    /** Исключение общей службы сохраняется причиной локализованного отказа; запасной движок не запускается. */
    @Test
    void previewExceptionKeepsCauseAndDoesNotUseFallback() {
        Harness harness = new Harness(home);
        AppState app = harness.app(home, true, TODAY);
        PlanCommandSnapshot before = harness.snapshot();
        harness.calculated.clear();
        RuntimeException cause = new IllegalStateException("preview unavailable");
        harness.previewResponse = request -> { throw cause; };
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> FormCatalog.forRestore(
                window(WindowType.ALERT, "actualize", Map.of()), app, harness, unusedStorage()));
        assertEquals(UiText.get("s2.capture.actualizeUnavailable"), error.getMessage());
        assertSame(cause, error.getCause());
        assertTrue(harness.calculated.isEmpty());
        assertEquals(before, harness.snapshot());
        assertEquals(0, harness.executions);
    }

    /** Отсутствующий ответ, иной исход, запрос или ревизия не используются как разрешённый проект. */
    @Test
    void malformedPreviewResponseDoesNotRestoreConfirmation() {
        for (String fault : List.of("null", "applied", "revision", "request")) {
            Harness harness = new Harness(home);
            AppState app = harness.app(home, true, TODAY);
            PlanCommandSnapshot before = harness.snapshot();
            harness.previewResponse = request -> {
                if (fault.equals("null")) return null;
                var snapshot = fault.equals("revision") ? new PlanCommandSnapshot(before.revision() + 1,
                        before.plan(), before.undoDescription(), before.redoDescription()) : before;
                return new PlanCommandResult(fault.equals("request") ? UUID.randomUUID() : request.requestId(),
                        fault.equals("applied") ? PlanCommandResult.Status.APPLIED : PlanCommandResult.Status.PREVIEW,
                        snapshot, PlanCommandEffect.NONE, List.of());
            };
            assertEquals(UiText.get("s2.capture.actualizeUnavailable"), assertThrows(IllegalArgumentException.class,
                    () -> FormCatalog.forRestore(window(WindowType.ALERT, "actualize", Map.of()), app, harness,
                            unusedStorage()), fault).getMessage());
            assertEquals(before, harness.snapshot(), fault);
            assertEquals(0, harness.executions, fault);
        }
    }

    /** Прежние ограничения даты и отсутствующего прогноза проверяются до обращения к командам. */
    @Test
    void unavailableDateOrForecastDoesNotRequestPreview() {
        Harness harness = new Harness(home);
        for (AppState app : List.of(harness.app(home, false, TODAY),
                harness.app(home, true, harness.document.plan().startDate()))) {
            assertEquals(UiText.get("s2.capture.actualizeUnavailable"), assertThrows(IllegalArgumentException.class,
                    () -> FormCatalog.forRestore(window(WindowType.ALERT, "actualize", Map.of()), app, harness,
                            unusedStorage())).getMessage());
        }
        assertTrue(harness.previewed.isEmpty());
        assertEquals(0, harness.executions);
    }

    /** Ошибка списка сохраняет структурированную причину и не подменяется пустой файловой папкой. */
    @Test
    void listingFailureIsLocalizedAndKeepsStorageProblem() {
        Harness harness = new Harness(home);
        Path folder = home.resolve("selected-folder");
        PlanStorage.Problem problem = new PlanStorage.Problem(PlanStorage.Code.IO_ERROR, PlanStorage.Conflict.NONE, "");
        List<PlanStorage.Collection> listed = new ArrayList<>();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> FormCatalog.forRestore(
                window(WindowType.CHOICE, "openPlan", Map.of()), harness.app(folder, true, TODAY), harness,
                storage(PlanStorage.Result.failure(problem), listed)));
        assertEquals(Texts.get("io.error.readPlansFolder", folder), error.getMessage());
        assertEquals(problem, assertInstanceOf(PlanStorageException.class, error.getCause()).problem());
        assertEquals(List.of(FilePlanStorage.collection(folder)), listed);
        assertTrue(harness.previewed.isEmpty());
    }

    /** Создаёт прежний сеанс с владельцем, геометрией и неизменяемыми неизвестными полями. */
    private static WindowState window(WindowType type, String purpose, Map<String, String> fields) {
        return new WindowState("restored", type, true, "owner", new WindowBounds(10, 20, 500, 300),
                Map.of("purpose", purpose, "targetId", ""), fields);
    }

    /** Порт допускает только список; чтение, наблюдение версий и запись сразу проваливают тест. */
    private static PlanStorage storage(PlanStorage.Result<List<PlanStorage.Entry>> result,
                                       List<PlanStorage.Collection> listed) {
        return (PlanStorage) Proxy.newProxyInstance(PlanStorage.class.getClassLoader(), new Class<?>[]{PlanStorage.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("list")) throw new AssertionError("Unexpected storage operation: " + method.getName());
                    listed.add((PlanStorage.Collection) args[0]);
                    return result;
                });
    }

    /** Не допускает обращения к хранению в сценарии, которому нужны только команды. */
    private static PlanStorage unusedStorage() {
        return (PlanStorage) Proxy.newProxyInstance(PlanStorage.class.getClassLoader(), new Class<?>[]{PlanStorage.class},
                (proxy, method, args) -> { throw new AssertionError("Unexpected storage operation: " + method.getName()); });
    }

    /** Настоящий владелец истории с записывающим портом команд и отличимым внедрённым результатом прогноза. */
    private static final class Harness implements PlanCommands {
        private final Path cashMemory;
        private final List<ForecastRequest> calculated = new ArrayList<>();
        private final List<PlanCommandRequest> previewed = new ArrayList<>();
        private final ForecastService forecastService = this::calculate;
        private final PlanDocument document;
        private final LocalPlanCommands commands;
        private Function<PlanCommandRequest, PlanCommandResult> previewResponse;
        private int executions;

        /** Создаёт общий документ; предметная ревизия специально отличается от UI-ревизии. */
        private Harness(Path home) {
            cashMemory = home.resolve("CashMemory");
            Plan plan = Plan.empty("Current", TODAY.minusDays(3)).withStart(TODAY.minusDays(3), Money.ofMajor(100));
            document = new PlanDocument(plan, null, () -> TODAY, forecastService);
            commands = new LocalPlanCommands(document);
            assertTrue(commands.execute(new PlanCommandRequest(UUID.randomUUID(), 0, "setup", new PlanCommand.SetCurrency("EUR"))).changed());
        }

        /** Строит неизменяемый снимок UI без создания окна, чтения диска или замены служб. */
        private AppState app(Path folder, boolean available, LocalDate today) {
            var view = new DocumentView(document.plan(), null, document.isDirty(), document.canUndo(),
                    document.undoDescription().orElse(""), document.canRedo(), document.redoDescription().orElse(""),
                    available ? document.forecast() : null, "", List.of());
            return new AppState(900, ClientProfile.fx("25"), today, cashMemory, folder, view, document.viewState(), "", false,
                    AppSettings.defaults(), null, List.of(), null, null, "");
        }

        /** Передаёт форме тот же исполнитель, который внедрён владельцу документа. */
        private FormContext formContext(WindowState saved, AppState app) {
            return new FormContext(saved.id(), saved.ownerId(), saved.context(), app, forecastService);
        }

        /** Регистрирует запрос и возвращает отличимый баланс; обход через DEFAULT дал бы исходные 100. */
        private Forecast calculate(ForecastRequest request) {
            calculated.add(request);
            Forecast forecast = EngineForecastService.DEFAULT.calculate(request);
            long[] balances = forecast.dailyBalance();
            Arrays.fill(balances, SHARED_BALANCE.minor());
            return new Forecast(forecast.plan(), forecast.whatIf(), forecast.today(), forecast.anchor(), forecast.rows(),
                    balances, forecast.dailyStart(), forecast.summary(), forecast.warnings());
        }

        /** {@inheritDoc} */
        @Override public PlanCommandSnapshot snapshot() { return commands.snapshot(); }

        /** {@inheritDoc} Регистрирует запрещённую для построения каталога запись, если она появится. */
        @Override public PlanCommandResult execute(PlanCommandRequest request) {
            executions++;
            return commands.execute(request);
        }

        /** {@inheritDoc} Обычно делегирует настоящей службе; отдельные проверки управляют отказом. */
        @Override public PlanCommandResult preview(PlanCommandRequest request) {
            previewed.add(request);
            return previewResponse == null ? commands.preview(request) : previewResponse.apply(request);
        }
    }
}
