package ru.cashprediction.core.ui.selftest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.menu.MenuNode;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.chart.*;
import ru.cashprediction.core.ui.view.table.*;

/** Перевод моделей в замороженную схему дампа без выдуманных границ виджетов. */
final class ModelDump {
    private ModelDump() { }

    /** Устанавливает метки фактического прогона, сохраняя все данные драйвера. */
    static UiDump label(UiDump d, String scenario, String step) {
        return new UiDump(d.schema(), d.client(), scenario, step, d.frame(), d.menuBar(), d.toolbar(), d.summary(), d.table(), d.chart(),
                d.status(), d.contextMenus(), d.windows(), d.alerts(), d.popups(), d.screens(), d.chooserRequests(), d.classCensus(), d.counters());
    }

    static UiDump build(RecordingUiPort p, AppController controller, String scenario, String step, List<UiDump.Popup> popups) {
        var m = p.screen;
        var bounds = p.geometry.bounds();
        List<UiDump.MenuItem> menus = m == null ? List.of() : m.menuBar().menus().stream().map(ModelDump::menu).toList();
        var toolbar = m == null ? null : new UiDump.Toolbar(false, m.toolbar().items().stream().map(n -> toolbar(object(n))).toList());
        var summary = m == null ? null : new UiDump.Summary(m.summary().visible(), m.summary().cards().stream().map(c ->
                new UiDump.Card(c.id(), c.title(), c.value(), color(c.valueColor()), c.caption(), color(c.captionColor()),
                        c.explanation(), null)).toList(), m.summary().unavailableText());
        var frame = new UiDump.Frame(m == null ? "" : m.windowTitle(), p.profile.kind() == ru.cashprediction.core.app.ClientKind.WEB ? "tab" : "os",
                p.profile.kind() == ru.cashprediction.core.app.ClientKind.WEB ? null : new UiDump.Size(900, 600), bounds.width(), bounds.height(), Map.of());
        var status = m == null ? List.<UiDump.Segment>of() : m.status().segments().stream()
                .map(s -> new UiDump.Segment(s.id(), s.text(), s.tooltip(), color(s.color()), s.visible())).toList();
        List<UiDump.Window> windows = new ArrayList<>(); List<UiDump.Alert> alerts = new ArrayList<>();
        for (var h : p.windows) {
            if (!h.open) continue;
            if (h.form != null) windows.add(window(h));
            else {
                var a = h.alert;
                alerts.add(new UiDump.Alert(h.id, a.purpose(), a.kind().name(), a.windowTitle(), a.glyph(), a.minWidth(),
                        a.header(), a.content(), a.details(), detailsLink(a.details(), a.detailsExpanded()), a.detailsExpanded(), a.buttons().stream().map(b ->
                        new UiDump.Button(b.id(), b.text(), b.tooltip(), b.enabled(), b.id().equals(a.defaultButtonId()), 0)).toList()));
            }
        }
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (CommandId id : CommandId.values()) { int count = controller.executedCount(id); if (count > 0) counters.put(id.id(), count); }
        return new UiDump(1, "model", scenario, step, frame, menus, toolbar, summary, m == null ? null : table(m.table()),
                m == null ? null : chart(m.chart().layout(bounds.width(), 500)), status,
                p.context.isEmpty() ? List.of() : List.of(new UiDump.ContextMenu(p.contextTarget, p.context.stream().map(ModelDump::menu).toList())),
                windows, alerts, popups, List.of(), p.choosers, Map.of(), counters);
    }

    static UiDump.MenuItem menu(MenuNode node) { return menu(object(node)); }
    private static UiDump.MenuItem menu(Map<String, Object> n) {
        List<UiDump.MenuItem> children = maps(n.get("children")).stream().map(ModelDump::menu).toList();
        String accel = "";
        if (n.get("accel") instanceof Map<?, ?> a) {
            accel = new ru.cashprediction.core.ui.command.KeyChord(Boolean.TRUE.equals(a.get("ctrl")),
                    Boolean.TRUE.equals(a.get("shift")), Boolean.TRUE.equals(a.get("alt")), a.get("key").toString()).display();
        }
        return new UiDump.MenuItem(text(n, "id"), text(n, "kind"), n.containsKey("label") ? text(n, "label") : text(n, "text"),
                accel, flag(n, "enabled", text(n, "kind").equals("Slider") || text(n, "kind").equals("Spinner")), flag(n, "checked", flag(n, "selected", false)), text(n, "group"),
                text(n, "value"), text(n, "currentLabel"), text(n, "tooltip"), children);
    }
    private static UiDump.ToolbarItem toolbar(Map<String, Object> n) {
        return new UiDump.ToolbarItem(text(n, "id"), text(n, "kind"), n.containsKey("glyphOrText") ? text(n, "glyphOrText") : text(n, "text"),
                text(n, "prompt"), text(n, "tooltip"), flag(n, "enabled", true), flag(n, "selected", false),
                text(n, "emphasis").equals("ACCENT") ? "accent" : text(n, "emphasis").equals("WHATIF") ? "whatif" : "text.primary",
                !text(n, "emphasis").isEmpty() && !text(n, "emphasis").equals("NONE"), 0, null, maps(n.get("items")).stream().map(ModelDump::menu).toList());
    }

