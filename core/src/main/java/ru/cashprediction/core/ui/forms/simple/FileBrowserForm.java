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
import ru.cashprediction.core.ui.form.FieldKind;
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

/** Серверный обозреватель файлов для web-клиента. */
public final class FileBrowserForm implements FormLogic {
    private final FileChooserSpec fileSpec;
    private final DirectoryChooserSpec directorySpec;
    private final FolderListing listing;
    public FileBrowserForm(FileChooserSpec spec, FolderListing listing) { this.fileSpec = java.util.Objects.requireNonNull(spec, "spec"); this.directorySpec = null; this.listing = java.util.Objects.requireNonNull(listing, "listing"); }
    public FileBrowserForm(DirectoryChooserSpec spec, FolderListing listing) { this.fileSpec = null; this.directorySpec = java.util.Objects.requireNonNull(spec, "spec"); this.listing = java.util.Objects.requireNonNull(listing, "listing"); }
    public FileChooserSpec fileSpec() { return fileSpec; }
    public DirectoryChooserSpec directorySpec() { return directorySpec; }
    public FolderListing listing() { return listing; }
    @Override public FormSpec spec(FormContext context) {
        List<FormRow> rows = new ArrayList<>();
        rows.add(new FormRow.Field(FieldSpecs.choice("root", UiText.get("dialog.file.disk"), roots())));
        rows.add(new FormRow.Inline("", List.of(FieldSpecs.button("up", UiText.get("dialog.file.up")), FieldSpecs.button("cashMemory", UiText.get("dialog.file.cashMemory")))));
        rows.add(new FormRow.Field(FieldSpecs.text("path", UiText.get("dialog.file.path"), "")));
        rows.add(new FormRow.Field(FieldSpecs.list("value", "", 12, List.of())));
        if (saving()) rows.add(new FormRow.Field(FieldSpecs.text("name", UiText.get("dialog.file.name"), "")));
        rows.add(new FormRow.Hint("type", fileSpec == null ? "" : UiText.get("dialog.file.type", fileSpec.filterDescription())));
        return new FormSpec("fileBrowser", WindowType.CHOICE, "fileBrowser", Presentation.FILE_BROWSER, title(), "", 680, true, false, false, List.of(new FormPage("main", rows)), List.of(ButtonSpecs.of("ok", UiText.get(okText()), ButtonRole.OK), ButtonSpecs.cancel()), "ok");
    }
    @Override public Map<String, String> defaults(FormContext context) { Path folder = initialFolder(); return saving() ? Map.of("root", rootOf(folder), "path", folder.toString(), "value", "", "name", fileSpec.initialName()) : Map.of("root", rootOf(folder), "path", folder.toString(), "value", ""); }
    @Override public FormView evaluate(FormState state, FormContext context) {
        Result result = read(state.value("path"));
        boolean validName = !saving() || validFileName(state.value("name"));
        boolean enabled = result.problem == null && (directories() || saving() ? validName : !state.value("value").isBlank());
        Map<String, FieldView> fields = new java.util.LinkedHashMap<>();
        fields.put("root", new FieldView(state.value("root"), true, true, false, null, roots(), null));
        fields.put("path", FieldView.of(state.value("path")));
        fields.put("value", new FieldView(state.value("value"), true, result.problem == null, false, null, result.options, null));
        if (saving()) fields.put("name", FieldView.of(state.value("name")));
        Problem problem = result.problem == null ? (validName ? Problem.NONE : Problem.error(UiText.get("dialog.file.nameInvalid"))) : Problem.error(result.problem);
        return new FormView(0, 0, "", fields, problem, Map.of("ok", enabled ? ru.cashprediction.core.ui.form.ButtonView.ENABLED : ru.cashprediction.core.ui.form.ButtonView.DISABLED), List.of(), List.of(), "", false);
    }
    @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        if (ButtonSpecs.CANCEL.equals(buttonId)) return new FormOutcome.Close(null);
        if ("cashMemory".equals(buttonId)) return new FormOutcome.SetFields(Map.of("root", rootOf(listing.cashMemory()), "path", listing.cashMemory().toString(), "value", ""));
        if ("up".equals(buttonId)) { Path parent = Path.of(state.value("path")).toAbsolutePath().normalize().getParent(); return parent == null ? FormOutcome.stay() : new FormOutcome.SetFields(Map.of("root", rootOf(parent), "path", parent.toString(), "value", "")); }
        if ("ok".equals(buttonId)) return close(state);
        return FormOutcome.stay();
    }
    @Override public FormOutcome onFieldActivated(String fieldId, int index, FormState state, FormContext context) {
        if (!"value".equals(fieldId)) return FormOutcome.stay();
        Result result = read(state.value("path"));
        if (index < 0 || index >= result.entries.size()) return FormOutcome.stay();
        FolderListing.Entry entry = result.entries.get(index);
        return entry.directory() ? new FormOutcome.SetFields(Map.of("root", rootOf(entry.path()), "path", entry.path().toString(), "value", "")) : new FormOutcome.SetFields(Map.of("value", entry.path().toString()));
    }
    @Override public Optional<FormOutcome> onFieldSubmitted(String fieldId, FormState state, FormContext context) { return "path".equals(fieldId) ? Optional.of(new FormOutcome.SetFields(Map.of("root", rootOf(Path.of(state.value("path"))), "path", Path.of(state.value("path")).toAbsolutePath().normalize().toString(), "value", ""))) : Optional.empty(); }
    private FormOutcome close(FormState state) {
        Result result = read(state.value("path"));
        if (result.problem != null) return new FormOutcome.Stay(Problem.error(result.problem));
        Path folder = result.folder;
        if (directories()) return new FormOutcome.Close(folder);
        if (saving()) { String name = state.value("name").strip(); if (name.isEmpty()) return new FormOutcome.Stay(Problem.error(UiText.get("dialog.file.nameRequired"))); if (!validFileName(name)) return new FormOutcome.Stay(Problem.error(UiText.get("dialog.file.nameInvalid"))); return new FormOutcome.Close(folder.resolve(withExtension(name))); }
        return state.value("value").isBlank() ? new FormOutcome.Stay(Problem.error(UiText.get("dialog.file.nameRequired"))) : new FormOutcome.Close(Path.of(state.value("value")));
    }
    private Result read(String raw) { try { Path path = Path.of(raw).toAbsolutePath().normalize(); FolderListing.Listing value = listing.list(path, directories() ? FolderListing.Mode.DIRECTORIES : FolderListing.Mode.FILES, fileSpec == null ? List.of() : fileSpec.extensions()); List<Option> options = value.entries().isEmpty() ? List.of(Option.of("", UiText.get("dialog.file.empty"))) : value.entries().stream().map(e -> new Option(e.path().toString(), e.name(), e.modifiedText(), e.directory())).toList(); return new Result(path, value.entries(), options, null); } catch (NoSuchFileException e) { return new Result(null, List.of(), List.of(), UiText.get("dialog.file.notFound", raw)); } catch (AccessDeniedException e) { return new Result(null, List.of(), List.of(), UiText.get("dialog.file.denied", raw)); } catch (IOException | RuntimeException e) { return new Result(null, List.of(), List.of(), UiText.get("dialog.file.denied", raw)); } }
    private boolean directories() { return directorySpec != null; }
    private boolean saving() { return fileSpec != null && fileSpec.mode() == FileChooserSpec.Mode.SAVE; }
    private Path initialFolder() { return directories() ? directorySpec.initialFolder() : fileSpec.initialFolder(); }
    private String title() { return directories() ? directorySpec.title() : fileSpec.title(); }
    private String okText() { return directories() ? "button.chooseFolder" : saving() ? "button.save" : "button.open"; }
    private List<Option> roots() { return listing.roots().stream().map(root -> Option.of(root.path().toString(), root.name())).toList(); }
    private static String rootOf(Path value) { Path root = value.toAbsolutePath().normalize().getRoot(); return root == null ? value.toAbsolutePath().normalize().toString() : root.toString(); }
    private boolean validFileName(String value) { return value != null && !value.strip().isEmpty() && value.chars().noneMatch(c -> "\\/:*?\"<>|".indexOf(c) >= 0); }
    private String withExtension(String name) { if (fileSpec.extensions().isEmpty()) return name; String suffix = "." + fileSpec.extensions().getFirst(); return name.toLowerCase(java.util.Locale.ROOT).endsWith(suffix) ? name : name + suffix; }
    private record Result(Path folder, List<FolderListing.Entry> entries, List<Option> options, String problem) { }
}
