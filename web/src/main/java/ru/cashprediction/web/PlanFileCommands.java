package ru.cashprediction.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Plan;

/**
 * Команды меню «Файл» web-клиента над {@link ServerState}: новый план, открытие, пример, импорт, сохранение,
 * «Сохранить как», переименование, перечитывание, скачивание, выбор папки на сеанс, автосохранение.
 *
 * <p>Соответствие диалогам: 10 «Открыть план» (JavaFX: {@code ChoiceDialog} → Swing: {@code JOptionPane.showInputDialog}
 * со списком → Web: {@code <dialog>} с {@code <select>} по {@code GET /api/plans}); 12 «Открыть из файла / Сохранить как»
 * (JavaFX: {@code FileChooser} → Swing: {@code JFileChooser} → Web: серверный обозреватель {@code /api/fs?mode=md},
 * {@code <input type="file">} и скачивание); 9 «Переименовать» (JavaFX: {@code TextInputDialog} → Swing:
 * {@code JOptionPane.showInputDialog} → Web: {@code <dialog>} с {@code <input>}); 13 «Папка CashMemory»
 * (JavaFX: {@code DirectoryChooser} → Swing: {@code JFileChooser(DIRECTORIES_ONLY)} → Web: {@code /api/fs?mode=dirs}).</p>
 *
 * <p>Каждая команда целиком выполняется под монитором состояния. Ошибки: {@link IllegalArgumentException} (400),
 * {@link NoSuchElementException} (404), {@link ConflictException} (409), {@link IOException} (500).</p>
 */
public final class PlanFileCommands {

    /** Формат времени изменения файла в списке планов. */
    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * Текст для скачивания.
     *
     * @param fileName предлагаемое имя файла
     * @param text     содержимое
     */
    public record Download(String fileName, String text) {
    }

    private final ServerState state;

    /**
     * Создаёт команды и подключает автосохранение к состоянию.
     *
     * @param state серверное состояние
     */
    public PlanFileCommands(ServerState state) {
        this.state = Objects.requireNonNull(state, "state");
        state.setAutosaveAction(this::autosave);
    }

    // ================================================================== список и открытие

    /**
     * Планы CashMemory и папки, выбранной на сеанс (для диалога «Открыть план» и меню «Недавние»).
     *
     * @return {@code {folder, current, plans:[{name, fileName, path, modified, modifiedText, current}], extraFolder,
     *         extraPlans, extraProblem, recent}}
     */
    public Map<String, Object> listPlans() {
        synchronized (state.lock) {
            String current = state.document().file().map(state::settingsName).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("folder", state.layout().dir().toString());
            m.put("current", current);
            m.put("plans", describe(state.repository().list(), current));
            Path extra = state.extraFolder();
            m.put("extraFolder", extra == null ? null : extra.toString());
            List<Object> extraPlans = new ArrayList<>();
            String problem = null;
            if (extra != null) {
                try {
                    extraPlans = describe(new PlanRepository(extra).list(), current);
                } catch (UncheckedIOException e) {
                    problem = "Папка не читается: " + e.getMessage();
                }
            }
            m.put("extraPlans", extraPlans);
            m.put("extraProblem", problem);
            m.put("recent", new ArrayList<Object>(state.settings().recentPlans()));
            return m;
        }
    }

    /**
     * Открывает план по имени из CashMemory (или папки сеанса) либо по полному пути («Из файла…», «Недавние»).
     *
     * @param name имя плана без {@code .md}; используется, если путь пуст
     * @param path полный путь или имя файла в CashMemory; может быть пустым
     * @return результат чтения с диагностикой
     * @throws IOException если файл не читается
     */
    public ReadResult open(String name, String path) throws IOException {
        synchronized (state.lock) {
            Path file = path != null && !path.isBlank() ? requirePlanFile(state.resolvePlanPath(path)) : findByName(name);
            ReadResult result = state.repository().load(file, state.today());
            state.replaceDocument(result.plan(), file, false, result.diagnostics());
            state.log().info("Открыт план: " + file);
            return result;
        }
    }

