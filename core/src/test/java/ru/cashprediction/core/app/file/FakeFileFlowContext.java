package ru.cashprediction.core.app.file;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.flow.*;
import ru.cashprediction.core.document.*;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.view.chart.ChartScene;

/** Изолированный контекст файловых тестов с настоящими документом, формами и рекордером, без реестра. */
final class FakeFileFlowContext implements InvocationHandler {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    final List<String> events = new ArrayList<>();
    final TestScheduler scheduler = new TestScheduler();
    final Deque<Runnable> uiQueue = new ArrayDeque<>();
    final Map<String, String> persistent = new LinkedHashMap<>();
    final List<AlertSpec> alerts = new ArrayList<>();
    final List<FormRequest> requests = new ArrayList<>();
    final Map<String, FormSession> sessions = new LinkedHashMap<>();
    final Map<String, Consumer<Object>> results = new LinkedHashMap<>();
    final List<FileChooserSpec> chooserRequests = new ArrayList<>();
    final List<SessionSnapshot> snapshots = new ArrayList<>();
    final AppEnvironment environment;
    final FlowContext context;
    final UiPort port;
    final PlanDocument document;
    final FileFlow files;
    final AutosaveService autosave;
    final SettingsKeeper settings;
    final ExitFlow exit;
    final FileChooserService choosers;
    final ExternalChangeGuard guard = new ExternalChangeGuard();
    AppSettings appSettings = AppSettings.defaults();
    OpenWindows windows = OpenWindows.NONE;
    Path plansFolder;
    ClientProfile profile = ClientProfile.swing();
    Consumer<String> alertAnswer;
    Consumer<Optional<Path>> fileAnswer;
    Consumer<Optional<Path>> directoryAnswer;
    SessionRecorder recorder;
    String autosaveProblem = "";
    String selection = "";
    boolean pastExpanded;
    boolean queuedUi;
    boolean forecastAvailable = true;
    IOException pngFailure;
    ChartScene pngScene;
    byte[] png = new byte[] { (byte) 137, 80, 78, 71 };
    ExitKind exited;
    int nextWindow;

    /** Создаёт отдельную временную CashMemory; окружение не открывает настоящий реестр. */
    FakeFileFlowContext(Path home) throws IOException {
        LaunchOptions options = LaunchOptions.parse(new String[] {
                "--home", home.toString(), "--registry", "memory", "--today", TODAY.toString() });
        environment = AppEnvironment.from(options);
        Files.createDirectories(environment.cashMemory());
        plansFolder = environment.cashMemory();
        document = new PlanDocument(Plan.empty("Current", TODAY), null, () -> TODAY);
        context = (FlowContext) Proxy.newProxyInstance(FlowContext.class.getClassLoader(),
                new Class<?>[] { FlowContext.class }, this);
        port = (UiPort) Proxy.newProxyInstance(UiPort.class.getClassLoader(), new Class<?>[] { UiPort.class },
                this::portCall);
        files = new FileFlow(context);
        autosave = new AutosaveService(context);
        settings = new SettingsKeeper(context);
        exit = new ExitFlow(context);
        choosers = new FileChooserService(context);
    }

    /** Возвращает свежий снимок; доступность прогноза можно отключить для сценариев ошибок. */
    AppState state() {
        DocumentView view = new DocumentView(document.plan(), document.file().orElse(null), document.isDirty(),
                document.canUndo(), document.undoDescription().orElse(""), document.canRedo(),
                document.redoDescription().orElse(""), forecastAvailable ? document.forecast() : null, "", document.loadDiagnostics());
        return new AppState(0, profile, TODAY, environment.cashMemory(), plansFolder, view, document.viewState(),
                selection, pastExpanded, appSettings, RecorderStatus.RECORDING, List.of(), windows,
                StatusMessages.EMPTY, autosaveProblem);
    }

    /** Помечает документ изменённым без изменения его имени и желаемого пути сохранения. */
    void dirty() {
        document.replace(document.plan(), document.file().orElse(null), true, List.of());
    }

    /** Создаёт настоящий файл плана внутри тестовой CashMemory. */
    Path planFile(String name) throws IOException {
        Path path = environment.cashMemory().resolve(name + ".md");
        new ru.cashprediction.core.io.PlanRepository(environment.cashMemory()).save(Plan.empty(name, TODAY), path);
        return path;
    }

    /** Даёт один ответ на последнее сообщение. */
    void answer(String button) {
        Consumer<String> callback = alertAnswer;
        if (callback == null) throw new AssertionError("No pending alert");
        alertAnswer = null;
        windows = windows.without(windows.topModal().orElseThrow().windowId());
        events.add("answer:" + button);
        callback.accept(button);
    }

