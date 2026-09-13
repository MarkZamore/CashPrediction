package ru.cashprediction.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.IntSupplier;
import ru.cashprediction.core.json.JsonException;
import ru.cashprediction.core.markdown.MarkdownParseException;

/**
 * Обработчик всех адресов {@code /api/*}: проверка безопасности, поиск маршрута, выполнение под монитором
 * серверного состояния и превращение исключений в JSON-ответы с русскими сообщениями.
 *
 * <p><b>Безопасность.</b> Сервер слушает только 127.0.0.1, но любая страница в браузере могла бы отправить запрос
 * на localhost. Поэтому каждый запрос API обязан нести секретный токен: в заголовке {@code X-Token} (обычные
 * запросы {@code fetch}) или в параметре {@code t} (ссылки скачивания и {@code navigator.sendBeacon}, которые
 * заголовки задавать не умеют). Заголовок {@code Host} сверяется с адресом сервера — это защита от
 * DNS rebinding.</p>
 *
 * <p><b>Потоки.</b> HTTP-сервер вызывает обработчик из нескольких потоков; каждый маршрут выполняется под монитором
 * {@link ServerState#lock}, поэтому {@code PlanDocument} видит запросы строго по одному.</p>
 *
 * <p><b>Ошибки.</b> {@link ApiException} → её статус; {@link ConflictException} → 409 с полем {@code conflict};
 * {@link IllegalArgumentException}, {@link JsonException}, {@link DateTimeException}, {@link MarkdownParseException},
 * {@link IllegalStateException} → 400; {@link NoSuchElementException} → 404; {@link IOException} и прочее → 500
 * со стеком в поле {@code details} (браузер показывает его в раскрываемой части {@code Alert(ERROR)}).</p>
 *
 * <p><b>Маршруты</b> (тела и ответы — JSON в UTF-8; поля форм — канонические строки {@code WindowState.fields}:
 * деньги {@code "95000,00"}, даты ISO, {@code "true"/"false"}, имена констант). Ответ правок — полное состояние,
 * как у {@code GET /api/state}.</p>
 * <table>
 *   <caption>JSON API web-клиента</caption>
 *   <tr><th>Метод и путь</th><th>Назначение</th></tr>
 *   <tr><td colspan="2"><b>Сессия и восстановление</b> ({@link SessionApi})</td></tr>
 *   <tr><td>GET /api/session</td><td>сводка: {@code state} (crashed|running|disabled|new|closed), {@code savedAt},
 *       {@code pendingRestore}, {@code pendingSnapshot} (окна с полями), статусы хранилища, открытые окна</td></tr>
 *   <tr><td>POST /api/session/start {restore}</td><td>решение по баннеру «Восстановить с сервера / Начать заново»</td></tr>
 *   <tr><td>POST /api/session/record</td><td>начать запись после восстановления с {@code RECORDER_NOT_STARTED}</td></tr>
 *   <tr><td>GET /api/session/unrestored-plan</td><td>скачать несохранённый план из снимка, который не открылся</td></tr>
 *   <tr><td>PUT /api/session/main {bounds, maximized, selectedRowId, view?}</td><td>состояние главного окна</td></tr>
 *   <tr><td>POST /api/session/windows {type, modal, ownerId, context, fields, bounds}</td><td>открыто окно → {@code {id}}</td></tr>
 *   <tr><td>PUT /api/session/windows/{id} {fields, context?, bounds?}</td><td>слияние введённых значений
 *       (также {@code .../{id}/fields})</td></tr>
 *   <tr><td>DELETE /api/session/windows/{id}</td><td>окно закрыто (вместе с дочерними)</td></tr>
 *   <tr><td>POST /api/session/exit</td><td>вкладка закрыта обычным образом ({@code sendBeacon}, токен в {@code ?t=}):
 *       окна остаются на сервере, снимок пишется сразу</td></tr>
 *   <tr><td>POST /api/session/snapshot</td><td>«Сделать снимок сейчас» ({@code saveNow})</td></tr>
 *   <tr><td>POST /api/session/clear</td><td>«Очистить снимки» ({@code SessionRecorder.clearSnapshots})</td></tr>
 *   <tr><td>GET /api/session/last</td><td>текст {@code web-session.md} («Показать последний снимок…»)</td></tr>
 *   <tr><td>POST /api/debug/client-error {message}</td><td>необработанная ошибка JavaScript: снимок сохраняется</td></tr>
 *   <tr><td>POST /api/debug/crash</td><td>«Симулировать сбой»: {@code Runtime.halt(3)} без сохранения</td></tr>
 *   <tr><td>POST /api/shutdown</td><td>корректная остановка сервера</td></tr>
 *   <tr><td colspan="2"><b>Состояние, вид, чтение</b> ({@link ViewApi})</td></tr>
 *   <tr><td>GET /api/state</td><td>план, прогноз видимого периода (строки, точки графика, сводка, предупреждения),
 *       {@code viewState}, настройки, {@code dirty}, {@code file}, {@code canUndo/canRedo}, окна, {@code pendingRestore}</td></tr>
 *   <tr><td>GET /api/forecast?from&amp;to&amp;maxPoints</td><td>прогноз произвольного интервала</td></tr>
 *   <tr><td>PUT /api/view {viewState}</td><td>режим, период, фильтры, строка фильтра, «что-если»</td></tr>
 *   <tr><td>PUT /api/settings {autosave}</td><td>CheckMenuItem «Автосохранение»</td></tr>
 *   <tr><td>GET /api/diagnostics</td><td>PlanValidator + диагностика чтения + предупреждения прогноза</td></tr>
 *   <tr><td>GET /api/goal?target&amp;byDate&amp;extraSaving</td><td>калькулятор цели ({@code GoalCalculator})</td></tr>
 *   <tr><td>POST /api/goal/save {target, byDate}</td><td>сделать цель целью плана</td></tr>
 *   <tr><td>GET /api/export.csv?separator&amp;bom&amp;range&amp;t=</td><td>скачать CSV ({@code CsvExporter})</td></tr>
 *   <tr><td>GET /api/format-help</td><td>текст FORMAT.md (также {@code /api/help/format})</td></tr>
 *   <tr><td>GET /api/about</td><td>версия, Java, путь CashMemory</td></tr>
 *   <tr><td colspan="2"><b>Файлы планов</b> ({@link FileApi}, {@link PlanFileCommands})</td></tr>
 *   <tr><td>GET /api/plans</td><td>планы CashMemory и папки сеанса, недавние</td></tr>
 *   <tr><td>POST /api/plans/new {fields, windowId?}</td><td>план из мастера (в памяти, до первого сохранения)</td></tr>
 *   <tr><td>POST /api/plans/open {name | path}</td><td>открыть план</td></tr>
 *   <tr><td>POST /api/plans/sample</td><td>«Открыть пример»</td></tr>
 *   <tr><td>POST /api/plans/save {overwrite?}</td><td>сохранить; 409 {@code externalChange} — файл изменён снаружи</td></tr>
 *   <tr><td>POST /api/plans/save-as {name, folder?, overwrite?}</td><td>«Сохранить как…»; 409 {@code exists}</td></tr>
 *   <tr><td>POST /api/plans/reload</td><td>«Перечитать» с диска</td></tr>
 *   <tr><td>POST /api/plans/rename {name}</td><td>«Переименовать…»</td></tr>
 *   <tr><td>POST /api/plans/import {name, text}</td><td>импорт файла, выбранного в браузере</td></tr>
 *   <tr><td>GET /api/plans/download?name=&amp;t=</td><td>скачать план (пустое имя — текущий)</td></tr>
 *   <tr><td>POST /api/plans/folder {folder}</td><td>папка планов на сеанс («Папка CashMemory…»)</td></tr>
 *   <tr><td>GET /api/fs?path=&amp;mode=dirs|md</td><td>серверный обозреватель ({@link FolderBrowserApi})</td></tr>
 *   <tr><td colspan="2"><b>Правка плана</b> ({@link PlanEditApi})</td></tr>
 *   <tr><td>PUT /api/plan/settings {fields}</td><td>«Параметры плана», валюта, горизонт</td></tr>
 *   <tr><td>POST /api/rules {fields} · PUT /api/rules/{id} · DELETE /api/rules/{id}</td><td>регулярные операции</td></tr>
 *   <tr><td>POST /api/rules/{id}/enabled {enabled}</td><td>включить/отключить правило</td></tr>
 *   <tr><td>GET|POST /api/preview-dates</td><td>«Ближайшие даты» для полей редактора правила</td></tr>
 *   <tr><td>POST /api/one-time · PUT /api/one-time/{id} · DELETE /api/one-time/{id}</td><td>разовые операции</td></tr>
 *   <tr><td>PUT /api/adjustments {ruleId, originalDate, action, amount, date, note}</td><td>корректировка события</td></tr>
 *   <tr><td>DELETE /api/adjustments?ruleId&amp;originalDate</td><td>«Сбросить» / «Вернуть как по правилу»</td></tr>
 *   <tr><td>POST /api/cleanup-orphans</td><td>удалить неиспользуемые корректировки</td></tr>
 *   <tr><td>POST /api/undo · POST /api/redo</td><td>отмена и повтор</td></tr>
 *   <tr><td>GET /api/actualize/preview · POST /api/actualize {balance?}</td><td>«Актуализировать на сегодня»</td></tr>
 *   <tr><td>POST /api/reconcile {balance}</td><td>«Сверить баланс»</td></tr>
 *   <tr><td>PUT /api/what-if {incomeFactor, expenseFactor, extraMonthlySaving}</td><td>«что-если»</td></tr>
 *   <tr><td>POST /api/what-if/apply · POST /api/what-if/reset</td><td>применить к плану / сбросить</td></tr>
 * </table>
 * <p>Во всех правках, пришедших из диалога, можно передать {@code windowId}: окно закроется на сервере в том же
 * запросе, и снимок сессии не увидит «уже применённый» диалог.</p>
 */
