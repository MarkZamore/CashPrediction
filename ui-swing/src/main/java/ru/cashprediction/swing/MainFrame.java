package ru.cashprediction.swing;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.DocumentEvent;
import ru.cashprediction.core.document.EventKind;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.RestoreTarget;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SnapshotSource;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.swing.action.QuickEditOpener;
import ru.cashprediction.swing.action.SwingActions;
import ru.cashprediction.swing.action.SwingAppContext;
import ru.cashprediction.swing.dialog.SwingDialogHost;
import ru.cashprediction.swing.dialog.SwingIcons;
import ru.cashprediction.swing.menu.AppMenuBar;
import ru.cashprediction.swing.menu.ForecastPopupMenu;
import ru.cashprediction.swing.menu.MainCommands;
import ru.cashprediction.swing.menu.MainToolBar;
import ru.cashprediction.swing.menu.ViewModels;
import ru.cashprediction.swing.popup.QuickEditPopup;
import ru.cashprediction.swing.session.SettingsKeeper;
import ru.cashprediction.swing.session.SwingWindowFactory;
import ru.cashprediction.swing.view.BalanceChartComponent;
import ru.cashprediction.swing.view.ForecastTable;
import ru.cashprediction.swing.view.ForecastTableModel;
import ru.cashprediction.swing.view.StatusBar;
import ru.cashprediction.swing.view.SummaryPanel;
import ru.cashprediction.swing.view.TableItem;

