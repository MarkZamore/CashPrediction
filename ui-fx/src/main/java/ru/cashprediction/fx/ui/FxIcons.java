package ru.cashprediction.fx.ui;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.IdentityHashMap;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Рисует значки исключительно из общего каталога, сохраняя семантический текст виджетов. */
public final class FxIcons {
    private static final Map<ImageKey, Image> IMAGES = new HashMap<>();
    private static final Map<Image, DecodedPng> SOURCES = new IdentityHashMap<>();
    private static final double INLINE_SIZE = DesignTokens.INLINE_ICON_SIZE;
    /** Ключ кэша различает общие цветовые варианты одного символа. */
    private record ImageKey(String key, ColorToken color) { }

    /** Исходные байты конкретного декодирования; не копия ресурса на диске и не ожидаемая краска. */
    record DecodedPng(byte[] bytes, String source) {
        /** Удерживает независимые байты до установки изображения в виджет. */
        DecodedPng { bytes = bytes.clone(); }
        /** Не отдаёт внутреннюю память наблюдателю. */
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private FxIcons() { }

    /** Загружает общий PNG без файловых копий и платформенных шрифтов. */
    public static Optional<Image> image(String key) {
        return image(key, null);
    }

    /** Загружает цветовой вариант из ядра; неподдерживаемый цвет обрабатывает общий каталог. */
    public static Optional<Image> image(String key, ColorToken color) {
        ImageKey cacheKey = new ImageKey(key, color);
        if (IMAGES.containsKey(cacheKey)) return Optional.of(IMAGES.get(cacheKey));
        return UiIcons.png(key, color).map(bytes -> {
            Image image = new Image(new ByteArrayInputStream(bytes));
            SOURCES.put(image, new DecodedPng(bytes, "UiIcons.png(" + key + "," + color + ")"));
            IMAGES.put(cacheKey, image); return image;
        });
    }

