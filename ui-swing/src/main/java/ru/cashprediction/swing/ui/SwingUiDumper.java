package ru.cashprediction.swing.ui;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.dump.DumpGeometry;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.app.AppController;

/** Снимает состояние настоящих компонентов Swing; модели служат только источником технических id. */
public final class SwingUiDumper {
    private final SwingUiPort port;
    private final String scenario;
    private final AppController controller;

    /** Создаёт дампер рендерера указанного сценария. */
    public SwingUiDumper(SwingUiPort port, AppController controller, String scenario) { this.port = port; this.controller = controller; this.scenario = scenario; }

    /** Читает тексты, цвета, выбранность и геометрию реальных компонентов в EDT. */
    public UiDump dump(String step) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("EDT required");
        MainFrameView frame = port.frame;
        UiDump.Frame geometry = null; List<UiDump.MenuItem> menus = List.of();
        UiDump.Toolbar toolbar = null;
        UiDump.Summary summary = null;
        UiDump.Table table = null; UiDump.Chart chart = null; List<UiDump.Segment> status = new ArrayList<>();
        if (frame != null) {
            Map<String, UiDump.Box> regions = new LinkedHashMap<>();
            regions.put("menuBar", box(frame.menus, frame.root)); regions.put("toolbar", box(frame.toolbar, frame.root)); regions.put("summary", box(frame.summary, frame.root));
            regions.put("center", box(frame.center, frame.root)); regions.put("status", box(frame.status, frame.root));
            baselineRegion(regions, "toolbar.baseline", frame.toolbar, frame.root);
            baselineRegion(regions, "status.baseline", frame.status, frame.root);
            if (frame.table.table.getTableHeader().isShowing()) {
                regions.put("table.header", box(frame.table.table.getTableHeader(), frame.root));
                columnRegions(regions, frame.table.table, frame.root);
            }
            geometry = new UiDump.Frame(frame.getTitle(), frame.isUndecorated() ? "none" : "os", new UiDump.Size(frame.getMinimumSize().width, frame.getMinimumSize().height), frame.root.getWidth(), frame.root.getHeight(), regions);
            menus = menuItems(frame.menus.getComponents()); toolbar = toolbar(frame.toolbar, frame.root); summary = summary(frame.summary, frame.root); table = table(frame.table);
            frame.chart.observeDormant(); chart = frame.chart.drawn;
            for (Component child : frame.status.getComponents()) if (child instanceof JLabel label && id(label) != null) status.add(new UiDump.Segment(id(label), plain(label), tip(label), color(label.getForeground()), label.isVisible()));
        }
        List<UiDump.Window> windows = new ArrayList<>();
        for (SwingFormDialog form : port.forms.values()) if (form.showing()) windows.add(window(form));
        List<UiDump.Alert> alerts = new ArrayList<>();
        for (SwingAlerts alert : port.alerts.values()) if (alert.showing()) alerts.add(new UiDump.Alert(alert.id, alert.spec.purpose(), alert.spec.kind().name(), alert.dialog.getTitle(), string(alert.glyph, "cp.glyph"), alert.dialog.getContentPane().getMinimumSize().width,
                plain(alert.header), plain(alert.content), alert.details.getText(), alert.detailsLink.isVisible() ? alert.detailsLink.getText() : "", alert.details.isShowing(), buttons(alert.buttonBar, alert.dialog.getRootPane())));
        List<UiDump.Popup> popups = new ArrayList<>();
        if (port.popups.content != null && port.popups.content.isShowing()) popups.add(new UiDump.Popup(port.popups.kind, labels(port.popups.content), contentBounds(port.popups.content, port.frame.root)));
        for (Window window : Window.getWindows()) {
            JToolTip tooltip = visibleTooltip(window);
            if (tooltip != null) {
                // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
                popups.add(new UiDump.Popup("tooltip", List.of(plain(new JLabel(tooltip.getTipText()))), contentBounds(tooltip, port.frame.root)));
                break;
            }
        }
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (CommandId command : CommandId.values()) if (controller.executedCount(command) > 0) counters.put(command.id(), controller.executedCount(command));
        return new UiDump(1, "swing", scenario, step, geometry, menus, toolbar, summary, table, chart, status, List.copyOf(port.contexts), windows, alerts, popups, List.of(), List.copyOf(port.chooserRequests), Map.of(), counters);
    }

    /** Читает фактическое дерево компонентов меню, включая закрытые подменю. */
    public static List<UiDump.MenuItem> menuItems(Component[] components) {
        List<UiDump.MenuItem> result = new ArrayList<>();
        for (Component component : components) {
            if (!(component instanceof JComponent c) || id(c) == null) continue;
            String kind = string(c, "cp.kind"), text = "", value = "", valueLabel = "", accel = string(c, "cp.accel"), group = string(c, "cp.group"); boolean checked = false;
            List<UiDump.MenuItem> children = List.of();
            if (c instanceof AbstractButton button) {
                text = button.getText();
                checked = (button instanceof JCheckBoxMenuItem || button instanceof JRadioButtonMenuItem) && button.isSelected();
            }
            if (c instanceof JMenu menu) children = menuItems(menu.getMenuComponents());
            if (kind.equals("Slider")) { JSlider slider = first(c, JSlider.class); JLabel label = first(c, JLabel.class); value = Integer.toString(slider.getValue()); valueLabel = label.getText(); }
            if (kind.equals("Spinner")) { JSpinner spinner = first(c, JSpinner.class); JLabel label = first(c, JLabel.class); text = label.getText(); value = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().getText(); }
            result.add(new UiDump.MenuItem(id(c), kind, text, accel, c.isEnabled(), checked, group, value, valueLabel, tip(c), children));
        }
        return result;
    }

    static UiDump.Toolbar toolbar(SwingToolbar toolbar) {
        return toolbar(toolbar, toolbar);
    }
    static UiDump.Toolbar toolbar(SwingToolbar toolbar, Component contentRoot) {
        List<UiDump.ToolbarItem> items = new ArrayList<>();
        for (Component child : toolbar.getComponents()) {
            if (!(child instanceof JComponent c) || id(c) == null) continue;
            String kind = string(c, "cp.kind"), text = "", prompt = "", tooltip = tip(c); boolean selected = false; Component rendered = c;
            if (c instanceof AbstractButton button) {
                text = button.getClientProperty("cp.text") instanceof String semantic ? semantic : button.getText();
                selected = button.isSelected();
            }
            if (c.getClientProperty("cp.main") instanceof JButton main) { text = main.getText(); tooltip = tip(main); rendered = main; }
            if (kind.equals("FilterField")) { JTextField field = first(c, JTextField.class); text = field.getText(); prompt = string(field, "cp.prompt"); tooltip = tip(field); }
            List<UiDump.MenuItem> menus = c.getClientProperty("cp.popup") instanceof JPopupMenu popup ? menuItems(popup.getComponents()) : List.of();
            String textColor = kind.equals("Separator") || kind.equals("Spacer") ? "" : color(rendered.getForeground());
            items.add(new UiDump.ToolbarItem(id(c), kind, text, prompt, tooltip, rendered.isEnabled(), selected, textColor, rendered.getFont().isBold(), 0, box(c, contentRoot), menus));
        }
        return new UiDump.Toolbar(false, items);
    }
    private UiDump.Summary summary(SwingSummaryPanel summary, Component contentRoot) {
        List<UiDump.Card> cards = new ArrayList<>(); String unavailable = "";
        for (Component child : summary.getComponents()) {
            if (child instanceof SwingSummaryPanel.Card card) cards.add(new UiDump.Card(id(card), card.title.getText(), card.value.getText(), color(card.value.getForeground()), card.caption.getText(), color(card.caption.getForeground()), tip(card), box(card, contentRoot)));
            else if (child instanceof JLabel label) unavailable = plain(label);
        }
        return new UiDump.Summary(summary.isVisible(), cards, unavailable);
    }
    UiDump.Table table(SwingTable view) {
        JTable table = view.table; List<String> columns = new ArrayList<>();
        for (int col = 0; col < table.getColumnCount(); col++) columns.add(table.getColumnModel().getColumn(col).getHeaderValue().toString());
        List<UiDump.Row> rows = new ArrayList<>(); MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        for (int row = 0; row < table.getRowCount(); row++) {
            List<String> texts = new ArrayList<>(); Map<String, UiDump.CellLook> styles = new LinkedHashMap<>(); String background = "";
            for (int col = 0; col < table.getColumnCount(); col++) {
                Component cell = table.prepareRenderer(table.getCellRenderer(row, col), row, col);
                texts.add(cell instanceof JLabel label ? label.getText() : ""); background = color(cell.getBackground());
                boolean strike = cell instanceof JComponent c && Boolean.TRUE.equals(c.getClientProperty("cp.strike"));
                styles.put(table.getColumnModel().getColumn(col).getIdentifier().toString(), new UiDump.CellLook(color(cell.getForeground()), cell.getFont().isBold(), cell.getFont().isItalic(), strike));
            }
            byte[] bytes = UiJson.write(texts).getBytes(StandardCharsets.UTF_8); digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            if (row < 60 || row == table.getSelectedRow()) { var metadata = view.adapter.source().row(row); rows.add(new UiDump.Row(row, metadata.rowId(), metadata.kind().name(), texts, background, styles)); }
        }
        String placeholder = view.placeholder.isShowing() ? labels(view.placeholder).stream().findFirst().orElse("") : "";
        String selected = table.getSelectedRow() < 0 ? "" : view.adapter.source().row(table.getSelectedRow()).rowId();
        return new UiDump.Table(columns, table.getRowCount(), rows, HexFormat.of().formatHex(digest.digest()), placeholder, view.placeholder.isShowing() ? buttons(view.placeholder, null) : List.of(), selected);
    }
    private UiDump.Window window(SwingFormDialog form) {
        List<UiDump.Field> fields = new ArrayList<>(); List<String> preview = new ArrayList<>(); int selected = -1;
        for (List<SwingFieldWidgets.Binding> group : form.fields.values()) {
            List<SwingFieldWidgets.Binding> visible = group.stream().filter(b -> b.root.isVisible()).toList();
            if (visible.isEmpty()) continue;
            SwingFieldWidgets.Binding binding = visible.getFirst();
            if (binding.spec.kind() == ru.cashprediction.core.ui.form.FieldKind.PREVIEW && binding.input instanceof JList<?> list) { for (int i = 0; i < list.getModel().getSize(); i++) preview.add(optionText(list.getModel().getElementAt(i))); selected = list.getSelectedIndex(); }
            List<String> options = new ArrayList<>();
            if (binding.input instanceof JComboBox<?> combo) for (int i = 0; i < combo.getItemCount(); i++) options.add(optionText(combo.getItemAt(i)));
            else if (binding.input instanceof JList<?> list) for (int i = 0; i < list.getModel().getSize(); i++) options.add(optionText(list.getModel().getElementAt(i)));
            List<AbstractButton> radios = visible.stream().flatMap(b -> b.radios.stream()).toList();
            RadioState radio = radioState(radios);
            if (binding.spec.kind() == ru.cashprediction.core.ui.form.FieldKind.RADIO) options.addAll(radio.options());
            String label = binding.label.getText(); if (label.endsWith(":")) label = label.substring(0, label.length() - 1);
            boolean isRadio = binding.spec.kind() == ru.cashprediction.core.ui.form.FieldKind.RADIO;
            fields.add(new UiDump.Field(binding.spec.id(), binding.spec.kind().name(), label, isRadio ? radio.value() : binding.displayed(), string(binding.input, "cp.prompt"), tip(binding.input), binding.spec.suffix(), isRadio ? radio.enabled() : binding.input.isEnabled(), group.stream().anyMatch(b -> b.root.isVisible()), binding.input instanceof javax.swing.text.JTextComponent text ? !text.isEditable() : binding.readOnly, options));
        }
        List<UiDump.ResultText> results = form.results.stream().map(label -> new UiDump.ResultText(plain(label), color(label.getForeground()))).toList();
        String ownerId = ownerId(form.dialog.getOwner());
        return new UiDump.Window(form.session.windowId(), form.spec.windowType().name(), form.spec.purpose(), form.dialog.getTitle(), plain(form.header), string(form.glyph, "cp.glyph"), form.dialog.getModalityType() != Dialog.ModalityType.MODELESS,
                ownerId, form.page, contentBounds(form.content, port.frame.root), form.sections.stream().map(JLabel::getText).toList(), form.hints.stream().map(SwingUiDumper::plain).toList(), fields,
                preview, selected, results, plain(form.problem), buttons(form.content, form.dialog.getRootPane()), form.details.getText(), form.detailsLink.isVisible() ? form.detailsLink.getText() : "", form.details.isShowing());
    }

    /** Читает только фактический контент; экранные границы ручки восстановления не меняет. */
    static UiDump.Box contentBounds(Component content, Component mainContent) {
        return DumpGeometry.relativeContent(screenBox(content), screenBox(mainContent));
    }

    /** Наблюдаемое общее значение и порядок вариантов одной физической группы переключателей. */
    record RadioState(String value, List<String> options, boolean enabled) { }

    /** Читает все реальные переключатели, включая часть группы в следующей строке формы. */
    static RadioState radioState(List<? extends AbstractButton> radios) {
        return new RadioState(radios.stream().filter(AbstractButton::isSelected).map(AbstractButton::getActionCommand).findFirst().orElse(""),
                radios.stream().map(AbstractButton::getText).toList(), radios.stream().anyMatch(AbstractButton::isEnabled));
    }
    private String ownerId(Window owner) {
        if (owner == port.frame) return "main";
        for (var entry : port.forms.entrySet()) if (owner == entry.getValue().dialog) return entry.getKey();
        return owner == null ? "" : "unmapped-owner";
    }

    /** Читает физические кнопки в порядке дерева раскладки. */
    public static List<UiDump.Button> buttons(Container parent, JRootPane root) {
        List<UiDump.Button> result = new ArrayList<>(); collectButtons(parent, root, root == null ? parent : root.getContentPane(), result); return result;
    }
    private static void collectButtons(Container parent, JRootPane root, Component relative, List<UiDump.Button> result) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && id(button) != null && button.isVisible()) {
                result.add(new UiDump.Button(id(button), button.getText(), tip(button), button.isEnabled(), root != null && root.getDefaultButton() == button, SwingUtilities.convertPoint(button, 0, 0, relative).x));
            } else if (child instanceof Container c) collectButtons(c, root, relative, result);
        }
    }
    static UiDump.Box box(Component component, Component relative) { if (component == null) return new UiDump.Box(0, 0, 0, 0); Point p = SwingUtilities.convertPoint(component, 0, 0, relative); return new UiDump.Box(p.x, p.y, component.getWidth(), component.getHeight()); }
    /** Измеряет линию текста штатным UI delegate реального компонента, включая его рамку и шрифт. */
    static void baselineRegion(Map<String, UiDump.Box> regions, String id, Container region, Component contentRoot) {
        JComponent text = baselineComponent(region);
        if (text == null) return;
        int baseline = text.getBaseline(text.getWidth(), text.getHeight());
        Point point = SwingUtilities.convertPoint(text, 0, baseline, contentRoot);
        Rectangle bounds = textBounds(text);
        Point left = SwingUtilities.convertPoint(text, bounds.x, 0, contentRoot);
        regions.put(id, new UiDump.Box(left.x, point.y, bounds.width, 1));
    }
    /** Измеряет реальную область текста с учётом иконки, рамки и выравнивания Swing-компонента. */
    private static Rectangle textBounds(JComponent component) {
        Insets insets = component.getInsets();
        Rectangle view = new Rectangle(insets.left, insets.top,
                Math.max(0, component.getWidth() - insets.left - insets.right),
                Math.max(0, component.getHeight() - insets.top - insets.bottom));
        Rectangle iconBox = new Rectangle(), textBox = new Rectangle();
        if (component instanceof JLabel label) {
            SwingUtilities.layoutCompoundLabel(label, label.getFontMetrics(label.getFont()), label.getText(), label.getIcon(),
                    label.getVerticalAlignment(), label.getHorizontalAlignment(), label.getVerticalTextPosition(), label.getHorizontalTextPosition(),
                    view, iconBox, textBox, label.getIconTextGap());
        } else if (component instanceof AbstractButton button) {
            SwingUtilities.layoutCompoundLabel(button, button.getFontMetrics(button.getFont()), button.getText(), button.getIcon(),
                    button.getVerticalAlignment(), button.getHorizontalAlignment(), button.getVerticalTextPosition(), button.getHorizontalTextPosition(),
                    view, iconBox, textBox, button.getIconTextGap());
        } else return view;
        return textBox;
    }
    private static JComponent baselineComponent(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible() || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (child instanceof JComponent component && (child instanceof JLabel || child instanceof AbstractButton || child instanceof javax.swing.text.JTextComponent)
                    && component.getBaseline(component.getWidth(), component.getHeight()) >= 0) return component;
            if (child instanceof Container container) {
                JComponent found = baselineComponent(container); if (found != null) return found;
            }
        }
        return null;
    }
    /** Читает видимые прямоугольники заголовков после раскладки JTable, включая горизонтальную прокрутку. */
    static void columnRegions(Map<String, UiDump.Box> regions, JTable table, Component contentRoot) {
        var header = table.getTableHeader();
        if (!header.isVisible() || header.getWidth() <= 0 || header.getHeight() <= 0) return;
        Rectangle visible = header.getVisibleRect();
        for (int column = 0; column < table.getColumnCount(); column++) {
            Rectangle rectangle = header.getHeaderRect(column).intersection(visible);
            if (rectangle.isEmpty()) continue;
            Point point = SwingUtilities.convertPoint(header, rectangle.x, rectangle.y, contentRoot);
            String id = table.getColumnModel().getColumn(column).getIdentifier().toString();
            regions.put("table.column." + id, new UiDump.Box(point.x, point.y, rectangle.width, rectangle.height));
        }
    }
    static UiDump.Box screenBox(Component component) { Point p = component.getLocationOnScreen(); return new UiDump.Box(p.x, p.y, component.getWidth(), component.getHeight()); }
    /** Находит только фактически показанную подсказку PopupFactory, независимо от лёгкого или тяжёлого окна. */
    private static JToolTip visibleTooltip(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isShowing()) continue;
            if (child instanceof JToolTip tooltip) return tooltip;
            if (child instanceof Container container) { JToolTip tooltip = visibleTooltip(container); if (tooltip != null) return tooltip; }
        }
        return null;
    }
    static String id(JComponent component) { Object id = component.getClientProperty("cp.id"); return id == null ? null : id.toString(); }
    static String string(JComponent component, String key) { Object value = component.getClientProperty(key); return value == null ? "" : value.toString(); }
    static String color(Color color) { return color == null ? "" : ColorToken.byArgb(color.getRGB()).map(ColorToken::id).orElse(String.format("#%08X", color.getRGB())); }
    static String tip(JComponent c) { return plain(new JLabel(c.getToolTipText())); }
    static String plain(JLabel label) {
        if (label.getClientProperty("cp.text") instanceof String logical) return logical;
        String text = label.getText(); if (text == null) return "";
        if (!text.startsWith("<html>")) return text;
        return text.replaceAll("<img[^>]*alt='([^']*)'[^>]*>", "$1").replace("<br>", "\n").replaceAll("<[^>]*>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
    static List<String> labels(Container parent) { List<String> result = new ArrayList<>(); for (Component child : parent.getComponents()) if (child instanceof JLabel label) result.add(plain(label)); else if (child instanceof Container c) result.addAll(labels(c)); return result; }
    private static String optionText(Object option) { return option instanceof ru.cashprediction.core.ui.form.Option o ? o.text() : String.valueOf(option); }
    static <T extends Component> T first(Container parent, Class<T> type) { for (Component child : parent.getComponents()) { if (type.isInstance(child)) return type.cast(child); if (child instanceof Container c) { T found = first(c, type); if (found != null) return found; } } return null; }
}
