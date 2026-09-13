package ru.cashprediction.core.session.store;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Хранилище снимка сессии в реестре Windows (раздел 5.3 плана).
 *
 * <p>Раскладка узла {@code HKCU\Software\JavaSoft\Prefs\ru\cashprediction\session\<клиент>}:</p>
 * <pre>
 * schema=1  state=running|closed  pid=12345  started.at=…  client=fx      маркер сеанса
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

    /** Префикс узлов реестра; к нему добавляется идентификатор клиента. */
    public static final String NODE_PREFIX = "ru/cashprediction/session/";

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

    /** Ошибка последней «тихой» операции; {@code null} — ошибок нет. */
    private volatile String lastError;

    /**
     * Создаёт хранилище поверх бэкенда.
     *
     * @param backend узел реестра
     * @param client  клиент, которому принадлежит узел
     */
    public RegistrySessionStore(RegistryBackend backend, String client) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.client = SnapshotSchema.requireClient(client);
    }

    /**
     * Создаёт хранилище в настоящем реестре для клиента: узел {@code ru/cashprediction/session/<клиент>}.
     *
     * @param client {@code fx}, {@code swing} или {@code web}
     * @return хранилище (возможно, недоступное — см. {@link #isAvailable()})
     */
    public static RegistrySessionStore forClient(String client) {
        return new RegistrySessionStore(new PreferencesRegistryBackend(NODE_PREFIX + SnapshotSchema.requireClient(client)),
                client);
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
        return "Реестр Windows";
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
            lastError = "Реестр Windows недоступен: " + backend.unavailableReason();
            return;
        }
        try {
            // Только ключи маркера: куски снимка не трогаем, пользователь ещё может восстановиться из них.
            backend.put(KEY_SCHEMA, String.valueOf(SnapshotSchema.CURRENT));
            backend.put(KEY_STATE, marker.state());
            backend.put(KEY_PID, String.valueOf(marker.pid()));
            backend.put(KEY_STARTED_AT, marker.startedAt().toString());
            backend.put(KEY_CLIENT, marker.client());
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
            lastError = "Реестр Windows недоступен: " + backend.unavailableReason();
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
            throw new SessionStoreException("Не удалось записать снимок в реестр Windows: " + e.getMessage(), e);
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
            throw corrupted("нет ключа " + KEY_SNAPSHOT_CRC);
        }
        int expectedCount = (length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (count != expectedCount) {
            throw corrupted("число кусков " + count + " не соответствует длине " + length);
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < count; i++) {
            String chunk = backend.get("snapshot." + i);
            if (chunk == null) {
                throw corrupted("нет куска snapshot." + i);
            }
            sb.append(chunk);
        }
        String json = sb.toString();
        if (json.length() != length) {
            throw corrupted("длина " + json.length() + " вместо " + length);
        }
        if (!crc32(json).equalsIgnoreCase(crc.strip())) {
            throw corrupted("контрольная сумма не совпадает");
        }
        try {
            return Optional.of(codec.decode(json));
        } catch (SnapshotFormatException e) {
            throw new SessionStoreException("Снимок в реестре повреждён: " + e.getMessage(), e);
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
            lastError = "Реестр Windows недоступен: " + backend.unavailableReason();
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

    private static boolean isAtLeast(String digits, int bound) {
        // Очень длинный номер заведомо больше границы; Integer.parseInt на нём упал бы.
        return digits.length() > 9 || Integer.parseInt(digits) >= bound;
    }

    private int parseCount(String key) throws SessionStoreException {
        String value = backend.get(key);
        if (value == null) {
            throw corrupted("нет ключа " + key);
        }
        try {
            int number = Integer.parseInt(value.strip());
            if (number < 0) {
                throw new NumberFormatException(value);
            }
            return number;
        } catch (NumberFormatException e) {
            throw corrupted("некорректное значение " + key + "=" + value);
        }
    }

    private void requireAvailable() throws SessionStoreException {
        if (!backend.isAvailable()) {
            throw new SessionStoreException("Реестр Windows недоступен: " + backend.unavailableReason());
        }
    }

    /**
     * Проверяет, что значение действительно записано. Под ограниченной учётной записью
     * {@code Preferences.put} может молча ничего не записать, и без этой проверки программа
     * считала бы, что снимок в реестре есть.
     */
    private void verifyWritten(String key, String expected) throws SessionStoreException {
        if (!expected.equals(backend.get(key))) {
            throw new SessionStoreException("Запись в реестр Windows не сохраняется (нет прав на раздел "
                    + "HKCU\\Software\\JavaSoft\\Prefs?)");
        }
    }

    private static SessionStoreException corrupted(String detail) {
        return new SessionStoreException("Снимок в реестре повреждён: " + detail);
    }
}
