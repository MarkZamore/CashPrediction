package ru.cashprediction.core.ui.dump;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.text.UiText;

/** Доказывает узкую область исключений §10 и отсутствие масок для строк вне данных дампа. */
class ClosedAllowancesTest {
    /** Исключение имени клиента не скрывает дату маркера, основной текст и неизвестное имя. */
    @Test void recoveryHeaderOnlyAllowsKnownClientCaption() throws IOException {
        var allowed = AllowedDiffs.parse(resource());
        String prefix = UiText.get("s2.startup.crashed") + "\n";
        String fx = prefix + UiText.get("s2.startup.marker", "13.09.2026 12:00", UiText.get("s2.startup.clientFx"));
        String web = prefix + UiText.get("s2.startup.marker", "13.09.2026 12:00", UiText.get("client.web"));
        String pointer = "/alerts/crashRecovery/header";
        assertTrue(allowed.filter("web", List.of(new DumpDiff.Difference(pointer, fx, web))).isEmpty());
        for (String invalid : List.of(
                prefix + UiText.get("s2.startup.marker", "14.09.2026 12:00", UiText.get("client.web")),
                "different\n" + UiText.get("s2.startup.marker", "13.09.2026 12:00", UiText.get("client.web")),
                prefix + UiText.get("s2.startup.marker", "13.09.2026 12:00", "unknown"))) {
            var difference = new DumpDiff.Difference(pointer, fx, invalid);
            assertEquals(List.of(difference), allowed.filter("web", List.of(difference)));
        }
    }
    /** Исключения хранилищ ссылаются на настоящие id кнопок, а общие решения остаются проверяемыми. */
    @Test void recoveryStorageRulesUseActualButtonIds() throws IOException {
        var allowed = AllowedDiffs.parse(resource());
        for (String id : List.of("restoreRegistry", "restoreXml", "restoreServer")) {
            String pointer = "/alerts/crashRecovery/buttons/" + id;
            assertTrue(allowed.entries().stream().anyMatch(e -> e.number() == 7 && e.pointer().equals(pointer)));
            assertTrue(allowed.filter("web", List.of(new DumpDiff.Difference(pointer,
                    Map.of("id", id, "text", "button", "tooltip", "", "enabled", true,
                            "isDefault", false, "x", 0), null))).isEmpty());
        }
        var shared = new DumpDiff.Difference("/alerts/crashRecovery/buttons/noRestore/enabled", true, false);
        assertEquals(List.of(shared), allowed.filter("web", List.of(shared)));
    }

