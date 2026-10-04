package ru.cashprediction.web.ui;

import java.util.concurrent.TimeUnit;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.ui.selftest.SelfTestScript;

/** Владеет единственным контроллером приложения и его потоком для HTTP-клиента. */
public final class CoreWebRuntime implements AutoCloseable {
    private final ControllerThread thread = new ControllerThread();
    private final EffectLog effects = new EffectLog();
    private final WebUiPort port;
    private final AppController controller;
    private final WebSelfTestBridge tests;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    /** Готовит окружение; запуск выполняется отдельно после регистрации маршрутов. */
    public CoreWebRuntime(AppEnvironment environment) {
        this(environment, () -> { });
    }

    /** Передаёт фактическую готовность главной модели владельцу серверного lifecycle. */
    public CoreWebRuntime(AppEnvironment environment, Runnable mainReady) {
        var options = environment.options();
        if (options.isSelftest() && !options.testApi()) throw new IllegalArgumentException("--selftest requires --test-api");
        port = new WebUiPort(thread, effects, options.testApi());
        port.onMainReady(mainReady);
        controller = new AppController(port, environment);
        tests = options.testApi() ? new WebSelfTestBridge(effects,
                options.isSelftest() ? SelfTestScript.load(options.selftest()) : null,
                options.selftestOut() == null ? null : options.selftestOut().toAbsolutePath().normalize(), environment.clock()::today) : null;
    }
    /** Запускает ядро на единственном потоке до приёма первых браузерных действий. */
    public void start() throws Exception {
        thread.submit(() -> {
            port.bind(controller); thread.onError(error -> controller.uncaught(Thread.currentThread(), error));
            controller.start(); return null;
        }).get(30, TimeUnit.SECONDS);
    }
    /** Возвращает поток для обработчиков HTTP. */
    public ControllerThread thread() { return thread; }
    /** Возвращает журнал для длинных запросов. */
    public EffectLog effects() { return effects; }
    /** Возвращает порт моделей и живых окон. */
    public WebUiPort port() { return port; }
    /** Возвращает общий контроллер, доступный только на его потоке. */
    public AppController controller() { return controller; }
    /** Возвращает тестовый мост либо null. */
    public WebSelfTestBridge tests() { return tests; }
    /**
     * Захватывает свежий снимок на живом UI; при ошибке очереди сохраняет последний захваченный.
     * Пять секунд ограничивают ожидание UI, но не время дискового IO запасной записи.
     * Ни один путь не помечает сеанс чистым.
     */
    public void saveSnapshot() {
        if (closed.get() || port.exitKind() != null) return;
        java.util.concurrent.CompletableFuture<?> capture = null;
        try {
            capture = thread.submit(() -> {
                if (!closed.get() && port.exitKind() == null && controller.recorder() != null)
                    controller.recorder().saveNow();
                return null;
            });
            capture.get(5, TimeUnit.SECONDS);
        } catch (Exception error) {
            // CompletableFuture отменяет ожидание, не уже поставленную задачу executor.
            // Поздняя задача повторно проверяет выход; рекордер защищает порядок снимков.
            if (capture != null) capture.cancel(false);
            error.printStackTrace();
            try {
                if (!closed.get() && port.exitKind() == null) controller.saveShutdownSnapshot();
            } finally {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }
    /** Освобождает таймеры, рекордер и ожидания HTTP. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            thread.submit(() -> {
                controller.autosave().stop();
                var kind = port.exitKind();
                if (kind != ru.cashprediction.core.app.ExitKind.HALT && kind != ru.cashprediction.core.app.ExitKind.WEB_CRASHED
                        && controller.recorder() != null && !controller.recorder().isClosed()) controller.recorder().shutdownClean();
                return null;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception error) { error.printStackTrace(); }
        finally { if (tests != null) tests.close(); effects.close(); thread.close(); }
    }
}
