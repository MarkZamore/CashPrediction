package ru.cashprediction.parity.driver;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.*;

/** Принимает эффекты test.step и возвращает реальные результаты в закрытый тестовый API web. */
public final class TestApiBridge {
    /** Канал отправки, подменяемый в тесте без сервера и браузера. */
    @FunctionalInterface public interface Sender {
        /** Отправляет JSON на относительный маршрут тестового API. */
        void post(String route, Map<String, Object> body) throws Exception;
    }
    private final UiTestDriver driver;
    private final Sender sender;
    private long last = -1;

    /** Создаёт последовательный мост; его вызывает получатель эффектов S3. */
    public TestApiBridge(UiTestDriver driver, Sender sender) {
        this.driver = Objects.requireNonNull(driver); this.sender = Objects.requireNonNull(sender);
    }

    /** Создаёт JDK HTTP канал только для локального сервера; не принимает перенаправления. */
    public static Sender http(URI base) {
        if (!"http".equals(base.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost()))
            throw new IllegalArgumentException("Test API must be loopback HTTP");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (route, body) -> {
            if (!Set.of("/api/test/result", "/api/test/dump").contains(route)) throw new IllegalArgumentException(route);
            var request = HttpRequest.newBuilder(base.resolve(route)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(UiJson.write(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 204)
                throw new IllegalStateException("Test API " + route + " HTTP " + response.statusCode());
        };
    }

    /** Выполняет ровно один новый шаг; ошибка операции отправляется как FAIL, ошибка транспорта выходит наружу. */
    public synchronized void accept(Map<String, Object> effect) throws Exception {
        if (!"test.step".equals(Json.requireString(effect, "type"))) throw new IllegalArgumentException("Not test.step");
        long n = Json.requireLong(effect, "n");
        if (n < 0 || n <= last) throw new IllegalArgumentException("Duplicate or out-of-order test step: " + n);
        last = n;
        String message = "";
        boolean ok = false;
        Map<String, Object> dump = null;
        try {
            SelfTestScript script = SelfTestScript.parse("test-api", Json.requireString(effect, "command"));
            if (script.lines().size() != 1) throw new IllegalArgumentException("Expected one command");
            SelfTestCommand command = script.lines().getFirst().command();
            if (command instanceof SelfTestCommand.Dump d) {
                driver.awaitIdle(Duration.ofSeconds(5));
                dump = Map.of("n", n, "dump", UiJson.toTree(driver.dump(d.step())));
            } else {
                driver.execute(command);
                driver.awaitIdle(Duration.ofSeconds(5));
            }
            ok = true;
        } catch (Exception e) { message = e.getClass().getSimpleName() + ": " + e.getMessage(); }
        if (dump != null) sender.post("/api/test/dump", dump);
        sender.post("/api/test/result", Map.of("n", n, "ok", ok, "message", message));
    }
}