    @Test
    void resourceAccountsForExactlySixteenSpecRowsWithoutFakeMasks() throws IOException {
        var root = (Map<?, ?>) JsonParser.parse(resource());
        AllowedDiffs allowed = AllowedDiffs.parse(resource());
        Set<Integer> rows = new TreeSet<>();
        for (var entry : allowed.entries()) {
            rows.add(entry.number());
            assertEquals("§10 №" + entry.number(), entry.specSection());
            assertFalse(entry.pointer().endsWith("/**"));
            assertFalse(entry.pointer().contains("/$order"));
        }
        for (Object item : (List<?>) root.get("nonMaskingRows")) {
            var row = (Map<?, ?>) item;
            int number = ((Number) row.get("number")).intValue();
            assertTrue(Set.of(12, 16).contains(number));
            assertEquals("§10 №" + number, row.get("specSection"));
            assertTrue(rows.add(number));
        }
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 16).boxed().collect(java.util.stream.Collectors.toSet()), rows);
    }

    @Test
    void eachSeededRuleMatchesItsClientAndUnusedIsDetectedSeparately() throws IOException {
        for (var entry : AllowedDiffs.parse(resource()).entries()) {
            var allowed = new AllowedDiffs(List.of(entry));
            assertEquals(List.of(entry), allowed.unused());
            var difference = example(entry);
            for (String client : List.of("fx", "swing", "web")) {
                if (!entry.clients().contains(client)) assertEquals(List.of(difference), allowed.filter(client, List.of(difference)));
            }
            assertEquals(List.of(entry), allowed.unused());
            var context = Map.of("frame", Map.of("contentWidth", 1000));
            assertTrue(allowed.filter(entry.clients().iterator().next(), List.of(difference), context, context).isEmpty(), entry.pointer());
            assertTrue(allowed.unused().isEmpty(), entry.pointer());
        }
    }

    @Test
    void forbiddenSiblingFieldsWholeMenusOrderAndChooserRequestsStayVisible() throws IOException {
        var allowed = AllowedDiffs.parse(resource());
        for (String pointer : List.of("/menuBar/recovery/text", "/menuBar/recovery/children/recovery.clear/enabled",
                "/menuBar/file/children/file.exit/text", "/alerts/about/header", "/alerts/about/buttons/ok/enabled",
                "/alerts/simulateHalt/content", "/alerts/simulateHalt/buttons/halt/enabled",
                "/alerts/lastSnapshot/buttons/ok/text", "/alerts/uncaught/content", "/alerts/uncaught/header",
                "/alerts/alreadyRunning/header", "/alerts/crashRecovery/buttons/noRestore/enabled",
                "/status/session/color", "/toolbar/items/add/row", "/toolbar/items/add/bounds/width",
                "/frame/contentWidth", "/screens/unknown", "/chooserRequests/0/title", "/chooserRequests/0/filter",
                "/chooserRequests/0/folder", "/chooserRequests/0/name", "/menuBar/$order/0", "/alerts/$order/0")) {
            var difference = new DumpDiff.Difference(pointer, "a", "b");
            assertEquals(List.of(difference), allowed.filter("web", List.of(difference)), pointer);
        }
        assertEquals(allowed.entries(), allowed.unused());
    }

    @Test
    void accelAboutFrameAndTooltipRejectUnspecifiedValues() throws IOException {
        var allowed = AllowedDiffs.parse(resource());
        String about = UiText.get("alert.about.content", "v1", "JavaFX", "25", "<CashMemory>");
        for (var difference : List.of(
                new DumpDiff.Difference("/menuBar/file/children/file.new/accel", "Ctrl+N", "bad"),
                new DumpDiff.Difference("/menuBar/file/children/file.save/accel", "Ctrl+S", "Alt+S"),
                new DumpDiff.Difference("/menuBar/file/children/unknown/accel", "Ctrl+N", "Alt+Shift+N"),
                new DumpDiff.Difference("/alerts/about/content", about, UiText.get("alert.about.content", "v2", "Swing", "25", "<CashMemory>")),
                new DumpDiff.Difference("/alerts/about/content", about, UiText.get("alert.about.content", "v1", "Swing", "26", "<CashMemory>")),
                new DumpDiff.Difference("/frame/titleBar", "os", "unknown"),
                new DumpDiff.Difference("/frame/minSize", Map.of("width", 800, "height", 600), null),
                new DumpDiff.Difference("/menuBar/file/children/file.exit/tooltip", UiText.get("menu.file.exit.tip"), "bad"),
                new DumpDiff.Difference("/alerts/replaceFile", null, Map.of("purpose", "wrongPurpose")))) {
            String client = difference.pointer().equals("/alerts/replaceFile") ? "fx" : "web";
            assertEquals(List.of(difference), allowed.filter(client, List.of(difference)), difference.pointer());
        }
    }

    @Test
    void invalidCitesAndBroadMasksAreRejected() {
        for (String pointer : List.of("/alerts/**", "/menuBar/**", "/alerts/*/**", "/menuBar/*/**", "/menuBar", "/alerts/$order/0")) {
            assertThrows(IllegalArgumentException.class, () -> new AllowedDiffs.Entry(6, pointer, Set.of("web"), "§10 №6", "bad"));
        }
        for (int number : List.of(12, 16)) assertThrows(IllegalArgumentException.class,
                () -> new AllowedDiffs.Entry(number, "/chooserRequests", Set.of("web"), "§10 №" + number, "outside"));
        assertThrows(IllegalArgumentException.class,
                () -> new AllowedDiffs.Entry(6, "/alerts/about/content", Set.of("web"), "§10 №5", "wrong cite"));
    }

    @Test
    void toolbarWrapNeedsNarrowFrameAndSnapshotPayloadIsNeverDesktopNodeAllowance() throws IOException {
        var allowed = AllowedDiffs.parse(resource());
        var wrap = new DumpDiff.Difference("/toolbar/wrap", false, true);
        assertEquals(List.of(wrap), allowed.filter("web", List.of(wrap)));
        for (int width : List.of(1200, 1300, 0)) {
            var context = Map.of("frame", Map.of("contentWidth", width));
            assertEquals(List.of(wrap), allowed.filter("web", List.of(wrap), context, context));
        }
        var changed = new DumpDiff.Difference("/alerts/lastSnapshot/details", "<node>/fx (JSON) old", "<node>/swing (JSON) corrupted");
        assertEquals(List.of(changed), allowed.filter("swing", List.of(changed)));
        var malformedClient = new DumpDiff.Difference("/alerts/about/content",
                UiText.get("alert.about.content", "v1", "Swing", "25", "<CashMemory>"),
                UiText.get("alert.about.content", "v1", "garbage", "25", "<CashMemory>"));
        assertEquals(List.of(malformedClient), allowed.filter("web", List.of(malformedClient)));
        var omitted = new DumpDiff.Difference("/alerts/replaceFile", null, Map.of("purpose", "replaceFile"));
        assertTrue(allowed.filter("fx", List.of(omitted)).isEmpty());
        assertEquals(List.of(omitted), allowed.filter("swing", List.of(omitted)));
    }

    @Test
    void snapshotLocationAllowanceNeverChangesRawPayloadOrUnknownXmlPaths() throws IOException {
        var allowed = AllowedDiffs.parse(resource());
        String title = ru.cashprediction.core.text.Texts.get("session.store.title.xml");
        String payload = "{\"savedAt\":1,\"pid\":2,\"y\":310,\"path\":\"session-fx.xml\",\"node\":\"<node>/fx\"}";
        String registryTitle = ru.cashprediction.core.text.Texts.get("session.store.title.registry");
        String fx = UiText.get("s2.recovery.registryBlock", registryTitle, "<node>\\fx", payload)
                + "\n" + UiText.get("s2.recovery.fileBlock", title,
                "<CashMemory>\\session-fx.xml", payload);
        String swing = UiText.get("s2.recovery.registryBlock", registryTitle, "<node>\\swing", payload)
                + "\n" + UiText.get("s2.recovery.fileBlock", title,
                "<CashMemory>\\session-swing.xml", payload);
        String pointer = "/alerts/lastSnapshot/details";
        assertTrue(allowed.filter("swing", List.of(new DumpDiff.Difference(pointer, fx, swing))).isEmpty());
        for (String invalid : List.of(swing.replace("\"savedAt\":1", "\"savedAt\":3"),
                swing.replace("\"pid\":2", "\"pid\":3"), swing.replace("\"y\":310", "\"y\":465"),
                swing.replace("\"<node>/fx\"", "\"<node>/swing\""),
                swing.replace("\"session-fx.xml\"", "\"session-swing.xml\""),
                swing.replace("<CashMemory>\\session-swing.xml", "<CashMemory>\\other\\session-swing.xml"),
                swing.replace("<CashMemory>\\session-swing.xml", "<CashMemory>\\session-swing.xml.bak"))) {
            var difference = new DumpDiff.Difference(pointer, fx, invalid);
            assertEquals(List.of(difference), allowed.filter("swing", List.of(difference)));
        }
    }

    @Test
    void allSeededPointersResolveInTheComparisonSchema() throws IOException {
        for (var entry : AllowedDiffs.parse(resource()).entries()) {
            Type type = UiDump.class;
            for (String encoded : entry.pointer().substring(1).split("/")) {
                String segment = encoded.replace("~1", "/").replace("~0", "~");
                if (type instanceof ParameterizedType collection) {
                    if (collection.getRawType() == List.class) type = collection.getActualTypeArguments()[0];
                    else if (collection.getRawType() == Map.class) type = collection.getActualTypeArguments()[1];
                    else fail(entry.pointer());
                } else {
                    assertInstanceOf(Class.class, type, entry.pointer());
                    Class<?> record = (Class<?>) type;
                    assertTrue(record.isRecord(), entry.pointer());
                    var component = java.util.Arrays.stream(record.getRecordComponents())
                            .filter(c -> c.getName().equals(segment)).findFirst().orElseThrow(() -> new AssertionError(entry.pointer()));
                    type = component.getGenericType();
                }
            }
        }
    }

    private static DumpDiff.Difference example(AllowedDiffs.Entry entry) {
        String pointer = entry.pointer().replace("*", "file");
        Object left = "desktop", right = "web";
        switch (entry.number()) {
            case 1 -> { left = Map.of("kind", "Radio", "group", "store", "enabled", false); right = null; }
            case 2 -> {
                String[] parts = pointer.split("/");
                var command = ru.cashprediction.core.ui.command.CommandId.byId(parts[parts.length - 2]).orElseThrow();
                left = ru.cashprediction.core.ui.command.HotkeyTable.shownAccelerator(command,
                        ru.cashprediction.core.app.ClientKind.FX).map(c -> c.display()).orElse("");
                right = ru.cashprediction.core.ui.command.HotkeyTable.shownAccelerator(command,
                        ru.cashprediction.core.app.ClientKind.WEB).map(c -> c.display()).orElse("");
            }
            case 3 -> { left = UiText.get("menu.file.exit.tip"); right = UiText.get("menu.file.exit.tip.web"); }
            case 4 -> { left = UiText.get("alert.halt.header"); right = UiText.get("alert.halt.web"); }
            case 6 -> {
                left = UiText.get("alert.about.content", "v1", "JavaFX", "25", "<CashMemory>");
                right = UiText.get("alert.about.content", "v1", "Swing", "25", "<CashMemory>");
            }
            case 7, 9, 10 -> {
                if (pointer.equals("/alerts/crashRecovery/header")) {
                    left = UiText.get("s2.startup.crashed") + "\n" + UiText.get("s2.startup.marker", "13.09.2026 12:00", UiText.get("s2.startup.clientFx"));
                    right = UiText.get("s2.startup.crashed") + "\n" + UiText.get("s2.startup.marker", "13.09.2026 12:00", UiText.get("client.web"));
                } else if (!pointer.endsWith("/content")) {
                    left = Map.of("id", pointer.substring(pointer.lastIndexOf('/') + 1), "text", "button", "tooltip", "",
                            "enabled", true, "isDefault", false, "x", 0);
                    right = null;
                }
            }
            case 8 -> {
                if (!entry.clients().contains("web")) {
                    String title = ru.cashprediction.core.text.Texts.get("session.store.title.registry");
                    left = UiText.get("s2.recovery.registryBlock", title, "<node>\\fx", "payload");
                    right = UiText.get("s2.recovery.registryBlock", title, "<node>\\swing", "payload");
                }
            }
            case 11 -> {
                if (pointer.endsWith("titleBar")) { left = "os"; right = "tab"; }
                else { left = Map.of("width", 900, "height", 600); right = null; }
            }
            case 13 -> { left = Map.of("purpose", "replaceFile"); right = null; }
            case 14 -> { left = Map.of("kind", pointer.substring(pointer.lastIndexOf('/') + 1)); right = null; }
            case 15 -> { left = false; right = true; }
            default -> { }
        }
        return new DumpDiff.Difference(pointer, left, right);
    }

    private static String resource() throws IOException {
        try (var stream = ClosedAllowancesTest.class.getClassLoader().getResourceAsStream(AllowedDiffs.RESOURCE)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