    /**
     * «Новый план» из полей мастера: план в памяти с несохранёнными изменениями (файл появится при первом сохранении).
     *
     * @param fields поля мастера ({@code WindowType.NEW_PLAN_WIZARD})
     */
    public void newPlan(Map<String, String> fields) {
        synchronized (state.lock) {
            Plan plan = PlanForms.fromWizard(fields, state.today());
            state.replaceDocument(plan, null, true, null);
            state.log().info("Создан новый план «" + plan.name() + "»");
        }
    }

    /**
     * «Открыть пример»: план «Пример» в памяти, помеченный изменённым.
     */
    public void openSample() {
        synchronized (state.lock) {
            state.replaceDocument(PlanForms.samplePlan(state.today()), null, true, null);
            state.log().info("Открыт пример плана");
        }
    }

    /**
     * Импорт плана из текста файла, выбранного в браузере ({@code <input type="file">}): план открывается в памяти,
     * на диск ничего не пишется до сохранения.
     *
     * @param name имя файла (для имени плана, если в тексте нет заголовка)
     * @param text содержимое файла
     * @return результат чтения с диагностикой
     */
    public ReadResult importPlan(String name, String text) {
        synchronized (state.lock) {
            String base = name == null ? "" : name.strip();
            if (base.toLowerCase(Locale.ROOT).endsWith(PlanRepository.EXTENSION)) {
                base = base.substring(0, base.length() - PlanRepository.EXTENSION.length());
            }
            String fallback = base.isBlank() ? PlanForms.DEFAULT_PLAN_NAME : base;
            ReadResult result = PlanMarkdownReader.read(Objects.requireNonNullElse(text, ""), fallback, state.today());
            state.replaceDocument(result.plan(), null, true, result.diagnostics());
            state.log().info("Импортирован план «" + result.plan().name() + "»");
            return result;
        }
    }

    /**
     * «Перечитать»: загружает текущий файл плана заново, отбрасывая несохранённые изменения.
     *
     * @return результат чтения
     * @throws IOException если файл не читается
     */
    public ReadResult reload() throws IOException {
        synchronized (state.lock) {
            Path file = state.document().file()
                    .orElseThrow(() -> new ConflictException(ConflictException.STATE, "План ещё не сохранён в файл: перечитывать нечего"));
            ReadResult result = state.repository().load(requirePlanFile(file), state.today());
            state.replaceDocument(result.plan(), file, false, result.diagnostics());
            state.log().info("План перечитан с диска: " + file);
            return result;
        }
    }

    // ================================================================== сохранение

    /**
     * «Сохранить» (Ctrl+S): в текущий файл или, для нового плана, в {@code CashMemory/<имя>.md}.
     *
     * @param overwrite перезаписать, даже если файл изменён снаружи или уже существует
     * @return путь сохранённого файла
     * @throws IOException если запись не удалась
     */
    public Path save(boolean overwrite) throws IOException {
        synchronized (state.lock) {
            PlanDocument document = state.document();
            Path target = document.file().orElseGet(() -> state.repository().pathFor(document.plan().name()));
            checkConflict(target, document.file().isPresent(), overwrite);
            writePlan(target);
            return target;
        }
    }

