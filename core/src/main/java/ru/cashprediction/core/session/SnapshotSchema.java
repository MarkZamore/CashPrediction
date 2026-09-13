package ru.cashprediction.core.session;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import ru.cashprediction.core.text.Texts;

/**
 * Версия схемы снимка сессии и допустимые идентификаторы клиентов.
 *
 * <p>Номер схемы пишется в каждый снимок (реестр, XML, Markdown). Если когда-нибудь формат
 * изменится несовместимо, новая версия программы увеличит {@link #CURRENT}, а старая, встретив
 * снимок с большим номером, откажется его разбирать с понятным сообщением вместо того,
 * чтобы восстановить окна наполовину.</p>
 *
 * <p>Класс-константы, потокобезопасен.</p>
 */
public final class SnapshotSchema {

    /** Текущая версия схемы снимка. */
    public static final int CURRENT = 1;

    /** Идентификатор JavaFX-клиента. */
    public static final String CLIENT_FX = "fx";

    /** Идентификатор Swing-клиента. */
    public static final String CLIENT_SWING = "swing";

    /** Идентификатор web-клиента (сервера). */
    public static final String CLIENT_WEB = "web";

    /** Все официальные клиенты в порядке их появления в меню и документации. */
    public static final List<String> CLIENTS = List.of(CLIENT_FX, CLIENT_SWING, CLIENT_WEB);

    /**
     * Допустимая форма идентификатора клиента: строчные латинские буквы и цифры.
     * Идентификатор становится частью пути узла реестра и имени файла, поэтому никаких
     * заглавных (в реестре они кодируются как {@code /A}), пробелов и разделителей.
     */
    private static final Pattern CLIENT_ID = Pattern.compile("[a-z0-9]{1,32}");

    private SnapshotSchema() {
    }

    /**
     * Проверяет идентификатор клиента.
     *
     * @param client идентификатор, обычно {@code fx}, {@code swing} или {@code web}
     * @return тот же идентификатор
     * @throws IllegalArgumentException если идентификатор пустой или содержит недопустимые символы
     */
    public static String requireClient(String client) {
        Objects.requireNonNull(client, "client");
        if (!CLIENT_ID.matcher(client).matches()) {
            // Идентификатор читается и из файлов снимков, поэтому сообщение — текст интерфейса, а не разработчика.
            throw new IllegalArgumentException(Texts.get("session.schema.error.client", client));
        }
        return client;
    }

    /**
     * Проверяет, что снимок этой версии схемы можно прочитать.
     *
     * @param schemaVersion версия из снимка
     * @return та же версия
     * @throws IllegalArgumentException если версия не положительна или новее {@link #CURRENT}
     */
    public static int requireSupported(int schemaVersion) {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException(Texts.get("session.codec.error.schemaVersion", schemaVersion));
        }
        if (schemaVersion > CURRENT) {
            throw new IllegalArgumentException(Texts.get("session.schema.newer", schemaVersion, CURRENT));
        }
        return schemaVersion;
    }

    /**
     * Название клиента для сообщений пользователю.
     *
     * @param client идентификатор клиента
     * @return «JavaFX», «Swing», «Web» или сам идентификатор для неизвестного клиента
     */
    public static String clientTitle(String client) {
        return switch (client) {
            case CLIENT_FX -> "JavaFX";
            case CLIENT_SWING -> "Swing";
            case CLIENT_WEB -> "Web";
            case null -> "";
            default -> client;
        };
    }
}
