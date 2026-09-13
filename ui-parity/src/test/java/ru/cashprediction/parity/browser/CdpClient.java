package ru.cashprediction.parity.browser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;

/**
 * Минимальный клиент Chrome DevTools Protocol на {@link HttpClient} и {@link WebSocket} из JDK.
 *
 * <p>Поддерживаются ровно три команды: {@code Page.navigate}, {@code Page.captureScreenshot} и
 * {@code Runtime.evaluate} (стадия S0). Ограничение закреплено перечислением {@link Method}: другую команду
 * отправить нельзя, так стенд не превращается в самодельный Puppeteer. События протокола игнорируются;
 * загрузку страницы ждут опросом {@code document.readyState} через {@link #waitFor}.</p>
 *
 * <p>JSON разбирается и пишется мини-JSON ядра, без внешних библиотек.</p>
 */
public final class CdpClient implements AutoCloseable {

    /** Команды протокола, которые разрешено отправлять. */
    public enum Method {
        /** Переход страницы по адресу. */
        PAGE_NAVIGATE("Page.navigate"),
        /** Снимок видимой области страницы. */
        PAGE_CAPTURE_SCREENSHOT("Page.captureScreenshot"),
        /** Выполнение выражения JavaScript в странице. */
        RUNTIME_EVALUATE("Runtime.evaluate");

        private final String wireName;

        Method(String wireName) {
            this.wireName = wireName;
        }

        /** @return имя команды в протоколе */
        public String wireName() {
            return wireName;
        }
    }

    /** Ошибка команды протокола или соединения. */
    public static final class CdpException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Создаёт ошибку.
         *
         * @param message описание
         * @param cause   причина или {@code null}
         */
        public CdpException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Время ожидания ответа на одну команду. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final WebSocket socket;
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Object sendLock = new Object();

    private CdpClient(HttpClient http, WebSocket socket) {
        this.http = http;
        this.socket = socket;
    }

    /**
     * Подключается к первой вкладке браузера: опрашивает {@code http://127.0.0.1:<port>/json/list},
     * пока там не появится цель типа {@code page}.
     *
     * @param port    отладочный порт из {@code DevToolsActivePort}
     * @param timeout наибольшее время ожидания вкладки и соединения
     * @return клиент вкладки
     * @throws CdpException если вкладка не появилась или соединение не установлено
     */
    public static CdpClient connectToFirstPage(int port, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        URI list = URI.create("http://127.0.0.1:" + port + "/json/list");
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(list).timeout(Duration.ofSeconds(5))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    for (Object item : (List<?>) JsonParser.parse(response.body())) {
                        Map<String, Object> target = Json.asObject(item, "target");
                        String url = Json.string(target, "webSocketDebuggerUrl", "");
                        if ("page".equals(Json.string(target, "type", "")) && !url.isEmpty()) {
                            return connect(http, URI.create(url), timeout);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Браузер ещё поднимает HTTP-обработчик отладки: пробуем снова до истечения времени.
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CdpException("Interrupted while connecting to DevTools", e);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CdpException("Interrupted while connecting to DevTools", e);
            }
        }
        throw new CdpException("No page target on DevTools port " + port + " within " + timeout, last);
    }

    private static CdpClient connect(HttpClient http, URI webSocketUrl, Duration timeout) {
        Receiver receiver = new Receiver();
        try {
            WebSocket socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(webSocketUrl, receiver).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            CdpClient client = new CdpClient(http, socket);
            receiver.client = client;
            return client;
        } catch (ExecutionException | TimeoutException e) {
            throw new CdpException("Cannot open DevTools WebSocket " + webSocketUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdpException("Interrupted while opening DevTools WebSocket", e);
        }
    }

    /**
     * Переходит по адресу ({@code Page.navigate}).
     *
     * @param url адрес
     * @return идентификатор фрейма
     * @throws CdpException если браузер сообщил ошибку перехода
     */
    public String navigate(String url) {
        Map<String, Object> result = call(Method.PAGE_NAVIGATE, Map.of("url", url));
        String error = Json.string(result, "errorText", "");
        if (!error.isEmpty()) {
            throw new CdpException("Page.navigate to " + url + " failed: " + error, null);
        }
        return Json.string(result, "frameId", "");
    }

    /**
     * Выполняет выражение JavaScript ({@code Runtime.evaluate}) и возвращает его значение как JSON.
     *
     * @param expression выражение; промисы дожидаются
     * @return значение: {@code String}, {@code Long}, {@code BigDecimal}, {@code Boolean}, карта, список или {@code null}
     * @throws CdpException если выражение выбросило исключение
     */
    public Object evaluate(String expression) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", true);
        Map<String, Object> result = call(Method.RUNTIME_EVALUATE, params);
        if (result.containsKey("exceptionDetails")) {
            throw new CdpException("Runtime.evaluate failed for '" + expression + "': "
                    + JsonWriter.write(result.get("exceptionDetails")), null);
        }
        Map<String, Object> remote = Json.object(result, "result");
        return remote.get("value");
    }

