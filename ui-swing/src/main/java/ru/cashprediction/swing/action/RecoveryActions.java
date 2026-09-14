package ru.cashprediction.swing.action;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.RestoreCoordinator;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.RestoreTarget;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.WindowFactory;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingFileChoosers;
import ru.cashprediction.swing.dialog.SwingRecoveryDialog;

/**
 * Команды меню «Восстановление» и шаги запуска после сбоя: хранилище по умолчанию, снимок сейчас, просмотр
 * последнего снимка, очистка снимков, симуляция сбоя, диалог 17 «Восстановление», само восстановление и вопрос
 * «уже запущен».
 *
 * <p>«Очистить снимки» вызывает {@code SessionRecorder.clearSnapshots()}, но никогда {@code SessionStore.clear()}:
 * тот удалил бы и маркер текущего сеанса, и следующий сбой был бы принят за корректный выход.
 * {@code RestoreCoordinator.startFresh} (то есть {@code clear()}) вызывается только на «Не восстанавливать» — до
 * начала записи.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
final class RecoveryActions extends ActionSupport {

    /** Код завершения процесса при симуляции аварийного завершения. */
    public static final int HALT_SIMULATED = 3;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Создаёт команды восстановления.
     *
     * @param shared общие объекты команд
     */
    RecoveryActions(ActionShared shared) {
        super(shared);
    }

    // ------------------------------------------------------------------ меню «Восстановление»

    /**
     * «Хранилище по умолчанию»: какую кнопку диалога восстановления сделать основной.
     *
     * @param kind реестр или XML
     */
    public void setDefaultStore(RecoveryStoreKind kind) {
        context().updateSettings(s -> s.withRecoveryStore(kind));
    }

    /**
     * «Сделать снимок сейчас»: синхронная запись во все хранилища.
     */
    public void snapshotNow() {
        if (!recorder().isStarted() || !recorder().isEnabled()) {
            alerts().info("Запись сессии не ведётся", recorder().isEnabled()
                    ? "Запись сессии ещё не начата." : "Программа открыта вторым экземпляром: снимки пишет первый экземпляр.");
            return;
        }
        recorder().saveNow();
        status("Снимок сессии записан");
    }

    /**
     * «Показать последний снимок…»: текст XML-файла и JSON снимка из реестра в раскрытой области «Подробнее»;
     * хранилище по умолчанию показывается первым.
     */
    public void showLastSnapshot() {
        List<String> parts = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        List<SessionStore> ordered = new ArrayList<>(recorder().stores());
        if (context().settings().recoveryStore() == RecoveryStoreKind.XML) {
            // Сортировка устойчива: XML-хранилище поднимается наверх, порядок остальных не меняется.
            ordered.sort((a, b) -> Boolean.compare(!(a instanceof XmlSessionStore), !(b instanceof XmlSessionStore)));
        }
        for (SessionStore store : ordered) {
            summary.add(store.title() + ": " + store.lastSavedAt().map(TIME::format).map(t -> "сохранено " + t).orElse("снимка нет"));
            if (store instanceof XmlSessionStore xml) {
                parts.add("=== " + store.title() + " - " + xml.file() + " ===\n" + readXml(xml));
            } else if (store instanceof RegistrySessionStore) {
                parts.add("=== " + store.title() + " - HKCU\\Software\\JavaSoft\\Prefs\\ru\\cashprediction\\session\\"
                        + SnapshotSchema.CLIENT_SWING + " (JSON) ===\n" + readRegistry(store));
            } else {
                parts.add("=== " + store.title() + " ===");
            }
        }
        // JavaFX: Alert + expandableContent → Swing: SwingAlert + «Подробнее» (JTextArea) → Web: <dialog class="alert"> с <pre>
        alerts().infoWithDetails("Последний снимок сессии", "Последний снимок сессии",
                String.join("\n", summary) + "\nХранилище по умолчанию: " + context().settings().recoveryStore().label() + ".",
                String.join("\n\n", parts));
    }

    private static String readXml(XmlSessionStore store) {
        try {
            return Files.exists(store.file()) ? Files.readString(store.file(), StandardCharsets.UTF_8) : "(файла нет)";
        } catch (IOException e) {
            return "(не удалось прочитать файл: " + e.getMessage() + ")";
        }
    }

    private static String readRegistry(SessionStore store) {
        if (!store.isAvailable()) {
            return "(реестр недоступен: " + store.unavailableReason() + ")";
        }
        try {
            Optional<SessionSnapshot> snapshot = store.load();
            return snapshot.map(s -> JsonWriter.writePretty(new JsonSnapshotCodec().toJsonObject(s))).orElse("(снимка нет)");
        } catch (SessionStoreException e) {
            return "(снимок не читается: " + e.getMessage() + ")";
        }
    }

    /**
     * «Очистить снимки»: подтверждение ({@code ALERT}, назначение {@code clearSnapshots}), затем
     * {@code recorder.clearSnapshots()} — снимки удаляются, маркер текущего сеанса ставится заново.
     *
     * @param request как открыть окно
     */
    public void clearSnapshots(OpenRequest request) {
        // JavaFX: Alert(CONFIRMATION) + ButtonType("Очистить", OK_DONE) → Swing: SwingAlert + SwingButtonType → Web: <dialog class="alert">
        SwingAlert alert = new SwingAlert(owner(request), SwingAlert.AlertType.CONFIRMATION, "Очистить снимки",
                "Удалить сохранённые снимки сеанса из реестра и XML-файла?",
                "Запись сеанса продолжится: следующий снимок появится при первом же изменении.",
                AppButtons.CLEAR, AppButtons.CANCEL);
        alert.makeRestorable(request.ownerId(), Purposes.CLEAR_SNAPSHOTS, "");
        alert.setOnResult(result -> {
            if (result.filter(AppButtons.CLEAR::equals).isPresent()) {
                recorder().clearSnapshots();
                status("Снимки сессии очищены");
            }
        });
        show(alert, request);
    }

    /**
     * «Симулировать сбой → Аварийное завершение процесса»: подтверждение, затем {@code Runtime.halt(3)} без
     * сохранения плана, снимка и отметки о корректном выходе.
     */
    public void simulateHalt() {
        // JavaFX: Alert(WARNING) → Swing: SwingAlert (JOptionPane.WARNING_MESSAGE) → Web: <dialog class="alert">
        SwingAlert alert = alerts().create(SwingAlert.AlertType.WARNING, "Симуляция сбоя",
                "Завершить процесс аварийно, без сохранения?",
                "Процесс будет остановлен немедленно (Runtime.halt). При следующем запуске появится диалог восстановления: "
                        + "снимок в реестре и XML-файле записан не позже чем 0,4-5 секунд назад.",
                AppButtons.HALT, AppButtons.CANCEL);
        alerts().show(alert, result -> {
            if (result.filter(AppButtons.HALT::equals).isPresent()) {
                // Именно halt: ни shutdown hook, ни saveNow — как при настоящем сбое.
                Runtime.getRuntime().halt(HALT_SIMULATED);
            }
        });
    }

    /**
     * «Симулировать сбой → Необработанное исключение»: бросает исключение в потоке EDT.
     */
    public void simulateException() {
        // invokeLater: исключение вылетает из цикла событий Swing, а не из обработчика меню, — проверяется именно
        // путь «необработанное исключение UI-потока» (обработчик по умолчанию → снимок → сообщение → halt(2)).
        SwingUtilities.invokeLater(() -> {
            throw new IllegalStateException("Симуляция сбоя: необработанное исключение в потоке интерфейса (меню Восстановление)");
        });
    }

    // ------------------------------------------------------------------ запуск после сбоя

    /**
     * Диалог 17 «Восстановление» до главного окна.
     *
     * @param detection результат {@code CrashDetector.detect}
     * @param stores    хранилища клиента в порядке «реестр, XML»
     * @param preferred хранилище по умолчанию из настроек
     * @param onChoice  выбор пользователя; крестик окна и Esc равносильны «Не восстанавливать»
     * @return показанный диалог (самотест отвечает на него программно)
     */
    public SwingRecoveryDialog showRecoveryDialog(CrashDetector.Detection detection, List<SessionStore> stores,
                                                  RecoveryStoreKind preferred, Consumer<SwingRecoveryDialog.Choice> onChoice) {
        SwingRecoveryDialog dialog = new SwingRecoveryDialog(frame(), detection, stores, preferred);
        dialog.setOnResult(result -> onChoice.accept(result.orElse(new SwingRecoveryDialog.Choice(null))));
        host().show(dialog);
        return dialog;
    }

    /**
     * Восстанавливает сессию из выбранного хранилища: загрузка снимка и {@code RestoreCoordinator.restore}.
     *
     * <p>Если в отчёте есть {@link RestoreCoordinator#RECORDER_NOT_STARTED} (несохранённый план из снимка не
     * открылся), пользователю предлагается сохранить текст плана из снимка в файл, и только затем запускается
     * запись сессии — иначе первая же запись заменила бы единственную копию несохранённого плана.</p>
     *
     * @param store        хранилище, выбранное в диалоге восстановления
     * @param target       главное окно
     * @param factory      фабрика окон
     * @param onLoadFailed вызывается после сообщения об ошибке, если снимок не читается (обычно — обычный запуск)
     * @param done         отчёт восстановления (после показа всех окон и, при необходимости, вопроса о плане)
     */
    public void restore(SessionStore store, RestoreTarget target, WindowFactory factory, Runnable onLoadFailed,
                        Consumer<RestoreReport> done) {
        SessionSnapshot snapshot;
        try {
            snapshot = store.load().orElseThrow(() -> new SessionStoreException("в хранилище нет снимка"));
        } catch (SessionStoreException | RuntimeException e) {
            SwingAlert alert = alerts().create(SwingAlert.AlertType.ERROR, "CashPrediction - восстановление",
                    "Не удалось прочитать снимок: " + store.title(),
                    Objects.requireNonNullElse(e.getMessage(), e.toString()) + "\nПрограмма откроется без восстановления.");
            alerts().show(alert, r -> onLoadFailed.run());
            return;
        }
        new RestoreCoordinator().restore(snapshot, target, factory, recorder(), report -> {
            if (report.warnings().contains(RestoreCoordinator.RECORDER_NOT_STARTED) && !recorder().isStarted()) {
                offerSavingSnapshotPlan(snapshot, () -> {
                    recorder().start();
                    recorder().touch();
                    done.accept(report);
                });
            } else {
                done.accept(report);
            }
        });
    }

    /** Предлагает сохранить текст несохранённого плана из снимка в файл, затем выполняет {@code then}. */
    private void offerSavingSnapshotPlan(SessionSnapshot snapshot, Runnable then) {
        String markdown = snapshot.plan().markdown();
        // JavaFX: Alert(WARNING) + expandableContent → Swing: SwingAlert + «Подробнее» → Web: баннер с <pre>
        SwingAlert alert = alerts().create(SwingAlert.AlertType.WARNING, "CashPrediction - восстановление",
                "Несохранённый план из снимка не открылся",
                "Текст плана из снимка можно сохранить в файл .md и открыть позже или поправить в Блокноте.\n"
                        + "После этого вопроса начнётся запись сессии, и снимок заменится текущим состоянием.",
                AppButtons.SAVE_PLAN_FILE, AppButtons.CONTINUE);
        alert.setDetailsText(markdown);
        alerts().show(alert, result -> {
            if (result.filter(AppButtons.SAVE_PLAN_FILE::equals).isEmpty()) {
                then.run();
                return;
            }
            String name = markdown.lines().findFirst().flatMap(PlanMarkdownReader::titleName).orElse("План из снимка");
            // JavaFX: FileChooser.showSaveDialog + ExtensionFilter("*.md") → Swing: JFileChooser.showSaveDialog + FileNameExtensionFilter → Web: скачивание .md
            Optional<Path> chosen = SwingFileChoosers.saveMarkdown(frame(), "Сохранить план из снимка",
                    context().layout().dir(), PlanRepository.fileBaseName(name) + PlanRepository.EXTENSION);
            if (chosen.isEmpty()) {
                // Отказ от выбора файла — ещё не согласие потерять план: спрашиваем снова.
                offerSavingSnapshotPlan(snapshot, then);
                return;
            }
            try {
                Files.writeString(chosen.get(), markdown, StandardCharsets.UTF_8);
                status("План из снимка сохранён: " + chosen.get().getFileName());
                then.run();
            } catch (IOException e) {
                SwingAlert error = alerts().create(SwingAlert.AlertType.ERROR, "CashPrediction - ошибка",
                        "Не удалось сохранить план в «" + chosen.get() + "»", Objects.requireNonNullElse(e.getMessage(), e.toString()));
                alerts().show(error, r -> offerSavingSnapshotPlan(snapshot, then));
            }
        });
    }

    /**
     * «Не восстанавливать»: очищает маркеры и снимки всех хранилищ клиента. Вызывается только до
     * {@code recorder.start()}.
     *
     * @param stores хранилища клиента
     */
    public void startFresh(List<SessionStore> stores) {
        RestoreCoordinator.startFresh(stores);
    }

    /**
     * Вопрос при втором экземпляре: «CashPrediction уже запущен. Открыть без восстановления и без записи сессии?».
     *
     * @param onAnswer {@code true} — открыть (вызывающий отключает рекордер), {@code false} — выйти
     * @return показанное сообщение (самотест отвечает на него программно)
     */
    public SwingAlert confirmAlreadyRunning(Consumer<Boolean> onAnswer) {
        // JavaFX: Alert(CONFIRMATION) → Swing: SwingAlert (JOptionPane.QUESTION_MESSAGE) → Web: <dialog class="alert">
        SwingAlert alert = new SwingAlert(frame(), SwingAlert.AlertType.CONFIRMATION, "CashPrediction",
                "CashPrediction уже запущен. Открыть без восстановления и без записи сессии?",
                "Снимки сессии пишет первый экземпляр программы. Этот экземпляр не будет сохранять окна и введённые "
                        + "значения, чтобы не затереть их.",
                AppButtons.OPEN, AppButtons.DO_NOT_OPEN);
        alerts().show(alert, result -> onAnswer.accept(result.filter(AppButtons.OPEN::equals).isPresent()));
        return alert;
    }

    /**
     * Показывает замечания отчёта восстановления, если они есть (например, «окно не восстановлено: правила нет»).
     *
     * @param report отчёт координатора
     */
    public void showRestoreReport(RestoreReport report) {
        if (report == null || report.clean()) {
            return;
        }
        alerts().warning("Сессия восстановлена с замечаниями",
                "Восстановлено окон: " + report.windowsRestored() + ". Замечаний: " + report.warnings().size() + ".",
                String.join("\n", report.warnings()));
    }
}
