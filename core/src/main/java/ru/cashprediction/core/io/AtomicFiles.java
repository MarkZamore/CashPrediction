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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Атомарная запись файлов CashMemory.
 *
 * <p>Зачем: приложение может упасть или быть убито в любой момент, в том числе посреди записи
 * снимка сессии или плана. Если писать прямо в целевой файл, после сбоя останется обрезанный файл,
 * и восстановление станет невозможным ровно тогда, когда оно нужнее всего.</p>
 *
 * <p>Как: содержимое пишется во временный файл В ТОЙ ЖЕ ПАПКЕ ({@code имя.<pid>.<nano>.tmp}),
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

    /** Шаблон временных файлов этого класса: {@code <что-угодно>.<pid>.<nano>.tmp}. */
    private static final Pattern TMP_NAME = Pattern.compile(".+\\.\\d+\\.-?\\d+\\.tmp");

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
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Атомарно записывает байты.
     *
     * @param target целевой файл
     * @param bytes  содержимое
     * @throws IOException если запись не удалась; целевой файл в этом случае не изменён
     */
    public static void write(Path target, byte[] bytes) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException("У файла нет родительской папки: " + target);
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve(target.getFileName() + "." + ProcessHandle.current().pid() + "." + System.nanoTime() + ".tmp");
        try {
            // CREATE_NEW: если такое имя вдруг занято, лучше ошибка, чем чужие данные.
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // Данные и метаданные физически на диске до переименования: иначе после сбоя питания
                // можно получить «успешно переименованный», но пустой файл.
                channel.force(true);
            }
            moveWithRetries(tmp, target);
        } finally {
            // Если перемещение не случилось, временный файл не должен остаться мусором.
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveWithRetries(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                // Файловая система не умеет атомарно: заменяем обычным способом.
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
     * Удаляются только файлы старше минуты: свежий временный файл может принадлежать
     * параллельно работающему экземпляру другого клиента.
     *
     * @param dir папка CashMemory
     * @return число удалённых файлов
     */
    public static int cleanupStaleTemporaryFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        Instant threshold = Instant.now().minus(Duration.ofMinutes(1));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tmp")) {
            for (Path file : stream) {
                try {
                    if (TMP_NAME.matcher(file.getFileName().toString()).matches()
                            && Files.getLastModifiedTime(file).toInstant().isBefore(threshold)) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // Файл занят или уже удалён: не критично, попробуем при следующем запуске.
                }
            }
        } catch (IOException ignored) {
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
