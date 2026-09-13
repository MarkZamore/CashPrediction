package ru.cashprediction.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.DocumentEvent;
import ru.cashprediction.core.document.EventKind;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.MarkdownParseException;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.RestoreCoordinator;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.RestoreTarget;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.SnapshotSource;
import ru.cashprediction.core.session.StoreStatus;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowFactory;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.store.MarkdownSessionStore;

/**
 * Единственный серверный сеанс web-клиента (разделы 5.5 и 6.8 плана): открытый план, настройки, открытые в браузере
 * окна, состояние главного окна и запись снимка сессии в {@code CashMemory/web-session.md}.
 *
 * <p><b>Состояние хранится на сервере.</b> Браузер — тонкий клиент: каждое изменение (правка плана, ввод в поле
 * диалога, смена вида) сразу отправляется сюда. Поэтому перезагрузка страницы или повторное открытие вкладки
 * возвращает то же самое состояние, включая открытые диалоги с введёнными значениями, а после аварийного
 * завершения сервера снимок из {@code web-session.md} позволяет восстановить их при следующем запуске.</p>
 *
 * <p><b>Жизненный цикл.</b> {@link #open} загружает настройки и стартовый план (последний, первый из CashMemory
 * или пустой «Мой план» с предложением мастера), затем проверяет {@link CrashDetector}:</p>
 * <ul>
 *   <li>{@code CLEAN_START} — запись сессии начинается сразу;</li>
 *   <li>{@code CRASHED} — снимок откладывается как «ожидающий восстановления», запись не начинается, пока пользователь
 *       не выберет в баннере «Восстановить с сервера» ({@link #restore()}) или «Начать заново» ({@link #startFresh()});</li>
 *   <li>{@code ALREADY_RUNNING} — с этой же папкой работает другой живой сервер: запись отключается, чтобы не затереть его снимок.</li>
 * </ul>
 * <p>Корректная остановка — {@link #shutdownClean()}: снимок, настройки, маркер {@code closed}.</p>
 *
 * <p><b>Потоки.</b> Все обращения к документу, окнам и настройкам идут под одним монитором {@link #lock}. Он же служит
 * «UI-потоком» рекордера ({@link ServerUiExecutor}). {@link PlanDocument} не потокобезопасен — монитор это обеспечивает.
 * Методы этого класса сами берут монитор; вызывать их можно из любого потока.</p>
 */
public final class ServerState implements SnapshotSource, RestoreTarget {

    /** Задержка записи {@code settings.md} после последнего изменения настроек. */
    public static final long SETTINGS_DEBOUNCE_MS = 500;

    /** Задержка автосохранения плана после последней правки. */
    public static final long AUTOSAVE_DELAY_MS = 1000;

    /** Монитор всего состояния и «UI-поток» рекордера. */
    final Object lock = new Object();

    private final CashMemoryLayout layout;
    private final PlanRepository repository;
    private final Clock clock;
    private final ServerLog log;
    private final MarkdownSessionStore store;
    private final List<SessionStore> stores;
    private final SessionRecorder recorder;
    private final PlanDocument document;
    private final ScheduledExecutorService tasks;
    private final Map<String, StoreStatus> statuses = new ConcurrentHashMap<>();

    /** Окна, открытые в браузере, в порядке открытия. */
    private final Map<String, WebWindow> windows = new LinkedHashMap<>();
    /** Сообщения для пользователя, накопленные при запуске (не открылся последний план и т. п.). */
    private final List<String> notices = new ArrayList<>();

