package ru.cashprediction.swing.action;

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
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.export.CsvExporter;
import ru.cashprediction.core.export.CsvOptions;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.MarkdownParseException;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.swing.dialog.CsvExportDialog;
import ru.cashprediction.swing.dialog.NewPlanWizard;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingButtonType;
import ru.cashprediction.swing.dialog.SwingChoiceDialog;
import ru.cashprediction.swing.dialog.SwingFileChoosers;
import ru.cashprediction.swing.dialog.SwingText;
import ru.cashprediction.swing.dialog.SwingTextInputDialog;

/**
 * Команды меню «Файл»: новый план, открыть, недавние, пример, сохранить, сохранить как, переименовать,
 * экспорт CSV, график в PNG и папка CashMemory.
 *
 * <p><b>Сохранение.</b> Ctrl+S пишет в файл плана, а новый план — в {@code PlanRepository.pathFor(имя)} в
 * CashMemory. Перед записью сравнивается время изменения файла с запомненным при открытии: если файл правили
 * снаружи, пользователь выбирает «Перезаписать» или «Перечитать» (диалог 18). «Сохранить как…» — окно выбора
 * файла с фильтром {@code *.md}.</p>
 *
 * <p><b>Папки.</b> Программа сама пишет только в CashMemory. Папка, выбранная в «Папка CashMemory…», служит лишь
 * источником планов для команды «Открыть…» до конца сеанса.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
final class FileActions extends ActionSupport {

    /** Пункт списка «Открыть план», открывающий окно выбора файла. */
    public static final String FROM_FILE = "Из файла…";
    /** Имя пустого плана, если мастер нового плана при первом запуске отменён. */
    public static final String DEFAULT_PLAN_NAME = "Мой план";

    /** Файл, время изменения которого запомнено для диалога «Файл изменён снаружи». */
    private Path trackedFile;
    private FileTime trackedTime;
    /** Открыт вопрос «Файл изменён снаружи», заданный автосохранением: второй такой же не показываем. */
    private boolean askingChangedOnDisk;

    /**
     * Создаёт команды меню «Файл».
     *
     * @param shared общие объекты команд
     */
    public FileActions(ActionShared shared) {
        super(shared);
    }

    // ------------------------------------------------------------------ учёт изменений файла снаружи

    /**
     * Запоминает время изменения файла плана, чтобы при сохранении заметить правку другой программой.
     *
     * @param file файл плана или {@code null}
     */
    public void trackFile(Path file) {
        trackedFile = file;
        trackedTime = null;
        if (file != null) {
            try {
                trackedTime = Files.getLastModifiedTime(file);
            } catch (IOException e) {
                // Файла ещё нет: сравнивать не с чем, это не ошибка.
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
            // Файл удалили снаружи: при сохранении он будет создан заново.
            return false;
        }
    }

    /**
     * Действия после открытия или сохранения файла: время изменения и «последний/недавние» в настройках.
     *
     * @param file файл плана
     */
    public void afterOpened(Path file) {
        trackFile(file);
        String name = settingsName(file);
        context().updateSettings(s -> s.withPlanOpened(name));
    }

    // ------------------------------------------------------------------ несохранённые изменения

    /**
     * Выполняет действие, предварительно предложив сохранить несохранённые изменения.
     *
     * @param proceed действие (не выполняется при «Отмена» или неудачном сохранении)
     */
    public void confirmDiscard(Runnable proceed) {
        if (!document().isDirty()) {
            proceed.run();
            return;
        }
        askSaveChanges(ok -> {
            if (ok) {
                proceed.run();
            }
        });
    }

    /**
     * Вопрос «Сохранить изменения в плане?» с кнопками «Сохранить», «Не сохранять», «Отмена».
     *
     * @param proceed получает {@code true}, если можно продолжать (сохранено или «Не сохранять»)
     */
    public void askSaveChanges(Consumer<Boolean> proceed) {
        SwingAlert alert = alerts().create(SwingAlert.AlertType.CONFIRMATION, "Несохранённые изменения",
                "Сохранить изменения в плане?", "План «" + plan().name() + "» изменён. Если не сохранить, изменения будут потеряны.",
                AppButtons.SAVE, AppButtons.DONT_SAVE, AppButtons.CANCEL);
        alerts().show(alert, result -> {
            SwingButtonType button = result.orElse(AppButtons.CANCEL);
            if (button.equals(AppButtons.SAVE)) {
                save(proceed);
            } else {
                proceed.accept(button.equals(AppButtons.DONT_SAVE));
            }
        });
    }

    // ------------------------------------------------------------------ новый план

    /**
     * «Файл → Новый план…» (Ctrl+N).
     */
    public void newPlanCommand() {
        confirmDiscard(() -> openWizard(interactive(), null));
    }

    /**
     * Открывает мастер «Новый план» (диалог 1).
     *
     * @param request     как открыть окно
     * @param onCancelled действие при отмене мастера или {@code null}
     */
    public void openWizard(OpenRequest request, Runnable onCancelled) {
        // JavaFX: Dialog<Plan> + DialogPane → Swing: SwingDialog<Plan> + SwingDialogPane → Web: <dialog> мастера
        NewPlanWizard wizard = new NewPlanWizard(owner(request), request.ownerId(), recorder(), today(),
                freeName(DEFAULT_PLAN_NAME), this::planFileExists);
        wizard.setOnResult(result -> result.ifPresentOrElse(this::createPlan, () -> {
            if (onCancelled != null) {
                onCancelled.run();
            }
        }));
        show(wizard, request);
    }

    /**
     * Мастер при первом запуске: если его отменить, открывается пустой несохранённый план «Мой план».
     */
    public void openWizardOnStartup() {
        openWizard(interactive(), () -> {
            context().openPlanDocument(Plan.empty(DEFAULT_PLAN_NAME, today()), null, false, List.of());
            trackFile(null);
        });
    }

    private void createPlan(Plan plan) {
        Path file = context().layout().plans().pathFor(plan.name());
        try {
            context().layout().plans().save(plan, file);
            context().openPlanDocument(plan, file, false, List.of());
            afterOpened(file);
        } catch (IOException e) {
            // План не должен пропасть из-за диска: открываем его несохранённым, его защищает снимок сессии.
            context().openPlanDocument(plan, null, true, List.of());
            trackFile(null);
            alerts().error("План создан, но не сохранён в CashMemory", e);
        }
    }

    // ------------------------------------------------------------------ открыть

    /**
     * Папка, из которой «Открыть…» показывает планы.
     *
     * @return выбранная в этом сеансе папка или CashMemory
     */
    public Path plansFolder() {
        return shared.plansFolder();
    }

    /**
     * «Файл → Открыть…» (Ctrl+O).
     */
    public void openPlanCommand() {
        confirmDiscard(() -> openPlanChooser(interactive()));
    }

    /**
     * Выбор плана из папки (диалог 10, {@code ChoiceDialog}) с пунктом «Из файла…».
     *
     * @param request как открыть окно
     */
    public void openPlanChooser(OpenRequest request) {
        Path folder = plansFolder();
        Map<String, Path> plans = new LinkedHashMap<>();
        try {
            for (PlanFileInfo info : new PlanRepository(folder).list()) {
                plans.put(info.name(), info.path());
            }
        } catch (UncheckedIOException e) {
            cannotOpen(request, "Не удалось прочитать папку планов", Objects.requireNonNullElse(e.getMessage(), folder.toString()));
            return;
        }
        List<String> items = new ArrayList<>(plans.keySet());
        items.add(FROM_FILE);
        String current = document().file().map(PlanMarkdownReader::nameWithoutExtension).orElse(null);
        String defaultChoice = current != null && plans.containsKey(current) ? current : items.getFirst();
        // JavaFX: ChoiceDialog<T> → Swing: SwingChoiceDialog<T> (аналог JOptionPane.showInputDialog с selectionValues) → Web: <dialog> с <select>
        SwingChoiceDialog<String> dialog = new SwingChoiceDialog<>(owner(request), request.ownerId(), recorder(),
                Purposes.OPEN_PLAN, "Открыть план",
                plans.isEmpty() ? "В папке " + folder + " пока нет планов" : "Планы в папке " + folder,
                "План", items, defaultChoice, s -> s, s -> s);
        dialog.setOnResult(result -> result.ifPresent(choice -> {
            if (FROM_FILE.equals(choice)) {
                chooseFileAndLoad();
            } else if (plans.containsKey(choice)) {
                loadPlan(plans.get(choice));
            }
        }));
        show(dialog, request);
    }

    /**
     * «Из файла…»: окно выбора {@code .md} (диалог 12) и загрузка выбранного плана.
     */
    public void chooseFileAndLoad() {
        // JavaFX: FileChooser.showOpenDialog + ExtensionFilter("*.md") → Swing: JFileChooser(FILES_ONLY) + FileNameExtensionFilter → Web: обозреватель /api/fs?mode=md + <input type="file">
        SwingFileChoosers.openMarkdown(frame(), "Открыть план из файла", plansFolder()).ifPresent(this::loadPlan);
    }

    /**
     * Открывает план из списка «Недавние».
     *
     * @param settingsName имя из настроек: имя файла в CashMemory или абсолютный путь
     */
    public void openRecent(String settingsName) {
        Path file = context().layout().dir().resolve(settingsName);
        if (!Files.exists(file)) {
            context().updateSettings(s -> s.withRecentPlanRemoved(settingsName));
            alerts().error("Файл плана не найден", "Файла «" + file + "» больше нет — он убран из списка недавних.");
            return;
        }
        confirmDiscard(() -> loadPlan(file));
    }

    /**
     * Загружает план из файла; при замечаниях разбора показывает диагностику.
     *
     * @param file файл плана
     * @return {@code true}, если план открыт
     */
    public boolean loadPlan(Path file) {
        return loadPlan(file, null);
    }

    /**
     * Загружает план из файла.
     *
     * <p>При восстановлении сессии ({@code warn != null}) проблемы не показываются модальными окнами, а уходят
     * в отчёт восстановления: координатор открывает окна снимка по цепочке, и лишнее сообщение поверх неё только
     * мешало бы.</p>
     *
     * @param file файл плана
     * @param warn приёмник замечаний при восстановлении или {@code null} для обычного открытия
     * @return {@code true}, если план открыт
     */
    public boolean loadPlan(Path file, Consumer<String> warn) {
        ReadResult result;
        try {
            result = context().layout().plans().load(file, today());
        } catch (NoSuchFileException e) {
            context().updateSettings(s -> s.withRecentPlanRemoved(settingsName(file)));
            report(warn, "Файл плана не найден", file.toString());
            return false;
        } catch (IOException e) {
            if (warn != null) {
                warn.accept("Не удалось прочитать план «" + file.getFileName() + "»: " + e.getMessage());
            } else {
                alerts().error("Не удалось прочитать план «" + file.getFileName() + "»", e);
            }
            return false;
        } catch (MarkdownParseException e) {
            report(warn, "Файл «" + file.getFileName() + "» не похож на план CashPrediction", e.getMessage());
            return false;
        }
        context().openPlanDocument(result.plan(), file, false, result.diagnostics());
        afterOpened(file);
        if (warn == null) {
            showLoadDiagnostics(file.getFileName().toString(), result.diagnostics());
        } else if (result.hasWarnings()) {
            warn.accept("План «" + file.getFileName() + "» прочитан с замечаниями: Инструменты → Проверить план");
        }
        return true;
    }

    /** Сообщает о проблеме: в отчёт восстановления или сообщением об ошибке. */
    private void report(Consumer<String> warn, String header, String message) {
        if (warn != null) {
            warn.accept(header + ": " + message);
        } else {
            alerts().error(header, message);
        }
    }

    /**
     * Диалог 15 «Диагностика» после чтения файла, если есть предупреждения или ошибки.
     *
     * @param fileName    имя файла
     * @param diagnostics замечания разбора
     */
    public void showLoadDiagnostics(String fileName, List<Diagnostic> diagnostics) {
        List<Diagnostic> important = diagnostics.stream().filter(d -> d.severity() != Severity.INFO).toList();
        if (important.isEmpty()) {
            return;
        }
        alerts().warning("План «" + fileName + "» прочитан с замечаниями",
                "Нераспознанные строки сохранены в плане и не пропадут при сохранении. Замечаний: " + important.size() + ".",
                important.stream().map(Diagnostic::format).collect(Collectors.joining("\n")));
    }

    /**
     * «Файл → Открыть пример».
     */
    public void openSampleCommand() {
        confirmDiscard(this::openSample);
    }

    /**
     * Открывает план «Пример» как несохранённый (без вопроса о текущих изменениях).
     */
    public void openSample() {
        context().openPlanDocument(SamplePlan.samplePlan(today()), null, true, List.of());
        trackFile(null);
    }

    // ------------------------------------------------------------------ сохранение

    /**
     * «Файл → Сохранить» (Ctrl+S).
     */
    public void saveCommand() {
        save(ok -> { });
    }

    /**
     * Сохраняет план в его файл или, для нового плана, в CashMemory.
     *
     * @param done получает {@code true}, если план записан
     */
    public void save(Consumer<Boolean> done) {
        Optional<Path> file = document().file();
        if (file.isPresent()) {
            if (changedOnDisk(file.get())) {
                askChangedOnDisk(file.get(), done);
            } else {
                writeTo(file.get(), done);
            }
            return;
        }
        Path target = context().layout().plans().pathFor(plan().name());
        if (!Files.exists(target)) {
            writeTo(target, done);
            return;
        }
        SwingAlert alert = alerts().create(SwingAlert.AlertType.CONFIRMATION, "Сохранение",
                "В CashMemory уже есть файл «" + target.getFileName() + "»",
                "Перезаписать его текущим планом? Чтобы сохранить под другим именем, выберите «Отмена» и «Сохранить как…».",
                AppButtons.OVERWRITE, AppButtons.CANCEL);
        alerts().show(alert, result -> {
            if (result.filter(AppButtons.OVERWRITE::equals).isPresent()) {
                writeTo(target, done);
            } else {
                done.accept(false);
            }
        });
    }

    /**
     * Автосохранение (около 1 с после правки). Как и в JavaFX-клиенте, идёт тем же путём, что Ctrl+S: если файл
     * изменили снаружи, пользователь выбирает «Перезаписать» или «Перечитать» — молча затирать чужие правки нельзя.
     * Пока этот вопрос открыт, следующие автосохранения его не дублируют.
     */
    public void autosave() {
        if (!document().isDirty() || askingChangedOnDisk) {
            return;
        }
        Optional<Path> file = document().file();
        if (file.isPresent()) {
            if (changedOnDisk(file.get())) {
                askingChangedOnDisk = true;
                askChangedOnDisk(file.get(), ok -> askingChangedOnDisk = false);
            } else {
                writeTo(file.get(), ok -> { });
            }
            return;
        }
        Path target = context().layout().plans().pathFor(plan().name());
        if (!Files.exists(target)) {
            writeTo(target, ok -> { });
        }
    }

    /** Диалог 18 «Файл изменён снаружи». */
    private void askChangedOnDisk(Path file, Consumer<Boolean> done) {
        SwingAlert alert = alerts().create(SwingAlert.AlertType.WARNING, "Файл изменён снаружи",
                "Файл «" + file.getFileName() + "» изменён другой программой после открытия",
                "«Перезаписать» — сохранить план из CashPrediction поверх чужих правок.\n"
                        + "«Перечитать» — открыть файл заново; несохранённые изменения в программе будут потеряны.",
                AppButtons.OVERWRITE, AppButtons.RELOAD, AppButtons.CANCEL);
        alerts().show(alert, result -> {
            SwingButtonType button = result.orElse(AppButtons.CANCEL);
            if (button.equals(AppButtons.OVERWRITE)) {
                writeTo(file, done);
            } else {
                if (button.equals(AppButtons.RELOAD)) {
                    loadPlan(file);
                }
                done.accept(false);
            }
        });
    }

    private void writeTo(Path file, Consumer<Boolean> done) {
        try {
            context().layout().plans().save(plan(), file);
            document().markSaved(file);
            afterOpened(file);
            status("План сохранён: " + file.getFileName());
            done.accept(true);
        } catch (IOException | RuntimeException e) {
            alerts().error("Не удалось сохранить план в «" + file + "»", e);
            done.accept(false);
        }
    }

    /**
     * «Файл → Сохранить как…» (Ctrl+Shift+S).
     */
    public void saveAsCommand() {
        saveAs(ok -> { });
    }

    /**
     * Сохраняет план в выбранный файл (диалог 12, {@code FileChooser} с фильтром {@code *.md}).
     *
     * @param done получает {@code true}, если план записан
     */
    public void saveAs(Consumer<Boolean> done) {
        Path initialDir = document().file().map(Path::getParent).orElse(context().layout().dir());
        // JavaFX: FileChooser.showSaveDialog + ExtensionFilter("*.md") → Swing: JFileChooser.showSaveDialog + FileNameExtensionFilter → Web: скачивание / серверный обозреватель
        Optional<Path> chosen = SwingFileChoosers.saveMarkdown(frame(), "Сохранить план как", initialDir,
                PlanRepository.fileBaseName(plan().name()) + PlanRepository.EXTENSION);
        if (chosen.isEmpty()) {
            done.accept(false);
            return;
        }
        Path file = chosen.get();
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PlanRepository.EXTENSION)) {
            file = file.resolveSibling(file.getFileName() + PlanRepository.EXTENSION);
        }
        // Имя файла побеждает заголовок при чтении, поэтому план сразу получает имя файла.
        String newName = PlanMarkdownReader.nameWithoutExtension(file);
        if (PlanValidator.checkPlanName(newName).isEmpty() && !newName.equals(plan().name())) {
            edit("Сохранение под именем «" + newName + "»", p -> p.withName(newName));
        }
        writeTo(file, done);
    }

    // ------------------------------------------------------------------ переименование

    /**
     * «Файл → Переименовать…» (F2).
     */
    public void renameCommand() {
        rename(interactive());
    }

    /**
     * Диалог 9 «Переименовать» ({@code TextInputDialog}); файл плана переименовывается вместе с планом.
     *
     * @param request как открыть окно
     */
    public void rename(OpenRequest request) {
        // JavaFX: TextInputDialog → Swing: SwingTextInputDialog (аналог JOptionPane.showInputDialog) → Web: <dialog> с <input>
        SwingTextInputDialog dialog = new SwingTextInputDialog(owner(request), request.ownerId(), recorder(), Purposes.RENAME,
                false, "Переименовать план",
                "Новое имя плана «" + plan().name() + "»"
                        + (document().file().isPresent() ? "\nФайл плана переименуется вместе с ним." : ""),
                "Имя", plan().name(), candidate -> renameProblem(candidate).orElse(null));
        dialog.setOnResult(result -> result.map(String::strip).ifPresent(this::applyRename));
        show(dialog, request);
    }

    private Optional<String> renameProblem(String candidate) {
        Optional<String> invalid = PlanValidator.checkPlanName(candidate == null ? "" : candidate.strip());
        if (invalid.isPresent()) {
            return invalid;
        }
        String name = candidate.strip();
        Optional<Path> file = document().file();
        Path target = file.map(f -> f.toAbsolutePath().resolveSibling(PlanRepository.fileBaseName(name) + PlanRepository.EXTENSION))
                .orElse(context().layout().plans().pathFor(name));
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
        String oldName = plan().name();
        if (newName.equals(oldName)) {
            return;
        }
        Optional<Path> file = document().file();
        if (file.isEmpty()) {
            edit("Переименование плана", p -> p.withName(newName));
            return;
        }
        try {
            String oldSettingsName = settingsName(file.get());
            boolean wasDirty = document().isDirty();
            Path renamed = context().layout().plans().rename(file.get(), newName);
            if (wasDirty) {
                // markSaved отметил бы несохранённые правки сохранёнными; replace честно оставляет их несохранёнными.
                document().replace(plan().withName(newName), renamed, true, document().loadDiagnostics());
            } else {
                edit("Переименование плана", p -> p.withName(newName));
                document().markSaved(renamed);
            }
            context().updateSettings(s -> s.withRecentPlanRemoved(oldSettingsName));
            afterOpened(renamed);
        } catch (FileAlreadyExistsException e) {
            alerts().error("Не удалось переименовать план", "План с именем «" + newName + "» уже существует.");
        } catch (IOException e) {
            alerts().error("Не удалось переименовать план", e);
        }
    }

    /**
     * Переименовывает файл плана вслед за сменой имени в «Параметрах плана» и сохраняет план в него.
     *
     * @param oldFile прежний файл плана
     * @param newName новое имя плана
     */
    public void renameFileAndSave(Path oldFile, String newName) {
        try {
            String oldSettingsName = settingsName(oldFile);
            Path renamed = context().layout().plans().rename(oldFile, newName);
            context().layout().plans().save(plan(), renamed);
            document().markSaved(renamed);
            context().updateSettings(s -> s.withRecentPlanRemoved(oldSettingsName));
            afterOpened(renamed);
        } catch (IOException e) {
            alerts().error("План переименован, но файл «" + oldFile.getFileName() + "» переименовать не удалось", e);
        }
    }

    // ------------------------------------------------------------------ экспорт

    /**
     * «Файл → Экспорт CSV…» (Ctrl+Shift+C).
     */
    public void exportCsvCommand() {
        exportCsv(interactive());
    }

    /**
     * Диалог 14 «Экспорт CSV»: параметры, затем окно сохранения файла и запись через {@link CsvExporter}.
     *
     * @param request как открыть окно
     */
    public void exportCsv(OpenRequest request) {
        Forecast forecast;
        try {
            forecast = document().forecast();
        } catch (IllegalStateException e) {
            cannotOpen(request, "Прогноз не рассчитан", e.getMessage());
            return;
        }
        Plan plan = plan();
        LocalDate periodEnd = document().viewState().periodEnd(plan, forecast.anchor());
        CsvExportDialog dialog = new CsvExportDialog(owner(request), request.ownerId(), recorder(), plan.startDate(),
                periodEnd, plan.endDate());
        dialog.setOnResult(result -> result.ifPresent(this::writeCsv));
        show(dialog, request);
    }

    private void writeCsv(CsvExportDialog.Choice choice) {
        // JavaFX: FileChooser.showSaveDialog + ExtensionFilter("*.csv") → Swing: JFileChooser.showSaveDialog + FileNameExtensionFilter → Web: GET /api/export.csv (скачивание)
        Optional<Path> chosen = SwingFileChoosers.saveFile(frame(), "Экспорт прогноза в CSV", context().layout().dir(),
                PlanRepository.fileBaseName(plan().name()) + ".csv", "Таблицы CSV (*.csv)", "csv");
        if (chosen.isEmpty()) {
            return;
        }
        try {
            // Диапазон считается заново: пока диалог был открыт, план мог измениться.
            Forecast current = document().forecast();
            LocalDate end = document().viewState().periodEnd(plan(), current.anchor());
            CsvOptions options = choice.wholeForecast()
                    ? new CsvOptions(choice.separator(), choice.bom(), null, null)
                    : new CsvOptions(choice.separator(), choice.bom(), plan().startDate(), end);
            Files.writeString(chosen.get(), CsvExporter.toCsv(current, options), StandardCharsets.UTF_8);
            status("CSV сохранён: " + chosen.get().getFileName());
        } catch (IOException | RuntimeException e) {
            alerts().error("Не удалось экспортировать CSV", e);
        }
    }

    /**
     * «Файл → Сохранить график PNG…»: окно сохранения файла и отрисовка графика в изображение.
     */
    public void saveChartPng() {
        // JavaFX: FileChooser.showSaveDialog + ExtensionFilter("*.png") → Swing: JFileChooser.showSaveDialog + FileNameExtensionFilter → Web: скачивание SVG/PNG
        Optional<Path> chosen = SwingFileChoosers.saveFile(frame(), "Сохранить график как изображение", context().layout().dir(),
                PlanRepository.fileBaseName(plan().name()) + ".png", "Изображения PNG (*.png)", "png");
        if (chosen.isEmpty()) {
            return;
        }
        try {
            context().writeChartPng(chosen.get());
            status("График сохранён: " + chosen.get().getFileName());
        } catch (IOException | RuntimeException e) {
            alerts().error("Не удалось сохранить график", e);
        }
    }

    // ------------------------------------------------------------------ папка CashMemory

    /**
     * Диалог 13 «Папка CashMemory»: путь к папке и выбор другой папки планов на время сеанса ({@code DirectoryChooser}).
     */
    public void cashMemoryFolder() {
        Path dir = context().layout().dir();
        String content = "Все данные программы — планы (.md), настройки и снимки сеанса — хранятся здесь.\n"
                + (shared.browsingOtherFolder() ? "\nПланы сейчас открываются из папки:\n" + shared.plansFolder() + "\n" : "")
                + "\nМожно открыть планы из другой папки на время этого сеанса. Новые планы, настройки и снимки "
                + "по-прежнему сохраняются только в CashMemory.";
        List<SwingButtonType> buttons = new ArrayList<>(List.of(AppButtons.OTHER_FOLDER));
        if (shared.browsingOtherFolder()) {
            buttons.add(AppButtons.BACK_TO_CASH_MEMORY);
        }
        buttons.add(AppButtons.CLOSE);
        SwingAlert alert = alerts().create(SwingAlert.AlertType.INFORMATION, "Папка CashMemory", dir.toString(), content,
                buttons.toArray(SwingButtonType[]::new));
        alerts().show(alert, result -> {
            SwingButtonType button = result.orElse(AppButtons.CLOSE);
            if (button.equals(AppButtons.OTHER_FOLDER)) {
                chooseOtherFolder();
            } else if (button.equals(AppButtons.BACK_TO_CASH_MEMORY)) {
                shared.setBrowseFolder(null);
                alerts().info("Планы снова открываются из CashMemory", dir.toString());
            }
        });
    }

    private void chooseOtherFolder() {
        JLabel accessory = new JLabel(SwingText.html("CashMemory:\n" + context().layout().dir()
                + "\n\nПланы из выбранной папки\nможно будет открыть\nв этом сеансе."));
        accessory.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        // JavaFX: DirectoryChooser.showDialog → Swing: JFileChooser(DIRECTORIES_ONLY) → Web: обозреватель GET /api/fs?mode=dirs
        Optional<Path> chosen = SwingFileChoosers.chooseDirectory(frame(),
                "Папка с планами (сейчас: " + plansFolder() + ")", plansFolder(), accessory);
        if (chosen.isEmpty()) {
            return;
        }
        Path folder = chosen.get();
        List<PlanFileInfo> plans;
        try {
            plans = new PlanRepository(folder).list();
        } catch (UncheckedIOException e) {
            alerts().error("Не удалось прочитать папку", Objects.requireNonNullElse(e.getMessage(), folder.toString()));
            return;
        }
        // Папка запоминается только в памяти на время сеанса: автоматически за пределами CashMemory ничего не пишется.
        shared.setBrowseFolder(folder);
        if (plans.isEmpty()) {
            alerts().info("В папке нет планов", "В папке «" + folder + "» нет файлов .md. Команда «Открыть…» будет показывать эту папку.");
        } else {
            confirmDiscard(() -> openPlanChooser(interactive()));
        }
    }

    // ------------------------------------------------------------------ служебное

    /**
     * Есть ли в CashMemory файл плана с таким именем.
     *
     * @param planName имя плана
     * @return {@code true}, если файл существует
     */
    public boolean planFileExists(String planName) {
        try {
            return Files.exists(context().layout().plans().pathFor(planName));
        } catch (RuntimeException e) {
            // Недопустимое имя файла: такого файла точно нет, ошибку имени покажет проверка формы.
            return false;
        }
    }

    /**
     * Свободное имя плана: «Мой план», «Мой план 2», …
     *
     * @param base основа имени
     * @return имя, файла с которым в CashMemory нет
     */
    public String freeName(String base) {
        String candidate = base;
        for (int n = 2; planFileExists(candidate) && n < 1000; n++) {
            candidate = base + " " + n;
        }
        return candidate;
    }
}
