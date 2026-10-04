package ru.cashprediction.parity.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.parity.launch.ReactorLayout;

/** Явно включаемая CDP-проба физических ресурсов, размеров и логических значений настоящего Web. */
class SharedIconBrowserContractTest {
    /** Загружает настоящую страницу; исходники адаптера не считаются доказательством DOM-семантики. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void actualDomResourcesAndRadioDumpKeepSharedIconContract() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("parity.realClients"), "Enable parity.realClients");
        Assumptions.assumeTrue(Boolean.getBoolean("parity.sharedIcons"), "Enable parity.sharedIcons");
        try (var run = WebAllowanceSession.openIconProof(ReactorLayout.fromSystemProperties(), 1200)) {
            assertTrue(Files.isRegularFile(run.output.resolve("icon-probe.json")));
            assertFalse(Files.exists(run.output.resolve("probe.json")),
                    "Icon evidence must not enter the closed allowance observation set");
            verifyRoutes(run);
            // Мастер использует валютный значок: проверяем PNG до закрытия первой реальной формы.
            verifyImages(run, "wizard", 26);
            run.execute(new SelfTestCommand.Cancel("last"));
            run.execute(new SelfTestCommand.Sample());
            verifyRadioMenu(run);
            run.execute(new SelfTestCommand.Menu("edit.addOneTime"));
            run.cdp.waitFor("!!document.querySelector('dialog[data-kind=ONE_TIME_EDITOR][open]')",
                    WebAllowanceSession.TIMEOUT);
            for (String value : List.of("INCOME", "EXPENSE")) {
                String prose = "note \u21b6 \u0394 125 \u20bd";
                run.execute(new SelfTestCommand.Fill("last", Map.of("kind", value, "title", prose, "note", prose)));
                var dump = run.dump("shared-icons-radio-" + value);
                var windows = dump.windows().stream().filter(w -> w.type().equals("ONE_TIME_EDITOR")).toList();
                assertEquals(1, windows.size());
                var fields = windows.getFirst().fields();
                assertEquals(value, fields.stream().filter(f -> f.id().equals("kind")).findFirst().orElseThrow().text());
                for (String id : List.of("title", "note")) {
                    assertEquals(prose, fields.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow().text());
                }
                Map<?, ?> radio = observation(run, "radio-" + value, RADIO);
                assertEquals(List.of("INCOME", "EXPENSE"), radio.get("values"));
                assertEquals(value, radio.get("selected"));
                assertEquals("kind", radio.get("fieldId"));
                assertFalse(((String) radio.get("background")).equals("none"));
                String background = (String) radio.get("background");
                assertTrue(UiIcons.manifest().values().stream()
                        .filter(path -> path.matches("/app/icons/dot(?:-[a-z_]+)?\\.png"))
                        .anyMatch(background::contains), background);
                verifyImages(run, "form", 26);
            }
            run.execute(new SelfTestCommand.Cancel("last"));
            run.execute(new SelfTestCommand.Menu("help.about"));
            run.cdp.waitFor("!!document.querySelector('dialog[data-purpose=about][open]')", WebAllowanceSession.TIMEOUT);
            verifyImages(run, "alert", 30);
            Files.writeString(run.output.resolve("shared-icons-result.txt"), "PASS\n");
        }
    }

    /** Проверяет каждый ответ настоящего маршрута и манифест сервера относительно байтов ядра. */
    private static void verifyRoutes(WebAllowanceSession run) throws Exception {
        Object observed = run.cdp.evaluate("""
                /** Загружает физические маршруты общего манифеста через настоящий сервер. */
                (async function() {
                  const {icons} = await import('/app/icons.js');
                  const resources = [];
                  for (const path of new Set(Object.values(icons))) {
                    const response = await fetch(path);
                    const bytes = new Uint8Array(await response.arrayBuffer());
                    let binary = '';
                    for (const byte of bytes) binary += String.fromCharCode(byte);
                    resources.push({path, status: response.status, mime: response.headers.get('Content-Type'), bytes: btoa(binary)});
                  }
                  return {icons, resources};
                })()
                """);
        assertInstanceOf(Map.class, observed);
        Map<?, ?> result = (Map<?, ?>) observed;
        Files.writeString(run.output.resolve("shared-icons-http.json"), UiJson.write(result));
        assertEquals(UiIcons.manifest(), result.get("icons"));
        List<?> resources = (List<?>) result.get("resources");
        assertEquals(UiIcons.manifest().values().stream().distinct().count(), resources.size());
        for (Object item : resources) {
            Map<?, ?> response = (Map<?, ?>) item;
            String path = (String) response.get("path");
            assertEquals(200L, response.get("status"), path);
            assertEquals("image/png", response.get("mime"), path);
            assertArrayEquals(UiIcons.resource(path.substring("/app/icons/".length())).orElseThrow(),
                    Base64.getDecoder().decode((String) response.get("bytes")), path);
        }
    }

