package ru.cashprediction.fx.action;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.image.WritableImage;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.export.CsvExporter;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.MarkdownParseException;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.fx.dialog.AppButtonTypes;
import ru.cashprediction.fx.dialog.CsvExportDialog;
import ru.cashprediction.fx.dialog.Dialogs;
import ru.cashprediction.fx.dialog.NewPlanWizard;
import ru.cashprediction.fx.dialog.OpenRequest;
import ru.cashprediction.fx.dialog.StatefulChoiceDialog;
import ru.cashprediction.fx.dialog.StatefulTextInputDialog;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Команды меню «Файл»: новый план, открыть, пример, сохранить, сохранить как, переименовать, экспорт CSV,
 * папка CashMemory.
 *
 * <p>Здесь же обнаружение правки файла снаружи: при открытии и сохранении запоминается время изменения
 * файла, и если перед сохранением оно другое — пользователь выбирает «Перезаписать» или «Перечитать».</p>
 *
 * <p>Ничего не пишется за пределами CashMemory без явного выбора пользователя: новый план сохраняется
 * в CashMemory, в другое место — только через «Сохранить как…» и экспорт CSV, где путь выбирает человек.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
final class FileCommands {

    /** Пункт списка «Открыть план», открывающий выбор файла. */
    static final String FROM_FILE = "Из файла…";
    /** Имя плана, если мастер нового плана при старте отменён. */
    static final String DEFAULT_PLAN_NAME = "Мой план";

    private final CommandSupport support;
    /** Файл, время изменения которого запомнено. */
    private Path trackedFile;
    private FileTime trackedTime;

    FileCommands(CommandSupport support) {
        this.support = support;
    }

    // ------------------------------------------------------------------ учёт времени изменения

    /**
     * Запоминает время изменения файла открытого плана.
     *
     * @param file файл или {@code null}, если план не связан с файлом
     */
    void trackFile(Path file) {
        trackedFile = file;
        trackedTime = null;
        if (file != null) {
            try {
                trackedTime = Files.getLastModifiedTime(file);
            } catch (IOException e) {
                // Файла ещё нет или атрибуты не читаются: проверять будет нечего, это не ошибка.
            }
        }
    }

    private boolean changedOnDisk(Path file) {
        if (trackedTime == null || !file.equals(trackedFile)) {
            return false;
        }
        try {
            return !Files.getLastModifiedTime(file).equals(trackedTime);
        } catch (IOException e) {
            // Файл удалили снаружи: при сохранении он просто будет создан заново.
            return false;
        }
    }

    // ------------------------------------------------------------------ несохранённые изменения

    /**
     * Перед заменой открытого плана спрашивает, сохранить ли несохранённые изменения.
     *
     * @param proceed что сделать, если пользователь не отменил
     */
    void confirmDiscard(Runnable proceed) {
        if (!support.document().isDirty()) {
            proceed.run();
            return;
        }
        askSaveChanges(answer -> {
            if (answer) {
                proceed.run();
            }
        });
    }

    /**
     * «Сохранить изменения в плане?» [Сохранить][Не сохранять][Отмена].
     *
     * @param proceed {@code true} — можно продолжать (сохранено или «Не сохранять»), {@code false} — отмена
     */
    void askSaveChanges(Consumer<Boolean> proceed) {
        // JavaFX: Alert(CONFIRMATION) + ButtonType → Swing: JOptionPane.showOptionDialog(SwingButtonType[]) → Web: <dialog class="alert"> с <button value>
        var alert = Dialogs.alert(AlertType.CONFIRMATION, "Несохранённые изменения",
                "Сохранить изменения в плане «" + support.plan().name() + "»?",
                "Если не сохранить, изменения будут потеряны.",
                AppButtonTypes.SAVE, AppButtonTypes.DONT_SAVE, AppButtonTypes.CANCEL);
        support.host().show(alert, WindowState.MAIN_OWNER, result -> {
            ButtonType button = result.orElse(AppButtonTypes.CANCEL);
            if (button == AppButtonTypes.SAVE) {
                save(proceed);
            } else {
                proceed.accept(button == AppButtonTypes.DONT_SAVE);
            }
        });
    }

    // ------------------------------------------------------------------ новый план, открыть, пример