    /** Применяет результат последней формы через тот же обработчик, что настоящий FormSession. */
    void formResult(Object result) {
        String id = sessions.keySet().stream().reduce((left, right) -> right).orElseThrow();
        results.get(id).accept(result);
        FormSession session = sessions.remove(id);
        results.remove(id);
        windows = windows.without(id);
        if (recorder != null) recorder.unregister(session);
    }

    /** Возвращает последний настоящий сеанс формы для проверки ошибок и кнопок. */
    FormSession form() {
        return sessions.values().stream().reduce((left, right) -> right).orElseThrow();
    }

    /** Выполняет накопленные задачи исполнителя интерфейса. */
    void drainUi() {
        while (!uiQueue.isEmpty()) uiQueue.removeFirst().run();
    }

    /** Создаёт рекордер с памятью вместо реестра и с настоящим SessionBridge. */
    void startRecorder() {
        SessionStore store = (SessionStore) Proxy.newProxyInstance(SessionStore.class.getClassLoader(),
                new Class<?>[] { SessionStore.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> "xml";
                    case "title" -> "Test";
                    case "isAvailable" -> true;
                    case "unavailableReason" -> "";
                    case "markDirty" -> { events.add("markDirty"); yield null; }
                    case "save" -> {
                        snapshots.add((SessionSnapshot) args[0]);
                        events.add("snapshot");
                        yield null;
                    }
                    case "markClean" -> {
                        if (!events.contains("persistent:settingsFailed"))
                            throw new AssertionError("Settings attempt must precede markClean");
                        events.add("markClean");
                        yield null;
                    }
                    case "readMarker", "load", "lastSavedAt", "lastError" -> Optional.empty();
                    case "clear" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        recorder = new SessionRecorder(profile.snapshotClient(), List.of(store), UiExecutor.direct(),
                new SessionBridge(context), new TestScheduler(),
                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), 42);
        recorder.start();
        events.clear();
    }

    /** {@inheritDoc} Обрабатывает только контракт FlowContext, неожиданный вызов проваливает тест. */
    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "port" -> port;
            case "environment" -> environment;
            case "document" -> document;
            case "state" -> state();
            case "recorder" -> recorder;
            case "files" -> files;
            case "autosave" -> autosave;
            case "settingsKeeper" -> settings;
            case "exit" -> exit;
            case "choosers" -> choosers;
            case "externalChanges" -> guard;
            case "edits" -> new EditFlow(context);
            case "refresh" -> { events.add("refresh"); yield null; }
            case "showMain" -> { events.add("showMain"); yield null; }
            case "setAutosaveProblem" -> { autosaveProblem = (String) args[0]; yield null; }
            case "updateSettings" -> {
                appSettings = ((UnaryOperator<AppSettings>) args[0]).apply(appSettings);
                settings.changed();
                yield null;
            }
            case "updateView" -> {
                document.setViewState(((UnaryOperator<ViewState>) args[0]).apply(document.viewState()));
                yield null;
            }
            case "setSelection" -> { selection = (String) args[0]; yield null; }
            case "setPastExpanded" -> { pastExpanded = (boolean) args[0]; yield null; }
            case "setPlansFolder" -> {
                plansFolder = args[0] == null ? environment.cashMemory() : (Path) args[0];
                yield null;
            }
            case "status" -> { events.add("status:" + args[1]); yield null; }
            case "persistentStatus" -> {
                if (args[1] == null) persistent.remove((String) args[0]);
                else persistent.put((String) args[0], (String) args[1]);
                events.add("persistent:" + args[0]);
                yield null;
            }
            case "session" -> Optional.ofNullable(sessions.get((String) args[0]));
            case "singleInstance" -> sessions.values().stream()
                    .filter(session -> session.windowType().name().equals(args[0])).findFirst();
            case "showAlert" -> {
                AlertSpec spec = (AlertSpec) args[0];
                alerts.add(spec);
                events.add("alert:" + spec.purpose());
                String id = "a" + ++nextWindow;
                windows = windows.with(new OpenWindows.OpenWindow(id, WindowType.ALERT, spec.purpose(), true, "main", ""));
                alertAnswer = (Consumer<String>) args[1];
                yield handle();
            }
            case "openForm" -> openForm((FormRequest) args[0], (Consumer<Object>) args[2]);
            case "installRecorder" -> { recorder = (SessionRecorder) args[0]; yield null; }
            case "setRecorderStatus" -> null;
            default -> throw new AssertionError("Unexpected FlowContext call: " + method.getName());
        };
    }

    /** Создаёт настоящий сеанс формы и связывает его жизненный цикл с тестовым контекстом. */
    private FormSession openForm(FormRequest request, Consumer<Object> result) {
        requests.add(request);
        events.add("form:" + request.type());
        String id = "w" + ++nextWindow;
        FormSession.Host host = new FormSession.Host() {
            /** {@inheritDoc} */
            @Override public void registered(FormSession session) { if (recorder != null) recorder.register(session); }
            /** {@inheritDoc} */
            @Override public void unregistered(FormSession session) {
                sessions.remove(id);
                results.remove(id);
                windows = windows.without(id);
                if (recorder != null) recorder.unregister(session);
            }
            /** {@inheritDoc} */
            @Override public void touched(FormSession session) { }
            /** {@inheritDoc} */
            @Override public void closed(FormSession session, Object value) { if (result != null) result.accept(value); }
            /** {@inheritDoc} */
            @Override public void openChild(FormSession parent, WindowState child) { throw new AssertionError("Unexpected child"); }
            /** {@inheritDoc} */
            @Override public void applied(FormSession session, Object value) { if (result != null) result.accept(value); }
        };
        FormSession session = new FormSession(request.type(), request.modal(), request.logic(),
                new FormContext(id, "main", request.context(), state()), host);
        sessions.put(id, session);
        results.put(id, result);
        windows = windows.with(new OpenWindows.OpenWindow(id, request.type(),
                request.context().getOrDefault("purpose", ""), request.modal(), "main", request.type().name()));
        session.attach(handle());
        session.shown();
        return session;
    }

    /** Обрабатывает запросы порта без настоящего интерфейса; ответы остаются под управлением теста. */
    @SuppressWarnings("unchecked")
    private Object portCall(Object proxy, Method method, Object[] args) throws IOException {
        return switch (method.getName()) {
            case "profile" -> profile;
            case "executor" -> new UiExecutor() {
                /** {@inheritDoc} */
                @Override public void execute(Runnable task) { if (queuedUi) uiQueue.addLast(task); else task.run(); }
                /** {@inheritDoc} */
                @Override public boolean isUiThread() { return true; }
            };
            case "scheduler" -> scheduler;
            case "mainGeometry" -> MainGeometry.UNKNOWN;
            case "chooseFile" -> {
                chooserRequests.add((FileChooserSpec) args[0]);
                fileAnswer = (Consumer<Optional<Path>>) args[1];
                events.add("chooseFile");
                yield null;
            }
            case "chooseDirectory" -> { directoryAnswer = (Consumer<Optional<Path>>) args[1]; events.add("chooseDirectory"); yield null; }
            case "renderChartPng" -> {
                if (pngFailure != null) throw pngFailure;
                pngScene = (ChartScene) args[0];
                events.add("renderPng");
                yield png.clone();
            }
            case "revealRow" -> { events.add("reveal:" + args[0]); yield null; }
            case "exit" -> { exited = (ExitKind) args[0]; events.add("exit:" + exited); yield null; }
            default -> throw new AssertionError("Unexpected UiPort call: " + method.getName());
        };
    }

    /** Возвращает безопасную ручку окна для настоящих сеансов форм. */
    private WindowHandle handle() {
        return (WindowHandle) Proxy.newProxyInstance(WindowHandle.class.getClassLoader(), new Class<?>[] { WindowHandle.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "showing" -> true;
                    case "bounds" -> null;
                    case "close", "update", "updateAlert", "toFront" -> null;
                    default -> throw new AssertionError(method.getName());
                });
    }

    /** Детерминированный планировщик с возможностью отдельно воспроизвести уже отправленный UI-вызов. */
    final class TestScheduler implements Scheduler {
        final List<Scheduled> tasks = new ArrayList<>();
        long now;
        boolean stopped;

        /** Одна задача и её отменяемое состояние. */
        final class Scheduled implements Task {
            final Runnable action;
            final long at;
            boolean cancelled;
            Scheduled(Runnable action, long at) { this.action = action; this.at = at; }
            /** {@inheritDoc} */
            @Override public void cancel() { cancelled = true; events.add("cancelTimer"); }
        }

        /** {@inheritDoc} */
        @Override public Task schedule(Runnable action, Duration delay) {
            if (stopped) throw new AssertionError("Scheduler stopped");
            Scheduled task = new Scheduled(action, now + delay.toMillis());
            tasks.add(task);
            return task;
        }
        /** {@inheritDoc} Периодический таймер рекордера в файловых тестах не продвигается. */
        @Override public Task scheduleAtFixedRate(Runnable action, Duration initial, Duration period) {
            return schedule(action, initial);
        }
        /** {@inheritDoc} */
        @Override public void execute(Runnable action) { action.run(); }
        /** {@inheritDoc} */
        @Override public void shutdown() {
            stopped = true;
            tasks.forEach(task -> task.cancelled = true);
            events.add("shutdownTimers");
        }
        /** Продвигает время, исполняя только задачи, срок которых действительно наступил. */
        void advance(long milliseconds) {
            long until = now + milliseconds;
            while (true) {
                Scheduled next = tasks.stream().filter(task -> !task.cancelled && task.at <= until)
                        .min(java.util.Comparator.comparingLong(task -> task.at)).orElse(null);
                if (next == null) break;
                now = next.at;
                next.cancelled = true;
                next.action.run();
            }
            now = until;
        }
    }
}
