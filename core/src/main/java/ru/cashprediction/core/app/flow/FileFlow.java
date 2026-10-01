package ru.cashprediction.core.app.flow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.app.RevealMode;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.export.CsvExporter;
import ru.cashprediction.core.export.CsvOptions;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.MarkdownParseException;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;
import ru.cashprediction.core.ui.forms.simple.CsvExportForm;
import ru.cashprediction.core.ui.forms.simple.OpenPlanForm;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.chart.ChartLayout;
import ru.cashprediction.core.ui.view.status.StatusLevel;
import ru.cashprediction.core.diagnostics.PlanValidator;

/** Файловые сценарии общего приложения: открытие с подтверждением, сохранение, экспорт и выбор папки. */
public final class FileFlow {
    private final FlowContext context;
    private boolean saving;
    private boolean discardPrompt;

    /** @param context контекст контроллера, доступный только в его потоке */
    public FileFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() { return context; }

    /**
     * Продолжает действие только без изменений, после успешной записи либо после «Не сохранять».
     * Ошибка записи, перечитывание вместо записи, отмена и крестик не вызывают продолжение.
     * @param proceed действие после разрешения пользователя
     */
    public void confirmDiscard(Runnable proceed) {
        Objects.requireNonNull(proceed, "proceed");
        if (discardPrompt || saving) return;
        if (!context.document().isDirty()) {
            proceed.run();
            return;
        }
        discardPrompt = true;
        ask(AlertCatalog.unsavedChanges(context.document().plan().name()), button -> {
            discardPrompt = false;
            if (AlertCatalog.BUTTON_SAVE.equals(button)) save(proceed);
            else if (AlertCatalog.BUTTON_DONT_SAVE.equals(button)) proceed.run();
        });
    }

    /** Подтверждает отбрасывание правок и открывает мастер; созданный план записывается в CashMemory. */
    public void newPlan() { confirmDiscard(this::wizard); }

    /** Показывает мастер первого запуска; отмена сохраняет исходный пустой документ. */
    public void firstRunWizard() { confirmDiscard(this::wizard); }

    /** Подтверждает отбрасывание правок и показывает планы текущей папки. */
    public void open() { confirmDiscard(this::openList); }

    /** Подтверждает отбрасывание правок и выбирает файл плана. */
    public void openFile() { confirmDiscard(this::chooseOpen); }

    /**
     * Открывает недавний план; отсутствующий файл удаляется из списка только после подтверждения.
     * @param path путь из списка недавних, абсолютный либо относительно CashMemory
     */
    public void openRecent(String path) {
        confirmDiscard(() -> {
            try {
                Path file = Path.of(path);
                if (!file.isAbsolute()) file = context.environment().cashMemory().resolve(file);
                if (!Files.isRegularFile(file)) {
                    context.updateSettings(settings -> settings.withRecentPlanRemoved(path));
                    error("recentMissing", null, file);
                    return;
                }
                load(file, false);
            } catch (RuntimeException failure) {
                error("readPlan", failure, path);
            }
        });
    }

    /** Подтверждает отбрасывание правок и открывает несохранённый пример. */
    public void openSample() { confirmDiscard(this::sample); }

    /**
     * Сохраняет текущий документ с проверкой внешних изменений либо совпадения имени первого файла.
     * @param onSaved продолжение только после успешной записи; допускается null
     */
    public void save(Runnable onSaved) { save(false, onSaved); }

    /** Выбирает новое имя файла и при допустимом имени меняет имя плана одним шагом отмены. */
    public void saveAs() {
        if (saving) return;
        Plan expected = context.document().plan();
        Path oldFile = context.document().file().orElse(null);
        Path folder = oldFile == null ? context.environment().cashMemory() : oldFile.toAbsolutePath().getParent();
        saving = true;
        context.choosers().chooseFile(chooser(FileChooserSpec.Purpose.SAVE_PLAN_AS, "md", folder,
                baseName() + ".md"), result -> {
            saving = false;
            if (result.isEmpty() || !sameDocument(expected, oldFile)) return;
            Path target = result.get();
            String name = PlanMarkdownReader.nameWithoutExtension(target);
            boolean rename = !name.equals(expected.name()) && PlanValidator.checkPlanName(name).isEmpty();
            Plan written = rename ? expected.withName(name) : expected;
            try {
                repository().save(written, target);
                if (rename) context.edits().edit(UiText.get("undo.saveAsName", name), "", plan -> written);
                saved(target, false, null);
            } catch (IOException | RuntimeException failure) {
                error("save", failure, target);
            }
        });
    }