    /**
     * Диалог 1 «Новый план».
     *
     * @param request     запрос открытия
     * @param onCancelled что сделать при отмене мастера или {@code null}
     */
    void openWizard(OpenRequest request, Runnable onCancelled) {
        LocalDate today = support.context().today();
        // JavaFX: Dialog<R> → Swing: SwingDialog<R> (NewPlanWizard) → Web: openDialog('newPlan'): Promise<R>
        NewPlanWizard wizard = new NewPlanWizard(today, freeName(DEFAULT_PLAN_NAME), this::planFileExists);
        support.host().open(wizard, request, result -> result.ifPresentOrElse(this::createPlan, () -> {
            if (onCancelled != null) {
                onCancelled.run();
            }
        }));
    }

    /** Мастер при старте без планов: отмена открывает пустой «Мой план», не сохраняя его. */
    void openWizardOnStartup() {
        openWizard(OpenRequest.fromMain(), () -> {
            support.context().openPlanDocument(Plan.empty(freeName(DEFAULT_PLAN_NAME), support.context().today()),
                    null, false, List.of());
            trackFile(null);
        });
    }

    private void createPlan(Plan plan) {
        Path file = support.context().layout().plans().pathFor(plan.name());
        try {
            support.context().layout().plans().save(plan, file);
            support.context().openPlanDocument(plan, file, false, List.of());
            afterOpened(file);
        } catch (IOException e) {
            // План не должен пропасть из-за диска: открываем его несохранённым, его защищает снимок сессии.
            support.context().openPlanDocument(plan, null, true, List.of());
            trackFile(null);
            support.error("План создан, но не сохранён в CashMemory", e);
        }
    }

    /**
     * Диалог 10 «Открыть план»: список планов текущей папки и пункт «Из файла…».
     *
     * @param request запрос открытия
     */
    void openPlan(OpenRequest request) {
        Path folder = support.plansFolder();
        Map<String, Path> plans = new LinkedHashMap<>();
        try {
            for (PlanFileInfo info : new PlanRepository(folder).list()) {
                plans.put(info.name(), info.path());
            }
        } catch (UncheckedIOException e) {
            support.cannotOpen(request, "Не удалось прочитать папку планов", e.getMessage());
            return;
        }
        List<String> items = new ArrayList<>(plans.keySet());
        items.add(FROM_FILE);
        String current = support.document().file().map(PlanMarkdownReader::nameWithoutExtension).orElse(null);
        String defaultChoice = current != null && plans.containsKey(current) ? current : items.getFirst();
        // JavaFX: ChoiceDialog<T> → Swing: JOptionPane.showInputDialog(..., selectionValues[], initial) → Web: <dialog> с <select>
        // В заголовке только имя папки: полный путь к CashMemory растягивал диалог шире экрана
        // (полный путь показывает «Файл → Папка CashMemory…»).
        String folderName = folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
        StatefulChoiceDialog<String> dialog = new StatefulChoiceDialog<>("openPlan", "Открыть план",
                plans.isEmpty() ? "В папке «" + folderName + "» пока нет планов" : "Планы в папке «" + folderName + "»",
                "План:", defaultChoice, items, AppButtonTypes.OPEN, s -> s, s -> s);
        support.host().open(dialog, request, result -> result.ifPresent(choice -> {
            if (FROM_FILE.equals(choice)) {
                chooseFileAndLoad();
            } else {
                Path file = plans.get(choice);
                if (file != null) {
                    loadPlan(file);
                }
            }
        }));
    }

    /** Диалог 12 «Открыть из файла». */
    void chooseFileAndLoad() {
        // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: обозреватель /api/fs?mode=md + <input type="file">
        File chosen = Dialogs.planChooser("Открыть план из файла", support.plansFolder(), null)
                .showOpenDialog(support.ownerWindow());
        if (chosen != null) {
            loadPlan(chosen.toPath());
        }
    }

    /**
     * Открывает план из недавних (имя из настроек: имя файла в CashMemory или полный путь).
     *
     * @param settingsName имя из «Недавние планы»
     */
    void openRecent(String settingsName) {
        Path file = support.context().layout().dir().resolve(settingsName);
        if (!Files.exists(file)) {
            support.context().updateSettings(s -> s.withRecentPlanRemoved(settingsName));
            support.error("Файл плана не найден", "Файла «" + file + "» больше нет - он убран из списка недавних.");
            return;
        }
        loadPlan(file);
    }

