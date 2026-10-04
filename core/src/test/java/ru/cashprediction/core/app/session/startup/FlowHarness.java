package ru.cashprediction.core.app.flow;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.document.*;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.service.plan.LocalPlanCommands;
import ru.cashprediction.core.service.plan.PlanCommands;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.alert.AlertSpec;

/** Изолированный контроллер: ответы приходят вручную, задачи таймеров не запускают фоновые потоки. */
final class FlowHarness {
    static final Instant NOW = Instant.parse("2026-10-01T10:20:30Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    final List<String> events = new ArrayList<>();
    final List<AlertSpec> alerts = new ArrayList<>();
    final List<Consumer<String>> answers = new ArrayList<>();
    final List<Runnable> queued = new ArrayList<>();
    final List<Runnable> scheduled = new ArrayList<>();
    final AppEnvironment environment;
    final PlanDocument document = new PlanDocument(Plan.empty("seed", TODAY), null, () -> TODAY);
    /** Единственная служба команд и ревизий этого документа. */
    final PlanCommands planCommands = new LocalPlanCommands(document);
    final ExternalChangeGuard externalChanges = new ExternalChangeGuard();
    final FlowContext context;
    final UiPort port;
    final FileChooserService choosers;
    final FileFlow files;
    final SnapshotSource source;
    final RestoreTarget target;
    final Scheduler scheduler;
    AppSettings settings = AppSettings.defaults();
    ClientProfile profile = ClientProfile.fx("25");
    RecorderStatus recorderStatus = RecorderStatus.NOT_STARTED;
    SessionRecorder recorder;
    Consumer<Optional<Path>> chosen;
    MainWindowState restored;
    boolean failPlan;
    boolean failAlert;
    boolean warningPlan;
    String exit;

    FlowHarness(Path home) {
        this(home, null);
    }

    FlowHarness(Path home, LaunchOptions.RecoveryAnswer answer) {
        LaunchOptions options = new LaunchOptions(home, null, true, TODAY,
                null, null, answer, false, true, true, List.of());
        environment = new AppEnvironment(options, home, home.resolve("CashMemory"),
                AppClock.of(Clock.fixed(NOW, ZoneOffset.UTC), TODAY));
        scheduler = proxy(Scheduler.class, (name, args) -> switch (name) {
            case "schedule" -> { scheduled.add((Runnable) args[0]); yield (Scheduler.Task) () -> { }; }
            case "scheduleAtFixedRate" -> (Scheduler.Task) () -> { };
            case "execute" -> { ((Runnable) args[0]).run(); yield null; }
            case "shutdown" -> null;
            default -> throw new AssertionError(name);
        });
        UiExecutor executor = proxy(UiExecutor.class, (name, args) -> switch (name) {
            case "isUiThread" -> true;
            case "execute" -> { queued.add((Runnable) args[0]); yield null; }
            default -> throw new AssertionError(name);
        });
        port = proxy(UiPort.class, (name, args) -> switch (name) {
            case "profile" -> profile;
            case "executor" -> executor;
            case "scheduler" -> scheduler;
            case "chooseFile" -> { chosen = cast(args[1]); events.add("chooser"); yield null; }
            case "exit" -> { exit = args[0] + ":" + args[1]; events.add("exit"); yield null; }
            default -> throw new AssertionError("Unexpected port call: " + name);
        });
        context = proxy(FlowContext.class, this::call);
        choosers = new FileChooserService(context);
        files = new FileFlow(context);
        source = proxy(SnapshotSource.class, (name, args) -> switch (name) {
            case "captureMain" -> MainWindowState.empty();
            case "capturePlan" -> PlanState.CLEAN;
            default -> throw new AssertionError(name);
        });
        target = proxy(RestoreTarget.class, (name, args) -> switch (name) {
            case "loadPlan" -> {
                events.add("load");
                if (failPlan) throw new IllegalArgumentException("broken plan");
                if (warningPlan) FlowHarness.<Consumer<String>>cast(args[2]).accept("restore warning");
                yield null;
            }
            case "applyMain" -> { restored = (MainWindowState) args[0]; events.add("apply"); yield null; }
            case "showMainWindow" -> { context.showMain(restored); yield null; }
            case "selectRow" -> { events.add("select"); yield null; }
            case "existingTargetIds" -> Set.of();
            default -> throw new AssertionError(name);
        });
    }

    /** Обрабатывает только контрактные действия потока; неожиданная операция ломает тест. */
    private Object call(String name, Object[] args) {
        return switch (name) {
            case "port" -> port;
            case "environment" -> environment;
            case "document" -> document;
            case "planCommands" -> planCommands;
            case "planStorage" -> externalChanges.storage();
            case "externalChanges" -> externalChanges;
            case "state" -> new AppState(1, profile, TODAY, environment.cashMemory(), null,
                    new DocumentView(document.plan(), document.file().orElse(null), document.isDirty(),
                            false, "", false, "", null, "", document.loadDiagnostics()),
                    document.viewState(), "", false, settings, recorderStatus, List.of(), null, null, "");
            case "updateSettings" -> { settings = FlowHarness.<UnaryOperator<AppSettings>>cast(args[0]).apply(settings); yield null; }
            case "updateView" -> { document.setViewState(FlowHarness.<UnaryOperator<ViewState>>cast(args[0]).apply(document.viewState())); yield null; }
            case "recorder" -> recorder;
            case "installRecorder" -> { recorder = (SessionRecorder) args[0]; events.add("install"); yield null; }
            case "setRecorderStatus" -> { recorderStatus = (RecorderStatus) args[0]; events.add("status:" + args[0]); yield null; }
            case "showMain" -> { events.add("main"); yield null; }
            case "showAlert" -> {
                if (failAlert) throw new IllegalStateException("alert failed");
                AlertSpec spec = (AlertSpec) args[0];
                alerts.add(spec);
                answers.add(cast(args[1]));
                events.add("alert:" + spec.purpose());
                yield null;
            }
            case "status" -> { events.add("message:" + args[1]); yield null; }
            case "files" -> files;
            case "choosers" -> choosers;
            case "openForm" -> { events.add("wizard"); yield null; }
            case "refresh" -> null;
            default -> throw new AssertionError("Unexpected context call: " + name);
        };
    }

    void answer(String value) { answers.getLast().accept(value); }

    StartupFlow startup(List<SessionStore> stores, CrashDetector.Status status) {
        Map<String, CrashDetector.StoreInfo> infos = new LinkedHashMap<>();
        for (SessionStore store : stores) {
            infos.put(store.id(), new CrashDetector.StoreInfo(store.isAvailable(), store.lastSavedAt(), ""));
        }
        return new StartupFlow(context, stores, source, target,
                (state, owner, shown, failed) -> { throw new AssertionError("Unexpected window"); },
                () -> new CrashDetector.Detection(status, infos, null));
    }

    void recording(List<SessionStore> stores) {
        recorder = new SessionRecorder(profile.snapshotClient(), stores, port.executor(), source,
                scheduler, environment.clock().clock());
        recorder.start();
    }

    static SessionSnapshot snapshot(String client, boolean dirty) {
        return SessionSnapshot.of(NOW, client, MainWindowState.empty(), dirty ? PlanState.dirty("broken text") : PlanState.CLEAN, List.of());
    }

    /** Поддерживает контрактный интерфейс без зависимости от клиентских инструментов. */
    private interface Calls { Object call(String name, Object[] args); }

    private static <T> T proxy(Class<T> type, Calls calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> calls.call(method.getName(), args == null ? new Object[0] : args)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) { return (T) value; }

    /** Хранилище без реестра и файлов с управляемой ошибкой повторного чтения. */
    static final class Store implements SessionStore {
        final String id;
        final List<String> events;
        SessionSnapshot snapshot;
        SessionMarker marker;
        boolean fail;
        boolean available = true;
        Store(String id, List<String> events) { this.id = id; this.events = events; }
        /** @return идентификатор */
        public String id() { return id; }
        /** @return тестовое имя */
        public String title() { return id; }
        /** @return доступность */
        public boolean isAvailable() { return available; }
        /** @return причина */
        public String unavailableReason() { return available ? "" : "unavailable"; }
        /** Сохраняет маркер и порядок действий. */
        public void markDirty(SessionMarker marker) { this.marker = marker; events.add("dirty:" + id); }
        /** Отмечает чистое закрытие. */
        public void markClean() { marker = null; }
        /** @return маркер */
        public Optional<SessionMarker> readMarker() { return Optional.ofNullable(marker); }
        /** Записывает снимок и событие для проверки порядка. */
        public void save(SessionSnapshot value) { snapshot = value; events.add("save:" + id); }
        /** @return снимок; ошибка настраивается тестом */
        public Optional<SessionSnapshot> load() throws SessionStoreException {
            if (fail) throw new SessionStoreException("unreadable");
            return Optional.ofNullable(snapshot);
        }
        /** @return время снимка */
        public Optional<Instant> lastSavedAt() { return Optional.ofNullable(snapshot).map(SessionSnapshot::savedAt); }
        /** Удаляет снимок и маркер. */
        public void clear() { snapshot = null; marker = null; events.add("clear:" + id); }
    }
}
