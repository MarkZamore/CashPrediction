package ru.cashprediction.core.session.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.SnapshotSchema;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.session.codec.SnapshotFormatException;
import ru.cashprediction.core.text.Texts;

/**
 * Хранилище снимка сессии в реестре Windows (раздел 5.3 плана).
 *
 * <p><b>Узел.</b> У каждой установки (портативной копии) свой узел
 * {@code HKCU\Software\JavaSoft\Prefs\ru\cashprediction\session\<клиент>-<8 hex SHA-256 пути CashMemory>},
 * например {@code …\session\fx-3fa92c1d} (решение L2: две копии на одном компьютере не делят снимки).
 * Явный префикс ({@code --registry-node}, свойство {@value #PROPERTY_NODE}) должен начинаться с
 * {@value #REQUIRED_PREFIX}; узел тогда {@code <префикс>/<клиент>}. Старые клиенты до этапа S4 используют
 * прежний общий узел {@code …\session\<клиент>} ({@link #forClient(String)}).</p>
 *
 * <p>Раскладка узла:</p>
 * <pre>
 * schema=1  state=running|closed  pid=12345  started.at=…  client=fx      маркер сеанса
 * cashmemory.path=D:\CashPrediction\CashMemory                            чья это копия (если известно)
 * plan.path=…  view=TABLE  windows.count=1  window.0.type=RULE_EDITOR     читаемые дубли для regedit
 * snapshot.count=4  snapshot.length=15321  snapshot.crc32=8f3a1c22
 * snapshot.0 … snapshot.3                                                 JSON-куски по 4096 символов
 * snapshot.time=2026-09-13T10:15:30.123Z                                  пишется ПОСЛЕДНИМ
 * </pre>
 *
 * <p><b>Почему куски.</b> {@code Preferences} ограничивает значение 8192 символами, а снимок с
 * текстом плана легко больше. Кусок в 4096 символов оставляет запас вдвое.</p>
 *
 * <p><b>Почему {@code snapshot.time} последним.</b> Процесс может быть убит посреди записи. Перед
 * записью ключ {@code snapshot.time} удаляется, а после всех кусков пишется снова — это маркер
 * фиксации: если его нет, снимок считается отсутствующим, а не «наполовину новым». Дополнительно
 * длина и CRC32 (по байтам UTF-8) ловят любую порчу, в том числе ручную правку в regedit.</p>
 *
 * <p><b>Читаемые дубли</b> ({@code plan.path}, {@code view}, {@code windows.count},
 * {@code window.N.type}) нужны только человеку, открывшему regedit при проверке задания:
 * JSON-куски там выглядят как {@code /u041f/u043b...}, а дубли — латиница.</p>
 *
 * <p>Класс потокобезопасен: все изменяющие операции синхронизированы на экземпляре.</p>
 */
public final class RegistrySessionStore implements SessionStore {

    /** Префикс узлов реестра; к нему добавляется идентификатор клиента (и хеш установки). */
    public static final String NODE_PREFIX = "ru/cashprediction/session/";

    /** Обязательное начало явного префикса узла: программа не пишет в реестр вне своей ветки. */
    public static final String REQUIRED_PREFIX = "ru/cashprediction/";

    /** Системное свойство с явным префиксом узла (аналог аргумента {@code --registry-node}). */
    public static final String PROPERTY_NODE = "cashprediction.registry.node";

    /** Ключ-дубль: абсолютный путь CashMemory копии программы, которой принадлежит узел. */
    public static final String KEY_CASHMEMORY_PATH = "cashmemory.path";

    /** Максимальный размер одного JSON-куска в символах. */
    public static final int CHUNK_SIZE = 4096;

