package ru.cashprediction.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Отдаёт статические файлы тонкого клиента из ресурсов jar: {@code /} → {@code web/index.html},
 * {@code /app.js} → {@code web/app.js} и т. д.
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

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            boolean head = "HEAD".equals(method);
            if (!"GET".equals(method) && !head) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                HttpUtil.send(exchange, 405, "text/plain; charset=utf-8", bytes("Метод не поддерживается"), false);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String name = resourceName(path);
            if (name == null) {
                HttpUtil.send(exchange, 404, "text/plain; charset=utf-8", bytes("Не найдено: " + path), head);
                return;
            }
            try (InputStream in = StaticHandler.class.getResourceAsStream(RESOURCE_ROOT + name)) {
                if (in == null) {
                    HttpUtil.send(exchange, 404, "text/plain; charset=utf-8", bytes("Не найдено: " + path), head);
                    return;
                }
                byte[] body = in.readAllBytes();
                if (name.endsWith(".html")) {
                    exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
                }
                HttpUtil.send(exchange, 200, contentType(name), body, head);
            }
        } catch (IOException e) {
            // Чаще всего браузер просто закрыл вкладку во время загрузки.
            log.error("Не удалось отдать статический файл", e);
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
}