    /**
     * «Сохранить как…»: в указанную папку под указанным именем; имя плана становится равным имени файла
     * (при чтении имя файла всё равно побеждает заголовок).
     *
     * @param name      имя плана/файла, можно с {@code .md}
     * @param folder    папка (полный путь); пусто — CashMemory
     * @param overwrite перезаписать существующий файл
     * @return путь сохранённого файла
     * @throws IOException если запись не удалась
     */
    public Path saveAs(String name, String folder, boolean overwrite) throws IOException {
        synchronized (state.lock) {
            String base = name == null ? "" : name.strip();
            if (base.toLowerCase(Locale.ROOT).endsWith(PlanRepository.EXTENSION)) {
                base = base.substring(0, base.length() - PlanRepository.EXTENSION.length()).strip();
            }
            PlanValidator.checkPlanName(base).ifPresent(message -> {
                throw new IllegalArgumentException(message);
            });
            Path dir = folder == null || folder.isBlank() ? state.layout().dir() : Path.of(folder).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                throw new NoSuchElementException("Папка «" + dir + "» не найдена");
            }
            Path target = dir.resolve(PlanRepository.fileBaseName(base) + PlanRepository.EXTENSION).normalize();
            PlanDocument document = state.document();
            boolean ownFile = document.file().map(f -> f.toAbsolutePath().normalize().equals(target)).orElse(false);
            checkConflict(target, ownFile, overwrite);
            String newName = base;
            document.edit("Имя плана: " + newName, p -> p.withName(newName));
            writePlan(target);
            return target;
        }
    }

    /**
     * «Переименовать…» (F2): новое имя плана и файла. Несохранённые изменения остаются несохранёнными.
     *
     * @param name новое имя
     * @return новый путь файла или {@code null}, если план ещё не сохранялся
     * @throws IOException если файл не удалось переименовать
     */
    public Path rename(String name) throws IOException {
        synchronized (state.lock) {
            String newName = name == null ? "" : name.strip();
            PlanValidator.checkPlanName(newName).ifPresent(message -> {
                throw new IllegalArgumentException(message);
            });
            PlanDocument document = state.document();
            Plan plan = document.plan();
            if (newName.equals(plan.name())) {
                return document.file().orElse(null);
            }
            Path from = document.file().orElse(null);
            if (from == null || !Files.isRegularFile(from)) {
                // Файла ещё нет: переименование — просто правка плана в памяти.
                document.edit("Переименование плана", p -> p.withName(newName));
                return null;
            }
            boolean wasDirty = document.isDirty();
            String oldSettingsName = state.settingsName(from);
            Path to;
            try {
                to = state.repository().rename(from, newName);
            } catch (FileAlreadyExistsException e) {
                throw new ConflictException(ConflictException.EXISTS, "План с именем «" + newName + "» уже существует");
            }
            if (wasDirty) {
                // PlanDocument меняет путь файла только через replace/markSaved; markSaved сделал бы план «чистым»,
                // поэтому для несохранённого плана путь меняется через replace (история отмены при этом очищается).
                document.replace(plan.withName(newName), to, true, document.loadDiagnostics());
            } else {
                document.edit("Переименование плана", p -> p.withName(newName));
                document.markSaved(to);
            }
            state.rememberFileStamp(to);
            String newSettingsName = state.settingsName(to);
            state.updateSettings(s -> s.withRecentPlanRemoved(oldSettingsName).withPlanOpened(newSettingsName));
            state.log().info("План переименован: " + from.getFileName() + " → " + to.getFileName());
            return to;
        }
    }

    /**
     * Текст плана для скачивания: текущего (с несохранёнными изменениями) или файла из CashMemory.
     *
     * @param name имя плана из CashMemory; пусто — текущий план
     * @return имя файла и текст
     * @throws IOException если файл не читается
     */
    public Download download(String name) throws IOException {
        synchronized (state.lock) {
            if (name == null || name.isBlank()) {
                PlanDocument document = state.document();
                String fileName = document.file().map(f -> f.getFileName().toString())
                        .orElseGet(() -> PlanRepository.fileBaseName(document.plan().name()) + PlanRepository.EXTENSION);
                return new Download(fileName, PlanMarkdownWriter.write(document.plan()));
            }
            Path file = findByName(name);
            return new Download(file.getFileName().toString(), AtomicFiles.readString(file));
        }
    }

    /**
     * Выбирает папку для чтения планов на время сеанса («Папка CashMemory…»). В неё ничего не пишется автоматически.
     *
     * @param folder полный путь; пусто — сбросить выбор
     * @return список планов после выбора
     */
    public Map<String, Object> chooseFolder(String folder) {
        synchronized (state.lock) {
            if (folder == null || folder.isBlank()) {
                state.setExtraFolder(null);
            } else {
                Path dir = Path.of(folder).toAbsolutePath().normalize();
                if (!Files.isDirectory(dir)) {
                    throw new NoSuchElementException("Папка «" + dir + "» не найдена");
                }
                state.setExtraFolder(dir.equals(state.layout().dir()) ? null : dir);
                state.log().info("Папка планов на сеанс: " + dir);
            }
            return listPlans();
        }
    }

    /**
     * Автосохранение через {@value ServerState#AUTOSAVE_DELAY_MS} мс после правки (CheckMenuItem «Автосохранение»).
     * Файл, изменённый снаружи, не перезаписывается: проблема показывается в строке состояния.
     */
    void autosave() {
        synchronized (state.lock) {
            if (state.isClosed() || !state.settings().autosave() || !state.document().isDirty()) {
                return;
            }
            try {
                Path file = save(false);
                state.log().info("Автосохранение: " + file.getFileName());
            } catch (ConflictException e) {
                state.setAutosaveProblem("Автосохранение пропущено: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                state.setAutosaveProblem("Автосохранение не удалось: " + e.getMessage());
                state.log().error("Автосохранение не удалось", e);
            }
        }
    }

    // ================================================================== внутреннее

    /** Пишет план, отмечает сохранение в документе, запоминает время файла и обновляет недавние планы. */
    private void writePlan(Path target) throws IOException {
        PlanDocument document = state.document();
        state.repository().save(document.plan(), target);
        document.markSaved(target);
        state.rememberFileStamp(target);
        state.setAutosaveProblem("");
        String settingsName = state.settingsName(target);
        state.updateSettings(s -> s.withPlanOpened(settingsName));
        state.log().info("План сохранён: " + target);
    }

    /**
     * Проверяет конфликт перед записью.
     *
     * @param target    целевой файл
     * @param ownFile   файл принадлежит документу (загружен или сохранён в этом сеансе)
     * @param overwrite пользователь уже согласился перезаписать
     */
    private void checkConflict(Path target, boolean ownFile, boolean overwrite) throws IOException {
        if (overwrite || !Files.exists(target)) {
            return;
        }
        if (!ownFile) {
            throw new ConflictException(ConflictException.EXISTS,
                    "Файл «" + target.getFileName() + "» уже существует. Перезаписать его?");
        }
        FileTime stamp = state.fileStamp();
        FileTime actual = state.repository().lastModified(target);
        if (stamp == null || !stamp.equals(actual)) {
            throw new ConflictException(ConflictException.EXTERNAL_CHANGE, "Файл «" + target.getFileName()
                    + "» изменён другой программой после загрузки. Перезаписать его или перечитать с диска?");
        }
    }

    private Path findByName(String name) {
        String wanted = name == null ? "" : name.strip();
        if (wanted.toLowerCase(Locale.ROOT).endsWith(PlanRepository.EXTENSION)) {
            wanted = wanted.substring(0, wanted.length() - PlanRepository.EXTENSION.length());
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Не указано имя плана");
        }
        List<PlanFileInfo> candidates = new ArrayList<>(state.repository().list());
        if (state.extraFolder() != null) {
            try {
                candidates.addAll(new PlanRepository(state.extraFolder()).list());
            } catch (UncheckedIOException ignored) {
                // Папка сеанса пропала — ищем только в CashMemory.
            }
        }
        for (PlanFileInfo info : candidates) {
            if (info.name().equalsIgnoreCase(wanted)) {
                return info.path();
            }
        }
        throw new NoSuchElementException("План «" + wanted + "» не найден");
    }

    private static Path requirePlanFile(Path file) {
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PlanRepository.EXTENSION)) {
            throw new IllegalArgumentException("План должен быть файлом .md: «" + file.getFileName() + "»");
        }
        if (!Files.isRegularFile(file)) {
            throw new NoSuchElementException("Файл «" + file + "» не найден");
        }
        return file;
    }

    private static List<Object> describe(List<PlanFileInfo> infos, String current) {
        List<Object> result = new ArrayList<>();
        for (PlanFileInfo info : infos) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", info.name());
            m.put("fileName", info.path().getFileName().toString());
            m.put("path", info.path().toString());
            m.put("modified", info.lastModified().toInstant().toString());
            m.put("modifiedText", MODIFIED.format(info.lastModified().toInstant().atZone(ZoneId.systemDefault())));
            m.put("current", current != null && (current.equalsIgnoreCase(info.path().getFileName().toString())
                    || current.equalsIgnoreCase(info.path().toString())));
            result.add(m);
        }
        return result;
    }
}
