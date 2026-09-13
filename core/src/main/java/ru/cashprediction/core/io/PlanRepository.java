package ru.cashprediction.core.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.markdown.MarkdownFormat;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Plan;

/**
 * Файлы планов в папке CashMemory: список, загрузка, сохранение, переименование.
 *
 * <p>Один план — один файл {@code <имя>.md}. Имя файла получается из имени плана заменой символов,
 * запрещённых в Windows ({@link #sanitizeFileName(String)}). Если заголовок внутри файла расходится
 * с именем файла (файл переименовали в Проводнике), при загрузке побеждает имя файла: пользователь
 * видит в списке именно его.</p>
 *
 * <p>Экземпляр неизменяем и потокобезопасен сам по себе; согласованность параллельной записи одного
 * файла обеспечивает {@link AtomicFiles} (последняя запись выигрывает, обрезанных файлов не бывает).</p>
 */
public final class PlanRepository {

    /** Расширение файлов планов. */
    public static final String EXTENSION = ".md";

    /** Имя файла для плана с пустым или полностью недопустимым именем. */
    public static final String DEFAULT_FILE_NAME = "План";

    /** Наибольшая длина имени файла без расширения. */
    public static final int MAX_FILE_NAME_LENGTH = 80;

    /** Имена устройств Windows: файл «CON.md» создать нельзя. */
    private static final Set<String> DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final Path dir;

    /**
     * Создаёт репозиторий для папки (папка не создаётся).
     *
     * @param dir папка с планами (обычно CashMemory)
     */
    public PlanRepository(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
    }

    /** @return папка с планами */
    public Path dir() {
        return dir;
    }

    /**
     * Перечисляет планы в папке: только файлы {@code *.md}, кроме служебных
     * ({@link CashMemoryLayout#isReservedPlanName(String)}), по имени без учёта регистра.
     *
     * @return список планов; пустой, если папки нет
     * @throws UncheckedIOException если папку не удалось прочитать
     */
    public List<PlanFileInfo> list() {
        List<PlanFileInfo> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (!hasPlanExtension(fileName) || !Files.isRegularFile(file)) {
                    continue;
                }
                String name = PlanMarkdownReader.nameWithoutExtension(file);
                if (CashMemoryLayout.isReservedPlanName(name)) {
                    continue;
                }
                try {
                    result.add(new PlanFileInfo(name, file, Files.getLastModifiedTime(file)));
                } catch (NoSuchFileException e) {
                    // Файл удалили между чтением каталога и запросом атрибутов: просто пропускаем.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать папку планов: " + dir, e);
        }
        result.sort(Comparator.comparing(PlanFileInfo::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PlanFileInfo::name));
        return result;
    }

    /**
     * Загружает план. Если имя в заголовке не соответствует имени файла, имя плана берётся из имени файла
     * (с сообщением INFO).
     *
     * @param file  файл плана
     * @param today сегодняшняя дата (для умолчаний)
     * @return план и диагностика
     * @throws IOException если файл не читается
     * @throws ru.cashprediction.core.markdown.MarkdownParseException если файл не является планом
     */
    public ReadResult load(Path file, LocalDate today) throws IOException {
        ReadResult result = PlanMarkdownReader.read(file, today);
        String fileBase = PlanMarkdownReader.nameWithoutExtension(file);
        Plan plan = result.plan();
        if (!fileBaseName(plan.name()).equals(fileBase)) {
            List<Diagnostic> diagnostics = new ArrayList<>(result.diagnostics());
            diagnostics.add(Diagnostic.info("Имя плана в заголовке («" + plan.name()
                    + "») не совпадает с именем файла; используется имя файла «" + fileBase + "»"));
            return new ReadResult(plan.withName(fileBase), diagnostics);
        }
        return result;
    }

    /**
     * Атомарно сохраняет план в файл.
     *
     * @param plan план
     * @param file целевой файл
     * @throws IOException если запись не удалась (прежнее содержимое файла в этом случае не изменено)
     */
    public void save(Plan plan, Path file) throws IOException {
        AtomicFiles.writeString(file, PlanMarkdownWriter.write(plan));
    }

    /**
     * Путь к файлу для плана с данным именем в этой папке.
     *
     * @param planName имя плана
     * @return {@code dir/<очищенное имя>.md}; к зарезервированному имени добавляется «_» ({@code settings_.md})
     */
    public Path pathFor(String planName) {
        return dir.resolve(fileBaseName(planName) + EXTENSION);
    }

    /**
     * Имя файла (без расширения) для имени плана: {@link #sanitizeFileName(String)} плюс «_» к именам
     * служебных файлов, чтобы план «settings» не затёр настройки.
     *
     * @param planName имя плана
     * @return безопасное имя файла без расширения
     */
    public static String fileBaseName(String planName) {
        String base = sanitizeFileName(planName);
        return CashMemoryLayout.isReservedPlanName(base) ? base + "_" : base;
    }

