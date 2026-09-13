package ru.cashprediction.fx.dialog;

import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Фабрики стандартных окон: сообщения ({@link Alert}), выбор файла ({@link FileChooser}) и папки
 * ({@link DirectoryChooser}).
 *
 * <p>Здесь окна только создаются; показывает их {@link FxDialogHost} (сообщения) или сам вызывающий код
 * (нативные окна выбора файла, которые Windows показывает модально и которые в снимок сессии не входят:
 * их состояние программе недоступно).</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class Dialogs {

    /** Фильтр планов для окон выбора файла. */
    private static final String MD_DESCRIPTION = "План CashPrediction (*.md)";
    /** Подпись ссылки раскрытия подробностей, пока они скрыты. */
    private static final String DETAILS_MORE = "Подробности";
    /** Подпись ссылки раскрытия подробностей, пока они показаны. */
    private static final String DETAILS_LESS = "Скрыть подробности";
    /** Метка в свойствах ссылки: русская подпись уже подключена (окно могут показать повторно). */
    private static final String DETAILS_LOCALIZED = "cashprediction.detailsLocalized";

    private Dialogs() {
    }

    /**
     * Сообщение с заданными кнопками.
     *
     * @param type    вид сообщения
     * @param title   заголовок окна
     * @param header  крупный текст
     * @param content пояснение
     * @param buttons кнопки; без кнопок — одна «OK»
     * @return сообщение (не показано)
     */
    public static Alert alert(AlertType type, String title, String header, String content, ButtonType... buttons) {
        // JavaFX: Alert → Swing: JOptionPane.showMessageDialog/showConfirmDialog/showOptionDialog → Web: <dialog class="alert">
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        Alert alert = new Alert(type, content, buttons.length == 0 ? new ButtonType[]{AppButtonTypes.OK} : buttons);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setResizable(true);
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        alert.getDialogPane().setMinWidth(460);
        return alert;
    }

    /**
     * Сообщение с раскрываемыми подробностями (стек, диагностика, текст справки или снимка).
     *
     * @param type    вид сообщения
     * @param title   заголовок окна
     * @param header  крупный текст
     * @param content короткое пояснение
     * @param details длинный текст для области «Подробности»
     * @return сообщение
     */
    public static Alert withDetails(AlertType type, String title, String header, String content, String details) {
        Alert alert = alert(type, title, header, content);
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        alert.getDialogPane().setExpandableContent(AppDialogPane.detailsArea(details));
        return alert;
    }

    /**
     * Переводит на русский ссылку раскрытия подробностей у панели диалога.
     *
     * <p>JavaFX берёт подписи «Show Details / Hide Details» из своих ресурсов, а русского перевода в них нет,
     * поэтому в окне ошибки или справки посреди русского интерфейса появлялась английская ссылка. Панель
     * создаёт ссылку сама и меняет её текст в слушателе {@code expanded}; наш слушатель добавляется позже и
     * вызывается после стандартного, поэтому русская подпись остаётся и после раскрытия.</p>
     *
     * <p>Вызывать после показа окна (ссылка создаётся вместе с панелью кнопок); повторный вызов безопасен.</p>
     *
     * @param pane панель диалога ({@code null} — ничего не делать)
     */
    public static void localizeDetailsButton(DialogPane pane) {
        if (pane == null) {
            return;
        }
        for (Node node : pane.lookupAll(".details-button")) {
            if (node instanceof Hyperlink link && link.getProperties().putIfAbsent(DETAILS_LOCALIZED, Boolean.TRUE) == null) {
                InvalidationListener update = o -> link.setText(pane.isExpanded() ? DETAILS_LESS : DETAILS_MORE);
                pane.expandedProperty().addListener(update);
                update.invalidated(pane.expandedProperty());
            }
        }
    }

    /**
     * Диалог 16 «Ошибка»: сообщение и стек в раскрываемой области.
     *
     * @param header что не получилось
     * @param error  исключение
     * @return сообщение об ошибке
     */
    public static Alert error(String header, Throwable error) {
        String message = Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
        return withDetails(AlertType.ERROR, "Ошибка", header, message, stackTrace(error));
    }

    /**
     * Диалог 16 «Ошибка» без исключения (понятная пользователю причина).
     *
     * @param header  что не получилось
     * @param message почему
     * @return сообщение об ошибке
     */
    public static Alert error(String header, String message) {
        return alert(AlertType.ERROR, "Ошибка", header, message);
    }

    /**
     * Текст стека исключения.
     *
     * @param error исключение
     * @return стек в том виде, как его печатает {@code printStackTrace}
     */
    public static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    /**
     * Диалог 12: выбор файла плана для открытия или сохранения.
     *
     * @param title       заголовок окна
     * @param initialDir  начальная папка (обычно CashMemory)
     * @param initialName предлагаемое имя файла или {@code null}
     * @return окно выбора файла
     */
    public static FileChooser planChooser(String title, Path initialDir, String initialName) {
        // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: обозреватель /api/fs?mode=md + <input type="file"> + скачивание
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(MD_DESCRIPTION, "*.md"));
        applyInitial(chooser, initialDir, initialName);
        return chooser;
    }

    /**
     * Выбор файла для экспорта CSV.
     *
     * @param initialDir  начальная папка
     * @param initialName предлагаемое имя
     * @return окно выбора файла
     */
    public static FileChooser csvChooser(Path initialDir, String initialName) {
        // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: скачивание GET /api/export.csv
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Экспорт прогноза в CSV");
        chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Таблица CSV (*.csv)", "*.csv"));
        applyInitial(chooser, initialDir, initialName);
        return chooser;
    }

    /**
     * Выбор файла для «Сохранить график PNG…» (только настольные клиенты).
     *
     * @param initialDir  начальная папка
     * @param initialName предлагаемое имя
     * @return окно выбора файла
     */
    public static FileChooser pngChooser(Path initialDir, String initialName) {
        // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: нет (PNG только в desktop)
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Сохранить график как PNG");
        chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Изображение PNG (*.png)", "*.png"));
        applyInitial(chooser, initialDir, initialName);
        return chooser;
    }

    /**
     * Диалог 13: выбор папки.
     *
     * @param title      заголовок окна (в нём же виден текущий путь)
     * @param initialDir начальная папка
     * @return окно выбора папки
     */
    public static DirectoryChooser folderChooser(String title, Path initialDir) {
        // JavaFX: DirectoryChooser → Swing: JFileChooser(DIRECTORIES_ONLY) → Web: обозреватель GET /api/fs?mode=dirs
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        if (initialDir != null && Files.isDirectory(initialDir)) {
            chooser.setInitialDirectory(initialDir.toFile());
        }
        return chooser;
    }

    private static void applyInitial(FileChooser chooser, Path initialDir, String initialName) {
        // Несуществующая начальная папка заставила бы Windows-диалог выбросить исключение.
        if (initialDir != null && Files.isDirectory(initialDir)) {
            chooser.setInitialDirectory(initialDir.toFile());
        }
        if (initialName != null && !initialName.isBlank()) {
            chooser.setInitialFileName(initialName);
        }
    }
}
