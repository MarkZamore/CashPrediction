package ru.cashprediction.core.ui.json;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.dump.DumpDiff;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.view.*;
import ru.cashprediction.core.ui.view.chart.*;
import ru.cashprediction.core.ui.view.popup.DayCardModel;
import ru.cashprediction.core.ui.view.status.StatusModel;
import ru.cashprediction.core.ui.view.summary.SummaryModel;
import ru.cashprediction.core.ui.view.table.*;

/** Проверяет весь закрытый протокол по независимым JSON-фикстурам и граничным входным данным. */
class UiJsonTest {

    @Test
    void allIntentsRoundTripAgainstWireFixtures() throws Exception {
        Set<Class<?>> covered = new java.util.HashSet<>();
        for (Map<String, Object> fixture : fixtures("intents.json")) {
            WebIntent intent = UiJson.readIntent(fixture);
            covered.add(intent.getClass());
            assertEquals(List.of(), DumpDiff.diff(fixture, JsonParser.parse(UiJson.write(intent)), 0), intent.type());
        }
        assertEquals(Set.of(WebIntent.class.getPermittedSubclasses()), covered);
        assertEquals(new WebIntent.SpinnerCommit("whatIf.extra", 9007199254740993L),
                UiJson.readIntent(fixtures("intents.json").get(6)));
    }

    @Test
    void allQueriesAndContextTargetsRoundTripAgainstWireFixtures() throws Exception {
        Set<Class<?>> queries = new java.util.HashSet<>();
        Set<Class<?>> targets = new java.util.HashSet<>();
        for (Map<String, Object> fixture : fixtures("queries.json")) {
            WebQuery query = UiJson.readQuery(fixture);
            queries.add(query.getClass());
            if (query instanceof WebQuery.ContextMenu context) targets.add(context.target().getClass());
            assertEquals(List.of(), DumpDiff.diff(fixture, JsonParser.parse(UiJson.write(query)), 0), query.type());
        }
        assertEquals(Set.of(WebQuery.class.getPermittedSubclasses()), queries);
        assertEquals(Set.of(ContextTarget.class.getPermittedSubclasses()), targets);
        assertEquals(new WebQuery.Calendar(YearMonth.of(2026, 10), LocalDate.of(2026, 10, 5)),
                UiJson.readQuery(fixtures("queries.json").getLast()));
        assertEquals(new WebQuery.Calendar(YearMonth.of(2026, 10), null),
                UiJson.readQuery(Map.of("type", "calendar", "month", "2026-10")));
    }

    @Test
    void malformedRequestsCannotBecomeValidActions() {
        List<String> intents = List.of(
                "{\"type\":\"unknown\"}",
                "{\"type\":\"command\",\"command\":\"FILE_SAVE\",\"source\":\"MENU\"}",
                "{\"type\":\"command\",\"command\":\"file.save\",\"source\":\"SELFTEST\"}",
                "{\"type\":\"formField\",\"windowId\":\"w1\",\"fieldId\":\"amount\",\"raw\":\"x\",\"clientRev\":1}",
                "{\"type\":\"sliderCommit\",\"itemId\":\"x\",\"value\":2147483648}",
                "{\"type\":\"sliderCommit\",\"itemId\":\"x\",\"value\":1.5}",
                "{\"type\":\"formPreview\",\"windowId\":\"w1\",\"index\":-1,\"activated\":true}",
                "{\"type\":\"formBounds\",\"windowId\":\"w1\",\"bounds\":{\"x\":0,\"y\":0,\"width\":1}}",
                "{\"type\":\"activateRow\",\"rowId\":\"r1\",\"columnId\":\"income\",\"how\":\"UNKNOWN\"}");
        for (String json : intents) assertThrows(IllegalArgumentException.class, () -> UiJson.readIntent(parse(json)), json);
        for (String json : List.of(
                "{\"type\":\"rows\",\"rev\":0,\"from\":0,\"count\":301}",
                "{\"type\":\"rows\",\"rev\":-1,\"from\":0,\"count\":1}",
                "{\"type\":\"rows\",\"rev\":0,\"from\":-1,\"count\":1}",
                "{\"type\":\"dayCard\",\"date\":\"2026-02-30\"}",
                "{\"type\":\"calendar\",\"month\":\"2026-13\"}",
                "{\"type\":\"contextMenu\",\"target\":{\"kind\":\"Row\",\"rowId\":\"r1\"}}",
                "{\"type\":\"chartScene\",\"rev\":1,\"w\":1e999,\"h\":100}")) {
            assertThrows(IllegalArgumentException.class, () -> UiJson.readQuery(parse(json)), json);
        }
        assertThrows(IllegalArgumentException.class, () -> UiJson.readQuery(
                Map.of("type", "chartHover", "rev", 1, "x", Double.NaN, "y", 0, "w", 100, "h", 100)));
    }

