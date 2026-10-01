package ru.cashprediction.core.ui.forms.simple;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.io.FolderListing;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.ButtonRole;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiText;

/** Серверный обозреватель файлов для web-клиента (§6.21).
 * JavaFX: FileChooser/DirectoryChooser → Swing: JFileChooser → Web: FILE_BROWSER.
 */
public final class FileBrowserForm implements FormLogic {
    private final FileChooserSpec fileSpec;
    private final DirectoryChooserSpec directorySpec;
    private final FolderListing listing;
    /** Создаёт форму выбора файла. */
    public FileBrowserForm(FileChooserSpec spec, FolderListing listing) { this.fileSpec = java.util.Objects.requireNonNull(spec, "spec"); this.directorySpec = null; this.listing = java.util.Objects.requireNonNull(listing, "listing"); }
    /** Создаёт форму выбора папки. */
    public FileBrowserForm(DirectoryChooserSpec spec, FolderListing listing) { this.fileSpec = null; this.directorySpec = java.util.Objects.requireNonNull(spec, "spec"); this.listing = java.util.Objects.requireNonNull(listing, "listing"); }
    /** Возвращает запрос выбора файла или null для папки. */
    public FileChooserSpec fileSpec() { return fileSpec; }
    /** Возвращает запрос выбора папки или null для файла. */
    public DirectoryChooserSpec directorySpec() { return directorySpec; }
    /** Возвращает обозреватель папок. */
    public FolderListing listing() { return listing; }
    /** Описывает поля и кнопки серверного обозревателя. */
    @Override public FormSpec spec(FormContext context) {
        List<FormRow> rows = new ArrayList<>();
        rows.add(new FormRow.Field(FieldSpecs.choice("root", UiText.get("dialog.file.disk"), roots())));
        rows.add(new FormRow.Inline("", List.of(
                FieldSpecs.withTooltip(FieldSpecs.button("up", UiText.get("dialog.file.up")), UiText.get("dialog.file.up.tip")),
                FieldSpecs.withTooltip(FieldSpecs.button("cashMemory", UiText.get("dialog.file.cashMemory")), UiText.get("dialog.file.cashMemory.tip")))));
        rows.add(new FormRow.Field(FieldSpecs.withTooltip(FieldSpecs.text("path", UiText.get("dialog.file.path"), ""), UiText.get("dialog.file.path.tip"))));
        rows.add(new FormRow.Field(FieldSpecs.list("value", "", 12, List.of())));
        if (saving()) rows.add(new FormRow.Field(FieldSpecs.text("name", UiText.get("dialog.file.name"), "")));
        rows.add(new FormRow.Hint("type", fileSpec == null ? "" : UiText.get("dialog.file.type", fileSpec.filterDescription())));
        return new FormSpec("fileBrowser", WindowType.CHOICE, "fileBrowser", Presentation.FILE_BROWSER, title(), "", 680, true, false, false, List.of(new FormPage("main", rows)), List.of(ButtonSpecs.of("ok", UiText.get(okText()), ButtonRole.OK), ButtonSpecs.cancel()), "ok");
    }
    /** Задаёт начальную папку и имя файла. */
    @Override public Map<String, String> defaults(FormContext context) { Path folder = initialFolder().toAbsolutePath().normalize(); return saving() ? Map.of("root", rootOf(folder), "path", folder.toString(), "value", "", "name", fileSpec.initialName()) : Map.of("root", rootOf(folder), "path", folder.toString(), "value", ""); }
    /** Проверяет ввод; усечённый список даёт предупреждение без блокировки OK. */
    @Override public FormView evaluate(FormState state, FormContext context) {
        Result result = read(state.value("path"));
        boolean validName = !saving() || validFileName(state.value("name"));
        boolean enabled = result.problem == null && (directories() || saving() ? validName : selectedFile(result, state.value("value")) != null);
        Map<String, FieldView> fields = new java.util.LinkedHashMap<>();
        fields.put("root", new FieldView(state.value("root"), true, true, false, null, roots(), null));
        fields.put("path", FieldView.of(state.value("path")));
        fields.put("up", new FieldView(null, true, result.folder != null && result.folder.getParent() != null, false, null, null, null));
        fields.put("value", new FieldView(state.value("value"), true, result.problem == null, false, null, result.options, null));
        if (saving()) fields.put("name", FieldView.of(state.value("name")));
        Problem problem = result.problem != null ? Problem.error(result.problem)
                : !validName ? Problem.error(UiText.get(state.value("name").isBlank() ? "dialog.file.nameRequired" : "dialog.file.nameInvalid"))
                : result.truncated ? Problem.warning(UiText.get("dialog.file.truncated", listing.maxEntries())) : Problem.NONE;
        return new FormView(0, 0, "", fields, problem, Map.of("ok", enabled ? ru.cashprediction.core.ui.form.ButtonView.ENABLED : ru.cashprediction.core.ui.form.ButtonView.DISABLED), List.of(), List.of(), "", false);
    }
    /** Завершённый выбор диска открывает только корень из списка ОС и сбрасывает выбор файла. */
    @Override public Optional<FormOutcome> onFieldChanged(String fieldId, boolean committed, FormState state,
            FormContext context) {
        if (!committed || !"root".equals(fieldId)) return Optional.empty();
        // Значение choice - путь корня, а не индекс: произвольный ввод не разбирается как число или путь.
        return listing.roots().stream().filter(root -> root.path().toString().equals(state.value("root")))
                .findFirst().map(root -> navigate(root.path()))
                .or(() -> Optional.of(new FormOutcome.Stay(Problem.error(UiText.get("dialog.file.notFound", state.value("root"))))));
    }
    /** Обрабатывает переходы между папками и завершение выбора. */
    @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        if (ButtonSpecs.CANCEL.equals(buttonId)) return new FormOutcome.Close(null);
        if ("cashMemory".equals(buttonId)) return new FormOutcome.SetFields(Map.of("root", rootOf(listing.cashMemory()), "path", listing.cashMemory().toString(), "value", ""));
        if ("up".equals(buttonId)) {
            Result result = read(state.value("path"));
            if (result.problem != null) return new FormOutcome.Stay(Problem.error(result.problem));
            Path parent = result.folder.getParent();
            return parent == null ? FormOutcome.stay() : navigate(parent);
        }
        if ("ok".equals(buttonId)) return close(state);
        return FormOutcome.stay();
    }
    /** По двойному щелчку входит в папку или выбирает файл. */
    @Override public FormOutcome onFieldActivated(String fieldId, int index, FormState state, FormContext context) {
        if (!"value".equals(fieldId)) return FormOutcome.stay();
        Result result = read(state.value("path"));
        if (index < 0 || index >= result.entries.size()) return FormOutcome.stay();
        FolderListing.Entry entry = result.entries.get(index);
        return entry.directory() ? navigate(entry.path()) : new FormOutcome.SetFields(saving()
                ? Map.of("value", entry.path().toString(), "name", entry.name()) : Map.of("value", entry.path().toString()));
    }
    /** Enter в пути проверяет папку и сбрасывает прежний выбор файла. */
    @Override public Optional<FormOutcome> onFieldSubmitted(String fieldId, FormState state, FormContext context) {
        if (!"path".equals(fieldId)) return Optional.empty();
        Result result = read(state.value("path"));
        return Optional.of(result.problem == null ? navigate(result.folder) : new FormOutcome.Stay(Problem.error(result.problem)));
    }
    private FormOutcome navigate(Path folder) {
        return new FormOutcome.SetFields(Map.of("root", rootOf(folder), "path", folder.toString(), "value", ""));
    }
    private FormOutcome close(FormState state) {
        Result result = read(state.value("path"));
        if (result.problem != null) return new FormOutcome.Stay(Problem.error(result.problem));
        Path folder = result.folder;
        if (directories()) return new FormOutcome.Close(folder);
        if (saving()) { String name = state.value("name").strip(); if (name.isEmpty()) return new FormOutcome.Stay(Problem.error(UiText.get("dialog.file.nameRequired"))); if (!validFileName(name)) return new FormOutcome.Stay(Problem.error(UiText.get("dialog.file.nameInvalid"))); return new FormOutcome.Close(folder.resolve(withExtension(name))); }
        FolderListing.Entry selected = selectedFile(result, state.value("value"));
        return selected == null ? new FormOutcome.Stay(Problem.error(UiText.get("dialog.file.nameRequired"))) : new FormOutcome.Close(selected.path());
    }
    private FolderListing.Entry selectedFile(Result result, String value) {
        return result.entries.stream().filter(entry -> !entry.directory() && entry.path().toString().equals(value)).findFirst().orElse(null);
    }
    private Result read(String raw) {
        try {
            // Пустой путь не означает рабочую папку процесса.
            if (raw.isBlank()) throw new NoSuchFileException(raw);
            Path path = Path.of(raw).toAbsolutePath().normalize();
            FolderListing.Listing value = listing.list(path, directories() ? FolderListing.Mode.DIRECTORIES : FolderListing.Mode.FILES, fileSpec == null ? List.of() : fileSpec.extensions());
            List<Option> options = value.entries().isEmpty() ? List.of(Option.of("", UiText.get("dialog.file.empty")))
                    : value.entries().stream().map(e -> new Option(e.path().toString(), e.name(), e.modifiedText(), e.directory())).toList();
            return new Result(path, value.entries(), options, null, value.truncated());
        } catch (NoSuchFileException e) {
            return failure(UiText.get("dialog.file.notFound", raw));
        } catch (AccessDeniedException e) {
            return failure(UiText.get("dialog.file.denied", raw));
        } catch (IOException | RuntimeException e) {
            return failure(UiText.get("dialog.file.denied", raw));
        }
    }
    private Result failure(String problem) { return new Result(null, List.of(), List.of(), problem, false); }
    private boolean directories() { return directorySpec != null; }
    private boolean saving() { return fileSpec != null && fileSpec.mode() == FileChooserSpec.Mode.SAVE; }
    private Path initialFolder() { return directories() ? directorySpec.initialFolder() : fileSpec.initialFolder(); }
    private String title() { return directories() ? directorySpec.title() : fileSpec.title(); }
    private String okText() { return directories() ? "button.chooseFolder" : saving() ? "button.save" : "button.open"; }
    private List<Option> roots() { return listing.roots().stream().map(root -> Option.of(root.path().toString(), root.name())).toList(); }
    private static String rootOf(Path value) { Path root = value.toAbsolutePath().normalize().getRoot(); return root == null ? value.toAbsolutePath().normalize().toString() : root.toString(); }
    private boolean validFileName(String value) {
        if (value == null) return false;
        String name = value.strip();
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.chars().noneMatch(c -> c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0);
    }
    private String withExtension(String name) { if (fileSpec.extensions().isEmpty()) return name; String suffix = "." + fileSpec.extensions().getFirst(); return name.toLowerCase(java.util.Locale.ROOT).endsWith(suffix) ? name : name + suffix; }
    /** Результат чтения папки с независимым признаком усечения. */
    private record Result(Path folder, List<FolderListing.Entry> entries, List<Option> options, String problem, boolean truncated) { }
}
