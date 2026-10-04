package ru.cashprediction.core.app.flow;

import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import ru.cashprediction.core.app.ChooserKind;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.io.FolderListing;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.forms.simple.FileBrowserForm;
import ru.cashprediction.core.ui.text.UiText;

/** Общий выбор файлов и папок: нативное окно либо серверная форма, расширение и подтверждение замены. */
public final class FileChooserService {
    private final FlowContext context;

    /** @param context контекст контроллера, доступный только в его потоке */
    public FileChooserService(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() { return context; }

    /**
     * Выбирает файл и возвращает результат ровно один раз, после расширения и подтверждения замены.
     * @param spec запрос выбора
     * @param onResult подтверждённый путь либо пустой результат при отмене
     */
    public void chooseFile(FileChooserSpec spec, Consumer<Optional<Path>> onResult) {
        Objects.requireNonNull(spec, "spec");
        // SAVE всегда начинается в CashMemory, даже если текущий план прочитан из внешней папки.
        if (spec.mode() == FileChooserSpec.Mode.SAVE) {
            spec = new FileChooserSpec(spec.purpose(), spec.mode(), spec.title(), spec.filterDescription(),
                    spec.extensions(), context.environment().cashMemory(), spec.initialName());
        }
        FileChooserSpec request = spec;
        Consumer<Optional<Path>> finish = once(onResult);
        Consumer<Optional<Path>> selected = once(value -> {
            if (value.isEmpty()) {
                finish.accept(Optional.empty());
                return;
            }
            Path original = value.get().toAbsolutePath().normalize();
            Path path = original;
            if (request.mode() == FileChooserSpec.Mode.SAVE && !request.extensions().isEmpty()) {
                String name = path.getFileName().toString();
                boolean recognized = request.extensions().stream().anyMatch(extension ->
                        name.toLowerCase(Locale.ROOT).endsWith("." + extension.toLowerCase(Locale.ROOT)));
                if (!recognized) path = path.resolveSibling(name + "." + request.extensions().getFirst());
            }
            Path result = path;
            String problem = selectionProblem(result, request.mode());
            if (problem != null) {
                rejectFile(request, result, problem, finish);
                return;
            }
            // Нативное подтверждение относится к выбранному имени, а не к дописанному расширению.
            boolean nativeConfirmed = context.port().profile().nativeReplacePrompt() && original.equals(result);
            if (request.mode() == FileChooserSpec.Mode.SAVE && Files.exists(result) && !nativeConfirmed) {
                // JavaFX: Alert → Swing: SwingAlert → Web: dialog.
                context.showAlert(AlertCatalog.replaceFile(result.getFileName().toString()),
                        once(button -> {
                            if (!"replace".equals(button)) { finish.accept(Optional.empty()); return; }
                            String changed = selectionProblem(result, request.mode());
                            if (changed == null) finish.accept(Optional.of(result));
                            else rejectFile(request, result, changed, finish);
                        }));
            } else {
                finish.accept(Optional.of(result));
            }
        });
        if (context.port().profile().chooser() == ChooserKind.SERVER_BROWSER) {
            // JavaFX: FileChooser → Swing: JFileChooser → Web: FileBrowserForm.
            context.openForm(FormRequest.fresh(new FileBrowserForm(spec,
                    new FolderListing(context.environment().cashMemory())), WindowType.CHOICE, true,
                    Map.of("purpose", "fileBrowser")), null,
                    result -> selected.accept(result instanceof Path path ? Optional.of(path) : Optional.empty()));
        } else {
            // JavaFX: FileChooser → Swing: JFileChooser → Web: FileBrowserForm.
            context.port().chooseFile(spec, selected);
        }
    }

    /** Проверяет окончательный путь; ошибки разрешения закрывают доступ без технических подробностей. */
    private String selectionProblem(Path path, FileChooserSpec.Mode mode) {
        try {
            if (mode == FileChooserSpec.Mode.SAVE) AtomicFiles.requireWriteScope(context.environment().cashMemory(), path);
            return CashMemoryLayout.isProtectedUserPath(context.environment().cashMemory(), path)
                ? UiText.get("dialog.file.protected") : null; }
        catch (IOException | RuntimeException unavailable) { return UiText.get("dialog.file.denied", path); }
    }
    /** Завершает отказ ровно один раз после подтверждения локализованного сообщения. */
    private void rejectFile(FileChooserSpec spec, Path path, String problem, Consumer<Optional<Path>> finish) {
        // JavaFX: Alert → Swing: SwingAlert → Web: dialog.
        context.showAlert(AlertCatalog.error(spec.mode() == FileChooserSpec.Mode.SAVE ? "save" : "readPlan",
                new IOException(problem), path), once(button -> finish.accept(Optional.empty())));
    }

    /**
     * Выбирает папку нативным окном либо серверной формой.
     * @param spec запрос выбора папки
     * @param onResult папка либо пустой результат; вызывается ровно один раз
     */
    public void chooseDirectory(DirectoryChooserSpec spec, Consumer<Optional<Path>> onResult) {
        Objects.requireNonNull(spec, "spec");
        Consumer<Optional<Path>> finish = once(onResult);
        if (context.port().profile().chooser() == ChooserKind.SERVER_BROWSER) {
            // JavaFX: DirectoryChooser → Swing: JFileChooser → Web: FileBrowserForm.
            context.openForm(FormRequest.fresh(new FileBrowserForm(spec,
                    new FolderListing(context.environment().cashMemory())), WindowType.CHOICE, true,
                    Map.of("purpose", "fileBrowser")), null,
                    result -> finish.accept(result instanceof Path path ? Optional.of(path.toAbsolutePath().normalize())
                            : Optional.empty()));
        } else {
            // JavaFX: DirectoryChooser → Swing: JFileChooser → Web: FileBrowserForm.
            context.port().chooseDirectory(spec, once(result ->
                    finish.accept(result.map(path -> path.toAbsolutePath().normalize()))));
        }
    }

    /** Защищает продолжение даже от ошибочного двойного ответа клиента. */
    private static <T> Consumer<T> once(Consumer<T> action) {
        Objects.requireNonNull(action, "onResult");
        AtomicBoolean completed = new AtomicBoolean();
        return value -> { if (completed.compareAndSet(false, true)) action.accept(value); };
    }
}