    /** Читает загруженные изображения открытого окна и видимые строчные значки тулбара. */
    private static void verifyImages(WebAllowanceSession run, String stage, int headerSize) throws Exception {
        Map<?, ?> result = observation(run, stage, IMAGES);
        List<?> headers = (List<?>) result.get("headers");
        List<?> inline = (List<?>) result.get("inline");
        assertFalse(headers.isEmpty(), stage);
        assertFalse(inline.isEmpty(), stage);
        for (var group : Map.of(headerSize, headers, 16, inline).entrySet()) {
            for (Object item : group.getValue()) {
                Map<?, ?> image = (Map<?, ?>) item;
                assertEquals(true, image.get("loaded"), image.toString());
                assertEquals((long) group.getKey(), image.get("width"), image.toString());
                assertEquals((long) group.getKey(), image.get("height"), image.toString());
                String base = UiIcons.manifest().get(image.get("key"));
                assertNotNull(base, image.toString());
                String basename = base.substring("/app/icons/".length(), base.length() - 4);
                assertTrue(List.of("accent", "whatif", "expense", "income", "text_primary", "text_muted",
                        "warn", "tooltip_text", "text_past").stream().anyMatch(color ->
                                java.util.Objects.equals(UiIcons.manifest().get(basename + "-" + color), image.get("path"))),
                        image.toString());
                assertEquals(image.get("semantic"), image.get("aria"), image.toString());
            }
        }
    }

    /** Радио-меню рисует точку, но дамп и скрытая подпись сохраняют ID и логическую галочку. */
    private static void verifyRadioMenu(WebAllowanceSession run) throws Exception {
        Map<?, ?> result = observation(run, "radio-menu", """
                /** Читает галочку дампа отдельно от физического значка радио-пункта. */
                (async function() {
                  const {menu} = await import('/app/dump.js');
                  const node = document.querySelector('.menu-node[data-cp-id="view.table"]');
                  const image = node.querySelector(':scope > button > .menu-mark img.shared-icon');
                  await image.decode();
                  return {dump: menu(node), key: image.dataset.iconKey,
                    path: new URL(image.currentSrc).pathname, semantic: image.parentElement.textContent,
                    checked: node.querySelector(':scope > button').getAttribute('aria-checked')};
                })()
                """);
        Map<?, ?> dump = (Map<?, ?>) result.get("dump");
        assertEquals("view.table", dump.get("id"));
        assertEquals("Radio", dump.get("kind"));
        assertEquals(true, dump.get("checked"));
        assertEquals("true", result.get("checked"));
        assertEquals("\u25cf", result.get("key"));
        assertEquals("\u2713", result.get("semantic"));
        assertEquals(UiIcons.manifest().get("dot-text_primary"), result.get("path"));
        assertNotEquals("\u25cf", dump.get("text"));
        assertNotEquals("\u2713", dump.get("text"));
    }

    /** Сохраняет самостоятельное наблюдение DOM для диагностики неуспешной пробы. */
    private static Map<?, ?> observation(WebAllowanceSession run, String name, String expression) throws Exception {
        Object value = run.cdp.evaluate(expression);
        assertInstanceOf(Map.class, value);
        Files.writeString(run.output.resolve("shared-icons-dom-" + name + ".json"), UiJson.write(value));
        return (Map<?, ?>) value;
    }

    private static final String RADIO = """
            /** Читает физические переключатели и их логические значения. */
            (function() {
              const form = document.querySelector('dialog[data-kind=ONE_TIME_EDITOR][open]');
              const field = form.querySelector('[data-cp-id=kind][data-kind=RADIO]');
              const inputs = [...field.querySelectorAll('input[type=radio]')];
              const selected = inputs.find(/** Возвращает выбранный переключатель. */ input => input.checked);
              return {fieldId: field.dataset.cpId, values: inputs.map(/** Читает логический код варианта. */ input => input.value),
                selected: selected.value, background: getComputedStyle(selected).backgroundImage};
            })()
            """;

    private static final String IMAGES = """
            /** Читает загруженные изображения открытого окна и тулбара. */
            (async function() {
              const headers = [...document.querySelectorAll('dialog[open] .window-glyph img.shared-icon')];
              const inline = [...document.querySelectorAll('#toolbar img.shared-icon')].filter(
                /** Исключает закрытые меню из измерений. */ image => image.getBoundingClientRect().width > 0);
              /** Дожидается загрузки и читает физический маршрут, размеры и доступную подпись. */
              async function read(image) {
                await image.decode();
                const parent = image.parentElement;
                const rect = image.getBoundingClientRect();
                return {key: image.dataset.iconKey, path: new URL(image.currentSrc).pathname,
                  width: rect.width, height: rect.height, loaded: image.complete && image.naturalWidth > 0,
                  semantic: parent.dataset.semanticText, aria: parent.getAttribute('aria-label')};
              }
              return {headers: await Promise.all(headers.map(read)), inline: await Promise.all(inline.map(read))};
            })()
            """;
}
