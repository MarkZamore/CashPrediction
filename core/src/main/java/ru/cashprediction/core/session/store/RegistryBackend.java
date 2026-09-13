package ru.cashprediction.core.session.store;

import java.util.List;
import ru.cashprediction.core.session.SessionStoreException;

/**
 * Плоское хранилище «ключ → строка» одного узла реестра.
 *
 * <p>Интерфейс отделяет логику {@link RegistrySessionStore} (куски, CRC, маркер фиксации) от способа
 * доступа к реестру. Боевая реализация — {@link PreferencesRegistryBackend} поверх
 * {@code java.util.prefs}; для тестов — {@link InMemoryRegistryBackend}. Вариант через {@code reg.exe}
 * не реализуется, но интерфейс оставляет для него место.</p>
 *
 * <p>Контракт ограничений повторяет {@code java.util.prefs.Preferences}: длина ключа не больше 80
 * символов, длина значения не больше 8192 символов; нарушение — {@link IllegalArgumentException}.</p>
 *
 * <p>Реализации обязаны быть потокобезопасными.</p>
 */
public interface RegistryBackend {

    /**
     * Читает значение.
     *
     * @param key ключ
     * @return значение или {@code null}, если ключа нет (или бэкенд недоступен)
     */
    String get(String key);

    /**
     * Записывает значение.
     *
     * @param key   ключ (строчные латинские буквы, цифры, точка)
     * @param value значение
     */
    void put(String key, String value);

    /**
     * Удаляет ключ; отсутствие ключа ошибкой не считается.
     *
     * @param key ключ
     */
    void remove(String key);

    /**
     * Перечисляет ключи узла.
     *
     * @return ключи (пустой список, если узел пуст или бэкенд недоступен)
     */
    List<String> keys();

    /**
     * Сбрасывает изменения на диск.
     *
     * @throws SessionStoreException если хранилище реестра недоступно
     */
    void flush() throws SessionStoreException;

    /**
     * Доступен ли бэкенд.
     *
     * @return {@code false} после любой ошибки хранилища (до перезапуска программы)
     */
    boolean isAvailable();

    /**
     * Причина недоступности.
     *
     * @return сообщение на русском или пустая строка
     */
    String unavailableReason();
}
