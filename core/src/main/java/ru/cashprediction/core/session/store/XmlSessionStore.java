package ru.cashprediction.core.session.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.codec.SessionDocument;
import ru.cashprediction.core.session.codec.SnapshotFormatException;
import ru.cashprediction.core.session.codec.XmlSnapshotCodec;
import ru.cashprediction.core.text.Texts;

/**
 * Хранилище снимка сессии в XML-файле {@code CashMemory/session-<клиент>.xml} (раздел 5.4 плана).
 *
 * <p>Маркер сеанса — атрибуты корневого элемента, снимок — вложенные элементы; формат описан
 * в {@link XmlSnapshotCodec}. Запись атомарная ({@link AtomicFiles#writeString}): временный файл
 * в той же папке и переименование поверх, поэтому при аварии посреди записи остаётся прежний
 * целый файл.</p>
 *
 * <p><b>Маркер без потери снимка.</b> {@link #markDirty} перечитывает файл и меняет только атрибуты
 * маркера. {@link #save} пишет снимок с текущим маркером: маркер запоминается в памяти после
 * {@code markDirty}/{@code markClean}, а до этого читается из файла.</p>
 *
 * <p><b>Повреждённый файл</b> (ручная правка в Блокноте): {@link #load} бросает
 * {@link SessionStoreException} с причиной, диалог восстановления отключает кнопку, а следующий
 * {@link #save} просто перезаписывает файл целиком.</p>
 *
 * <p>Класс потокобезопасен: все операции синхронизированы на экземпляре.</p>
 */
public final class XmlSessionStore implements SessionStore {

    private final Path file;
    private final String client;
    private final XmlSnapshotCodec codec = new XmlSnapshotCodec();

    /** Текущий маркер сеанса по мнению этого процесса; действителен, если {@link #markerKnown}. */
    private SessionMarker marker;
    /** Прочитан или записан ли уже маркер. */
    private boolean markerKnown;
    /** Ошибка последней «тихой» операции. */
    private String lastError;

    /**
     * Создаёт хранилище.
     *
     * @param file   путь к XML-файлу, обычно {@code CashMemory/session-fx.xml}
     * @param client клиент
     */
    public XmlSessionStore(Path file, String client) {
        this.file = Objects.requireNonNull(file, "file");
        this.client = SnapshotSchema.requireClient(client);
    }

    /**
     * Создаёт хранилище со стандартным именем файла в папке CashMemory.
     *
     * @param cashMemory папка CashMemory
     * @param client     клиент
     * @return хранилище для файла {@code session-<клиент>.xml}
     */
    public static XmlSessionStore inCashMemory(Path cashMemory, String client) {
        return new XmlSessionStore(cashMemory.resolve("session-" + SnapshotSchema.requireClient(client) + ".xml"), client);
    }

    /**
     * Путь к файлу.
     *
     * @return путь к XML-файлу
     */
    public Path file() {
        return file;
    }

    /**
     * Возвращает машинный идентификатор XML-хранилища.
     * @return {@code xml}
     */
    @Override
    public String id() {
        return "xml";
    }

    /**
     * Возвращает название хранилища из общей локализации.
     * @return название для пользователя
     */
    @Override
    public String title() {
        return Texts.get("session.store.title.xml");
    }

    /**
     * Файловое хранилище доступно всегда: ошибки файловой системы (папка только для чтения, файл
     * занят OneDrive) обычно временные и сообщаются для каждой операции отдельно.
     *
     * @return {@code true}
     */
    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * Возвращает пустую причину: файловые ошибки относятся к отдельным операциям.
     * @return пустая строка
     */
    @Override
    public String unavailableReason() {
        return "";
    }

    /**
     * Запоминает маркер и заменяет XML-документ, сохраняя снимок, если старый документ читается.
     * При отсутствии или ошибке чтения старого документа записывает только маркер.
     * Ошибка записи сохраняется в {@link #lastError()}, новый маркер остаётся в памяти.
     * @param newMarker маркер начавшегося сеанса, не {@code null}
     */
    @Override
    public synchronized void markDirty(SessionMarker newMarker) {
        Objects.requireNonNull(newMarker, "marker");
        marker = newMarker;
        markerKnown = true;
        SessionSnapshot keep = readQuietly().map(SessionDocument::snapshot).orElse(null);
        writeQuietly(new SessionDocument(client, newMarker, keep));
    }

