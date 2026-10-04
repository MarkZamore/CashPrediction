package ru.cashprediction.core.session.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.codec.MarkdownSnapshotCodec;
import ru.cashprediction.core.session.codec.SessionDocument;
import ru.cashprediction.core.session.codec.SnapshotFormatException;
import ru.cashprediction.core.text.Texts;

/**
 * Хранилище сессии web-сервера: {@code CashMemory/web-session.md} и, при несохранённых изменениях
 * плана, {@code CashMemory/web-session.plan.md} (раздел 5.5 плана).
 *
 * <p>Текст несохранённого плана лежит в отдельном файле, чтобы {@code web-session.md} оставался
 * коротким и читаемым, а план — открывался в любом Markdown-редакторе как обычный план. Файл
 * плана пишется до файла сессии: файл сессии служит «фиксацией», и он никогда не ссылается на
 * ещё не записанный план. Если изменений нет, файл плана удаляется после записи файла сессии.</p>
 *
 * <p>Остальная логика совпадает с {@link XmlSessionStore}: атомарная запись, {@link #markDirty}
 * сохраняет снимок, повреждённый файл даёт {@link SessionStoreException} при чтении и
 * перезаписывается следующим {@link #save}.</p>
 *
 * <p>Класс потокобезопасен: все операции синхронизированы на экземпляре.</p>
 */
public final class MarkdownSessionStore implements SessionStore {

    /** Стандартное имя файла сессии web-сервера. */
    public static final String SESSION_FILE_NAME = "web-session.md";

    /** Стандартное имя файла несохранённого плана web-сервера. */
    public static final String PLAN_FILE_NAME = "web-session.plan.md";

    private final Path sessionFile;
    private final Path planFile;
    private final String client;
    private final MarkdownSnapshotCodec codec = new MarkdownSnapshotCodec();

    /** Текущий маркер сеанса по мнению этого процесса; действителен, если {@link #markerKnown}. */
    private SessionMarker marker;
    /** Прочитан или записан ли уже маркер. */
    private boolean markerKnown;
    /** Ошибка последней «тихой» операции. */
    private String lastError;

    /**
     * Создаёт хранилище.
     *
     * @param sessionFile файл сессии, обычно {@code CashMemory/web-session.md}
     * @param planFile    файл несохранённого плана, обычно {@code CashMemory/web-session.plan.md}
     * @param client      клиент (для web-сервера — {@code web})
     */
    public MarkdownSessionStore(Path sessionFile, Path planFile, String client) {
        this.sessionFile = Objects.requireNonNull(sessionFile, "sessionFile");
        this.planFile = Objects.requireNonNull(planFile, "planFile");
        this.client = SnapshotSchema.requireClient(client);
    }

    /**
     * Создаёт хранилище web-сервера со стандартными именами файлов в папке CashMemory.
     *
     * @param cashMemory папка CashMemory
     * @return хранилище клиента {@code web}
     */
    public static MarkdownSessionStore inCashMemory(Path cashMemory) {
        return new MarkdownSessionStore(cashMemory.resolve(SESSION_FILE_NAME), cashMemory.resolve(PLAN_FILE_NAME),
                SnapshotSchema.CLIENT_WEB);
    }

    /**
     * Путь к файлу сессии.
     *
     * @return путь к {@code web-session.md}
     */
    public Path sessionFile() {
        return sessionFile;
    }

    /**
     * Путь к файлу несохранённого плана.
     *
     * @return путь к {@code web-session.plan.md}
     */
    public Path planFile() {
        return planFile;
    }

    /**
     * Возвращает машинный идентификатор хранилища web-сервера.
     * @return {@code server}
     */
    @Override
    public String id() {
        return "server";
    }

    /**
     * Возвращает название хранилища из общей локализации.
     * @return название для пользователя
     */
    @Override
    public String title() {
        return Texts.get("session.store.title.server");
    }

    /**
     * Файловое хранилище доступно всегда; ошибки файловой системы сообщаются для каждой операции.
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
     * Запоминает маркер и перезаписывает документ с читаемым снимком и прежней ссылкой на план.
     * Если старый документ не читается или повреждён, записывает только маркер.
     * Файл плана не меняется; ошибка записи сохраняется в {@link #lastError()},
     * а новый маркер остаётся в памяти даже при неудаче.
     * @param newMarker маркер начавшегося сеанса, не {@code null}
     */
    @Override
    public synchronized void markDirty(SessionMarker newMarker) {
        Objects.requireNonNull(newMarker, "marker");
        marker = newMarker;
        markerKnown = true;
        rewriteMarker(newMarker);
    }

    /**
     * Закрывает текущий маркер, сохраняя читаемый снимок и ссылку на отдельный план.
     * Без текущего маркера ничего не записывает. Ошибку записи сохраняет в {@link #lastError()};
     * закрытый маркер остаётся в памяти даже при неудаче.
     */
    @Override
    public synchronized void markClean() {
        SessionMarker current = currentMarker();
        if (current == null) {
            return;
        }
        marker = current.closed();
        rewriteMarker(marker);
    }

    /**
     * Читает маркер из документа сессии, не открывая отдельный файл плана.
     * @return маркер или пусто при отсутствии маркера, файла либо при ошибке чтения или формата
     */
    @Override
    public synchronized Optional<SessionMarker> readMarker() {
        return readQuietly().map(SessionDocument::marker);
    }

