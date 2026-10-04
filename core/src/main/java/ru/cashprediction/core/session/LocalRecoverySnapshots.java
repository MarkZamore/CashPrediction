package ru.cashprediction.core.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Локальный адаптер чтения снимков; сохраняет владельцев данных внутри SessionStore. */
public final class LocalRecoverySnapshots implements RecoverySnapshots {
    private final Map<String, SessionStore> stores;

    /** Подключает уникальные хранилища без чтения и записи. @param stores хранилища одной копии клиента */
    public LocalRecoverySnapshots(List<SessionStore> stores) {
        Map<String, SessionStore> indexed = new LinkedHashMap<>();
        for (SessionStore store : List.copyOf(stores)) {
            if (indexed.putIfAbsent(store.id(), store) != null) throw new IllegalArgumentException("DUPLICATE_RECOVERY_STORE");
        }
        this.stores = Map.copyOf(indexed);
    }

    /** {@inheritDoc} */
    @Override public Result read(String storeId) {
        Objects.requireNonNull(storeId, "storeId");
        SessionStore store = stores.get(storeId);
        if (store == null) return failed(SessionStoreException.Code.UNKNOWN_STORE, storeId);
        try {
            if (!store.isAvailable()) return failed(SessionStoreException.Code.UNAVAILABLE, store.unavailableReason());
            return new Result(store.load(), Optional.empty());
        } catch (SessionStoreException failure) {
            return failed(failure.code(), reason(failure));
        } catch (RuntimeException failure) {
            return failed(SessionStoreException.Code.UNSPECIFIED, reason(failure));
        }
    }

    /** Создаёт отказ без Throwable и без потери категории. */
    private static Result failed(SessionStoreException.Code code, String detail) {
        return new Result(Optional.empty(), Optional.of(new Problem(code, detail)));
    }

    /** Сохраняет исходную диагностику даже при пустом сообщении. */
    private static String reason(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