    /** Ключ: версия схемы. */
    public static final String KEY_SCHEMA = "schema";
    /** Ключ: состояние сеанса. */
    public static final String KEY_STATE = "state";
    /** Ключ: pid процесса. */
    public static final String KEY_PID = "pid";
    /** Ключ: момент начала сеанса. */
    public static final String KEY_STARTED_AT = "started.at";
    /** Ключ: клиент. */
    public static final String KEY_CLIENT = "client";
    /** Ключ-дубль: путь к плану. */
    public static final String KEY_PLAN_PATH = "plan.path";
    /** Ключ-дубль: вид главного окна. */
    public static final String KEY_VIEW = "view";
    /** Ключ-дубль: число окон. */
    public static final String KEY_WINDOWS_COUNT = "windows.count";
    /** Ключ: число JSON-кусков. */
    public static final String KEY_SNAPSHOT_COUNT = "snapshot.count";
    /** Ключ: длина JSON в символах. */
    public static final String KEY_SNAPSHOT_LENGTH = "snapshot.length";
    /** Ключ: CRC32 JSON в UTF-8, 8 шестнадцатеричных цифр. */
    public static final String KEY_SNAPSHOT_CRC = "snapshot.crc32";
    /** Ключ: момент снимка, маркер фиксации. */
    public static final String KEY_SNAPSHOT_TIME = "snapshot.time";

    /** Ключ куска: {@code snapshot.N}. */
    private static final Pattern CHUNK_KEY = Pattern.compile("snapshot\\.(\\d+)");
    /** Ключ-дубль типа окна: {@code window.N.type}. */
    private static final Pattern WINDOW_TYPE_KEY = Pattern.compile("window\\.(\\d+)\\.type");

    private final RegistryBackend backend;
    private final String client;
    private final JsonSnapshotCodec codec = new JsonSnapshotCodec();

    /** Путь CashMemory для читаемого значения {@link #KEY_CASHMEMORY_PATH}; {@code null} — не писать. */
    private final String cashMemoryPath;

    /** Ошибка последней «тихой» операции; {@code null} — ошибок нет. */
    private volatile String lastError;

    /**
     * Создаёт хранилище поверх бэкенда.
     *
     * @param backend узел реестра
     * @param client  клиент, которому принадлежит узел
     */
    public RegistrySessionStore(RegistryBackend backend, String client) {
        this(backend, client, null);
    }

