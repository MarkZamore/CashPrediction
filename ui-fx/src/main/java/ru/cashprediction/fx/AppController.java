package ru.cashprediction.fx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.DocumentEvent;
import ru.cashprediction.core.document.EventKind;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.RestoreCoordinator;
import ru.cashprediction.core.session.RestoreTarget;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.SnapshotSource;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;
import ru.cashprediction.fx.action.FxActions;
import ru.cashprediction.fx.action.FxAppContext;
import ru.cashprediction.fx.action.QuickEditOpener;
import ru.cashprediction.fx.dialog.FxDialogHost;
import ru.cashprediction.fx.dialog.RecoveryChoice;
import ru.cashprediction.fx.session.FxCrashHooks;
import ru.cashprediction.fx.session.FxUiExecutor;
import ru.cashprediction.fx.session.FxWindowFactory;
import ru.cashprediction.fx.view.ViewFlags;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Контроллер JavaFX-клиента: связывает документ плана, настройки, рекордер сессии, фасад команд и главное окно.
 *
 * <p><b>Запуск</b> ({@link #start()}): папка CashMemory и {@code settings.md}, хранилища снимка
 * (реестр Windows и {@code session-fx.xml}), рекордер, фасад команд, фабрика окон, главное окно (ещё скрытое),
 * затем {@link CrashDetector#detect}:</p>
 * <ul>
 *   <li>{@code CLEAN_START} — обычный запуск: последний план из настроек, иначе первый план папки, иначе мастер
 *       нового плана поверх показанного окна; затем {@code recorder.start()};</li>
 *   <li>{@code CRASHED} — диалог «Восстановление» до главного окна; выбранный снимок восстанавливает
 *       {@link RestoreCoordinator} (этот класс — его {@link RestoreTarget}), «Не восстанавливать» —
 *       {@code RestoreCoordinator.startFresh} и обычный запуск;</li>
 *   <li>{@code ALREADY_RUNNING} — вопрос «уже запущен…»: обычный запуск с отключённым рекордером или выход.</li>
 * </ul>
 *
 * <p><b>Работа</b>: каждое событие документа перерисовывает окно, сообщает рекордеру ({@code touch}),
 * при изменении вида откладывает запись настроек, а при включённом автосохранении — сохранение плана через
 * секунду после правки. Этот же класс — {@link SnapshotSource} снимка сессии.</p>
 *
 * <p><b>Выход</b> ({@link #requestExit()}): {@code recorder.saveNow()} → вопрос о несохранённых изменениях →
 * запись настроек → {@code recorder.shutdownClean()} (маркер «закрыто») → {@code Platform.exit()}.</p>
 *
 * <p>Только FX Application Thread (рекордер сам переводит свои вызовы в этот поток через {@link FxUiExecutor}).</p>
 */
public final class AppController implements FxAppContext, ShellContext, SnapshotSource, RestoreTarget {

    /** Идентификатор клиента в хранилищах снимка. */
    public static final String CLIENT = SnapshotSchema.CLIENT_FX;
    /** Имя пустого плана, если открыть нечего. */
    public static final String DEFAULT_PLAN_NAME = "Мой план";
    /** Задержка записи настроек после изменения: серия щелчков по флажкам вида даёт одну запись. */
    private static final Duration SETTINGS_DELAY = Duration.millis(700);
    /** Задержка автосохранения плана после правки. */
    private static final Duration AUTOSAVE_DELAY = Duration.seconds(1);

    private final Stage stage;
    private final FxSelfTest selfTest;
    private final PauseTransition settingsTimer = new PauseTransition(SETTINGS_DELAY);
    private final PauseTransition autosaveTimer = new PauseTransition(AUTOSAVE_DELAY);
    private final Map<String, StoreStatus> storeStatuses = new LinkedHashMap<>();
    private final List<String> restoreWarnings = new ArrayList<>();

    private CashMemoryLayout layout;
    private AppSettings settings = AppSettings.defaults();
    private PlanDocument document;
    private List<SessionStore> stores;
    private SessionRecorder recorder;
    private FxActions actions;
    private FxWindowFactory factory;
    private MainWindow window;
    private boolean exitAsked;
    private boolean exiting;
    private boolean startupFinished;

    /**
     * Создаёт контроллер.
     *
     * @param stage    главная сцена JavaFX (из {@code Application.start})
     * @param selfTest самотест или {@code null} в обычном режиме
     */
    public AppController(Stage stage, FxSelfTest selfTest) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.selfTest = selfTest;
    }

    // ================================================================== запуск

    /**
     * Запускает приложение: создаёт все части и выбирает сценарий по результату обнаружения сбоя.
     *
     * @throws IOException если папку CashMemory создать не удалось
     */
    public void start() throws IOException {
        layout = CashMemoryLayout.openDefault();
        settings = SettingsMarkdown.load(layout.settingsFile());
        document = new PlanDocument(Plan.empty(DEFAULT_PLAN_NAME, today()), null, this::today);
        document.setViewState(ViewState.fromSettings(settings));

        // Хранилища только своего клиента: одновременно запущенный Swing-клиент пишет в свои узел и файл.
        stores = List.of(RegistrySessionStore.forClient(CLIENT), XmlSessionStore.inCashMemory(layout.dir(), CLIENT));
        recorder = SessionRecorder.create(CLIENT, stores, new FxUiExecutor(), this);
        // Слушатель вызывается из фонового потока записи — в UI переходим через runLater.
        recorder.addStatusListener(status -> Platform.runLater(() -> onStoreStatus(status)));
        FxCrashHooks.attach(recorder, selfTest != null);

        FxDialogHost host = new FxDialogHost(() -> stage, () -> recorder);
        actions = new FxActions(this, host);
        factory = new FxWindowFactory(actions, this);
        window = new MainWindow(stage, this);
        window.syncSettings(settings);
        document.addListener(this::onDocumentEvent);
        settingsTimer.setOnFinished(e -> writeSettings());
        autosaveTimer.setOnFinished(e -> {
            if (settings.autosave() && !exiting) {
                actions.autosave();
            }
        });
        // Крестик окна и Alt+F4 идут тем же путём, что и «Файл → Выход».
        stage.setOnCloseRequest(e -> {
            e.consume();
            requestExit();
        });
        window.refreshAll();

        CrashDetector.Detection detection = CrashDetector.detect(stores, CLIENT);
        switch (detection.status()) {
            case CLEAN_START -> normalStart();
            case CRASHED -> {
                actions.recovery(detection, this::onRecoveryChoice);
                if (selfTest != null) {
                    selfTest.answerRecovery();
                }
            }
            case ALREADY_RUNNING -> {
                actions.alreadyRunning(open -> {
                    if (open) {
                        normalStart();
                    } else {
                        // Второй экземпляр уходит, ничего не записав: снимки и маркер принадлежат первому.
                        Platform.exit();
                    }
                });
                if (selfTest != null) {
                    selfTest.answerAlreadyRunning();
                }
            }
        }
    }

    private void onRecoveryChoice(RecoveryChoice choice) {
        if (choice == RecoveryChoice.NONE) {
            RestoreCoordinator.startFresh(stores);
            normalStart();
            return;
        }
        Optional<SessionSnapshot> snapshot;
        try {
            snapshot = actions.loadSnapshot(choice);
        } catch (SessionStoreException e) {
            actions.showError("Не удалось прочитать снимок сеанса", e.getMessage());
            normalStart();
            return;
        }
        if (snapshot.isEmpty()) {
            actions.showError("Снимок сеанса не найден", "В выбранном хранилище нет снимка. Программа запущена как обычно.");
            normalStart();
            return;
        }
        SessionSnapshot chosen = snapshot.get();
        new RestoreCoordinator().restore(chosen, this, factory, recorder, report -> {
            restoreWarnings.addAll(report.warnings());
            actions.restoreFinished(chosen, report, null);
            refreshStoreStatuses();
            afterStartup();
        });
    }

    private void normalStart() {
        boolean opened = openInitialPlan();
        window.show();
        recorder.start();
        recorder.touch();
        refreshStoreStatuses();
        if (!opened) {
            // Планов нет: мастер нового плана поверх окна; отмена оставит пустой несохранённый «Мой план».
            actions.startupWizard();
        }
        afterStartup();
    }

    private boolean openInitialPlan() {
        String last = settings.lastPlan();
        if (!last.isBlank()) {
            Path file = layout.dir().resolve(last);
            if (Files.isRegularFile(file) && actions.loadPlan(file)) {
                return true;
            }
        }
        try {
            List<PlanFileInfo> plans = layout.plans().list();
            if (!plans.isEmpty()) {
                return actions.loadPlan(plans.getFirst().path());
            }
        } catch (UncheckedIOException e) {
            actions.showError("Не удалось прочитать папку CashMemory", e.getMessage());
        }
        return false;
    }

    private void afterStartup() {
        if (startupFinished) {
            return;
        }
        startupFinished = true;
        if (selfTest != null) {
            selfTest.start(this);
        }
    }

    // ================================================================== события документа и настройки

    private void onDocumentEvent(DocumentEvent event) {
        window.refreshAll();
        if (event.has(EventKind.VIEW)) {
            AppSettings next = document.viewState().applyTo(settings);
            if (!next.equals(settings)) {
                settings = next;
                settingsTimer.playFromStart();
            }
        }
        if (event.has(EventKind.PLAN) && settings.autosave() && document.isDirty()) {
            autosaveTimer.playFromStart();
        }
        recorder.touch();
    }

    private void onStoreStatus(StoreStatus status) {
        storeStatuses.put(status.storeId(), status);
        refreshStoreStatuses();
    }

    private void refreshStoreStatuses() {
        window.status().setStores(storeStatuses.values(), recorder.isEnabled());
    }

    private void writeSettings() {
        settingsTimer.stop();
        try {
            SettingsMarkdown.save(layout.settingsFile(), settings);
            window.status().showMessage("");
        } catch (IOException e) {
            // Настройки — не данные пользователя: сообщение в строке состояния вместо модального окна.
            window.status().showMessage("Настройки не сохранены: " + e.getMessage());
        }
    }

    // ================================================================== выход

    /** {@inheritDoc} */
    @Override
    public void requestExit() {
        if (exiting || exitAsked) {
            return;
        }
        exitAsked = true;
        actions.confirmExit(ok -> {
            exitAsked = false;
            if (ok) {
                finishExit();
            }
        });
    }

    /**
     * Вторая половина выхода без вопросов: настройки, маркер «закрыто», завершение JavaFX.
     * Вызывается после подтверждения и командой самотеста {@code exit}.
     */
    public void finishExit() {
        if (exiting) {
            return;
        }
        exiting = true;
        autosaveTimer.stop();
        writeSettings();
        recorder.shutdownClean();
        Platform.exit();
    }

    // ================================================================== FxAppContext

    /** {@inheritDoc} */
    @Override
    public Stage owner() {
        return stage;
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
        return settings;
    }

    /** {@inheritDoc} Запись в {@code settings.md} — через 0,7 с после последнего изменения. */
    @Override
    public void updateSettings(UnaryOperator<AppSettings> change) {
        AppSettings next = change.apply(settings);
        if (next == null || next.equals(settings)) {
            return;
        }
        settings = next;
        window.syncSettings(settings);
        settingsTimer.playFromStart();
        if (!settings.autosave()) {
            autosaveTimer.stop();
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
        window.table().clearSelection();
        document.replace(plan, file, dirty, diagnostics);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> selectedRowId() {
        return window.table().selectedRowId();
    }

    /** {@inheritDoc} */
    @Override
    public QuickEditOpener quickEdit() {
        return (state, onShown, onFailed) -> window.table().openQuickEdit(state, onShown, onFailed);
    }

    // ================================================================== ShellContext

    /** {@inheritDoc} */
    @Override
    public FxActions actions() {
        return actions;
    }

    /** {@inheritDoc} */
    @Override
    public void updateView(UnaryOperator<ViewState> change) {
        document.setViewState(change.apply(document.viewState()));
    }

    /** {@inheritDoc} */
    @Override
    public Window ownerWindow() {
        return stage;
    }

    /** {@inheritDoc} */
    @Override
    public void focusFilter() {
        window.focusFilter();
    }

    /** {@inheritDoc} */
    @Override
    public Node chartNode() {
        return window.chart().chartNode();
    }

    /** {@inheritDoc} */
    @Override
    public void showTableFrom(LocalDate date) {
        if (date == null) {
            return;
        }
        updateView(v -> v.withMode(ViewMode.TABLE));
        if (window.table().selectFirstFrom(date)) {
            window.table().requestFocus();
        }
    }

    // ================================================================== SnapshotSource

    /** {@inheritDoc} */
    @Override
    public MainWindowState captureMain() {
        ViewState view = document.viewState();
        return new MainWindowState(window.captureBounds(), stage.isMaximized(), view.mode().name(), planPath(),
                view.period().name(), ViewFlags.toMap(view), view.filterText(), selectedRowId().orElse(""));
    }

    /** {@inheritDoc} При несохранённых изменениях — полный текст плана в формате .md. */
    @Override
    public PlanState capturePlan() {
        return document.isDirty() ? PlanState.dirty(PlanMarkdownWriter.write(document.plan())) : PlanState.CLEAN;
    }

    private String planPath() {
        return document.file().map(file -> {
            Path absolute = file.toAbsolutePath().normalize();
            // План из CashMemory хранится относительным именем: папку с программой можно переносить.
            return layout.dir().equals(absolute.getParent()) ? absolute.getFileName().toString() : absolute.toString();
        }).orElse("");
    }

    // ================================================================== RestoreTarget

    /** {@inheritDoc} */
    @Override
    public void loadPlan(PlanState plan, String planPath, Consumer<String> warn) {
        Path file = planPath == null || planPath.isBlank() ? null : layout.dir().resolve(planPath);
        if (plan.dirty()) {
            String fallback = file != null ? PlanMarkdownReader.nameWithoutExtension(file) : DEFAULT_PLAN_NAME;
            // Исключение разбора уходит координатору: несохранённый план не открыт, запись сеанса не начнётся.
            ReadResult result = PlanMarkdownReader.read(plan.markdown(), fallback, today());
            openPlanDocument(result.plan(), file, true, result.diagnostics());
            actions.trackFile(file);
            if (result.hasWarnings()) {
                warn.accept("Несохранённый план из снимка прочитан с замечаниями: Инструменты → Проверить план");
            }
            return;
        }
        if (file != null) {
            if (!actions.loadPlanForRestore(file, warn)) {
                openPlanDocument(Plan.empty(DEFAULT_PLAN_NAME, today()), null, false, List.of());
                warn.accept("Открыт пустой план «" + DEFAULT_PLAN_NAME + "»");
            }
        } else {
            openPlanDocument(Plan.empty(DEFAULT_PLAN_NAME, today()), null, false, List.of());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void applyMain(MainWindowState main) {
        ViewState view = document.viewState();
        view = view.withMode(parseEnum(ViewMode.class, main.view(), view.mode()));
        view = view.withPeriod(parseEnum(PeriodChoice.class, main.period(), view.period()));
        view = ViewFlags.apply(view, main.filters()).withFilterText(main.filterText());
        document.setViewState(view);
        window.applyBounds(main.bounds(), main.maximized());
    }

    /** {@inheritDoc} */
    @Override
    public void showMainWindow() {
        window.show();
    }

    /** {@inheritDoc} */
    @Override
    public void selectRow(String rowId) {
        if (window.table().select(rowId)) {
            window.table().requestFocus();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> existingTargetIds() {
        Set<String> ids = new LinkedHashSet<>();
        Plan plan = document.plan();
        for (RecurringRule rule : plan.rules()) {
            ids.add(rule.id().value());
        }
        for (OneTimeTransaction tx : plan.oneTimes()) {
            ids.add(tx.id().value());
        }
        return ids;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        try {
            return name == null || name.isBlank() ? fallback : Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            // Снимок от другой версии: оставляем текущее значение вида.
            return fallback;
        }
    }

    // ================================================================== для самотеста

    /** @return фабрика окон (команда самотеста {@code open}) */
    FxWindowFactory factory() {
        return factory;
    }

    /** @return главное окно */
    MainWindow window() {
        return window;
    }

    /** @return предупреждения последнего восстановления сессии */
    List<String> restoreWarnings() {
        return Collections.unmodifiableList(restoreWarnings);
    }

    /** @return последние состояния хранилищ снимка */
    List<StoreStatus> storeStatuses() {
        return List.copyOf(storeStatuses.values());
    }
}
