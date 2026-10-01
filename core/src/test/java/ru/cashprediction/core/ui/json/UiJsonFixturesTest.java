package ru.cashprediction.core.ui.json;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.command.HotkeyTable;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.menu.MenuModels;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.ScreenPart;
import ru.cashprediction.core.ui.view.chart.ChartLayout;
import ru.cashprediction.core.ui.view.status.StatusBuilder;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.ui.view.table.LazyTableModel;

/**
 * Фиксирует полный bootstrap и все эффекты S2 для разработки web-клиента без запуска интерфейса.
 * Эталоны строятся из настоящих моделей на фиксированном AppState, без файлов и реестра.
 * Порядок полей записей и всех упорядоченных списков сравнивается целиком; только карта texts
 * и множества scopes приводятся к алфавитному порядку, поскольку Map.copyOf/Set.copyOf его не обещают.
 */
class UiJsonFixturesTest {

    @Test
    void bootstrapMatchesWholeFixtureAndWireOrder() throws Exception {
        Map<String, Object> wire = object(JsonParser.parse(UiJson.write(bootstrap())));
        assertEquals(List.of("seq", "client", "testApi", "profile", "screen", "hotkeys", "windows",
                "overlay", "texts"), keys(wire));
        assertEquals(42L, wire.get("seq"));
        assertEquals("web", wire.get("client"));
        assertEquals(false, wire.get("testApi"));
        assertTrue(wire.containsKey("overlay"));
        assertNull(wire.get("overlay"));
        assertEquals(List.of("kind", "chooser", "nativeReplacePrompt", "toolkitVersion"),
                keys(Json.object(wire, "profile")));
        assertEquals("WEB", Json.object(wire, "profile").get("kind"));
        Map<String, Object> screen = Json.object(wire, "screen");
        assertEquals(List.of("revision", "windowTitle", "menuBar", "toolbar", "summary", "table",
                "chart", "status", "mode"), keys(screen));
        assertLazyBoundaries(screen);
        List<?> windows = Json.list(wire, "windows");
        assertEquals(List.of("form.open", "alert.open"), windows.stream()
                .map(value -> object(value).get("type")).toList());
        windows.forEach(value -> assertFalse(object(value).containsKey("seq")));
        Set<String> expectedTexts = UiText.keys().stream().filter(key -> key.startsWith(WebBootstrap.OFFLINE_PREFIX)
                || WebBootstrap.CHROME_TEXT_KEYS.contains(key)).collect(Collectors.toSet());
        assertEquals(expectedTexts, Json.object(wire, "texts").keySet());
        assertFixture("bootstrap.json", wire);
    }

    @Test
    void everySealedEffectMatchesWholeFixtureAndExactEnvelope() throws Exception {
        List<WebEffect> effects = effects();
        assertEquals(Set.of(WebEffect.class.getPermittedSubclasses()), effects.stream()
                .map(Object::getClass).collect(Collectors.toSet()));
        assertEquals(WebEffect.class.getPermittedSubclasses().length, effects.size());
        List<List<String>> fields = List.of(
                List.of("revision", "parts"), List.of("window"), List.of("windowId", "view", "echoOf"),
                List.of("windowId"), List.of("windowId"), List.of("alertId", "spec"),
                List.of("alertId", "spec"), List.of("alertId"), List.of("tab", "target", "items"),
                List.of("target"), List.of("rowId", "mode"), List.of("text"), List.of("value"),
                List.of("tab"), List.of("kind", "title", "text"), List.of("n", "command"));
        List<String> types = List.of("screen", "form.open", "form.view", "form.close", "form.front",
                "alert.open", "alert.update", "alert.close", "contextMenu", "focus", "reveal", "clipboard",
                "inert", "reload", "exit", "test.step");
        List<Map<String, Object>> wire = effectTrees();
        for (int index = 0; index < effects.size(); index++) {
            Map<String, Object> item = wire.get(index);
            List<String> expectedKeys = new ArrayList<>(List.of("seq", "type"));
            expectedKeys.addAll(fields.get(index));
            assertEquals(expectedKeys, keys(item), types.get(index));
            assertEquals(43L + index, item.get("seq"));
            assertEquals(types.get(index), item.get("type"));
            Map<String, Object> unsequenced = new LinkedHashMap<>(item);
            unsequenced.remove("seq");
            assertEquals(JsonWriter.write(unsequenced), UiJson.write(effects.get(index)));
        }
        Map<String, Object> parts = Json.object(wire.getFirst(), "parts");
        assertEquals(List.of("TITLE", "MENU", "TOOLBAR", "SUMMARY", "TABLE", "CHART", "STATUS", "MODE"),
                keys(parts));
        assertLazyBoundaries(Map.of("table", parts.get("TABLE"), "chart", parts.get("CHART")));
        Map<String, Object> window = Json.object(wire.get(1), "window");
        assertEquals(List.of("id", "ownerId", "modal", "placement", "spec", "view"), keys(window));
        assertEquals("w1", window.get("id"));
        assertEquals(List.of("ownerId", "bounds", "anchor"), keys(Json.object(window, "placement")));
        assertNull(Json.object(window, "placement").get("bounds"));
        assertNull(Json.object(window, "placement").get("anchor"));
        Map<String, Object> echo = Json.object(wire.get(2), "echoOf");
        assertEquals(List.of("tab", "clientRev"), keys(echo));
        assertEquals(Map.of("tab", "tab1", "clientRev", 17L), echo);
        assertEquals(List.of("kind", "cardId"), keys(Json.object(wire.get(8), "target")));
        assertEquals("card", Json.object(wire.get(8), "target").get("kind"));
        assertEquals("FILTER", wire.get(9).get("target"));
        assertEquals("SELECT_AND_SCROLL", wire.get(10).get("mode"));
        assertEquals("copy\t\"text\"\n\\", wire.get(11).get("text"));
        assertEquals("WEB_STOPPED", wire.get(14).get("kind"));
        assertFixture("effects.json", wire);
    }

