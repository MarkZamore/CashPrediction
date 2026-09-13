package ru.cashprediction.core.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Хранилище-шпион для тестов рекордера, детектора сбоя и координатора восстановления.
 *
 * <p>Методы намеренно НЕ синхронизированы: так тест «saveNow ждёт фоновую запись» проверяет именно
 * блокировку рекордера, а не монитор хранилища.</p>
 */
final class FakeStore implements SessionStore {

    final String id;
    /** Журнал операций: {@code markDirty}, {@code save}, {@code markClean}, {@code clear}. */
    final List<String> events;
    final List<SessionSnapshot> saved = new CopyOnWriteArrayList<>();
    volatile SessionMarker marker;
    volatile SessionSnapshot snapshot;
    volatile boolean available = true;
    volatile String failSave;
    volatile String failLoad;
    /** Если задан, первая запись блокируется до его освобождения. */
    volatile CountDownLatch blockSave;
    final CountDownLatch saveEntered = new CountDownLatch(1);

    FakeStore(String id) {
        this(id, new CopyOnWriteArrayList<>());
    }

    FakeStore(String id, List<String> sharedEvents) {
        this.id = id;
        this.events = sharedEvents;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String title() {
        return "Хранилище " + id;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String unavailableReason() {
        return available ? "" : "выключено в тесте";
    }

    @Override
    public void markDirty(SessionMarker newMarker) {
        events.add(id + ":markDirty");
        marker = newMarker;
    }

    @Override
    public void markClean() {
        events.add(id + ":markClean");
        if (marker != null) {
            marker = marker.closed();
        }
    }

    @Override
    public Optional<SessionMarker> readMarker() {
        return Optional.ofNullable(marker);
    }

    @Override
    public void save(SessionSnapshot newSnapshot) throws SessionStoreException {
        events.add(id + ":save");
        CountDownLatch block = blockSave;
        if (block != null) {
            blockSave = null;
            saveEntered.countDown();
            try {
                if (!block.await(10, TimeUnit.SECONDS)) {
                    throw new SessionStoreException("тест не освободил запись");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failSave != null) {
            throw new SessionStoreException(failSave);
        }
        saved.add(newSnapshot);
        snapshot = newSnapshot;
    }

    @Override
    public Optional<SessionSnapshot> load() throws SessionStoreException {
        if (failLoad != null) {
            throw new SessionStoreException(failLoad);
        }
        return Optional.ofNullable(snapshot);
    }

    @Override
    public Optional<Instant> lastSavedAt() {
        SessionSnapshot current = snapshot;
        return current == null ? Optional.empty() : Optional.of(current.savedAt());
    }

    @Override
    public void clear() {
        events.add(id + ":clear");
        marker = null;
        snapshot = null;
    }

    /** @return число операций записи снимка в журнале (включая неудачные) */
    long saveAttempts() {
        return events.stream().filter(e -> e.equals(id + ":save")).count();
    }
}