    /**
     * Закрывает текущий маркер и заменяет XML-документ с сохранением читаемого снимка.
     * Без текущего маркера ничего не делает. При ошибке записи закрытый маркер остаётся
     * в памяти, а причина сохраняется в {@link #lastError()}.
     */
    @Override
    public synchronized void markClean() {
        SessionMarker current = currentMarker();
        if (current == null) {
            return;
        }
        marker = current.closed();
        SessionSnapshot keep = readQuietly().map(SessionDocument::snapshot).orElse(null);
        writeQuietly(new SessionDocument(client, marker, keep));
    }

    /**
     * Читает маркер из XML-документа, не используя запомненный маркер процесса.
     * @return маркер или пусто при его отсутствии, отсутствии файла либо ошибке чтения или формата
     */
    @Override
    public synchronized Optional<SessionMarker> readMarker() {
        return readQuietly().map(SessionDocument::marker);
    }

    /**
     * Заменяет единый XML-документ снимком с текущим маркером через {@link AtomicFiles}.
     * Содержимое временного файла сбрасывается на диск до перемещения; атомарная замена
     * используется, если поддерживается файловой системой, иначе выполняется обычная замена.
     * До замены старый документ сохраняется; без поддержки атомарного перемещения
     * гарантия целого старого или нового файла при сбое отсутствует.
     * Успех сбрасывает {@link #lastError()}, ошибка ввода-вывода передаётся исключением.
     * @param snapshot снимок, не {@code null}
     * @throws SessionStoreException если запись XML-файла не удалась
     */
    @Override
    public synchronized void save(SessionSnapshot snapshot) throws SessionStoreException {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            AtomicFiles.writeString(file, codec.encodeDocument(new SessionDocument(client, currentMarker(), snapshot)));
            lastError = null;
        } catch (IOException e) {
            throw new SessionStoreException(Texts.get("session.store.xml.writeFailed", file.getFileName(),
                    e.getMessage()), e);
        }
    }

    /**
     * Читает и разбирает XML-документ со снимком.
     * @return снимок или пусто, если файл отсутствует либо содержит только маркер
     * @throws SessionStoreException если файл не читается или документ повреждён
     */
    @Override
    public synchronized Optional<SessionSnapshot> load() throws SessionStoreException {
        return Optional.ofNullable(readDocument()).map(SessionDocument::snapshot);
    }

    /**
     * Читает время сохранения снимка, разбирая XML-документ целиком.
     * @return время или пусто при отсутствии снимка либо ошибке чтения или формата
     */
    @Override
    public synchronized Optional<Instant> lastSavedAt() {
        return readQuietly().map(SessionDocument::snapshot).map(SessionSnapshot::savedAt);
    }

    /**
     * Удаляет XML-файл, если он существует, и сбрасывает маркер в памяти.
     * При ошибке удаления маркер в памяти сохраняется, причина записывается в {@link #lastError()}.
     */
    @Override
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
            marker = null;
            markerKnown = true;
            lastError = null;
        } catch (IOException e) {
            lastError = Texts.get("session.store.xml.deleteFailed", file.getFileName(), e.getMessage());
        }
    }

    /**
     * Возвращает сохранённую ошибку записи маркера или удаления файла.
     * Успешная запись маркера, сохранение снимка или очистка сбрасывает ошибку;
     * чтение и операции без действия её не меняют.
     * @return сообщение или пусто, если сохранённой ошибки нет
     */
    @Override
    public synchronized Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    /** @return документ из файла или {@code null}, если файла нет */
    private SessionDocument readDocument() throws SessionStoreException {
        if (!Files.exists(file)) {
            return null;
        }
        String text;
        try {
            text = AtomicFiles.readString(file);
        } catch (IOException e) {
            throw new SessionStoreException(Texts.get("session.store.xml.readFailed", file.getFileName(),
                    e.getMessage()), e);
        }
        try {
            return codec.decodeDocument(text);
        } catch (SnapshotFormatException e) {
            throw new SessionStoreException(e.getMessage(), e);
        }
    }

    private Optional<SessionDocument> readQuietly() {
        try {
            return Optional.ofNullable(readDocument());
        } catch (SessionStoreException e) {
            // Повреждённый файл: сохранить из него нечего, следующая запись его заменит.
            return Optional.empty();
        }
    }

    private SessionMarker currentMarker() {
        if (!markerKnown) {
            marker = readQuietly().map(SessionDocument::marker).orElse(null);
            markerKnown = true;
        }
        return marker;
    }

    private void writeQuietly(SessionDocument document) {
        try {
            AtomicFiles.writeString(file, codec.encodeDocument(document));
            lastError = null;
        } catch (IOException e) {
            lastError = Texts.get("session.store.xml.writeFailed", file.getFileName(), e.getMessage());
        }
    }
}
