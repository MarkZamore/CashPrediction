package ru.cashprediction.fx.ui;

import java.nio.file.Path;
import java.util.Objects;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.update.lifecycle.UpdateLifecycle;
import ru.cashprediction.core.update.lifecycle.UpdateSessionLifecycle;

/**
 * Тихая привязка событий клиента к единственному lifecycle ядра.
 * Не проверяет версии, не выбирает аргументы перезапуска и не выполняет установку.
 * Отказ предстартового барьера запрещает создание интерфейса; повторные события подавляются.
 */
public final class FxUpdateSession implements AutoCloseable {
    /** Проверяемое действие без пользовательского интерфейса. */
    @FunctionalInterface interface Action {
        /** Выполняет единственный вызов общего lifecycle. */
        void run() throws Exception;
    }
    /** Проверяемый предстартовый барьер. */
    @FunctionalInterface interface Before {
        /** Возвращает решение ядра о допустимости запуска UI. */
        boolean run() throws Exception;
    }
    /** Фабрика позволяет проверить порядок вызовов без сети, файлов и живого клиента. */
    @FunctionalInterface interface Factory {
        /** Создаёт набор вызовов lifecycle при первом предстартовом событии. */
        Calls create() throws Exception;
    }
    /** Шов проверяет входы фабрики, не меняя API или решения lifecycle ядра. */
    @FunctionalInterface interface LifecycleFactory {
        /** Создаёт вызовы с окружением ядра и неизменёнными исходными аргументами. */
        Calls create(Path root, Path memory, String client, String[] args) throws Exception;
    }
    /** Только вызовы замороженного API; все решения принадлежат ядру. */
    record Calls(Before before, Action ready, Action close) {
        Calls { Objects.requireNonNull(before); Objects.requireNonNull(ready); Objects.requireNonNull(close); }
    }

    private final Factory factory;
    private Calls calls;
    private Boolean allowed;
    private boolean ready;
    private boolean closed;

    FxUpdateSession(Factory factory) { this.factory = Objects.requireNonNull(factory); }

    /** Сохраняет окружение ядра и копию исходных аргументов до первого предстартового вызова. */
    public static FxUpdateSession open(AppEnvironment environment, String[] args) {
        return open(environment, args, (root, memory, client, original) -> {
            UpdateSessionLifecycle lifecycle = UpdateLifecycle.create(root, memory, client, original);
            return new Calls(lifecycle::beforeUi, lifecycle::afterUiReady, lifecycle::close);
        });
    }

    /** Подменяет только вызовы обновлятора для теста входов и порядка запуска. */
    static FxUpdateSession open(AppEnvironment environment, String[] args, LifecycleFactory factory) {
        Objects.requireNonNull(environment);
        Objects.requireNonNull(factory);
        String[] original = Objects.requireNonNull(args).clone();
        return new FxUpdateSession(() -> factory.create(
                environment.appHome(), environment.cashMemory(), "fx", original.clone()));
    }

    /**
     * Проходит барьер ровно один раз до создания UI.
     * При неопределённом результате исключения безопасное открытие UI не доказано.
     */
    public synchronized boolean beforeUi() {
        if (closed) return false;
        if (allowed != null) return allowed;
        try {
            calls = Objects.requireNonNull(factory.create());
            allowed = calls.before().run();
        } catch (Exception | LinkageError failure) {
            restoreInterrupt(failure);
            allowed = false;
        }
        if (!allowed) close();
        return allowed;
    }

    /** Передаёт фактическую готовность ровно один раз; исключения не попадают в обработчик UI. */
    public synchronized void afterUiReady() {
        if (closed || ready || !Boolean.TRUE.equals(allowed)) return;
        ready = true;
        try { calls.ready().run(); }
        catch (Exception | LinkageError failure) { restoreInterrupt(failure); }
    }

    /** Закрывает lifecycle один раз перед exit/halt и при внешнем завершении JVM. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (calls == null) return;
        try { calls.close().run(); }
        catch (Exception | LinkageError failure) { restoreInterrupt(failure); }
    }

    private static void restoreInterrupt(Throwable failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
    }
}