    /** Переименовывает план; файловый конфликт и ошибки применения остаются в строке проблем формы. */
    public void rename() {
        // JavaFX: TextInputDialog → Swing: SwingTextInputDialog → Web: dialog.
        context.openForm(FormRequest.fresh(new RenameForm(), WindowType.TEXT_INPUT, true,
                Map.of("purpose", TextInputForms.PURPOSE_RENAME)), null, result -> {
            if (result instanceof String name) renameTo(name);
        });
    }

    /**
     * Применяет имя из обычной или восстановленной формы, сохраняя несохранённые правки плана.
     * При внешнем изменении файла отказывает до записи и сохраняет прежнюю метку конфликта;
     * исключение оставляет форму и введённое имя открытыми через стандартный механизм FormSession.
     * @param name проверенное формой новое имя плана
     * @throws IllegalArgumentException если файл изменён снаружи либо новое имя уже занято
     * @throws UncheckedIOException если переименование файла не удалось
     */
    public void renameTo(String name) {
        Objects.requireNonNull(name, "name");
        if (name.equals(context.document().plan().name())) return;
        Path oldFile = context.document().file().orElse(null);
        if (oldFile == null) {
            if (context.edits().edit(UiText.get("undo.rename"), "", plan -> plan.withName(name))) {
                context.status(StatusLevel.INFO, "status.msg.renamed", name);
            }
            return;
        }
        if (context.externalChanges().changedExternally(oldFile)) {
            throw new IllegalArgumentException(UiText.get("alert.external.header",
                    PlanMarkdownReader.nameWithoutExtension(oldFile)));
        }
        try {
            Path target = oldFile.resolveSibling(PlanRepository.fileBaseName(name) + ".md");
            if (Files.exists(target) && !Files.isSameFile(oldFile, target)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            Path renamed = repository().rename(oldFile, name);
            // Переименование не сохраняет прежние несохранённые правки автоматически.
            boolean dirty = context.document().isDirty();
            Plan renamedPlan = context.document().plan().withName(name);
            context.document().replace(renamedPlan, renamed, dirty, context.document().loadDiagnostics());
            context.externalChanges().remember(renamed);
            context.updateSettings(settings -> settings.withRecentPlanRemoved(oldFile.toString())
                    .withRecentPlanRemoved(oldFile.getFileName().toString()).withPlanOpened(renamed.toString()));
            context.status(StatusLevel.INFO, "status.msg.renamed", name);
            context.refresh();
        } catch (FileAlreadyExistsException failure) {
            throw new IllegalArgumentException(UiText.get("s2.file.renameExists", name), failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(UiText.get("s2.file.renameFailed", failure.getMessage()), failure);
        }
    }

    /** Переключает общую настройку и таймер автосохранения. */
    public void toggleAutosave() {
        boolean enabled = !context.state().settings().autosave();
        context.updateSettings(settings -> settings.withAutosave(enabled));
        context.autosave().setEnabled(enabled);
        context.refresh();
    }

    /** Показывает параметры CSV, выбирает файл и атомарно экспортирует выбранный диапазон прогноза. */
    public void exportCsv() {
        if (!context.state().document().forecastAvailable()) return;
        // JavaFX: Dialog → Swing: SwingDialog → Web: dialog.
        context.openForm(FormRequest.fresh(new CsvExportForm(), WindowType.CSV_EXPORT, true, Map.of()), null, result -> {
            if (!(result instanceof CsvExportForm.Choice choice)) return;
            context.choosers().chooseFile(chooser(FileChooserSpec.Purpose.EXPORT_CSV, "csv",
                    context.environment().cashMemory(), baseName() + ".csv"), file -> {
                if (file.isEmpty()) return;
                try {
                    AppState state = context.state();
                    if (!state.document().forecastAvailable()) return;
                    boolean period = "PERIOD".equals(choice.range());
                    char separator = "TAB".equals(choice.separator()) ? '\t' : choice.separator().charAt(0);
                    CsvOptions options = new CsvOptions(separator, choice.bom(),
                            period ? state.document().forecast().anchor() : null,
                            period ? state.view().periodEnd(state.document().plan(), state.document().forecast().anchor()) : null);
                    AtomicFiles.writeString(file.get(), CsvExporter.toCsv(state.document().forecast(), options));
                    context.status(StatusLevel.SUCCESS, "status.msg.csv", file.get());
                } catch (IOException | RuntimeException failure) {
                    error("csv", failure);
                }
            });
        });
    }

    /** Рисует актуальную сцену 1200×700 через порт, затем выбирает файл и атомарно записывает PNG. */
    public void savePng() {
        AppState state = context.state();
        if (!state.document().forecastAvailable()) return;
        byte[] bytes;
        try {
            bytes = context.port().renderChartPng(ChartLayout.model(state, state.revision()).layout(1200, 700));
        } catch (IOException | RuntimeException failure) {
            error("png", failure, baseName());
            return;
        }
        context.choosers().chooseFile(chooser(FileChooserSpec.Purpose.SAVE_PNG, "png",
                context.environment().cashMemory(), UiText.get("s2.file.pngName", baseName())), file -> {
            if (file.isEmpty()) return;
            try {
                AtomicFiles.write(file.get(), bytes);
                context.status(StatusLevel.SUCCESS, "status.msg.png", file.get());
            } catch (IOException | RuntimeException failure) {
                error("png", failure, file.get());
            }
        });
    }

    /** Показывает папку данных, позволяет выбрать другую папку планов либо вернуться к CashMemory. */
    public void cashMemoryFolder() {
        Path folder = context.state().plansFolder();
        Path home = context.environment().cashMemory();
        ask(AlertCatalog.cashMemoryFolder(home, folder.equals(home) ? null : folder), button -> {
            if ("backToCashMemory".equals(button)) {
                context.setPlansFolder(null);
                context.status(StatusLevel.INFO, "status.msg.backToCashMemory");
            } else if ("otherFolder".equals(button)) {
                confirmDiscard(() -> {
                    // JavaFX: DirectoryChooser → Swing: JFileChooser → Web: FileBrowserForm.
                    context.choosers().chooseDirectory(new DirectoryChooserSpec(
                            UiText.get("s2.file.folderTitle", context.state().plansFolder()),
                            context.state().plansFolder()), selected -> {
                        if (selected.isPresent()) {
                            context.setPlansFolder(selected.get());
                            openList();
                        }
                    });
                });
            }
        });
    }

    /** @return ожидается ли решение о сохранении; предотвращает параллельные запросы автосохранения */
    boolean saving() { return saving; }

    /** Автоматическая попытка; ошибки видны в статусе, но не открывают повторных сообщений. */
    void saveAutomatically() { save(true, null); }

    /** Выполняет одну ручную или автоматическую попытку с общим замком на время подтверждения. */
    private void save(boolean automatic, Runnable onSaved) {
        if (saving) return;
        if (automatic && !context.document().isDirty()) return;
        Plan expected = context.document().plan();
        Path current = context.document().file().orElse(null);
        Path target = current == null ? repository().pathFor(expected.name()) : current;
        if (automatic && current == null && Files.exists(target)) {
            String text = UiText.get("status.msg.autosaveSkipped", PlanMarkdownReader.nameWithoutExtension(target));
            context.setAutosaveProblem(text);
            context.status(StatusLevel.WARN, "status.msg.autosaveSkipped", PlanMarkdownReader.nameWithoutExtension(target));
            return;
        }
        saving = true;
        Consumer<String> answer = button -> {
            saving = false;
            if (!sameDocument(expected, current)) return;
            if (AlertCatalog.BUTTON_OVERWRITE.equals(button)) write(target, automatic, onSaved);
            else if (AlertCatalog.BUTTON_RELOAD.equals(button)) {
                if (load(target, true)) context.setAutosaveProblem("");
                else if (automatic) context.setAutosaveProblem(UiText.get("s2.file.autosaveReloadFailed", target));
            } else if (automatic) {
                context.setAutosaveProblem(UiText.get("s2.file.autosaveCancelled", target));
            }
        };
        if (current != null && context.externalChanges().changedExternally(current)) {
            ask(AlertCatalog.externalChange(PlanMarkdownReader.nameWithoutExtension(target)), answer);
        } else if (current == null && Files.exists(target)) {
            ask(AlertCatalog.overwriteOnFirstSave(PlanMarkdownReader.nameWithoutExtension(target)), answer);
        } else {
            saving = false;
            write(target, automatic, onSaved);
        }
    }

    /** Записывает документ; продолжение вызывается за пределами обработки файловой ошибки. */
    private void write(Path target, boolean automatic, Runnable onSaved) {
        try {
            repository().save(context.document().plan(), target);
        } catch (IOException | RuntimeException failure) {
            if (automatic) context.setAutosaveProblem(UiText.get("s2.file.autosaveFailed", target, failure.getMessage()));
            else error("save", failure, target);
            return;
        }
        saved(target, automatic, onSaved);
    }

    /** Синхронизирует документ, недавние файлы, внешнюю метку и статус после успешной записи. */
    private void saved(Path target, boolean automatic, Runnable onSaved) {
        context.document().markSaved(target);
        context.externalChanges().remember(target);
        context.updateSettings(settings -> settings.withPlanOpened(target.toAbsolutePath().normalize().toString()));
        context.setAutosaveProblem("");
        if (!automatic) context.status(StatusLevel.SUCCESS, "status.msg.saved", target);
        context.refresh();
        if (onSaved != null) onSaved.run();
    }

    /** Открывает мастер, обрабатывая создание, пример и отмену. */
    private void wizard() {
        // JavaFX: Dialog → Swing: SwingDialog → Web: dialog.
        context.openForm(FormRequest.fresh(new NewPlanWizardForm(), WindowType.NEW_PLAN_WIZARD, true, Map.of()), null,
                result -> {
                    if (result instanceof NewPlanWizardForm.OpenSample) sample();
                    else if (result instanceof NewPlanWizardForm.Created created) {
                        Plan plan = created.plan();
                        Path file = repository().pathFor(plan.name());
                        if (Files.exists(file)) throw new IllegalArgumentException(UiText.get("val.plan.exists", plan.name()));
                        replace(plan, null, true, List.of());
                        try {
                            repository().save(plan, file);
                            saved(file, true, null);
                            context.status(StatusLevel.SUCCESS, "status.msg.created", plan.name());
                        } catch (IOException | RuntimeException failure) {
                            error("createdNotSaved", failure);
                        }
                    }
                });
    }

    /** Показывает список после подтверждения; «Из файла» продолжает уже разрешённое открытие. */
    private void openList() {
        List<ru.cashprediction.core.io.PlanFileInfo> plans;
        try {
            plans = new PlanRepository(context.state().plansFolder()).list();
        } catch (RuntimeException failure) {
            error("readPlansFolder", failure);
            return;
        }
        // JavaFX: Dialog → Swing: SwingDialog → Web: dialog.
        context.openForm(FormRequest.fresh(new OpenPlanForm(plans), WindowType.CHOICE, true,
                Map.of("purpose", OpenPlanForm.PURPOSE)), null, result -> {
            if (result instanceof Path path) load(path, false);
            else if (OpenPlanForm.FROM_FILE.equals(result)) chooseOpen();
        });
    }

    /** Выбирает файл без повторного вопроса о правках после уже разрешённого входа. */
    private void chooseOpen() {
        context.choosers().chooseFile(chooser(FileChooserSpec.Purpose.OPEN_PLAN, "md",
                context.state().plansFolder(), ""), result -> result.ifPresent(path -> load(path, false)));
    }

    /** Читает план до изменения документа; при ошибке прежний документ и история сохраняются. */
    private boolean load(Path path, boolean reloaded) {
        Path file = path.toAbsolutePath().normalize();
        try {
            var read = repository().load(file, context.environment().clock().today());
            replace(read.plan(), file, false, read.diagnostics());
            context.updateSettings(settings -> settings.withPlanOpened(file.toString()));
            if (reloaded) context.status(StatusLevel.INFO, "status.msg.reloaded");
            else context.status(StatusLevel.INFO, "status.msg.opened", read.plan().name());
            if (read.diagnostics().stream().anyMatch(value -> value.severity() != Severity.INFO)) {
                ask(AlertCatalog.loadDiagnostics(PlanMarkdownReader.nameWithoutExtension(file), read.diagnostics()), ignored -> { });
            }
            return true;
        } catch (MarkdownParseException failure) {
            error("notPlan", failure, file);
        } catch (IOException | RuntimeException failure) {
            error("readPlan", failure, file);
        }
        return false;
    }

    /** Сбрасывает прежние окна быстрой правки и выделение, устанавливая новый документ. */
    private void replace(Plan plan, Path file, boolean dirty, List<ru.cashprediction.core.diagnostics.Diagnostic> diagnostics) {
        context.singleInstance(WindowType.QUICK_EDIT_POPUP.name()).ifPresent(FormSession::closeRequested);
        context.document().replace(plan, file, dirty, diagnostics);
        context.setSelection("");
        context.setPastExpanded(false);
        context.externalChanges().remember(file);
        context.setAutosaveProblem("");
        context.refresh();
        if (context.state().document().forecastAvailable()) {
            var row = context.document().visibleRows().stream()
                    .filter(value -> !value.date().isBefore(context.environment().clock().today())).findFirst();
            row.ifPresent(value -> context.port().revealRow(value.rowId(), RevealMode.SCROLL_TO_TOP));
        }
    }

    /** Открывает пример как изменённый документ без файла. */
    private void sample() {
        replace(SamplePlan.create(context.environment().clock().today()), null, true, List.of());
        context.status(StatusLevel.INFO, "status.msg.sample");
    }

    /** @return репозиторий для автоматических имён в CashMemory */
    private PlanRepository repository() { return new PlanRepository(context.environment().cashMemory()); }

    /** @return безопасное базовое имя текущего файла либо плана */
    private String baseName() {
        return context.document().file().map(PlanMarkdownReader::nameWithoutExtension)
                .orElseGet(() -> PlanRepository.fileBaseName(context.document().plan().name()));
    }

    /** Проверяет, что ответ относится к тому документу, для которого открыли запрос. */
    private boolean sameDocument(Plan expected, Path file) {
        return expected.equals(context.document().plan()) && Objects.equals(file, context.document().file().orElse(null));
    }

    /** Создаёт запрос выбора с общими текстами и расширениями. */
    private FileChooserSpec chooser(FileChooserSpec.Purpose purpose, String extension, Path folder, String name) {
        String title = switch (purpose) {
            case OPEN_PLAN -> UiText.get("s2.file.openTitle");
            case SAVE_PLAN_AS -> UiText.get("s2.file.saveAsTitle");
            case EXPORT_CSV -> UiText.get("dialog.csv.title");
            case SAVE_PNG -> UiText.get("s2.file.pngTitle");
            case SAVE_SNAPSHOT_PLAN -> UiText.get("s2.file.snapshotTitle");
        };
        String filter = switch (extension) {
            case "csv" -> UiText.get("s2.file.csvFilter");
            case "png" -> UiText.get("s2.file.pngFilter");
            default -> UiText.get("s2.file.planFilter");
        };
        return new FileChooserSpec(purpose, purpose == FileChooserSpec.Purpose.OPEN_PLAN
                ? FileChooserSpec.Mode.OPEN : FileChooserSpec.Mode.SAVE, title, filter, List.of(extension), folder, name);
    }

    /** Показывает сообщение с защитой от повторного ответа. */
    private void ask(AlertSpec spec, Consumer<String> result) {
        AtomicBoolean answered = new AtomicBoolean();
        // JavaFX: Alert → Swing: SwingAlert → Web: dialog.
        context.showAlert(spec, button -> { if (answered.compareAndSet(false, true)) result.accept(button); });
    }

    /** Показывает локализованную ошибку со свёрнутым стеком. */
    private void error(String key, Throwable failure, Object... args) {
        ask(AlertCatalog.error(key, failure, args), ignored -> { });
    }

    /** Дополняет общую форму имени проверкой коллизии файла до разрешения подтверждающей кнопки. */
    static final class RenameForm implements FormLogic {
        // JavaFX: TextInputDialog → Swing: SwingTextInputDialog → Web: dialog.
        private final FormLogic delegate = TextInputForms.rename();

        /** {@inheritDoc} */
        @Override public FormSpec spec(FormContext form) { return delegate.spec(form); }
        /** {@inheritDoc} */
        @Override public Map<String, String> defaults(FormContext form) { return delegate.defaults(form); }
        /** {@inheritDoc} */
        @Override public FormView evaluate(FormState state, FormContext form) {
            FormView view = delegate.evaluate(state, form);
            String problem = collision(state.value("value"), form);
            if (problem == null) return view;
            return new FormView(view.revision(), view.page(), view.header(), view.fields(), Problem.error(problem),
                    Map.of("rename", ru.cashprediction.core.ui.form.ButtonView.DISABLED), view.results(),
                    view.preview(), view.details(), view.detailsExpanded());
        }
        /** {@inheritDoc} */
        @Override public FormOutcome onButton(String button, FormState state, FormContext form) {
            if ("cancel".equals(button)) return delegate.onButton(button, state, form);
            String problem = collision(state.value("value"), form);
            return problem == null ? delegate.onButton(button, state, form) : new FormOutcome.Stay(Problem.error(problem));
        }

        /** Возвращает первую ошибку проверки целевого файла либо null. */
        private String collision(String name, FormContext form) {
            if (PlanValidator.checkPlanName(name).isPresent()) return null;
            Path file = form.app().document().fileOptional().orElse(null);
            if (file == null) return null;
            Path target = file.resolveSibling(PlanRepository.fileBaseName(name.strip()) + ".md");
            try {
                return Files.exists(target) && !Files.isSameFile(file, target)
                        ? UiText.get("s2.file.renameExists", name.strip()) : null;
            } catch (IOException | RuntimeException failure) {
                return UiText.get("s2.file.renameFailed", failure.getMessage());
            }
        }
    }
}