    /**
     * Возвращает источник по идентичности установленного Image, а не по маркеру узла.
     * Для CSS-скинов источник берётся из неизменяемого data URL самого Image;
     * сетевые URL и неизвестные stream-изображения не загружаются повторно и не угадываются.
     */
    static Optional<DecodedPng> decodedSource(Image installed) {
        if (installed == null) return Optional.empty();
        DecodedPng retained = SOURCES.get(installed);
        if (retained != null) return Optional.of(retained);
        String url = installed.getUrl();
        String prefix = "data:image/png;base64,";
        if (url == null || !url.startsWith(prefix) || url.length() > 1_400_000) return Optional.empty();
        try {
            String encoded = url.substring(prefix.length());
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > 1_048_576
                    || !java.util.Base64.getEncoder().encodeToString(bytes).equals(encoded)) return Optional.empty();
            return Optional.of(new DecodedPng(bytes, "Image.data:image/png;base64"));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** Создаёт независимый узел изображения заданного размера. */
    public static ImageView view(String key, double size) {
        return view(key, size, null);
    }

    /** Создаёт узел общего PNG с цветом конкретной подписи. */
    public static ImageView view(String key, double size, ColorToken color) {
        ImageView view = new ImageView(image(key, color).orElseThrow(() -> new IllegalArgumentException("icon: " + key)));
        view.setFitWidth(size); view.setFitHeight(size); view.setPreserveRatio(true);
        view.setMouseTransparent(true); view.getProperties().put("cp.icon", key);
        return view;
    }

    /** Устанавливает общую эмблему главного или раннего окна. */
    public static void application(Stage stage) {
        stage.getIcons().setAll(new Image(new ByteArrayInputStream(UiIcons.applicationPng())));
    }

    /** Заменяет краску отдельного значка, оставляя getText и доступность исходными. */
    public static void icon(Labeled label, String key, double size) {
        label.getProperties().put("cp.logicalText", label.getText());
        label.setAccessibleText(label.getText());
        label.getProperties().put("cp.fixedIcon", key); label.getProperties().put("cp.fixedIconSize", size);
        if (label.getProperties().putIfAbsent("cp.fixedIconListener", true) == null)
            label.textFillProperty().addListener((o, previous, next) -> refreshIcon(label));
        refreshIcon(label);
    }

    private static void refreshIcon(Labeled label) {
        String key = (String) label.getProperties().get("cp.fixedIcon");
        double size = ((Number) label.getProperties().get("cp.fixedIconSize")).doubleValue();
        label.setGraphic(view(key, size, color(label.getTextFill()))); label.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    /** Подключает оформление подписи действия или проблемы при изменении текста. */
    public static void decorate(Labeled label) {
        if (label.getProperties().putIfAbsent("cp.iconDecoration", true) != null) return;
        label.textProperty().addListener((o, previous, next) -> refresh(label));
        label.textFillProperty().addListener((o, previous, next) -> refresh(label));
        refresh(label);
    }

    private static void refresh(Labeled label) {
        String logical = label.getText() == null ? "" : label.getText();
        List<Part> parts = parts(logical, Boolean.TRUE.equals(label.getProperties().get("cp.iconMarks")));
        if (parts.stream().noneMatch(Part::icon)) {
            label.setGraphic(null); label.setContentDisplay(ContentDisplay.LEFT);
            label.getProperties().remove("cp.logicalText"); label.setAccessibleText(null); return;
        }
        label.getProperties().put("cp.logicalText", logical); label.setAccessibleText(logical);
        TextFlow flow = new TextFlow(); flow.setMinWidth(0); flow.setMouseTransparent(true);
        for (Part part : parts) {
            if (part.icon()) flow.getChildren().add(view(part.text(), INLINE_SIZE, color(label.getTextFill())));
            else {
                Text text = new Text(part.text()); text.fontProperty().bind(label.fontProperty());
                text.fillProperty().bind(label.textFillProperty()); flow.getChildren().add(text);
            }
        }
        label.setGraphic(flow); label.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    /** Заменяет отметки в специально отведённой строке предпросмотра дат. */
    static void preview(Labeled label) { label.getProperties().put("cp.iconMarks", true); decorate(label); }

    /** Делит только декоративные позиции; валютные значения и обычный текст остаются текстом. */
    static List<Part> parts(String text, boolean marks) {
        List<Part> result = new ArrayList<>(); StringBuilder ordinary = new StringBuilder();
        boolean pure = text.codePointCount(0, text.length()) == 1;
        int first = 0; while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); String token = new String(Character.toChars(cp));
            boolean lineStart = text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset).isBlank();
            boolean suffix = (token.equals("\u25be") || token.equals("\u25b8") || token.equals("\u203a"))
                    && text.substring(offset + token.length()).isBlank();
            boolean position = marks || pure || suffix || token.equals("\u2713") || token.equals("\u2717")
                    || (offset == first || lineStart) && !token.equals("\u20bd") && !token.equals("?")
                    && offset + token.length() < text.length() && Character.isWhitespace(text.charAt(offset + token.length()));
            boolean shared = position && UiIcons.manifest().containsKey(token);
            if (shared) {
                if (!ordinary.isEmpty()) { result.add(new Part(ordinary.toString(), false)); ordinary.setLength(0); }
                result.add(new Part(token, true));
            } else ordinary.append(token);
            offset += token.length();
        }
        if (!ordinary.isEmpty()) result.add(new Part(ordinary.toString(), false));
        return List.copyOf(result);
    }

    /** Фрагмент текста либо ключ общего изображения, без создания toolkit. */
    record Part(String text, boolean icon) { }

    /** Составляет краску отметок и заголовков таблицы из PNG и обычных фрагментов. */
    static Node tableGraphic(Text source, boolean marks) {
        List<Part> parts = parts(source.getText(), marks);
        if (parts.stream().noneMatch(Part::icon)) return source;
        TextFlow flow = new TextFlow(); flow.setMouseTransparent(true);
        for (Part part : parts) {
            if (part.icon()) flow.getChildren().add(view(part.text(), INLINE_SIZE, color(source.getFill())));
            else {
                Text text = new Text(part.text()); text.setFont(source.getFont()); text.setFill(source.getFill());
                text.setStrikethrough(source.isStrikethrough()); flow.getChildren().add(text);
            }
        }
        return flow;
    }

    /** Рисует декоративные значки Canvas из того же каталога, измеряя только обычный текст. */
    static void paint(javafx.scene.canvas.GraphicsContext g, String logical, double x, double y) {
        List<Part> parts = parts(logical, false);
        if (parts.stream().noneMatch(Part::icon)) { g.fillText(logical, x, y); return; }
        double size = INLINE_SIZE; double width = 0;
        for (Part part : parts) width += part.icon() ? size : textWidth(part.text(), g.getFont());
        double start = switch (g.getTextAlign()) { case CENTER -> x - width / 2; case RIGHT -> x - width; default -> x; };
        g.save(); g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        for (Part part : parts) {
            if (part.icon()) { g.drawImage(image(part.text(), color(g.getFill())).orElseThrow(), start, y - size, size, size); start += size; }
            else { g.fillText(part.text(), start, y); start += textWidth(part.text(), g.getFont()); }
        }
        g.restore();
    }

