package ru.cashprediction.core.app;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.time.Duration;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.Scheduler;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.alert.AlertSession;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.ScreenPart;
import ru.cashprediction.core.ui.view.table.LazyTableModel;
import ru.cashprediction.core.ui.view.chart.ChartLayout;
import ru.cashprediction.core.ui.view.popup.PopupBuilders;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.ui.view.status.StatusBuilder;
import ru.cashprediction.core.ui.view.status.StatusLevel;
import ru.cashprediction.core.ui.menu.MenuModels;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.EventKind;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.ui.command.CommandAvailability;
import ru.cashprediction.core.ui.command.Availability;
import ru.cashprediction.core.ui.command.HotkeyTable;
import ru.cashprediction.core.ui.view.table.RowKind;
import ru.cashprediction.core.ui.view.table.TableRowView;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.FocusScope;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.menu.MenuNode;
import ru.cashprediction.core.ui.view.chart.ChartHover;
import ru.cashprediction.core.ui.view.chart.ChartScene;
import ru.cashprediction.core.ui.view.popup.CalendarModel;
import ru.cashprediction.core.ui.view.popup.DayCardModel;
import ru.cashprediction.core.ui.view.popup.SparklineModel;

/**
 * Контроллер приложения — всё поведение интерфейса в ядре (архитектура §3.7). Один экземпляр на процесс (web: на
 * сервер); все клиенты только отрисовывают его модели через {@link UiPort} и передают действия в {@link UiIntents}.
 *
 * <p><b>Устройство (этап S2).</b></p>
 * <ul>
 *   <li>Держит {@code PlanDocument}, {@code ViewState}, выделение, {@code pastExpanded}, настройки,
 *       {@link OpenWindows}, {@link StatusMessages}, папку планов и строит {@link AppState} ({@link #state()}).</li>
 *   <li>Реализует {@code core.app.flow.FlowContext} (замороженный на этапе S0 контракт) — через него потоки получают
 *       порт, состояние, открытие форм и сообщений.</li>
 *   <li>Намерения: проверка потока ({@code port.executor().isUiThread()}), правило модальности, затем
 *       {@code CommandAvailability.of}; команда передаётся потоку: {@code FileFlow} (file.*), {@code EditFlow}
 *       (edit.*, row.*, total.copy), {@code ViewFlow} (view.*, filter.*, past.toggle, card/chart.showInTable),
 *       {@code ToolsFlow} (tools.*, whatIf.*, card.copyValue), {@code RecoveryFlow} (recovery.*), {@code HelpFlow}
 *       (help.*), {@code ExitFlow} (file.exit, крестик).</li>
 *   <li>Отрисовка: после каждого изменения строит {@code MainScreenModel}, хеширует части и вызывает
 *       {@code port.render} только с изменившимися {@code ScreenPart}.</li>
 *   <li>Сеанс: {@code SessionBridge} — источник снимка и цель восстановления; {@code CoreWindowFactory} открывает
 *       восстановленные окна тем же путём, что и меню.</li>
 *   <li>Для проверки горячих клавиш считает выполненные команды ({@link #executedCount(CommandId)}); счётчик
 *       попадает в дамп самотеста.</li>
 * </ul>
 *
 * <p><b>Владелец файла.</b> Весь файл пишет задача S2 core-app-file (stages.md). Методы, поведение которых живёт в
 * потоках других задач, — однострочные делегаты: {@code selectRow}, {@code filterText}, {@code sliderCommit} →
 * {@code ViewFlow}; {@code activateRow} → {@code EditFlow}/{@code ViewFlow}; {@code spinnerCommit} →
 * {@code ToolsFlow}; {@code mainGeometry} → {@code SessionBridge}; {@code uncaught} → {@code RecoveryFlow}.</p>
 *
 * <p>Не потокобезопасен: используется только в потоке контроллера (архитектура §3.9).</p>
 */
public final class AppController implements UiIntents, FlowContext {

