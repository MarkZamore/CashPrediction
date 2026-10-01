package ru.cashprediction.core.app.flow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.app.ExitKind;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.session.store.MarkdownSessionStore;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/**
 * Меню «Восстановление» и необработанные ошибки (спецификация v2, §3.5, §6.27, §6.33).
 *
 * <p><b>Необработанная ошибка:</b> снимок сохраняется ({@code recorder.saveNow}); сообщение §6.33 с кнопкой
 * [Закрыть программу] → {@code port.exit(HALT, 2)}; повторная ошибка при открытом сообщении пишется только в stderr;
 * web — ошибка JS не роняет сервер (кнопки [Перезагрузить страницу] [Продолжить работу]).</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class RecoveryFlow {

    private final FlowContext context;
    private boolean fatalAlertOpen;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public RecoveryFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /**
     * {@code recovery.store.registry}/{@code xml}: хранилище по умолчанию для диалога восстановления.
     *
     * @param store хранилище
     */
    public void setDefaultStore(RecoveryStoreKind store) {
        Objects.requireNonNull(store, "store");
        context.updateSettings(settings -> settings.withRecoveryStore(store));
    }

    /** {@code recovery.snapshotNow}: {@code status.msg.snapshot} или {@code info.recordingOff}. */
    public void snapshotNow() {
        if (!recording()) {
            recordingOff();
            return;
        }
        context.recorder().saveNow();
        context.status(StatusLevel.SUCCESS, "status.msg.snapshot");
    }

    /** {@code recovery.showLast}: §6.27 «Последний снимок», хранилище по умолчанию первым. */
    public void showLast() {
        List<SessionStore> ordered = new ArrayList<>(context.recorder() == null
                ? SessionStores.forClient(context.port().profile(), context.environment()) : context.recorder().stores());
        String preferred = context.state().settings().recoveryStore() == RecoveryStoreKind.XML ? "xml" : "registry";
        ordered.sort(Comparator.comparing(store -> !store.id().equals(preferred)));
        List<String> lines = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (SessionStore store : ordered) {
            String saved = store.lastSavedAt().map(time -> UiText.get("s2.recovery.saved",
                    UiFormats.dateTimeSeconds(time.atZone(context.environment().clock().zone()).toLocalDateTime())))
                    .orElseGet(() -> UiText.get("s2.recovery.absent"));
            lines.add(UiText.get("s2.recovery.summary", store.title(), saved));
            details.add(describe(store));
        }
        if (context.port().profile().kind() != ClientKind.WEB) {
            lines.add(UiText.get("s2.recovery.default", UiText.get(context.state().settings().recoveryStore() == RecoveryStoreKind.XML
                    ? "s2.recovery.defaultXml" : "s2.recovery.defaultRegistry")));
        }
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        AlertSpec snapshotSpec = AlertCatalog.lastSnapshot(lines, String.join("\n\n", details));
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        context.showAlert(new AlertSpec(snapshotSpec.kind(), snapshotSpec.purpose(), snapshotSpec.targetId(), snapshotSpec.windowTitle(),
                snapshotSpec.glyph(), UiText.get("s2.recovery.snapshotHeader"), snapshotSpec.content(), snapshotSpec.details(), true,
                snapshotSpec.minWidth(), snapshotSpec.buttons(), snapshotSpec.defaultButtonId(), false), button -> { });
    }

    /** {@code recovery.clear}: подтверждение §6.27, {@code recorder.clearSnapshots}. */
    public void clear() {
        if (!recording()) {
            recordingOff();
            return;
        }
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        context.showAlert(AlertCatalog.clearSnapshots(), button -> {
            if ("clear".equals(button)) {
                context.recorder().clearSnapshots();
                context.status(StatusLevel.INFO, "status.msg.snapshotsCleared");
            }
        });
    }

    /** {@code recovery.simulate.halt}: подтверждение, затем {@code port.exit(HALT, 3)} (web — {@code WEB_CRASHED}). */
    public void simulateHalt() {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        context.showAlert(withText(AlertCatalog.simulateHalt(context.port().profile()),
                UiText.get("s2.recovery.haltContent")), button -> {
            if ("halt".equals(button)) {
                context.port().exit(context.port().profile().kind() == ClientKind.WEB
                        ? ExitKind.WEB_CRASHED : ExitKind.HALT, 3);
            }
        });
    }

    /** {@code recovery.simulate.exception}: бросает исключение в потоке интерфейса → §6.33. */
    public void simulateException() {
        // Нулевая отложенная задача выходит из обработчика команды даже при исполнителе,
        // который выполняет execute() синхронно в своём потоке.
        context.port().scheduler().schedule(() -> context.port().executor().execute(() -> {
            throw new IllegalStateException(UiText.get("s2.recovery.simulatedException"));
        }), Duration.ZERO);
    }

    /**
     * Необработанное исключение (§6.33).
     *
     * @param thread поток
     * @param error  исключение
     */
    public void uncaught(Thread thread, Throwable error) {
        Objects.requireNonNull(error, "error");
        if (fatalAlertOpen) {
            error.printStackTrace(System.err);
            return;
        }
        fatalAlertOpen = true;
        try {
            SessionRecorder recorder = context.recorder();
            if (recorder != null) recorder.saveNow();
        } catch (Throwable saveError) {
            // Сбой снимка не должен помешать сообщению и аварийному завершению.
            saveError.printStackTrace(System.err);
        }
        try {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(withText(AlertCatalog.uncaught(error, false), UiText.get("s2.recovery.errorDescription",
                    error.getClass().getSimpleName(), error.getMessage() == null || error.getMessage().isBlank()
                            ? UiText.get("s2.recovery.noDescription") : error.getMessage())),
                    button -> context.port().exit(ExitKind.HALT, 2));
        } catch (Throwable alertError) {
            error.printStackTrace(System.err);
            alertError.printStackTrace(System.err);
            context.port().exit(ExitKind.HALT, 2);
        }
    }

    /** Проверяет, что запись действительно работает, включая ожидание сохранения исходного плана. */
    private boolean recording() {
        SessionRecorder recorder = context.recorder();
        return recorder != null && recorder.isStarted() && recorder.isEnabled() && !recorder.isClosed();
    }

    /** Показывает причину недоступности записи. */
    private void recordingOff() {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        context.showAlert(AlertCatalog.info("recordingOff"), button -> { });
    }

    /** Возвращает исходный файл или JSON с точным путём выбранного хранилища. */
    private String describe(SessionStore store) {
        if (store instanceof XmlSessionStore xml) {
            return UiText.get("s2.recovery.fileBlock", store.title(), xml.file(), readFile(xml.file()));
        }
        if (store instanceof MarkdownSessionStore markdown) {
            return UiText.get("s2.recovery.fileBlock", store.title(), markdown.sessionFile(), readFile(markdown.sessionFile()));
        }
        String content;
        if (!store.isAvailable()) {
            content = UiText.get("s2.recovery.registryUnavailable", store.unavailableReason());
        } else {
            try {
                content = store.load().map(snapshot -> JsonWriter.writePretty(new JsonSnapshotCodec().toJsonObject(snapshot)))
                        .orElseGet(() -> UiText.get("s2.recovery.noSnapshot"));
            } catch (SessionStoreException | RuntimeException e) {
                content = UiText.get("s2.recovery.unreadableSnapshot", reason(e));
            }
        }
        if (store instanceof RegistrySessionStore registry) {
            return UiText.get("s2.recovery.registryBlock", store.title(), registry.nodePath().replace('/', '\\'), content);
        }
        return UiText.get("s2.recovery.block", store.title(), content);
    }

    /** Читает файл без потери повреждённого текста, чтобы пользователь мог его исследовать. */
    private String readFile(Path path) {
        try {
            return Files.exists(path) ? AtomicFiles.readString(path) : UiText.get("s2.recovery.noFile");
        } catch (IOException | RuntimeException e) {
            return UiText.get("s2.recovery.unreadableFile", reason(e));
        }
    }

    /** Подставляет имя класса, если у исключения нет описания. */
    private static String reason(Exception error) {
        return Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
    }

    /** Дополняет описание сообщения, сохраняя общий набор кнопок. */
    private static AlertSpec withText(AlertSpec base, String content) {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        return new AlertSpec(base.kind(), base.purpose(), base.targetId(), base.windowTitle(), base.glyph(),
                base.purpose().equals("uncaught") ? UiText.get("s2.recovery.uncaughtHeader") : base.header(),
                content, base.details(), base.detailsExpanded(), base.minWidth(), base.buttons(), base.defaultButtonId(), base.restorable());
    }
}
