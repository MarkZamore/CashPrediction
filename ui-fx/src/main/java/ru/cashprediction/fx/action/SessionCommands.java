package ru.cashprediction.fx.action;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.RestoreCoordinator;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.fx.dialog.AppButtonTypes;
import ru.cashprediction.fx.dialog.Dialogs;
import ru.cashprediction.fx.dialog.FxRecoveryDialog;
import ru.cashprediction.fx.dialog.OpenRequest;
import ru.cashprediction.fx.dialog.RecoveryChoice;
import ru.cashprediction.fx.dialog.StatefulAlert;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Команды меню «Восстановление»: снимок сейчас, просмотр последнего снимка, очистка снимков и симуляция сбоя.
 *
 * <p>«Очистить снимки» вызывает только {@code SessionRecorder.clearSnapshots()}: прямой {@code SessionStore.clear()}
 * посреди сеанса удалил бы маркер {@code running}, и следующий сбой был бы принят за корректный выход.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
final class SessionCommands {

    /** Идентификатор хранилища реестра. */
    private static final String REGISTRY_ID = "registry";
    /** Код завершения процесса при симуляции аварии. */
    static final int HALT_SIMULATED = 3;

    private final CommandSupport support;

    SessionCommands(CommandSupport support) {
        this.support = support;
    }

    /** «Сделать снимок сейчас»: синхронная запись во все хранилища. */
    void snapshotNow() {
        support.context().recorder().saveNow();
    }

    /** «Показать последний снимок…»: текст XML-файла или JSON снимка из реестра — по хранилищу по умолчанию. */
    void showLastSnapshot() {
        RecoveryStoreKind kind = support.context().settings().recoveryStore();
        String header;
        String details;
        if (kind == RecoveryStoreKind.XML) {
            Path file = support.context().layout().sessionXml(SnapshotSchema.CLIENT_FX);
            header = "Снимок сеанса в XML-файле";
            try {
                details = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "Файла пока нет: " + file;
            } catch (IOException e) {
                details = "Не удалось прочитать " + file + ": " + e.getMessage();
            }
            header += "\n" + file;
        } else {
            header = "Снимок сеанса в реестре Windows\nHKCU\\Software\\JavaSoft\\Prefs\\ru\\cashprediction\\session\\fx";
            details = registryText();
        }
        // JavaFX: Alert → Swing: JOptionPane.showMessageDialog + JTextArea → Web: <dialog class="alert"> с <pre>
        Alert alert = Dialogs.withDetails(AlertType.INFORMATION, "Последний снимок", header,
                "Хранилище по умолчанию: " + kind.label() + ". Сменить: Восстановление → Хранилище по умолчанию.", details);
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        alert.getDialogPane().setExpanded(true);
        support.host().show(alert, WindowState.MAIN_OWNER, r -> { });
    }

    private String registryText() {
        for (SessionStore store : support.context().recorder().stores()) {
            if (!REGISTRY_ID.equals(store.id())) {
                continue;
            }
            if (!store.isAvailable()) {
                return "Реестр недоступен: " + store.unavailableReason();
            }
            try {
                Optional<SessionSnapshot> snapshot = store.load();
                return snapshot.map(s -> JsonWriter.writePretty(new JsonSnapshotCodec().toJsonObject(s)))
                        .orElse("В реестре пока нет снимка.");
            } catch (SessionStoreException e) {
                return "Снимок в реестре не читается: " + e.getMessage();
            }
        }
        return "Хранилище реестра не подключено.";
    }

    /**
     * «Очистить снимки»: подтверждение, затем {@code recorder.clearSnapshots()}.
     *
     * @param request запрос открытия
     */
    void clearSnapshots(OpenRequest request) {
        // JavaFX: Alert → Swing: JOptionPane.showConfirmDialog → Web: <dialog class="alert">
        StatefulAlert alert = new StatefulAlert(AlertType.CONFIRMATION, "clearSnapshots", null, "Очистить снимки",
                "Удалить сохранённые снимки сеанса из реестра и XML-файла?",
                "Запись сеанса продолжится: следующий снимок появится при первом же изменении.",
                AppButtonTypes.CLEAR, AppButtonTypes.CANCEL);
        support.host().open(alert, request, result -> {
            if (result.orElse(AppButtonTypes.CANCEL) == AppButtonTypes.CLEAR) {
                support.context().recorder().clearSnapshots();
            }
        });
    }

