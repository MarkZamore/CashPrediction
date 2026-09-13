package ru.cashprediction.web;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Встроенный HTTP-сервер web-клиента: статика из ресурсов jar ({@link StaticHandler}) и JSON API ({@link ApiHandler}).
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

    private final ServerState state;
    private final ServerLog log;
    private final HttpServer http;
    private final ExecutorService executor;
    private final String token;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final List<Runnable> stopListeners = new CopyOnWriteArrayList<>();

    private WebServer(ServerState state, ServerLog log, HttpServer http, String token) {
        this.state = state;
        this.log = log;
        this.http = http;
        this.token = token;
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "cashprediction-http-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Создаёт и запускает сервер.
     *
     * @param state     серверное состояние
     * @param log       журнал сервера
     * @param preferred желаемый порт (0 — любой свободный)
     * @param strict    только этот порт, без запасного варианта
     * @return запущенный сервер
     * @throws IOException если порт занять не удалось
     */
    public static WebServer start(ServerState state, ServerLog log, int preferred, boolean strict) throws IOException {
        Objects.requireNonNull(state, "state");
        HttpServer http = PortFinder.bind(InetAddress.getByName(HOST), preferred, strict, log);
        WebServer server = new WebServer(state, log, http, newToken());
        server.configure();
        http.start();
        log.info("Сервер CashPrediction запущен: " + server.browserUri());
        return server;
    }

    /** Регистрирует маршруты и контексты. */
    private void configure() {
        PlanFileCommands files = new PlanFileCommands(state);
        Router router = new Router();
        new SessionApi(state, this::shutdownAndExit, WebServer::crash).register(router);
        new FileApi(state, files).register(router);
        new PlanEditApi(state, files).register(router);
        new ViewApi(state).register(router);
        new FolderBrowserApi(state.layout().dir()).register(router);
        http.createContext("/api/", new ApiHandler(router, state, log, token, this::port));
        http.createContext("/", new StaticHandler(log));
        http.setExecutor(executor);
    }

    /** @return фактический порт сервера */
    public int port() {
        return http.getAddress().getPort();
    }

    /** @return секретный токен сеанса */
    public String token() {
        return token;
    }

    /** @return серверное состояние */
    public ServerState state() {
        return state;
    }

    /** @return адрес страницы с токеном для открытия в браузере */
    public URI browserUri() {
        return URI.create("http://" + HOST + ":" + port() + "/?t=" + token);
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
        state.shutdownClean();
        http.stop(0);
        executor.shutdownNow();
        for (Runnable listener : stopListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("Слушатель остановки сервера завершился с ошибкой", e);
            }
        }
        log.info("HTTP-сервер остановлен");
    }

    /**
     * Имитация «убитого» процесса для тестов: HTTP-сервер останавливается, а сеанс бросается без снимка и без
     * маркера {@code closed} ({@link ServerState#abandon()}). Следующий {@link ServerState} над той же папкой
     * увидит незакрытый сеанс и предложит восстановление.
     */
    void abandon() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        state.abandon();
        http.stop(0);
        executor.shutdownNow();
    }

    /** Корректная остановка и завершение процесса (команда «Выход» из браузера или окна статуса). */
    public void shutdownAndExit() {
        stop();
        System.exit(0);
    }

    /**
     * «Симулировать сбой → Остановить сервер аварийно»: процесс завершается немедленно, без снимка и без маркера
     * {@code closed}. При следующем запуске сервер увидит незакрытый сеанс и предложит восстановление.
     */
    static void crash() {
        Runtime.getRuntime().halt(3);
    }

    /** @return случайный токен (192 бита в Base64 без заполнителя, безопасен для URL) */
    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
