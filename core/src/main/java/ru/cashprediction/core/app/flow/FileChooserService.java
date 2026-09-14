package ru.cashprediction.core.app.flow;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.FileChooserSpec;

/**
 * Выбор файлов и папок одним путём для всех клиентов (архитектура §3.6 «File choosers»; спецификация v2, §6.21).
 *
 * <p>Клиенты {@code NATIVE}/{@code SWING} — {@code port.chooseFile}/{@code chooseDirectory}; клиент
 * {@code SERVER_BROWSER} — форма ядра {@code FileBrowserForm}. В обоих случаях при сохранении ядро дописывает
 * расширение (если его нет) и, если файл существует, спрашивает {@code confirm.replaceFile} (§6.13), кроме
 * {@code profile.nativeReplacePrompt()}. Отмена на вопросе о замене — повторный выбор не открывается, результат пуст.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class FileChooserService {

    private final FlowContext context;

    /**
     * Создаёт службу.
     *
     * @param context контекст контроллера
     */
    public FileChooserService(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /**
     * Выбор файла.
     *
     * @param spec     запрос
     * @param onResult итоговый путь (с расширением, подтверждённая замена) или пусто, ровно один раз
     */
    public void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> onResult) {
        throw new UnsupportedOperationException("S2: core-app-file - FileChooserService.chooseFile");
    }

    /**
     * Выбор папки.
     *
     * @param spec     запрос
     * @param onResult папка или пусто, ровно один раз
     */
    public void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> onResult) {
        throw new UnsupportedOperationException("S2: core-app-file - FileChooserService.chooseDirectory");
    }
}