    @Test
    void everyEffectHasItsSpecifiedEnvelope() {
        FormSpec spec = new FormSpec("rename", WindowType.TEXT_INPUT, "rename", Presentation.TEXT_INPUT,
                "title", "", 460, true, false, true, List.of(), List.of(), "ok");
        FormView view = new FormView(12, 0, "header", Map.of("amount", new FieldView("80000,00", true, true, false,
                null, null, null, null, null)),
                Problem.NONE, Map.of(), List.of(), List.of(), "details", false);
        AlertSpec alert = new AlertSpec(AlertKind.INFORMATION, "about", "", "title", "", "header", "body", "",
                false, 460, List.of(), "ok", false);
        List<WebEffect> effects = List.of(
                new WebEffect.Screen(screen(), EnumSet.of(ScreenPart.TITLE, ScreenPart.TABLE)),
                new WebEffect.FormOpen("w1", "main", true, Placement.centered("main"), spec, view),
                new WebEffect.FormViewUpdate("w1", view, "tab1", 17), new WebEffect.FormClose("w1"),
                new WebEffect.FormFront("w1"), new WebEffect.AlertOpen("a1", alert),
                new WebEffect.AlertUpdate("a1", alert), new WebEffect.AlertClose("a1"),
                new WebEffect.ContextMenu("tab1", new ContextTarget.Card("m3"), List.of(new MenuNode.Separator("sep"))),
                new WebEffect.Focus(FocusTarget.FILTER), new WebEffect.Reveal("r1", RevealMode.SELECT_AND_SCROLL),
                new WebEffect.Clipboard("copy\ttext\n"), new WebEffect.Inert(true), new WebEffect.Reload("tab1"),
                new WebEffect.Exit(ExitKind.WEB_STOPPED, "title", "text"), new WebEffect.TestStep(3, "menu file.save"));
        Set<Class<?>> covered = effects.stream().map(Object::getClass).collect(Collectors.toSet());
        assertEquals(Set.of(WebEffect.class.getPermittedSubclasses()), covered);
        for (WebEffect effect : effects) {
            Map<String, Object> wire = UiJson.effect(42, effect);
            assertEquals(42L, wire.get("seq"));
            assertEquals(effect.type(), wire.get("type"));
            assertEquals(List.of(), DumpDiff.diff(wire, JsonParser.parse(ru.cashprediction.core.json.JsonWriter.write(wire)), 0));
            assertFalse(parse(UiJson.write(effect)).containsKey("seq"));
        }
        Map<String, Object> opened = UiJson.effect(1, effects.get(1));
        assertEquals(Set.of("seq", "type", "window"), opened.keySet());
        assertEquals("w1", Json.object(opened, "window").get("id"));
        assertFalse(Json.object(opened, "window").containsKey("windowId"));
        Map<String, Object> echo = Json.object(UiJson.effect(2, effects.get(2)), "echoOf");
        assertEquals(Map.of("tab", "tab1", "clientRev", 17L), echo);
        Map<String, Object> parts = Json.object(UiJson.effect(3, effects.getFirst()), "parts");
        assertEquals(Set.of("TITLE", "TABLE"), parts.keySet());
        assertEquals("title", parts.get("TITLE"));
        assertEquals(200000, Json.object(parts, "TABLE").get("rowCount"));
        assertEquals("WEB_STOPPED", UiJson.effect(4, effects.get(14)).get("kind"));
    }