public final class ApiHandler implements HttpHandler {

    /** Имя заголовка с токеном. */
    public static final String TOKEN_HEADER = "X-Token";

    /** Имя параметра строки запроса с токеном. */
    public static final String TOKEN_PARAM = "t";

    private final Router router;
    private final ServerState state;
    private final ServerLog log;
    private final byte[] token;
    private final IntSupplier port;

    /**
     * Создаёт обработчик.
     *
     * @param router маршруты API
     * @param state  серверное состояние (его монитор сериализует запросы)
     * @param log    журнал сервера
     * @param token  секретный токен сеанса
     * @param port   порт сервера (для проверки заголовка Host; известен только после запуска)
     */
    public ApiHandler(Router router, ServerState state, ServerLog log, String token, IntSupplier port) {
        this.router = Objects.requireNonNull(router, "router");
        this.state = Objects.requireNonNull(state, "state");
        this.log = Objects.requireNonNull(log, "log");
        this.token = Objects.requireNonNull(token, "token").getBytes(StandardCharsets.UTF_8);
        this.port = Objects.requireNonNull(port, "port");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            boolean head = "HEAD".equals(method);
            ApiResponse response;
            try {
                response = dispatch(exchange, method);
            } catch (RuntimeException | IOException e) {
                response = errorResponse(e, method + " " + exchange.getRequestURI().getPath());
            }
            if (response.fileName() != null) {
                exchange.getResponseHeaders().set("Content-Disposition", HttpUtil.attachment(response.fileName()));
            }
            HttpUtil.send(exchange, response.status(), response.contentType(), response.body(), head);
        } catch (IOException e) {
            // Браузер закрыл соединение раньше, чем получил ответ (например, sendBeacon при закрытии вкладки).
            log.error("Ответ API не отправлен", e);
        }
    }

    /**
     * Проверяет запрос, находит маршрут и выполняет его под монитором состояния.
     *
     * @param exchange обмен
     * @param method   метод в верхнем регистре
     * @return ответ маршрута
     * @throws IOException при ошибке чтения тела или работы маршрута с файлами
     */
    private ApiResponse dispatch(HttpExchange exchange, String method) throws IOException {
        checkHost(exchange.getRequestHeaders().getFirst("Host"));
        Map<String, String> query = HttpUtil.parseQuery(exchange.getRequestURI());
        checkToken(exchange.getRequestHeaders().getFirst(TOKEN_HEADER), query.get(TOKEN_PARAM));
        String path = exchange.getRequestURI().getPath();
        Router.Match match = router.find(method, path);
        String body = HttpUtil.readBody(exchange);
        ApiRequest request = new ApiRequest(method, path, query, match.params(), body);
        // Один монитор на все маршруты: PlanDocument не потокобезопасен, а снимок сессии должен видеть
        // план целиком до или после правки, но не посередине.
        synchronized (state.lock) {
            return match.handler().handle(request);
        }
    }

    /**
     * Сверяет заголовок Host с адресом сервера (защита от DNS rebinding).
     *
     * @param host значение заголовка или {@code null}
     */
    private void checkHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        int p = port.getAsInt();
        if (!h.equals("127.0.0.1:" + p) && !h.equals("localhost:" + p) && !h.equals("[::1]:" + p)) {
            throw ApiException.forbidden("Запрос к API с чужим адресом «" + host + "» отклонён");
        }
    }

    /**
     * Проверяет токен сеанса за постоянное время (чтобы токен нельзя было подобрать по времени ответа).
     *
     * @param header значение заголовка {@value #TOKEN_HEADER} или {@code null}
     * @param param  значение параметра {@value #TOKEN_PARAM} или {@code null}
     */
    private void checkToken(String header, String param) {
        String given = header != null && !header.isBlank() ? header.strip() : param;
        if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.forbidden("Нет доступа: откройте CashPrediction по ссылке из окна сервера "
                    + "(адрес со вторым параметром ?t=…)");
        }
    }

    /**
     * Превращает исключение в JSON-ответ.
     *
     * @param error исключение
     * @param where метод и путь запроса (для журнала)
     * @return ответ с полем {@code error}
     */
    ApiResponse errorResponse(Exception error, String where) {
        Map<String, Object> body = new LinkedHashMap<>();
        int status;
        switch (error) {
            case ApiException api -> {
                status = api.status();
                body.putAll(api.extra());
            }
            case ConflictException conflict -> {
                status = 409;
                body.put("conflict", conflict.kind());
            }
            case NoSuchElementException _ -> status = 404;
            case IllegalArgumentException _ -> status = 400;
            case JsonException _ -> status = 400;
            case DateTimeException _ -> status = 400;
            case MarkdownParseException _ -> status = 400;
            case IllegalStateException _ -> status = 400;
            default -> status = 500;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        if (status == 500) {
            String prefix = error instanceof IOException || error instanceof UncheckedIOException
                    ? "Ошибка чтения или записи файла: " : "Внутренняя ошибка сервера: ";
            message = prefix + message;
            body.put("details", stackTrace(error));
            log.error("Ошибка при обработке " + where, error);
        }
        body.put("error", message);
        body.put("status", (long) status);
        return ApiResponse.json(status, body);
    }

    /**
     * Стек исключения текстом.
     *
     * @param error исключение
     * @return стек
     */
    static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