    private AppSettings settings;
    private MainWindowState main = MainWindowState.empty();
    /** Время изменения файла плана при загрузке/сохранении: для обнаружения правки снаружи. */
    private FileTime fileStamp;
    private CrashDetector.Detection detection;
    private SessionSnapshot pendingRestore;
    private String pendingProblem = "";
    private RestoreReport lastRestoreReport;
    /** Текст несохранённого плана из снимка, который не удалось открыть при восстановлении. */
    private String unrestoredPlanMarkdown;
    private boolean alreadyRunning;
    private boolean openWizard;
    /** Папка, выбранная аналогом DirectoryChooser на время сеанса (планы только читаются). */
    private Path extraFolder;
    private boolean closed;
    private ScheduledFuture<?> settingsSave;
    private ScheduledFuture<?> autosave;
    private Runnable autosaveAction = () -> { };
    private String autosaveProblem = "";

    /**
     * Создаёт состояние; запуск — {@link #start()}.
     *
     * @param layout папка CashMemory
     * @param clock  часы («сегодня» — по часовому поясу часов)
     * @param log    журнал сервера
     */
    ServerState(CashMemoryLayout layout, Clock clock, ServerLog log) {
        this.layout = layout;
        this.repository = layout.plans();
        this.clock = clock;
        this.log = log;
        this.store = MarkdownSessionStore.inCashMemory(layout.dir());
        this.stores = List.of(store);
        this.recorder = SessionRecorder.create(SnapshotSchema.CLIENT_WEB, stores, new ServerUiExecutor(lock), this);
        this.recorder.addStatusListener(this::onStoreStatus);
        this.document = new PlanDocument(Plan.empty(PlanForms.DEFAULT_PLAN_NAME, LocalDate.now(clock)), null, this::today);
        this.document.addListener(this::onDocumentEvent);
        this.tasks = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cashprediction-web-tasks");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Открывает серверный сеанс над папкой CashMemory: настройки, стартовый план, обнаружение сбоя.
     *
     * @param layout папка CashMemory
     * @param log    журнал сервера
     * @return запущенное состояние
     */
    public static ServerState open(CashMemoryLayout layout, ServerLog log) {
        ServerState state = new ServerState(layout, Clock.systemDefaultZone(), log);
        state.start();
        return state;
    }

    /** Загружает настройки и стартовый план, проверяет, как завершился прошлый сеанс сервера. */
    void start() {
        synchronized (lock) {
            settings = SettingsMarkdown.load(layout.settingsFile());
            document.setViewState(ViewState.fromSettings(settings));
            loadStartupPlan();
            detection = CrashDetector.detect(stores, SnapshotSchema.CLIENT_WEB);
            switch (detection.status()) {
                case CLEAN_START -> {
                    recorder.start();
                    log.info("Предыдущий сеанс завершён корректно, запись сессии начата");
                }
                case ALREADY_RUNNING -> {
                    alreadyRunning = true;
                    recorder.setEnabled(false);
                    String pid = detection.findMarker().map(m -> Long.toString(m.pid())).orElse("?");
                    notices.add("С папкой CashMemory уже работает другой сервер CashPrediction (процесс " + pid
                            + "). Этот сервер открыт без восстановления и без записи сессии.");
                    log.info("Обнаружен работающий сервер (pid " + pid + "): запись сессии отключена");
                }
                case CRASHED -> preparePendingRestore();
            }
        }
    }

    // ================================================================== доступ для команд и API

    /** @return папка CashMemory */
    public CashMemoryLayout layout() {
        return layout;
    }

    /** @return репозиторий планов CashMemory */
    PlanRepository repository() {
        return repository;
    }

    /** @return журнал сервера */
    public ServerLog log() {
        return log;
    }

    /** @return сегодняшняя дата по часам сервера */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** @return рекордер сессии (для тестов и окна статуса) */
    public SessionRecorder recorder() {
        return recorder;
    }

    /** @return хранилище снимка {@code web-session.md} */
    public MarkdownSessionStore store() {
        return store;
    }

    /**
     * Выполняет действие под монитором состояния.
     *
     * @param action действие
     * @param <T>    тип результата
     * @return результат действия
     */
    public <T> T locked(Supplier<T> action) {
        synchronized (lock) {
            return action.get();
        }
    }

    /** @return документ плана; вызывать только под {@link #lock} */
    PlanDocument document() {
        return document;
    }

    /** @return текущие настройки; вызывать только под {@link #lock} */
    AppSettings settings() {
        return settings;
    }

    /**
     * Меняет настройки и откладывает запись {@code settings.md} (debounce {@value #SETTINGS_DEBOUNCE_MS} мс).
     *
     * @param change изменение
     */
    public void updateSettings(UnaryOperator<AppSettings> change) {
        synchronized (lock) {
            AppSettings next = change.apply(settings);
            if (next.equals(settings)) {
                return;
            }
            settings = next;
            if (!closed) {
                if (settingsSave != null) {
                    settingsSave.cancel(false);
                }
                settingsSave = tasks.schedule(this::flushSettings, SETTINGS_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Записывает настройки немедленно (при выходе и из отложенной задачи). Ошибка записи попадает в журнал.
     */
    public void flushSettings() {
        synchronized (lock) {
            try {
                SettingsMarkdown.save(layout.settingsFile(), settings);
            } catch (IOException e) {
                log.error("Не удалось записать settings.md", e);
            }
        }
    }

    /**
     * Заменяет план документа (открытие, новый план, пример, импорт, восстановление) и отмечает это в настройках.
     *
     * @param plan        план
     * @param file        файл плана или {@code null}
     * @param dirty       есть ли несохранённые изменения
     * @param diagnostics диагностика чтения или {@code null}
     */
    void replaceDocument(Plan plan, Path file, boolean dirty, List<Diagnostic> diagnostics) {
        synchronized (lock) {
            document.replace(plan, file, dirty, diagnostics);
            rememberFileStamp(file);
            if (file != null) {
                String name = settingsName(file);
                updateSettings(s -> s.withPlanOpened(name));
            }
            openWizard = false;
            autosaveProblem = "";
        }
    }

    /**
     * Запоминает время изменения файла плана (после загрузки или сохранения).
     *
     * @param file файл или {@code null}
     */
    void rememberFileStamp(Path file) {
        synchronized (lock) {
            try {
                fileStamp = file != null && Files.isRegularFile(file) ? repository.lastModified(file) : null;
            } catch (IOException e) {
                fileStamp = null;
            }
        }
    }

    /** @return время изменения файла при загрузке или последнем сохранении; {@code null} — неизвестно */
    FileTime fileStamp() {
        return fileStamp;
    }

    /**
     * Имя плана для настроек и снимка: имя файла, если план лежит в корне CashMemory, иначе полный путь.
     *
     * @param file файл плана
     * @return {@code "Семейный бюджет.md"} или {@code "D:\\Планы\\x.md"}
     */
    public String settingsName(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return layout.dir().equals(normalized.getParent()) ? normalized.getFileName().toString() : normalized.toString();
    }

    /**
     * Путь к плану по имени из настроек или снимка.
     *
     * @param nameOrPath имя файла в CashMemory или полный путь
     * @return абсолютный путь
     * @throws IllegalArgumentException если строка не является допустимым путём
     */
    public Path resolvePlanPath(String nameOrPath) {
        Path path = Path.of(nameOrPath.strip());
        return (path.isAbsolute() ? path : layout.dir().resolve(path)).toAbsolutePath().normalize();
    }

    /** @return папка, выбранная для чтения планов на время сеанса, или {@code null} */
    Path extraFolder() {
        return extraFolder;
    }

    /**
     * Выбирает дополнительную папку с планами на время сеанса (web-аналог DirectoryChooser).
     *
     * @param folder папка или {@code null} — сбросить
     */
    void setExtraFolder(Path folder) {
        synchronized (lock) {
            extraFolder = folder;
        }
    }

    /**
     * Задаёт действие автосохранения (его выполняет {@link PlanFileCommands}, знающий правила сохранения).
     *
     * @param action действие; выполняется в фоновом потоке и само берёт монитор
     */
    void setAutosaveAction(Runnable action) {
        synchronized (lock) {
            autosaveAction = action;
        }
    }

    /**
     * Сообщает о проблеме автосохранения (показывается в строке состояния браузера).
     *
     * @param problem текст проблемы; пустая строка — проблем нет
     */
    void setAutosaveProblem(String problem) {
        synchronized (lock) {
            autosaveProblem = problem == null ? "" : problem;
        }
    }

    /** @return текст последней проблемы автосохранения или пустая строка */
    String autosaveProblem() {
        return autosaveProblem;
    }

    /** @return сообщения для пользователя (копия) */
    List<String> notices() {
        synchronized (lock) {
            return List.copyOf(notices);
        }
    }

    /** Удаляет показанные пользователю сообщения. */
    void clearNotices() {
        synchronized (lock) {
            notices.clear();
        }
    }

    /** @return нужно ли браузеру предложить мастер «Новый план» (в CashMemory нет ни одного плана) */
    boolean openWizard() {
        return openWizard;
    }

    /** @return работает ли с этой папкой другой сервер (запись сессии отключена) */
    boolean alreadyRunning() {
        return alreadyRunning;
    }

    /** @return остановлен ли сеанс */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    // ================================================================== окна браузера

    /**
     * Регистрирует окно, открытое в браузере.
     *
     * @param typeName имя типа из словаря {@link WindowType}
     * @param modal    модальность; {@code null} — по умолчанию для типа
     * @param ownerId  владелец: {@code main} или идентификатор открытого окна
     * @param context  контекст окна
     * @param fields   начальные значения полей
     * @param bounds   границы или {@code null}
     * @return зарегистрированное окно
     * @throws IllegalArgumentException если тип неизвестен
     */
    public WebWindow openWindow(String typeName, Boolean modal, String ownerId, Map<String, String> context,
                                Map<String, String> fields, WindowBounds bounds) {
        WindowType type = WindowType.fromName(typeName)
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный тип окна «" + typeName + "»"));
        synchronized (lock) {
            String owner = ownerId == null || ownerId.isBlank() || !windows.containsKey(ownerId) ? WindowState.MAIN_OWNER : ownerId;
            WebWindow window = new WebWindow(new WindowState(recorder.nextWindowId(), type,
                    modal == null ? type.defaultModal() : modal, owner, bounds, context, fields));
            windows.put(window.windowId(), window);
            if (type == WindowType.NEW_PLAN_WIZARD) {
                // Мастер открыт — предлагать его при следующей загрузке страницы уже не нужно.
                openWizard = false;
            }
            recorder.register(window);
            return window;
        }
    }

    /**
     * Сливает изменения окна, пришедшие из браузера.
     *
     * @param id      идентификатор окна
     * @param fields  изменённые поля или {@code null}
     * @param context изменённый контекст или {@code null}
     * @param bounds  новые границы или {@code null}
     * @return окно
     * @throws NoSuchElementException если окна нет
     */
    public WebWindow updateWindow(String id, Map<String, String> fields, Map<String, String> context, WindowBounds bounds) {
        synchronized (lock) {
            WebWindow window = requireWindow(id);
            window.merge(fields, context, bounds);
            recorder.touch();
            return window;
        }
    }

    /**
     * Закрывает окно и все окна, которыми оно владеет (вложенные диалоги без владельца не имеют смысла).
     *
     * @param id идентификатор окна
     * @return идентификаторы закрытых окон
     * @throws NoSuchElementException если окна нет
     */
    public List<String> closeWindow(String id) {
        synchronized (lock) {
            requireWindow(id);
            List<String> closedIds = new ArrayList<>();
            collectWithChildren(id, closedIds);
            for (String closedId : closedIds) {
                WebWindow removed = windows.remove(closedId);
                if (removed != null) {
                    recorder.unregister(removed);
                }
            }
            return closedIds;
        }
    }

    /** @return открытые окна в порядке открытия (копия) */
    public List<WebWindow> windows() {
        synchronized (lock) {
            return List.copyOf(windows.values());
        }
    }

    /**
     * Обновляет состояние главного окна, присланное браузером.
     *
     * @param bounds        границы окна браузера или {@code null} — не менять
     * @param maximized     развёрнуто ли окно или {@code null} — не менять
     * @param selectedRowId выделенная строка или {@code null} — не менять
     */
    public void updateMain(WindowBounds bounds, Boolean maximized, String selectedRowId) {
        synchronized (lock) {
            main = new MainWindowState(bounds != null ? bounds : main.bounds(),
                    maximized != null ? maximized : main.maximized(), main.view(), main.planPath(), main.period(),
                    main.filters(), main.filterText(), selectedRowId != null ? selectedRowId : main.selectedRowId());
            recorder.touch();
        }
    }

    /** @return состояние главного окна, присланное браузером (границы, развёрнутость, выделение) */
    MainWindowState main() {
        return main;
    }

    /**
     * Сообщает рекордеру об изменении (например, вкладка закрыта обычным образом).
     */
    public void touch() {
        recorder.touch();
    }

    private WebWindow requireWindow(String id) {
        WebWindow window = windows.get(id);
        if (window == null) {
            throw new NoSuchElementException("Окно «" + id + "» не открыто");
        }
        return window;
    }

    private void collectWithChildren(String id, List<String> out) {
        out.add(id);
        for (WebWindow window : List.copyOf(windows.values())) {
            if (id.equals(window.ownerId()) && !out.contains(window.windowId())) {
                collectWithChildren(window.windowId(), out);
            }
        }
    }

    // ================================================================== сеанс и восстановление

    /** @return результат обнаружения сбоя при запуске */
    CrashDetector.Detection detection() {
        return detection;
    }

    /** @return снимок, ожидающий решения пользователя, или пусто */
    public Optional<SessionSnapshot> pendingRestore() {
        synchronized (lock) {
            return Optional.ofNullable(pendingRestore);
        }
    }

    /** @return почему восстановиться нельзя (снимок повреждён и т. п.); пустая строка — можно или нечего */
    String pendingProblem() {
        return pendingProblem;
    }

    /** @return ждёт ли сеанс решения «Восстановить / Начать заново» */
    public boolean isRestorePending() {
        synchronized (lock) {
            return pendingRestore != null || !pendingProblem.isEmpty();
        }
    }

    /** @return отчёт последнего восстановления или {@code null} */
    RestoreReport lastRestoreReport() {
        return lastRestoreReport;
    }

    /** @return текст несохранённого плана из снимка, который не открылся, или {@code null} */
    String unrestoredPlanMarkdown() {
        return unrestoredPlanMarkdown;
    }

    /**
     * «Восстановить с сервера»: план, вид и окна из снимка аварийно завершённого сеанса.
     *
     * @return отчёт восстановления
     * @throws ConflictException если восстанавливать нечего или снимок повреждён
     */
    public RestoreReport restore() {
        synchronized (lock) {
            if (pendingRestore == null) {
                throw new ConflictException(ConflictException.STATE, pendingProblem.isEmpty()
                        ? "Нет сеанса для восстановления" : "Восстановить нельзя: " + pendingProblem);
            }
            SessionSnapshot snapshot = pendingRestore;
            // Окна, открытые до решения пользователя, заменяются окнами из снимка.
            for (WebWindow window : List.copyOf(windows.values())) {
                recorder.unregister(window);
            }
            windows.clear();
            AtomicReference<RestoreReport> report = new AtomicReference<>();
            // Фабрика и цель вызываются синхронно в этом же потоке под монитором, поэтому отчёт готов сразу.
            new RestoreCoordinator().restore(snapshot, this, windowFactory(), recorder, report::set);
            pendingRestore = null;
            pendingProblem = "";
            openWizard = false;
            lastRestoreReport = report.get();
            if (!recorder.isStarted()) {
                unrestoredPlanMarkdown = snapshot.plan().markdown();
            }
            log.info("Сеанс восстановлен: окон " + lastRestoreReport.windowsRestored() + ", замечаний "
                    + lastRestoreReport.warnings().size());
            lastRestoreReport.warnings().forEach(w -> log.info("  • " + w));
            return lastRestoreReport;
        }
    }

    /**
     * «Начать заново»: удаляет снимок сбоя и начинает запись нового сеанса. Если решение уже принято, ничего не делает.
     */
    public void startFresh() {
        synchronized (lock) {
            if (pendingRestore == null && pendingProblem.isEmpty()) {
                return;
            }
            RestoreCoordinator.startFresh(stores);
            pendingRestore = null;
            pendingProblem = "";
            recorder.start();
            recorder.touch();
            log.info("Снимок прошлого сеанса отброшен, запись сессии начата");
        }
    }

    /**
     * Начинает запись после восстановления, в котором несохранённый план не открылся
     * ({@code RestoreCoordinator.RECORDER_NOT_STARTED}): пользователь уже скачал текст плана или отказался.
     */
    public void startRecording() {
        synchronized (lock) {
            if (alreadyRunning || closed) {
                return;
            }
            recorder.start();
            recorder.touch();
            unrestoredPlanMarkdown = null;
        }
    }

    /**
     * «Сделать снимок сейчас».
     */
    public void saveNow() {
        synchronized (lock) {
            recorder.saveNow();
        }
    }

    /**
     * «Очистить снимки»: через рекордер, чтобы маркер текущего сеанса остался (никогда не {@code store.clear()}).
     */
    public void clearSnapshots() {
        synchronized (lock) {
            recorder.clearSnapshots();
            log.info("Снимки сессии очищены");
        }
    }

    /**
     * Текст последнего снимка для «Показать последний снимок…».
     *
     * @return текст {@code web-session.md} или пусто, если файла нет
     * @throws IOException если файл не читается
     */
    public Optional<String> lastSnapshotText() throws IOException {
        Path file = store.sessionFile();
        return Files.isRegularFile(file) ? Optional.of(AtomicFiles.readString(file)) : Optional.empty();
    }

    /** @return последние статусы хранилищ снимка */
    List<StoreStatus> storeStatuses() {
        return List.copyOf(statuses.values());
    }

    /**
     * Корректная остановка сервера: снимок, настройки, маркер {@code closed}. Повторный вызов ничего не делает.
     */
    public void shutdownClean() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            cancelTasks();
            recorder.saveNow();
            flushSettings();
            recorder.shutdownClean();
            tasks.shutdownNow();
            log.info("Сеанс сервера корректно завершён");
        }
    }

    /**
     * Имитация «убитого» процесса для тестов: останавливает таймеры, ничего не записывая (маркер остаётся {@code running}).
     */
    void abandon() {
        synchronized (lock) {
            closed = true;
            cancelTasks();
            tasks.shutdownNow();
            recorder.setEnabled(false);
            recorder.shutdownClean();
        }
    }

    // ================================================================== SnapshotSource

    @Override
    public MainWindowState captureMain() {
        synchronized (lock) {
            ViewState view = document.viewState();
            Map<String, Boolean> filters = new LinkedHashMap<>();
            filters.put("showIncome", view.showIncome());
            filters.put("showExpense", view.showExpense());
            filters.put("showOneTime", view.showOneTime());
            filters.put("showSkipped", view.showSkipped());
            filters.put("monthTotals", view.monthTotals());
            filters.put("chartMarkers", view.chartMarkers());
            filters.put("chartBars", view.chartBars());
            filters.put("summaryPanel", view.summaryPanel());
            String planPath = document.file().map(this::settingsName).orElse("");
            return new MainWindowState(main.bounds(), main.maximized(), view.mode().name(), planPath, view.period().name(),
                    filters, view.filterText(), main.selectedRowId());
        }
    }

    @Override
    public PlanState capturePlan() {
        synchronized (lock) {
            return document.isDirty() ? PlanState.dirty(PlanMarkdownWriter.write(document.plan())) : PlanState.CLEAN;
        }
    }

    // ================================================================== RestoreTarget

    @Override
    public void loadPlan(PlanState plan, String planPath, Consumer<String> warn) {
        synchronized (lock) {
            Path file = planPath == null || planPath.isBlank() ? null : resolvePlanPath(planPath);
            if (plan.dirty()) {
                String fallback = file == null ? PlanForms.DEFAULT_PLAN_NAME : PlanMarkdownReader.nameWithoutExtension(file);
                // Исключение разбора пробрасывается: координатор не начнёт запись и не затрёт единственную копию текста.
                ReadResult result = PlanMarkdownReader.read(plan.markdown(), fallback, today());
                replaceDocument(result.plan(), file, true, result.diagnostics());
                return;
            }
            if (file == null) {
                replaceDocument(Plan.empty(PlanForms.DEFAULT_PLAN_NAME, today()), null, false, null);
                return;
            }
            if (!Files.isRegularFile(file)) {
                warn.accept("Файл плана «" + planPath + "» не найден: открыт пустой план");
                replaceDocument(Plan.empty(PlanForms.DEFAULT_PLAN_NAME, today()), null, false, null);
                return;
            }
            try {
                ReadResult result = repository.load(file, today());
                replaceDocument(result.plan(), file, false, result.diagnostics());
            } catch (IOException | MarkdownParseException e) {
                warn.accept("Файл плана «" + planPath + "» не открылся (" + e.getMessage() + "): открыт пустой план");
                replaceDocument(Plan.empty(PlanForms.DEFAULT_PLAN_NAME, today()), null, false, null);
            }
        }
    }

    @Override
    public void applyMain(MainWindowState state) {
        synchronized (lock) {
            main = state;
            ViewState view = document.viewState();
            view = ViewMode.parse(state.view()).map(view::withMode).orElse(view);
            view = PeriodChoice.parse(state.period()).map(view::withPeriod).orElse(view);
            Map<String, Boolean> f = state.filters();
            view = view.withShowIncome(f.getOrDefault("showIncome", view.showIncome()))
                    .withShowExpense(f.getOrDefault("showExpense", view.showExpense()))
                    .withShowOneTime(f.getOrDefault("showOneTime", view.showOneTime()))
                    .withShowSkipped(f.getOrDefault("showSkipped", view.showSkipped()))
                    .withMonthTotals(f.getOrDefault("monthTotals", view.monthTotals()))
                    .withChartMarkers(f.getOrDefault("chartMarkers", view.chartMarkers()))
                    .withChartBars(f.getOrDefault("chartBars", view.chartBars()))
                    .withSummaryPanel(f.getOrDefault("summaryPanel", view.summaryPanel()))
                    .withFilterText(state.filterText());
            document.setViewState(view);
        }
    }

    /**
     * Главное окно web-клиента — вкладка браузера; сервер его не показывает, браузер перерисуется по {@code GET /api/state}.
     */
    @Override
    public void showMainWindow() {
        log.info("Состояние главного окна восстановлено");
    }

    @Override
    public void selectRow(String rowId) {
        synchronized (lock) {
            main = main.withSelectedRowId(rowId);
        }
    }

    @Override
    public Set<String> existingTargetIds() {
        synchronized (lock) {
            Set<String> ids = new TreeSet<>();
            document.plan().rules().forEach(rule -> ids.add(rule.id().value()));
            document.plan().oneTimes().forEach(tx -> ids.add(tx.id().value()));
            return ids;
        }
    }

    /**
     * Фабрика окон восстановления: окно браузера нельзя открыть с сервера, поэтому создаётся серверный прокси,
     * который считается показанным сразу; браузер откроет диалоги по списку окон в {@code GET /api/state}.
     */
    private WindowFactory windowFactory() {
        return (state, ownerId, onShown, onFailed) -> {
            WebWindow window;
            try {
                window = new WebWindow(state.withIds(state.id(), ownerId));
            } catch (RuntimeException e) {
                onFailed.accept(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                return;
            }
            windows.put(window.windowId(), window);
            // Регистрацию в рекордере выполняет RestoreCoordinator в onShown.
            onShown.accept(window);
        };
    }

    // ================================================================== внутреннее

    /** Стартовый план: последний открытый, иначе первый из CashMemory, иначе пустой «Мой план» и мастер. */
    private void loadStartupPlan() {
        if (!settings.lastPlan().isBlank()) {
            try {
                Path file = resolvePlanPath(settings.lastPlan());
                if (Files.isRegularFile(file)) {
                    ReadResult result = repository.load(file, today());
                    replaceDocument(result.plan(), file, false, result.diagnostics());
                    log.info("Открыт последний план: " + file.getFileName());
                    return;
                }
                log.info("Последний план «" + settings.lastPlan() + "» не найден");
            } catch (IOException | RuntimeException e) {
                notices.add("Не удалось открыть последний план «" + settings.lastPlan() + "»: " + e.getMessage());
                log.error("Не удалось открыть последний план", e);
            }
        }
        try {
            for (PlanFileInfo info : repository.list()) {
                try {
                    ReadResult result = repository.load(info.path(), today());
                    replaceDocument(result.plan(), info.path(), false, result.diagnostics());
                    log.info("Открыт план: " + info.path().getFileName());
                    return;
                } catch (IOException | RuntimeException e) {
                    notices.add("Не удалось открыть план «" + info.name() + "»: " + e.getMessage());
                }
            }
        } catch (UncheckedIOException e) {
            notices.add("Не удалось прочитать папку CashMemory: " + e.getMessage());
        }
        document.replace(Plan.empty(PlanForms.DEFAULT_PLAN_NAME, today()), null, false, null);
        openWizard = true;
        log.info("Планов нет: браузер предложит мастер «Новый план»");
    }

    /** Сбой прошлого сеанса: снимок откладывается до решения пользователя. */
    private void preparePendingRestore() {
        try {
            Optional<SessionSnapshot> snapshot = store.load();
            if (snapshot.isPresent()) {
                pendingRestore = snapshot.get();
                log.info("Прошлый сеанс сервера завершился аварийно; снимок от " + pendingRestore.savedAt()
                        + " ждёт решения пользователя");
            } else {
                pendingProblem = "снимок не найден";
                log.info("Прошлый сеанс сервера завершился аварийно, но снимка нет");
            }
        } catch (SessionStoreException | RuntimeException e) {
            pendingProblem = e.getMessage() == null ? "снимок не читается" : e.getMessage();
            log.error("Снимок прошлого сеанса не читается", e);
        }
    }

    /** Реакция на изменения документа: снимок, настройки вида, автосохранение. */
    private void onDocumentEvent(DocumentEvent event) {
        if (event.has(EventKind.VIEW) && settings != null) {
            ViewState view = document.viewState();
            updateSettings(view::applyTo);
        }
        if (event.has(EventKind.PLAN) && settings != null && settings.autosave() && document.isDirty() && !closed) {
            if (autosave != null) {
                autosave.cancel(false);
            }
            Runnable action = autosaveAction;
            autosave = tasks.schedule(action, AUTOSAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        recorder.touch();
    }

    private void onStoreStatus(StoreStatus status) {
        StoreStatus previous = statuses.put(status.storeId(), status);
        if (!status.ok() && (previous == null || previous.ok() || !previous.message().equals(status.message()))) {
            log.info("Снимок сессии: " + status.message());
        }
    }

    private void cancelTasks() {
        if (settingsSave != null) {
            settingsSave.cancel(false);
        }
        if (autosave != null) {
            autosave.cancel(false);
        }
    }
}
