package ru.cashprediction.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Таблица маршрутов API: метод + шаблон пути ({@code /api/rules/{id}}) → обработчик.
 *
 * <p>Шаблон состоит из сегментов; сегмент в фигурных скобках совпадает с любым непустым сегментом пути
 * и попадает в параметры запроса. Маршруты проверяются в порядке добавления, поэтому точные пути
 * ({@code /api/what-if/apply}) нужно добавлять раньше шаблонных, если они пересекаются.</p>
 *
 * <p>Таблица заполняется один раз при создании сервера и дальше только читается — потокобезопасна в этом режиме.</p>
 */
public final class Router {

    /** Обработчик маршрута. */
    @FunctionalInterface
    public interface Handler {
        /**
         * Обрабатывает запрос.
         *
         * @param request запрос
         * @return ответ
         * @throws IOException при ошибке ввода-вывода (ответ 500)
         */
        ApiResponse handle(ApiRequest request) throws IOException;
    }

    /**
     * Маршрут.
     *
     * @param method   HTTP-метод
     * @param template шаблон пути
     * @param segments сегменты шаблона
     * @param handler  обработчик
     */
    private record Route(String method, String template, String[] segments, Handler handler) {
    }

    /**
     * Найденный маршрут.
     *
     * @param handler обработчик
     * @param params  параметры пути
     */
    public record Match(Handler handler, Map<String, String> params) {
    }

    private final List<Route> routes = new ArrayList<>();

    /**
     * Добавляет маршрут.
     *
     * @param method   HTTP-метод ({@code GET}, {@code POST}, {@code PUT}, {@code DELETE})
     * @param template шаблон пути
     * @param handler  обработчик
     * @return этот же маршрутизатор (для цепочки вызовов)
     */
    public Router add(String method, String template, Handler handler) {
        routes.add(new Route(method, template, split(template), Objects.requireNonNull(handler, "handler")));
        return this;
    }

    /**
     * Ищет маршрут для запроса.
     *
     * @param method HTTP-метод
     * @param path   путь без строки запроса
     * @return совпадение
     * @throws ApiException 404, если путь неизвестен; 405, если путь известен, но метод другой
     */
    public Match find(String method, String path) {
        String[] parts = split(path);
        TreeSet<String> allowed = new TreeSet<>();
        for (Route route : routes) {
            Map<String, String> params = match(route.segments(), parts);
            if (params == null) {
                continue;
            }
            // HEAD обслуживается как GET: браузеры и утилиты иногда проверяют ссылку скачивания HEAD-запросом.
            if (route.method().equals(method) || ("HEAD".equals(method) && "GET".equals(route.method()))) {
                return new Match(route.handler(), params);
            }
            allowed.add(route.method());
        }
        if (!allowed.isEmpty()) {
            throw new ApiException(405, "Метод " + method + " не поддерживается для " + path
                    + " (допустимо: " + String.join(", ", allowed) + ")", Map.of("allowed", new ArrayList<Object>(allowed)));
        }
        throw ApiException.notFound("Неизвестный адрес API: " + path);
    }

    /** @return шаблоны всех маршрутов вида {@code GET /api/state} (для журнала и документации) */
    public List<String> describe() {
        return routes.stream().map(r -> r.method() + " " + r.template()).toList();
    }

    private static Map<String, String> match(String[] template, String[] parts) {
        if (template.length != parts.length) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < template.length; i++) {
            String t = template[i];
            if (t.startsWith("{") && t.endsWith("}")) {
                params.put(t.substring(1, t.length() - 1), HttpUtil.decode(parts[i]));
            } else if (!t.equals(parts[i])) {
                return null;
            }
        }
        return params;
    }

    private static String[] split(String path) {
        return java.util.Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
}