    private static double textWidth(String value, javafx.scene.text.Font font) {
        Text text = new Text(value); text.setFont(font); return text.getLayoutBounds().getWidth();
    }

    /** Читает фактический цвет краски; совпадающий LINE_TODAY соответствует варианту WHATIF. */
    private static ColorToken color(javafx.scene.paint.Paint paint) {
        for (ColorToken token : List.of(ColorToken.ACCENT, ColorToken.WHATIF, ColorToken.EXPENSE,
                ColorToken.INCOME, ColorToken.TEXT_PRIMARY, ColorToken.TEXT_MUTED,
                ColorToken.WARN, ColorToken.TOOLTIP_TEXT, ColorToken.TEXT_PAST))
            if (javafx.scene.paint.Color.web(token.hex()).equals(paint)) return token;
        return null;
    }

    /** Меняет только графику существующего скина, сохраняя его обработчики и область нажатия. */
    public static void skin(Control control) {
        if (control.getProperties().putIfAbsent("cp.iconSkin", true) != null) return;
        Runnable update = () -> {
            if (control.getScene() == null) return;
            control.applyCss();
            var window = control.getScene().getWindow();
            var scale = new javafx.geometry.Dimension2D(window == null ? 1 : window.getRenderScaleX(),
                    window == null ? 1 : window.getRenderScaleY());
            boolean scaleChanged = !scale.equals(control.getProperties().put("cp.iconLayoutScale", scale));
            if (paintSkinGraphics(control) || scaleChanged) {
                // Применить новый CSS до повторного расчёта VirtualFlow: иначе он кеширует прежнюю ширину полосы.
                // Только изменённая графика запрашивает раскладку, чтобы hook не создавал бесконечных pulse.
                control.applyCss();
                // EndButton кеширует prefSize отдельно от VirtualFlow. После замены стрелки инвалидируется
                // именно его кеш, иначе размер, рассчитанный до появления peer, остаётся на прежнем DPI.
                for (String selector : List.of(".increment-arrow", ".decrement-arrow"))
                    for (Node arrow : control.lookupAll(selector))
                        if (inScrollBar(arrow) && arrow.getParent() != null) {
                            arrow.getParent().requestLayout();
                            if (arrow.getParent().getParent() != null) arrow.getParent().getParent().requestLayout();
                        }
                control.requestLayout();
            }
        };
        control.skinProperty().addListener((o, previous, next) -> Platform.runLater(update));
        control.sceneProperty().addListener((o, previous, next) -> { if (next != null) Platform.runLater(update); });
        if (control instanceof javafx.scene.control.TableView<?>) {
            // Завершение раскладки ловит позднее создание полос VirtualFlow; повторный проход идемпотентен.
            control.needsLayoutProperty().addListener((o, previous, next) -> {
                if (!next) Platform.runLater(update);
            });
        }
        if (control instanceof javafx.scene.control.CheckBox check)
            check.selectedProperty().addListener((o, previous, next) -> update.run());
        if (control instanceof javafx.scene.control.RadioButton radio)
            radio.selectedProperty().addListener((o, previous, next) -> update.run());
        update.run();
    }

    /** Оформляет уже созданные стрелки настоящего меню, комбобокса или спиннера. */
    static void skinGraphics(Node root) { paintSkinGraphics(root); }

    /** Сообщает о реально изменённой графике, чтобы только первый проход запрашивал новую раскладку. */
    private static boolean paintSkinGraphics(Node root) {
        boolean changed = replace(root, ".arrow", "\u25be", INLINE_SIZE);
        changed |= replace(root, ".increment-arrow", "\u2191", 8);
        changed |= replace(root, ".decrement-arrow", "\u25be", 8);
        changed |= replace(root, ".menu .arrow", "\u25b8", INLINE_SIZE);
        changed |= replace(root, ".scroll-bar:horizontal .increment-arrow", "\u25b6", 8);
        changed |= replace(root, ".scroll-bar:vertical .increment-arrow", "\u25be", 8);
        changed |= replace(root, ".scroll-bar:horizontal .decrement-arrow", "\u25c0", 8);
        changed |= replace(root, ".scroll-bar:vertical .decrement-arrow", "\u2191", 8);
        changed |= replace(root, ".menu-up-arrow", "\u2191", 8);
        changed |= replace(root, ".menu-down-arrow", "\u25be", 8);
        if (root instanceof javafx.scene.control.CheckBox check) mark(root, ".mark", "\u2713", check.isSelected());
        if (root instanceof javafx.scene.control.RadioButton radio) mark(root, ".dot", "\u25cf", radio.isSelected());
        return changed;
    }