    /** Проверяет точные границы ленивых моделей, включая порядок полей и явный null. */
    private static void assertLazyBoundaries(Map<String, Object> screen) {
        Map<String, Object> table = Json.object(screen, "table");
        assertEquals(List.of("revision", "columns", "rowCount", "selectedRowId", "scrollToRowId", "placeholder"),
                keys(table));
        assertTrue(((Number) table.get("rowCount")).intValue() > 0);
        assertNull(table.get("placeholder"));
        assertEquals(List.of("revision"), keys(Json.object(screen, "chart")));
    }

    /** Фиксированный снимок с примером плана и настоящим прогнозом; папка не создаётся. */
    private static AppState state() {
        AppState fake = FakeStates.withPlan(ClientProfile.web(), SamplePlan.create(FakeStates.TODAY),
                Path.of("CashMemory"));
        DocumentView document = new DocumentView(fake.document().plan(), null, false, false, "", false, "",
                ForecastEngine.forecast(fake.document().plan(), fake.view().whatIf(), fake.today(),
                        fake.view().showSkipped()), "", List.of());
        return new AppState(7, fake.profile(), fake.today(), fake.cashMemory(), fake.plansFolder(), document,
                fake.view(), "", false, fake.settings(), fake.recorder(), fake.stores(), fake.windows(),
                fake.status(), "");
    }

    /** Строит все области экрана теми же построителями, что и контроллер. */
    private static MainScreenModel screen() {
        AppState state = state();
        // JavaFX: MenuBar → Swing: JMenuBar → Web: nav
        return new MainScreenModel(7, UiText.get("main.title", state.document().plan().name()),
                MenuModels.menuBar(state, state.client()), MenuModels.toolbar(state, state.client()),
                SummaryBuilder.build(state), LazyTableModel.build(state, 7), ChartLayout.model(state, 7),
                StatusBuilder.build(state, Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC), state.view().mode());
    }

    /** Открытая настоящая форма переименования с непустым значением и владельцем. */
    private static WebEffect.FormOpen formOpen() {
        // JavaFX: Dialog → Swing: JDialog → Web: dialog
        FormLogic logic = TextInputForms.rename();
        FormContext context = new FormContext("w1", "main", Map.of(), state());
        return new WebEffect.FormOpen("w1", "main", true, Placement.centered("main"), logic.spec(context),
                logic.evaluate(new FormState(0, logic.defaults(context)), context));
    }

