package ru.cashprediction.core.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.MarkdownParseException;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.text.Texts;

/**
 * Локальный адаптер существующего репозитория Markdown. Пути и файловые атрибуты остаются здесь;
 * статические преобразования ссылок обслуживают только файловый выбор интерфейса.
 * Общий экземпляр сериализует собственные операции, но не блокирует сторонний редактор:
 * проверка версии непосредственно перед вызовом репозитория не является межпроцессной транзакцией.
 */
public final class FilePlanStorage implements PlanStorage {
    private final PlanRepository repository;

    /** Совместимый адаптер для старых изолированных контекстов; политика путей определяется папкой файла. */
    public FilePlanStorage() { repository = null; }

    /** @param cashMemory корневая CashMemory для прежней политики защищённых файлов */
    public FilePlanStorage(Path cashMemory) { repository = new PlanRepository(cashMemory); }

    /** @param file выбранный интерфейсом путь @return непрозрачная ссылка для операций хранения */
    public static Reference reference(Path file) { return new Reference(encode(file)); }

    /** @param directory выбранная интерфейсом папка @return непрозрачная коллекция */
    public static Collection collection(Path directory) { return new Collection(encode(directory)); }

    /** @param reference ссылка файлового адаптера @return путь только для интерфейса выбора и состояния окна */
    public static Path path(Reference reference) { return decode(reference.token()); }

    /** @param directory папка интерфейса @param name имя плана @return прежний безопасный путь первого сохранения */
    public static Path pathFor(Path directory, String name) { return new PlanRepository(directory).pathFor(name); }

    /** @param name имя плана @return прежнее безопасное базовое имя для интерфейса выбора */
    public static String fileBaseName(String name) { return PlanRepository.fileBaseName(name); }

    /** {@inheritDoc} */
    @Override public synchronized Result<List<Entry>> list(Collection collection) {
        try {
            List<Entry> entries = new ArrayList<>();
            for (var info : new PlanRepository(decode(collection.token())).list()) {
                try {
                    Version version = observe(info.path());
                    if (!Version.ABSENT.equals(version)) {
                        entries.add(new Entry(reference(info.path()), version, info.name(), info.lastModified().toInstant()));
                    }
                } catch (NoSuchFileException ignored) {
                    // Исчезнувший при перечислении файл пропускается, как в прежнем репозитории.
                }
            }
            return Result.success(List.copyOf(entries));
        } catch (IOException | RuntimeException failure) {
            return failed(failure);
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized Result<Version> version(Reference reference) {
        try { return Result.success(observe(path(reference))); }
        catch (IOException | RuntimeException failure) { return failed(failure); }
    }

    /** {@inheritDoc} */
    @Override public synchronized Result<Snapshot> read(Reference reference, LocalDate today, Optional<Version> expectedVersion) {
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        try {
            Path file = path(reference);
            Version before = observe(file);
            if (Version.ABSENT.equals(before)) return failure(Code.MISSING, Conflict.NONE, "");
            if (expectedVersion.isPresent() && !before.equals(expectedVersion.get())) return changed();
            var loaded = repository(file).load(file, today);
            if (!before.equals(observe(file))) return changed();
            return Result.success(new Snapshot(reference, before, loaded.plan(), loaded.diagnostics()));
        } catch (IOException | RuntimeException failure) { return failed(failure); }
    }

    /** {@inheritDoc} */
    @Override public synchronized Result<Stored> write(Reference reference, Plan plan, Version expectedVersion) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        try {
            Path file = path(reference);
            // Считаем версию именно записываемого текста; последующая внешняя правка не станет нашей меткой.
            byte[] contents = PlanMarkdownWriter.write(plan).getBytes(StandardCharsets.UTF_8);
            if (!expectedVersion.equals(observe(file))) return changed();
            requireWritableTarget(file);
            repository(file).save(plan, file);
            return Result.success(new Stored(reference, versionOf(Files.readAttributes(file, BasicFileAttributes.class), digest(contents))));
        } catch (IOException | RuntimeException failure) { return failed(failure); }
    }

    /** {@inheritDoc} */
    @Override public synchronized Result<Stored> rename(Reference reference, String name, Version expectedVersion) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        Path target = null;
        try {
            Path file = path(reference);
            Version before = observe(file);
            if (Version.ABSENT.equals(before)) return failure(Code.MISSING, Conflict.NONE, "");
            if (!expectedVersion.equals(before)) return changed();
            target = renameTarget(file, name);
            Path renamed = repository(file).rename(file, name);
            return Result.success(new Stored(reference(renamed), observe(renamed)));
        } catch (IOException | RuntimeException failure) {
            // Только занятое итоговое имя является бизнес-конфликтом, не родитель или временный файл.
            if (failure instanceof FileAlreadyExistsException exists && target != null
                    && target.toString().equals(exists.getFile())) {
                return failure(Code.CONFLICT, Conflict.NAME_EXISTS, exists.getMessage());
            }
            return failed(failure);
        }
    }