/**
 * Главное окно Swing-клиента и его контроллер: меню, панель инструментов, сводка, таблица или график, строка
 * состояния — и связь всего этого с документом плана, настройками и записью сессии.
 *
 * <p><b>Роли.</b> Класс реализует сразу четыре контракта:</p>
 * <ul>
 *   <li>{@link SwingAppContext} — что фасад команд {@link SwingActions} знает о приложении;</li>
 *   <li>{@link SnapshotSource} — что попадает в снимок сессии о главном окне и плане;</li>
 *   <li>{@link RestoreTarget} — как {@code RestoreCoordinator} открывает план, применяет вид и показывает окно;</li>
 *   <li>{@link MainCommands} — команды окна для меню (выход, недавние планы, фильтр).</li>
 * </ul>
 * <p>Окно — не наследник {@code JFrame}, а владелец: у {@code java.awt.Container} есть устаревший метод
 * {@code layout()}, который конфликтовал бы с {@link SwingAppContext#layout()}.</p>
 *
 * <p><b>Синхронизация.</b> Все изменения плана и вида идут через {@link PlanDocument}. Слушатель документа
 * перестраивает таблицу, график и сводку, приводит отметки меню и панели инструментов к {@link ViewState} (через
 * общие модели {@link ViewModels}), обновляет заголовок «CashPrediction — план*», сохраняет вид в настройках,
 * запускает автосохранение и сообщает рекордеру сессии {@code touch()}.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class MainFrame implements SwingAppContext, SnapshotSource, RestoreTarget, MainCommands {

    /** Имя пустого плана, если открыть нечего. */
    public static final String DEFAULT_PLAN_NAME = "Мой план";
    /** Префикс заголовка окна. */
    public static final String TITLE_PREFIX = "CashPrediction — ";

    private static final String CARD_TABLE = "table";
    private static final String CARD_CHART = "chart";

    private final CashMemoryLayout layout;
    private final SettingsKeeper settingsKeeper;
    private final JFrame frame = new JFrame("CashPrediction");
    private final PlanDocument document;
    private final SwingActions actions;
    private final SwingWindowFactory windowFactory;
    private final ViewModels models;
    private final AppMenuBar menuBar;
    private final MainToolBar toolBar;
    private final SummaryPanel summary;
    private final ForecastTable table;
    private final BalanceChartComponent chart;
    private final StatusBar statusBar = new StatusBar();
    private final JPanel center = new JPanel(new CardLayout());
    private final Timer autosaveTimer;

    private SessionRecorder recorder;
    private QuickEditPopup quickEdit;
    /** Развёрнута ли группа «Прошедшие события». */
    private boolean pastExpanded;
    /** Окно уже показывалось: до этого у диалогов нет владельца (диалог восстановления до старта). */
    private boolean shown;
    /** Границы окна в обычном (не развёрнутом) состоянии — для снимка. */
    private Rectangle normalBounds;
    private List<String> restoreWarnings = List.of();
    private boolean exiting;

    /**
     * Создаёт главное окно (не показывает его) с пустым планом «Мой план».
     *
     * @param layout   раскладка папки CashMemory
     * @param settings хранитель настроек
     */
    public MainFrame(CashMemoryLayout layout, SettingsKeeper settings) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.settingsKeeper = Objects.requireNonNull(settings, "settings");
        document = new PlanDocument(Plan.empty(DEFAULT_PLAN_NAME, LocalDate.now()), null, LocalDate::now);
        document.setViewState(ViewState.fromSettings(settings.settings()));
        actions = new SwingActions(this);
        windowFactory = new SwingWindowFactory(actions);
        models = new ViewModels(
                mode -> changeView(v -> v.withMode(mode)),
                period -> changeView(v -> v.withPeriod(period)),
                (key, value) -> changeView(v -> ViewModels.withFilter(v, key, value)),
                actions::setIncomeWhatIf,
                actions::setExpenseWhatIf,
                actions::setExtraSaving,
                on -> updateSettings(s -> s.withAutosave(on)),
                actions::setDefaultStore);
        menuBar = new AppMenuBar(actions, models, this);
        toolBar = new MainToolBar(actions, models, text -> changeView(v -> v.withFilterText(text)));
        summary = new SummaryPanel((title, value, date, component, x, y) ->
                ForecastPopupMenu.forCard(actions, models, this, title, value, date).show(component, x, y));
        table = new ForecastTable(new ForecastTableModel(), new TableHandler());
        chart = new BalanceChartComponent((date, component, x, y) ->
                // JavaFX: ContextMenu.show(node, screenX, screenY) → Swing: JPopupMenu.show(component, x, y) → Web: <ul class="context-menu">
                ForecastPopupMenu.forChart(actions, models, this, date).show(component, x, y));
        autosaveTimer = new Timer(1000, e -> {
            if (settings().autosave()) {
                actions.autosave();
            }
        });
        autosaveTimer.setRepeats(false);
        buildFrame();
        document.addListener(this::onDocumentEvent);
        models.sync(document.viewState());
        models.syncSettings(settings.settings().autosave(), settings.settings().recoveryStore());
        menuBar.updateUndoRedo(document);
        syncHorizon();
        refreshViews();
        updateTitle();
    }

    private void buildFrame() {
        frame.setIconImages(SwingIcons.appIcons());
        // Закрытие окна проходит через тот же путь, что «Файл → Выход»: снимок, вопрос о сохранении, настройки.
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setJMenuBar(menuBar.menuBar());
        JPanel north = new JPanel(new BorderLayout());
        north.add(toolBar.toolBar(), BorderLayout.NORTH);
        north.add(summary, BorderLayout.CENTER);
        center.add(new JScrollPane(table), CARD_TABLE);
        center.add(chart, CARD_CHART);
        JPanel content = new JPanel(new BorderLayout());
        content.add(north, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(statusBar, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.setMinimumSize(new Dimension(820, 520));
        frame.setSize(1240, 800);
        frame.setLocationRelativeTo(null);
        normalBounds = frame.getBounds();
        frame.addWindowListener(new WindowAdapter() {
            /** Пользователь закрывает окно крестиком: выполняется то же, что кнопка отмены или команда выхода. */
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }

            /** Окно потеряло активность: закрываются всплывающие панели, привязанные к нему. */
            @Override
            public void windowDeactivated(WindowEvent e) {
                chart.hidePopups();
                summary.hidePopups();
            }
        });
        frame.addComponentListener(new ComponentAdapter() {
            /** Окно перемещено: положение попадёт в следующий снимок сессии. */
            @Override
            public void componentMoved(ComponentEvent e) {
                rememberBounds();
            }

            /** Размер окна изменён: размер попадёт в следующий снимок сессии. */
            @Override
            public void componentResized(ComponentEvent e) {
                rememberBounds();
            }
        });
        frame.addWindowStateListener(e -> touch());
    }

    private void rememberBounds() {
        if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0 && frame.isShowing()) {
            normalBounds = frame.getBounds();
        }
        touch();
    }

    // ================================================================== связь с рекордером и запуск

    /**
     * Подключает рекордер сессии (создаётся после окна: окно — его источник снимков).
     *
     * @param sessionRecorder рекордер
     */
    public void attachRecorder(SessionRecorder sessionRecorder) {
        recorder = Objects.requireNonNull(sessionRecorder, "sessionRecorder");
        // Состояние хранилищ приходит из фонового потока записи — в строку состояния только через EDT.
        recorder.addStatusListener(status -> SwingUtilities.invokeLater(() -> statusBar.updateStore(status)));
    }

    /** Показывает главное окно (при восстановлении — уже с границами и видом из снимка). */
    public void showFrame() {
        shown = true;
        if (!frame.isVisible()) {
            frame.setVisible(true);
        }
        frame.toFront();
    }

    /**
     * Окно Swing.
     *
     * @return главное окно
     */
    public JFrame frame() {
        return frame;
    }

    /**
     * Фасад команд.
     *
     * @return фасад
     */
    public SwingActions actions() {
        return actions;
    }

    /**
     * Фабрика окон для восстановления и самотеста.
     *
     * @return фабрика
     */
    public SwingWindowFactory windowFactory() {
        return windowFactory;
    }

    /**
     * Общие модели переключателей (вид, период, фильтры) — самотест щёлкает их как пользователь.
     *
     * @return модели
     */
    public ViewModels models() {
        return models;
    }

    /**
     * Панель инструментов (поле фильтра).
     *
     * @return панель
     */
    public MainToolBar toolBar() {
        return toolBar;
    }

    /**
     * Строка состояния.
     *
     * @return строка состояния
     */
    public StatusBar statusBar() {
        return statusBar;
    }

    /**
     * Открытая быстрая правка суммы.
     *
     * @return панель или пусто
     */
    public Optional<QuickEditPopup> quickEditPopup() {
        return Optional.ofNullable(quickEdit).filter(QuickEditPopup::isShowing);
    }

    /**
     * Запоминает отчёт восстановления (замечания видны в самотесте).
     *
     * @param report отчёт координатора
     */
    public void setRestoreReport(RestoreReport report) {
        restoreWarnings = report == null ? List.of() : List.copyOf(report.warnings());
    }

    /**
     * Замечания последнего восстановления.
     *
     * @return список (пустой, если восстановления не было или оно чистое)
     */
    public List<String> restoreWarnings() {
        return restoreWarnings;
    }

    /**
     * Особое состояние записи сессии в строке состояния («запись отключена»).
     *
     * @param note текст
     */
    public void setRecordingNote(String note) {
        statusBar.setRecordingNote(note);
    }

    // ================================================================== SwingAppContext

    /** {@inheritDoc} */
    @Override
    public JFrame owner() {
        // Пока окно не показано, диалоги (например, «уже запущен») открываются без владельца и видны в панели задач.
        return shown ? frame : null;
    }

    /** {@inheritDoc} */
    @Override
    public PlanDocument document() {
        return document;
    }

    /** {@inheritDoc} */
    @Override
    public SessionRecorder recorder() {
        return recorder;
    }

    /** {@inheritDoc} */
    @Override
    public CashMemoryLayout layout() {
        return layout;
    }

    /** {@inheritDoc} */
    @Override
    public AppSettings settings() {
        return settingsKeeper.settings();
    }

    /** {@inheritDoc} */
    @Override
    public void updateSettings(UnaryOperator<AppSettings> change) {
        if (settingsKeeper.update(change)) {
            AppSettings s = settingsKeeper.settings();
            models.syncSettings(s.autosave(), s.recoveryStore());
            if (s.autosave() && document.isDirty()) {
                autosaveTimer.restart();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public LocalDate today() {
        return LocalDate.now();
    }

    /** {@inheritDoc} */
    @Override
    public void openPlanDocument(Plan plan, Path file, boolean dirty, List<Diagnostic> diagnostics) {
        closeQuickEdit();
        table.clearSelection();
        pastExpanded = false;
        document.replace(plan, file, dirty, diagnostics);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> selectedRowId() {
        return table.selectedRowId();
    }

    /** {@inheritDoc} */
    @Override
    public QuickEditOpener quickEdit() {
        return this::openQuickEdit;
    }

    /** {@inheritDoc} */
    @Override
    public void showStatus(String message) {
        statusBar.showMessage(message);
    }

    /** {@inheritDoc} */
    @Override
    public void writeChartPng(Path file) throws IOException {
        int width = chart.isShowing() && chart.getWidth() > 200 ? chart.getWidth() : 1200;
        int height = chart.isShowing() && chart.getHeight() > 150 ? chart.getHeight() : 700;
        chart.writePng(file, width, height);
    }

    // ================================================================== MainCommands

    /** {@inheritDoc} */
    @Override
    public void exit() {
        if (exiting) {
            return;
        }
        actions.confirmExit(ok -> {
            if (ok) {
                shutdown();
            }
        });
    }

    /**
     * Корректный выход без вопросов (самотест): несохранённые изменения плана отбрасываются, настройки пишутся,
     * рекордер отмечает корректное завершение.
     */
    public void exitWithoutAsking() {
        if (recorder != null) {
            recorder.saveNow();
        }
        shutdown();
    }

    private void shutdown() {
        exiting = true;
        closeQuickEdit();
        autosaveTimer.stop();
        settingsKeeper.update(document.viewState()::applyTo);
        settingsKeeper.writeNow();
        if (recorder != null) {
            // Снимок и отметка «closed» во всех хранилищах: следующий запуск не предложит восстановление.
            recorder.shutdownClean();
        }
        frame.dispose();
        System.exit(0);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> recentPlans() {
        return settings().recentPlans();
    }

    /** {@inheritDoc} */
    @Override
    public void focusFilter() {
        toolBar.filterField().requestFocusInWindow();
        toolBar.filterField().selectAll();
    }

    /** {@inheritDoc} */
    @Override
    public void showTableAt(LocalDate date) {
        if (date == null) {
            return;
        }
        changeView(v -> v.withMode(ViewMode.TABLE));
        ForecastTableModel model = table.forecastModel();
        for (TableItem item : model.items()) {
            if (item.isRow() && !item.row().date().isBefore(date)) {
                selectRowId(item.rowId());
                return;
            }
        }
    }

    // ================================================================== SnapshotSource

    /** {@inheritDoc} */
    @Override
    public MainWindowState captureMain() {
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        Rectangle b = maximized && normalBounds != null ? normalBounds : frame.getBounds();
        WindowBounds bounds = b.width > 0 && b.height > 0 ? new WindowBounds(b.x, b.y, b.width, b.height) : null;
        ViewState view = document.viewState();
        Map<String, Boolean> filters = new LinkedHashMap<>();
        for (String key : ViewModels.FILTER_KEYS) {
            filters.put(key, ViewModels.filterValue(view, key));
        }
        return new MainWindowState(bounds, maximized, view.mode().name(), planPathForSnapshot(), view.period().name(),
                filters, view.filterText(), table.selectedRowId().orElse(""));
    }

    /** {@inheritDoc} */
    @Override
    public PlanState capturePlan() {
        // Несохранённый план целиком уходит в снимок: после сбоя его больше негде взять.
        return document.isDirty() ? PlanState.dirty(PlanMarkdownWriter.write(document.plan())) : PlanState.CLEAN;
    }

    private String planPathForSnapshot() {
        return document.file().map(file -> {
            Path absolute = file.toAbsolutePath().normalize();
            Path dir = layout.dir().toAbsolutePath().normalize();
            // План из CashMemory записывается относительным именем: папку можно перенести вместе с программой.
            return dir.equals(absolute.getParent()) ? absolute.getFileName().toString() : absolute.toString();
        }).orElse("");
    }

    // ================================================================== RestoreTarget

    /** {@inheritDoc} */
    @Override
    public void loadPlan(PlanState plan, String planPath, Consumer<String> warn) {
        Path file = resolvePlanPath(planPath, warn);
        if (plan.dirty()) {
            String fallback = file != null ? PlanMarkdownReader.nameWithoutExtension(file) : "План из снимка";
            // Исключение разбора уходит координатору: он не начнёт запись сессии и сохранит снимок.
            ReadResult read = PlanMarkdownReader.read(plan.markdown(), fallback, today());
            openPlanDocument(read.plan(), file, true, read.diagnostics());
            actions.trackFile(file);
            if (file != null) {
                String name = planPathForSnapshot();
                updateSettings(s -> s.withPlanOpened(name));
            }
            if (read.hasWarnings()) {
                warn.accept("Несохранённый план из снимка прочитан с замечаниями: Инструменты → Проверить план");
            }
            return;
        }
        if (file == null) {
            openPlanDocument(Plan.empty(DEFAULT_PLAN_NAME, today()), null, false, List.of());
            actions.trackFile(null);
            return;
        }
        if (!actions.loadPlanForRestore(file, warn)) {
            openPlanDocument(Plan.empty(DEFAULT_PLAN_NAME, today()), null, false, List.of());
            actions.trackFile(null);
            warn.accept("План «" + planPath + "» не открылся — открыт пустой план «" + DEFAULT_PLAN_NAME + "»");
        }
    }

    private Path resolvePlanPath(String planPath, Consumer<String> warn) {
        if (planPath == null || planPath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(planPath);
            return path.isAbsolute() ? path : layout.dir().resolve(path);
        } catch (InvalidPathException e) {
            warn.accept("Некорректный путь плана в снимке: «" + planPath + "»");
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void applyMain(MainWindowState main) {
        WindowBounds b = main.bounds();
        if (b != null && b.width() >= 200 && b.height() >= 150) {
            Rectangle r = new Rectangle((int) Math.round(b.x()), (int) Math.round(b.y()),
                    (int) Math.round(b.width()), (int) Math.round(b.height()));
            if (SwingDialogHost.isOnScreen(r)) {
                frame.setBounds(r);
            } else {
                // Экран, на котором было окно, отключён: сохраняем размер и ставим окно по центру.
                frame.setSize(r.width, r.height);
                frame.setLocationRelativeTo(null);
            }
            normalBounds = frame.getBounds();
        }
        if (main.maximized()) {
            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
        }
        ViewState view = document.viewState();
        Optional<ViewMode> mode = ViewMode.parse(main.view());
        if (mode.isPresent()) {
            view = view.withMode(mode.get());
        }
        Optional<PeriodChoice> period = parsePeriod(main.period());
        if (period.isPresent()) {
            view = view.withPeriod(period.get());
        }
        for (Map.Entry<String, Boolean> filter : main.filters().entrySet()) {
            if (ViewModels.FILTER_KEYS.contains(filter.getKey()) && filter.getValue() != null) {
                view = ViewModels.withFilter(view, filter.getKey(), filter.getValue());
            }
        }
        document.setViewState(view.withFilterText(main.filterText()));
    }

    private static Optional<PeriodChoice> parsePeriod(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (PeriodChoice choice : PeriodChoice.values()) {
            if (choice.name().equalsIgnoreCase(text.strip())) {
                return Optional.of(choice);
            }
        }
        return PeriodChoice.parse(text);
    }

    /** {@inheritDoc} */
    @Override
    public void showMainWindow() {
        showFrame();
    }

    /** {@inheritDoc} */
    @Override
    public void selectRow(String rowId) {
        selectRowId(rowId);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> existingTargetIds() {
        Set<String> ids = new LinkedHashSet<>();
        document.plan().rules().forEach(rule -> ids.add(rule.id().value()));
        document.plan().oneTimes().forEach(tx -> ids.add(tx.id().value()));
        return ids;
    }

    // ================================================================== вид

    private void changeView(UnaryOperator<ViewState> change) {
        document.setViewState(change.apply(document.viewState()));
    }

    /**
     * Выделяет строку таблицы по идентификатору; при необходимости разворачивает группу «Прошедшие события».
     *
     * @param rowId идентификатор строки
     * @return {@code true}, если строка найдена и выделена
     */
    public boolean selectRowId(String rowId) {
        if (rowId == null || rowId.isBlank()) {
            return false;
        }
        if (table.selectRowId(rowId)) {
            return true;
        }
        if (!pastExpanded && isVisiblePastRow(rowId)) {
            pastExpanded = true;
            refreshViews();
            return table.selectRowId(rowId);
        }
        return false;
    }

    private boolean isVisiblePastRow(String rowId) {
        try {
            return document.visibleRows().stream().anyMatch(r -> r.rowId().equals(rowId) && r.flags().past());
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void onDocumentEvent(DocumentEvent event) {
        if (event.has(EventKind.PLAN)) {
            // Сумма под быстрой правкой могла измениться: панель со старым значением вводила бы в заблуждение.
            closeQuickEdit();
        }
        if (event.has(EventKind.PLAN) || event.has(EventKind.VIEW)) {
            refreshViews();
        }
        if (event.has(EventKind.VIEW)) {
            ViewState view = document.viewState();
            models.sync(view);
            toolBar.setFilterTextQuietly(view.filterText());
            updateSettings(view::applyTo);
            if (view.mode() != ViewMode.TABLE) {
                closeQuickEdit();
            }
        }
        if (event.has(EventKind.PLAN)) {
            menuBar.updateUndoRedo(document);
            syncHorizon();
            if (settings().autosave()) {
                autosaveTimer.restart();
            }
        }
        updateTitle();
        touch();
    }

    private void refreshViews() {
        ViewState view = document.viewState();
        Plan plan = document.plan();
        try {
            Forecast forecast = document.forecast();
            List<ForecastRow> rows = document.visibleRows();
            table.setItems(buildItems(rows, forecast, view.monthTotals()), plan.currency(), plan.cushion());
            LocalDate from = plan.startDate();
            LocalDate to = view.periodEnd(plan, forecast.anchor());
            List<DailyPoint> points = to.isAfter(from)
                    ? ChartSeries.sample(forecast, from, to, ChartSeries.DEFAULT_MAX_POINTS) : List.of();
            chart.setData(new BalanceChartComponent.ChartData(forecast, points, from, to, rows, plan.cushion(),
                    plan.goalOptional().orElse(null), forecast.today(), view.chartMarkers(), view.chartBars(),
                    plan.currency()), null);
            summary.update(forecast, plan);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Прогноз не считается (например, горизонт длиннее 200 000 дней): показываем причину, окно работает.
            String message = "Прогноз не рассчитан: " + Objects.requireNonNullElse(e.getMessage(), e.toString());
            table.setItems(List.of(), plan.currency(), plan.cushion());
            chart.setData(null, message);
            summary.showUnavailable(message);
            statusBar.showMessage(message);
        }
        summary.setVisible(view.summaryPanel());
        ((CardLayout) center.getLayout()).show(center, view.mode() == ViewMode.CHART ? CARD_CHART : CARD_TABLE);
        if (view.mode() == ViewMode.TABLE) {
            chart.hidePopups();
        }
    }

    private List<TableItem> buildItems(List<ForecastRow> rows, Forecast forecast, boolean monthTotals) {
        int pastCount = (int) rows.stream().filter(MainFrame::isPastEvent).count();
        List<TableItem> items = new ArrayList<>();
        Map<YearMonth, MonthTotals> byMonth = forecast.summary().byMonth();
        YearMonth current = null;
        boolean headerAdded = false;
        for (ForecastRow row : rows) {
            boolean past = isPastEvent(row);
            if (past && !headerAdded) {
                items.add(TableItem.pastHeader(pastCount, pastExpanded));
                headerAdded = true;
            }
            if (past && !pastExpanded) {
                continue;
            }
            YearMonth month = YearMonth.from(row.date());
            if (monthTotals && current != null && !month.equals(current)) {
                addMonthTotal(items, current, byMonth);
            }
            current = month;
            items.add(TableItem.of(row));
        }
        if (monthTotals && current != null) {
            addMonthTotal(items, current, byMonth);
        }
        return items;
    }

    private static boolean isPastEvent(ForecastRow row) {
        // Строка начального баланса всегда видна первой, даже если план начался в прошлом.
        return row.flags().past() && row.origin() != Origin.START;
    }

    private static void addMonthTotal(List<TableItem> items, YearMonth month, Map<YearMonth, MonthTotals> byMonth) {
        MonthTotals totals = byMonth.get(month);
        if (totals != null) {
            items.add(TableItem.monthTotal(month, totals));
        }
    }

    private void syncHorizon() {
        Plan plan = document.plan();
        menuBar.horizonSlider().setValueQuietly((int) Math.min(120, plan.horizon().approximateMonths(plan.startDate())));
    }

    private void updateTitle() {
        boolean dirty = document.isDirty();
        frame.setTitle(TITLE_PREFIX + document.plan().name() + (dirty ? "*" : ""));
        statusBar.setFile(document.file().map(Path::toString).orElse("План не сохранён"), dirty);
    }

    /**
     * Заголовок главного окна.
     *
     * @return «CashPrediction — план» и «*», если есть несохранённые изменения
     */
    public String title() {
        return frame.getTitle();
    }

    private void touch() {
        if (recorder != null) {
            recorder.touch();
        }
    }

    // ================================================================== быстрая правка суммы

    private void openQuickEdit(WindowState state, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        OccurrenceKey key;
        try {
            key = new OccurrenceKey(new RuleId(state.contextValue(WindowType.CONTEXT_RULE_ID).strip()),
                    LocalDate.parse(state.contextValue(WindowType.CONTEXT_ORIGINAL_DATE).strip()));
        } catch (RuntimeException e) {
            onFailed.accept("в контексте быстрой правки нет правила или даты события");
            return;
        }
        String rowId = key.asRowId();
        Optional<ForecastRow> row;
        try {
            row = document.forecast().findRow(rowId);
        } catch (IllegalStateException e) {
            onFailed.accept("прогноз не рассчитан: " + e.getMessage());
            return;
        }
        if (row.isEmpty()) {
            onFailed.accept("события " + rowId + " нет в прогнозе");
            return;
        }
        // Панель ставится у ячейки суммы — таблица должна быть на экране.
        if (document.viewState().mode() != ViewMode.TABLE) {
            changeView(v -> v.withMode(ViewMode.TABLE));
        }
        if (!selectRowId(rowId)) {
            onFailed.accept("строка события " + rowId + " не показана в таблице (фильтр или период)");
            return;
        }
        frame.validate();
        Optional<Rectangle> cell = table.amountCell(rowId);
        if (cell.isEmpty() || !table.isShowing()) {
            onFailed.accept("таблица прогноза не показана");
            return;
        }
        closeQuickEdit();
        QuickEditPopup popup = new QuickEditPopup(table, state, row.get(), document.plan().currency(),
                amount -> actions.quickEditAmount(key, amount), this::touch, this::quickEditClosed);
        quickEdit = popup;
        Point location = new Point(cell.get().x, cell.get().y);
        SwingUtilities.convertPointToScreen(location, table);
        popup.showAt(location);
        onShown.accept(popup);
    }

    private void quickEditClosed(QuickEditPopup popup) {
        if (quickEdit == popup) {
            quickEdit = null;
        }
        if (recorder != null) {
            recorder.unregister(popup);
        }
    }

    private void closeQuickEdit() {
        if (quickEdit != null) {
            quickEdit.cancel();
        }
    }

    /** Реакции таблицы прогноза. */
    private final class TableHandler implements ForecastTable.Handler {

        /** {@inheritDoc} */
        @Override
        public void editRow(ForecastRow row) {
            actions.editRow(row.rowId());
        }

        /** {@inheritDoc} */
        @Override
        public void quickEdit(ForecastRow row) {
            row.occurrenceKey().ifPresent(actions::quickEdit);
        }

        /** {@inheritDoc} */
        @Override
        public void deleteSelected() {
            actions.deleteSelected();
        }

        /** {@inheritDoc} */
        @Override
        public void showContextMenu(ForecastRow row, java.awt.Component component, int x, int y) {
            // JavaFX: ContextMenu.show(node, screenX, screenY) → Swing: JPopupMenu.show(component, x, y) → Web: <ul class="context-menu">
            ForecastPopupMenu.forRow(actions, row).show(component, x, y);
        }

        /** {@inheritDoc} */
        @Override
        public void togglePast() {
            pastExpanded = !pastExpanded;
            refreshViews();
        }

        /** {@inheritDoc} */
        @Override
        public void selectionChanged() {
            touch();
        }
    }
}
