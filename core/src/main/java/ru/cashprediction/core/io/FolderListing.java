package ru.cashprediction.core.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Содержимое папки для окна ядра «Выбор файла» web-клиента (спецификация v2, §6.21; архитектура §2 — перенесено из
 * web {@code FolderBrowserApi}, прежний класс работает до этапа S4).
 *
 * <p>JavaFX: {@code FileChooser}/{@code DirectoryChooser} → Swing: {@code JFileChooser} → Web: форма ядра
 * {@code FileBrowserForm} поверх этого класса. Браузер не видит файловую систему, поэтому список строит сервер на том же
 * компьютере.</p>
 *
 * <p><b>Правила:</b> только чтение (ничего не создаёт и не удаляет); сначала папки, затем файлы, внутри — по имени без
 * учёта регистра, как в проводнике; скрытые и недоступные элементы не показываются; в самой папке CashMemory скрыты
 * служебные файлы ({@code settings.md}, {@code web-session.md}, {@code web-session.plan.md}, {@code session-*.xml});
 * в режиме файлов показываются только файлы с расширениями фильтра; огромные папки обрезаются до
 * {@link #maxEntries()} элементов. Ошибки — исключения NIO: {@link NoSuchFileException} (папки нет),
 * {@link AccessDeniedException} (нет доступа); тексты «Папка «{0}» не найдена» и «Нет доступа к папке «{0}»» выбирает
 * форма.</p>
 *
 * <p>Экземпляр неизменяем и потокобезопасен.</p>
 */
public final class FolderListing {

    /** Наибольшее число элементов по умолчанию. */
    public static final int MAX_ENTRIES = 2000;

    /** Формат времени изменения «13.09.2026 10:15» (§6.21, колонка «Изменён»). */
    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");

    /** Что показывать. */
    public enum Mode {
        /** Только папки (выбор папки). */
        DIRECTORIES,
        /** Папки и файлы с расширениями фильтра (выбор файла). */
        FILES
    }

    /**
     * Элемент папки.
     *
     * @param name         имя
     * @param path         абсолютный нормализованный путь
     * @param directory    папка ли
     * @param size         размер файла в байтах (для папки 0)
     * @param modified     время изменения
     * @param modifiedText время изменения «dd.MM.yyyy HH:mm» в часовом поясе обозревателя
     */
    public record Entry(String name, Path path, boolean directory, long size, Instant modified, String modifiedText) {
        /** Проверяет поля. */
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(modified, "modified");
            modifiedText = Objects.requireNonNullElse(modifiedText, "");
        }
    }

    /**
     * Корень файловой системы (диск Windows) для списка «Диск:».
     *
     * @param name имя, например {@code C:\}
     * @param path путь корня
     */
    public record Root(String name, Path path) {
        /** Проверяет поля. */
        public Root {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * Содержимое папки.
     *
     * @param folder    абсолютная нормализованная папка
     * @param parent    родительская папка или {@code null} для корня
     * @param entries   элементы по порядку показа
     * @param truncated обрезан ли список
     */
    public record Listing(Path folder, Path parent, List<Entry> entries, boolean truncated) {
        /** Проверяет поля и копирует список. */
        public Listing {
            Objects.requireNonNull(folder, "folder");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    private final Path cashMemory;
    private final ZoneId zone;
    private final int maxEntries;

    /**
     * Обозреватель в часовом поясе системы с пределом {@link #MAX_ENTRIES}.
     *
     * @param cashMemory папка CashMemory (в ней скрываются служебные файлы)
     */
    public FolderListing(Path cashMemory) {
        this(cashMemory, ZoneId.systemDefault(), MAX_ENTRIES);
    }

    /**
     * Обозреватель с заданным часовым поясом и пределом (для тестов).
     *
     * @param cashMemory папка CashMemory
     * @param zone       часовой пояс для текста времени изменения
     * @param maxEntries наибольшее число элементов (≥ 1)
     */
    public FolderListing(Path cashMemory, ZoneId zone, int maxEntries) {
        this.cashMemory = Objects.requireNonNull(cashMemory, "cashMemory").toAbsolutePath().normalize();
        this.zone = Objects.requireNonNull(zone, "zone");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1: " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    /** @return папка CashMemory (кнопка «CashMemory» окна выбора) */
    public Path cashMemory() {
        return cashMemory;
    }

    /** @return наибольшее число элементов списка */
    public int maxEntries() {
        return maxEntries;
    }

    /** @return корни файловой системы (диски) в порядке ОС */
    public List<Root> roots() {
        List<Root> roots = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            roots.add(new Root(root.toString(), root));
        }
        return List.copyOf(roots);
    }

    /**
     * Содержимое папки.
     *
     * @param folder     папка (относительный путь разрешается от текущей папки процесса)
     * @param mode       что показывать
     * @param extensions расширения файлов без точки (без учёта регистра), например {@code [md]}; пусто — все файлы
     * @return содержимое
     * @throws NoSuchFileException   если папки нет или это не папка
     * @throws AccessDeniedException если папку нельзя прочитать
     * @throws IOException           при другой ошибке чтения
     */
    public Listing list(Path folder, Mode mode, List<String> extensions) throws IOException {
        Objects.requireNonNull(mode, "mode");
        Path dir = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new NoSuchFileException(dir.toString());
        }
        List<String> suffixes = new ArrayList<>();
        for (String extension : extensions == null ? List.<String>of() : extensions) {
            suffixes.add("." + extension.strip().toLowerCase(Locale.ROOT));
        }
        boolean inCashMemory = dir.equals(cashMemory);
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                Entry entry = describe(child, mode, suffixes, inCashMemory);
                if (entry == null) {
                    continue;
                }
                if (entries.size() >= maxEntries) {
                    truncated = true;
                    break;
                }
                entries.add(entry);
            }
        }
        // Папки сначала, затем файлы; внутри — по имени без учёта регистра, как в проводнике.
        entries.sort(Comparator.comparing((Entry e) -> !e.directory())
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return new Listing(dir, dir.getParent(), entries, truncated);
    }

    /**
     * Служебный ли это файл CashMemory, который окно выбора не показывает.
     *
     * @param fileName имя файла
     * @return {@code true} для settings.md, web-session.md, web-session.plan.md и session-*.xml
     */
    public static boolean isServiceFile(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.equals(CashMemoryLayout.SETTINGS) || name.equals(CashMemoryLayout.WEB_SESSION)
                || name.equals(CashMemoryLayout.WEB_SESSION_PLAN)
                || (name.startsWith(CashMemoryLayout.SESSION_XML_PREFIX) && name.endsWith(CashMemoryLayout.SESSION_XML_SUFFIX));
    }

    /** @return описание элемента или {@code null}, если элемент не показывается */
    private Entry describe(Path child, Mode mode, List<String> suffixes, boolean inCashMemory) {
        try {
            Path fileName = child.getFileName();
            if (fileName == null || Files.isHidden(child)) {
                return null;
            }
            BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class);
            String name = fileName.toString();
            boolean directory = attributes.isDirectory();
            if (!directory) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (mode == Mode.DIRECTORIES || (inCashMemory && isServiceFile(name))
                        || (!suffixes.isEmpty() && suffixes.stream().noneMatch(lower::endsWith))) {
                    return null;
                }
            }
            Instant modified = attributes.lastModifiedTime().toInstant();
            return new Entry(name, child.toAbsolutePath().normalize(), directory, directory ? 0 : attributes.size(),
                    modified, MODIFIED.format(modified.atZone(zone)));
        } catch (IOException | SecurityException e) {
            // Системные и недоступные элементы (например, «System Volume Information») просто не показываются.
            return null;
        }
    }
}