    /** Использует общую стрелку вправо в существующей строке вложенного меню. */
    static void submenu(Node row) {
        for (Node arrow : row.lookupAll(".arrow")) arrow.getProperties().put("cp.arrowDirection", "\u25b8");
        replace(row, ".arrow", "\u25b8", INLINE_SIZE);
    }

    /** Оставляет выбор нативному пункту меню, заменяя лишь его отмеченный символ. */
    static void menuMark(Node row, boolean radio, boolean selected) {
        mark(row, ".check", radio ? "\u25cf" : "\u2713", selected);
        mark(row, ".radio", "\u25cf", selected);
    }

    private static void mark(Node root, String selector, String key, boolean selected) {
        replace(root, selector, key, INLINE_SIZE, ColorToken.ACCENT);
        for (Node node : root.lookupAll(selector)) {
            String style = (String) node.getProperties().get("cp.iconStyle");
            if (style != null) node.setStyle(style + (selected ? "" : "-fx-background-image: none;"));
        }
    }

    /** Возвращает PNG для фоновой графики Region без создания копии ресурса на диске. */
    static String imageCss(String key) {
        return imageCss(key, null);
    }

    /** Возвращает исходные байты общего цветового варианта для графики стандартного скина. */
    static String imageCss(String key, ColorToken color) {
        byte[] bytes = UiIcons.png(key, color).orElseThrow(() -> new IllegalArgumentException("icon: " + key));
        return "-fx-background-image: url('data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
                + "'); -fx-background-size: contain; -fx-background-repeat: no-repeat; -fx-background-position: center;";
    }

    private static boolean replace(Node root, String selector, String key, double size) {
        return replace(root, selector, key, size, ColorToken.TEXT_PRIMARY);
    }

    private static boolean replace(Node root, String selector, String key, double size, ColorToken color) {
        boolean changed = false;
        for (Node node : root.lookupAll(selector)) if (node instanceof Region pane) {
            // Общий селектор раскрытия не захватывает стрелки полосы с отдельными направлением и размером.
            if (size == INLINE_SIZE && inScrollBar(pane)) continue;
            if ((selector.equals(".increment-arrow") || selector.equals(".decrement-arrow")) && inScrollBar(pane)) continue;
            String chosen = pane.getProperties().getOrDefault("cp.arrowDirection", key).toString();
            if (chosen.equals(pane.getProperties().get("cp.icon"))) continue;
            double width = pane.prefWidth(-1), height = pane.prefHeight(-1);
            if (width <= 0) width = size;
            if (height <= 0) height = size;
            if (pane.getProperties().get("cp.iconSize") instanceof javafx.geometry.Dimension2D original) {
                width = original.getWidth(); height = original.getHeight();
            } else pane.getProperties().put("cp.iconSize", new javafx.geometry.Dimension2D(width, height));
            // Общая стрелка приложения занимает 16 px, независимо от маленькой формы стрелки Modena.
            if (size == INLINE_SIZE) { width = size; height = size; }
            pane.getProperties().put("cp.icon", chosen);
            String style = pane.getProperties().getOrDefault("cp.originalIconStyle", pane.getStyle()).toString();
            pane.getProperties().putIfAbsent("cp.originalIconStyle", style);
            // Modena оставляет светлый фон со смещением -1 px: он расширяет bounds даже у прозрачной заливки.
            // Общий PNG рисуется ровно внутри области стрелки, без унаследованных выступов и теней.
            style += ";-fx-shape: null; -fx-background-color: transparent; -fx-background-insets: 0;"
                    + "-fx-background-radius: 0; -fx-effect: null;"
                    + (inScrollBar(pane) ? "" : "-fx-padding: 0;") + imageCss(chosen, color);
            pane.getProperties().put("cp.iconStyle", style); pane.setStyle(style);
            // Полоса вычисляет размер стрелки из нативных insets с учётом DPI. Фиксированный prefSize
            // отменяет эту зависимость и оставляет EndButton с кешем, созданным до показа окна.
            if (!inScrollBar(pane)) {
                pane.setMinSize(width, height); pane.setPrefSize(width, height); pane.setMaxSize(width, height);
            }
            changed = true;
        }
        return changed;
    }

    /** Отличает стрелки полосы прокрутки от общих стрелок раскрытия приложения. */
    private static boolean inScrollBar(Node node) {
        for (Node ancestor = node; ancestor != null; ancestor = ancestor.getParent())
            if (ancestor instanceof javafx.scene.control.ScrollBar) return true;
        return false;
    }
}
