package ru.cashprediction.swing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.swing.Timer;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.io.AppPaths;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;
import ru.cashprediction.swing.action.AppButtons;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingRecoveryDialog;
import ru.cashprediction.swing.selftest.SelfTestConfig;
import ru.cashprediction.swing.selftest.SwingSelfTest;
import ru.cashprediction.swing.session.SettingsKeeper;
import ru.cashprediction.swing.session.SwingCrashHooks;
import ru.cashprediction.swing.session.SwingUiExecutor;

/**
 * Порядок запуска Swing-клиента (разделы 5.2–5.6 плана): папка CashMemory и настройки, главное окно, рекордер
 * сессии, обнаружение сбоя и одна из трёх веток.
 *
 * <ul>
 *   <li><b>CLEAN_START</b> — обычный запуск: показать окно, открыть последний план (или первый из CashMemory, или
 *   мастер нового плана), начать запись сессии.</li>
 *   <li><b>CRASHED</b> — до главного окна диалог «Восстановление» (реестр / XML / не восстанавливать). Выбор хранилища
 *   запускает {@code RestoreCoordinator}; «Не восстанавливать» очищает хранилища клиента и ведёт к обычному запуску.</li>
 *   <li><b>ALREADY_RUNNING</b> — вопрос «CashPrediction уже запущен. Открыть без восстановления и без записи сессии?»:
 *   «Открыть» отключает рекордер (снимки пишет первый экземпляр), «Не открывать» завершает процесс.</li>
 * </ul>
 *
 * <p>После запуска (и после восстановления, если оно было) стартует самотест, если он включён системным свойством
 * {@code cashprediction.selftest}. В режиме самотеста диалог восстановления и вопрос о втором экземпляре всё равно
 * показываются, а через секунду на них отвечает свойство {@code cashprediction.selftest.recovery}.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
final class StartupFlow {

    /** Через сколько миллисекунд самотест отвечает на диалог восстановления (окно успевает показаться). */
    private static final int AUTO_ANSWER_DELAY_MS = 1200;
    /**
     * Свойство самотеста: через сколько миллисекунд отвечать на диалог восстановления (по умолчанию
     * {@link #AUTO_ANSWER_DELAY_MS}). Большая задержка оставляет время снять снимок экрана самого диалога.
     */
    private static final String PROP_ANSWER_DELAY = "cashprediction.selftest.recoveryDelayMs";

    private final Optional<SelfTestConfig> selfTest = SelfTestConfig.fromSystemProperties();
    private CashMemoryLayout layout;
    private MainFrame app;
    private List<SessionStore> stores;
    private boolean started;
    private String startupProblem;

    /** Создаёт порядок запуска. */
    StartupFlow() {
    }

    /** Выполняет запуск. */
    void run() {
        layout = openLayout();
        AppSettings settings = SettingsMarkdown.load(layout.settingsFile());
        app = new MainFrame(layout, new SettingsKeeper(layout.settingsFile(), settings));
        stores = List.of(RegistrySessionStore.forClient(SnapshotSchema.CLIENT_SWING),
                XmlSessionStore.inCashMemory(layout.dir(), SnapshotSchema.CLIENT_SWING));
        SessionRecorder recorder = SessionRecorder.create(SnapshotSchema.CLIENT_SWING, stores, new SwingUiExecutor(), app);
        app.attachRecorder(recorder);
        SwingCrashHooks.setRecorder(recorder);
        if (selfTest.isPresent()) {
            // В самотесте сообщение о необработанном исключении закрывается само, и процесс завершается с кодом 2.
            // Свойство cashprediction.selftest.errorAlertMs продлевает показ, чтобы снять снимок экрана сообщения.
            SwingCrashHooks.setAutoCloseMillis(Math.max(1, Integer.getInteger("cashprediction.selftest.errorAlertMs", 1500)));
        }

        CrashDetector.Detection detection;
        try {
            detection = CrashDetector.detect(stores, SnapshotSchema.CLIENT_SWING);
        } catch (RuntimeException e) {
            // Хранилища не читаются вовсе: восстанавливать нечего, запускаемся обычно.
            startupProblem = "Не удалось проверить прошлый сеанс: " + e.getMessage();
            normalStart();
            return;
        }
        switch (detection.status()) {
            case CLEAN_START -> normalStart();
            case CRASHED -> crashed(detection);
            case ALREADY_RUNNING -> alreadyRunning();
        }
    }

    private CashMemoryLayout openLayout() {
        try {
            CashMemoryLayout opened = CashMemoryLayout.openDefault();
            if (!opened.probeWritable()) {
                startupProblem = "Папка CashMemory недоступна для записи: " + opened.dir();
            }
            return opened;
        } catch (IOException | RuntimeException e) {
            // Программа всё равно открывается: план можно смотреть, а причину пользователь увидит в строке состояния.
            startupProblem = "Не удалось создать папку CashMemory: " + e.getMessage();
            return new CashMemoryLayout(AppPaths.cashMemory());
        }
    }

    // ------------------------------------------------------------------ ветки

    private void crashed(CrashDetector.Detection detection) {
        SwingRecoveryDialog dialog = app.actions().showRecoveryDialog(detection, stores, app.settings().recoveryStore(), choice -> {
            if (choice.restore()) {
                app.actions().restore(choice.store(), app, app.windowFactory(), this::normalStart, report -> {
                    app.setRestoreReport(report);
                    app.actions().showRestoreReport(report);
                    afterStart();
                });
            } else {
                // «Не восстанавливать»: хранилища клиента очищаются до начала записи нового сеанса.
                app.actions().startFresh(stores);
                normalStart();
            }
        });
        selfTest.ifPresent(config -> later(() -> {
            String answer = config.recovery();
            if (dialog.isClosed()) {
                return;
            }
            if ("registry".equals(answer) || "xml".equals(answer)) {
                String problem = dialog.chooseStore(answer);
                if (problem != null) {
                    config.log("SELFTEST 0 FAIL recovery " + answer + ": " + problem);
                    dialog.chooseDoNotRestore();
                }
            } else if (!answer.isBlank()) {
                dialog.chooseDoNotRestore();
            }
        }));
    }

    private void alreadyRunning() {
        SwingAlert alert = app.actions().confirmAlreadyRunning(open -> {
            if (open) {
                // Второй экземпляр не пишет снимки: иначе он затёр бы снимок первого.
                app.recorder().setEnabled(false);
                app.setRecordingNote("Запись сессии отключена: CashPrediction уже запущен");
                normalStart();
            } else {
                // Маркер сеанса не трогаем: он принадлежит работающему первому экземпляру.
                System.exit(0);
            }
        });
        selfTest.ifPresent(config -> later(() -> {
            if (alert.isClosed() || config.recovery().isBlank()) {
                return;
            }
            alert.press("already-ok".equals(config.recovery()) ? AppButtons.OPEN : AppButtons.DO_NOT_OPEN);
        }));
    }

    /** Обычный запуск: окно, план, запись сессии, самотест. */
    private void normalStart() {
        app.showFrame();
        if (startupProblem != null) {
            app.showStatus(startupProblem);
        }
        boolean opened = false;
        Optional<Path> last = lastPlan(app.settings().lastPlan());
        if (last.isPresent()) {
            opened = app.actions().loadPlan(last.get());
        }
        if (!opened) {
            try {
                List<PlanFileInfo> plans = layout.plans().list();
                if (!plans.isEmpty()) {
                    opened = app.actions().loadPlan(plans.getFirst().path());
                }
            } catch (UncheckedIOException e) {
                app.showStatus("Не удалось прочитать папку CashMemory: " + e.getMessage());
            }
        }
        if (!opened) {
            // Планов нет: мастер нового плана; его отмена оставляет пустой несохранённый «Мой план».
            app.actions().startupWizard();
        }
        // Запись сессии — когда состояние главного окна уже можно снять (ядро может сразу записать снимок).
        app.recorder().start();
        afterStart();
    }

    private Optional<Path> lastPlan(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(name);
            Path resolved = path.isAbsolute() ? path : layout.dir().resolve(path);
            return Files.isRegularFile(resolved) ? Optional.of(resolved) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private void afterStart() {
        if (started) {
            return;
        }
        started = true;
        selfTest.ifPresent(config -> new SwingSelfTest(app, config).start());
    }

    private static void later(Runnable action) {
        int delay = Math.max(1, Integer.getInteger(PROP_ANSWER_DELAY, AUTO_ANSWER_DELAY_MS));
        Timer timer = new Timer(delay, e -> Objects.requireNonNull(action).run());
        timer.setRepeats(false);
        timer.start();
    }
}
