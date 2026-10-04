package ru.cashprediction.core.session.store;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.text.Texts;

/**
 * {@link RegistryBackend} поверх {@code java.util.prefs}: на Windows узел
 * {@code Preferences.userRoot().node("ru/cashprediction/session/fx")} — это раздел
 * {@code HKCU\Software\JavaSoft\Prefs\ru\cashprediction\session\fx}.
 *
 * <p>Используется только {@link Preferences#userRoot()}: {@code systemRoot()} указывает в HKLM,
 * куда обычный пользователь писать не может, и JDK печатает предупреждение уже при первом обращении.</p>
 *
 * <p><b>Недоступность до перезапуска.</b> Любая {@link BackingStoreException},
 * {@link SecurityException} или {@link IllegalStateException} (узел удалён извне) переводит бэкенд
 * в состояние «недоступен», и больше он реестр не трогает. Причина в устройстве JDK: класс
 * {@code WindowsPreferences} при неудачном открытии раздела сбрасывает внутренний флаг
 * {@code isBackingStoreAvailable} и никогда не восстанавливает его, так что повторные попытки
 * всё равно бы проваливались, засоряя журнал предупреждениями.</p>
 *
 * <p>Особенности JDK 25, учтённые в {@link RegistrySessionStore}: {@code put} пишет в реестр сразу,
 * {@code flush()} вызывает {@code RegFlushKey}; заглавные буквы в имени ключа кодируются как
 * {@code /A}, а не-ASCII символы значения — как {@code /uXXXX}, поэтому ключи только строчные
 * латинские.</p>
 *
 * <p>Класс потокобезопасен: {@link Preferences} синхронизирован сам, флаг недоступности — volatile.</p>
 */
public final class PreferencesRegistryBackend implements RegistryBackend {

    /** Путь узла относительно пользовательского корня. */
    private final String nodePath;

    /** Узел; {@code null}, если открыть его не удалось. */
    private final Preferences node;

    /** Причина недоступности; {@code null} — бэкенд работает. */
    private volatile String failure;

    /**
     * Открывает (при необходимости создаёт) узел пользовательского реестра.
     *
     * @param nodePath путь узла, например {@code ru/cashprediction/session/fx}
     */
    public PreferencesRegistryBackend(String nodePath) {
        this.nodePath = Objects.requireNonNull(nodePath, "nodePath");
        Preferences opened = null;
        try {
            opened = Preferences.userRoot().node(nodePath);
        } catch (RuntimeException e) {
            // SecurityException (политика), IllegalArgumentException (плохой путь) и прочее: реестр не используем.
            failure = describe(e);
        }
        this.node = opened;
    }

    /**
     * Путь узла.
     *
     * @return путь относительно пользовательского корня
     */
    public String nodePath() {
        return nodePath;
    }

    /**
     * Читает значение узла; при удалённом узле или запрете доступа запоминает недоступность.
     * @param key ключ значения
     * @return значение или {@code null} при отсутствии ключа либо недоступности бэкенда
     */
    @Override
    public String get(String key) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return node.get(key, null);
        } catch (IllegalStateException | SecurityException e) {
            markUnavailable(e);
            return null;
        }
    }

    /**
     * Передаёт запись значения в {@link Preferences}; недоступный бэкенд пропускает запись.
     * Удалённый узел и запрет доступа переводят бэкенд в недоступное состояние без исключения.
     * Метод не подтверждает сохранение обратным чтением и не объединяет записи в транзакцию.
     * @param key ключ значения
     * @param value записываемое значение
     * @throws IllegalArgumentException если доступный узел отвергает длину ключа или значения
     */
    @Override
    public void put(String key, String value) {
        if (!isAvailable()) {
            return;
        }
        try {
            node.put(key, value);
        } catch (IllegalStateException | SecurityException e) {
            markUnavailable(e);
        }
    }

    /**
     * Удаляет ключ, если бэкенд доступен; отсутствие ключа допустимо.
     * Удалённый узел или запрет доступа запоминаются как недоступность без исключения;
     * подтверждения удаления и отката нет.
     * @param key удаляемый ключ
     */
    @Override
    public void remove(String key) {
        if (!isAvailable()) {
            return;
        }
        try {
            node.remove(key);
        } catch (IllegalStateException | SecurityException e) {
            markUnavailable(e);
        }
    }

    /**
     * Получает список ключей узла; ошибку хранилища, удалённый узел или запрет доступа
     * запоминает как недоступность бэкенда.
     * @return ключи без гарантии порядка или пустой список при пустом либо недоступном узле
     */
    @Override
    public List<String> keys() {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            return Arrays.asList(node.keys());
        } catch (BackingStoreException | IllegalStateException | SecurityException e) {
            markUnavailable(e);
            return List.of();
        }
    }

    /**
     * Вызывает сброс узла через {@link Preferences#flush()}, не создавая транзакции
     * и не откатывая предшествующие записи при отказе. Ошибка сброса запоминается
     * как недоступность; последующие обращения этого экземпляра к узлу прекращаются.
     * @throws SessionStoreException если бэкенд уже недоступен или сброс не удался
     */
    @Override
    public void flush() throws SessionStoreException {
        if (!isAvailable()) {
            throw new SessionStoreException(SessionStoreException.Code.UNAVAILABLE, Texts.get("session.registry.unavailable", failure));
        }
        try {
            node.flush();
        } catch (BackingStoreException | IllegalStateException | SecurityException e) {
            markUnavailable(e);
            throw new SessionStoreException(SessionStoreException.Code.UNAVAILABLE, Texts.get("session.registry.unavailable", failure), e);
        }
    }

    /**
     * Проверяет отсутствие запомненной ошибки, не обращаясь к реестру.
     * После ошибки этот экземпляр не восстанавливает доступность автоматически.
     * @return {@code true}, если создание и предыдущие операции не зафиксировали отказ
     */
    @Override
    public boolean isAvailable() {
        return failure == null;
    }

    /**
     * Возвращает запомненное описание отказа из общей локализации.
     * @return причина недоступности или пустая строка, если ошибка не зафиксирована
     */
    @Override
    public String unavailableReason() {
        String reason = failure;
        return reason == null ? "" : reason;
    }

    /**
     * Удаляет узел целиком (используется интеграционным тестом и при полной очистке).
     *
     * @throws SessionStoreException если реестр недоступен
     */
    public void removeNode() throws SessionStoreException {
        if (!isAvailable()) {
            throw new SessionStoreException(SessionStoreException.Code.UNAVAILABLE, Texts.get("session.registry.unavailable", failure));
        }
        try {
            Preferences parent = node.parent();
            node.removeNode();
            if (parent != null) {
                parent.flush();
            }
        } catch (BackingStoreException | IllegalStateException | SecurityException e) {
            markUnavailable(e);
            throw new SessionStoreException(SessionStoreException.Code.UNAVAILABLE, Texts.get("session.registry.unavailable", failure), e);
        }
    }

    private void markUnavailable(Exception e) {
        failure = describe(e);
    }

    private static String describe(Exception e) {
        return switch (e) {
            case SecurityException _ -> Texts.get("session.registry.reason.security");
            case BackingStoreException be -> Texts.get("session.registry.reason.backingStore", be.getMessage());
            case IllegalStateException _ -> Texts.get("session.registry.reason.removed");
            default -> Texts.get("session.registry.reason.cannotOpen", e.getMessage());
        };
    }
}
