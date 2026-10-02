package ru.cashprediction.fx.ui;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.json.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет свежие дампы реального s02, не создавая клиентских бизнес-фикстур. */
@EnabledIfSystemProperty(named = "fx.renderedDump", matches = ".+")
class FxRenderedRegressionTest {
    private Map<?, ?> dump(String step) throws Exception {
        return (Map<?, ?>) JsonParser.parse(Files.readString(Path.of(System.getProperty("fx.renderedDump"), step + ".json")));
    }
    private Map<?, ?> object(Map<?, ?> map, String key) { return (Map<?, ?>) map.get(key); }
    private Map<?, ?> item(Object tree, String id) {
        if (tree instanceof Map<?, ?> map) {
            if (id.equals(map.get("id"))) return map;
            for (Object value : map.values()) { Map<?, ?> found = item(value, id); if (found != null) return found; }
        } else if (tree instanceof List<?> list) {
            for (Object value : list) { Map<?, ?> found = item(value, id); if (found != null) return found; }
        }
        return null;
    }
    /** Размеры относятся к содержимому сцены, а не к наружной рамке Windows. */
    @Test void exactContentSizeAndNoSpontaneousSelection() throws Exception {
        var dump = dump("table"); assertEquals("fx", dump.get("client"));
        var frame = object(dump, "frame");
        assertEquals(1200, ((Number) frame.get("contentWidth")).doubleValue());
        assertEquals(800, ((Number) frame.get("contentHeight")).doubleValue());
        assertEquals("", object(dump, "table").get("selectedRowId"));
    }
    /** Реальные цвета, жирность и доступность не заменяются ожидаемыми метаданными. */
    @Test void toolbarAndDisabledCommandsAreRendered() throws Exception {
        var dump = dump("table"); var save = item(dump.get("toolbar"), "tb.save");
        assertEquals("accent", save.get("color")); assertEquals(true, save.get("bold"));
        assertEquals("text.primary", item(dump.get("toolbar"), "tb.add").get("color"));
        assertEquals(false, item(dump.get("menuBar"), "edit.adjust").get("enabled"));
        assertEquals(false, item(dump.get("toolbar"), "tb.add.sep.1").get("enabled"));
    }
    /** Дубликат радио в панели не снимает отметку у видимого пункта меню. */
    @Test void repeatedPeriodRadioRemainsSelected() throws Exception {
        assertEquals(true, item(dump("table").get("menuBar"), "view.period.M12").get("checked"));
        assertEquals(true, item(dump("three-months").get("menuBar"), "view.period.M3").get("checked"));
    }
    /** Дамп настоящей формы содержит один радио-контракт из обоих физических рядов. */
    @Test @EnabledIfSystemProperty(named = "fx.formDump", matches = ".+")
    void horizonFragmentsDumpOneLogicalFieldInVisualOrder() throws Exception {
        var tree = (Map<?, ?>) JsonParser.parse(Files.readString(Path.of(System.getProperty("fx.formDump"), "settings.json")));
        var windows = (List<?>) tree.get("windows"); assertEquals(1, windows.size());
        var fields = (List<?>) ((Map<?, ?>) windows.getFirst()).get("fields");
        var radios = fields.stream().map(v -> (Map<?, ?>) v).filter(f -> "horizonKind".equals(f.get("id"))).toList();
        assertEquals(1, radios.size()); assertEquals("MONTHS", radios.getFirst().get("text"));
        assertEquals(List.of("\u043c\u0435\u0441\u044f\u0446\u0435\u0432", "\u043b\u0435\u0442", "\u0434\u043e \u0434\u0430\u0442\u044b"), radios.getFirst().get("options"));
    }
    /** Смена выбора между физическими рядами оставляет единственное глобальное значение. */
    @Test @EnabledIfSystemProperty(named = "fx.widgetDump", matches = ".+")
    void horizonSelectionCrossesBothPhysicalRows() throws Exception {
        for (String value : List.of("YEARS", "UNTIL", "MONTHS")) {
            var tree = (Map<?, ?>) JsonParser.parse(Files.readString(Path.of(System.getProperty("fx.widgetDump"), "horizon-" + value.toLowerCase(Locale.ROOT) + ".json")));
            var windows = (List<?>) tree.get("windows"); var fields = (List<?>) ((Map<?, ?>) windows.getFirst()).get("fields");
            var matches = fields.stream().map(v -> (Map<?, ?>) v).filter(f -> "horizonKind".equals(f.get("id"))).toList();
            assertEquals(1, matches.size()); assertEquals(value, matches.getFirst().get("text"));
            assertEquals(3, ((List<?>) matches.getFirst().get("options")).size());
        }
    }
    /** Реальные жесты Alt и F10 дают по одному вызову после фокусировки таблицы. */
    @Test @EnabledIfSystemProperty(named = "fx.keyDump", matches = ".+")
    void physicalMenuGesturesFireExactlyOnce() throws Exception {
        int expected = 1;
        for (String step : List.of("alt-alone", "f10-once")) {
            var tree = (Map<?, ?>) JsonParser.parse(Files.readString(Path.of(System.getProperty("fx.keyDump"), step + ".json")));
            assertEquals(expected++, ((Number) ((Map<?, ?>) tree.get("counters")).get("ui.menuBar")).intValue());
        }
    }
    /** Геометрия читается из виджетов, включая заголовок таблицы и реальные эффекты темы. */
    @Test @EnabledIfSystemProperty(named = "fx.layoutDump", matches = ".+")
    void toolbarUsesSharedMeasuredGeometry() throws Exception {
        var tree = (Map<?, ?>) JsonParser.parse(Files.readString(Path.of(System.getProperty("fx.layoutDump"), "sample.json")));
        var regions = object(object(tree, "frame"), "regions");
        assertEquals(28, ((Number) object(regions, "menuBar").get("height")).doubleValue());
        assertEquals(36, ((Number) object(regions, "toolbar").get("height")).doubleValue());
        assertEquals(28, ((Number) object(regions, "table.header").get("height")).doubleValue());
        var toolbar = object(tree, "toolbar");
        for (Object entry : (List<?>) toolbar.get("items")) {
            var bounds = object((Map<?, ?>) entry, "bounds");
            assertEquals(28, ((Number) bounds.get("height")).doubleValue());
            assertEquals(32, ((Number) bounds.get("y")).doubleValue());
        }
        assertEquals(0, ((Number) object(item(toolbar, "tb.add"), "bounds").get("x")).doubleValue());
        assertEquals(28, ((Number) object(item(toolbar, "tb.undo"), "bounds").get("width")).doubleValue());
        assertEquals(28, ((Number) object(item(toolbar, "tb.redo"), "bounds").get("width")).doubleValue());
        assertEquals(220, ((Number) object(item(toolbar, "tb.filter"), "bounds").get("width")).doubleValue());
    }
}