    /**
     * Превращает имя плана в допустимое имя файла Windows.
     *
     * <ul>
     *   <li>символы {@code \ / : * ? " < > |} и управляющие символы заменяются на «_»;</li>
     *   <li>пробелы по краям и точки в конце отбрасываются (Windows их молча срезает);</li>
     *   <li>к именам устройств CON, PRN, AUX, NUL, COM1–COM9, LPT1–LPT9 добавляется «_» ({@code CON} → {@code CON_});</li>
     *   <li>длина ограничивается {@value #MAX_FILE_NAME_LENGTH} символами;</li>
     *   <li>пустой результат заменяется на «План».</li>
     * </ul>
     *
     * @param name имя плана
     * @return имя файла без расширения
     */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return DEFAULT_FILE_NAME;
        }
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            boolean forbidden = cp < 0x20 || cp == 0x7F || "\\/:*?\"<>|".indexOf(cp) >= 0;
            sb.appendCodePoint(forbidden ? '_' : cp);
        });
        // Обрезка до проверки имени устройства: «CON» + 77 пробелов + текст после обрезки и срезания
        // пробелов превращается в «CON», и такое имя тоже нужно поймать.
        String result = truncate(trimEnds(sb.toString()));
        if (result.isEmpty()) {
            return DEFAULT_FILE_NAME;
        }
        // Windows считает устройством и «CON», и «CON.txt»: проверяем часть до первой точки.
        int dot = result.indexOf('.');
        String stem = dot < 0 ? result : result.substring(0, dot);
        if (DEVICE_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
            // Добавленный «_» может вывести длину за предел — обрезаем ещё раз. Префикс «CON_» при этом
            // остаётся, поэтому новое имя устройства появиться не может.
            result = truncate(stem + "_" + result.substring(stem.length()));
        }
        return result;
    }

    /** Ограничивает длину {@value #MAX_FILE_NAME_LENGTH} кодовыми точками и срезает то, что Windows срежет сама. */
    private static String truncate(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_FILE_NAME_LENGTH) {
            return value;
        }
        return trimEnds(value.substring(0, value.offsetByCodePoints(0, MAX_FILE_NAME_LENGTH)));
    }

    /**
     * Переименовывает план: файл получает новое имя, а заголовок {@code # План:} внутри — новое имя плана.
     * Остальной текст файла не меняется ни на символ.
     *
     * @param from    текущий файл плана
     * @param newName новое имя плана
     * @return путь к переименованному файлу
     * @throws FileAlreadyExistsException если другой файл с таким именем уже существует
     * @throws IOException                если чтение или запись не удались
     */
    public Path rename(Path from, String newName) throws IOException {
        String title = newName == null || newName.isBlank() ? DEFAULT_FILE_NAME
                : newName.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').strip();
        Path target = from.toAbsolutePath().normalize().resolveSibling(fileBaseName(title) + EXTENSION);
        String text = AtomicFiles.readString(from);
        String updated = replaceTitle(text, title);

        boolean targetExists = Files.exists(target);
        boolean sameFile = targetExists && Files.isSameFile(from, target);
        if (targetExists && !sameFile) {
            throw new FileAlreadyExistsException(target.toString(), null, "План с таким именем уже существует");
        }
        if (sameFile) {
            AtomicFiles.writeString(from, updated);
            String currentName = from.toAbsolutePath().normalize().getFileName().toString();
            if (!currentName.equals(target.getFileName().toString())) {
                // Меняется только регистр букв: Windows считает это тем же файлом, поэтому переименовываем
                // через промежуточное имя.
                Path intermediate = target.resolveSibling(fileBaseName(title) + ".renaming-" + System.nanoTime() + EXTENSION);
                Files.move(from, intermediate);
                Files.move(intermediate, target);
            }
            return target;
        }
        AtomicFiles.writeString(target, updated);
        Files.delete(from);
        return target;
    }

    /**
     * Время последнего изменения файла (для обнаружения правки снаружи).
     *
     * @param file файл плана
     * @return время изменения
     * @throws IOException если атрибуты не читаются
     */
    public FileTime lastModified(Path file) throws IOException {
        return Files.getLastModifiedTime(file);
    }

    /**
     * Заменяет заголовок плана в тексте, сохраняя стиль переводов строк. Заголовок ищется только
     * до первой секции {@code ##}, как это делает читатель; если его нет, он добавляется в начало.
     */
    static String replaceTitle(String text, String title) {
        String separator = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\\r\\n|\\r|\\n", -1);
        String titleLine = MarkdownFormat.TITLE_PREFIX + title;
        for (int i = 0; i < lines.length; i++) {
            if (PlanMarkdownReader.isSectionHeading(lines[i])) {
                break;
            }
            if (PlanMarkdownReader.titleName(lines[i]).isPresent()) {
                lines[i] = titleLine;
                return String.join(separator, lines);
            }
        }
        return titleLine + separator + separator + text;
    }

    private static boolean hasPlanExtension(String fileName) {
        return fileName.length() > EXTENSION.length()
                && fileName.regionMatches(true, fileName.length() - EXTENSION.length(), EXTENSION, 0, EXTENSION.length());
    }

    private static String trimEnds(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '.' || Character.isWhitespace(value.charAt(end - 1))
                || Character.isSpaceChar(value.charAt(end - 1)))) {
            end--;
        }
        return value.substring(0, end).strip();
    }
}