    /** «Аварийное завершение процесса»: подтверждение, затем {@code Runtime.halt(3)} без сохранения. */
    void simulateHalt() {
        // JavaFX: Alert → Swing: JOptionPane.showConfirmDialog → Web: POST /api/shutdown?crash
        Alert alert = Dialogs.alert(AlertType.WARNING, "Симуляция сбоя",
                "Завершить процесс аварийно, без сохранения?",
                "Процесс будет убит немедленно (Runtime.halt). При следующем запуске появится диалог восстановления: "
                        + "снимок в реестре и XML-файле записан не позже чем 0,4–5 секунд назад.",
                AppButtonTypes.HALT, AppButtonTypes.CANCEL);
        support.host().show(alert, WindowState.MAIN_OWNER, result -> {
            if (result.orElse(AppButtonTypes.CANCEL) == AppButtonTypes.HALT) {
                Runtime.getRuntime().halt(HALT_SIMULATED);
            }
        });
    }

    // ------------------------------------------------------------------ запуск: сбой, второй экземпляр

    /**
     * Диалог 17 «Восстановление» до главного окна.
     *
     * <p>Главное окно ещё не показано, поэтому окно модально для всего приложения. Внимание оболочке: пока
     * это единственное окно, нужно {@code Platform.setImplicitExit(false)}, иначе закрытие диалога (последнего
     * окна) завершило бы JavaFX раньше, чем покажется главное окно.</p>
     *
     * @param detection результат {@code CrashDetector.detect} со статусом {@code CRASHED}
     * @param choice    получатель выбора; закрытие крестиком — {@link RecoveryChoice#NONE}
     */
    void recovery(CrashDetector.Detection detection, Consumer<RecoveryChoice> choice) {
        // JavaFX: Dialog<R> → Swing: SwingRecoveryDialog (JOptionPane.showOptionDialog) → Web: баннер восстановления
        FxRecoveryDialog dialog = new FxRecoveryDialog(detection, support.context().settings().recoveryStore());
        support.host().show(dialog, WindowState.MAIN_OWNER, result -> choice.accept(result.orElse(RecoveryChoice.NONE)));
    }

    /**
     * Загружает снимок из выбранного хранилища для {@code RestoreCoordinator.restore}.
     *
     * @param choice выбор пользователя в диалоге восстановления ({@code REGISTRY} или {@code XML})
     * @return снимок; пусто, если выбрано «Не восстанавливать» или снимка нет
     * @throws SessionStoreException если снимок повреждён или хранилище не читается (сообщение по-русски)
     */
    Optional<SessionSnapshot> loadSnapshot(RecoveryChoice choice) throws SessionStoreException {
        String storeId = switch (choice) {
            case REGISTRY -> FxRecoveryDialog.REGISTRY_STORE_ID;
            case XML -> FxRecoveryDialog.XML_STORE_ID;
            case NONE -> null;
        };
        if (storeId == null) {
            return Optional.empty();
        }
        for (SessionStore store : support.context().recorder().stores()) {
            if (storeId.equals(store.id())) {
                return store.load();
            }
        }
        throw new SessionStoreException("Хранилище «" + storeId + "» не подключено");
    }

    /**
     * «CashPrediction уже запущен. Открыть без восстановления и без записи сессии?»
     *
     * <p>При согласии рекордер отключается ({@code setEnabled(false)}): снимки и маркер принадлежат первому
     * экземпляру, и второй не должен их затирать.</p>
     *
     * @param openAnyway {@code true} — продолжить обычный запуск; {@code false} — выйти из программы
     */
    void alreadyRunning(Consumer<Boolean> openAnyway) {
        // JavaFX: Alert → Swing: JOptionPane.showConfirmDialog → Web: не нужен (одна сессия на сервер)
        Alert alert = Dialogs.alert(AlertType.CONFIRMATION, "CashPrediction",
                "CashPrediction уже запущен. Открыть без восстановления и без записи сессии?",
                "Второе окно программы не будет сохранять снимки сеанса, чтобы не мешать первому. "
                        + "Несохранённые правки во втором окне после сбоя не восстановятся.",
                AppButtonTypes.OPEN_WITHOUT_SESSION, AppButtonTypes.EXIT);
        support.host().show(alert, WindowState.MAIN_OWNER, result -> {
            boolean open = result.orElse(AppButtonTypes.EXIT) == AppButtonTypes.OPEN_WITHOUT_SESSION;
            if (open) {
                support.context().recorder().setEnabled(false);
            }
            openAnyway.accept(open);
        });
    }