    private final UiPort port;
    private final AppEnvironment environment;
    /** Единственный изменяемый документ; прогноз остаётся ленивым до первого снимка состояния. */
    private final PlanDocument document;
    private AppSettings settings = AppSettings.defaults();
    private String selectedRowId = "";
    private boolean pastExpanded;
    private Path plansFolder;
    private long revision;
    private OpenWindows windows = OpenWindows.NONE;
    private StatusMessages messages = StatusMessages.EMPTY;
    private RecorderStatus recorderStatus = RecorderStatus.NOT_STARTED;
    private List<StoreStatus> stores = List.of();
    private String autosaveProblem = "";
    private final Map<CommandId, Integer> executed = new EnumMap<>(CommandId.class);
    private final Map<String, FormSession> forms = new LinkedHashMap<>();
    private final Map<String, WindowHandle> handles = new LinkedHashMap<>();
    private final Map<String, AlertSession> alerts = new LinkedHashMap<>();
    private final FileFlow fileFlow;
    private final EditFlow editFlow;
    private final ViewFlow viewFlow;
    private final ToolsFlow toolsFlow;
    private final RecoveryFlow recoveryFlow;
    private final HelpFlow helpFlow;
    private final ExitFlow exitFlow;
    private final FileChooserService chooserService;
    private final AutosaveService autosaveService;
    private final SettingsKeeper settingsService;
    private final ExternalChangeGuard externalGuard = new ExternalChangeGuard();
    private SessionRecorder recorder;
    private MainScreenModel screen;
    private AppState renderedState;
    private boolean shown;
    private boolean started;
    private long windowSequence;
    private Scheduler.Task statusExpiry;
    private long statusGeneration;
    private String closingResultId;

    /**
     * Создаёт контроллер. Ничего не показывает и не читает с диска до {@link #start()}.
     *
     * @param port        порт клиента
     * @param environment окружение процесса
     */
    public AppController(UiPort port, AppEnvironment environment) {
        this.port = Objects.requireNonNull(port, "port");
        this.environment = Objects.requireNonNull(environment, "environment");
        document = new PlanDocument(Plan.empty(UiText.get("plan.defaultName"), environment.clock().today()), null,
                environment.clock()::today);
        plansFolder = environment.cashMemory();
        fileFlow = new FileFlow(this);
        editFlow = new EditFlow(this);
        viewFlow = new ViewFlow(this);
        toolsFlow = new ToolsFlow(this);
        recoveryFlow = new RecoveryFlow(this);
        helpFlow = new HelpFlow(this);
        exitFlow = new ExitFlow(this);
        chooserService = new FileChooserService(this);
        autosaveService = new AutosaveService(this);
        settingsService = new SettingsKeeper(this);
        document.addListener(event -> {
            assertUiThread();
            if (recorder != null) recorder.touch();
            if (event.has(EventKind.PLAN)) {
                discardQuickEdit();
                autosaveService.documentChanged();
            }
            AppState app = state();
            for (FormSession form : List.copyOf(forms.values())) {
                form.documentChanged(form.context().withApp(app));
            }
            refresh();
        });
    }

    /** @return порт клиента */
    public UiPort port() {
        return port;
    }

    /** @return окружение процесса */
    public AppEnvironment environment() {
        return environment;
    }

    /**
     * Запускает приложение: {@code StartupFlow.start()} (§6.28) — CashMemory, настройки, детектор сбоя, диалог
     * восстановления или обычное открытие плана, показ главного окна, начало записи сеанса. Вызывается один раз в
     * потоке контроллера.
     */
    public void start() {
        assertUiThread();
        if (started) {
            throw new IllegalStateException("Controller already started");
        }
        started = true;
        new StartupFlow(this).start();
    }

