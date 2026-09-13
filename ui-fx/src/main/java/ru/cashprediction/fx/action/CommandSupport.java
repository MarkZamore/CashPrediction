package ru.cashprediction.fx.action;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.stage.Window;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.fx.dialog.Dialogs;
import ru.cashprediction.fx.dialog.FxDialogHost;
import ru.cashprediction.fx.dialog.OpenRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Общие средства групп команд: контекст приложения, показ сообщений, безопасное изменение плана,
 * текущая папка планов.
 *
 * <p>Только FX Application Thread.</p>
 */
final class CommandSupport {

    private final FxAppContext context;
    private final FxDialogHost host;
    /** Папка, из которой открываются планы в этом сеансе; {@code null} — CashMemory. */
    private Path browseFolder;

    CommandSupport(FxAppContext context, FxDialogHost host) {
        this.context = Objects.requireNonNull(context, "context");
        this.host = Objects.requireNonNull(host, "host");
    }

    /** @return контекст приложения */
    FxAppContext context() {
        return context;
    }

    /** @return хост диалогов */
    FxDialogHost host() {
        return host;
    }

    /** @return документ плана */
    PlanDocument document() {
        return context.document();
    }

    /** @return текущий план */
    Plan plan() {
        return context.document().plan();
    }

    /** @return окно-владелец для нативных окон выбора файла (может быть {@code null}) */
    Window ownerWindow() {
        return host.mainWindow();
    }

    /** @return папка, из которой сейчас открываются планы (CashMemory или выбранная в этом сеансе) */
    Path plansFolder() {
        return browseFolder != null ? browseFolder : context.layout().dir();
    }

    /**
     * Меняет папку, из которой открываются планы, на время сеанса.
     *
     * @param folder папка или {@code null}, чтобы вернуться к CashMemory
     */
    void setBrowseFolder(Path folder) {
        browseFolder = folder == null || folder.equals(context.layout().dir()) ? null : folder;
    }

    /** @return выбрана ли папка, отличная от CashMemory */
    boolean browsingOtherFolder() {
        return browseFolder != null;
    }

    /**
     * Имя плана для настроек: имя файла, если он лежит в CashMemory, иначе полный путь.
     *
     * @param file файл плана
     * @return строка для «Последний план»/«Недавние планы»
     */
    String settingsName(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path dir = context.layout().dir().toAbsolutePath().normalize();
        return dir.equals(absolute.getParent()) ? absolute.getFileName().toString() : absolute.toString();
    }

    /**
     * Изменяет план одним шагом истории; ошибку модели показывает пользователю вместо аварии.
     *
     * @param description описание для «Отменить …»
     * @param change      изменение
     * @return {@code true}, если изменение применено без исключения
     */
    boolean edit(String description, UnaryOperator<Plan> change) {
        try {
            context.document().edit(description, change);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            error("Изменение не применено", Objects.requireNonNullElse(e.getMessage(), e.toString()));
            return false;
        }
    }

    /**
     * Информационное сообщение поверх главного окна.
     *
     * @param header  крупный текст
     * @param content пояснение
     */
    void info(String header, String content) {
        // JavaFX: Alert(INFORMATION) → Swing: JOptionPane.showMessageDialog(INFORMATION_MESSAGE) → Web: <dialog class="alert">
        host.show(Dialogs.alert(AlertType.INFORMATION, "CashPrediction", header, content), WindowState.MAIN_OWNER, r -> { });
    }

    /**
     * Диалог 16 «Ошибка» с понятной причиной.
     *
     * @param header  что не получилось
     * @param message почему
     */
    void error(String header, String message) {
        // JavaFX: Alert(ERROR) → Swing: JOptionPane.showMessageDialog(ERROR_MESSAGE) → Web: <dialog class="alert error">
        host.show(Dialogs.error(header, message), WindowState.MAIN_OWNER, r -> { });
    }

    /**
     * Диалог 16 «Ошибка» со стеком в подробностях.
     *
     * @param header что не получилось
     * @param error  исключение
     */
    void error(String header, Throwable error) {
        // JavaFX: Alert(ERROR) + expandableContent → Swing: JOptionPane.showMessageDialog + JTextArea со стеком → Web: <dialog class="alert error"> с <details>
        host.show(Dialogs.error(header, error), WindowState.MAIN_OWNER, r -> { });
    }

    /**
     * Сообщает, что окно открыть нельзя: при восстановлении — координатору, иначе — сообщением об ошибке.
     *
     * @param request запрос открытия
     * @param header  что не получилось
     * @param reason  почему
     */
    void cannotOpen(OpenRequest request, String header, String reason) {
        if (request.isRestore()) {
            request.fail(reason);
        } else {
            error(header, reason);
        }
    }

    /**
     * Диалог 15 «Диагностика» после чтения файла с замечаниями.
     *
     * @param fileName    имя файла для заголовка
     * @param diagnostics замечания читателя
     */
    void showLoadDiagnostics(String fileName, List<Diagnostic> diagnostics) {
        if (diagnostics.stream().noneMatch(d -> d.severity() != Severity.INFO)) {
            return;
        }
        long errors = diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).count();
        String details = diagnostics.stream().map(Diagnostic::format).collect(Collectors.joining("\n"));
        // JavaFX: Alert(WARNING) + expandableContent → Swing: JOptionPane.showMessageDialog(WARNING_MESSAGE) + JTextArea → Web: <dialog class="alert"> с <pre>
        Alert alert = Dialogs.withDetails(AlertType.WARNING, "Диагностика", "Файл «" + fileName + "» прочитан с замечаниями",
                (errors > 0
                        ? "Часть строк не разобрана: они перенесены в заметку плана и не участвуют в прогнозе. "
                        : "")
                        + "Нераспознанный текст сохранится в файле при следующем сохранении. Подробности ниже.",
                details);
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        alert.getDialogPane().setExpanded(true);
        host.show(alert, WindowState.MAIN_OWNER, r -> { });
    }
}