    /**
     * Завершает восстановление по отчёту координатора.
     *
     * <p>Если в отчёте есть {@link RestoreCoordinator#RECORDER_NOT_STARTED}, несохранённый текст плана остался
     * только в снимке: пользователю предлагается сохранить его в файл (окно выбора файла), и лишь потом запускается
     * запись сеанса ({@code recorder.start()}), которая этот снимок перезапишет. Остальные предупреждения показываются
     * одним сообщением с подробностями.</p>
     *
     * @param snapshot снимок, из которого восстанавливались
     * @param report   отчёт {@code RestoreCoordinator}
     * @param done     вызывается, когда пользователь ответил на все вопросы (может быть {@code null})
     */
    void restoreFinished(SessionSnapshot snapshot, RestoreReport report, Runnable done) {
        Runnable finish = done == null ? () -> { } : done;
        List<String> others = report.warnings().stream()
                .filter(w -> !RestoreCoordinator.RECORDER_NOT_STARTED.equals(w)).toList();
        Runnable showOthers = () -> {
            if (!others.isEmpty()) {
                // JavaFX: Alert → Swing: JOptionPane.showMessageDialog(WARNING_MESSAGE) → Web: баннер с предупреждениями
                Alert alert = Dialogs.withDetails(AlertType.WARNING, "Восстановление сеанса",
                        "Сеанс восстановлен с замечаниями",
                        "Восстановлено окон: " + report.windowsRestored() + ". Подробности — ниже.", String.join("\n", others));
                // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
                alert.getDialogPane().setExpanded(true);
                support.host().show(alert, WindowState.MAIN_OWNER, r -> finish.run());
            } else {
                finish.run();
            }
        };
        if (report.warnings().contains(RestoreCoordinator.RECORDER_NOT_STARTED)) {
            offerSaveSnapshotPlan(snapshot, () -> {
                SessionRecorder recorder = support.context().recorder();
                recorder.start();
                recorder.touch();
                showOthers.run();
            });
        } else {
            showOthers.run();
        }
    }

    /**
     * Предлагает сохранить текст несохранённого плана из снимка в файл (окно выбора файла).
     *
     * @param snapshot снимок с {@code plan.dirty() == true}
     * @param then     что сделать после ответа пользователя (сохранил, отказался или сохранение не удалось)
     */
    private void offerSaveSnapshotPlan(SessionSnapshot snapshot, Runnable then) {
        String markdown = snapshot.plan().markdown();
        if (markdown == null || markdown.isBlank()) {
            then.run();
            return;
        }
        // JavaFX: Alert → Swing: JOptionPane.showOptionDialog → Web: не нужен (план хранится в web-session.plan.md)
        Alert alert = Dialogs.withDetails(AlertType.WARNING, "Восстановление сеанса",
                "Несохранённый план из снимка не удалось открыть",
                "Его текст сохранился только в снимке сеанса. Сохраните его в файл, чтобы не потерять: "
                        + "как только начнётся запись нового сеанса, снимок будет перезаписан. Текст плана — ниже.",
                markdown);
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        alert.getButtonTypes().setAll(AppButtonTypes.SAVE_SNAPSHOT_PLAN, AppButtonTypes.SKIP);
        support.host().show(alert, WindowState.MAIN_OWNER, result -> {
            ButtonType button = result.orElse(AppButtonTypes.SKIP);
            if (button == AppButtonTypes.SAVE_SNAPSHOT_PLAN) {
                saveMarkdownToChosenFile(markdown);
            }
            then.run();
        });
    }

    private void saveMarkdownToChosenFile(String markdown) {
        String firstLine = markdown.lines().findFirst().orElse("");
        String name = PlanMarkdownReader.titleName(firstLine).orElse("Восстановленный план");
        // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: не нужен
        File chosen = Dialogs.planChooser("Сохранить план из снимка", support.context().layout().dir(),
                PlanRepository.fileBaseName(name + " (восстановлен)") + PlanRepository.EXTENSION)
                .showSaveDialog(support.ownerWindow());
        if (chosen == null) {
            return;
        }
        try {
            // Путь выбрал сам пользователь, поэтому запись вне CashMemory здесь допустима.
            Files.writeString(chosen.toPath(), markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            support.error("Не удалось сохранить план из снимка", e);
        }
    }

    /** «Необработанное исключение»: бросает исключение в UI-потоке — срабатывает обычный обработчик сбоя. */
    void simulateException() {
        // runLater: исключение должно вылететь из цикла событий JavaFX, а не из обработчика меню,
        // чтобы проверить именно путь «необработанное исключение UI-потока».
        Platform.runLater(() -> {
            throw new IllegalStateException("Симуляция сбоя: необработанное исключение в UI-потоке (меню Восстановление)");
        });
    }
}
