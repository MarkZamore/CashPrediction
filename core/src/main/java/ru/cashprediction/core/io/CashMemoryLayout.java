package ru.cashprediction.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Раскладка папки {@code CashMemory}: какие файлы где лежат.
 *
 * <pre>
 * CashMemory/
 *   &lt;имя плана&gt;.md          планы (в корне папки)
 *   settings.md               настройки, общие для трёх клиентов
 *   session-fx.xml            XML-снимок сессии JavaFX-клиента
 *   session-swing.xml         XML-снимок сессии Swing-клиента
 *   web-session.md            снимок сессии web-сервера
 *   web-session.plan.md       несохранённый план web-сессии
 *   *.&lt;pid&gt;.&lt;nano&gt;.tmp       временные файлы атомарной записи (чистятся при старте)
 * </pre>
 *
 * <p>Имена служебных файлов зарезервированы: план с таким именем перепутался бы с ними
 * (см. {@link #isReservedPlanName(String)}).</p>
 *
 * <p>Объект неизменяем и потокобезопасен; обращения к диску выполняет только {@link #probeWritable()}
 * и {@link #openDefault()}.</p>
 */
public final class CashMemoryLayout {

    /** Файл настроек. */
    public static final String SETTINGS = "settings.md";

    /** Начало имени XML-снимка сессии: {@code session-<клиент>.xml}. */
    public static final String SESSION_XML_PREFIX = "session-";

    /** Расширение XML-снимка сессии. */
    public static final String SESSION_XML_SUFFIX = ".xml";

    /** Снимок сессии web-сервера. */
    public static final String WEB_SESSION = "web-session.md";

    /** Несохранённый план web-сессии (в штатном формате плана). */
    public static final String WEB_SESSION_PLAN = "web-session.plan.md";

    /** Имена (без {@code .md}), которые нельзя использовать как имена планов. */
    public static final List<String> RESERVED_PLAN_NAMES =
            List.of("settings", "web-session", "web-session.plan", "session-fx", "session-swing");

    /** Идентификатор клиента: строчные латинские буквы и цифры ({@code fx}, {@code swing}, {@code web}). */
    private static final Pattern CLIENT_ID = Pattern.compile("[a-z][a-z0-9]*");

    private final Path dir;

    /**
     * Создаёт раскладку для указанной папки (папка не создаётся).
     *
     * @param dir папка CashMemory
     */
    public CashMemoryLayout(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
    }

    /**
     * Открывает штатную папку CashMemory рядом с приложением: создаёт её при необходимости
     * и удаляет временные файлы, оставшиеся после аварийного завершения посреди записи.
     *
     * @return раскладка штатной папки
     * @throws IOException если папку создать не удалось
     */
    public static CashMemoryLayout openDefault() throws IOException {
        Path dir = AppPaths.ensureCashMemory();
        AtomicFiles.cleanupStaleTemporaryFiles(dir);
        return new CashMemoryLayout(dir);
    }

    /** @return папка CashMemory */
    public Path dir() {
        return dir;
    }

    /** @return путь к {@code settings.md} */
    public Path settingsFile() {
        return dir.resolve(SETTINGS);
    }

    /**
     * Путь к XML-снимку сессии клиента.
     *
     * @param client идентификатор клиента: {@code fx} или {@code swing}
     * @return путь {@code CashMemory/session-<client>.xml}
     * @throws IllegalArgumentException если идентификатор клиента некорректен
     */
    public Path sessionXml(String client) {
        return dir.resolve(sessionXmlFileName(client));
    }

    /**
     * Имя файла XML-снимка сессии клиента.
     *
     * @param client идентификатор клиента
     * @return {@code session-<client>.xml}
     * @throws IllegalArgumentException если идентификатор содержит что-то кроме строчных латинских букв и цифр
     */
    public static String sessionXmlFileName(String client) {
        // Проверка не даёт составить путь вида «session-../x.xml» за пределами CashMemory.
        if (client == null || !CLIENT_ID.matcher(client).matches()) {
            // Идентификатор клиента задаётся константой в коде клиента, а не пользователем: сообщение для разработчика.
            throw new IllegalArgumentException("Invalid client id: '" + client + "'");
        }
        return SESSION_XML_PREFIX + client + SESSION_XML_SUFFIX;
    }

    /** @return путь к {@code web-session.md} */
    public Path webSession() {
        return dir.resolve(WEB_SESSION);
    }

    /** @return путь к {@code web-session.plan.md} */
    public Path webSessionPlan() {
        return dir.resolve(WEB_SESSION_PLAN);
    }

    /** @return репозиторий планов этой папки */
    public PlanRepository plans() {
        return new PlanRepository(dir);
    }

    /**
     * Проверяет, занято ли имя служебным файлом CashMemory.
     *
     * @param nameWithoutExtension имя плана или файла без {@code .md}
     * @return {@code true} для settings, web-session, web-session.plan, session-fx, session-swing (регистр не важен)
     */
    public static boolean isReservedPlanName(String nameWithoutExtension) {
        if (nameWithoutExtension == null) {
            return false;
        }
        String name = nameWithoutExtension.strip().toLowerCase(Locale.ROOT);
        return RESERVED_PLAN_NAMES.contains(name);
    }

    /**
     * Проверяет, можно ли писать в папку: создаёт и сразу удаляет маленький временный файл.
     *
     * <p>Нужна для баннера «папка только для чтения» при старте. Имя пробного файла подходит под шаблон
     * временных файлов {@link AtomicFiles}, поэтому, если процесс упадёт между созданием и удалением,
     * файл уберёт очистка при следующем запуске.</p>
     *
     * @return {@code true}, если файл удалось создать и удалить
     */
    public boolean probeWritable() {
        Path probe = dir.resolve("write-probe." + ProcessHandle.current().pid() + "." + System.nanoTime() + ".tmp");
        try {
            Files.write(probe, new byte[] {'o', 'k'}, java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException | SecurityException ignored) {
                // Удалить не удалось: файл уберёт очистка временных файлов при следующем запуске.
            }
            return false;
        }
    }

    /** @return путь к папке */
    @Override
    public String toString() {
        return dir.toString();
    }
}
