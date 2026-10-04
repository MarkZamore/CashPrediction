package ru.cashprediction.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.cashprediction.core.text.Texts;

/**
 * Атомарная запись файлов CashMemory.
 *
 * <p>Зачем: приложение может упасть или быть убито в любой момент, в том числе посреди записи
 * снимка сессии или плана. Если писать прямо в целевой файл, после сбоя останется обрезанный файл,
 * и восстановление станет невозможным ровно тогда, когда оно нужнее всего.</p>
 *
 * <p>Как: содержимое пишется во временный файл В ТОЙ ЖЕ ПАПКЕ
 * ({@code cashprediction-tmp-<pid>-<uuid>.md} или {@code .xml} для текста),
 * сбрасывается на диск ({@code force}), затем переименовывается поверх целевого файла атомарным
 * перемещением. Временный файл живёт внутри CashMemory, поэтому требование «никаких файлов вне
 * CashMemory» не нарушается. Уникальное имя исключает столкновение двух потоков или двух
 * процессов (например, JavaFX- и Swing-клиентов, запущенных одновременно).</p>
 *
 * <p>Особые случаи:</p>
 * <ul>
 *   <li>папка под OneDrive или антивирус могут на мгновение заблокировать файл: перемещение
 *       повторяется до трёх раз с паузой 100 мс;</li>
 *   <li>FAT32 и некоторые сетевые диски не поддерживают атомарное перемещение: тогда используется
 *       обычная замена (атомарность не гарантируется, но файл всё равно пишется целиком заранее).</li>
 * </ul>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class AtomicFiles {

    /** Служебное пространство имён; пользовательские планы не могут занимать этот префикс. */
    static final String TEMP_PREFIX = "cashprediction-tmp-";

    /** Только расходные копии записи и пробы; единственная копия при переименовании сюда не входит. */
    private static final Pattern TMP_NAME = Pattern.compile(TEMP_PREFIX
            + "([1-9]\\d*)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(md|xml|tmp)");

    /** Число попыток перемещения при временной блокировке файла. */
    private static final int MOVE_ATTEMPTS = 3;

    private AtomicFiles() {
    }

    /**
     * Атомарно записывает текст в UTF-8 без BOM.
     *
     * @param target  целевой файл
     * @param content содержимое
     * @throws IOException если запись не удалась; целевой файл в этом случае не изменён
     */
    public static void writeString(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8), textSuffix(content));
    }

    /** Проверка версии и области записи после staging и перед каждой попыткой публикации. */
    @FunctionalInterface
    public interface WriteGuard {
        /** @throws IOException если публикацию следует отменить, сохранив цель */
        void check() throws IOException;
    }

    /**
     * Записывает текст с guard, не меняя протокол атомарной замены существующей цели.
     * @param target цель @param content UTF-8 текст @param replaceExisting разрешена ли замена
     * @param guard проверка после force и перед каждой попыткой move
     * @throws IOException при конфликте или отказе; проверка и move не являются межпроцессным CAS
     */
    public static void writeString(Path target, String content, boolean replaceExisting, WriteGuard guard) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8), textSuffix(content), replaceExisting,
                java.util.Objects.requireNonNull(guard, "guard"));
    }

    /**
     * Проверяет application-managed цель до создания любых папок или staging.
     * @param cashMemory разрешённый корень @param target цель @return проверенный абсолютный путь
     * @throws IOException если путь вне корня или содержит небезопасного предка
     */
    public static Path requireWriteScope(Path cashMemory, Path target) throws IOException {
        Path root = CanonicalPaths.requireNoLinks(cashMemory);
        Path file = CanonicalPaths.requireNoLinks(target);
        if (file.equals(root) || !file.startsWith(root)) throw new IOException("WRITE_OUTSIDE_CASHMEMORY");
        return file;
    }

    /**
     * Записывает application-managed текст только в CashMemory, повторяя scope перед публикацией.
     * @param cashMemory разрешённый корень @param target цель @param content UTF-8 текст
     * @param replaceExisting разрешена ли замена @param guard проверка версии
     * @throws IOException при выходе за scope, конфликте или отказе файловой системы
     */
    public static void writeStringScoped(Path cashMemory, Path target, String content,
            boolean replaceExisting, WriteGuard guard) throws IOException {
        Path file = requireWriteScope(cashMemory, target);
        java.util.Objects.requireNonNull(guard, "guard");
        writeString(file, content, replaceExisting, () -> {
            requireWriteScope(cashMemory, file);
            guard.check();
        });
    }

    /**
     * Записывает экспортные байты и их staging только в CashMemory.
     * @param cashMemory разрешённый корень @param target цель @param bytes данные экспорта
     * @throws IOException при выходе за scope или отказе записи
     */
    public static void writeScoped(Path cashMemory, Path target, byte[] bytes) throws IOException {
        Path file = requireWriteScope(cashMemory, target);
        write(file, bytes, "tmp", true, () -> requireWriteScope(cashMemory, file));
    }

    /** Выбирает расширение по содержимому, а не по имени конечного файла. */
    static String textSuffix(String content) {
        // XML-снимок имеет декларацию; остальной текст остаётся читаемым Markdown независимо от имени цели.
        String text = content.startsWith("\uFEFF") ? content.substring(1) : content;
        text = text.stripLeading();
        return text.startsWith("<?xml") && text.length() > 5 && Character.isWhitespace(text.charAt(5))
                ? "xml" : "md";
    }

    /**
     * Атомарно записывает байты.
     *
     * @param target целевой файл
     * @param bytes  содержимое
     * @throws IOException если запись не удалась; целевой файл в этом случае не изменён
     */
    public static void write(Path target, byte[] bytes) throws IOException {
        write(target, bytes, "tmp");
    }

    /** Создаёт уникальный служебный путь без обращения к диску; расширение отражает вид содержимого. */
    static Path temporaryPath(Path target, String suffix) {
        return target.toAbsolutePath().resolveSibling(TEMP_PREFIX + ProcessHandle.current().pid()
                + "-" + UUID.randomUUID() + "." + suffix);
    }

    /** Резервирует весь префикс, включая незавершённые имена и алиасы Windows с конечными точками. */
    static boolean isTemporaryName(String name) {
        return name != null && name.strip().toLowerCase(Locale.ROOT).startsWith(TEMP_PREFIX);
    }

    /** Записывает копию с нужным расширением, сохраняя сброс на диск и атомарную замену. */
    private static void write(Path target, byte[] bytes, String suffix) throws IOException {
        write(target, bytes, suffix, true, () -> { });
    }

    /** Подготавливает полную расходную копию, затем проверяет право публикации. */
    private static void write(Path target, byte[] bytes, String suffix, boolean replaceExisting, WriteGuard guard) throws IOException {
        target = CanonicalPaths.requireNoLinks(target);
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException(Texts.get("io.error.noParentFolder", target));
        }
        Files.createDirectories(dir);
        CanonicalPaths.requireNoLinks(target);
        Path tmp = temporaryPath(target, suffix);
        CanonicalPaths.requireNoLinks(tmp);
        boolean created = false;
        Throwable primary = null;
        try {
            // CREATE_NEW: если такое имя вдруг занято, лучше ошибка, чем чужие данные.
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // Данные и метаданные физически на диске до переименования: иначе после сбоя питания
                // можно получить «успешно переименованный», но пустой файл.
                channel.force(true);
            }
            moveWithRetries(tmp, target, replaceExisting, guard);
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            // Не удаляем ничего через подменённого предка; cleanup не маскирует primary failure.
            if (created) {
                try {
                    CanonicalPaths.requireNoLinks(tmp);
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanup) {
                    if (primary != null) primary.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
    }

    private static void moveWithRetries(Path tmp, Path target, boolean replaceExisting, WriteGuard guard) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            CanonicalPaths.requireNoLinks(tmp);
            CanonicalPaths.requireNoLinks(target);
            guard.check();
            // ATOMIC_MOVE допускает замену занятой цели: создание без overwrite использует отдельный move.
            if (!replaceExisting) {
                Files.move(tmp, target);
                return;
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                // Файловая система не умеет атомарно: заменяем обычным способом.
                CanonicalPaths.requireNoLinks(tmp);
                CanonicalPaths.requireNoLinks(target);
                guard.check();
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                last = e;
            } catch (FileSystemException e) {
                // На Windows занятый другим процессом файл даёт FileSystemException с текстом «used by another process».
                last = e;
            }
            sleepQuietly(100);
        }
        throw last;
    }

    /**
     * Читает текстовый файл в UTF-8, отбрасывая BOM, если его добавил Блокнот при ручной правке.
     *
     * @param file файл
     * @return содержимое без BOM
     * @throws IOException если файл не читается
     */
    public static String readString(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /**
     * Удаляет временные файлы, оставшиеся после аварийного завершения посреди записи.
     * Удаляются только обычные файлы строгого служебного шаблона старше минуты, если PID
     * владельца больше не существует. Живой PID (даже повторно использованный), ссылки,
     * старые неоднозначные .tmp и промежуточные копии переименования сохраняются.
     *
     * @param dir папка CashMemory
     * @return число удалённых файлов
     */
    public static int cleanupStaleTemporaryFiles(Path dir) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        Instant threshold = Instant.now().minus(Duration.ofMinutes(1));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                try {
                    Matcher name = TMP_NAME.matcher(file.getFileName().toString());
                    if (name.matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(threshold)
                            && ProcessHandle.of(Long.parseLong(name.group(1))).isEmpty()) {
                        if (Files.deleteIfExists(file)) removed++;
                    }
                } catch (IOException | IllegalArgumentException | SecurityException ignored) {
                    // Файл занят или уже удалён: не критично, попробуем при следующем запуске.
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Папка недоступна для чтения: очистка не обязательна для работы.
        }
        return removed;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
