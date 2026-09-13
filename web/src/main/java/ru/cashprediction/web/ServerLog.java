package ru.cashprediction.web;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Журнал web-сервера в памяти: последние строки для окна статуса сервера.
 *
 * <p>Файловый журнал сознательно не ведётся (раздел 5.7 плана: «ничего, кроме CashMemory»), поэтому
 * {@code java.util.logging.FileHandler} не используется. Строки хранятся в кольцевом буфере
 * ограниченного размера и рассылаются подписчикам (окну статуса). При желании строки дублируются
 * в стандартный вывод: в режиме разработки их видно в консоли Maven, а у exe без консоли вывод просто теряется.</p>
 *
 * <p>Класс потокобезопасен: запись идёт из потоков HTTP-сервера и рекордера сессии.</p>
 */
public final class ServerLog {

    /** Сколько последних строк хранится. */
    public static final int CAPACITY = 500;

    /** Формат времени в начале строки. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Deque<String> lines = new ArrayDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final boolean echoToConsole;

    /**
     * Создаёт журнал.
     *
     * @param echoToConsole дублировать ли строки в {@code System.out} (в тестах выключено, чтобы не шуметь)
     */
    public ServerLog(boolean echoToConsole) {
        this.echoToConsole = echoToConsole;
    }

    /**
     * Добавляет строку с отметкой времени.
     *
     * @param message сообщение на русском
     */
    public void info(String message) {
        add(LocalTime.now().format(TIME) + "  " + Objects.requireNonNullElse(message, ""));
    }

    /**
     * Добавляет строку об ошибке с отметкой времени.
     *
     * @param message сообщение на русском
     * @param error   исключение или {@code null}
     */
    public void error(String message, Throwable error) {
        String reason = error == null ? "" : ": " + Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
        add(LocalTime.now().format(TIME) + "  ОШИБКА  " + Objects.requireNonNullElse(message, "") + reason);
    }

    /**
     * Последние строки журнала.
     *
     * @param max сколько строк вернуть не больше
     * @return копия строк, самые старые первыми
     */
    public List<String> tail(int max) {
        synchronized (lines) {
            List<String> all = new ArrayList<>(lines);
            return List.copyOf(all.subList(Math.max(0, all.size() - max), all.size()));
        }
    }

    /**
     * Подписывает слушателя на новые строки.
     *
     * @param listener слушатель; вызывается в потоке, записавшем строку, поэтому Swing-подписчик
     *                 обязан перейти в EDT через {@code invokeLater}
     */
    public void addListener(Consumer<String> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Отписывает слушателя.
     *
     * @param listener слушатель
     */
    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    private void add(String line) {
        synchronized (lines) {
            lines.addLast(line);
            while (lines.size() > CAPACITY) {
                lines.removeFirst();
            }
        }
        if (echoToConsole) {
            System.out.println(line);
        }
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(line);
            } catch (RuntimeException e) {
                // Сломанный подписчик (например, закрытое окно) не должен мешать работе сервера.
            }
        }
    }
}