    /**
     * Читает план из файла при обычном открытии: замечания и ошибки показываются сообщениями.
     *
     * @param file файл плана
     * @return {@code true}, если план открыт
     */
    boolean loadPlan(Path file) {
        return loadPlan(file, null);
    }

    /**
     * Читает план из файла и делает его открытым документом.
     *
     * <p>При восстановлении сессии ({@code warn != null}) проблемы не показываются модальными окнами, а уходят
     * в отчёт восстановления: координатор открывает окна снимка по цепочке, и лишний Alert поверх неё только мешал бы.</p>
     *
     * @param file файл плана
     * @param warn приёмник замечаний при восстановлении или {@code null} для обычного открытия
     * @return {@code true}, если план открыт
     */
    boolean loadPlan(Path file, Consumer<String> warn) {
        ReadResult result;
        try {
            result = support.context().layout().plans().load(file, support.context().today());
        } catch (NoSuchFileException e) {
            support.context().updateSettings(s -> s.withRecentPlanRemoved(support.settingsName(file)));
            report(warn, "Файл плана не найден", file.toString());
            return false;
        } catch (IOException e) {
            if (warn != null) {
                warn.accept("Не удалось прочитать план «" + file.getFileName() + "»: " + e.getMessage());
            } else {
                support.error("Не удалось прочитать план «" + file.getFileName() + "»", e);
            }
            return false;
        } catch (MarkdownParseException e) {
            report(warn, "Файл «" + file.getFileName() + "» не похож на план CashPrediction", e.getMessage());
            return false;
        }
        support.context().openPlanDocument(result.plan(), file, false, result.diagnostics());
        afterOpened(file);
        if (warn == null) {
            support.showLoadDiagnostics(file.getFileName().toString(), result.diagnostics());
        } else if (result.hasWarnings()) {
            warn.accept("План «" + file.getFileName() + "» прочитан с замечаниями: Инструменты → Проверить план");
        }
        return true;
    }

    private void report(Consumer<String> warn, String header, String message) {
        if (warn != null) {
            warn.accept(header + ": " + message);
        } else {
            support.error(header, message);
        }
    }

    /** «Открыть пример»: несохранённый план «Пример». */
    void openSample() {
        support.context().openPlanDocument(SamplePlan.samplePlan(support.context().today()), null, true, List.of());
        trackFile(null);
    }

    private void afterOpened(Path file) {
        trackFile(file);
        String name = support.settingsName(file);
        support.context().updateSettings(s -> s.withPlanOpened(name));
    }

    // ------------------------------------------------------------------ сохранение

    /**
     * «Сохранить» (Ctrl+S): в файл плана или, для нового плана, в CashMemory под его именем.
     *
     * @param done {@code true}, если план сохранён
     */
    void save(Consumer<Boolean> done) {
        Optional<Path> file = support.document().file();
        if (file.isPresent()) {
            if (changedOnDisk(file.get())) {
                askChangedOnDisk(file.get(), done);
            } else {
                writeTo(file.get(), done);
            }
            return;
        }
        Path target = support.context().layout().plans().pathFor(support.plan().name());
        if (!Files.exists(target)) {
            writeTo(target, done);
            return;
        }
        // JavaFX: Alert(CONFIRMATION) → Swing: JOptionPane.showOptionDialog → Web: <dialog class="alert">
        var alert = Dialogs.alert(AlertType.CONFIRMATION, "Сохранение",
                "В CashMemory уже есть файл «" + target.getFileName() + "»",
                "Перезаписать его текущим планом? Чтобы сохранить под другим именем, выберите «Отмена» и «Сохранить как…».",
                AppButtonTypes.OVERWRITE, AppButtonTypes.CANCEL);
        support.host().show(alert, WindowState.MAIN_OWNER, result -> {
            if (result.orElse(AppButtonTypes.CANCEL) == AppButtonTypes.OVERWRITE) {
                writeTo(target, done);
            } else {
                done.accept(false);
            }
        });
    }

