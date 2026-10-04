package ru.cashprediction.parity.browserprobe;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.parity.launch.ReactorLayout;

/** Отдельный браузерный gate ревизий настоящего WebServer после живого resync. */
@EnabledIfSystemProperty(named = "parity.revisionProof", matches = "true")
class WebRevisionResyncBrowserIT {
    /** Требует свежий полный отчёт JDK CDP, принятую сервером правку и монотонность отправок. */
    @Test
    @Timeout(180)
    void revisionsSurviveLiveFormRecreation(@TempDir Path output) throws Exception {
        Path root = ReactorLayout.fromSystemProperties().root();
        Path report = output.resolve("revision-result.json");
        assertFalse(Files.exists(report));
        Path tests = root.resolve("web/target/test-classes");
        assertTrue(Files.isRegularFile(tests.resolve("ru/cashprediction/web/js/BrowserFixtureProbe.class")),
                "Build Web tests before the browser gate");
        // Исходные ресурсы идут первыми: сервер отдаёт проверяемый JS, а не старую копию из сборки.
        try (var loader = new URLClassLoader(new java.net.URL[] {
                root.resolve("web/src/main/resources").toUri().toURL(), tests.toUri().toURL(),
                root.resolve("web/target/classes").toUri().toURL()
        }, getClass().getClassLoader())) {
            try {
                loader.loadClass("ru.cashprediction.web.js.BrowserFixtureProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[] {root.toString(), output.toString(), "revision-only"});
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }
        assertTrue(Files.isRegularFile(report), "Fresh browser evidence required");
        var result = JsonParser.parseObject(Files.readString(report));
        assertEquals(16, ((Number) result.get("checks")).intValue());
        List<?> completed = assertInstanceOf(List.class, result.get("completedChecks"));
        assertEquals(16, completed.size(), "Every check must have completed before report publication");
        assertEquals(16, completed.stream().distinct().count(), "Check evidence must not repeat");
        for (Object item : completed) assertFalse(assertInstanceOf(String.class, item).isBlank());
        assertEquals(true, result.get("echoSafe"));
        assertEquals(true, result.get("pendingLocal"));
        assertEquals(true, result.get("recreated"));
        assertEquals("Latest local edit", result.get("value"));
        String tab = assertInstanceOf(String.class, result.get("tab"));
        String windowId = assertInstanceOf(String.class, result.get("windowId"));
        assertFalse(tab.isBlank());
        assertFalse(windowId.isBlank());
        assertEquals(1003L, assertInstanceOf(Number.class, result.get("clientRev")).longValue());
        List<?> revisions = assertInstanceOf(List.class, result.get("revisions"));
        List<?> requests = assertInstanceOf(List.class, result.get("requests"));
        List<String> texts = List.of("High revision", "After resync", "Delayed echo", "Latest local edit");
        assertEquals(4, revisions.size(), "Exactly four real field inputs must send revisions");
        assertEquals(4, requests.size(), "Evidence must include every actual HTTP field input");
        for (int index = 0; index < texts.size(); index++) {
            long expected = 1000L + index;
            assertEquals(expected, assertInstanceOf(Number.class, revisions.get(index)).longValue());
            var request = Json.asObject(requests.get(index), "request");
            assertEquals(tab, request.get("tab"));
            assertTrue(assertInstanceOf(Number.class, request.get("afterSeq")).longValue() >= 0);
            var intent = Json.object(request, "intent");
            assertEquals("formField", intent.get("type"));
            assertEquals(windowId, intent.get("windowId"));
            assertEquals("name", intent.get("fieldId"));
            assertEquals(false, intent.get("committed"));
            assertEquals(texts.get(index), intent.get("raw"));
            assertEquals(expected, assertInstanceOf(Number.class, intent.get("clientRev")).longValue());
        }
        // Эхо содержит настоящую серверную модель и контекст той же вкладки и формы.
        long highRevision = assertServerEcho(result.get("highEcho"), tab, windowId, 1000, "High revision");
        long heldRevision = assertServerEcho(result.get("heldEcho"), tab, windowId, 1002, "Delayed echo");
        assertTrue(heldRevision > highRevision, "Held echo must originate after the initial server view");
    }

    /** Проверяет происхождение сохранённого HTTP-эффекта и возвращает ревизию серверной модели. */
    private static long assertServerEcho(Object evidence, String tab, String windowId, long clientRev, String text) {
        var effect = Json.asObject(evidence, "echo");
        assertEquals("form.view", effect.get("type"));
        assertEquals(windowId, effect.get("windowId"));
        assertTrue(assertInstanceOf(Number.class, effect.get("seq")).longValue() > 0);
        var echo = Json.object(effect, "echoOf");
        assertEquals(tab, echo.get("tab"));
        assertEquals(clientRev, assertInstanceOf(Number.class, echo.get("clientRev")).longValue());
        var view = Json.object(effect, "view");
        assertEquals(text, Json.object(Json.object(view, "fields"), "name").get("value"));
        return assertInstanceOf(Number.class, view.get("revision")).longValue();
    }
}