    /** @return текущий неизменяемый снимок состояния */
    public AppState state() {
        assertUiThread();
        Forecast forecast = null;
        String forecastError = "";
        try {
            forecast = document.forecast();
        } catch (IllegalArgumentException | ArithmeticException exception) {
            forecastError = Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName());
        }
        DocumentView snapshot = new DocumentView(document.plan(), document.file().orElse(null), document.isDirty(),
                document.undoDescription().isPresent(), document.undoDescription().orElse(""),
                document.redoDescription().isPresent(), document.redoDescription().orElse(""), forecast, forecastError,
                document.loadDiagnostics());
        return new AppState(revision, port.profile(), environment.clock().today(), environment.cashMemory(), plansFolder,
                snapshot, document.viewState(), selectedRowId, pastExpanded, settings, recorderStatus, stores, windows,
                messages, autosaveProblem);
    }

    /**
     * Сколько раз команда была действительно выполнена (после проверок модальности и доступности).
     *
     * @param command команда
     * @return счётчик с начала работы
     */
    public int executedCount(CommandId command) {
        assertUiThread();
        return executed.getOrDefault(Objects.requireNonNull(command, "command"), 0);
    }

    @Override
    public void command(CommandId id, CommandArgs args, InvokeSource source) {
        assertUiThread();
        Objects.requireNonNull(id, "command");
        Objects.requireNonNull(source, "source");
        CommandArgs actual = args == null ? CommandArgs.NONE : args;
        if (source.isMainWindow() && windows.modalOpen()) return;
        if (source == InvokeSource.FORM && (session(actual.key()).isEmpty()
                || windows.topModal().filter(top -> !top.windowId().equals(actual.key())).isPresent())) return;
        Availability availability = CommandAvailability.of(id, actual, state());
        if (!availability.enabled()) {
            if (source == InvokeSource.HOTKEY && !availability.hintKey().isEmpty())
                status(StatusLevel.INFO, availability.hintKey());
            return;
        }
        String row = actual.rowId().isEmpty() ? selectedRowId : actual.rowId();
        executed.merge(id, 1, Integer::sum);
        switch (id) {
            case FILE_NEW -> files().newPlan();
            case FILE_OPEN -> files().open();
            case FILE_OPEN_FILE -> files().openFile();
            case FILE_SAMPLE -> files().openSample();
            case FILE_RECENT_OPEN -> files().openRecent(actual.value());
            case FILE_SAVE -> files().save(() -> { });
            case FILE_SAVE_AS -> files().saveAs();
            case FILE_RENAME -> files().rename();
            case FILE_AUTOSAVE -> files().toggleAutosave();
            case FILE_EXPORT_CSV -> files().exportCsv();
            case FILE_SAVE_PNG -> files().savePng();
            case FILE_CASH_MEMORY -> files().cashMemoryFolder();
            case FILE_EXIT -> exit().requestExit();
            case EDIT_ADD_INCOME -> edits().addRule(Kind.INCOME);
            case EDIT_ADD_EXPENSE -> edits().addRule(Kind.EXPENSE);
            case EDIT_ADD_ONE_TIME, ROW_ADD_ONE_TIME, CHART_ADD_ONE_TIME -> edits().addOneTime(actual.date());
            case EDIT_EDIT, ROW_EDIT -> edits().editRow(row);
            case EDIT_DELETE, ROW_DELETE -> edits().deleteRow(row);
            case EDIT_ADJUST, ROW_ADJUST -> edits().adjust(row);
            case EDIT_SKIP, ROW_SKIP -> edits().skip(row);
            case EDIT_RESET, ROW_RESET -> edits().reset(row);
            case EDIT_UNDO -> edits().undo();
            case EDIT_REDO -> edits().redo();
            case EDIT_PLAN_SETTINGS -> edits().planSettings();
            case EDIT_ACTUALIZE -> edits().actualize();
            case EDIT_RECONCILE -> edits().reconcile();
            case VIEW_TABLE -> views().setMode(ViewMode.TABLE);
            case VIEW_CHART -> views().setMode(ViewMode.CHART);
            case VIEW_FLAG_SHOW_INCOME, VIEW_FLAG_SHOW_EXPENSE, VIEW_FLAG_SHOW_ONE_TIME,
                 VIEW_FLAG_SHOW_SKIPPED, VIEW_FLAG_MONTH_TOTALS, VIEW_FLAG_CHART_MARKERS,
                 VIEW_FLAG_CHART_BARS, VIEW_FLAG_SUMMARY_PANEL -> views().toggleFlag(id);
            case VIEW_PERIOD_M3 -> views().setPeriod(PeriodChoice.M3);
            case VIEW_PERIOD_M6 -> views().setPeriod(PeriodChoice.M6);
            case VIEW_PERIOD_M12 -> views().setPeriod(PeriodChoice.M12);
            case VIEW_PERIOD_M24 -> views().setPeriod(PeriodChoice.M24);
            case VIEW_PERIOD_ALL -> views().setPeriod(PeriodChoice.ALL);
            case VIEW_HORIZON_MONTHS -> views().customMonths();
            case VIEW_HORIZON_SLIDER -> views().horizonSliderCommit(Integer.parseInt(actual.value()));
            case VIEW_FOCUS_FILTER -> views().focusFilter();
            case TOOLS_GOAL -> tools().goalCalculator();
            case WHAT_IF_INCOME -> tools().toggleWhatIfIncome();
            case WHAT_IF_EXPENSE -> tools().toggleWhatIfExpense();
            case WHAT_IF_EXTRA -> tools().setWhatIfExtra(Long.parseLong(actual.value()));
            case WHAT_IF_APPLY -> tools().applyWhatIf();
            case WHAT_IF_RESET -> tools().resetWhatIf();
            case TOOLS_VALIDATE -> tools().validate();
            case TOOLS_CLEANUP -> tools().cleanup();
            case TOOLS_CURRENCY -> tools().currency();
            case RECOVERY_STORE_REGISTRY -> recovery().setDefaultStore(RecoveryStoreKind.REGISTRY);
            case RECOVERY_STORE_XML -> recovery().setDefaultStore(RecoveryStoreKind.XML);
            // Серверное хранилище web единственное: радио-пункт информационный, настройки desktop не меняет.
            case RECOVERY_STORE_SERVER -> { }
            case RECOVERY_SNAPSHOT_NOW -> recovery().snapshotNow();
            case RECOVERY_SHOW_LAST -> recovery().showLast();
            case RECOVERY_CLEAR -> recovery().clear();
            case RECOVERY_SIMULATE_HALT -> recovery().simulateHalt();
            case RECOVERY_SIMULATE_EXCEPTION -> recovery().simulateException();
            case HELP_ABOUT -> help().about();
            case HELP_HOTKEYS -> help().hotkeys();
            case HELP_FORMAT -> help().format();
            case FILTER_CLEAR -> views().clearFilter();
            case FILTER_FOCUS_TABLE -> views().focusTable();
            case ROW_QUICK_EDIT -> edits().quickEdit(row, actual.key());
            case ROW_GO_TO_RULE -> edits().goToRule(row);
            case ROW_DISABLE_RULE -> edits().disableRule(row);
            case ROW_COPY -> edits().copyRow(row);
            case TOTAL_COPY -> edits().copyTotal(row);
            case PAST_TOGGLE -> views().togglePast();
            case CARD_SHOW_IN_TABLE, CHART_SHOW_IN_TABLE -> views().showInTable(actual.date());
            case CARD_COPY_VALUE -> tools().copyCardValue(actual.cardId());
            case UI_MENU_BAR -> port.focus(FocusTarget.MENU_BAR);
            case UI_CONTEXT_MENU -> {
                ContextTarget target = actual.cardId().isEmpty() ? rowContext(row) : new ContextTarget.Card(actual.cardId());
                List<MenuNode> items = contextMenu(target);
                if (!items.isEmpty()) port.showContextMenu(target, items);
            }
            case PREVIEW_ADJUST -> session(actual.key()).ifPresent(form -> form.previewSelected(
                    Integer.parseInt(actual.value()), true));
        }
    }

    @Override
    public boolean key(KeyChord chord, FocusScope scope, String focusId) {
        assertUiThread();
        if (windows.modalOpen()) return false;
        var binding = HotkeyTable.find(chord, scope, port.profile().kind());
        if (binding.isEmpty()) return false;
        if (scope == FocusScope.TABLE && chord.equals(KeyChord.parse("Enter")) && screen != null) {
            int index = screen.table().indexOf(selectedRowId);
            if (index >= 0 && screen.table().row(index).kind() == RowKind.PAST_HEADER) {
                command(CommandId.PAST_TOGGLE, CommandArgs.row(selectedRowId), InvokeSource.HOTKEY);
                return true;
            }
        }
        CommandArgs args = scope == FocusScope.CARD ? CommandArgs.card(focusId,
                SummaryBuilder.card(state(), focusId).map(card -> card.date()).orElse(null))
                : CommandArgs.row(selectedRowId);
        command(binding.get().command(), args, InvokeSource.HOTKEY);
        return true;
    }

    @Override
    public void selectRow(String rowId) {
        assertUiThread();
        if (!windows.modalOpen()) views().selectRow(rowId);
    }

    @Override
    public void activateRow(String rowId, String columnId, Activation how) {
        assertUiThread();
        if (windows.modalOpen() || screen == null) return;
        int index = screen.table().indexOf(rowId);
        if (index < 0) return;
        TableRowView row = screen.table().row(index);
        if (how == Activation.CLICK) {
            if (row.kind() == RowKind.PAST_HEADER) views().togglePast();
            else views().selectRow(rowId);
        } else if (row.quickEditable() && ("income".equals(columnId) || "expense".equals(columnId))
                && !row.cells().get("income".equals(columnId) ? 4 : 5).isEmpty()) {
            edits().quickEdit(rowId, columnId);
        } else if (row.kind() != RowKind.MONTH_TOTAL && row.kind() != RowKind.PAST_HEADER) {
            command(CommandId.EDIT_EDIT, CommandArgs.row(rowId), InvokeSource.MAIN);
        }
    }

    @Override
    public void filterText(String text) {
        assertUiThread();
        if (!windows.modalOpen()) views().filterText(text);
    }

    @Override
    public void sliderCommit(String itemId, int value) {
        assertUiThread();
        if (!windows.modalOpen() && "view.horizonSlider".equals(itemId)) views().horizonSliderCommit(value);
    }

    @Override
    public void spinnerCommit(String itemId, long value) {
        assertUiThread();
        if (!windows.modalOpen() && "whatIf.extra".equals(itemId)) tools().setWhatIfExtra(value);
    }

    @Override
    public void mainGeometry(WindowBounds bounds, boolean maximized) {
        assertUiThread();
        // Геометрия принадлежит порту; намерение только помечает снимок изменённым.
        if (recorder != null) recorder.touch();
    }

    @Override
    public void menuHover(String itemIdOrNull) {
        assertUiThread();
        MenuNode node = screen == null || itemIdOrNull == null ? null : screen.menuBar().find(itemIdOrNull).orElse(null);
        String tip = switch (node) {
            case MenuNode.Action action -> action.tooltip();
            case MenuNode.Check check -> check.tooltip();
            case MenuNode.Radio radio -> radio.tooltip();
            case MenuNode.Submenu submenu -> submenu.tooltip();
            case MenuNode.Slider slider -> slider.tooltip();
            case MenuNode.Spinner spinner -> spinner.tooltip();
            case null, default -> "";
        };
        messages = messages.hover(tip);
        refresh();
    }

    @Override
    public void closeMainRequested() {
        assertUiThread();
        if (!windows.modalOpen()) exit().requestExit();
    }

    @Override
    public void uncaught(Thread thread, Throwable error) {
        assertUiThread();
        recovery().uncaught(thread, error);
    }

    @Override
    public List<MenuNode> contextMenu(ContextTarget target) {
        assertUiThread();
        if (target instanceof ContextTarget.Preview preview) {
            if (session(preview.windowId()).isEmpty() || windows.topModal()
                    .filter(top -> !top.windowId().equals(preview.windowId())).isPresent()) return List.of();
        } else if (windows.modalOpen()) return List.of();
        return MenuModels.contextMenu(state(), target, port.profile().kind());
    }

    @Override
    public String tableTooltip(long revision, int index, String columnId) {
        assertUiThread();
        if (screen == null || revision != screen.table().revision() || index < 0 || index >= screen.table().rowCount()) {
            return "";
        }
        return screen.table().tooltip(index, columnId);
    }

    @Override
    public ChartScene chartScene(double width, double height) {
        assertUiThread();
        return (screen == null ? ChartLayout.model(state(), revision) : screen.chart()).layout(width, height);
    }

    @Override
    public Optional<ChartHover> chartHover(long revision, double x, double y, double width, double height) {
        assertUiThread();
        return screen == null || revision != screen.chart().revision() ? Optional.empty()
                : screen.chart().hover(x, y, width, height);
    }

    @Override
    public DayCardModel dayCard(LocalDate date) {
        assertUiThread();
        return PopupBuilders.dayCard(state(), date);
    }

    @Override
    public SparklineModel sparkline(String cardId) {
        assertUiThread();
        return PopupBuilders.sparkline(state(), cardId);
    }

    @Override
    public CalendarModel calendar(YearMonth month, LocalDate selected) {
        assertUiThread();
        return PopupBuilders.calendar(month, selected, environment.clock().today());
    }

    /** {@inheritDoc} */
    @Override public PlanDocument document() { return document; }
    /** {@inheritDoc} */
    @Override public SessionRecorder recorder() { return recorder; }
    /** {@inheritDoc} */
    @Override public FileFlow files() { return fileFlow; }
    /** {@inheritDoc} */
    @Override public EditFlow edits() { return editFlow; }
    /** {@inheritDoc} */
    @Override public ViewFlow views() { return viewFlow; }
    /** {@inheritDoc} */
    @Override public ToolsFlow tools() { return toolsFlow; }
    /** {@inheritDoc} */
    @Override public RecoveryFlow recovery() { return recoveryFlow; }
    /** {@inheritDoc} */
    @Override public HelpFlow help() { return helpFlow; }
    /** {@inheritDoc} */
    @Override public ExitFlow exit() { return exitFlow; }
    /** {@inheritDoc} */
    @Override public FileChooserService choosers() { return chooserService; }
    /** {@inheritDoc} */
    @Override public AutosaveService autosave() { return autosaveService; }
    /** {@inheritDoc} */
    @Override public SettingsKeeper settingsKeeper() { return settingsService; }
    /** {@inheritDoc} */
    @Override public ExternalChangeGuard externalChanges() { return externalGuard; }

    /** {@inheritDoc} */
    @Override public void installRecorder(SessionRecorder value) {
        assertUiThread();
        if (recorder != null) throw new IllegalStateException("Recorder already installed");
        recorder = Objects.requireNonNull(value, "recorder");
        recorder.addStatusListener(status -> port.executor().execute(() -> {
            List<StoreStatus> updated = new java.util.ArrayList<>(stores);
            updated.removeIf(old -> old.storeId().equals(status.storeId()));
            updated.add(status);
            stores = List.copyOf(updated);
            refresh();
        }));
    }

    /** {@inheritDoc} */
    @Override public void setRecorderStatus(RecorderStatus value) {
        assertUiThread(); recorderStatus = Objects.requireNonNull(value); refresh();
    }
    /** {@inheritDoc} */
    @Override public void setAutosaveProblem(String value) {
        assertUiThread(); autosaveProblem = Objects.requireNonNullElse(value, ""); refresh();
    }
    /** {@inheritDoc} */
    @Override public void updateSettings(UnaryOperator<AppSettings> change) {
        assertUiThread();
        AppSettings updated = Objects.requireNonNull(change.apply(settings));
        if (!updated.equals(settings)) { settings = updated; settingsService.changed(); refresh(); }
    }
    /** {@inheritDoc} */
    @Override public void updateView(UnaryOperator<ViewState> change) {
        assertUiThread();
        ViewState updated = Objects.requireNonNull(change.apply(document.viewState()));
        document.setViewState(updated);
        updateSettings(updated::applyTo);
        if (recorder != null) recorder.touch();
        refresh();
    }
    /** {@inheritDoc} */
    @Override public void setSelection(String value) {
        assertUiThread();
        String next = Objects.requireNonNullElse(value, "");
        if (!selectedRowId.equals(next)) {
            selectedRowId = next;
            if (recorder != null) recorder.touch();
            refresh();
        }
    }
    /** {@inheritDoc} */
    @Override public void setPastExpanded(boolean value) {
        assertUiThread();
        if (pastExpanded != value) {
            pastExpanded = value;
            if (recorder != null) recorder.touch();
            refresh();
        }
    }
    /** {@inheritDoc} */
    @Override public void setPlansFolder(Path value) {
        assertUiThread(); plansFolder = value == null ? environment.cashMemory() : value; refresh();
    }
    /** {@inheritDoc} */
    @Override public void status(StatusLevel level, String key, Object... args) {
        assertUiThread();
        messages = messages.show(UiText.get(key, args), level, environment.clock().now());
        if (statusExpiry != null) statusExpiry.cancel();
        long generation = ++statusGeneration;
        statusExpiry = port.scheduler().schedule(() -> port.executor().execute(() -> {
            if (generation != statusGeneration) return;
            // Таймер завершает сообщение и при замороженных часах воспроизводимого самотеста.
            messages = new StatusMessages(null, messages.hoverTip(), messages.persistent());
            refresh();
        }), Duration.ofSeconds(10));
        refresh();
    }
    /** {@inheritDoc} */
    @Override public void persistentStatus(String cause, String text) {
        assertUiThread();
        messages = text == null ? messages.withoutPersistent(cause)
                : messages.withPersistent(cause, text, StatusLevel.ERROR);
        refresh();
    }

    /** {@inheritDoc} */
    @Override public Optional<FormSession> session(String id) { assertUiThread(); return Optional.ofNullable(forms.get(id)); }
    /** {@inheritDoc} */
    @Override public Optional<FormSession> singleInstance(String key) {
        assertUiThread(); return windows.findSingleInstance(key).flatMap(w -> session(w.windowId()));
    }

    /** {@inheritDoc} */
    @Override public FormSession openForm(FormRequest request, Placement placement, Consumer<Object> onResult) {
        assertUiThread();
        if (request.modal()) discardQuickEdit();
        String singleton = request.type() == WindowType.GOAL_CALCULATOR || request.type() == WindowType.QUICK_EDIT_POPUP
                ? request.type().name() : "";
        Optional<FormSession> existing = singleInstance(singleton);
        if (existing.isPresent()) {
            if (existing.get().handle() != null) existing.get().handle().toFront();
            return existing.get();
        }
        String id = request.restored() == null ? nextWindowId() : request.restored().id();
        String owner = placement == null ? modalOwner() : placement.ownerId();
        Placement effective = placement == null ? Placement.centered(owner) : placement;
        Consumer<Object> callback = onResult == null ? ignored -> { } : onResult;
        FormSession result = new FormSession(request.type(), request.modal(), request.logic(),
                new FormContext(id, owner, request.context(), state()), new FormSession.Host() {
            @Override public void registered(FormSession form) { if (recorder != null) recorder.register(form); }
            @Override public void unregistered(FormSession form) {
                if (recorder != null) recorder.unregister(form);
                removeWindow(form.windowId());
            }
            @Override public void touched(FormSession form) { if (recorder != null) recorder.touch(); }
            @Override public void closed(FormSession form, Object value) {
                String previous = closingResultId;
                closingResultId = form.windowId();
                try { callback.accept(value); }
                finally { closingResultId = previous; }
                removeWindow(form.windowId());
            }
            @Override public void applied(FormSession form, Object value) { callback.accept(value); }
            @Override public void openChild(FormSession parent, WindowState child) {
                WindowState prepared = child.withIds(nextWindowId(), parent.windowId());
                new CoreWindowFactory(AppController.this).open(prepared, parent.windowId(), ignored -> { },
                        warning -> { throw new IllegalArgumentException(warning); });
            }
        });
        if (request.restored() != null) result.applyState(request.restored());
        forms.put(id, result);
        windows = windows.with(new OpenWindows.OpenWindow(id, request.type(),
                request.context().getOrDefault(WindowType.CONTEXT_PURPOSE, ""), request.modal(), owner, singleton));
        try {
            // JavaFX: Dialog → Swing: JDialog → Web: dialog
            result.attach(port.openForm(result, result.spec(), result.view(), effective));
            if (forms.containsKey(id)) handles.put(id, result.handle());
        } catch (RuntimeException error) {
            result.abortOpening();
            removeWindow(id);
            throw error;
        }
        refresh();
        return result;
    }

    /** {@inheritDoc} */
    @Override public WindowHandle showAlert(AlertSpec spec, Consumer<String> onButton) {
        assertUiThread();
        String id = nextWindowId();
        return showAlertSession(spec, id, modalOwner(), null, null, onButton);
    }

    /** {@inheritDoc} */
    @Override public WindowHandle showRestoredAlert(AlertSpec spec, WindowState restored,
            Consumer<StatefulWindow> onShown, Consumer<String> onButton) {
        assertUiThread();
        Objects.requireNonNull(restored, "restored");
        if (restored.type() != WindowType.ALERT || !spec.restorable())
            throw new IllegalArgumentException("Restored alert must be restorable");
        return showAlertSession(spec, restored.id(), restored.ownerId(), restored,
                Objects.requireNonNull(onShown, "onShown"), onButton);
    }

    /** Единый жизненный цикл свежего и восстановленного сообщения. */
    private WindowHandle showAlertSession(AlertSpec spec, String id, String owner, WindowState restored,
            Consumer<StatefulWindow> onShown, Consumer<String> onButton) {
        discardQuickEdit();
        AlertSession alert = spec.restorable() ? new AlertSession(id, owner, spec, new AlertSession.Host() {
            @Override public void registered(AlertSession session) { if (recorder != null) recorder.register(session); }
            @Override public void unregistered(AlertSession session) { if (recorder != null) recorder.unregister(session); }
        }) : null;
        if (alert != null && restored != null) alert.applyState(restored);
        if (alert != null && onShown != null) alert.whenShown(onShown::accept);
        windows = windows.with(new OpenWindows.OpenWindow(id, WindowType.ALERT, spec.purpose(), true, owner, ""));
        if (alert != null) alerts.put(id, alert);
        boolean[] answered = { false };
        try {
            // JavaFX: Alert → Swing: JDialog → Web: dialog
            WindowHandle handle = port.showAlert(spec, alert, button -> {
                assertUiThread();
                if (answered[0]) return;
                answered[0] = true;
                if (alert != null) alert.closed();
                removeWindow(id);
                if (onButton != null) onButton.accept(button);
            });
            if (alert != null) alert.attach(handle);
            if (!answered[0]) handles.put(id, handle);
            refresh();
            return handle;
        } catch (RuntimeException error) { removeWindow(id); throw error; }
    }

    /** {@inheritDoc} */
    @Override public void showMain(MainWindowState restored) {
        assertUiThread();
        if (shown) throw new IllegalStateException("Main window already shown");
        refresh();
        shown = true;
        port.showMain(screen, restored);
    }

    /** {@inheritDoc} */
    @Override public void refresh() {
        assertUiThread();
        AppState app = state();
        boolean dataChanged = screen == null || renderedState == null
                || !app.document().plan().equals(renderedState.document().plan())
                || app.document().forecast() != renderedState.document().forecast()
                || !app.document().forecastError().equals(renderedState.document().forecastError())
                || !app.view().equals(renderedState.view())
                || !app.today().equals(renderedState.today());
        boolean tableChanged = dataChanged || !app.selectedRowId().equals(renderedState.selectedRowId())
                || app.pastExpanded() != renderedState.pastExpanded();
        long nextRevision = revision + 1;
        MainScreenModel next = new MainScreenModel(nextRevision,
                UiText.get(app.document().dirty() ? "main.title.dirty" : "main.title", app.document().plan().name()),
                MenuModels.menuBar(app, port.profile().kind()), MenuModels.toolbar(app, port.profile().kind()),
                SummaryBuilder.build(app), tableChanged ? LazyTableModel.build(app, nextRevision) : screen.table(),
                dataChanged ? ChartLayout.model(app, nextRevision) : screen.chart(),
                StatusBuilder.build(app, environment.clock().now()), app.view().mode());
        EnumSet<ScreenPart> changed = EnumSet.noneOf(ScreenPart.class);
        if (screen == null) changed = EnumSet.allOf(ScreenPart.class);
        else {
            if (!screen.windowTitle().equals(next.windowTitle())) changed.add(ScreenPart.TITLE);
            if (!screen.menuBar().equals(next.menuBar())) changed.add(ScreenPart.MENU);
            if (!screen.toolbar().equals(next.toolbar())) changed.add(ScreenPart.TOOLBAR);
            if (!screen.summary().equals(next.summary())) changed.add(ScreenPart.SUMMARY);
            if (tableChanged) changed.add(ScreenPart.TABLE);
            if (dataChanged) changed.add(ScreenPart.CHART);
            if (!screen.status().equals(next.status())) changed.add(ScreenPart.STATUS);
            if (screen.mode() != next.mode()) changed.add(ScreenPart.MODE);
        }
        if (!changed.isEmpty()) { revision = nextRevision; screen = next; }
        renderedState = app;
        if (shown && !changed.isEmpty()) port.render(screen, changed);
    }

    /** Выделяет новый id; собственные окна запуска не должны пересекаться с id рекордера. */
    private String nextWindowId() {
        String id;
        do { id = recorder == null ? "w" + ++windowSequence : recorder.nextWindowId(); }
        while (windows.windows().stream().map(OpenWindows.OpenWindow::windowId).toList().contains(id));
        return id;
    }

    /** Несохранённый быстрый ввод не переживает изменение плана или открытие модального окна. */
    private void discardQuickEdit() {
        for (FormSession form : List.copyOf(forms.values())) {
            if (form.windowType() == WindowType.QUICK_EDIT_POPUP) form.closeRequested();
        }
    }

    /** Выбирает меню строки по модели, не по префиксу пользовательского идентификатора. */
    private ContextTarget rowContext(String id) {
        if (screen != null) {
            int index = screen.table().indexOf(id);
            if (index >= 0) {
                RowKind kind = screen.table().row(index).kind();
                if (kind == RowKind.MONTH_TOTAL) return new ContextTarget.Total(id);
                if (kind == RowKind.PAST_HEADER) return new ContextTarget.PastHeader(id);
            }
        }
        return new ContextTarget.Row(id);
    }

    /** Продолжение закрываемой формы открывает окно над её живым владельцем, а не над исчезающим окном. */
    private String modalOwner() {
        List<OpenWindows.OpenWindow> open = windows.windows();
        for (int index = open.size() - 1; index >= 0; index--) {
            OpenWindows.OpenWindow window = open.get(index);
            if (window.modal() && !window.windowId().equals(closingResultId)) return window.windowId();
        }
        return WindowState.MAIN_OWNER;
    }

    /** Убирает окно и закрывает дочерние ручки, не оставляя невидимых сеансов в снимке. */
    private void removeWindow(String id) {
        OpenWindows remaining = windows.without(id);
        List<String> removed = windows.windows().stream().map(OpenWindows.OpenWindow::windowId)
                .filter(old -> remaining.windows().stream().noneMatch(w -> w.windowId().equals(old))).toList();
        windows = remaining;
        for (String removedId : removed) {
            FormSession form = forms.remove(removedId);
            if (form != null && !removedId.equals(id)) form.closeRequested();
            AlertSession alert = alerts.remove(removedId);
            if (alert != null) alert.closed();
            WindowHandle handle = handles.remove(removedId);
            if (handle != null && !removedId.equals(id)) handle.close();
        }
        refresh();
    }

    /** Проверяет поток на границе ядра, чтобы клиенты не меняли состояние из фоновой задачи. */
    private void assertUiThread() {
        if (!port.executor().isUiThread()) {
            throw new IllegalStateException("Controller requires UI thread");
        }
    }
}