    /**
     * Автосохранение после правки: сохраняет план, связанный с файлом, и новый план, если его имя в CashMemory свободно.
     * Если файл изменён снаружи, спрашивает так же, как «Сохранить».
     */
    void autosave() {
        if (!support.document().isDirty()) {
            return;
        }
        if (support.document().file().isPresent()) {
            save(ok -> { });
            return;
        }
        Path target = support.context().layout().plans().pathFor(support.plan().name());
        if (!Files.exists(target)) {
            writeTo(target, ok -> { });
        }
    }

    /** Диалог 18 «Файл изменён снаружи». */
    private void askChangedOnDisk(Path file, Consumer<Boolean> done) {
        // JavaFX: Alert(WARNING) + ButtonType «Перезаписать/Перечитать» → Swing: JOptionPane.showOptionDialog → Web: <dialog class="alert">
        var alert = Dialogs.alert(AlertType.WARNING, "Файл изменён снаружи",
                "Файл «" + file.getFileName() + "» изменён другой программой после открытия",
                "«Перезаписать» - сохранить план из CashPrediction поверх чужих правок.\n"
                        + "«Перечитать» - открыть файл заново; несохранённые изменения в программе будут потеряны.",
                AppButtonTypes.OVERWRITE, AppButtonTypes.RELOAD, AppButtonTypes.CANCEL);
        support.host().show(alert, WindowState.MAIN_OWNER, result -> {
            ButtonType button = result.orElse(AppButtonTypes.CANCEL);
            if (button == AppButtonTypes.OVERWRITE) {
                writeTo(file, done);
            } else {
                if (button == AppButtonTypes.RELOAD) {
                    loadPlan(file);
                }
                done.accept(false);
            }
        });
    }

    private void writeTo(Path file, Consumer<Boolean> done) {
        try {
            support.context().layout().plans().save(support.plan(), file);
            support.document().markSaved(file);
            afterOpened(file);
            done.accept(true);
        } catch (IOException | RuntimeException e) {
            support.error("Не удалось сохранить план в «" + file + "»", e);
            done.accept(false);
        }
    }

