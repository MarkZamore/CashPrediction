package ru.cashprediction.web;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Серверный обозреватель файловой системы для диалогов выбора папки и файла в браузере.
 *
 * <p>JavaFX: {@code DirectoryChooser} → Swing: {@code JFileChooser(DIRECTORIES_ONLY)} → Web: {@code GET /api/fs?mode=dirs}.<br>
 * JavaFX: {@code FileChooser} → Swing: {@code JFileChooser(FILES_ONLY)} + {@code FileNameExtensionFilter} →
 * Web: {@code GET /api/fs?mode=md}.</p>
 *
 * <p>Браузер не видит файловую систему компьютера, поэтому список папок и файлов отдаёт сервер (он работает на том же
 * компьютере). Обозреватель только читает: создавать папки и удалять файлы через него нельзя — программа ничего,
 * кроме CashMemory, на диске не создаёт.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FolderBrowserApi {

    /** Режим «только папки» (аналог DirectoryChooser). */
    public static final String MODE_DIRS = "dirs";

    /** Режим «папки и файлы .md» (аналог FileChooser с фильтром *.md). */
    public static final String MODE_MD = "md";

    /** Наибольшее число элементов в ответе: огромные папки обрезаются, чтобы не повесить браузер. */
    public static final int MAX_ENTRIES = 2000;

    /** Формат времени изменения. */
    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final Path cashMemory;

    /**
     * Создаёт обозреватель.
     *
     * @param cashMemory папка CashMemory — начальная папка диалогов ({@code initialDirectory} в FileChooser)
     */
    public FolderBrowserApi(Path cashMemory) {
        this.cashMemory = Objects.requireNonNull(cashMemory, "cashMemory");
    }

    /**
     * Регистрирует маршрут {@code GET /api/fs}.
     *
     * @param router маршрутизатор
     */
    public void register(Router router) {
        router.add("GET", "/api/fs", request -> ApiResponse.json(
                list(request.query("path", ""), request.query("mode", MODE_MD))));
    }

    /**
     * Содержимое папки.
     *
     * @param pathText полный путь; пусто — CashMemory
     * @param mode     {@value #MODE_DIRS} или {@value #MODE_MD}
     * @return {@code {path, parent, cashMemory, mode, roots:[{name, path}], entries:[{name, path, dir, size, modifiedText}],
     *         truncated}}
     * @throws IOException если папка не читается
     */
    public Map<String, Object> list(String pathText, String mode) throws IOException {
        String m = MODE_DIRS.equalsIgnoreCase(mode) ? MODE_DIRS : MODE_MD;
        Path dir;
        try {
            dir = pathText == null || pathText.isBlank() ? cashMemory : Path.of(pathText.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Недопустимый путь «" + pathText + "»");
        }
        if (!Files.isDirectory(dir)) {
            throw new NoSuchElementException("Папка «" + dir + "» не найдена");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                Map<String, Object> entry = describe(child, m);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (java.nio.file.AccessDeniedException e) {
            throw new IllegalArgumentException("Нет доступа к папке «" + dir + "»");
        }
        // Папки сначала, затем файлы; внутри — по имени без учёта регистра, как в проводнике.
        entries.sort(Comparator.<Map<String, Object>, Boolean>comparing(e -> !(Boolean) e.get("dir"))
                .thenComparing(e -> ((String) e.get("name")).toLowerCase(Locale.ROOT)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", dir.toString());
        result.put("parent", dir.getParent() == null ? null : dir.getParent().toString());
        result.put("cashMemory", cashMemory.toString());
        result.put("mode", m);
        result.put("roots", roots());
        result.put("entries", new ArrayList<Object>(entries));
        result.put("truncated", truncated);
        return result;
    }

    /**
     * Описание элемента папки или {@code null}, если элемент не показывается в этом режиме.
     *
     * @param child элемент
     * @param mode  режим
     * @return объект элемента
     */
    private static Map<String, Object> describe(Path child, String mode) {
        try {
            if (Files.isHidden(child) || child.getFileName() == null) {
                return null;
            }
            BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class);
            String name = child.getFileName().toString();
            boolean dir = attributes.isDirectory();
            if (!dir && (MODE_DIRS.equals(mode) || !name.toLowerCase(Locale.ROOT).endsWith(".md"))) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", child.toAbsolutePath().normalize().toString());
            m.put("dir", dir);
            m.put("size", dir ? null : attributes.size());
            m.put("modifiedText", MODIFIED.format(attributes.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault())));
            return m;
        } catch (IOException | SecurityException e) {
            // Системные и недоступные элементы (например, «System Volume Information») просто не показываются.
            return null;
        }
    }

    /** @return корни файловой системы (диски Windows) */
    private static List<Object> roots() {
        List<Object> roots = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", root.toString());
            m.put("path", root.toString());
            roots.add(m);
        }
        return roots;
    }
}