    /**
     * Ждёт, пока выражение станет истинным ({@code true}), опрашивая {@link #evaluate}.
     *
     * @param expression выражение JavaScript, возвращающее логическое значение
     * @param timeout    наибольшее время ожидания
     * @throws CdpException если время вышло
     */
    public void waitFor(String expression, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (Boolean.TRUE.equals(evaluate(expression))) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new CdpException("Timed out after " + timeout + " waiting for " + expression, null);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CdpException("Interrupted while waiting for " + expression, e);
            }
        }
    }

    /**
     * Снимает видимую область страницы в PNG ({@code Page.captureScreenshot}).
     *
     * @return байты PNG
     */
    public byte[] captureScreenshot() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("format", "png");
        // Только окно просмотра: размер снимка равен --window-size, как у снимков настольных клиентов.
        params.put("captureBeyondViewport", false);
        Map<String, Object> result = call(Method.PAGE_CAPTURE_SCREENSHOT, params);
        return Base64.getDecoder().decode(Json.requireString(result, "data"));
    }

    /**
     * Отправляет команду и ждёт ответ с тем же {@code id}.
     *
     * @param method команда из разрешённого набора
     * @param params параметры команды
     * @return объект {@code result} ответа
     * @throws CdpException при ошибке протокола, разрыве соединения или истечении времени
     */
    private Map<String, Object> call(Method method, Map<String, ?> params) {
        long id = ids.incrementAndGet();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("method", method.wireName());
        message.put("params", params);
        try {
            // WebSocket JDK запрещает начинать новую отправку, пока не завершилась предыдущая.
            synchronized (sendLock) {
                socket.sendText(JsonWriter.write(message), true).get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            Map<String, Object> response = future.get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response.containsKey("error")) {
                throw new CdpException(method.wireName() + " failed: " + JsonWriter.write(response.get("error")), null);
            }
            return Json.object(response, "result");
        } catch (ExecutionException | TimeoutException e) {
            throw new CdpException(method.wireName() + " did not complete", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdpException("Interrupted during " + method.wireName(), e);
        } finally {
            pending.remove(id);
        }
    }

    private void onMessage(String text) {
        Map<String, Object> message;
        try {
            message = JsonParser.parseObject(text);
        } catch (RuntimeException e) {
            return;
        }
        if (message.get("id") instanceof Long id) {
            CompletableFuture<Map<String, Object>> future = pending.get(id);
            if (future != null) {
                future.complete(message);
            }
        }
        // Сообщения без id — события протокола; стенду S0 они не нужны.
    }

    private void failAll(Throwable cause) {
        pending.values().forEach(f -> f.completeExceptionally(cause));
    }

    /** Закрывает WebSocket; сам браузер закрывает {@link BrowserSession}. */
    @Override
    public void close() {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // Браузер мог уже закрыть соединение — это не ошибка закрытия.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            socket.abort();
            failAll(new CdpException("DevTools connection closed", null));
            http.shutdownNow();
        }
    }

    /** Собирает текстовые кадры в сообщения и передаёт их клиенту. */
    private static final class Receiver implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private volatile CdpClient client;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                CdpClient c = client;
                if (c != null) {
                    c.onMessage(text);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            CdpClient c = client;
            if (c != null) {
                c.failAll(new CdpException("DevTools WebSocket closed: " + statusCode + " " + reason, null));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            CdpClient c = client;
            if (c != null) {
                c.failAll(new CdpException("DevTools WebSocket error", error));
            }
        }
    }
}