    /**
     * Диалог 12 «Сохранить как…». Имя плана становится именем выбранного файла: при чтении файла имя
     * берётся из имени файла, и так план не «переименуется» сам при следующем открытии.
     *
     * @param done {@code true}, если план сохранён
     */
    void saveAs(Consumer<Boolean> done) {
        Path initialDir = support.document().file().map(Path::getParent).orElse(support.context().layout().dir());
        // JavaFX: FileChooser.showSaveDialog → Swing: JFileChooser.showSaveDialog → Web: POST /api/plans/save + ссылка на скачивание
        File chosen = Dialogs.planChooser("Сохранить план как", initialDir,
                PlanRepository.fileBaseName(support.plan().name()) + PlanRepository.EXTENSION).showSaveDialog(support.ownerWindow());
        if (chosen == null) {
            done.accept(false);
            return;
        }
        Path file = chosen.toPath();
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PlanRepository.EXTENSION)) {
            file = file.resolveSibling(file.getFileName() + PlanRepository.EXTENSION);
        }
        String newName = PlanMarkdownReader.nameWithoutExtension(file);
        if (PlanValidator.checkPlanName(newName).isEmpty() && !newName.equals(support.plan().name())) {
            support.edit("Сохранение под именем «" + newName + "»", p -> p.withName(newName));
        }
        writeTo(file, done);
    }

    // ------------------------------------------------------------------ переименование

    /**
     * Диалог 9 «Переименовать» (F2).
     *
     * @param request запрос открытия
     */
    void rename(OpenRequest request) {
        // JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog(parent, msg, title, QUESTION_MESSAGE, null, null, initial) → Web: <dialog> с <input>
        StatefulTextInputDialog dialog = new StatefulTextInputDialog("rename", "Переименовать план",
                "Новое имя плана «" + support.plan().name() + "»"
                        + (support.document().file().isPresent() ? "\nФайл плана переименуется вместе с ним." : ""),
                "Имя:", support.plan().name(), AppButtonTypes.RENAME, this::renameProblem, false);
        support.host().open(dialog, request, result -> result.map(String::strip).ifPresent(this::applyRename));
    }

    private Optional<String> renameProblem(String candidate) {
        Optional<String> invalid = PlanValidator.checkPlanName(candidate);
        if (invalid.isPresent()) {
            return invalid;
        }
        String name = candidate.strip();
        Optional<Path> file = support.document().file();
        Path target = file.map(f -> f.toAbsolutePath().resolveSibling(PlanRepository.fileBaseName(name) + PlanRepository.EXTENSION))
                .orElse(support.context().layout().plans().pathFor(name));
        try {
            boolean sameFile = file.isPresent() && Files.exists(target) && Files.isSameFile(file.get(), target);
            if (Files.exists(target) && !sameFile) {
                return Optional.of("Файл «" + target.getFileName() + "» уже существует");
            }
        } catch (IOException e) {
            return Optional.of("Не удалось проверить имя файла: " + e.getMessage());
        }
        return Optional.empty();
    }

    private void applyRename(String newName) {
        String oldName = support.plan().name();
        if (newName.equals(oldName)) {
            return;
        }
        Optional<Path> file = support.document().file();
        if (file.isEmpty()) {
            support.edit("Переименование плана", p -> p.withName(newName));
            return;
        }
        try {
            String oldSettingsName = support.settingsName(file.get());
            boolean wasDirty = support.document().isDirty();
            Path renamed = support.context().layout().plans().rename(file.get(), newName);
            if (wasDirty) {
                // markSaved отметил бы несохранённые правки сохранёнными; replace честно оставляет их несохранёнными
                // (ценой истории отмены, которую нельзя привязать к новому файлу).
                support.document().replace(support.plan().withName(newName), renamed, true, support.document().loadDiagnostics());
            } else {
                support.edit("Переименование плана", p -> p.withName(newName));
                support.document().markSaved(renamed);
            }
            support.context().updateSettings(s -> s.withRecentPlanRemoved(oldSettingsName));
            afterOpened(renamed);
        } catch (FileAlreadyExistsException e) {
            support.error("Не удалось переименовать план", "План с именем «" + newName + "» уже существует.");
        } catch (IOException e) {
            support.error("Не удалось переименовать план", e);
        }
    }

    // ------------------------------------------------------------------ экспорт и папка

    /**
     * Диалог 14 «Экспорт CSV» → выбор файла → запись.
     *
     * @param request запрос открытия
     */
    void exportCsv(OpenRequest request) {
        Plan plan = support.plan();
        Forecast forecast;
        try {
            forecast = support.document().forecast();
        } catch (IllegalStateException e) {
            support.cannotOpen(request, "Прогноз не рассчитан", e.getMessage());
            return;
        }
        LocalDate periodEnd = support.document().viewState().periodEnd(plan, forecast.anchor());
        // JavaFX: Dialog<R> → Swing: SwingDialog<R> (CsvExportDialog) → Web: openDialog('csv'): Promise<R>
        CsvExportDialog dialog = new CsvExportDialog(plan.startDate(), periodEnd, plan.endDate());
        support.host().open(dialog, request, result -> result.ifPresent(choice -> {
            // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: скачивание GET /api/export.csv
            File chosen = Dialogs.csvChooser(support.context().layout().dir(),
                    PlanRepository.fileBaseName(support.plan().name()) + ".csv").showSaveDialog(support.ownerWindow());
            if (chosen == null) {
                return;
            }
            try {
                // Диапазон считается заново: пока диалог был открыт, план мог измениться.
                Forecast current = support.document().forecast();
                LocalDate end = support.document().viewState().periodEnd(support.plan(), current.anchor());
                String csv = CsvExporter.toCsv(current, choice.toOptions(support.plan().startDate(), end));
                Files.writeString(chosen.toPath(), csv, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                support.error("Не удалось экспортировать CSV", e);
            }
        }));
    }

    /**
     * Диалог 13 «Папка CashMemory»: показывает путь и позволяет открыть планы из другой папки на время сеанса.
     */
    void chooseCashMemoryFolder() {
        Path dir = support.context().layout().dir();
        String content = "Все данные программы - планы (.md), настройки и снимки сеанса - хранятся здесь.\n"
                + (support.browsingOtherFolder() ? "\nПланы сейчас открываются из папки:\n" + support.plansFolder() + "\n" : "")
                + "\nМожно открыть планы из другой папки на время этого сеанса. Новые планы, настройки и снимки "
                + "по-прежнему сохраняются только в CashMemory.";
        List<ButtonType> buttons = new ArrayList<>(List.of(AppButtonTypes.OTHER_FOLDER));
        if (support.browsingOtherFolder()) {
            buttons.add(AppButtonTypes.BACK_TO_CASH_MEMORY);
        }
        buttons.add(AppButtonTypes.CLOSE);
        // JavaFX: Alert(INFORMATION) + ButtonType → Swing: JOptionPane.showOptionDialog → Web: <dialog class="alert">
        var alert = Dialogs.alert(AlertType.INFORMATION, "Папка CashMemory", dir.toString(), content,
                buttons.toArray(ButtonType[]::new));
        support.host().show(alert, WindowState.MAIN_OWNER, result -> {
            ButtonType button = result.orElse(AppButtonTypes.CLOSE);
            if (button == AppButtonTypes.OTHER_FOLDER) {
                chooseOtherFolder();
            } else if (button == AppButtonTypes.BACK_TO_CASH_MEMORY) {
                support.setBrowseFolder(null);
                support.info("Планы снова открываются из CashMemory", dir.toString());
            }
        });
    }

    private void chooseOtherFolder() {
        // JavaFX: DirectoryChooser → Swing: JFileChooser(DIRECTORIES_ONLY) → Web: обозреватель GET /api/fs?mode=dirs
        File chosen = Dialogs.folderChooser("Папка с планами (сейчас: " + support.plansFolder() + ")", support.plansFolder())
                .showDialog(support.ownerWindow());
        if (chosen == null) {
            return;
        }
        Path folder = chosen.toPath();
        int count;
        try {
            count = new PlanRepository(folder).list().size();
        } catch (UncheckedIOException e) {
            support.error("Не удалось прочитать папку", e.getMessage());
            return;
        }
        support.setBrowseFolder(folder);
        if (count == 0) {
            support.info("В папке нет планов", "В папке «" + folder + "» нет файлов .md. Команда «Открыть…» будет показывать эту папку.");
        } else {
            confirmDiscard(() -> openPlan(OpenRequest.fromMain()));
        }
    }

    // ------------------------------------------------------------------ выход и PNG

    /**
     * Первая половина выхода: {@code recorder.saveNow()}, затем, если есть несохранённые изменения,
     * «Сохранить изменения в плане?» [Сохранить][Не сохранять][Отмена].
     *
     * <p>Снимок пишется до вопроса: если процесс оборвётся, пока окно вопроса открыто (выключение компьютера),
     * при следующем запуске будет что восстановить. Запись настроек и {@code recorder.shutdownClean()} делает
     * оболочка после положительного ответа.</p>
     *
     * @param proceed {@code true} — можно завершать программу, {@code false} — пользователь отменил выход
     */
    void confirmExit(Consumer<Boolean> proceed) {
        support.context().recorder().saveNow();
        if (!support.document().isDirty()) {
            proceed.accept(true);
            return;
        }
        askSaveChanges(proceed);
    }

    /**
     * «Сохранить график PNG…»: снимок узла графика, выбор файла и запись PNG.
     *
     * @param chart узел графика в том виде, как он показан на экране
     */
    void saveChartPng(Node chart) {
        WritableImage image;
        try {
            image = chart.snapshot(new SnapshotParameters(), null);
        } catch (RuntimeException e) {
            support.error("Не удалось снять изображение графика", e);
            return;
        }
        // JavaFX: FileChooser → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: нет (только desktop)
        File chosen = Dialogs.pngChooser(support.context().layout().dir(),
                PlanRepository.fileBaseName(support.plan().name() + " - график") + ".png").showSaveDialog(support.ownerWindow());
        if (chosen == null) {
            return;
        }
        Path file = chosen.toPath();
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            file = file.resolveSibling(file.getFileName() + ".png");
        }
        try {
            Files.write(file, PngEncoder.encode(image));
        } catch (IOException | RuntimeException e) {
            support.error("Не удалось сохранить график в «" + file + "»", e);
        }
    }

    // ------------------------------------------------------------------ служебное

    private boolean planFileExists(String planName) {
        return Files.exists(support.context().layout().plans().pathFor(planName));
    }

    private String freeName(String base) {
        String candidate = base;
        for (int n = 2; planFileExists(candidate) && n < 1000; n++) {
            candidate = base + " " + n;
        }
        return Objects.requireNonNull(candidate);
    }
}
