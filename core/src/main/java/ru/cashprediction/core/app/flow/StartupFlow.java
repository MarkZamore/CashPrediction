package ru.cashprediction.core.app.flow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import ru.cashprediction.core.app.ExitKind;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.app.LaunchOptions.RecoveryAnswer;
import ru.cashprediction.core.app.RecorderStatus;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.RestoreCoordinator;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.RestoreTarget;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.SnapshotSource;
import ru.cashprediction.core.session.WindowFactory;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.alert.AlertKind;
import ru.cashprediction.core.ui.alert.AlertButton;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/**
 * Запуск (спецификация v2, §6.28, §6.29).
 *
 * <p><b>Порядок:</b> 1) папка CashMemory (рядом с программой или {@code --home}), settings.md, пустой «Мой план»;
 * 2) хранилища {@code SessionStores.forClient}; 3) {@code CrashDetector.detect}:</p>
 * <ul>
 *   <li>CLEAN_START — открыть settings.lastPlan, если файл есть, иначе первый план CashMemory; показать окно; начать
 *       запись; если план не открыт — первый запуск: мастер поверх окна ({@code FileFlow.firstRunWizard});</li>
 *   <li>CRASHED — диалог восстановления до главного окна ({@code AlertCatalog.crashRecovery}); выбор хранилища →
 *       {@code RestoreCoordinator} (план, вид, окно, окна по порядку, выделение, «что-если», прошедшие), затем отчёт;
 *       «Не восстанавливать» → снимки очищаются, обычный запуск; ошибка чтения → {@code err.readSnapshot}; пустой
 *       снимок → {@code info.snapshotEmpty}; RECORDER_NOT_STARTED — цикл «Сохранить план в файл…» (отмена выбора и
 *       ошибка записи возвращают к окну);</li>
 *   <li>ALREADY_RUNNING — сообщение §6.28; «Открыть без восстановления» — без записи сеанса
 *       ({@code RecorderStatus.DISABLED_SECOND_INSTANCE}); «Выйти» — выход.</li>
 * </ul>
 * <p>4) Загрузка с замечаниями — §6.17; 5) ошибка запуска — {@code AlertCatalog.startupError} и выход. Автоответы
 * самотеста — {@code LaunchOptions.selftestRecovery}.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class StartupFlow {

    private final FlowContext context;
    private List<SessionStore> stores;
    private SnapshotSource source;
    private RestoreTarget target;
    private WindowFactory factory;
    private Supplier<CrashDetector.Detection> detector;
    private boolean started;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public StartupFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Подменяет только внешние участники запуска для изолированных тестов, без изменения их контрактов. */
    StartupFlow(FlowContext context, List<SessionStore> stores, SnapshotSource source, RestoreTarget target,
                WindowFactory factory, Supplier<CrashDetector.Detection> detector) {
        this(context);
        this.stores = List.copyOf(stores);
        this.source = Objects.requireNonNull(source);
        this.target = Objects.requireNonNull(target);
        this.factory = Objects.requireNonNull(factory);
        this.detector = Objects.requireNonNull(detector);
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** Выполняет запуск (порядок — в описании класса). */
    public void start() {
        if (started) {
            throw new IllegalStateException("Startup already requested");
        }
        started = true;
        guarded(() -> {
            Path home = context.environment().cashMemory();
            try {
                Files.createDirectories(home);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            AppSettings settings = SettingsMarkdown.load(home.resolve("settings.md"));
            context.updateSettings(old -> settings);
            context.document().replace(Plan.empty(UiText.get("s2.startup.planName"),
                    context.environment().clock().today()), null, false, List.of());
            context.updateView(old -> ViewState.fromSettings(settings));
            if (stores == null) {
                stores = SessionStores.forClient(context.port().profile(), context.environment());
                SessionBridge bridge = new SessionBridge(context);
                source = bridge;
                target = bridge;
                factory = new CoreWindowFactory(context);
                detector = () -> CrashDetector.detect(stores, context.port().profile().snapshotClient());
            }
            CrashDetector.Detection detection = detector.get();
            switch (detection.status()) {
                case CLEAN_START -> ordinary(true);
                case ALREADY_RUNNING -> alreadyRunning();
                case CRASHED -> crashed(detection);
            }
        });
    }

    /** Сообщает об ошибке запуска, в том числе из асинхронного продолжения. */
    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            AlertSpec base = AlertCatalog.startupError(e);
            context.showAlert(new AlertSpec(base.kind(), base.purpose(), base.targetId(), UiText.get("alert.uncaught.title"),
                    base.glyph(), UiText.get("s2.startup.errorHeader"), base.content(), base.details(), false, base.minWidth(),
                    base.buttons(), base.defaultButtonId(), false), button -> context.port().exit(ExitKind.CLEAN, 2));
        }
    }

    /** Оборачивает продолжение сообщения защитой от ошибки запуска. */
    private void alert(AlertSpec spec, Consumer<String> answer) {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        context.showAlert(spec, button -> guarded(() -> answer.accept(button)));
    }

    /** Открывает обычный план, показывает главное окно и только затем начинает запись. */
    private void ordinary(boolean record) {
        PlanRepository repository = new PlanRepository(context.environment().cashMemory());
        String last = context.state().settings().lastPlan();
        Path file = last.isBlank() ? null : repository.dir().resolve(last);
        if (file == null || !Files.isRegularFile(file)) {
            file = repository.list().stream().findFirst().map(info -> info.path()).orElse(null);
        }
        ReadResult loaded = null;
        if (file != null) {
            try {
                loaded = repository.load(file, context.environment().clock().today());
                context.externalChanges().remember(file);
                context.document().replace(loaded.plan(), file, false, loaded.diagnostics());
                Path opened = file;
                context.updateSettings(settings -> settings.withPlanOpened(opened.toString()));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        context.showMain(null);
        if (record) {
            installRecorder();
            beginRecording();
        }
        if (loaded != null && loaded.hasWarnings()) {
            alert(AlertCatalog.loadDiagnostics(file.getFileName().toString(), loaded.diagnostics()), button -> { });
        }
        if (loaded == null) {
            context.files().firstRunWizard();
        }
    }

    /** Создаёт рекордер с общими часами и планировщиком порта. */
    private void installRecorder() {
        context.installRecorder(new SessionRecorder(context.port().profile().snapshotClient(), stores,
                context.port().executor(), source, context.port().scheduler(), context.environment().clock().clock()));
    }

    /** Запускает запись лишь после восстановления или решения о сохранении исходного текста. */
    private void beginRecording() {
        context.recorder().start();
        context.setRecorderStatus(RecorderStatus.RECORDING);
        context.recorder().touch();
    }

    /** Второй экземпляр не устанавливает рекордер и не изменяет снимки первого. */
    private void alreadyRunning() {
        Consumer<String> answer = button -> {
            if ("openWithoutRestore".equals(button) || "continue".equals(button)) {
                context.setRecorderStatus(RecorderStatus.DISABLED_SECOND_INSTANCE);
                ordinary(false);
            } else {
                context.port().exit(ExitKind.CLEAN, 0);
            }
        };
        if (context.environment().options().selftestRecovery() == RecoveryAnswer.ALREADY_OK) {
            answer.accept("openWithoutRestore");
        } else {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            AlertSpec base = AlertCatalog.alreadyRunning(context.port().profile());
            alert(new AlertSpec(AlertKind.CONFIRMATION, base.purpose(), base.targetId(), base.windowTitle(), base.glyph(),
                    UiText.get("s2.startup.alreadyHeader"), UiText.get("s2.startup.alreadyContent"), base.details(), false,
                    base.minWidth(), base.buttons(), base.defaultButtonId(), false), answer);
        }
    }

    /** Показывает выбор до главного окна; автоматический ответ проходит тот же путь чтения. */
    private void crashed(CrashDetector.Detection detection) {
        context.setRecorderStatus(RecorderStatus.PENDING_RESTORE);
        RecoveryAnswer automatic = context.environment().options().selftestRecovery();
        if (automatic != null && automatic != RecoveryAnswer.ALREADY_OK) {
            restoreChoice(context.port().profile().snapshotClient().equals("web") && automatic != RecoveryAnswer.NONE
                    ? AlertCatalog.BUTTON_RESTORE_SERVER : switch (automatic) {
                case REGISTRY -> AlertCatalog.BUTTON_RESTORE_REGISTRY;
                case XML -> AlertCatalog.BUTTON_RESTORE_XML;
                default -> AlertCatalog.BUTTON_NO_RESTORE;
            });
            return;
        }
        String preferred = context.state().settings().recoveryStore() == RecoveryStoreKind.XML
                ? AlertCatalog.BUTTON_RESTORE_XML : AlertCatalog.BUTTON_RESTORE_REGISTRY;
        if (context.port().profile().snapshotClient().equals("web")) {
            preferred = AlertCatalog.BUTTON_RESTORE_SERVER;
        }
        SessionSnapshot preview = null;
        for (SessionStore store : stores) {
            try {
                preview = store.load().orElse(null);
                if (preview != null) break;
            } catch (SessionStoreException | RuntimeException ignored) {
                // Ошибка предварительного просмотра не запрещает выбрать второе хранилище.
            }
        }
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        AlertSpec base = recoveryButtons(AlertCatalog.crashRecovery(detection, preview, preferred, context.port().profile()), preferred);
        String header = UiText.get("s2.startup.crashed");
        if (detection.marker() != null) {
            String clientKey = switch (detection.marker().client()) {
                case "fx" -> "s2.startup.clientFx";
                case "swing" -> "client.swing";
                default -> "client.web";
            };
            header += "\n" + UiText.get("s2.startup.marker",
                    UiFormats.dateTime(detection.marker().startedAt().atZone(context.environment().clock().zone()).toLocalDateTime()),
                    UiText.get(clientKey));
        }
        List<String> content = new ArrayList<>();
        content.add(UiText.get("s2.startup.restoreQuestion"));
        for (SessionStore store : stores) {
            CrashDetector.StoreInfo info = detection.stores().get(store.id());
            String detail = info != null && info.restorable()
                    ? UiText.get("s2.startup.snapshotAt", UiFormats.dateTimeSeconds(info.snapshotAt().orElseThrow()
                            .atZone(context.environment().clock().zone()).toLocalDateTime()))
                    : UiText.get("s2.startup.cannotRestore", info == null || !info.available()
                            ? UiText.get("s2.startup.storeUnavailable")
                            : info.problem().isBlank() ? UiText.get("s2.recovery.absent") : info.problem());
            content.add(UiText.get("s2.startup.storeLine", store.title(), detail));
        }
        if (preview != null) {
            String path = preview.main().planPath().isBlank() ? UiText.get("s2.startup.noFile") : preview.main().planPath();
            String dirty = preview.plan().dirty() ? UiText.get("s2.startup.dirty") : "";
            String view = "CHART".equals(preview.main().view()) ? UiText.get("s2.startup.view.chart") : UiText.get("s2.startup.view.table");
            String windows = preview.windows().stream().map(window -> window.type() == null
                    ? UiText.get("s2.startup.unknownWindow") : window.type().title()).reduce((a, b) -> a + ", " + b)
                    .orElseGet(() -> UiText.get("s2.startup.noWindows"));
            content.add(UiText.get("s2.startup.preview", path, dirty, view, windows));
        }
        alert(withText(base, header, String.join("\n", content)), this::restoreChoice);
    }

    /** Согласует идентификаторы каталога с опубликованными константами ответов восстановления. */
    private static AlertSpec recoveryButtons(AlertSpec base, String preferred) {
        // JavaFX: ButtonType → Swing: JButton → Web: button.
        List<AlertButton> buttons = base.buttons().stream().map(button -> {
            String id = switch (button.id()) {
                case "registry" -> AlertCatalog.BUTTON_RESTORE_REGISTRY;
                case "xml" -> AlertCatalog.BUTTON_RESTORE_XML;
                case "server" -> AlertCatalog.BUTTON_RESTORE_SERVER;
                default -> button.id();
            };
            return new AlertButton(id, button.text(), button.role(), button.enabled(), button.tooltip());
        }).toList();
        String defaultId = buttons.stream().filter(button -> button.id().equals(preferred) && button.enabled())
                .findFirst().orElseGet(() -> buttons.stream().filter(AlertButton::enabled).findFirst().orElseThrow()).id();
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        return new AlertSpec(base.kind(), base.purpose(), base.targetId(), base.windowTitle(), base.glyph(), base.header(),
                base.content(), base.details(), base.detailsExpanded(), base.minWidth(), buttons, defaultId, base.restorable());
    }

    /** Читает выбранное хранилище повторно: оно могло измениться, пока сообщение было открыто. */
    private void restoreChoice(String button) {
        String id = switch (Objects.requireNonNullElse(button, "")) {
            case AlertCatalog.BUTTON_RESTORE_REGISTRY -> "registry";
            case AlertCatalog.BUTTON_RESTORE_XML -> "xml";
            case AlertCatalog.BUTTON_RESTORE_SERVER -> "server";
            default -> "";
        };
        if (id.isEmpty()) {
            RestoreCoordinator.startFresh(stores);
            ordinary(true);
            return;
        }
        Optional<SessionSnapshot> snapshot;
        try {
            SessionStore selected = stores.stream().filter(store -> store.id().equals(id)).findFirst().orElse(null);
            snapshot = selected == null ? Optional.empty() : selected.load();
        } catch (SessionStoreException | RuntimeException e) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            AlertSpec base = AlertCatalog.error("readSnapshot", e);
            alert(withText(base, base.header(), UiText.get("s2.startup.readSnapshot", reason(e))), answer -> ordinary(true));
            return;
        }
        if (snapshot.isEmpty()) {
            alert(AlertCatalog.info("snapshotEmpty"), answer -> ordinary(true));
            return;
        }
        installRecorder();
        SessionSnapshot chosen = snapshot.get();
        new RestoreCoordinator().restore(chosen, target, factory, context.recorder(),
                report -> guarded(() -> restored(chosen, report)));
    }

    /** Отчёт показывается только с замечаниями, затем сохраняется неоткрытый исходный план. */
    private void restored(SessionSnapshot snapshot, RestoreReport report) {
        if (context.recorder().isStarted()) {
            context.setRecorderStatus(RecorderStatus.RECORDING);
        }
        Runnable next = () -> diagnostics(() -> {
            if (report.warnings().contains(RestoreCoordinator.RECORDER_NOT_STARTED)) {
                preservePlan(snapshot.plan().markdown());
            }
        });
        if (!report.clean()) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            AlertSpec base = AlertCatalog.restoreReport(report.windowsRestored(), report.warnings());
            AlertSpec reportSpec = withText(base, UiText.get("s2.startup.reportHeader"),
                    UiText.get("s2.startup.report", report.windowsRestored(), report.warnings().size()));
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            alert(new AlertSpec(reportSpec.kind(), reportSpec.purpose(), reportSpec.targetId(), UiText.get("alert.recovery.title"),
                    reportSpec.glyph(), reportSpec.header(), reportSpec.content(), reportSpec.details(), true,
                    reportSpec.minWidth(), reportSpec.buttons(), reportSpec.defaultButtonId(), false), answer -> next.run());
        } else {
            next.run();
        }
    }

    /** Показывает замечания чтения восстановленного плана перед продолжением запуска. */
    private void diagnostics(Runnable next) {
        if (context.document().loadDiagnostics().stream().anyMatch(d -> d.severity() != ru.cashprediction.core.diagnostics.Severity.INFO)) {
            String name = context.document().file().map(path -> path.getFileName().toString()).orElse(context.document().plan().name());
            alert(AlertCatalog.loadDiagnostics(name, context.document().loadDiagnostics()), answer -> next.run());
        } else {
            next.run();
        }
    }

    /** Уточняет тексты потока, сохраняя кнопки и поведение общего каталога сообщений. */
    private static AlertSpec withText(AlertSpec base, String header, String content) {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        return new AlertSpec(base.kind(), base.purpose(), base.targetId(), base.windowTitle(), base.glyph(), header,
                content, base.details(), base.detailsExpanded(), base.minWidth(), base.buttons(), base.defaultButtonId(), base.restorable());
    }

    /** Причина ошибки чтения без пустого заполнителя. */
    private static String reason(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** Отмена выбора и ошибка записи возвращают вопрос без запуска записи и потери старого снимка. */
    private void preservePlan(String markdown) {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        AlertSpec base = AlertCatalog.recorderNotStarted(markdown);
        AlertSpec prompt = new AlertSpec(base.kind(), base.purpose(), base.targetId(), UiText.get("alert.recovery.title"),
                base.glyph(), UiText.get("s2.startup.recorderHeader"), UiText.get("s2.startup.recorderContent"), markdown,
                false, base.minWidth(), base.buttons(), base.defaultButtonId(), false);
        alert(prompt, button -> {
            if (!"saveSnapshotPlan".equals(button)) {
                beginRecording();
                return;
            }
            // JavaFX: FileChooser → Swing: JFileChooser → Web: dialog.
            FileChooserSpec spec = new FileChooserSpec(FileChooserSpec.Purpose.SAVE_SNAPSHOT_PLAN,
                    FileChooserSpec.Mode.SAVE, UiText.get("s2.startup.saveTitle"), UiText.get("s2.startup.planFilter"),
                    List.of("md"), context.environment().cashMemory(),
                    UiText.get("s2.startup.recoveredName", PlanRepository.fileBaseName(context.document().plan().name())));
            context.choosers().chooseFile(spec, result -> guarded(() -> {
                if (result.isEmpty()) {
                    preservePlan(markdown);
                    return;
                }
                try {
                    AtomicFiles.writeString(result.get(), markdown);
                } catch (IOException | RuntimeException e) {
                    alert(AlertCatalog.error("snapshotPlanSave", e), answer -> preservePlan(markdown));
                    return;
                }
                context.status(StatusLevel.SUCCESS, "status.msg.snapshotPlanSaved", result.get());
                beginRecording();
            }));
        });
    }
}
