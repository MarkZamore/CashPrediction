package ru.cashprediction.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import ru.cashprediction.core.text.Texts;
import java.util.Locale;
import java.util.Map;
import ru.cashprediction.core.ui.token.TokenCss;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Отдаёт статические файлы тонкого клиента из ресурсов jar: {@code /} → {@code web/index.html},
 * {@code /app/main.js} → {@code web/app/main.js} и т. д.
 *
 * <p>Файлы берутся из ресурсов модуля, а не с диска: портативная сборка ничего не распаковывает
 * (раздел 5.7 плана). Кэширование выключено ({@code no-store}), чтобы после обновления программы браузер
 * не держал старый JavaScript. Заголовок Content-Security-Policy разрешает скрипты и стили только с этого же
 * сервера: внешние CDN не используются, а встроенные {@code <script>} и атрибуты {@code on...} запрещены —
 * так даже «вредный» план с HTML в названии не сможет выполнить код с доступом к API.</p>
 *
 * <p>Токен здесь не проверяется: страница и скрипты не секретны, а все данные идут через {@code /api/*}.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class StaticHandler implements HttpHandler {

    /** Папка ресурсов со статикой. */
    public static final String RESOURCE_ROOT = "/web/";

    /** Политика безопасности содержимого для страниц клиента. */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

    /** Типы содержимого по расширению. */
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"));

    private final ServerLog log;

    /**
     * Создаёт обработчик.
     *
     * @param log журнал сервера
     */
    public StaticHandler(ServerLog log) {
        this.log = log;
    }

    /** Отдаёт только публичные ресурсы клиента и общие значки ядра с поддержкой GET и HEAD. */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            boolean head = "HEAD".equals(method);
            if (!"GET".equals(method) && !head) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                HttpUtil.send(exchange, 405, "text/plain; charset=utf-8", bytes(Texts.get("app.http.unsupportedMethod")), false);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/favicon.png".equals(path)) {
                HttpUtil.send(exchange, 200, "image/png", UiIcons.applicationPng(), head);
                return;
            }
            if ("/app/icons.js".equals(path)) {
                String module = "export const icons = Object.freeze("
                        + ru.cashprediction.core.ui.json.UiJson.write(UiIcons.manifest()) + ");\n";
                HttpUtil.send(exchange, 200, "text/javascript; charset=utf-8", bytes(module), head);
                return;
            }
            if (path.startsWith("/app/icons/")) {
                String filename = path.substring("/app/icons/".length());
                var icon = UiIcons.resource(filename);
                if (icon.isPresent()) {
                    HttpUtil.send(exchange, 200, contentType(filename), icon.get(), head);
                } else {
                    HttpUtil.send(exchange, 404, "text/plain; charset=utf-8", bytes(Texts.get("app.http.notFound", path)), head);
                }
                return;
            }
            if ("/app/tokens.css".equals(path)) {
                HttpUtil.send(exchange, 200, "text/css; charset=utf-8", bytes(TokenCss.webCss()), head);
                return;
            }
            String name = resourceName(path);
            if (name == null) {
                HttpUtil.send(exchange, 404, "text/plain; charset=utf-8", bytes(Texts.get("app.http.notFound", path)), head);
                return;
            }
            try (InputStream in = StaticHandler.class.getResourceAsStream(RESOURCE_ROOT + name)) {
                if (in == null) {
                    HttpUtil.send(exchange, 404, "text/plain; charset=utf-8", bytes(Texts.get("app.http.notFound", path)), head);
                    return;
                }
                byte[] body = in.readAllBytes();
                if (name.equals("index.html")) {
                    String html = new String(body, StandardCharsets.UTF_8);
                    html = html.replace("{{alert.info.title}}", escapeHtml(UiText.get("alert.info.title")));
                    Map<String, String> texts = new java.util.LinkedHashMap<>();
                    for (String key : UiText.keys()) {
                        if (key.startsWith("offline.")) {
                            texts.put(key, UiText.get(key));
                            html = html.replace("{{" + key + "}}", escapeHtml(UiText.get(key)));
                        }
                    }
                    // Инертный template разрешён CSP: здесь данные общего каталога, без выполняемого скрипта.
                    String template = "<template id=\"cp-prebootstrap-texts\">"
                            + escapeHtml(ru.cashprediction.core.ui.json.UiJson.write(texts)) + "</template>";
                    html = html.replace("<body>", "<body>" + template);
                    body = bytes(html);
                }
                if (name.endsWith(".html")) {
                    exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
                }
                HttpUtil.send(exchange, 200, contentType(name), body, head);
            }
        } catch (IOException e) {
            // Чаще всего браузер просто закрыл вкладку во время загрузки.
            log.error(Texts.get("app.http.staticFailure"), e);
        }
    }

    /**
     * Превращает путь запроса в имя ресурса внутри {@link #RESOURCE_ROOT}.
     *
     * @param path путь запроса
     * @return имя ресурса или {@code null}, если путь недопустим (выход за пределы папки, скрытые файлы)
     */
    static String resourceName(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "index.html";
        }
        String name = path.startsWith("/") ? path.substring(1) : path;
        if (name.endsWith("/")) {
            name = name + "index.html";
        }
        // Запрещаем «..», обратные косые и скрытые файлы: ресурсы jar нельзя покинуть, но лишний доступ не нужен.
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.startsWith(".") || segment.contains("\\") || segment.contains(":")) {
                return null;
            }
        }
        return name;
    }

    /**
     * Тип содержимого по расширению файла.
     *
     * @param name имя файла
     * @return тип; {@code application/octet-stream} для неизвестных
     */
    static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Экранирует общие тексты перед подстановкой в HTML. */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
