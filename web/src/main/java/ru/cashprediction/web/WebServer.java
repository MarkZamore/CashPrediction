package ru.cashprediction.web;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.web.ui.CoreWebRuntime;
import ru.cashprediction.web.ui.UiApi;
import ru.cashprediction.core.web.reconnect.ReconnectCredentials;
import ru.cashprediction.core.web.reconnect.ReconnectAuthenticator;

/**
 * Встроенный HTTP-сервер web-клиента: статика из ресурсов jar ({@link StaticHandler}) и JSON API ({@link UiApi}).
 *
 * <p>Сервер слушает только адрес обратной петли 127.0.0.1. При запуске создаётся случайный токен; адрес для браузера
 * имеет вид {@code http://127.0.0.1:8765/?t=<токен>}. Браузер сохраняет токен в {@code sessionStorage} и отправляет
 * его с каждым запросом API.</p>
 *
 * <p>Класс потокобезопасен: остановку можно вызвать из любого потока, повторный вызов ничего не делает.</p>
 */
public final class WebServer {

    /** Адрес, на котором слушает сервер. */
    public static final String HOST = "127.0.0.1";

    private CoreWebRuntime core;
    private WebUpdateSession updates;
    private final ServerLog log;
    private final HttpServer http;
    private final ExecutorService executor;
    private final String token;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final List<Runnable> stopListeners = new CopyOnWriteArrayList<>();

    private WebServer(ServerLog log, HttpServer http, String token) {
        this.log = log;
        this.http = http;
        this.token = token;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Запускает единое ядро, защищённый API и статические ресурсы клиента. */
    public static WebServer startCore(AppEnvironment environment, ServerLog log, int preferred, boolean strict) throws Exception {
        return startCore(environment, log, preferred, strict, environment.options().toArguments().toArray(String[]::new));
    }

    /** Проходит предстартовый барьер с исходными аргументами до привязки HTTP и создания ядра. */
    public static WebServer startCore(AppEnvironment environment, ServerLog log, int preferred, boolean strict,
                                      String[] args) throws Exception {
        return startCore(environment, log, preferred, strict, WebUpdateSession.open(environment, args));
    }

    /** Шов проверяет реальные HTTP и startup-пути с изолированным обновлятором. */
    static WebServer startCore(AppEnvironment environment, ServerLog log, int preferred, boolean strict,
                               WebUpdateSession updates) throws Exception {
        if (!updates.beforeUi()) throw new WebUpdateSession.DeferredLaunch();
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(updates::close, "cp-web-update-close"));
            return startRegistered(environment, log, preferred, strict, updates);
        } catch (Exception | LinkageError failure) { updates.close(); throw failure; }
    }

    private static WebServer startRegistered(AppEnvironment environment, ServerLog log, int preferred,
                                              boolean strict, WebUpdateSession updates) throws Exception {
        HttpServer http = PortFinder.bind(InetAddress.getByName(HOST), preferred, strict, log);
        final WebServer server;
        try { server = new WebServer(log, http, newToken()); }
        catch (RuntimeException | LinkageError failure) { http.stop(0); throw failure; }
        server.updates = updates;
        // Каждое ожидание long-poll обслуживается собственным виртуальным потоком.
        try {
            server.core = new CoreWebRuntime(environment, updates::mainReady);
            server.core.port().onExit((kind, code) -> {
                // Короткое ограниченное ожидание позволяет отправить ответ intent и последний long-poll.
                Thread shutdown = new Thread(() -> {
                    try {
                        if (server.core.tests() != null && server.core.tests().hasScript())
                            server.core.tests().completion().get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) { /* Остановка ограничена по времени. */ }
                    // completion завершается внутри последнего POST: ответу нужен тот же короткий запас,
                    // что и обычному intent выхода, иначе stop(0) может оборвать подтверждение результата.
                    try { Thread.sleep(500); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    server.stop();
                }, "cashprediction-web-exit");
                shutdown.setDaemon(true); shutdown.start();
            });
            // StartupFlow создаёт CashMemory до открытия хранилища: первая сессия тоже получает ключ.
            server.core.start();
            ReconnectAuthenticator reconnect = null;
            try {
                reconnect = ReconnectCredentials.open(environment.cashMemory())
                        .map(credential -> new ReconnectAuthenticator(credential, server.token, java.time.Clock.systemUTC()))
                        .orElse(null);
            } catch (Exception unavailable) {
                // Повреждённое или недоступное доказательство не мешает обычному запуску со свежим токеном.
            }
            UiApi api = new UiApi(server.core.thread(), server.core.controller(), server.core.port(), server.core.effects(),
                    server.token, server::port, server.core.tests(), reconnect);
            http.createContext("/api/", api);
            http.createContext("/", new StaticHandler(log));
            http.setExecutor(server.executor);
            http.start();
            updates.httpReady();
            return server;
        } catch (Exception | LinkageError error) {
            try { server.stop(); }
            catch (RuntimeException | LinkageError cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    /** Освобождает lifecycle при поздней ошибке запуска, не вызывая слушателей обычного выхода. */
    public void closeUpdates() { if (updates != null) updates.close(); }

    /** Возвращает инфраструктуру единственного интерфейса ядра. */
    public CoreWebRuntime coreRuntime() { return core; }

    /** @return фактический порт сервера */
    public int port() {
        return http.getAddress().getPort();
    }

    /** @return секретный токен сеанса */
    public String token() {
        return token;
    }

    /** @return адрес страницы с токеном для открытия в браузере */
    public URI browserUri() {
        return URI.create("http://" + HOST + ":" + port() + "/?" + UiApi.TOKEN_PARAM + "=" + token);
    }

    /**
     * Подписывает слушателя на остановку сервера (окно статуса закрывается).
     *
     * @param listener слушатель
     */
    public void addStopListener(Runnable listener) {
        stopListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Корректная остановка: снимок сессии, настройки, маркер {@code closed}, остановка HTTP-сервера.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            if (core != null) core.close();
        } finally {
            try { http.stop(0); }
            finally {
                try { executor.shutdownNow(); }
                finally { if (updates != null) updates.close(); }
            }
        }
        for (Runnable listener : stopListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error(Texts.get("app.web.stopListenerFailure"), e);
            }
        }
        log.info(Texts.get("app.web.stopped"));
    }

    /** Корректная остановка и завершение процесса (команда «Выход» из браузера или окна статуса). */
    public void shutdownAndExit() {
        core.thread().execute(core.controller()::closeMainRequested);
    }

    /** @return случайный токен (192 бита в Base64 без заполнителя, безопасен для URL) */
    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