    @Test
    void serializationKeepsCodesNullsAndLazyModelBoundaries() {
        MenuNode action = new MenuNode.Action("save", CommandId.FILE_SAVE, CommandArgs.NONE, "save", null, "", true);
        Map<String, Object> wire = Json.asObject(UiJson.toTree(action), "action");
        assertEquals("Action", wire.get("kind"));
        assertEquals("file.save", wire.get("command"));
        assertTrue(wire.containsKey("accel"));
        assertNull(wire.get("accel"));
        assertEquals(Set.of("revision"), Json.asObject(UiJson.toTree(screen().chart()), "chart").keySet());
        assertFalse(Json.asObject(UiJson.toTree(screen().table()), "table").containsKey("rows"));
        assertEquals("2026-10-05", UiJson.toTree(LocalDate.of(2026, 10, 5)));
        assertEquals(Path.of("example").toString(), UiJson.toTree(Path.of("example")));
        assertEquals("[null,\"text\"]", UiJson.write(Arrays.asList(null, "text")));
        assertThrows(IllegalArgumentException.class, () -> UiJson.toTree(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> UiJson.toTree(new Object()));
        assertThrows(IllegalArgumentException.class, () -> UiJson.toTree(Map.of(1, "value")));
        assertThrows(IllegalArgumentException.class, () -> UiJson.effect(-1, new WebEffect.Reload("tab")));
    }

    @Test
    void actualS1ModelsAndWizardPagesSerializeWithoutToolkitKnowledge(@TempDir Path folder) {
        AppState state = ViewStates.sample();
        TableModel table = LazyTableModel.build(state, 7);
        ChartModel chart = ChartLayout.model(state, 7);
        for (ClientKind client : ClientKind.values()) {
            assertEquals(6, Json.list(parse(UiJson.write(MenuModels.menuBar(state, client))), "menus").size());
            assertFalse(Json.list(parse(UiJson.write(MenuModels.toolbar(state, client))), "items").isEmpty());
        }
        assertEquals(8, Json.list(parse(UiJson.write(table)), "columns").size());
        assertFalse(Json.list(parse(UiJson.write(chart.layout(1200, 700))), "primitives").isEmpty());
        assertEquals(9, Json.list(parse(UiJson.write(ru.cashprediction.core.ui.view.summary.SummaryBuilder.build(state))), "cards").size());
        FormContext context = new FormContext("w1", "main", Map.of(),
                ru.cashprediction.core.app.fake.FakeStates.empty(ClientProfile.swing(), folder));
        var wizard = new ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm();
        Map<String, Object> spec = parse(UiJson.write(wizard.spec(context)));
        assertEquals(3, Json.list(spec, "pages").size());
        for (int page = 0; page < 3; page++) {
            Map<String, Object> view = parse(UiJson.write(wizard.evaluate(new FormState(page, wizard.defaults(context)), context)));
            assertEquals((long) page, view.get("page"));
            assertFalse(Json.object(view, "fields").isEmpty());
        }
    }

    private static List<Map<String, Object>> fixtures(String name) throws Exception {
        try (var stream = UiJsonTest.class.getResourceAsStream("/ui-json/" + name)) {
            assertNotNull(stream);
            return ((List<?>) JsonParser.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8)))
                    .stream().map(value -> Json.asObject(value, name)).toList();
        }
    }

    private static Map<String, Object> parse(String json) { return Json.asObject(JsonParser.parse(json), "test"); }

    /** Модели намеренно падают при попытке материализовать строки или сцену во время сериализации. */
    private static MainScreenModel screen() {
        TableModel table = new TableModel() {
            public long revision() { return 7; }
            public List<ColumnSpec> columns() { return List.of(); }
            public int rowCount() { return 200000; }
            public TableRowView row(int index) { throw new AssertionError("eager row"); }
            public String tooltip(int index, String columnId) { throw new AssertionError("eager tooltip"); }
            public int indexOf(String rowId) { throw new AssertionError("eager index"); }
            public String selectedRowId() { return ""; }
            public String scrollToRowId() { return "r1"; }
            public Placeholder placeholder() { return null; }
        };
        ChartModel chart = new ChartModel() {
            public long revision() { return 7; }
            public ChartScene layout(double w, double h) { throw new AssertionError("eager scene"); }
            public DayCardModel dayCard(LocalDate date) { throw new AssertionError("eager card"); }
            public java.util.Optional<ChartHover> hover(double x, double y, double w, double h) { throw new AssertionError("eager hover"); }
        };
        return new MainScreenModel(7, "title", new MenuBarModel(List.of()), new ToolbarModel(List.of()),
                new SummaryModel(true, List.of(), ""), table, chart, new StatusModel(List.of()), ViewMode.TABLE);
    }
}
