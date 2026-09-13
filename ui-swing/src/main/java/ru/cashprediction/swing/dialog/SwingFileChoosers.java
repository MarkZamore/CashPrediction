package ru.cashprediction.swing.dialog;

import java.awt.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Выбор файлов и папок: открыть/сохранить план, сохранить CSV, выбрать папку.
 * Swing-аналоги JavaFX {@code FileChooser} и {@code DirectoryChooser}.
 *
 * <p>Нативные окна выбора файлов не восстанавливаются после сбоя ни в одном клиенте (раздел 5.1 плана), поэтому
 * здесь допустим штатный блокирующий {@code JFileChooser.showOpenDialog/showSaveDialog}: он не участвует в цепочке
 * восстановления и не попадает в снимок. Вызывающий код получает результат сразу.</p>
 *
 * <p>Класс без состояния; методы вызываются в потоке EDT.</p>
 */
public final class SwingFileChoosers {

    /** Фильтр планов. */
    private static final String MD_DESCRIPTION = "Планы CashPrediction (*.md)";

    private SwingFileChoosers() {
    }

    /**
     * «Открыть из файла…»: выбор существующего {@code .md}.
     *
     * @param parent     владелец окна выбора
     * @param title      заголовок окна
     * @param initialDir начальная папка (обычно CashMemory)
     * @return выбранный файл или пусто при отмене
     */
    public static Optional<Path> openMarkdown(Component parent, String title, Path initialDir) {
        // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: обозреватель /api/fs?mode=md + <input type="file">
        JFileChooser chooser = new JFileChooser(existingDir(initialDir));
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter(MD_DESCRIPTION, "md"));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return Optional.empty();
        }
        return Optional.of(chooser.getSelectedFile().toPath());
    }

    /**
     * «Сохранить как…»: выбор файла для записи с автодобавлением расширения и подтверждением перезаписи.
     *
     * @param parent            владелец окна выбора
     * @param title             заголовок окна
     * @param initialDir        начальная папка
     * @param suggestedFileName предлагаемое имя файла (с расширением)
     * @param filterDescription подпись фильтра («Планы CashPrediction (*.md)»)
     * @param extension         расширение без точки ({@code md}, {@code csv})
     * @return выбранный файл или пусто при отмене
     */
    public static Optional<Path> saveFile(Component parent, String title, Path initialDir, String suggestedFileName,
                                          String filterDescription, String extension) {
        // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: скачивание файла
        JFileChooser chooser = new JFileChooser(existingDir(initialDir)) {
            /** Пользователь подтвердил выбор файла: при сохранении поверх существующего файла сначала спрашиваем. */
            @Override
            public void approveSelection() {
                File selected = getSelectedFile();
                if (selected == null) {
                    return;
                }
                // Как FileChooser с ExtensionFilter в JavaFX: имя без расширения дополняем расширением фильтра.
                if (!selected.getName().toLowerCase(Locale.ROOT).endsWith("." + extension)) {
                    selected = new File(selected.getParentFile(), selected.getName() + "." + extension);
                    setSelectedFile(selected);
                }
                if (selected.exists()) {
                    // JFileChooser сам не спрашивает о перезаписи. Вопрос задаётся внутри уже модального окна выбора,
                    // поэтому здесь уместен блокирующий JOptionPane.
                    int answer = JOptionPane.showConfirmDialog(this,
                            "Файл «" + selected.getName() + "» уже существует. Заменить его?",
                            "Подтверждение", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (answer != JOptionPane.YES_OPTION) {
                        return;
                    }
                }
                super.approveSelection();
            }
        };
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter(filterDescription, extension));
        if (suggestedFileName != null && !suggestedFileName.isBlank()) {
            chooser.setSelectedFile(new File(existingDir(initialDir), suggestedFileName));
        }
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return Optional.empty();
        }
        return Optional.of(chooser.getSelectedFile().toPath());
    }

    /**
     * Сохранение плана в {@code .md}.
     *
     * @param parent            владелец
     * @param title             заголовок окна
     * @param initialDir        начальная папка
     * @param suggestedFileName предлагаемое имя
     * @return выбранный файл или пусто
     */
    public static Optional<Path> saveMarkdown(Component parent, String title, Path initialDir, String suggestedFileName) {
        return saveFile(parent, title, initialDir, suggestedFileName, MD_DESCRIPTION, "md");
    }

    /**
     * Выбор папки.
     *
     * @param parent     владелец
     * @param title      заголовок окна
     * @param initialDir начальная папка
     * @param accessory  дополнительная панель справа (например, путь к CashMemory) или {@code null}
     * @return выбранная папка или пусто
     */
    public static Optional<Path> chooseDirectory(Component parent, String title, Path initialDir, JComponent accessory) {
        // JavaFX: DirectoryChooser → Swing: JFileChooser(DIRECTORIES_ONLY) → Web: обозреватель GET /api/fs?mode=dirs
        JFileChooser chooser = new JFileChooser(existingDir(initialDir));
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (accessory != null) {
            chooser.setAccessory(accessory);
        }
        if (chooser.showDialog(parent, "Выбрать папку") != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return Optional.empty();
        }
        return Optional.of(chooser.getSelectedFile().toPath());
    }

    private static File existingDir(Path dir) {
        if (dir != null && Files.isDirectory(dir)) {
            return dir.toFile();
        }
        return null;
    }
}