    /**
     * Создаёт хранилище поверх бэкенда и запоминает путь CashMemory, который пишется читаемым значением
     * {@value #KEY_CASHMEMORY_PATH}: по нему в regedit видно, какой портативной копии принадлежит узел.
     *
     * @param backend        узел реестра
     * @param client         клиент, которому принадлежит узел
     * @param cashMemoryPath абсолютный путь папки CashMemory или {@code null}, если значение не пишется
     */
    public RegistrySessionStore(RegistryBackend backend, String client, String cashMemoryPath) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.client = SnapshotSchema.requireClient(client);
        this.cashMemoryPath = cashMemoryPath == null || cashMemoryPath.isBlank() ? null : cashMemoryPath;
    }

    /**
     * Создаёт хранилище в настоящем реестре по прежней схеме узлов, общей для всех копий программы на компьютере.
     *
     * <p>Узел: префикс из системного свойства {@value #PROPERTY_NODE} + {@code /<клиент>}, если свойство задано
     * (так изолируются самотесты старых клиентов); иначе прежний узел {@code ru/cashprediction/session/<клиент>}.
     * Метод оставлен для старых клиентов до этапа S4; новый интерфейс ядра использует
     * {@link #forClient(String, Path)} с отдельным узлом для каждой установки (решение L2).</p>
     *
     * @param client {@code fx}, {@code swing} или {@code web}
     * @return хранилище (возможно, недоступное — см. {@link #isAvailable()})
     * @throws IllegalArgumentException если свойство задаёт префикс вне {@code ru/cashprediction/}
     * @deprecated две портативные копии на одном компьютере делят этот узел; используйте
     *             {@link #forClient(String, Path)}
     */
    @Deprecated
    public static RegistrySessionStore forClient(String client) {
        SnapshotSchema.requireClient(client);
        String prefix = System.getProperty(PROPERTY_NODE);
        String node = prefix == null || prefix.isBlank() ? legacyNodePath(client) : nodePath(prefix, client);
        return new RegistrySessionStore(new PreferencesRegistryBackend(node), client);
    }

    /**
     * Создаёт хранилище в настоящем реестре с узлом этой установки (решение L2): две портативные копии
     * на одном компьютере не делят снимки сеанса.
     *
     * @param client        {@code fx}, {@code swing} или {@code web}
     * @param cashMemoryDir папка CashMemory этой копии программы (может ещё не существовать)
     * @return хранилище (возможно, недоступное — см. {@link #isAvailable()})
     * @see #forClient(String, Path, String)
     */
    public static RegistrySessionStore forClient(String client, Path cashMemoryDir) {
        return forClient(client, cashMemoryDir, null);
    }

    /**
     * Создаёт хранилище в настоящем реестре с узлом установки или с явным префиксом узла.
     *
     * <p>Выбор узла:</p>
     * <ol>
     *   <li>{@code nodePrefixOrNull} задан (аргумент {@code --registry-node} из {@code LaunchOptions}) —
     *       узел {@code <префикс>/<клиент>};</li>
     *   <li>иначе задано системное свойство {@value #PROPERTY_NODE} — узел {@code <свойство>/<клиент>};</li>
     *   <li>иначе узел установки {@link #installationNodePath(String, Path)}, например
     *       {@code ru/cashprediction/session/fx-3fa92c1d}.</li>
     * </ol>
     * <p>Префикс обязан начинаться с {@value #REQUIRED_PREFIX} и не может лежать в ветке снимков
     * {@code ru/cashprediction/session}: так тесты и ручные запуски не могут записать снимки в чужую ветку реестра,
     * в общий узел прежних клиентов или в узел настоящей установки (решение L12). Пустой или состоящий из пробелов
     * префикс равносилен {@code null}: иначе заданное свойство молча уступило бы узлу установки.</p>
     *
     * @param client           {@code fx}, {@code swing} или {@code web}
     * @param cashMemoryDir    папка CashMemory этой копии программы (может ещё не существовать)
     * @param nodePrefixOrNull префикс узла, например {@code ru/cashprediction/selftest/<uuid>}, или {@code null} / пустая
     *                         строка
     * @return хранилище (возможно, недоступное — см. {@link #isAvailable()})
     * @throws IllegalArgumentException если префикс не начинается с {@value #REQUIRED_PREFIX} или некорректен
     */
    public static RegistrySessionStore forClient(String client, Path cashMemoryDir, String nodePrefixOrNull) {
        String node = resolveNodePath(client, cashMemoryDir, nodePrefixOrNull);
        return new RegistrySessionStore(new PreferencesRegistryBackend(node), client, normalizedPath(cashMemoryDir));
    }

    /**
     * Создаёт хранилище в памяти процесса (аргумент {@code --registry memory}): настоящий реестр не трогается,
     * снимок живёт до завершения процесса. Нужен тестам и изолированным запускам.
     *
     * @param client        {@code fx}, {@code swing} или {@code web}
     * @param cashMemoryDir папка CashMemory или {@code null}
     * @return хранилище поверх {@link InMemoryRegistryBackend}
     */
    public static RegistrySessionStore inMemory(String client, Path cashMemoryDir) {
        return new RegistrySessionStore(new InMemoryRegistryBackend(), client,
                cashMemoryDir == null ? null : normalizedPath(cashMemoryDir));
    }

    /**
     * Вычисляет путь узла, который выберет {@link #forClient(String, Path, String)}, не открывая реестр.
     *
     * @param client           идентификатор клиента
     * @param cashMemoryDir    папка CashMemory
     * @param nodePrefixOrNull явный префикс или {@code null} (пустая строка — то же, что {@code null})
     * @return путь узла относительно {@code HKCU\Software\JavaSoft\Prefs}
     * @throws IllegalArgumentException если префикс некорректен
     */
    public static String resolveNodePath(String client, Path cashMemoryDir, String nodePrefixOrNull) {
        SnapshotSchema.requireClient(client);
        Objects.requireNonNull(cashMemoryDir, "cashMemoryDir");
        // Пустой аргумент не должен перекрывать свойство: с ним тестовый запуск ушёл бы в настоящий узел установки.
        boolean explicit = nodePrefixOrNull != null && !nodePrefixOrNull.isBlank();
        String prefix = explicit ? nodePrefixOrNull : System.getProperty(PROPERTY_NODE);
        return prefix == null || prefix.isBlank() ? installationNodePath(client, cashMemoryDir) : nodePath(prefix, client);
    }

    /**
     * Узел установки по умолчанию: {@code ru/cashprediction/session/<клиент>-<8 hex>}, где 8 hex — первые восемь
     * строчных шестнадцатеричных цифр SHA-256 от нормализованного абсолютного пути CashMemory в нижнем регистре.
     *
     * <p>Почему хеш, а не сам путь: имя узла реестра ограничено 80 символами и не должно содержать косую черту;
     * хеш короткий, стабильный и различает копии. Сам путь для человека пишется значением
     * {@value #KEY_CASHMEMORY_PATH}.</p>
     *
     * @param client        идентификатор клиента
     * @param cashMemoryDir папка CashMemory
     * @return путь узла, например {@code ru/cashprediction/session/fx-3fa92c1d}
     */
    public static String installationNodePath(String client, Path cashMemoryDir) {
        return NODE_PREFIX + SnapshotSchema.requireClient(client) + "-" + installationHash(cashMemoryDir);
    }

    /**
     * Первые 8 шестнадцатеричных цифр SHA-256 пути CashMemory (см. {@link #installationNodePath(String, Path)}).
     *
     * @param cashMemoryDir папка CashMemory
     * @return 8 строчных шестнадцатеричных цифр
     */
    public static String installationHash(Path cashMemoryDir) {
        String key = normalizedPath(cashMemoryDir).toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен для любой реализации Java SE; сюда выполнение не доходит.
            throw new IllegalStateException("SHA-256 is not available in this JDK", e);
        }
    }

    /**
     * Прежний общий узел клиента {@code ru/cashprediction/session/<клиент>}.
     *
     * @param client идентификатор клиента
     * @return путь узла
     */
    public static String legacyNodePath(String client) {
        return NODE_PREFIX + SnapshotSchema.requireClient(client);
    }

    /**
     * Проверяет явный префикс узла и приклеивает к нему клиента.
     *
     * @param prefix префикс, например {@code ru/cashprediction/selftest/1b2c} (завершающая косая черта допустима)
     * @param client идентификатор клиента
     * @return {@code <префикс>/<клиент>}
     * @throws IllegalArgumentException если префикс вне {@value #REQUIRED_PREFIX}, в ветке снимков
     *                                  {@code ru/cashprediction/session}, содержит пустые части, {@code .}, {@code ..}
     *                                  или обратную косую черту
     */
    public static String nodePath(String prefix, String client) {
        SnapshotSchema.requireClient(client);
        String p = Objects.requireNonNull(prefix, "prefix").strip();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.startsWith(REQUIRED_PREFIX) || p.length() == REQUIRED_PREFIX.length()) {
            // Тексты из каталога (решение L13): сообщение видит пользователь, запустивший exe с --registry-node.
            throw new IllegalArgumentException(Texts.get("registry.error.prefix", prefix, REQUIRED_PREFIX));
        }
        String sessionArea = NODE_PREFIX.substring(0, NODE_PREFIX.length() - 1);
        if (p.equals(sessionArea) || p.startsWith(NODE_PREFIX)) {
            // Явный узел нужен для изоляции (L12): в ветке session лежат общий узел прежних клиентов и узлы установок.
            throw new IllegalArgumentException(Texts.get("registry.error.sessionArea", prefix, sessionArea));
        }
        for (String part : p.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.contains("\\") || part.length() > 80) {
                throw new IllegalArgumentException(Texts.get("registry.error.part", prefix));
            }
        }
        return p + "/" + client;
    }

    /**
     * Путь узла в реестре, если хранилище работает поверх настоящего реестра.
     *
     * @return путь относительно {@code HKCU\Software\JavaSoft\Prefs} или пустая строка для хранилища в памяти
     */
    public String nodePath() {
        return backend instanceof PreferencesRegistryBackend preferences ? preferences.nodePath() : "";
    }

    /**
     * Путь CashMemory, который пишется значением {@value #KEY_CASHMEMORY_PATH}.
     *
     * @return путь или пусто, если хранилище создано без него
     */
    public Optional<String> cashMemoryPath() {
        return Optional.ofNullable(cashMemoryPath);
    }

    private static String normalizedPath(Path dir) {
        return Objects.requireNonNull(dir, "cashMemoryDir").toAbsolutePath().normalize().toString();
    }

    /**
     * Бэкенд хранилища.
     *
     * @return узел реестра
     */
    public RegistryBackend backend() {
        return backend;
    }

    @Override
    public String id() {
        return "registry";
    }

    @Override
    public String title() {
        return Texts.get("session.store.title.registry");
    }

    @Override
    public boolean isAvailable() {
        return backend.isAvailable();
    }

    @Override
    public String unavailableReason() {
        return backend.isAvailable() ? "" : backend.unavailableReason();
    }

    @Override
    public synchronized void markDirty(SessionMarker marker) {
        Objects.requireNonNull(marker, "marker");
        if (!backend.isAvailable()) {
            lastError = Texts.get("session.registry.unavailable", backend.unavailableReason());
            return;
        }
        try {
            // Только ключи маркера: куски снимка не трогаем, пользователь ещё может восстановиться из них.
            backend.put(KEY_SCHEMA, String.valueOf(SnapshotSchema.CURRENT));
            backend.put(KEY_STATE, marker.state());
            backend.put(KEY_PID, String.valueOf(marker.pid()));
            backend.put(KEY_STARTED_AT, marker.startedAt().toString());
            backend.put(KEY_CLIENT, marker.client());
            putCashMemoryPath();
            backend.flush();
            verifyWritten(KEY_STATE, marker.state());
            lastError = null;
        } catch (SessionStoreException | RuntimeException e) {
            lastError = e.getMessage();
        }
    }

    @Override
    public synchronized void markClean() {
        if (!backend.isAvailable()) {
            lastError = Texts.get("session.registry.unavailable", backend.unavailableReason());
            return;
        }
        try {
            // Без маркера (сеанс не начинался) закрывать нечего: одиночный state=closed был бы неполным маркером.
            if (backend.get(KEY_STATE) == null) {
                return;
            }
            backend.put(KEY_STATE, SessionMarker.CLOSED);
            backend.flush();
            verifyWritten(KEY_STATE, SessionMarker.CLOSED);
            lastError = null;
        } catch (SessionStoreException | RuntimeException e) {
            lastError = e.getMessage();
        }
    }

    @Override
    public synchronized Optional<SessionMarker> readMarker() {
        String state = backend.get(KEY_STATE);
        if (state == null) {
            return Optional.empty();
        }
        try {
            String markerClient = Objects.requireNonNullElse(backend.get(KEY_CLIENT), client);
            return Optional.of(new SessionMarker(state, Long.parseLong(Objects.requireNonNull(backend.get(KEY_PID))),
                    Instant.parse(Objects.requireNonNull(backend.get(KEY_STARTED_AT))), markerClient));
        } catch (RuntimeException e) {
            // Повреждённый маркер равносилен отсутствующему: гадать о сбое по мусору нельзя.
            return Optional.empty();
        }
    }

    @Override
    public synchronized void save(SessionSnapshot snapshot) throws SessionStoreException {
        Objects.requireNonNull(snapshot, "snapshot");
        requireAvailable();
        String json = codec.encode(snapshot);
        List<String> chunks = split(json);
        try {
            // 1. Снимаем маркер фиксации: пока идёт запись, снимок считается отсутствующим.
            backend.remove(KEY_SNAPSHOT_TIME);
            // 2. Читаемые дубли для regedit.
            backend.put(KEY_SCHEMA, String.valueOf(snapshot.schemaVersion()));
            backend.put(KEY_CLIENT, snapshot.client());
            putCashMemoryPath();
            backend.put(KEY_PLAN_PATH, snapshot.main().planPath());
            backend.put(KEY_VIEW, snapshot.main().view());
            List<WindowState> windows = snapshot.windows();
            backend.put(KEY_WINDOWS_COUNT, String.valueOf(windows.size()));
            for (int i = 0; i < windows.size(); i++) {
                WindowState window = windows.get(i);
                backend.put("window." + i + ".type", window.type() == null ? "" : window.type().name());
            }
            // 3. Контрольные значения и куски.
            backend.put(KEY_SNAPSHOT_COUNT, String.valueOf(chunks.size()));
            backend.put(KEY_SNAPSHOT_LENGTH, String.valueOf(json.length()));
            backend.put(KEY_SNAPSHOT_CRC, crc32(json));
            for (int i = 0; i < chunks.size(); i++) {
                backend.put("snapshot." + i, chunks.get(i));
            }
            // 4. Хвосты прошлого, более длинного снимка: иначе в regedit висели бы устаревшие куски и окна.
            removeStale(chunks.size(), windows.size());
            // 5. Маркер фиксации — строго последним.
            backend.put(KEY_SNAPSHOT_TIME, snapshot.savedAt().toString());
            backend.flush();
        } catch (RuntimeException e) {
            throw new SessionStoreException(Texts.get("session.registry.writeFailed", e.getMessage()), e);
        }
        requireAvailable();
        verifyWritten(KEY_SNAPSHOT_TIME, snapshot.savedAt().toString());
        lastError = null;
    }

    @Override
    public synchronized Optional<SessionSnapshot> load() throws SessionStoreException {
        requireAvailable();
        String time = backend.get(KEY_SNAPSHOT_TIME);
        if (time == null) {
            return Optional.empty();
        }
        int count = parseCount(KEY_SNAPSHOT_COUNT);
        int length = parseCount(KEY_SNAPSHOT_LENGTH);
        String crc = backend.get(KEY_SNAPSHOT_CRC);
        if (crc == null) {
            throw corrupted(Texts.get("session.registry.corrupt.noKey", KEY_SNAPSHOT_CRC));
        }
        int expectedCount = (length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (count != expectedCount) {
            throw corrupted(Texts.get("session.registry.corrupt.chunkCount", count, length));
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < count; i++) {
            String chunk = backend.get("snapshot." + i);
            if (chunk == null) {
                throw corrupted(Texts.get("session.registry.corrupt.noChunk", "snapshot." + i));
            }
            sb.append(chunk);
        }
        String json = sb.toString();
        if (json.length() != length) {
            throw corrupted(Texts.get("session.registry.corrupt.length", json.length(), length));
        }
        if (!crc32(json).equalsIgnoreCase(crc.strip())) {
            throw corrupted(Texts.get("session.registry.corrupt.crc"));
        }
        try {
            return Optional.of(codec.decode(json));
        } catch (SnapshotFormatException e) {
            throw new SessionStoreException(Texts.get("session.registry.corrupted", e.getMessage()), e);
        }
    }

    @Override
    public Optional<Instant> lastSavedAt() {
        String time = backend.get(KEY_SNAPSHOT_TIME);
        if (time == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(time));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized void clear() {
        if (!backend.isAvailable()) {
            lastError = Texts.get("session.registry.unavailable", backend.unavailableReason());
            return;
        }
        try {
            for (String key : backend.keys()) {
                backend.remove(key);
            }
            backend.flush();
            lastError = null;
        } catch (SessionStoreException | RuntimeException e) {
            lastError = e.getMessage();
        }
    }

    @Override
    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    /**
     * Режет JSON на куски не длиннее {@link #CHUNK_SIZE} символов.
     *
     * @param json текст
     * @return куски; для пустого текста — один пустой кусок не создаётся, список пуст
     */
    static List<String> split(String json) {
        List<String> chunks = new ArrayList<>();
        // Резать можно даже посреди суррогатной пары: куски склеиваются обратно в ту же строку Java.
        for (int start = 0; start < json.length(); start += CHUNK_SIZE) {
            chunks.add(json.substring(start, Math.min(json.length(), start + CHUNK_SIZE)));
        }
        return chunks;
    }

    /**
     * Вычисляет CRC32 текста в UTF-8.
     *
     * @param text текст
     * @return 8 строчных шестнадцатеричных цифр
     */
    static String crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    private void removeStale(int chunkCount, int windowCount) {
        for (String key : backend.keys()) {
            Matcher chunk = CHUNK_KEY.matcher(key);
            if (chunk.matches() && isAtLeast(chunk.group(1), chunkCount)) {
                backend.remove(key);
                continue;
            }
            Matcher window = WINDOW_TYPE_KEY.matcher(key);
            if (window.matches() && isAtLeast(window.group(1), windowCount)) {
                backend.remove(key);
            }
        }
    }

    /** Пишет читаемый путь CashMemory, если хранилище создано с ним (длинный путь обрезается до лимита значения). */
    private void putCashMemoryPath() {
        if (cashMemoryPath != null) {
            backend.put(KEY_CASHMEMORY_PATH, cashMemoryPath.length() > 4000 ? cashMemoryPath.substring(0, 4000) : cashMemoryPath);
        }
    }

    private static boolean isAtLeast(String digits, int bound) {
        // Очень длинный номер заведомо больше границы; Integer.parseInt на нём упал бы.
        return digits.length() > 9 || Integer.parseInt(digits) >= bound;
    }

    private int parseCount(String key) throws SessionStoreException {
        String value = backend.get(key);
        if (value == null) {
            throw corrupted(Texts.get("session.registry.corrupt.noKey", key));
        }
        try {
            int number = Integer.parseInt(value.strip());
            if (number < 0) {
                throw new NumberFormatException(value);
            }
            return number;
        } catch (NumberFormatException e) {
            throw corrupted(Texts.get("session.registry.corrupt.badValue", key, value));
        }
    }

    private void requireAvailable() throws SessionStoreException {
        if (!backend.isAvailable()) {
            throw new SessionStoreException(Texts.get("session.registry.unavailable", backend.unavailableReason()));
        }
    }

    /**
     * Проверяет, что значение действительно записано. Под ограниченной учётной записью
     * {@code Preferences.put} может молча ничего не записать, и без этой проверки программа
     * считала бы, что снимок в реестре есть.
     */
    private void verifyWritten(String key, String expected) throws SessionStoreException {
        if (!expected.equals(backend.get(key))) {
            throw new SessionStoreException(Texts.get("session.registry.notPersisted", "HKCU\\Software\\JavaSoft\\Prefs"));
        }
    }

    /**
     * Ошибка «снимок в реестре повреждён».
     *
     * @param detail что именно повреждено, на языке интерфейса
     * @return исключение с готовым сообщением
     */
    private static SessionStoreException corrupted(String detail) {
        return new SessionStoreException(Texts.get("session.registry.corrupted", detail));
    }
}