    /** Возвращает прежний репозиторий с политикой защищённых файлов приложения. */
    private PlanRepository repository(Path file) {
        return repository == null ? new PlanRepository(file.toAbsolutePath().getParent()) : repository;
    }

    /** Наблюдает и метку, и содержимое: сохранение старой метки редактором не скрывает изменение. */
    private static Version observe(Path file) throws IOException {
        BasicFileAttributes before;
        try { before = Files.readAttributes(file, BasicFileAttributes.class); }
        catch (NoSuchFileException missing) {
            // На Windows файл вместо родительской папки тоже может сообщаться как отсутствие потомка.
            requireDirectoryAncestors(file.getParent());
            return Version.ABSENT;
        }
        // Папка и специальные объекты не являются сохранённым планом, как в прежней проверке isRegularFile.
        if (!before.isRegularFile()) return Version.ABSENT;
        MessageDigest digest = sha256();
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
        if (!stamp(before).equals(stamp(after))) throw new PlanStorageException(
                new Problem(Code.CONFLICT, Conflict.VERSION_CHANGED, ""));
        return versionOf(after, digest.digest());
    }

    /** Отличает отсутствующие папки от файла на месте родителя, не создавая и не меняя объекты. */
    private static void requireDirectoryAncestors(Path directory) throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            BasicFileAttributes attributes;
            try { attributes = Files.readAttributes(current, BasicFileAttributes.class); }
            catch (NoSuchFileException missing) { continue; }
            if (!attributes.isDirectory()) throw new NotDirectoryException(current.toString());
            return;
        }
    }

    /** Запись не должна заменять каталог или специальный объект, даже если сохранённого плана нет. */
    private static void requireWritableTarget(Path file) throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(file, BasicFileAttributes.class); }
        catch (NoSuchFileException missing) { return; }
        if (!attributes.isRegularFile()) throw new FileSystemException(file.toString(), null, Texts.get("err.generic"));
    }

    /** Повторяет только вычисление итогового имени репозитория для точной классификации его коллизии. */
    private static Path renameTarget(Path file, String name) {
        String title = name.isBlank() ? PlanRepository.DEFAULT_FILE_NAME
                : name.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').strip();
        return file.resolveSibling(fileBaseName(title) + PlanRepository.EXTENSION);
    }

    /** Собирает непрозрачную версию без раскрытия атрибутов бизнес-потребителю. */
    private static Version versionOf(BasicFileAttributes attributes, byte[] digest) {
        return new Version(stamp(attributes) + ":" + HexFormat.of().formatHex(digest));
    }

    /** Учитывает замену файла, размер и изменение времени, сохраняя прежнюю семантику внешней метки. */
    private static String stamp(BasicFileAttributes attributes) {
        return attributes.lastModifiedTime() + ":" + attributes.creationTime() + ":" + attributes.size() + ":" + attributes.fileKey();
    }

    /** Вычисляет отпечаток записываемого снимка средствами JDK. */
    private static byte[] digest(byte[] contents) { return sha256().digest(contents); }

    /** Получает обязательный алгоритм JDK; его отсутствие является ошибкой окружения. */
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Кодирует локатор; значение токена не является контрактом бизнес-слоя. */
    private static String encode(Path path) {
        return Base64.getUrlEncoder().encodeToString(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Принимает только собственный канонический абсолютный локатор, не разрешая неверный токен относительно cwd. */
    private static Path decode(String token) {
        try {
            Path file = Path.of(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            if (!file.isAbsolute() || !encode(file).equals(token)) throw new IllegalArgumentException();
            return file;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(Texts.get("err.generic"), invalid);
        }
    }

    /** Создаёт структурированный отказ из существующего исключения адаптера. */
    private static <T> Result<T> failed(Exception failure) {
        if (failure instanceof PlanStorageException storage) return Result.failure(storage.problem());
        if (failure instanceof UncheckedIOException unchecked) return failed(unchecked.getCause());
        Code code = failure instanceof NoSuchFileException ? Code.MISSING
                : failure instanceof MarkdownParseException ? Code.CORRUPT : Code.IO_ERROR;
        // FileAlreadyExistsException вне коллизии итогового имени rename - ошибка инфраструктуры.
        return failure(code, Conflict.NONE, failure.getMessage());
    }

    /** Создаёт отказ без записи при отличии версии. */
    private static <T> Result<T> changed() { return failure(Code.CONFLICT, Conflict.VERSION_CHANGED, ""); }

    /** Собирает отказ единственным способом. */
    private static <T> Result<T> failure(Code code, Conflict conflict, String detail) {
        return Result.failure(new Problem(code, conflict, detail));
    }
}