    private static UiDump.Table table(TableModel model) {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        List<UiDump.Row> rows = new ArrayList<>();
        for (int i = 0; i < model.rowCount(); i++) {
            var r = model.row(i);
            // Длина UTF-8 записи отделяет строки без неоднозначности разделителей внутри ячеек.
            byte[] bytes = UiJson.write(r.cells()).getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            if (i < 60 || r.rowId().equals(model.selectedRowId())) {
                Map<String, UiDump.CellLook> styles = new LinkedHashMap<>();
                for (var column : model.columns()) {
                    var cell = r.cellStyles().get(column.id());
                    var rowStyle = r.rowStyle();
                    styles.put(column.id(), new UiDump.CellLook(color(cell == null ? rowStyle.text() : cell.text()),
                            rowStyle.bold() || cell != null && cell.bold(), rowStyle.italic() || cell != null && cell.italic(), cell != null && cell.strike()));
                }
                rows.add(new UiDump.Row(i, r.rowId(), r.kind().name(), r.cells(), color(r.rowStyle().background()), styles));
            }
        }
        var placeholder = model.placeholder();
        return new UiDump.Table(model.columns().stream().map(ColumnSpec::title).toList(), model.rowCount(), rows,
                HexFormat.of().formatHex(digest.digest()), placeholder == null ? "" : placeholder.text(),
                placeholder == null ? List.of() : placeholder.buttons().stream().map(b -> new UiDump.Button(b.id(), b.text(), "", true, false, 0)).toList(),
                model.selectedRowId());
    }
    private static UiDump.Chart chart(ChartScene scene) {
        List<String> x = new ArrayList<>(), y = new ArrayList<>(), labels = new ArrayList<>(); int markers = 0, bars = 0;
        for (var primitive : scene.primitives()) {
            if (primitive instanceof ChartPrimitive.Label l) {
                if (l.y() > scene.plot().plotY() + scene.plot().plotHeight()) x.add(l.text());
                else if (l.x() < scene.plot().plotX()) y.add(l.text()); else labels.add(l.text());
            } else if (primitive instanceof ChartPrimitive.Circle) markers++;
            else if (primitive instanceof ChartPrimitive.Box) bars++;
        }
        return new UiDump.Chart(scene.legend().stream().map(LegendItem::text).toList(), x, y, labels, markers, bars, scene.emptyText());
    }
    static List<UiDump.Button> buttons(RecordingUiPort.Handle h) {
        List<UiDump.Button> buttons = new ArrayList<>();
        for (var row : h.spec.pages().get(h.view.page()).rows()) {
            if (row instanceof FormRow.SideColumn side) side.buttons().forEach(b -> addButton(buttons, h, b.id(), b.text(), b.tooltip()));
        }
        ModelUiDriver.fields(h).stream().filter(f -> f.kind() == FieldKind.BUTTON)
                .forEach(f -> addButton(buttons, h, f.id(), f.label(), f.tooltip()));
        h.spec.buttons().forEach(b -> addButton(buttons, h, b.id(), b.text(), b.tooltip()));
        return buttons;
    }
    private static void addButton(List<UiDump.Button> list, RecordingUiPort.Handle h, String id, String text, String tooltip) {
        var view = h.view.buttons().getOrDefault(id, ButtonView.ENABLED);
        if (view.visible()) list.add(new UiDump.Button(id, view.text() == null ? text : view.text(), view.tooltip() == null ? tooltip : view.tooltip(),
                view.enabled(), id.equals(h.spec.defaultButtonId()), 0));
    }
    private static UiDump.Window window(RecordingUiPort.Handle h) {
        List<String> sections = new ArrayList<>(), hints = new ArrayList<>();
        h.spec.pages().get(h.view.page()).rows().forEach(r -> {
            if (r instanceof FormRow.Section s) sections.add(s.caption());
            else if (r instanceof FormRow.Hint s) hints.add(s.text());
            else if (r instanceof FormRow.SideColumn s) sections.add(s.caption());
        });
        List<UiDump.Field> fields = new ArrayList<>();
        for (FieldSpec f : ModelUiDriver.fields(h)) {
            var v = h.view.fields().get(f.id()); if (v == null || !v.visible()) continue;
            var options = v.options() == null ? f.options() : v.options();
            fields.add(new UiDump.Field(f.id(), f.kind().name(), v.label() == null ? f.label() : v.label(),
                    v.value() == null ? FieldCodec.display(f.kind(), h.form.state().value(f.id())) : v.value(),
                    f.prompt(), v.tooltip() == null ? f.tooltip() : v.tooltip(), f.suffix(), v.enabled(), v.visible(), v.readOnly(),
                    options.stream().map(Option::text).toList()));
        }
        var b = h.bounds();
        return new UiDump.Window(h.id, h.spec.windowType().name(), h.spec.purpose(), h.spec.windowTitle(), h.view.header(), h.spec.glyph(),
                h.spec.modal(), h.placement.ownerId(), h.view.page(), b == null ? null : new UiDump.Box(b.x(), b.y(), b.width(), b.height()),
                sections, hints, fields, h.view.preview().stream().map(PreviewItem::text).toList(), h.form.state().previewIndex(),
                h.view.results().stream().map(r -> new UiDump.ResultText(r.text(), color(r.color()))).toList(), h.view.problem().text(),
                buttons(h), h.view.details(), detailsLink(h.view.details(), h.view.detailsExpanded()), h.view.detailsExpanded());
    }
    private static String detailsLink(String text, boolean expanded) {
        return text == null || text.isEmpty() ? "" : expanded ? ru.cashprediction.core.ui.text.UiText.get("details.hide")
                : ru.cashprediction.core.ui.text.UiText.get("details.show");
    }
    private static String color(ColorToken token) { return token == null ? "" : token.canonical().id(); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) UiJson.toTree(value); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) { return value instanceof List<?> l ? (List<Map<String, Object>>) l : List.of(); }
    private static String text(Map<String, Object> n, String key) { return java.util.Objects.toString(n.get(key), ""); }
    private static boolean flag(Map<String, Object> n, String key, boolean fallback) { return n.get(key) instanceof Boolean b ? b : fallback; }
}
