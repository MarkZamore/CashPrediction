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
import ru.cashprediction.core.session.UiExecutor;
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
import ru.cashprediction.core.forecast.service.EngineForecastService;
import ru.cashprediction.core.service.plan.LocalPlanCommands;
import ru.cashprediction.core.service.plan.PlanCommands;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;
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
    private final UiPort clientPort;
    /** Видим и фоновым доставщикам задач; переход выполняется только в потоке контроллера. */
    private volatile boolean exited;
    private final AppEnvironment environment;
    /** Единственный изменяемый документ; прогноз остаётся ленивым до первого снимка состояния. */
    private final PlanDocument document;
    private final PlanCommands planCommands;
    private final PlanStorage planStorage;
    private AppSettings settings = AppSettings.defaults();
    private String selectedRowId = "";
    /** Выбор цели меню и раскрытие прошлого публикуются одним завершённым кадром. */
    private boolean contextSelectionInProgress;
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
    private final ExternalChangeGuard externalGuard;
    // Shutdown hook может читать ссылку вне UI-потока после отложенного завершения StartupFlow.
    private volatile SessionRecorder recorder;
    private MainScreenModel screen;
    private AppState renderedState;
    private boolean shown;
    private boolean started;
    private long windowSequence;
    private long chooserSequence;
    private long chooserConfirmationSequence;
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
        this.clientPort = Objects.requireNonNull(port, "port");
        this.port = new LifecyclePort();
        this.environment = Objects.requireNonNull(environment, "environment");
        document = new PlanDocument(Plan.empty(UiText.get("plan.defaultName"), environment.clock().today()), null,
                environment.clock()::today, new EngineForecastService());
        planCommands = new LocalPlanCommands(document);
        planStorage = new FilePlanStorage(environment.cashMemory());
        externalGuard = new ExternalChangeGuard(planStorage, environment.cashMemory());
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
            if (exited) return;
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

    /** Завершает доставку задач до вызова клиента; чистый снимок уже подготовлен потоком выхода. */
    private void terminate(ExitKind kind, int code) {
        if (exited) return;
        assertUiThread();
        autosaveService.stop();
        if (statusExpiry != null) statusExpiry.cancel();
        statusGeneration++;
        port.scheduler().shutdown();
        // Конечное состояние устанавливается до вызова клиента, включая синхронные события закрытия.
        exited = true;
        clientPort.exit(kind, code);
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
        if (exited) return;
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
        } catch (RuntimeException exception) {
            // Ожидаемые ошибки службы показываем; ошибки программирования сохраняют аварийное поведение.
            if (!(exception instanceof ru.cashprediction.core.forecast.service.ForecastFailure)) throw exception;
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
     * Строит общую модель страницы под предстартовым диалогом, не публикуя главный экран.
     * Сохраняет прежние bootstrap revision 0 и заголовок сообщения; не вызывает refresh/showMain.
     * Даже после внутреннего refresh это отдельная placeholder-модель, а не опубликованный screen.
     *
     * @return неизменяемая модель для адаптера до первого showMain
     */
    public MainScreenModel bootstrapPlaceholder() {
        assertUiThread();
        AppState app = state();
        return new MainScreenModel(0, UiText.get("alert.info.title"),
                MenuModels.menuBar(app, port.profile().kind()), MenuModels.toolbar(app, port.profile().kind()),
                SummaryBuilder.build(app), LazyTableModel.build(app, 0), ChartLayout.model(app, 0),
                StatusBuilder.build(app, environment.clock().now()), app.view().mode());
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

    /**
     * Проверяет источник, модальность и доступность команды, затем передаёт её соответствующему потоку.
     * После выхода вызов игнорируется. Команда формы допускается только для существующего сеанса,
     * над которым нет другого модального окна; команды главного окна блокируются модальностью.
     * Недоступная горячая клавиша может показать подсказку. Счётчик увеличивается перед передачей
     * разрешённой команды, поэтому ошибка её исполнения не отменяет учёт вызова.
     *
     * @param id команда
     * @param args аргументы или {@code null} для {@link CommandArgs#NONE}; без rowId используется выделение
     * @param source источник действия
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     * @throws NullPointerException если у активного контроллера id или source равен {@code null}
     * @throws RuntimeException если разбор аргументов или исполнение разрешённой команды завершается ошибкой
     */
    @Override
    public void command(CommandId id, CommandArgs args, InvokeSource source) {
        if (exited) return;
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

    /**
     * Находит привязку клавиши для области фокуса и профиля клиента и вызывает команду как горячую клавишу.
     * Enter на заголовке прошлого переключает группу; для карточки передаются её id и дата из сводки.
     * Клиент перед Enter в поле фильтра должен сначала передать текущий текст через {@link #filterText}.
     * Enter и Esc форм обрабатываются их сеансами.
     *
     * @param chord сочетание физических клавиш
     * @param scope область фокуса
     * @param focusId id карточки при фокусе на карточке; в остальных областях здесь не используется
     * @return {@code false} после выхода, при модальном окне или отсутствии привязки;
     *         {@code true} при найденной привязке, даже если её команда недоступна
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public boolean key(KeyChord chord, FocusScope scope, String focusId) {
        if (exited) return false;
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

    /**
     * Передаёт выделение потоку представления, который проверяет id и при необходимости раскрывает прошлое.
     * После выхода и при модальном окне ничего не меняет; неизвестная строка игнорируется потоком представления.
     *
     * @param rowId id строки; пустая строка или {@code null} снимает выделение
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public void selectRow(String rowId) {
        if (exited) return;
        assertUiThread();
        if (!windows.modalOpen()) views().selectRow(rowId);
    }

    /**
     * Обрабатывает щелчок по строке текущей таблицы: одиночный выделяет строку или переключает прошлое,
     * двойной открывает быструю правку непустой редактируемой суммы либо обычное изменение события.
     * Итоги и заголовок прошлого не открывают обычный редактор. После выхода, при модальном окне,
     * отсутствии модели или неизвестном rowId действие игнорируется.
     *
     * @param rowId id строки текущей модели
     * @param columnId id колонки; income и expense могут открыть быструю правку
     * @param how одиночный или двойной щелчок
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public void activateRow(String rowId, String columnId, Activation how) {
        if (exited) return;
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

    /**
     * Применяет текст фильтра через поток представления, если приложение активно и нет модального окна.
     * Задержку ввода обеспечивает клиент; этот метод дополнительного таймера не создаёт.
     * Повторное значение не меняет представление.
     *
     * @param text текст фильтра или {@code null} для очистки
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public void filterText(String text) {
        if (exited) return;
        assertUiThread();
        if (!windows.modalOpen()) views().filterText(text);
    }

    /**
     * Передаёт отпускание ползунка горизонта потоку представления для однократного изменения плана.
     * После выхода, при модальном окне и для другого id действие игнорируется без проверки значения.
     *
     * @param itemId ожидается {@code view.horizonSlider}
     * @param value горизонт в месяцах, от 1 до 120
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     * @throws IllegalArgumentException если принятое значение вне диапазона 1..120
     */
    @Override
    public void sliderCommit(String itemId, int value) {
        if (exited) return;
        assertUiThread();
        if (!windows.modalOpen() && "view.horizonSlider".equals(itemId)) views().horizonSliderCommit(value);
    }

    /**
     * Передаёт дополнительное ежемесячное сбережение потоку инструментов, который заменяет предыдущую
     * отложенную задачу и применяет последнее значение через 600 мс.
     * После выхода, при модальном окне и для другого id действие игнорируется без проверки значения.
     *
     * @param itemId ожидается {@code whatIf.extra}
     * @param value сумма в основных денежных единицах, от 0 до 10 000 000
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     * @throws IllegalArgumentException если принятое значение вне допустимого диапазона
     */
    @Override
    public void spinnerCommit(String itemId, long value) {
        if (exited) return;
        assertUiThread();
        if (!windows.modalOpen() && "whatIf.extra".equals(itemId)) tools().setWhatIfExtra(value);
    }

    /**
     * Помечает снимок сеанса изменённым при уведомлении о геометрии главного окна, в том числе при модальности.
     * Геометрию при захвате снимка читает порт: переданные значения здесь не сохраняются и не проверяются.
     * После выхода или без рекордера снимок не помечается.
     *
     * @param bounds границы окна в нормальном состоянии из уведомления клиента
     * @param maximized признак развёрнутости из уведомления клиента
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public void mainGeometry(WindowBounds bounds, boolean maximized) {
        if (exited) return;
        assertUiThread();
        // Геометрия принадлежит порту; намерение только помечает снимок изменённым.
        if (recorder != null) recorder.touch();
    }

    /**
     * Находит подсказку пункта в текущей модели главного меню и обновляет сообщение строки состояния.
     * Отсутствующий пункт, неподдерживаемый вид узла или уход указателя очищает подсказку.
     * После выхода вызов игнорируется; отдельной проверки модальности в этом обработчике нет.
     *
     * @param itemIdOrNull id пункта или {@code null} при уходе указателя
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public void menuHover(String itemIdOrNull) {
        if (exited) return;
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

    /**
     * Передаёт крестик или Alt+F4 потоку выхода, который выполняет общий сценарий завершения приложения.
     * После выхода или при открытом модальном окне запрос игнорируется.
     *
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public void closeMainRequested() {
        if (exited) return;
        assertUiThread();
        if (!windows.modalOpen()) exit().requestExit();
    }

    /**
     * Передаёт необработанную ошибку клиента потоку восстановления, независимо от наличия модального окна.
     * Доставку в поток контроллера обеспечивает вызывающая сторона; после выхода ошибка сюда не передаётся.
     *
     * @param thread поток, в котором возникла ошибка
     * @param error исходная ошибка
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public void uncaught(Thread thread, Throwable error) {
        if (exited) return;
        assertUiThread();
        recovery().uncaught(thread, error);
    }

    /** {@inheritDoc} */
    @Override public void clientError(String message, String stack) {
        if (exited) return;
        assertUiThread();
        recovery().clientError(message, stack);
    }

    /**
     * Строит контекстное меню по текущему состоянию и профилю клиента. Для строки, итога и заголовка
     * прошлого сначала проверяет и устанавливает выделение через поток представления, публикуя
     * завершённый кадр после этой операции даже при её ошибке.
     * Меню предпросмотра допускается только для существующей формы, над которой нет другого
     * модального окна; для остальных целей открытое модальное окно запрещает меню.
     *
     * @param target объект, для которого запрошено меню
     * @return пункты меню или пустой список после выхода либо при запрете по модальности или сеансу
     * @throws IllegalStateException если активный контроллер вызван вне своего потока
     */
    @Override
    public List<MenuNode> contextMenu(ContextTarget target) {
        if (exited) return List.of();
        assertUiThread();
        if (target instanceof ContextTarget.Preview preview) {
            if (session(preview.windowId()).isEmpty() || windows.topModal()
                    .filter(top -> !top.windowId().equals(preview.windowId())).isPresent()) return List.of();
        } else if (windows.modalOpen()) return List.of();
        String rowId = switch (target) {
            case ContextTarget.Row row -> row.rowId();
            case ContextTarget.Total total -> total.rowId();
            case ContextTarget.PastHeader header -> header.rowId();
            default -> null;
        };
        if (rowId != null) {
            // JavaFX: ContextMenu → Swing: JPopupMenu → Web: contextmenu.
            // ViewFlow проверяет id и раскрывает прошлое; клиент не принимает решение о выделении.
            contextSelectionInProgress = true;
            try {
                views().selectRow(rowId);
            } finally {
                contextSelectionInProgress = false;
                refresh();
            }
        }
        return MenuModels.contextMenu(state(), target, port.profile().kind());
    }

    /**
     * Запрашивает подсказку у текущей таблицы только после проверки её ревизии и границ строки.
     * Проверяет поток контроллера также после выхода; модальность не ограничивает запрос.
     *
     * @param revision ревизия модели таблицы у клиента
     * @param index индекс строки от нуля
     * @param columnId id колонки
     * @return текст подсказки или пустая строка при отсутствии модели, другой ревизии,
     *         недопустимом индексе либо отсутствии подсказки
     * @throws IllegalStateException если вызван вне потока контроллера
     */
    @Override
    public String tableTooltip(long revision, int index, String columnId) {
        assertUiThread();
        if (screen == null || revision != screen.table().revision() || index < 0 || index >= screen.table().rowCount()) {
            return "";
        }
        return screen.table().tooltip(index, columnId);
    }

    /** Возвращает явные позиции значков, сохраняя проверку ревизии и границ строк. */
    @Override
    public ru.cashprediction.core.ui.view.table.DecoratedTooltip decoratedTableTooltip(
            long revision, int index, String columnId) {
        assertUiThread();
        if (screen == null || revision != screen.table().revision() || index < 0 || index >= screen.table().rowCount()) {
            return ru.cashprediction.core.ui.view.table.DecoratedTooltip.plain("");
        }
        return screen.table().decoratedTooltip(index, columnId);
    }

    /**
     * Рассчитывает сцену текущей модели графика для области клиента. До первого кадра строит
     * временную модель из текущего состояния с текущей ревизией, не публикуя её как кадр.
     * Запрос не блокируется модальностью или завершением приложения.
     *
     * @param width ширина области рисования в пикселях
     * @param height высота области рисования в пикселях
     * @return сцена, рассчитанная моделью графика
     * @throws IllegalStateException если вызван вне потока контроллера
     */
    @Override
    public ChartScene chartScene(double width, double height) {
        assertUiThread();
        return (screen == null ? ChartLayout.model(state(), revision) : screen.chart()).layout(width, height);
    }

    /**
     * Передаёт координаты указателя текущей модели графика только при совпадении её ревизии.
     * Запрос не блокируется модальностью или завершением приложения.
     *
     * @param revision ревизия графика у клиента
     * @param x координата указателя по горизонтали
     * @param y координата указателя по вертикали
     * @param width ширина области рисования
     * @param height высота области рисования
     * @return наведение или пустое значение при отсутствии кадра, другой ревизии,
     *         отсутствии данных либо указателе вне области построения
     * @throws IllegalStateException если вызван вне потока контроллера
     */
    @Override
    public Optional<ChartHover> chartHover(long revision, double x, double y, double width, double height) {
        assertUiThread();
        return screen == null || revision != screen.chart().revision() ? Optional.empty()
                : screen.chart().hover(x, y, width, height);
    }

    /**
     * Строит карточку дня из текущего состояния с учётом фильтров событий и валюты плана.
     * При отсутствии прогноза построитель возвращает карточку без событий и баланса.
     * Запрос не блокируется модальностью или завершением приложения.
     *
     * @param date день карточки
     * @return модель карточки дня
     * @throws IllegalStateException если вызван вне потока контроллера
     * @throws NullPointerException если date равен {@code null}
     */
    @Override
    public DayCardModel dayCard(LocalDate date) {
        assertUiThread();
        return PopupBuilders.dayCard(state(), date);
    }

    /**
     * Строит спарклайн карточки сводки из текущего состояния; построитель проверяет известность id.
     * При отсутствии данных возвращает модель без точек с пояснением.
     * Запрос не блокируется модальностью или завершением приложения.
     *
     * @param cardId id карточки из каталога сводки
     * @return модель спарклайна
     * @throws IllegalStateException если вызван вне потока контроллера
     * @throws IllegalArgumentException если карточка неизвестна
     */
    @Override
    public SparklineModel sparkline(String cardId) {
        assertUiThread();
        return PopupBuilders.sparkline(state(), cardId);
    }

    /**
     * Строит календарь из шести недель с понедельника, отмечая выбранный день и сегодня по часам окружения.
     * Запрос не блокируется модальностью или завершением приложения.
     *
     * @param month показываемый месяц
     * @param selected выбранная дата или {@code null}, если выбора нет
     * @return модель календаря с 42 днями
     * @throws IllegalStateException если вызван вне потока контроллера
     * @throws NullPointerException если month равен {@code null}
     */
    @Override
    public CalendarModel calendar(YearMonth month, LocalDate selected) {
        assertUiThread();
        return PopupBuilders.calendar(month, selected, environment.clock().today());
    }

    /** {@inheritDoc} */
    @Override public PlanDocument document() { return document; }

    /** {@inheritDoc} */
    @Override public PlanCommands planCommands() { return planCommands; }

    /** {@inheritDoc} */
    @Override public PlanStorage planStorage() { return planStorage; }
    /** {@inheritDoc} */
    @Override public SessionRecorder recorder() { return recorder; }

    /**
     * Сохраняет последний уже захваченный снимок при внешнем завершении JVM.
     * Вызывается из shutdown hook без обращения к UI и без отметки корректного выхода.
     * До установки рекордера ничего не делает; закрытый рекордер сам запрещает повторную запись.
     * Это best-effort запись, а не обещание захватить ещё не обработанный ввод или ограничить время дискового IO.
     */
    public void saveShutdownSnapshot() {
        SessionRecorder current = recorder;
        if (current != null) current.saveShutdownSnapshot();
    }
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
        if (exited) return;
        assertUiThread(); recorderStatus = Objects.requireNonNull(value); refresh();
    }
    /** {@inheritDoc} */
    @Override public void setAutosaveProblem(String value) {
        if (exited) return;
        assertUiThread(); autosaveProblem = Objects.requireNonNullElse(value, ""); refresh();
    }
    /** {@inheritDoc} */
    @Override public void updateSettings(UnaryOperator<AppSettings> change) {
        if (exited) return;
        assertUiThread();
        AppSettings updated = Objects.requireNonNull(change.apply(settings));
        if (!updated.equals(settings)) { settings = updated; settingsService.changed(); refresh(); }
    }
    /** {@inheritDoc} */
    @Override public void updateView(UnaryOperator<ViewState> change) {
        if (exited) return;
        assertUiThread();
        ViewState updated = Objects.requireNonNull(change.apply(document.viewState()));
        document.setViewState(updated);
        updateSettings(updated::applyTo);
        if (recorder != null) recorder.touch();
        refresh();
    }
    /** {@inheritDoc} */
    @Override public void setSelection(String value) {
        if (exited) return;
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
        if (exited) return;
        assertUiThread();
        if (pastExpanded != value) {
            pastExpanded = value;
            if (recorder != null) recorder.touch();
            refresh();
        }
    }
    /** {@inheritDoc} */
    @Override public void setPlansFolder(Path value) {
        if (exited) return;
        assertUiThread(); plansFolder = value == null ? environment.cashMemory() : value; refresh();
    }
    /** {@inheritDoc} */
    @Override public void status(StatusLevel level, String key, Object... args) {
        if (exited) return;
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
        if (exited) return;
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
        // Серверный аналог нативного выбора не сдвигает идентификаторы общих диалогов.
        boolean transientChooser = request.logic() instanceof ru.cashprediction.core.ui.forms.simple.FileBrowserForm;
        String id = request.restored() == null ? transientChooser ? "chooser" + ++chooserSequence : nextWindowId()
                : request.restored().id();
        String owner = placement == null ? modalOwner() : placement.ownerId();
        Placement effective = placement == null ? Placement.centered(owner) : placement;
        Consumer<Object> callback = onResult == null ? ignored -> { } : onResult;
        FormSession result = new FormSession(request.type(), request.modal(), request.logic(),
                new FormContext(id, owner, request.context(), state(), document.forecastService()), new FormSession.Host() {
            /**
             * Включает показанную форму в запись сеанса, если рекордер уже создан.
             * @param form показанный сеанс формы
             */
            @Override public void registered(FormSession form) { if (recorder != null) recorder.register(form); }
            /**
             * Убирает форму из записи при наличии рекордера, затем удаляет окно и его дочерние окна
             * из состояния контроллера. Ошибка рекордера прерывает последующее удаление.
             * @param form закрытый сеанс формы
             */
            @Override public void unregistered(FormSession form) {
                if (recorder != null) recorder.unregister(form);
                removeWindow(form.windowId());
            }
            /**
             * Помечает общий снимок изменённым после изменения значений или геометрии формы.
             * Без рекордера ничего не делает.
             * @param form сеанс, сообщивший об изменении
             */
            @Override public void touched(FormSession form) { if (recorder != null) recorder.touch(); }
            /**
             * Передаёт результат продолжению формы и после успеха удаляет окно.
             * На время продолжения отмечает закрываемую форму, чтобы новое окно получило живого владельца.
             * При ошибке продолжения восстанавливает эту отметку и передаёт ошибку сеансу, не удаляя форму;
             * сеанс может оставить её открытой и показать проблему. После выхода вызов игнорируется.
             * @param form закрываемый сеанс
             * @param value результат закрытия или {@code null} при отмене
             * @throws RuntimeException если продолжение не смогло применить результат
             */
            @Override public void closed(FormSession form, Object value) {
                if (exited) return;
                String previous = closingResultId;
                closingResultId = form.windowId();
                try { callback.accept(value); }
                finally { closingResultId = previous; }
                removeWindow(form.windowId());
            }
            /**
             * Передаёт действие продолжению, сохраняя форму открытой; после выхода ничего не делает.
             * Ошибка продолжения передаётся сеансу для показа проблемы без применения обновлений полей.
             * @param form сеанс, запросивший действие
             * @param value действие формы
             * @throws RuntimeException если продолжение не смогло выполнить действие
             */
            @Override public void applied(FormSession form, Object value) { if (!exited) callback.accept(value); }
            /**
             * Открывает дочернее окно общим фабричным маршрутом, заменяя id новым и назначая родительскую
             * форму владельцем. Предупреждение фабрики превращает в ошибку для вызывающего сеанса.
             * После выхода ничего не открывает.
             * @param parent сеанс родительской формы
             * @param child описание дочернего окна с временным id
             * @throws IllegalArgumentException если фабрика сообщает о недопустимом состоянии окна
             */
            @Override public void openChild(FormSession parent, WindowState child) {
                if (exited) return;
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
        // Это часть выбора файла: FX показывает её внутри нативного окна, Swing/Web отдельным сообщением.
        String id = "replaceFile".equals(spec.purpose()) && !spec.restorable()
                ? "chooserConfirm" + ++chooserConfirmationSequence : nextWindowId();
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
            /**
             * Включает показанное восстанавливаемое сообщение в запись сеанса при наличии рекордера.
             * @param session показанный сеанс сообщения
             */
            @Override public void registered(AlertSession session) { if (recorder != null) recorder.register(session); }
            /**
             * Убирает закрытое сообщение из записи сеанса при наличии рекордера.
             * Удаление окна из состояния контроллера выполняется отдельным маршрутом закрытия сообщения.
             * @param session закрытый сеанс сообщения
             */
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
                if (exited) return;
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
        if (exited) return;
        assertUiThread();
        if (shown) throw new IllegalStateException("Main window already shown");
        refresh();
        shown = true;
        port.showMain(screen, restored);
    }

    /** {@inheritDoc} */
    @Override public void refresh() {
        if (exited) return;
        assertUiThread();
        if (contextSelectionInProgress) return;
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
        if (exited) return;
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

    /**
     * Внутренняя граница жизненного цикла: общие потоки получают те же операции клиента,
     * но таймеры, доставленные задачи и ответы выбора не продолжаются после выхода.
     * Профиль и средства доставки берутся у клиента один раз, до завершения.
     */
    private final class LifecyclePort implements UiPort {
        private final ClientProfile profile = clientPort.profile();
        private final UiExecutor clientExecutor = clientPort.executor();
        private final Scheduler clientScheduler = clientPort.scheduler();
        private boolean schedulerStopped;
        private final UiExecutor executor = new UiExecutor() {
            /** {@inheritDoc} */
            @Override public void execute(Runnable task) {
                if (!exited) clientExecutor.execute(guard(task));
            }
            /** {@inheritDoc} */
            @Override public boolean isUiThread() { return clientExecutor.isUiThread(); }
        };
        private final Scheduler scheduler = new Scheduler() {
            /** {@inheritDoc} */
            @Override public Task schedule(Runnable action, Duration delay) {
                return exited ? () -> { } : clientScheduler.schedule(guard(action), delay);
            }
            /** {@inheritDoc} */
            @Override public Task scheduleAtFixedRate(Runnable action, Duration initialDelay, Duration period) {
                return exited ? () -> { } : clientScheduler.scheduleAtFixedRate(guard(action), initialDelay, period);
            }
            /** {@inheritDoc} */
            @Override public void execute(Runnable action) {
                if (!exited) clientScheduler.execute(guard(action));
            }
            /** {@inheritDoc} */
            @Override public synchronized void shutdown() {
                if (!schedulerStopped) {
                    schedulerStopped = true;
                    clientScheduler.shutdown();
                }
            }
        };

        /** Не позволяет уже доставленному вызову продолжить работу завершённого ядра. */
        private Runnable guard(Runnable action) { return () -> { if (!exited) action.run(); }; }

        /** Не позволяет прямому вызову потока обратиться к завершённому клиенту. */
        private void requireActive() {
            if (exited) throw new IllegalStateException("Controller exited");
        }

        /** {@inheritDoc} */
        @Override public ClientProfile profile() { return profile; }
        /** {@inheritDoc} */
        @Override public UiExecutor executor() { return executor; }
        /** {@inheritDoc} */
        @Override public Scheduler scheduler() { return scheduler; }
        /** {@inheritDoc} */
        @Override public void showMain(MainScreenModel model, MainWindowState restored) {
            requireActive(); clientPort.showMain(model, restored);
        }
        /** {@inheritDoc} */
        @Override public void render(MainScreenModel model, EnumSet<ScreenPart> changed) {
            requireActive(); clientPort.render(model, changed);
        }
        /** {@inheritDoc} */
        @Override public MainGeometry mainGeometry() { requireActive(); return clientPort.mainGeometry(); }
        /** {@inheritDoc} */
        @Override public WindowHandle openForm(FormSession session, ru.cashprediction.core.ui.form.FormSpec spec,
                ru.cashprediction.core.ui.form.FormView initial, Placement placement) {
            // JavaFX: Dialog → Swing: JDialog → Web: dialog.
            requireActive(); return clientPort.openForm(session, spec, initial, placement);
        }
        /** {@inheritDoc} */
        @Override public WindowHandle showAlert(AlertSpec spec, AlertSession session, Consumer<String> onButton) {
            // JavaFX: Alert → Swing: JDialog → Web: dialog.
            requireActive(); return clientPort.showAlert(spec, session, button -> {
                if (!exited) onButton.accept(button);
            });
        }
        /** {@inheritDoc} */
        @Override public void showContextMenu(ContextTarget target, List<MenuNode> items) {
            // JavaFX: ContextMenu → Swing: JPopupMenu → Web: contextMenu.
            requireActive(); clientPort.showContextMenu(target, items);
        }
        /** {@inheritDoc} */
        @Override public void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> onResult) {
            // JavaFX: FileChooser → Swing: JFileChooser → Web: dialog.
            requireActive(); clientPort.chooseFile(spec, result -> { if (!exited) onResult.accept(result); });
        }
        /** {@inheritDoc} */
        @Override public void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> onResult) {
            // JavaFX: DirectoryChooser → Swing: JFileChooser → Web: dialog.
            requireActive(); clientPort.chooseDirectory(spec, result -> { if (!exited) onResult.accept(result); });
        }
        /** {@inheritDoc} */
        @Override public byte[] renderChartPng(ChartScene scene) throws java.io.IOException {
            requireActive(); return clientPort.renderChartPng(scene);
        }
        /** {@inheritDoc} */
        @Override public void focus(FocusTarget target) { requireActive(); clientPort.focus(target); }
        /** {@inheritDoc} */
        @Override public void revealRow(String rowId, RevealMode mode) {
            requireActive(); clientPort.revealRow(rowId, mode);
        }
        /** {@inheritDoc} */
        @Override public void copyToClipboard(String text) { requireActive(); clientPort.copyToClipboard(text); }
        /** {@inheritDoc} */
        @Override public void reloadPage() { if (!exited) { assertUiThread(); clientPort.reloadPage(); } }
        /** {@inheritDoc} */
        @Override public void exit(ExitKind kind, int code) { terminate(kind, code); }
    }
}
