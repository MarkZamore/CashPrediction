package ru.cashprediction.web.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.json.*;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.json.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.web.*;

/** Четыре HTTP-маршрута UI: безопасность существующего API и сериализация действий на потоке ядра. */
public final class UiApi implements HttpHandler {
    private final ControllerThread thread;
    private final AppController controller;
    private final WebUiPort port;
    private final EffectLog log;
    private final byte[] token;
    private final IntSupplier serverPort;
    private final WebSelfTestBridge tests;
    // Последняя ревизия вкладки по полю: эхо другой вкладки не отменяет ввод этой.
    private final Map<String, Map<String, Long>> clientRevisions = new LinkedHashMap<>();

    /** Создаёт защищённый обработчик; тестовый мост может отсутствовать. */
    public UiApi(ControllerThread thread, AppController controller, WebUiPort port, EffectLog log,
            String token, IntSupplier serverPort, WebSelfTestBridge tests) {
        this.thread = thread; this.controller = controller; this.port = port; this.log = log;
        this.token = token.getBytes(StandardCharsets.UTF_8); this.serverPort = serverPort; this.tests = tests;
    }
    /** {@inheritDoc} */
    @Override public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            int status = 200;
            Object result;
            try { result = dispatch(exchange); }
            catch (ApiException error) { status = error.status(); result = Map.of("error", error.getMessage()); }
            catch (TimeoutException error) { status = 503; result = Map.of("busy", true); }
            catch (IllegalArgumentException | JsonException error) { status = 400; result = Map.of("error", UiText.get("json.error.uiField", "request")); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); status = 503; result = Map.of("busy", true); }
            catch (Exception error) { status = 500; result = Map.of("error", UiText.get("err.generic")); }
            byte[] body = JsonWriter.write(UiJson.toTree(result)).getBytes(StandardCharsets.UTF_8);
            HttpUtil.send(exchange, status, ApiResponse.JSON, body, false);
        }
    }
    private Object dispatch(HttpExchange exchange) throws Exception {
        Map<String, String> query = HttpUtil.parseQuery(exchange.getRequestURI());
        checkSecurity(exchange, query);
        String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
        if (path.equals("/api/test/counters") && tests != null && method.equals("GET")) {
            if (!HttpUtil.readBody(exchange).isEmpty() || query.keySet().stream().anyMatch(key -> !key.equals("t")))
                throw new IllegalArgumentException("counters request");
            return call(() -> {
                Map<String, Integer> counters = new LinkedHashMap<>();
                for (var command : ru.cashprediction.core.ui.command.CommandId.values()) {
                    int count = controller.executedCount(command);
                    if (count > 0) counters.put(command.id(), count);
                }
                return Map.of("counters", counters);
            });
        }
        if (path.equals("/api/ui/events") && method.equals("GET")) {
            tab(query.get("tab"));
            return log.await(number(query.get("after")), Duration.ofSeconds(25));
        }
        if (path.equals("/api/ui/bootstrap") && method.equals("GET")) {
            String tab = tab(query.get("tab"));
            return call(() -> {
                WebBootstrap bootstrap = port.bootstrap();
                // Первый шаг находится в bootstrap.seq + 1, поэтому не теряется между bootstrap и events.
                if (tests != null) tests.connected(tab);
                return UiJson.toTree(bootstrap);
            });
        }
        if (!method.equals("POST")) throw ApiException.notFound(UiText.get("json.error.uiField", "route"));
        if (!path.equals("/api/ui/query") && !path.equals("/api/ui/intent")
                && !(tests != null && (path.equals("/api/test/result") || path.equals("/api/test/dump") || path.equals("/api/test/shot"))))
            throw ApiException.notFound(UiText.get("json.error.uiField", "route"));
        Map<String, Object> body = Json.asObject(JsonParser.parse(HttpUtil.readBody(exchange)), "body");
        if (path.equals("/api/ui/query")) {
            WebQuery request = UiJson.readQuery(body);
            return call(() -> query(request));
        }
        if (path.equals("/api/ui/intent")) {
            String tab = tab(Json.requireString(body, "tab"));
            long after = Json.requireLong(body, "afterSeq");
            log.after(after); // Неверный курсор не должен выполнить действие.
            WebIntent intent = UiJson.readIntent(Json.asObject(body.get("intent"), "intent"));
            if (intent instanceof WebIntent.Command command && command.source() == InvokeSource.SELFTEST)
                throw new IllegalArgumentException("source");
            return call(() -> {
                if (!port.stopped()) {
                    String window = intent instanceof WebIntent.FormField field ? field.windowId() : "";
                    long rev = intent instanceof WebIntent.FormField field ? field.clientRev() : 0;
                    port.intentContext(tab, window, rev);
                    try { intent(tab, intent); } finally { port.intentContext("", "", 0); }
                }
                return log.after(after);
            });
        }
        if (tests != null && (path.equals("/api/test/result") || path.equals("/api/test/dump") || path.equals("/api/test/shot"))) {
            return call(() -> { tests.receive(path, body); return Map.of("ok", true); });
        }
        throw ApiException.notFound(UiText.get("json.error.uiField", "route"));
    }
    private <T> T call(java.util.concurrent.Callable<T> action) throws Exception {
        try { return thread.submit(action).get(5, TimeUnit.SECONDS); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }
    private void intent(String tab, WebIntent intent) {
        switch (intent) {
            case WebIntent.Command command -> controller.command(command.command(), command.args(), command.source());
            case WebIntent.Key key -> controller.key(key.chord(), key.scope(), key.focusId());
            case WebIntent.SelectRow row -> controller.selectRow(row.rowId());
            case WebIntent.ActivateRow row -> controller.activateRow(row.rowId(), row.columnId(), row.how());
            case WebIntent.FilterText filter -> controller.filterText(filter.text());
            case WebIntent.SliderCommit slider -> controller.sliderCommit(slider.itemId(), slider.value());
            case WebIntent.SpinnerCommit spinner -> controller.spinnerCommit(spinner.itemId(), spinner.value());
            case WebIntent.MainGeometry geometry -> {
                port.geometry(geometry.bounds(), geometry.maximized()); controller.mainGeometry(geometry.bounds(), geometry.maximized());
            }
            case WebIntent.MenuHover hover -> controller.menuHover(hover.itemId());
            case WebIntent.CloseMain ignored -> controller.closeMainRequested();
            case WebIntent.FormField field -> port.form(field.windowId()).ifPresent(form -> {
                if (!editable(form)) return;
                String key = field.windowId() + ":" + field.fieldId();
                Map<String, Long> revisions = clientRevisions.computeIfAbsent(tab, ignored -> new LinkedHashMap<>());
                if (field.clientRev() <= revisions.getOrDefault(key, -1L)) return;
                if (!form.view().fields().containsKey(field.fieldId())) throw new IllegalArgumentException("fieldId");
                revisions.put(key, field.clientRev()); port.typed(field.windowId(), field.fieldId(), field.raw());
                form.fieldChanged(field.fieldId(), field.raw(), field.committed(), field.clientRev());
                revisions.keySet().removeIf(value -> port.form(value.substring(0, value.indexOf(':'))).isEmpty());
            });
            case WebIntent.FormButton button -> port.form(button.windowId()).ifPresent(form -> { if (editable(form)) form.buttonPressed(button.buttonId()); });
            case WebIntent.FormPreview preview -> port.form(preview.windowId()).ifPresent(form -> { if (editable(form)) form.previewSelected(preview.index(), preview.activated()); });
            case WebIntent.FormActivate activate -> port.form(activate.windowId()).ifPresent(form -> { if (editable(form)) form.fieldActivated(activate.fieldId(), activate.index()); });
            case WebIntent.FormSubmit submit -> port.form(submit.windowId()).ifPresent(form -> { if (editable(form)) form.fieldSubmitted(submit.fieldId()); });
            case WebIntent.FormBounds bounds -> port.bounds(bounds.windowId(), bounds.bounds());
            case WebIntent.FormShown shown -> port.formShown(shown.windowId());
            case WebIntent.FormClose close -> port.form(close.windowId()).ifPresent(form -> { if (editable(form)) form.closeRequested(); });
            case WebIntent.AlertShown shown -> port.alertShown(shown.windowId());
            case WebIntent.AlertAnswer answer -> {
                if (controller.state().windows().topModal().map(window -> window.windowId().equals(answer.alertId())).orElse(true))
                    port.answer(answer.alertId(), answer.buttonId());
            }
            case WebIntent.ClientError error -> controller.clientError(error.message(), error.stack());
        }
    }
    private boolean editable(FormSession form) {
        return controller.state().windows().topModal().map(window -> window.windowId().equals(form.windowId())).orElse(true);
    }
    private Object query(WebQuery request) {
        var screen = port.screen();
        Long requested = switch (request) {
            case WebQuery.Rows rows -> rows.rev();
            case WebQuery.Tooltip tooltip -> tooltip.rev();
            case WebQuery.Chart chart -> chart.rev();
            case WebQuery.ChartHover hover -> hover.rev();
            default -> null;
        };
        long actual = screen == null ? 0 : switch (request) {
            case WebQuery.Chart ignored -> screen.chart().revision();
            case WebQuery.ChartHover ignored -> screen.chart().revision();
            default -> screen.table().revision();
        };
        if (requested != null && (screen == null || requested != actual)) return Map.of("stale", true, "rev", actual);
        Object result = switch (request) {
            case WebQuery.ContextMenu menu -> controller.contextMenu(menu.target());
            case WebQuery.Tooltip tooltip -> controller.tableTooltip(tooltip.rev(), tooltip.index(), tooltip.columnId());
            case WebQuery.Rows rows -> {
                var values = new ArrayList<Object>();
                int end = (int) Math.min((long) screen.table().rowCount(), (long) rows.from() + rows.count());
                for (int i = rows.from(); i < end; i++) values.add(screen.table().row(i));
                yield values;
            }
            case WebQuery.Chart chart -> controller.chartScene(chart.w(), chart.h());
            case WebQuery.ChartHover hover -> controller.chartHover(hover.rev(), hover.x(), hover.y(), hover.w(), hover.h()).orElse(null);
            case WebQuery.DayCard day -> controller.dayCard(day.date());
            case WebQuery.Sparkline sparkline -> controller.sparkline(sparkline.cardId());
            case WebQuery.Calendar calendar -> controller.calendar(calendar.month(), calendar.selected());
        };
        Map<String, Object> response = new LinkedHashMap<>(); response.put("result", UiJson.toTree(result)); return response;
    }
    private void checkSecurity(HttpExchange exchange, Map<String, String> query) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        String value = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        int port = serverPort.getAsInt();
        if (!value.equals("127.0.0.1:" + port) && !value.equals("localhost:" + port) && !value.equals("[::1]:" + port))
            throw ApiException.forbidden(UiText.get("json.error.uiField", "Host"));
        String header = exchange.getRequestHeaders().getFirst(ApiHandler.TOKEN_HEADER);
        String given = header != null && !header.isBlank() ? header.strip() : query.get(ApiHandler.TOKEN_PARAM);
        if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8)))
            throw ApiException.forbidden(UiText.get("json.error.uiField", "token"));
    }
    private static String tab(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException("tab");
        return value;
    }
    private static long number(String value) {
        try { long result = Long.parseLong(value); if (result < 0) throw new IllegalArgumentException("after"); return result; }
        catch (NumberFormatException error) { throw new IllegalArgumentException("after", error); }
    }
}
