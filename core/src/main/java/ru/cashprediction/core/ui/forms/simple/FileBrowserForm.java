package ru.cashprediction.core.ui.forms.simple;

import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.io.FolderListing;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

/**
 * §6.21 [WEB] окно ядра «Выбор файла» (представление {@code FILE_BROWSER}, ширина 680, модальное, не
 * восстанавливается; заголовок окна — из запроса). JavaFX: {@code FileChooser}/{@code DirectoryChooser} → Swing:
 * {@code JFileChooser} → Web: эта форма.
 *
 * <p>Раскладка: «Диск:» (choice дисков) · «↑» («На уровень вверх») · «CashMemory» («Перейти в папку CashMemory») ·
 * поле пути («Путь к папке: Enter — перейти»); список 12 строк, колонки «Имя» и «Изменён» (dd.MM.yyyy HH:mm), сначала
 * папки (жирно, двойной щелчок — войти), затем файлы фильтра; служебные файлы CashMemory скрыты; пустая папка —
 * «(папка пуста)»; «Тип файлов: {фильтр}»; в режиме сохранения поле «Имя файла:». Кнопки [Открыть] / [Сохранить] /
 * [Выбрать папку] (OK) и [Отмена]; OK отключена, пока не выбран файл или не введено имя. Ошибки: «Папка «{0}» не
 * найдена»; «Нет доступа к папке «{0}»»; «Укажите имя файла»; «Имя файла не может содержать символы \ / : * ? " &lt; &gt; |».
 * Список папки строит {@link FolderListing}.</p>
 *
 * <p>Результат {@code Close(Path)}: выбранный файл (в режиме сохранения — с дописанным расширением) или папка.</p>
 */
public final class FileBrowserForm implements FormLogic {

    private final FileChooserSpec fileSpec;
    private final DirectoryChooserSpec directorySpec;
    private final FolderListing listing;

    /**
     * Выбор файла.
     *
     * @param spec    запрос
     * @param listing обозреватель папок
     */
    public FileBrowserForm(FileChooserSpec spec, FolderListing listing) {
        this.fileSpec = Objects.requireNonNull(spec, "spec");
        this.directorySpec = null;
        this.listing = Objects.requireNonNull(listing, "listing");
    }

    /**
     * Выбор папки.
     *
     * @param spec    запрос
     * @param listing обозреватель папок
     */
    public FileBrowserForm(DirectoryChooserSpec spec, FolderListing listing) {
        this.fileSpec = null;
        this.directorySpec = Objects.requireNonNull(spec, "spec");
        this.listing = Objects.requireNonNull(listing, "listing");
    }

    /** @return запрос выбора файла или {@code null} в режиме папок */
    public FileChooserSpec fileSpec() {
        return fileSpec;
    }

    /** @return запрос выбора папки или {@code null} в режиме файлов */
    public DirectoryChooserSpec directorySpec() {
        return directorySpec;
    }

    /** @return обозреватель папок */
    public FolderListing listing() {
        return listing;
    }

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FileBrowserForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FileBrowserForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FileBrowserForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FileBrowserForm.onButton");
    }
}
