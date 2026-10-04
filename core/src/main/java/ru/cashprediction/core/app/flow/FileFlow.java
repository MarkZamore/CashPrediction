package ru.cashprediction.core.app.flow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;
import ru.cashprediction.core.service.storage.PlanStorageException;
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
    private final PlanStorage storage;
    private boolean saving;
    private boolean discardPrompt;

    /** @param context контекст контроллера, доступный только в его потоке */
    public FileFlow(FlowContext context) {
        this(context, context.externalChanges().storage());
    }

    /**
     * @param context контекст контроллера
     * @param storage общий сервис сохранённых планов
     */
    public FileFlow(FlowContext context, PlanStorage storage) {
        this.context = Objects.requireNonNull(context, "context");
        this.storage = Objects.requireNonNull(storage, "storage");
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
                var observed = storage.version(FilePlanStorage.reference(file)).requireValue();
                if (PlanStorage.Version.ABSENT.equals(observed)) {
                    context.updateSettings(settings -> settings.withRecentPlanRemoved(path));
                    error("recentMissing", null, file);
                    return;
                }
                load(file, false, Optional.of(observed));
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
    public void saveAs() { saveAs(null); }

    /** Сохранение импортированной копии продолжает исходное действие только после успешной записи. */
    private void saveAs(Runnable onSaved) {
        if (saving) return;
        Plan expected = context.document().plan();
        Path oldFile = context.document().file().orElse(null);
        Path folder = context.environment().cashMemory();
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
                var reference = FilePlanStorage.reference(target);
                var version = storage.version(reference).requireValue();
                if (oldFile != null && reference.equals(FilePlanStorage.reference(oldFile))) {
                    version = context.externalChanges().expectedVersion(reference, version);
                }
                Runnable afterWrite = () -> {
                    if (rename) context.edits().edit(UiText.get("undo.saveAsName", name), "",
                            new ru.cashprediction.core.service.plan.PlanCommand.RenamePlan(name));
                };
                write(target, expected, oldFile, written, version, false, afterWrite, onSaved);
            } catch (RuntimeException failure) {
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
            if (context.edits().edit(UiText.get("undo.rename"), "",
                    new ru.cashprediction.core.service.plan.PlanCommand.RenamePlan(name))) {
                context.status(StatusLevel.INFO, "status.msg.renamed", name);
            }
            return;
        }
        if (!context.externalChanges().canWrite(oldFile)) {
            throw new IllegalArgumentException(UiText.get("s2.file.importedReadOnly", oldFile,
                    context.environment().cashMemory()));
        }
        try {
            var reference = FilePlanStorage.reference(oldFile);
            var observed = storage.version(reference).requireValue();
            var expected = context.externalChanges().expectedVersion(reference, observed);
            var result = storage.rename(reference, name, expected);
            if (!result.succeeded()) {
                var problem = result.problem();
                if (problem.conflict() == PlanStorage.Conflict.NAME_EXISTS) {
                    throw new IllegalArgumentException(UiText.get("s2.file.renameExists", name));
                }
                if (problem.code() == PlanStorage.Code.CONFLICT || problem.code() == PlanStorage.Code.MISSING) {
                    throw new IllegalArgumentException(UiText.get("alert.external.header",
                            PlanMarkdownReader.nameWithoutExtension(oldFile)));
                }
                throw new PlanStorageException(problem);
            }
            var stored = result.value();
            Path renamed = FilePlanStorage.path(stored.reference());
            // Переименование не сохраняет прежние несохранённые правки автоматически.
            boolean dirty = context.document().isDirty();
            Plan renamedPlan = context.document().plan().withName(name);
            context.document().replace(renamedPlan, renamed, dirty, context.document().loadDiagnostics());
            context.externalChanges().remember(stored.reference(), stored.version());
            context.updateSettings(settings -> settings.withRecentPlanRemoved(oldFile.toString())
                    .withRecentPlanRemoved(oldFile.getFileName().toString()).withPlanOpened(renamed.toString()));
            context.status(StatusLevel.INFO, "status.msg.renamed", name);
            context.refresh();
        } catch (PlanStorageException failure) {
            throw new UncheckedIOException(UiText.get("s2.file.renameFailed", failure.getMessage()),
                    new IOException(failure.getMessage(), failure));
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
                    AtomicFiles.writeStringScoped(context.environment().cashMemory(), file.get(),
                            CsvExporter.toCsv(state.document().forecast(), options), true, () -> { });
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
                AtomicFiles.writeScoped(context.environment().cashMemory(), file.get(), bytes);
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
        // Внешний Markdown доступен для чтения/правок в памяти, но не для application-managed записи.
        if (current != null && !context.externalChanges().canWrite(current)) {
            if (automatic) context.setAutosaveProblem(UiText.get("s2.file.importedReadOnly", current,
                    context.environment().cashMemory()));
            else saveAs(onSaved);
            return;
        }
        Path target = current == null ? FilePlanStorage.pathFor(context.environment().cashMemory(), expected.name()) : current;
        var reference = FilePlanStorage.reference(target);
        PlanStorage.Version observed;
        try {
            observed = storage.version(reference).requireValue();
        } catch (RuntimeException failure) {
            saveFailure(target, automatic, failure);
            return;
        }
        if (automatic && current == null && !PlanStorage.Version.ABSENT.equals(observed)) {
            autosaveSkipped(target);
            return;
        }
        var version = current == null ? PlanStorage.Version.ABSENT
                : context.externalChanges().expectedVersion(reference, observed);
        if (!version.equals(observed) || current != null && PlanStorage.Version.ABSENT.equals(observed)) {
            promptOverwrite(target, expected, current, expected, observed, automatic, () -> { }, onSaved);
        } else {
            write(target, expected, current, expected, version, automatic, () -> { }, onSaved);
        }
    }

    /** Показывает прежние варианты решения, связывая ответ с показанной версией и документом. */
    private void promptOverwrite(Path target, Plan expected, Path current, Plan written,
            PlanStorage.Version observed, boolean automatic, Runnable afterWrite, Runnable onSaved) {
        saving = true;
        Consumer<String> answer = button -> {
            saving = false;
            if (!sameDocument(expected, current)) return;
            if (AlertCatalog.BUTTON_OVERWRITE.equals(button)) {
                write(target, expected, current, written, observed, automatic, afterWrite, onSaved);
            }
            else if (AlertCatalog.BUTTON_RELOAD.equals(button)) {
                if (load(target, true, Optional.of(observed))) context.setAutosaveProblem("");
                else if (automatic) context.setAutosaveProblem(UiText.get("s2.file.autosaveReloadFailed", target));
            } else if (automatic) {
                context.setAutosaveProblem(UiText.get("s2.file.autosaveCancelled", target));
            }
        };
        ask(current == null ? AlertCatalog.overwriteOnFirstSave(PlanMarkdownReader.nameWithoutExtension(target))
                : AlertCatalog.externalChange(PlanMarkdownReader.nameWithoutExtension(target)), answer);
    }

    /** Записывает снимок с обязательной версией; повторный конфликт не теряет правки и не вызывает продолжение. */
    private void write(Path target, Plan expected, Path current, Plan written, PlanStorage.Version version,
            boolean automatic, Runnable afterWrite, Runnable onSaved) {
        PlanStorage.Result<PlanStorage.Stored> result;
        try {
            result = storage.write(FilePlanStorage.reference(target), written, version);
        } catch (RuntimeException failure) {
            saveFailure(target, automatic, failure);
            return;
        }
        if (!result.succeeded()) {
            if (result.problem().code() != PlanStorage.Code.CONFLICT) {
                saveFailure(target, automatic, new PlanStorageException(result.problem()));
                return;
            }
            if (automatic && current == null) { autosaveSkipped(target); return; }
            PlanStorage.Version observed;
            try { observed = storage.version(FilePlanStorage.reference(target)).requireValue(); }
            catch (RuntimeException failure) { saveFailure(target, automatic, failure); return; }
            promptOverwrite(target, expected, current, written, observed, automatic, afterWrite, onSaved);
            return;
        }
        afterWrite.run();
        saved(result.value(), automatic, onSaved);
    }

    /** Объясняет прежний пропуск автосохранения первого файла без подтверждения перезаписи. */
    private void autosaveSkipped(Path target) {
        context.setAutosaveProblem(UiText.get("status.msg.autosaveSkipped", PlanMarkdownReader.nameWithoutExtension(target)));
        context.status(StatusLevel.WARN, "status.msg.autosaveSkipped", PlanMarkdownReader.nameWithoutExtension(target));
    }

    /** Сохраняет прежнее различие ручной ошибки и проблемы автосохранения. */
    private void saveFailure(Path target, boolean automatic, RuntimeException failure) {
        String detail = failure.getMessage();
        if (failure instanceof PlanStorageException && (detail == null || detail.isBlank())) detail = UiText.get("err.generic");
        if (automatic) context.setAutosaveProblem(UiText.get("s2.file.autosaveFailed", target, detail));
        else error("save", failure, target);
    }

    /** Синхронизирует документ, недавние файлы, внешнюю метку и статус после успешной записи. */
    private void saved(PlanStorage.Stored stored, boolean automatic, Runnable onSaved) {
        Path target = FilePlanStorage.path(stored.reference());
        context.document().markSaved(target);
        context.externalChanges().remember(stored.reference(), stored.version());
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
                        Path file = FilePlanStorage.pathFor(context.environment().cashMemory(), plan.name());
                        var reference = FilePlanStorage.reference(file);
                        var observed = storage.version(reference);
                        if (observed.succeeded() && !PlanStorage.Version.ABSENT.equals(observed.value())) {
                            throw new IllegalArgumentException(UiText.get("val.plan.exists", plan.name()));
                        }
                        // Первый render/reveal нового плана уже использует выбранный период, даже при ошибке записи.
                        context.updateView(view -> view.withPeriod(created.displayPeriod()));
                        replace(plan, null, true, List.of());
                        try {
                            observed.requireValue();
                            var stored = storage.write(reference, plan, PlanStorage.Version.ABSENT).requireValue();
                            saved(stored, true, null);
                            context.status(StatusLevel.SUCCESS, "status.msg.created", plan.name());
                        } catch (RuntimeException failure) {
                            error("createdNotSaved", failure);
                        }
                    }
                });
    }

    /** Показывает список после подтверждения; «Из файла» продолжает уже разрешённое открытие. */
    private void openList() {
        List<PlanFileInfo> plans;
        List<PlanStorage.Entry> entries;
        try {
            entries = storage.list(FilePlanStorage.collection(context.state().plansFolder())).requireValue();
            // Тип файловой формы остаётся на границе выбора; бизнес-список не содержит Path и FileTime.
            plans = entries.stream().map(entry -> new PlanFileInfo(entry.name(), FilePlanStorage.path(entry.reference()),
                    FileTime.from(entry.modifiedAt()))).toList();
        } catch (RuntimeException failure) {
            error("readPlansFolder", failure);
            return;
        }
        // JavaFX: Dialog → Swing: SwingDialog → Web: dialog.
        context.openForm(FormRequest.fresh(new OpenPlanForm(plans), WindowType.CHOICE, true,
                Map.of("purpose", OpenPlanForm.PURPOSE)), null, result -> {
            if (result instanceof Path path) {
                var expected = entries.stream().filter(entry -> entry.reference().equals(FilePlanStorage.reference(path)))
                        .findFirst().map(PlanStorage.Entry::version);
                load(path, false, expected);
            }
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
        return load(path, reloaded, Optional.empty());
    }

    /** Не заменяет документ, если выбранный в списке или сообщении снимок уже изменился. */
    private boolean load(Path path, boolean reloaded, Optional<PlanStorage.Version> expectedVersion) {
        Path file = path.toAbsolutePath().normalize();
        try {
            var result = storage.read(FilePlanStorage.reference(file), context.environment().clock().today(), expectedVersion);
            if (!result.succeeded()) {
                error(result.problem().code() == PlanStorage.Code.CORRUPT ? "notPlan" : "readPlan",
                        new PlanStorageException(result.problem()), file);
                return false;
            }
            var read = result.value();
            context.externalChanges().remember(read.reference(), read.version());
            replace(read.plan(), file, false, read.diagnostics());
            context.updateSettings(settings -> settings.withPlanOpened(file.toString()));
            if (!context.externalChanges().canWrite(file)) context.status(StatusLevel.INFO, "s2.file.importedReadOnly",
                    file, context.environment().cashMemory());
            else if (reloaded) context.status(StatusLevel.INFO, "status.msg.reloaded");
            else context.status(StatusLevel.INFO, "status.msg.opened", read.plan().name());
            if (read.diagnostics().stream().anyMatch(value -> value.severity() != Severity.INFO)) {
                ask(AlertCatalog.loadDiagnostics(PlanMarkdownReader.nameWithoutExtension(file), read.diagnostics()), ignored -> { });
            }
            return true;
        } catch (RuntimeException failure) {
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
        if (file == null) context.externalChanges().forget();
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

    /** @return безопасное базовое имя текущего файла либо плана */
    private String baseName() {
        return context.document().file().map(PlanMarkdownReader::nameWithoutExtension)
                .orElseGet(() -> FilePlanStorage.fileBaseName(context.document().plan().name()));
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
        Throwable shown = failure instanceof PlanStorageException
                && (failure.getMessage() == null || failure.getMessage().isBlank())
                ? new IOException(UiText.get("err.generic"), failure) : failure;
        ask(AlertCatalog.error(key, shown, args), ignored -> { });
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
            Path target = file.resolveSibling(FilePlanStorage.fileBaseName(name.strip()) + ".md");
            try {
                return Files.exists(target) && !Files.isSameFile(file, target)
                        ? UiText.get("s2.file.renameExists", name.strip()) : null;
            } catch (IOException | RuntimeException failure) {
                return UiText.get("s2.file.renameFailed", failure.getMessage());
            }
        }
    }
}