    /** Полный ответ загрузки, включая открытые окна, горячие клавиши и каталог оформления. */
    private static WebBootstrap bootstrap() {
        Map<String, String> texts = new TreeMap<>();
        UiText.keys().stream().filter(key -> key.startsWith(WebBootstrap.OFFLINE_PREFIX)
                || WebBootstrap.CHROME_TEXT_KEYS.contains(key)).forEach(key -> texts.put(key, UiText.get(key)));
        // JavaFX: Alert → Swing: SwingAlert → Web: dialog
        return new WebBootstrap(42, "web", false, ClientProfile.web(), screen(), HotkeyTable.bindings(ClientKind.WEB),
                List.of(formOpen(), new WebEffect.AlertOpen("a1", AlertCatalog.unsavedChanges("Plan"))), null, texts);
    }

    /** По одному представителю каждого закрытого вида эффекта в явном порядке протокола. */
    private static List<WebEffect> effects() {
        WebEffect.FormOpen open = formOpen();
        FormView view = open.view();
        FormView updated = new FormView(12, view.page(), view.header(),
                Map.of("value", FieldView.of("Renamed plan")), view.problem(), view.buttons(), view.results(),
                view.preview(), view.details(), view.detailsExpanded());
        // JavaFX: Alert → Swing: SwingAlert → Web: dialog
        var alert = AlertCatalog.unsavedChanges("Plan");
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu]
        var target = new ContextTarget.Card("m3");
        return List.of(new WebEffect.Screen(screen(), EnumSet.allOf(ScreenPart.class)), open,
                new WebEffect.FormViewUpdate("w1", updated, "tab1", 17), new WebEffect.FormClose("w1"),
                new WebEffect.FormFront("w1"), new WebEffect.AlertOpen("a1", alert),
                new WebEffect.AlertUpdate("a1", AlertCatalog.unsavedChanges("Renamed plan")),
                new WebEffect.AlertClose("a1"),
                new WebEffect.ContextMenu("tab1", target, MenuModels.contextMenu(state(), target, ClientKind.WEB)),
                new WebEffect.Focus(FocusTarget.FILTER), new WebEffect.Reveal("r1@2026-10-05", RevealMode.SELECT_AND_SCROLL),
                new WebEffect.Clipboard("copy\t\"text\"\n\\"), new WebEffect.Inert(true), new WebEffect.Reload("tab1"),
                new WebEffect.Exit(ExitKind.WEB_STOPPED, UiText.get("main.title", "Plan"), ""),
                new WebEffect.TestStep(3, "menu file.save"));
    }

    /** Использует сериализатор журнала с возрастающими номерами, а не ручные карты эффекта. */
    private static List<Map<String, Object>> effectTrees() {
        List<WebEffect> effects = effects();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < effects.size(); index++) result.add(UiJson.effect(43L + index, effects.get(index)));
        return result;
    }

    /** Сравнивает полный JSON, сохраняя порядок полей, массивов и все значения. */
    private static void assertFixture(String name, Object actual) throws Exception {
        try (var stream = UiJsonFixturesTest.class.getResourceAsStream("/ui-json/" + name)) {
            assertNotNull(stream, name);
            Object expected = JsonParser.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(JsonWriter.write(expected), JsonWriter.write(stable(actual)), name);
        }
    }

    /** Упорядочивает лишь неупорядоченные коллекции, не меняя порядок полей записей. */
    private static Object stable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put((String) key, switch ((String) key) {
                case "texts" -> new TreeMap<>(object(item));
                case "scopes" -> ((List<?>) item).stream().map(Object::toString).sorted().toList();
                default -> stable(item);
            }));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(UiJsonFixturesTest::stable).toList();
        return value;
    }

    /** Читает объект через мини-JSON ядра. */
    private static Map<String, Object> object(Object value) { return Json.asObject(value, "fixture"); }

    /** Возвращает ключи в фактическом порядке записи. */
    private static List<String> keys(Map<String, Object> value) { return List.copyOf(value.keySet()); }

    /**
     * Печатает воспроизводимую фикстуру в stdout без записи файлов: аргумент bootstrap или effects.
     * После изолированной компиляции javac запустить этот класс с тестовыми и основными ресурсами в classpath;
     * вывод применяется к соответствующему ресурсу отдельно, тесты ничего не перезаписывают.
     *
     * @param args имя фикстуры
     */
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("bootstrap or effects");
        Object tree = switch (args[0]) {
            case "bootstrap" -> JsonParser.parse(UiJson.write(bootstrap()));
            case "effects" -> effectTrees();
            default -> throw new IllegalArgumentException("bootstrap or effects");
        };
        System.out.println(JsonWriter.writePretty(stable(tree)));
    }
}