    /**
     * Записывает снимок с текущим маркером: сначала несохранённый план, затем документ сессии.
     * Документ сессии фиксирует ссылку на уже записанный план; для чистого плана отдельный
     * файл удаляется после записи документа. Каждый файл заменяется через {@link AtomicFiles},
     * с обычным перемещением при отсутствии поддержки атомарного; общей транзакции двух файлов нет.
     * При неудаче записи сессии новый план может остаться рядом со старым документом,
     * а ошибка удаления плана возможна уже после сохранения нового документа. Отката нет.
     * Успех сбрасывает {@link #lastError()}, ошибки ввода-вывода передаются исключением.
     * @param snapshot снимок, не {@code null}
     * @throws SessionStoreException если запись файла или удаление ненужного плана не удалось
     */
    @Override
    public synchronized void save(SessionSnapshot snapshot) throws SessionStoreException {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            PlanState plan = snapshot.plan();
            if (plan.dirty()) {
                AtomicFiles.writeString(planFile, plan.markdown());
            }
            String text = codec.encodeDocument(new SessionDocument(client, currentMarker(), snapshot),
                    plan.dirty() ? planFile.getFileName().toString() : null);
            AtomicFiles.writeString(sessionFile, text);
            if (!plan.dirty()) {
                Files.deleteIfExists(planFile);
            }
            lastError = null;
        } catch (IOException e) {
            throw new SessionStoreException(Texts.get("session.store.md.writeFailed", sessionFile.getFileName(),
                    e.getMessage()), e);
        }
    }

    /**
     * Читает снимок и, при наличии ссылки для несохранённого плана, подставляет текст из {@link #planFile()}.
     * @return снимок или пусто, если файла сессии либо снимка в нём нет
     * @throws SessionStoreException если документ повреждён, не читается или требуемый файл плана отсутствует
     */
    @Override
    public synchronized Optional<SessionSnapshot> load() throws SessionStoreException {
        String text = readText();
        if (text == null) {
            return Optional.empty();
        }
        SessionDocument document = decode(text);
        SessionSnapshot snapshot = document.snapshot();
        if (snapshot == null || !snapshot.plan().dirty() || MarkdownSnapshotCodec.externalPlanFile(text) == null) {
            return Optional.ofNullable(snapshot);
        }
        // Текст плана вынесен в отдельный файл: подставляем его в снимок.
        if (!Files.exists(planFile)) {
            throw new SessionStoreException(Texts.get("session.store.md.planFileMissing", planFile.getFileName()));
        }
        try {
            String markdown = AtomicFiles.readString(planFile);
            return Optional.of(new SessionSnapshot(snapshot.schemaVersion(), snapshot.savedAt(), snapshot.client(),
                    snapshot.main(), PlanState.dirty(markdown), snapshot.windows()));
        } catch (IOException e) {
            throw new SessionStoreException(Texts.get("session.store.md.planFileReadFailed", planFile.getFileName(),
                    e.getMessage()), e);
        }
    }

    /**
     * Получает время снимка из документа сессии без проверки отдельного файла плана.
     * @return время или пусто при отсутствии снимка либо ошибке чтения или формата документа
     */
    @Override
    public synchronized Optional<Instant> lastSavedAt() {
        return readQuietly().map(SessionDocument::snapshot).map(SessionSnapshot::savedAt);
    }

    /**
     * Последовательно удаляет файл сессии и файл плана, затем сбрасывает маркер в памяти.
     * Отсутствующие файлы допустимы. При ошибке удаления отката нет, маркер в памяти
     * не сбрасывается, а причина сохраняется в {@link #lastError()}.
     */
    @Override
    public synchronized void clear() {
        try {
            Files.deleteIfExists(sessionFile);
            Files.deleteIfExists(planFile);
            marker = null;
            markerKnown = true;
            lastError = null;
        } catch (IOException e) {
            lastError = Texts.get("session.store.md.deleteFailed", e.getMessage());
        }
    }

    /**
     * Возвращает сохранённую ошибку записи маркера или удаления файлов.
     * Успешная запись маркера, сохранение снимка или очистка сбрасывает ошибку;
     * чтение и операции без действия её не меняют.
     * @return сообщение или пусто, если сохранённой ошибки нет
     */
    @Override
    public synchronized Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    /**
     * Перезаписывает файл сессии с новым маркером, сохраняя снимок. Файл плана не трогается:
     * ссылка на него в файле сессии остаётся прежней.
     */
    private void rewriteMarker(SessionMarker newMarker) {
        String text = null;
        SessionSnapshot keep = null;
        try {
            text = readText();
            if (text != null) {
                keep = codec.decodeDocument(text).snapshot();
            }
        } catch (SessionStoreException | SnapshotFormatException e) {
            // Повреждённый файл: сохранить из него нечего.
            keep = null;
        }
        String external = keep != null && text != null ? MarkdownSnapshotCodec.externalPlanFile(text) : null;
        try {
            AtomicFiles.writeString(sessionFile, codec.encodeDocument(new SessionDocument(client, newMarker, keep), external));
            lastError = null;
        } catch (IOException e) {
            lastError = Texts.get("session.store.md.writeFailed", sessionFile.getFileName(), e.getMessage());
        }
    }

    private String readText() throws SessionStoreException {
        if (!Files.exists(sessionFile)) {
            return null;
        }
        try {
            return AtomicFiles.readString(sessionFile);
        } catch (IOException e) {
            throw new SessionStoreException(Texts.get("session.store.md.readFailed", sessionFile.getFileName(),
                    e.getMessage()), e);
        }
    }

    private SessionDocument decode(String text) throws SessionStoreException {
        try {
            return codec.decodeDocument(text);
        } catch (SnapshotFormatException e) {
            throw new SessionStoreException(e.getMessage(), e);
        }
    }

    private Optional<SessionDocument> readQuietly() {
        try {
            String text = readText();
            return text == null ? Optional.empty() : Optional.of(decode(text));
        } catch (SessionStoreException e) {
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
}
