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

    @Override
    public void flush() throws SessionStoreException {
        if (!isAvailable()) {
            throw new SessionStoreException(Texts.get("session.registry.unavailable", failure));
        }
        try {
            node.flush();
        } catch (BackingStoreException | IllegalStateException | SecurityException e) {
            markUnavailable(e);
            throw new SessionStoreException(Texts.get("session.registry.unavailable", failure), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return failure == null;
    }

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
            throw new SessionStoreException(Texts.get("session.registry.unavailable", failure));
        }
        try {
            Preferences parent = node.parent();
            node.removeNode();
            if (parent != null) {
                parent.flush();
            }
        } catch (BackingStoreException | IllegalStateException | SecurityException e) {
            markUnavailable(e);
            throw new SessionStoreException(Texts.get("session.registry.unavailable", failure), e);
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
