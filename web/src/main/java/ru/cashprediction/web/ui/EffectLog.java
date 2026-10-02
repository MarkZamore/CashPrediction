package ru.cashprediction.web.ui;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.json.WebEffect;

/** Ограниченный журнал неизменяемых JSON-эффектов с независимым ожиданием вкладок. */
public final class EffectLog {
    private final int capacity;
    private final ArrayDeque<Map<String, Object>> entries = new ArrayDeque<>();
    private long seq;
    private boolean closed;

    /** Создаёт журнал рабочего размера. */
    public EffectLog() { this(1000); }
    /** Создаёт журнал заданного размера для проверки переполнения. */
    public EffectLog(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    /** Записывает эффект целиком до уведомления читателей. */
    public synchronized long append(WebEffect effect) {
        Map<String, Object> value = UiJson.effect(seq + 1, effect);
        seq++;
        entries.addLast(value);
        if (entries.size() > capacity) entries.removeFirst();
        notifyAll();
        return seq;
    }
    /** Возвращает последний номер. */
    public synchronized long sequence() { return seq; }
    /** Возвращает эффекты после курсора либо требование повторного bootstrap. */
    public synchronized Map<String, Object> after(long after) {
        if (after < 0 || after > seq) throw new IllegalArgumentException("after");
        if (after < seq - entries.size()) return Map.of("resync", true);
        List<Map<String, Object>> values = entries.stream().filter(value -> ((Number) value.get("seq")).longValue() > after).toList();
        return Map.of("seq", seq, "effects", values);
    }
    /** Ждёт изменение курсора без удержания потока контроллера. */
    public synchronized Map<String, Object> await(long after, Duration timeout) throws InterruptedException {
        after(after);
        long end = System.nanoTime() + timeout.toNanos();
        while (!closed && seq == after) {
            long left = end - System.nanoTime();
            if (left <= 0) break;
            java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return after(after);
    }
    /** Прерывает ожидание читателей при закрытии сервера. */
    public synchronized void close() { closed = true; notifyAll(); }
}
