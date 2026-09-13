package ru.cashprediction.core.session.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.text.Texts;

/**
 * {@link RegistryBackend} в памяти — для модульных тестов и для запуска без реестра.
 *
 * <p>Повторяет ограничения {@code Preferences} (ключ ≤ 80, значение ≤ 8192 символов), чтобы тесты
 * ловили те же ошибки, что и настоящий реестр. Умеет имитировать отказ хранилища
 * ({@link #failWith(String)}), как это бывает под ограниченной учётной записью.</p>
 *
 * <p>Класс потокобезопасен: все методы синхронизированы на экземпляре.</p>
 */
public final class InMemoryRegistryBackend implements RegistryBackend {

    /** Максимальная длина ключа, как {@code Preferences.MAX_KEY_LENGTH}. */
    public static final int MAX_KEY_LENGTH = 80;

    /** Максимальная длина значения, как {@code Preferences.MAX_VALUE_LENGTH}. */
    public static final int MAX_VALUE_LENGTH = 8192;

    /** Значения в порядке первой записи. */
    private final Map<String, String> values = new LinkedHashMap<>();

    /** Причина имитируемого отказа; {@code null} — бэкенд работает. */
    private String failure;

    /** Число вызовов {@link #flush()}. */
    private int flushCount;

    /** Создаёт пустой узел. */
    public InMemoryRegistryBackend() {
    }

    @Override
    public synchronized String get(String key) {
        return failure != null ? null : values.get(key);
    }

    @Override
    public synchronized void put(String key, String value) {
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Key too long: " + key);
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Value too long: " + key);
        }
        // Как и Preferences под запретом записи: put молча ничего не делает, ошибку покажет flush.
        if (failure == null) {
            values.put(key, value);
        }
    }

    @Override
    public synchronized void remove(String key) {
        if (failure == null) {
            values.remove(key);
        }
    }

    @Override
    public synchronized List<String> keys() {
        return failure != null ? List.of() : new ArrayList<>(values.keySet());
    }

    @Override
    public synchronized void flush() throws SessionStoreException {
        flushCount++;
        if (failure != null) {
            throw new SessionStoreException(Texts.get("session.registry.unavailable", failure));
        }
    }

    @Override
    public synchronized boolean isAvailable() {
        return failure == null;
    }

    @Override
    public synchronized String unavailableReason() {
        return failure == null ? "" : failure;
    }

    /**
     * Имитирует отказ хранилища: чтение возвращает пусто, запись не выполняется, {@code flush} бросает.
     *
     * @param reason причина на русском; {@code null} возвращает бэкенд в рабочее состояние
     */
    public synchronized void failWith(String reason) {
        failure = reason;
    }

    /**
     * Копия содержимого для проверок в тестах.
     *
     * @return ключи и значения в порядке первой записи
     */
    public synchronized Map<String, String> contents() {
        return new LinkedHashMap<>(values);
    }

    /**
     * Сколько раз вызывался {@link #flush()}.
     *
     * @return число вызовов
     */
    public synchronized int flushCount() {
        return flushCount;
    }
}
